"""Verify Android-host pairing using an independent console on the two dedicated QA emulators."""
import argparse
from pathlib import Path
import re
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument("--adb",type=Path,required=True)
parser.add_argument("--code-file",type=Path,required=True)
parser.add_argument("--faults",action="store_true")
args = parser.parse_args()
target=[str(args.adb),"-s","emulator-5586"]
host=[str(args.adb),"-s","emulator-5594"]
pkg="dev.rprobedesk"
def read(prefix,command):
    return subprocess.check_output(prefix+["shell",command],timeout=12).decode("utf-8","replace").strip()
def send_file(name,value):
    subprocess.run(host+["shell","-T",f"run-as {pkg} sh -c 'cat >cache/{name}'"],input=value,check=True,timeout=8)

assert read(target,"cat /data/local/tmp/rprobedesk-qa-target")=="RProbeDesk-qa-target-5586"
assert "RProbeDesk_QA35" in subprocess.check_output(host+["emu","avd","name"],text=True)
code=args.code_file.read_bytes().strip()
args.code_file.unlink()
assert re.fullmatch(rb"\d{6}",code)
read(host,"run-as dev.rprobedesk rm -f cache/qa-wireless-answer cache/qa-wireless-confirm cache/qa-wireless-phase cache/qa-wireless-ready")
read(target,"cmd notification post -t RProbeDesk RProbeDesk-confirm waiting")
send_file("qa-wireless-code",code)
def tls_port():
    output=read(target,"dumpsys adb")
    port=re.search(r"(?m)^\s*tls_port=(\d+)\s*$",output)
    return int(port[1]) if port and 0<int(port[1])<65536 else None

def wait_for(label, probe):
    start=time.monotonic()
    while time.monotonic()-start<45:
        value=probe()
        if value: return value
        time.sleep(.3)
    raise RuntimeError(label)

def cycle():
    old=tls_port()
    assert old
    try:
        read(target,"settings put global adb_wifi_enabled 0")
        wait_for("Wireless debugging stayed enabled",lambda: not tls_port())
        read(target,"svc wifi disable")
        wait_for("Wi-Fi stayed up",lambda: "inet " not in read(target,"ip -4 addr show wlan0"))
    finally:
        read(target,"svc wifi enable")
        read(target,"cmd wifi connect-network AndroidWifi open")
        wait_for("QA Wi-Fi did not reconnect",lambda:"inet " in read(target,"ip -4 addr show wlan0"))
        read(target,"settings put global adb_wifi_enabled 1")
    new=wait_for("Wireless debugging did not restart",tls_port)
    subprocess.run(target+["emu","redir","del","tcp:48372"],capture_output=True,check=True)
    reply=subprocess.check_output(target+["emu","redir","add",f"tcp:48372:{new}"],text=True)
    assert "OK" in reply
    print("target Wi-Fi and wireless debugging recovered, TLS port",old,"->",new,flush=True)

with subprocess.Popen(host+["shell","am","instrument","-w","-e","qaWireless","emulator-5586","-e","qaFaults",str(args.faults).lower(),"-e","class",
        pkg+".AndroidWirelessTest",pkg+".test/androidx.test.runner.AndroidJUnitRunner"],
        stdout=subprocess.PIPE,stderr=subprocess.STDOUT) as test:
    deadline=time.monotonic()+60
    while time.monotonic()<deadline and test.poll() is None:
        if read(host,"run-as dev.rprobedesk sh -c 'if test -f cache/qa-wireless-confirm; then echo waiting; fi'")=="waiting":
            keys=read(target,"cmd notification list").splitlines()
            key=next((k for k in keys if "|RProbeDesk-confirm|" in k),None)
            if key and re.fullmatch(r"[\w.|:-]+",key):
                result=re.search(r"android\.text=String \((\d{8})\)",read(target,f"cmd notification get '{key}'"))
                if result:
                    send_file("qa-wireless-answer",result[1].encode("ascii"))
                    break
        time.sleep(.25)
    impaired=False
    try:
        if args.faults:
            def phase(name):
                return read(host,"run-as dev.rprobedesk sh -c 'test ! -f cache/qa-wireless-phase || cat cache/qa-wireless-phase'")==name
            wait_for("Target Wi-Fi cycle not requested",lambda:phase("wifi-cycle"))
            cycle()
            send_file("qa-wireless-ready",b"wifi-cycle")
            wait_for("Packet-loss trial not requested",lambda:phase("impaired"))
            assert "RProbeDesk_QA35" in subprocess.check_output(host+["emu","avd","name"],text=True)
            assert "dev wlan0" in read(host,"ip route get 10.0.2.2")
            assert "qdisc noqueue 0: root" in read(host,"tc qdisc show dev wlan0")
            read(host,"su 0 tc qdisc add dev wlan0 root netem delay 40ms loss 2%")
            impaired=True
            assert "netem" in read(host,"tc qdisc show dev wlan0")
            send_file("qa-wireless-ready",b"impaired")
        output=test.communicate(timeout=100)[0].decode("utf-8","replace")
    finally:
        if impaired:
            print(read(host,"tc -s qdisc show dev wlan0").split("qdisc clsact")[0].strip())
            read(host,"su 0 tc qdisc del dev wlan0 root")
    print(output)
    if test.returncode or "OK (1 test)" not in output: raise SystemExit(1)
