"""Generate signed, activity-free fixtures and run the packaged app against a trusted loopback HTTPS origin."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import threading


def run(args, env, timeout=60, quiet=False):
    result = subprocess.run(list(map(str, args)), env=env, capture_output=True, timeout=timeout)
    output = (result.stdout + result.stderr).decode("utf-8", "replace")
    if result.returncode:
        raise RuntimeError(output)
    if not quiet:
        print(output, end="", flush=True)
    return output


def invalid_downloads(args):
    html = b"<!doctype html><title>APK unavailable</title>"
    prefix = b"PK\x03\x04incomplete-apk"
    samples = {
        "/html.apk": ("text/html", len(html), html),
        "/truncated.apk": ("application/vnd.android.package-archive", 4096, prefix),
    }
    requests = []

    class Download(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path not in samples:
                self.send_error(404)
                return
            kind, declared, actual = samples[self.path]
            self.send_response(200)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(declared))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(actual)
            self.wfile.flush()
            self.close_connection = True
            requests.append(self.path)
            print(f"GET {self.path} declared={declared} sent={len(actual)}", flush=True)

        def log_message(self, *_):
            pass

    with tempfile.TemporaryDirectory(prefix="rprobe-invalid-apk-") as scratch, ThreadingHTTPServer(("127.0.0.1", 0), Download) as server:
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            run([args.jdk / "bin/java.exe", "-Djava.net.useSystemProxies=false", "-Dhttp.nonProxyHosts=127.*",
                 "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-cp", str(args.app_libs.resolve() / "*"),
                 Path(__file__).with_name("HttpsInstallCheck.java").resolve(), scratch, "invalid-downloads",
                 f"http://127.0.0.1:{server.server_port}", str(len(prefix))],
                dict(os.environ, LOCALAPPDATA=scratch))
            if requests != list(samples):
                raise AssertionError(f"Unexpected requests: {requests}")
        finally:
            server.shutdown()
            worker.join()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--app-libs", type=Path, required=True)
    p.add_argument("--sdk", type=Path)
    p.add_argument("--jdk", type=Path, required=True)
    p.add_argument("--port", type=int)
    p.add_argument("--invalid-downloads-only", action="store_true", help="HTML and truncated HTTP responses; no SDK or device needed")
    p.add_argument("--build-tools", default="35.0.0")
    p.add_argument("--platform", default="android-35")
    args = p.parse_args()
    if os.name != "nt":
        p.error("This check requires Windows")
    if args.invalid_downloads_only:
        return invalid_downloads(args)
    if args.sdk is None or args.port is None or not 1 <= args.port <= 65535:
        p.error("The signed install check requires --sdk and the --port of an authorized disposable loopback emulator")
    tools = args.sdk / "build-tools" / args.build_tools
    keytool = args.jdk / "bin/keytool.exe"
    signer = tools / "apksigner.bat"
    java = args.jdk / "bin/java.exe"
    android_jar = args.sdk / "platforms" / args.platform / "android.jar"
    for tool in (tools / "aapt2.exe", tools / "zipalign.exe", keytool, signer, java, android_jar):
        if not tool.is_file():
            raise FileNotFoundError(tool)
    pkg = "dev.rprobedesk.qa.tlsfixture.x" + secrets.token_hex(6)
    with tempfile.TemporaryDirectory(prefix="rprobe-https-") as scratch:
        temp = Path(scratch)
        local = temp / "profile"
        local.mkdir()
        env = dict(os.environ, RPROBE_QA_PASS=secrets.token_hex(16), ANDROID_HOME=str(args.sdk.resolve()),
                   LOCALAPPDATA=str(local), JAVA_HOME=str(args.jdk.resolve()))
        pass_args = ["-storepass:env", "RPROBE_QA_PASS"]
        for key in ("tls", "signer-a", "signer-b"):
            extra = ["-ext", "SAN=IP:127.0.0.1"] if key == "tls" else []
            run([keytool, "-genkeypair", "-alias", key, "-keystore", temp / (key + ".p12"), "-storetype", "PKCS12",
                 *pass_args, "-keyalg", "RSA", "-keysize", "2048", "-validity", "3", "-dname", "CN=RProbeDesk QA", *extra], env, quiet=True)
        run([keytool, "-exportcert", "-alias", "tls", "-keystore", temp / "tls.p12", *pass_args,
             "-file", temp / "loopback.cer"], env, quiet=True)
        run([keytool, "-importcert", "-noprompt", "-alias", "loopback", "-keystore", temp / "trust.p12",
             "-storetype", "PKCS12", *pass_args, "-file", temp / "loopback.cer"], env, quiet=True)
        signatures = []
        for version, name, key in ((1, "v1", "signer-a"), (2, "v2", "signer-a"), (3, "wrong", "signer-b")):
            manifest = temp / "AndroidManifest.xml"
            manifest.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{pkg}" '
                                f'android:versionCode="{version}" android:versionName="{version}.0">'
                                '<application android:hasCode="false"/></manifest>', encoding="utf-8")
            raw, aligned, apk = (temp / (name + s) for s in ("-raw.apk", "-aligned.apk", ".apk"))
            run([tools / "aapt2.exe", "link", "-o", raw, "-I", android_jar, "--min-sdk-version", "30",
                 "--target-sdk-version", "30", "--manifest", manifest], env, quiet=True)
            run([tools / "zipalign.exe", "-f", "4", raw, aligned], env, quiet=True)
            run([signer, "sign", "--ks", temp / (key + ".p12"), "--ks-pass", "env:RPROBE_QA_PASS",
                 "--out", apk, aligned], env, quiet=True)
            info = run([signer, "verify", "--print-certs", apk], env, quiet=True)
            signatures.append(re.search(r"certificate SHA-256 digest:\s*([a-fA-F0-9]{64})", info).group(1))
        if signatures[0] != signatures[1] or signatures[1] == signatures[2]:
            raise RuntimeError("Generated APK signer matrix does not match the scenario")
        print("Verified independent APKs:", pkg, "v1/v2 signer matches, v3 differs", flush=True)
        run([java, "-Djava.net.preferIPv4Stack=true", "-Dhttp.nonProxyHosts=localhost|127.*", "-Dfile.encoding=UTF-8",
             "-cp", str(args.app_libs.resolve() / "*"), Path(__file__).with_name("HttpsInstallCheck.java").resolve(),
             temp, args.port, pkg], env, timeout=200)


if __name__ == "__main__":
    main()

