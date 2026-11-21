"""Click two saved rows and paste the actual Windows panel output into a private Win32 Edit."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from types import SimpleNamespace

import win32con
import win32gui
import win32process

from check_windows_bridge import check

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "windows-mirror"))
from check_isolation import isolated_child, station_name


def run(args):
    exe, adb, jdk = (x.resolve(strict=True) for x in (args.exe, args.adb, args.jdk))
    private = os.environ["RPROBE_MIRROR_STATION"]
    assert private and private.casefold() != "winsta0" and station_name() == private
    targets = [(5586, "RProbeDesk_API35", "Q10-A-Target"), (5588, "RProbeDesk_Host35", "Q10-B-Host")]
    version = re.search(r"-Djpackage\.app-version=(\S+)", (exe.parent/"app/RProbeDesk.cfg").read_text()).group(1)
    assert re.fullmatch(r"\d+\.\d+\.\d+", version), version

    def adb_text(*command):
        return subprocess.check_output([adb, *command], timeout=15, encoding="utf-8").strip()

    original = [line.split()[0] for line in adb_text("devices").splitlines()[1:] if line.strip()]
    opened = []
    evidence = []
    try:
        scratch = Path(os.environ["LOCALAPPDATA"])
        settings = scratch/"RProbeDesk"
        settings.mkdir()
        prefs = scratch/"prefs"
        prefs.mkdir()
        (prefs/"adb.txt").write_text(str(adb), encoding="utf-8")
        classes = scratch/"classes"
        classes.mkdir()
        subprocess.run([jdk/"bin/javac.exe","--release","17","-d",classes,
                        Path(__file__).with_name("QaToolPreferences.java")], check=True)
        env = {"JAVA_TOOL_OPTIONS": "-Djavax.accessibility.assistive_technologies=com.sun.java.accessibility.AccessBridge "
               '-Djava.util.prefs.PreferencesFactory=QaToolPreferences "-Xbootclasspath/a:' + str(classes) + '"',
               "RPROBE_QA_PREFS": str(prefs)}
        properties = ["version=1"]
        expected = {}
        for console, avd, note in targets:
            serial = f"emulator-{console}"
            address = f"127.0.0.1:{console+1}"
            assert adb_text("-s",serial,"emu","avd","name").splitlines()[0].strip() == avd
            assert adb_text("-s",serial,"shell","getprop","ro.kernel.qemu") == "1"
            if address not in original:
                assert "connected" in adb_text("connect",address)
                opened.append(address)
            assert adb_text("-s",address,"shell","getprop","ro.boot.qemu.avd_name") == avd
            fields = {"设备":note,"地址":address}
            for label, prop in [("型号","ro.product.model"),("Android","ro.build.version.release"),
                                ("SDK","ro.build.version.sdk"),("ABI","ro.product.cpu.abilist")]:
                fields[label] = adb_text("-s",serial,"shell","getprop",prop)
                assert fields[label]
            fields["Android 用户"] = adb_text("-s",serial,"shell","am","get-current-user")
            expected[note] = fields
            properties.extend((f"device.{console}.name={note}", f"device.{console}.ip=127.0.0.1",
                               f"device.{console}.port={console+1}"))
        (settings/"desk.properties").write_text("\n".join(properties)+"\n", encoding="ascii")

        def exercise(desktop, pid, hwnd, tree, act, wait):
            assert station_name()==private and win32process.GetWindowThreadProcessId(hwnd)[1]==pid
            edit = win32gui.CreateWindow("EDIT","",win32con.WS_POPUP|win32con.ES_MULTILINE,
                                         0,0,320,240,0,0,0,None)
            def action(label,role):
                return next((ref for ref,n in tree() if n.name==label and n.role_us==role and n.action
                             and "enabled" in n.states_us.split(",")),None)
            try:
                for idx, (port,avd,note) in enumerate(targets):
                    if idx:
                        act(wait(lambda: action("设备","page tab")))
                    def connect_in_row():
                        nodes=tree()
                        for i,(_,n) in enumerate(nodes):
                            if n.name!=note: continue
                            row=[]
                            for ref,item in nodes[i+1:]:
                                if item.name in expected: break
                                row.append((ref,item))
                            if any(item.name==expected[note]["地址"] for _,item in row):
                                return next((ref for ref,item in row if item.name=="连接"
                                             and item.action and "enabled" in item.states_us.split(",")),None)
                        return None
                    act(wait(connect_in_row))
                    act(wait(lambda: action("复制设备信息","push button")))
                    wait(lambda: any("已复制设备信息" in (n.name,n.description) for _,n in tree()))
                    assert station_name()==private
                    win32gui.SetWindowText(edit,"")
                    win32gui.SendMessage(edit,win32con.WM_PASTE,0,0)
                    pasted=win32gui.GetWindowText(edit)
                    fields=dict(line.split("  ",1) for line in pasted.splitlines())
                    assert expected[note].items() <= fields.items(), (fields,expected[note])
                    evidence.append(dict(avd=avd, serial=f"emulator-{port}", pasted=fields,
                                         matched_independent_adb=True))
            finally:
                win32gui.DestroyWindow(edit)
        check(exe,None,str(scratch),environment=env,inspect=exercise)
        args.output.write_text(json.dumps(dict(version=version, private_window_station=True, results=evidence),
                                          indent=2,ensure_ascii=False)+"\n",encoding="utf-8")
    finally:
        for address in opened: adb_text("disconnect",address)


if __name__=="__main__":
    if not __debug__: raise SystemExit("Run without Python -O")
    parser=argparse.ArgumentParser()
    for name in ("exe","adb","jdk"): parser.add_argument("--"+name,type=Path,required=True)
    parser.add_argument("--output",type=Path,default=Path(__file__).with_name("windows-panel-copy.json"))
    opts=parser.parse_args()
    if "RPROBE_MIRROR_STATION" in os.environ: run(opts)
    else: isolated_child(SimpleNamespace(phase="control"),__file__)
