"""Run Q08 against an explicitly named disposable Android emulator and the packaged Windows host."""
import argparse
import ctypes as ct
from ctypes import wintypes as wt
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET


def wait_for(label, read, timeout=15):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        result = read()
        if result:
            return result
        time.sleep(0.17)
    raise RuntimeError("Timed out: " + label)


class Window:
    def __init__(self, pid, title, desktop_windows=None):
        self.pid, self.title = pid, title
        self.user = ct.WinDLL("user32", use_last_error=True)
        self.user.SetProcessDpiAwarenessContext(ct.c_void_p(-4))
        self.user.GetWindowThreadProcessId.argtypes = [wt.HWND, ct.POINTER(wt.DWORD)]
        self.user.GetWindowTextW.argtypes = [wt.HWND, wt.LPWSTR, ct.c_int]
        self.user.PostMessageW.argtypes = [wt.HWND, wt.UINT, wt.WPARAM, wt.LPARAM]
        self.user.GetClientRect.argtypes = [wt.HWND, ct.POINTER(wt.RECT)]
        self.user.IsWindowVisible.argtypes = [wt.HWND]
        self.user.IsWindow.argtypes = [wt.HWND]
        callback_type = ct.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)

        def find():
            handles = []

            def check(handle, unused):
                if (desktop_windows is not None or self.user.IsWindowVisible(handle)) and self.matches(handle):
                    handles.append(handle)
                return True

            if desktop_windows is None:
                self.user.EnumWindows(callback_type(check), 0)
            else:
                for handle in desktop_windows(): check(handle, 0)
            return handles[0] if len(handles) == 1 else None

        self.hwnd = wait_for("exact PID and scrcpy window title", find, 25)
        self.user.SetForegroundWindow.argtypes = [wt.HWND]
        self.user.SetForegroundWindow(self.hwnd)

    def matches(self, handle):
        pid, title = wt.DWORD(), ct.create_unicode_buffer(256)
        self.user.GetWindowThreadProcessId(handle, ct.byref(pid))
        self.user.GetWindowTextW(handle, title, len(title))
        return pid.value == self.pid and title.value == self.title

    def send(self, message, key=0, data=0):
        if not self.matches(self.hwnd):
            raise RuntimeError("Refusing to address a replaced window")
        if not self.user.PostMessageW(self.hwnd, message, key, data):
            raise ct.WinError(ct.get_last_error())

    def size(self):
        rect = wt.RECT()
        if not self.user.GetClientRect(self.hwnd, ct.byref(rect)):
            raise ct.WinError(ct.get_last_error())
        return rect.right, rect.bottom

    def client_pixel(self, position, video):
        width, height = self.size()
        scale = min(width / video[0], height / video[1])
        x = round((width - video[0] * scale) / 2 + position[0] * scale)
        y = round((height - video[1] * scale) / 2 + position[1] * scale)
        return x, y

    def click(self, position, video):
        self.click_pixel(*self.client_pixel(position, video))

    def move_pointer(self, x, y):
        width, height = self.size()
        if not (0 <= x < width and 0 <= y < height):
            raise RuntimeError("Fixture point outside mirror client area")
        # SDL polls the Win32 pointer between queued messages. Keep it over the same verified client pixel.
        self.user.ClientToScreen.argtypes = [wt.HWND, ct.POINTER(wt.POINT)]
        self.user.WindowFromPoint.argtypes = [wt.POINT]
        self.user.WindowFromPoint.restype = wt.HWND
        pixel = wt.POINT(x, y)
        self.user.ClientToScreen(self.hwnd, ct.byref(pixel))
        if self.user.WindowFromPoint(pixel) != self.hwnd:
            raise RuntimeError("Another window covers the fixture point; refusing input")
        self.user.SetCursorPos(pixel.x, pixel.y)
        return (y << 16) | x

    def click_pixel(self, x, y):
        point = self.move_pointer(x, y)
        time.sleep(0.15)
        self.send(0x0200, 0, point)
        time.sleep(0.04)
        self.send(0x0201, 1, point)
        time.sleep(0.06)
        self.send(0x0202, 0, point)

    def drag(self, start, end, video):
        x, y = self.client_pixel(start, video)
        dx, dy = self.client_pixel(end, video)
        point = self.move_pointer(x, y)
        self.send(0x0200, 0, point)
        time.sleep(0.15)
        self.send(0x0201, 1, point)
        time.sleep(0.08)
        try:
            for i in range(1, 13):
                point = self.move_pointer(round(x + (dx-x)*i/12), round(y + (dy-y)*i/12))
                self.send(0x0200, 1, point)
                time.sleep(0.04)
        finally:
            self.send(0x0202, 0, point)

    def key(self, vk, down):
        scan = self.user.MapVirtualKeyW(vk, 0)
        self.send(0x0100 if down else 0x0101, vk, 1 | (scan << 16) | (0 if down else 0xC0000000))

    def stroke(self, key):
        self.key(key, True)
        self.key(key, False)

    def resize(self, width, height):
        if not self.matches(self.hwnd):
            raise RuntimeError("Refusing to resize a replaced window")
        self.user.SetWindowPos.argtypes = [wt.HWND, wt.HWND, ct.c_int, ct.c_int, ct.c_int, ct.c_int, wt.UINT]
        if not self.user.SetWindowPos(self.hwnd, None, 0, 0, width, height, 0x16):
            raise ct.WinError(ct.get_last_error())

    def raise_fixture(self):
        if not self.matches(self.hwnd):
            raise RuntimeError("Mirror window has changed")
        self.user.SetWindowPos.argtypes = [wt.HWND, wt.HWND, ct.c_int, ct.c_int, ct.c_int, ct.c_int, wt.UINT]
        # The test addresses only its own SDL window. Keep every test pixel in the work area.
        area = wt.RECT()
        if not self.user.SystemParametersInfoW(0x30, 0, ct.byref(area), 0):
            raise ct.WinError(ct.get_last_error())
        self.user.ShowWindow(self.hwnd, 9)
        if not self.user.SetWindowPos(self.hwnd, wt.HWND(-1), area.left+12, area.top+12,
                                      510, min(800, area.bottom-area.top-24), 0x40):
            raise ct.WinError(ct.get_last_error())


