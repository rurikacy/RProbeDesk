"""Follow the real TalkBack touch-exploration path on the dedicated API 30 release fixture."""

import argparse
import io
import json
import re
import subprocess
import time
from pathlib import Path

from PIL import Image, ImageChops


def check(adb_path, output):
    adb = [str(adb_path), "-s", "emulator-5590"]

    def run(*args):
        return subprocess.check_output([*adb, *args], timeout=12)

    def lines(*args):
        return run(*args).decode("utf-8", "replace").strip()

    assert lines("emu", "avd", "name").splitlines()[0].strip() == "RProbeDesk_WindowsQA"
    assert lines("shell", "getprop", "ro.kernel.qemu") == "1"
    assert lines("shell", "getprop", "ro.build.version.sdk") == "30"
    state = lines("shell", "dumpsys", "accessibility")
    assert "touchExplorationEnabled=true" in state and "FEEDBACK_SPOKEN" in state
    assert "com.android.talkback/com.google.android.marvin.talkback.TalkBackService" in state
    pid = lines("shell", "pidof", "com.android.talkback")
    assert pid.isdigit()
    stamp = lines("shell", "date", "+%s.%N")
    assert re.fullmatch(r"\d+\.\d+", stamp)
    output.mkdir(parents=True, exist_ok=True)
    run("shell", "am", "force-stop", "dev.rprobedesk")
    assert "Activity: dev.rprobedesk/.MainActivity" in lines("shell", "am", "start", "-W", "-n", "dev.rprobedesk/.MainActivity")
    time.sleep(1)

    def finger(x, y, down):
        assert lines("emu", "event", "mouse", str(x), str(y), "0", str(int(down))) == "OK"

    def focus(name, x, y):
        finger(x, y, True)
        time.sleep(.25)
        finger(x, y, False)
        time.sleep(.7)
        png = run("exec-out", "screencap", "-p")
        image = Image.open(io.BytesIO(png)).convert("RGB")
        assert image.size == (1080, 2280), "此验收使用固定 API30 AVD 的原始屏幕"
        red, green, blue = image.split()
        mask = ImageChops.multiply(red.point(lambda v: 255 if v < 125 else 0),
                                   green.point(lambda v: 255 if 130 <= v <= 220 else 0))
        rect = ImageChops.multiply(mask, blue.point(lambda v: 255 if v < 105 else 0)).getbbox()
        assert rect and rect[0] < x < rect[2] and rect[1] < y < rect[3], (name, rect)
        (output / f"talkback-{name}.png").write_bytes(png)
        return rect

    def activate(x, y):
        for down in (True, False, True, False):
            finger(x, y, down)
        time.sleep(.8)

    shell = focus("shell-focus", 150, 359)
    activate(150, 359)
    detail = focus("favorite-focus", 278, 1162)
    activate(278, 1162)
    command = focus("command-read", 164, 1254)
    time.sleep(3)
    events = lines("logcat", "-b", "events", "-d", "-T", stamp, "-s", "tts_speak_success:I")
    success = [line.strip() for line in events.splitlines()
               if re.search(r"\[com\.reecedunn\.espeak,\d+," + pid + r",[1-9]\d*,", line)]
    assert success, "未观察到由 TalkBack 发起的非空 TTS 成功事件"
    version = re.search(r"versionName=([^\s]+)", lines("shell", "dumpsys", "package", "dev.rprobedesk")).group(1)
    record = dict(app_version=version, android_api=30, touch_exploration=True, screen_reader="TalkBack 2021-04-23",
                  engine="eSpeak NG 1.52.0", shell_focus=shell, detail_focus=detail, command_focus=command,
                  tts_events=success)
    (output / "talkback.json").write_text(json.dumps(record, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")


if __name__ == "__main__":
    if not __debug__:
        raise SystemExit("请使用启用断言的 Python")
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parent)
    args = parser.parse_args()
    check(args.adb, args.output)
