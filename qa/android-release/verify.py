"""Exercise first installation and signed in-place upgrade through the release app's UI."""

import argparse
import hashlib
import json
import re
import struct
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

SERIAL = "emulator-5590"
AVD = "RProbeDesk_WindowsQA"
PACKAGE = "dev.rprobedesk"
DEVICE_NAME = "QA-5590"
IP = "192.0.2.170"
PORT = "40321"
FAVORITE = "Release-QA-5590"
COMMAND = "echo qa5590"
DUMP = "/data/local/tmp/rprobedesk-release-ui.xml"


def run(*args, timeout=40):
    proc = subprocess.run([str(s) for s in args], capture_output=True, timeout=timeout)
    if proc.returncode:
        raise RuntimeError(proc.stderr.decode("utf-8", "replace") or proc.stdout.decode("utf-8", "replace"))
    return proc.stdout


def text(*args):
    return run(*args).decode("utf-8", "replace").strip()


class ReleaseCheck:
    def __init__(self, args):
        self.adb = (args.adb, "-s", SERIAL)
        self.output = args.output
        self.output.mkdir(parents=True, exist_ok=True)
        self.report = self.output / "results.json"
        self.data = json.loads(self.report.read_text(encoding="utf-8")) if self.report.exists() else {}

    def shell(self, *args):
        return text(*self.adb, "shell", *args)

    def save(self):
        tmp = self.report.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.data, indent=2, ensure_ascii=False)+"\n", encoding="utf-8")
        tmp.replace(self.report)

    def verify_avd(self):
        assert text(*self.adb, "emu", "avd", "name").splitlines()[0].strip() == AVD
        assert self.shell("getprop", "ro.build.version.sdk") == "30"
        assert self.shell("getprop", "ro.kernel.qemu") == "1"
        self.data.update(serial=SERIAL, avd=AVD, api=30)

    def installed(self):
        dump = self.shell("dumpsys", "package", PACKAGE)
        if not re.search(r"Package \[dev\.rprobedesk\]", dump):
            return None
        assert not re.search(r"(?:flags|pkgFlags)=\[[^\]]*\bDEBUGGABLE\b", dump)
        def field(name, pattern):
            value = re.search(r"^\s*"+name+"=("+pattern+")", dump, re.M)
            assert value, f"missing package manager field {name}"
            return value[1].strip()
        return dict(version=field("versionName", r"[^\r\n]+"), code=int(field("versionCode", r"\d+")),
                    first_install=field("firstInstallTime", r"[^\r\n]+"), last_update=field("lastUpdateTime", r"[^\r\n]+"))

    def start(self):
        self.shell("am", "force-stop", PACKAGE)
        started = self.shell("am", "start", "-W", "-n", PACKAGE+"/.MainActivity")
        assert "Status: ok" in started, started

    def tree(self):
        self.shell("uiautomator", "dump", DUMP)
        raw = run(*self.adb, "exec-out", "cat", DUMP)
        return [node.attrib for node in ET.fromstring(raw).iter("node") if node.get("package") == PACKAGE]

    @staticmethod
    def named(nodes, name):
        return [n for n in nodes if name in (n.get("text"), n.get("content-desc"))]

    def find(self, name, editable=False, timeout=20):
        limit = time.monotonic()+timeout
        while time.monotonic() < limit:
            nodes = self.tree()
            found = self.named(nodes, name)
            if editable:
                direct = [n for n in found if n["class"].endswith("EditText")]
                if direct:
                    return direct[0]
                descriptions = [n for n in found if n.get("content-desc") == name]
                if descriptions:
                    return descriptions[0]
                if found:
                    label_bounds = list(map(int, re.findall(r"-?\d+", found[0]["bounds"])))
                    fields = [n for n in nodes if n["class"].endswith("EditText") and
                              int(re.findall(r"-?\d+", n["bounds"])[1]) >= label_bounds[1]]
                    if fields:
                        return fields[0]
            elif found:
                return found[0]
            time.sleep(.2)
        shown = [n.get("text") or n.get("content-desc") for n in nodes]
        raise AssertionError(f"control {name!r} missing; visible app labels: {list(filter(None, shown))}")

    def tap(self, name, editable=False):
        node = self.find(name, editable)
        assert node["enabled"] == "true", f"disabled control {name!r}"
        x1,y1,x2,y2 = map(int, re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node["bounds"]).groups())
        assert x2>x1 and y2>y1
        self.shell("input", "tap", str((x1+x2)//2), str((y1+y2)//2))

    def type_field(self, label, value):
        assert re.fullmatch(r"[a-zA-Z0-9 .-]+", value)
        self.tap(label, editable=True)
        self.shell("input", "text", value.replace(" ", "%s"))
        time.sleep(.25)
        if re.search(r"\b(?:mInputShown|mIsInputViewShown)=true", self.shell("dumpsys","input_method")):
            self.shell("input", "keyevent", "KEYCODE_BACK")

    def screenshot(self, name):
        image = run(*self.adb, "exec-out", "screencap", "-p")
        assert image.startswith(b"\x89PNG\r\n\x1a\n")
        (self.output / name).write_bytes(image)

    def enter_records(self):
        self.start()
        if self.named(self.tree(), "切换至暗色"):
            self.tap("切换至暗色")
        self.find("切换至亮色")
        if not self.named(self.tree(), DEVICE_NAME):
            self.tap("添加")
            self.type_field("设备备注", DEVICE_NAME)
            self.type_field("IP 地址", IP)
            self.type_field("连接端口", PORT)
            self.tap("保存")
        self.find(DEVICE_NAME)
        self.tap("Shell")
        if not self.named(self.tree(), FAVORITE):
            self.type_field("设备 Shell 命令", COMMAND)
            self.tap("收藏命令")
            self.type_field("名称", FAVORITE)
            self.tap("保存")
        self.find(FAVORITE)

    def read_records(self, stage, light=False):
        self.start()
        if light:
            self.find("搜索设备")
            visible=self.tree()
            assert not self.named(visible,"切换至亮色") and not self.named(visible,"切换至暗色")
            raw=run(*self.adb,"exec-out","screencap")
            width,height,fmt=struct.unpack_from("<III",raw)
            assert fmt in (1,2) and width>0 and height>0
            pixels=raw[-width*height*4:]
            assert pixels.count(bytes((245,246,248,255))) > width*height*.05, "工作台不是浅色画布"
            assert pixels.count(bytes((255,255,255,255))) > width*height*.02, "未检测到白色工作面"
        else:
            self.find("切换至亮色")
        self.find(DEVICE_NAME)
        self.find(f"{IP}:{PORT}")
        self.screenshot(stage+"-device.png")
        self.tap("Shell")
        self.find(FAVORITE)
        self.tap("详情")
        self.find(COMMAND)
        self.screenshot(stage+"-favorite.png")
        return dict(theme="light" if light else "dark", device_name=DEVICE_NAME, endpoint=f"{IP}:{PORT}", favorite=FAVORITE, command=COMMAND)

    def install_stage(self, args, apk):
        self.verify_avd()
        meta = self.installed()
        if args.phase=="seed":
            if meta is None:
                self.data["observed_absent_before_first_install"]=True
                self.save()
                result=text(*self.adb,"install",args.apk)
                assert "Success" in result, result
                meta=self.installed()
                assert meta and meta["version"]=="1.0.0" and meta["code"]==1
                self.data["baseline"]={**meta,**apk}
                self.save()
                self.start()
                self.find("切换至暗色")
                self.find("尚未保存设备")
            else:
                assert self.data.get("baseline",{}).get("sha256")==apk["sha256"] and meta["first_install"]==self.data["baseline"]["first_install"], "已有应用。请在没有安装 RProbeDesk 的指定测试 AVD 中开始，或使用本轮原始结果目录恢复。"
            assert meta["version"]=="1.0.0" and meta["code"]==1
            self.enter_records()
            self.data["baseline_after_restart"]=self.read_records("baseline")
        else:
            previous,stage,old_code,new_code,new_name={
                "upgrade":("baseline","upgrade",1,2,"1.0.1"),
                "light":("upgrade","light_upgrade",2,3,"1.1.0"),
                "patch":("light_upgrade","patch_upgrade",3,4,"1.1.1"),
            }[args.phase]
            light=args.phase in ("light","patch")
            baseline=self.data[previous]
            assert self.data[previous+"_after_restart"]["favorite"]==FAVORITE
            assert meta and meta["first_install"]==baseline["first_install"]
            assert baseline["signer_sha256"]==apk["signer_sha256"], "升级 APK 签名不一致"
            if meta["code"]==old_code:
                result=text(*self.adb,"install","-r",args.apk)
                assert "Success" in result, result
            else:
                assert self.data.get(stage,{}).get("sha256")==apk["sha256"], "测试记录与已安装版本不符"
            updated=self.installed()
            assert updated["version"]==new_name and updated["code"]==new_code
            assert updated["first_install"]==baseline["first_install"]
            self.data[stage]={**updated,**apk}
            self.save()
            self.data[stage+"_after_restart"]=self.read_records(stage,light=light)
        self.save()


def apk_info(args):
    name,code={"seed":("1.0.0","1"),"upgrade":("1.0.1","2"),"light":("1.1.0","3"),"patch":("1.1.1","4")}[args.phase]
    badging=text(args.build_tools/"aapt2.exe","dump","badging",args.apk)
    assert f"package: name='{PACKAGE}' versionCode='{code}' versionName='{name}'" in badging
    assert "application-debuggable" not in badging
    cert=text(args.build_tools/"apksigner.bat","verify","--print-certs",args.apk)
    signer=re.search(r"Signer #1 certificate SHA-256 digest:\s*([a-fA-F0-9]{64})",cert)
    assert signer, "未能核对签名证书"
    with args.apk.open("rb") as stream:
        digest=hashlib.file_digest(stream,"sha256").hexdigest()
    return dict(sha256=digest,signer_sha256=signer[1].lower())


if __name__=="__main__":
    if not __debug__:
        raise SystemExit("Run without Python -O so device guards and assertions are active.")
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase",choices=("seed","upgrade","light","patch"))
    parser.add_argument("--adb",type=Path,required=True)
    parser.add_argument("--build-tools",type=Path,required=True)
    parser.add_argument("--apk",type=Path,required=True)
    parser.add_argument("--output",type=Path,default=Path(__file__).resolve().parent/"results")
    arguments=parser.parse_args()
    check=ReleaseCheck(arguments)
    check.install_stage(arguments,apk_info(arguments))
    print(check.report.resolve())
