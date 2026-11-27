"""Drive the installed tool choosers on a private desktop and keep Java Preferences in a temporary folder."""

import argparse
import ctypes
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

import win32con
import win32gui
import win32process

from check_windows_bridge import check


def child_controls(dialog):
    children = []
    win32gui.EnumChildWindows(dialog, lambda child, _: children.append(child) or True, None)
    return children


def choose_native(desktop, pid, path, wait):
    def owned_dialog():
        try:
            return next((w for w in desktop.EnumDesktopWindows()
                         if win32process.GetWindowThreadProcessId(w)[1] == pid
                         and win32gui.GetClassName(w) == "#32770" and any(win32gui.GetClassName(c) == "DirectUIHWND" for c in child_controls(w))), None)
        except win32gui.error as error:
            if error.winerror != 1400:  # Explorer recreates controls while opening the dialog.
                raise
            return None
    dialog = wait(owned_dialog)
    def filename_control():
        for edit in child_controls(dialog):
            if win32gui.GetClassName(edit) != "Edit" or not win32gui.IsWindowVisible(edit):
                continue
            if win32gui.GetDlgCtrlID(edit) == 1001 and win32gui.GetClassName(win32gui.GetParent(edit)) == "ComboBox":
                return edit
            parent = edit
            while parent and parent != dialog:
                if win32gui.GetDlgCtrlID(parent) in (1148, 1152):  # cmb13 / edt1, Win32 filename box
                    return edit
                parent = win32gui.GetParent(parent)
        return None
    edit = wait(filename_control)
    ok = win32gui.GetDlgItem(dialog, win32con.IDOK)
    assert win32gui.IsChild(dialog, edit) and win32gui.IsChild(dialog, ok)
    assert all(win32process.GetWindowThreadProcessId(w)[1] == pid for w in (dialog, edit, ok))
    send = ctypes.windll.user32.SendMessageW
    send.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_size_t, ctypes.c_void_p]
    send.restype = ctypes.c_ssize_t
    buf = ctypes.create_unicode_buffer(str(path))
    assert send(edit, win32con.WM_SETTEXT, 0, buf)
    readback = ctypes.create_unicode_buffer(32768)
    send(edit, win32con.WM_GETTEXT, len(readback), readback)
    assert Path(readback.value) == path
    win32gui.PostMessage(ok, win32con.BM_CLICK, 0, 0)
    wait(lambda: not win32gui.IsWindow(dialog) or not win32gui.IsWindowVisible(dialog))


