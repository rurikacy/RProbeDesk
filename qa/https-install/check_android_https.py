"""Verify the release Android workspace via a loopback TLS origin and an already-paired disposable target."""

import argparse
import base64
from datetime import datetime, timedelta, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ipaddress
import json
from pathlib import Path
import ssl
import subprocess
import tempfile
import threading
from zipfile import ZipFile

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID


def certificates(tmp):
    start = datetime.now(timezone.utc)
    ca_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    leaf_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "RProbeDesk ephemeral QA CA")])
    def cert(name, key, authority):
        return (x509.CertificateBuilder().subject_name(name).issuer_name(ca_name).public_key(key.public_key())
                .serial_number(x509.random_serial_number()).not_valid_before(start - timedelta(days=1))
                .not_valid_after(start + timedelta(days=1)).add_extension(x509.BasicConstraints(authority, None), critical=True))
    ca = cert(ca_name, ca_key, True).sign(ca_key, hashes.SHA256())
    leaf = (cert(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "QA loopback")]), leaf_key, False)
            .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), False)
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), False).sign(ca_key, hashes.SHA256()))
    (tmp / "leaf.pem").write_bytes(leaf.public_bytes(serialization.Encoding.PEM))
    (tmp / "key.pem").write_bytes(leaf_key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                                         serialization.NoEncryption()))
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(tmp / "leaf.pem", tmp / "key.pem")
    return context, base64.b64encode(ca.public_bytes(serialization.Encoding.DER)).decode("ascii")


def test(args, tmp):
    def adb(serial, *cmd):
        return subprocess.check_output([args.adb, "-s", serial, *map(str, cmd)], timeout=150)
    host, target = "emulator-5588", "emulator-5586"
    for serial, avd in ((host, "RProbeDesk_Host35"), (target, "RProbeDesk_API35")):
        assert adb(serial, "emu", "avd", "name").decode().splitlines()[0] == avd
        assert adb(serial, "shell", "getprop", "ro.kernel.qemu").strip() == b"1"
    marker = "RProbeDesk-qa-target-5586"
    assert adb(target, "shell", "cat", "/data/local/tmp/rprobedesk-qa-target").decode().strip() == marker
    original = args.release.read_bytes()
    installed = adb(host, "shell", "pm", "path", "dev.rprobedesk").decode().strip().removeprefix("package:")
    assert adb(host, "exec-out", "cat", installed) == original, "Installed APK differs from release"
    with ZipFile(args.runner) as apk:
        fixture = apk.read("assets/fixture-debug.apk")
    assert b"Success" in adb(host, "install", "-r", "-t", args.runner)
    tls, encoded = certificates(tmp)
    requests = []

    class Fixture(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != "/fixture.apk":
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(len(fixture)))
            self.end_headers()
            self.wfile.write(fixture)
            requests.append({"path": self.path, "tls": self.connection.version(), "bytes": len(fixture)})
        def log_message(self, *_):
            pass

    with ThreadingHTTPServer(("127.0.0.1", 0), Fixture) as server:
        server.socket = tls.wrap_socket(server.socket, server_side=True)
        port = server.server_port
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        binding = "tcp:" + str(port)
        try:
            adb(host, "reverse", "--no-rebind", binding, binding)
            try:
                result = adb(host, "shell", "am", "instrument", "-w", "-r", "-e", "class",
                             "dev.rprobedesk.AndroidDeviceTest#httpsInstallFromReleaseWorkspace",
                             "-e", "targetIp", "10.0.2.2", "-e", "targetPort", str(args.target_port),
                             "-e", "qaMarker", marker, "-e", "tlsPort", str(port), "-e", "qaCaDer", encoded,
                             "dev.rprobedesk.test/androidx.test.runner.AndroidJUnitRunner").decode("utf-8")
            finally:
                adb(host, "reverse", "--remove", binding)
        finally:
            server.shutdown()
            thread.join()
    print(result)
    args.output.with_suffix(".txt").write_bytes(result.replace("\r\n", "\n").encode("utf-8"))
    assert "OK (1 test)" in result and "FAILURES!!!" not in result
    assert len(requests) == 1
    events = [s.partition("q05=")[2] for s in result.splitlines() if s.startswith("INSTRUMENTATION_STATUS: q05=")]
    assert len(events) == 4 and "SSL" in events[0] and "version 1" in events[1] and "restored" in events[2] and "Q01 " in events[3]
    data = dict(release_version="1.1.1", release_sha256=hashlib.sha256(original).hexdigest(),
                host=host, target=target, fixture_sha256=hashlib.sha256(fixture).hexdigest(), requests=requests, events=events)
    args.output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    if not __debug__:
        raise SystemExit("Enable Python assertions")
    p = argparse.ArgumentParser()
    for name in ("adb", "release", "runner"):
        p.add_argument("--" + name, type=Path, required=True)
    p.add_argument("--target-port", type=int, default=48372)
    p.add_argument("--output", type=Path, default=Path(__file__).with_name("android-https.json"))
    with tempfile.TemporaryDirectory(prefix="rpd-android-tls-") as scratch:
        test(p.parse_args(), Path(scratch))
