"""Measure a process-cold start to accessible navigation and idle working set on a private desktop."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "desk-ui"))
from check_windows_bridge import check

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("exe", type=Path)
    parser.add_argument("--trial", action="store_true")
    args = parser.parse_args()
    if not args.trial:
        for _ in range(3):
            subprocess.run([sys.executable, __file__, str(args.exe), "--trial"], check=True)
    else:
        start = time.perf_counter()
        def observe(desktop, pid, hwnd, tree, act, wait):
            elapsed = round((time.perf_counter() - start) * 1000)
            time.sleep(3)
            # The GUI JVM may have a jpackage launcher parent and its own ADB children.
            command = "Get-CimInstance Win32_Process | Select ProcessId,ParentProcessId,Name,WorkingSetSize | ConvertTo-Json -Compress"
            census = json.loads(subprocess.check_output(["powershell.exe","-NoProfile","-Command",command]))
            by_pid = {row["ProcessId"]: row for row in census}
            root = by_pid[pid]
            parent = by_pid.get(root["ParentProcessId"])
            if parent and parent["Name"].casefold() == args.exe.name.casefold():
                root = parent
            members = {root["ProcessId"]}
            while True:
                grown = members | {row["ProcessId"] for row in census if row["ParentProcessId"] in members}
                if grown == members:
                    break
                members = grown
            working_set = sum(int(by_pid[item]["WorkingSetSize"]) for item in members)
            print(json.dumps(dict(ready_ms=elapsed, idle_working_set_bytes=working_set, processes=len(members))), flush=True)
        with tempfile.TemporaryDirectory() as profile:
            check(args.exe.resolve(), None, profile, inspect=observe)