def run(args, scratch):
    exe, adb, scrcpy, jdk = (p.resolve(strict=True) for p in (args.exe, args.adb, args.scrcpy, args.jdk))
    assert adb.name.lower() == "adb.exe" and scrcpy.name.lower() == "scrcpy.exe"
    cfg = (exe.parent / "app/RProbeDesk.cfg").read_text(encoding="utf-8")
    version = re.search(r"-Djpackage\.app-version=(\S+)", cfg).group(1)
    assert re.fullmatch(r"\d+\.\d+\.\d+", version), version
    prefs = scratch / "prefs"
    prefs.mkdir()
    settings = scratch / "RProbeDesk"
    settings.mkdir()
    (settings / "desk.properties").write_text("version=1\nselected=qa-tool\ndevice.qa-tool.ip=127.0.0.1\n"
                                               "device.qa-tool.port=1\ndevice.qa-tool.name=QA-tool-check\n", encoding="ascii")
    missing = scratch / "moved-tools/scrcpy.exe"
    (prefs / "scrcpy.txt").write_text(str(missing), encoding="utf-8")
    classes = scratch / "classes"
    classes.mkdir()
    subprocess.run([jdk / "bin/javac.exe", "--release", "17", "-d", classes,
                    Path(__file__).with_name("QaToolPreferences.java")], check=True)
    fake_dir = scratch / "old-tools"
    fake_dir.mkdir()
    old = fake_dir / "Tool.cs"
    old.write_text('using System; using System.Reflection; using System.IO; class Help { static int Main(string[] a) { '
                   'string n=Path.GetFileNameWithoutExtension(Assembly.GetExecutingAssembly().Location); '
                   'File.AppendAllText(Path.Combine(Environment.GetEnvironmentVariable("RPROBE_QA_PREFS"),"probes.txt"), '
                   'n+" "+String.Join(" ",a)+Environment.NewLine); '
                   'if (a.Length!=1 || a[0]!=(n=="adb"?"help":"--help")) return 2; '
                   'Console.WriteLine(n=="adb"?"Android Debug Bridge version 1.0.32":"scrcpy 1.18"); return 0; } }',
                   encoding="ascii")
    system = Path(os.environ["SystemRoot"])
    subprocess.run([system / "Microsoft.NET/Framework64/v4.0.30319/csc.exe", "/nologo", "/target:exe",
                    "/out:" + str(fake_dir / "adb.exe"), old], check=True)
    shutil.copyfile(fake_dir / "adb.exe", fake_dir / "scrcpy.exe")
    copied = scratch / "selected-tools"
    copied.mkdir()
    for library in adb.parent.glob("*.dll"):
        shutil.copyfile(library, copied / library.name)
    original_copy = copied / adb.name
    shutil.copyfile(adb, original_copy)
    # jpackage runs this alternate factory from the bootstrap class search path. No WindowsPreferences instance is created.
    java_options = ('-Djavax.accessibility.assistive_technologies=com.sun.java.accessibility.AccessBridge '
                    '-Djava.util.prefs.PreferencesFactory=QaToolPreferences "-Xbootclasspath/a:' + str(classes) + '"')
    isolated = {"JAVA_TOOL_OPTIONS": java_options, "RPROBE_QA_PREFS": str(prefs), "ANDROID_HOME": str(scratch),
                "ANDROID_SDK_ROOT": str(scratch), "PATH": str(system / "System32")}
    evidence = {"version": version, "app_sha256": hashlib.sha256(exe.read_bytes()).hexdigest(), "steps": []}

    def exercise(desktop, pid, hwnd, tree, act, wait):
        assert int((prefs / "factory-pid").read_text()) == pid
        def node(label, actionable=False):
            return next((ref for ref, item in tree() if (label == item.name or label == item.description)
                         and (not actionable or item.action)), None)
        def observe(label):
            wait(lambda: node(label))
            evidence["steps"].append(label)
            print(label, flush=True)
        def pick(label, path):
            act(wait(lambda: node(label, True)))
            choose_native(desktop, pid, path, wait)

        act(wait(lambda: node("设置", True)))
        observe("更改 adb.exe 路径")
        bundle = exe.parent / "app/resources/tools/scrcpy"
        observe(str(bundle / "adb.exe"))
        observe(str(bundle / "scrcpy.exe"))
        assert not (prefs / "adb.txt").exists()
        pick("更改 adb.exe 路径", system / "System32/where.exe")
        observe("请选择 adb.exe")
        assert not (prefs / "adb.txt").exists()
        pick("更改 adb.exe 路径", fake_dir / "adb.exe")
        observe("ADB 1.0.32 不支持按连接区分设备，请更新 Platform-Tools")
        assert not (prefs / "adb.txt").exists()
        pick("更改 adb.exe 路径", original_copy)
        observe("更改 adb.exe 路径")
        wait(lambda: (prefs / "adb.txt").is_file() and Path((prefs / "adb.txt").read_text(encoding="utf-8")).samefile(original_copy))
        shutil.copyfile(fake_dir / "adb.exe", original_copy)
        probes = prefs / "probes.txt"
        before = probes.read_text().splitlines()
        act(wait(lambda: node("设备", True)))
        act(wait(lambda: node("连接", True)))
        wait(lambda: probes.read_text().splitlines() == before + ["adb help"])
        act(wait(lambda: node("设置", True)))
        observe("已回退到内置 adb · ADB 1.0.32 不支持按连接区分设备，请更新 Platform-Tools")
        pick("更改 adb.exe 路径", adb)
        wait(lambda: Path((prefs / "adb.txt").read_text(encoding="utf-8")).samefile(adb))
        assert Path((prefs / "adb.txt").read_text(encoding="utf-8")).samefile(adb)
        observe("更改 scrcpy.exe 路径")
        assert not missing.exists() and (prefs / "scrcpy.txt").read_text(encoding="utf-8") == str(missing)
        pick("更改 scrcpy.exe 路径", fake_dir / "scrcpy.exe")
        observe("scrcpy 1.18 缺少镜像所需的选项，请选择较新版本")
        assert (prefs / "scrcpy.txt").read_text(encoding="utf-8") == str(missing)
        pick("更改 scrcpy.exe 路径", scrcpy)
        observe("更改 scrcpy.exe 路径")
        wait(lambda: (prefs / "scrcpy.txt").is_file() and Path((prefs / "scrcpy.txt").read_text(encoding="utf-8")) == scrcpy)
        assert set(probes.read_text().splitlines()) == {"adb help", "scrcpy --help"}
        evidence.update(native_selections=6, isolated_preferences=True, stale_path_replaced=True,
                        bundled_tools_without_sdk=True, replaced_adb_rechecked_on_connect=True,
                        fixture_invocations=probes.read_text().splitlines())

    check(exe, None, str(scratch), environment=isolated, inspect=exercise)
    args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    if not __debug__:
        raise SystemExit("Run with Python assertions enabled")
    p = argparse.ArgumentParser()
    for name in ("exe", "adb", "scrcpy", "jdk"):
        p.add_argument("--" + name, type=Path, required=True)
    p.add_argument("--output", type=Path, default=Path(__file__).with_name("windows-tools.json"))
    options = p.parse_args()
    with tempfile.TemporaryDirectory(prefix="rpd-tools-") as tmp:
        run(options, Path(tmp))
