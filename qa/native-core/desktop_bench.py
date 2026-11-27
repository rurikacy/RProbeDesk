"""Measure a process-cold start to accessible navigation and idle working set on a private desktop."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import win32api
import win32con
import win32process

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
            proc = win32api.OpenProcess(win32con.PROCESS_QUERY_INFORMATION | win32con.PROCESS_VM_READ, False, pid)
            try:
                working_set = win32process.GetProcessMemoryInfo(proc)["WorkingSetSize"]
                print(json.dumps(dict(ready_ms=elapsed, idle_working_set_bytes=working_set)), flush=True)
            finally:
                proc.Close()
        with tempfile.TemporaryDirectory() as profile:
            check(args.exe.resolve(), None, profile, inspect=observe)