class Fixture:
    package = "dev.rprobedesk.fixture"

    def __init__(self, adb, serial):
        self.prefix = [str(adb), "-s", serial]

    def run(self, *args, binary=False):
        result = subprocess.run(self.prefix + list(args), capture_output=True, check=True, timeout=20)
        return result.stdout if binary else result.stdout.decode("utf-8", "replace").strip()

    def sh(self, *args):
        return self.run("shell", *args)

    def start(self, seed_clipboard=True):
        self.sh("am", "force-stop", self.package)
        self.sh("am", "start", "-W", "-n", self.package + "/.TargetsActivity", "--ez", "qaSeedClipboard", str(seed_clipboard).lower())
        wait_for("fixture resumed", self.active)

    def active(self):
        info = self.sh("dumpsys", "activity", "activities")
        return any("ResumedActivity" in line and self.package + "/" in line for line in info.splitlines())

    def nodes(self):
        if not self.active():
            raise RuntimeError("Fixture is not in the foreground on the selected emulator")
        output = self.run("exec-out", "uiautomator", "dump", "/dev/tty")
        root = ET.fromstring(output[output.index("<?xml"):output.index("</hierarchy>") + len("</hierarchy>")])
        return [n for n in root.iter("node") if n.get("package") == self.package]

    def video(self):
        png = self.run("exec-out", "screencap", "-p", binary=True)
        assert png.startswith(b"\x89PNG\r\n\x1a\n")
        return struct.unpack(">II", png[16:24])

    @staticmethod
    def center(node):
        x, y, right, bottom = map(int, re.findall(r"\d+", node.attrib["bounds"]))
        return (x + right) / 2, (y + bottom) / 2

    def lines(self):
        return self.sh("logcat", "-d", "-v", "brief", "RProbeFixture:I", "*:S").splitlines()

    def touch_targets(self, window, rotation):
        self.sh("wm", self.rotate_command, "lock", str(rotation))
        video = wait_for("encoded display rotated", lambda: (v if ((v := self.video())[0] > v[1]) == bool(rotation) else None))
        nodes = self.nodes()
        time.sleep(1.2)
        for label in ["TL", "TR", "BL", "BR"]:
            node = next(n for n in nodes if n.get("text") == label and n.get("clickable") == "true")
            original = sum("touch=" + label in line for line in self.lines())
            print("Checking", label, node.get("bounds"), video, window.size(), flush=True)
            window.click(self.center(node), video)
            try:
                wait_for("fixture touch=" + label, lambda: sum("touch=" + label in line for line in self.lines()) > original, 5)
            except RuntimeError:
                print("Observed fixture events:", self.lines()[-5:], flush=True)
                if self.evidence:
                    from PIL import ImageGrab
                    ImageGrab.grab(window=window.hwnd).save(self.evidence / f"failure-{rotation}-{label}-mirror.png")
                    (self.evidence / f"failure-{rotation}-{label}-device.png").write_bytes(self.run("exec-out", "screencap", "-p", binary=True))
                raise
        print("Rotation", rotation, "video", video, "window", window.size(), "TL TR BL BR hit", flush=True)
        if self.evidence:
            from PIL import ImageGrab
            ImageGrab.grab(window=window.hwnd).save(self.evidence / ("mirror-landscape.png" if rotation else "mirror-portrait.png"))

    def letterbox(self, window):
        self.sh("wm", self.rotate_command, "lock", "0")
        video = wait_for("portrait restored", lambda: (v if (v := self.video())[1] > v[0] else None))
        node = next(n for n in self.nodes() if n.get("text") == "TL" and n.get("clickable") == "true")
        window.resize(880, 650)
        time.sleep(0.8)
        width, height = window.size()
        left = (width - height * video[0] / video[1]) / 2
        if left < 16:
            raise RuntimeError("Resized window has no side bars")
        before = sum("touch=" in line for line in self.lines())
        window.click_pixel(int(left / 2), round(self.center(node)[1] * height / video[1]))
        time.sleep(0.45)
        if sum("touch=" in line for line in self.lines()) != before:
            raise RuntimeError("Black border triggered a fixture target")
        window.click(self.center(node), video)
        wait_for("resized TL", lambda: sum("touch=" in line for line in self.lines()) > before)
        print("Resized client", window.size(), "side bar produced no fixture target event; content TL hit", flush=True)

    def editor(self):
        return next(n for n in self.nodes() if n.get("class") == "android.widget.EditText")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ["app-libs", "scrcpy", "adb", "fixture", "java"]:
        parser.add_argument("--" + option, required=True, type=Path)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--evidence-dir", type=Path)
    args = parser.parse_args()
    if os.name != "nt" or not 1 <= args.port <= 65535:
        raise SystemExit("Requires Windows and the port of a disposable local emulator")
    paths = [args.scrcpy, args.adb, args.fixture, args.java]
    if not all(p.is_file() for p in paths) or not list(args.app_libs.glob("app-desktop*.jar")):
        raise SystemExit("Missing built Windows app, fixture, scrcpy, adb, or JDK")
    serial = f"127.0.0.1:{args.port}"
    env = os.environ.copy()
    env["PATH"] = str(args.scrcpy.resolve().parent) + os.pathsep + env["PATH"]
    src = Path(__file__).with_name("MirrorLauncher.java").resolve()
    f = Fixture(args.adb, serial)
    f.evidence = args.evidence_dir
    subprocess.run([str(args.adb), "connect", serial], capture_output=True, check=True, timeout=15)
    if f.sh("getprop", "ro.kernel.qemu") != "1":
        raise SystemExit("Refusing to operate on a non-emulator")
    wm_help = subprocess.run(f.prefix + ["shell", "wm", "help"], capture_output=True, timeout=5)
    f.rotate_command = "set-user-rotation" if b"set-user-rotation [" in wm_help.stdout + wm_help.stderr else "user-rotation"
    was_installed = ("package:" + f.package) in f.sh("pm", "list", "packages", f.package).splitlines()
    old_rotation = f.sh("settings", "get", "system", "user_rotation")
    old_auto = f.sh("settings", "get", "system", "accelerometer_rotation")
    child = None
    window = None
    try:
        assert "Success" in f.run("install", "-r", str(args.fixture.resolve()))
        f.start()
        with tempfile.TemporaryFile(mode="w+", encoding="utf-8") as events:
            child = subprocess.Popen([str(args.java), "-Dcompose.application.resources.dir=" + str(args.app_libs.resolve() / "resources"), "-cp", str(args.app_libs.resolve() / "*"), str(src),
                                      str(args.scrcpy.resolve()), str(args.adb.resolve()), str(args.port)],
                                     stdin=subprocess.PIPE, stdout=events, stderr=events, env=env)

            def pid_line():
                events.seek(0)
                text = events.read()
                found = re.search(r"SCRCPY_PID=(\d+)", text)
                if child.poll() is not None and not found:
                    raise RuntimeError("Host bridge failed: " + text[-1800:])
                return int(found.group(1)) if found else None

            pid = wait_for("WindowsHost.startMirror", pid_line, 65)
            window = Window(pid, "RProbeDesk · " + serial)
            window.raise_fixture()
            print("Verified packaged WindowsHost -> scrcpy PID", pid, flush=True)
            if args.evidence_dir:
                args.evidence_dir.mkdir(parents=True, exist_ok=True)
                time.sleep(1)
            f.touch_targets(window, 0)
            f.touch_targets(window, 1)
            video = f.video()
            start = (round(video[0]*0.3), round(video[1]*0.25))
            end = (round(video[0]*0.6), round(video[1]*0.26))
            before = len([line for line in f.lines() if "drag=" in line])
            window.drag(start, end, video)
            def verified_drag():
                drags = [line for line in f.lines() if "drag=" in line]
                if len(drags) <= before: return False
                coords = list(map(int, drags[-1].split("drag=")[1].split(",")))
                return len(coords) == 4 and all(abs(a-b) < 12 for a,b in zip(coords, (*start,*end)))
            wait_for("drag start and end match the target coordinates", verified_drag)
            print("Verified drag", start, end, flush=True)
            f.letterbox(window)
            window.click(f.center(f.editor()), f.video())
            window.key(0x10, True)
            window.stroke(ord("A"))
            window.key(0x10, False)
            window.stroke(ord("1"))
            entered = wait_for("direct keyboard input", lambda: (s if (s := f.editor().get("text", "")).lower() == "a1" else None))
            print("Keyboard -> fixture:", entered, flush=True)
            for character in "测试":
                window.send(0x0102, ord(character), 1)
            time.sleep(0.35)
            expression = f.editor().get("text", "")
            print("Direct WM_CHAR Chinese:", "delivered" if "测试" in expression else "SDK keyboard does not deliver these characters", flush=True)
            # With --no-clipboard-autosync Ctrl+V uses only the disposable emulator clipboard seeded by the fixture.
            window.key(0x11, True)
            window.stroke(ord("V"))
            window.key(0x11, False)
            wait_for("Chinese paste from fixture clipboard", lambda: f.editor().get("text", "").endswith("测试"))
            print("Ctrl+V -> 测试 from the fixture clipboard", flush=True)
            # Right click maps to Android BACK in scrcpy and requires no global keyboard event.
            point = (window.size()[1] // 2 << 16) | (window.size()[0] // 2)
            for _ in range(2):
                if not f.active():
                    break
                window.send(0x0204, 2, point)
                window.send(0x0205, 0, point)
                time.sleep(0.5)
            wait_for("BACK finished fixture", lambda: not f.active())
            print("BACK reached fixture", flush=True)
            window.send(0x0112, 0xF060)  # Title-bar Close, WM_SYSCOMMAND / SC_CLOSE.
            wait_for("mirror window closed", lambda: not window.user.IsWindow(window.hwnd), 15)
            child.stdin.write(b"\n")
            child.stdin.flush()
            child.wait(timeout=15)
            events.seek(0)
            result = events.read()
            if child.returncode or "RETAINED_SESSION=yes" not in result:
                raise RuntimeError("Post-close check failed: " + result[-1800:])
            print("Window closed; same DeviceSession still usable", flush=True)
    finally:
        if child:
            if child.stdin and not child.stdin.closed:
                child.stdin.close()
            try:
                child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                child.terminate()
        if old_rotation.isdigit():
            f.sh("wm", f.rotate_command, "lock", old_rotation)
        if old_auto == "1":
            f.sh("wm", f.rotate_command, "free")
        if not was_installed:
            f.run("uninstall", f.package)


if __name__ == "__main__":
    main()

