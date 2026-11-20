"""Write 2,000 distinguishable main-buffer records on RProbeDesk's named QA target."""

import argparse
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--run", required=True, help="A new alphanumeric identifier for this run")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9-]{3,32}", args.run):
        parser.error("Use 3–32 letters, digits or hyphens for --run")
    adb = [args.adb, "-s", "emulator-5586"]
    avd = subprocess.check_output([*adb, "emu", "avd", "name"], text=True).splitlines()[0].strip()
    if avd != "RProbeDesk_API35":
        raise RuntimeError(f"Expected RProbeDesk_API35, got {avd}")

    script = f'''i=1
while [ "$i" -le 2000 ]; do
    log -p i -t RPDQ09 "{args.run} seq=$i" || exit 1
    i=$((i + 1))
done
'''
    start = time.monotonic()
    subprocess.run([*adb, "shell", "sh", "-s"], input=script.encode("ascii"), check=True, timeout=180)
    duration = time.monotonic() - start
    result = subprocess.check_output([*adb, "shell", "logcat", "-b", "main", "-d", "-s", "RPDQ09:I"], timeout=30)
    numbers = [int(n) for n in re.findall(rb"\b" + args.run.encode("ascii") + rb" seq=(\d+)", result)]
    if numbers != list(range(1, 2001)):
        raise RuntimeError(f"Expected exactly 1..2000 in the target buffer, read {len(numbers)} matches")
    print(f"{args.run}: target confirmed 2,000 distinct records in sequence; write interval {duration:.2f} s")


if __name__ == "__main__":
    main()
