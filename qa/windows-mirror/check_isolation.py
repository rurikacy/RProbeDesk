"""Measure a packaged Windows host against its dedicated Android 11 emulator."""
import argparse
import ctypes as ct
from ctypes import wintypes as wt
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import sys
import tempfile
import threading
import time
import uuid

from PIL import Image, ImageChops, ImageStat
import win32api
import win32clipboard
import win32con as con
import win32event
import win32gui as gui
import win32process
import win32security
import win32service as ws

from check_mirror import Fixture, Window, wait_for


def station_name():
    return ws.GetUserObjectInformation(ws.GetProcessWindowStation(), con.UOI_NAME)


def isolated_child(args, entry=None):
    # Obtain an independent logon ID for CreateWindowStation(NULL), without network access or elevation.
    name=station_name()
    if args.phase=="control":
        token = win32security.LogonUser("RProbeDesk-Local-QA", ".", uuid.uuid4().hex,
                                        con.LOGON32_LOGON_NEW_CREDENTIALS, con.LOGON32_PROVIDER_WINNT50)
        win32security.ImpersonateLoggedOnUser(token)
        try:
            station = ws.CreateWindowStation(None, 1, con.MAXIMUM_ALLOWED, None)  # CWF_CREATE_ONLY
        finally:
            win32security.RevertToSelf()
            token.Close()
        name = ws.GetUserObjectInformation(station, con.UOI_NAME)
        assert name != station_name() and name.casefold() != "winsta0"
        station.SetProcessWindowStation()
    deskname="RProbeMirror-"+uuid.uuid4().hex
    desk = ws.CreateDesktop(deskname, 0, con.GENERIC_ALL, None)
    desk.SetThreadDesktop()
    start = win32process.STARTUPINFO()
    start.lpDesktop = name + "\\"+deskname
    with tempfile.TemporaryDirectory() as tmp:
        env = {**os.environ, "LOCALAPPDATA": tmp, "RPROBE_MIRROR_STATION": name, "RPROBE_MIRROR_DESKTOP":deskname}
        command = [sys.executable, "-B", "-X", "utf8", str(Path(entry or __file__).resolve()), *sys.argv[1:]]
        child, _, _, _ = win32process.CreateProcess(None, subprocess.list2cmdline(command), None, None, False, 0,
                                                    env, os.getcwd(), start)
        try:
            assert win32event.WaitForSingleObject(child, 160_000) == win32event.WAIT_OBJECT_0
            assert win32process.GetExitCodeProcess(child) == 0, "查看隔离测试日志"
        finally:
            child.Close()


def mirror_image(window):
    cw, ch = window.size()
    if min(cw,ch)<2:
        return None
    # Print only this verified HWND's client into our own bitmap. No user desktop pixels are captured.
    import win32ui
    dc = gui.GetDC(window.hwnd)
    src = win32ui.CreateDCFromHandle(dc)
    mem = src.CreateCompatibleDC()
    bmp = win32ui.CreateBitmap()
    bmp.CreateCompatibleBitmap(src,cw,ch)
    old = mem.SelectObject(bmp)
    try:
        print_window=ct.windll.user32.PrintWindow
        print_window.argtypes=[wt.HWND,wt.HDC,wt.UINT]
        if not print_window(window.hwnd,mem.GetSafeHdc(),3): return None
        return Image.frombytes("RGB",(cw,ch),bmp.GetBitmapBits(True),"raw","BGRX")
    finally:
        mem.SelectObject(old)
        gui.DeleteObject(bmp.GetHandle())
        mem.DeleteDC()
        del src
        gui.ReleaseDC(window.hwnd,dc)


def measure_motion(window, fixture):
    fixture.sh("am", "start", "-S", "-W", "-n", fixture.package+"/.TargetsActivity", "--ez", "qaMotion", "true")
    time.sleep(1)
    start = last_change = time.perf_counter()
    previous = None
    changes = samples = 0
    longest = 0
    while time.perf_counter()-start < 8:
        image = mirror_image(window)
        if image is None: raise RuntimeError("Mirror surface unavailable during video sampling")
        sample = image.getpixel((image.width//2, image.height//2))[0]
        now = time.perf_counter()
        if previous is not None and abs(sample-previous) > 3:
            changes += 1
            longest = max(longest, now-last_change)
            last_change = now
        previous = sample
        samples += 1
        time.sleep(.005)
    duration = time.perf_counter()-start
    longest = max(longest, time.perf_counter()-last_change)
    print("motion observations", samples, "changes", changes, "seconds", duration, "longest", longest, flush=True)
    if not changes: raise RuntimeError("No changing frame reached the product mirror window")
    return dict(seconds=round(duration,2), observations=samples, changed_frames=changes,
                observed_changes_per_second=round(changes/duration,1), longest_unchanged_ms=round(longest*1000))


def capture(window, reference, output):
    # Compare the real window's client image with the device image. Reject a blank or unrelated frame.
    reference = reference.convert("L")
    reference.thumbnail((96, 96))
    diagnostic = None
    def frame():
        nonlocal diagnostic
        image = mirror_image(window)
        if image is None: return None
        cw, ch = image.size
        scale = min(cw/reference.width, ch/reference.height)
        w, h = round(reference.width*scale), round(reference.height*scale)
        x = (cw-w)//2
        y = (ch-h)//2
        small = image.crop((x,y,x+w,y+h)).convert("L").resize(reference.size, Image.Resampling.LANCZOS)
        error = ImageStat.Stat(ImageChops.difference(small, reference)).mean[0]
        if diagnostic is None:
            diagnostic=(window.size(),gui.IsWindowVisible(window.hwnd),round(ImageStat.Stat(small).stddev[0],2), round(error,2))
            print("initial render capture", diagnostic,flush=True)
        if ImageStat.Stat(small).stddev[0] > 18 and error < 21:
            image.save(output)
            return round(error, 2)
        return None
    return wait_for("recognizable scrcpy frame", frame, 12)


def run_result(args):
    import ctypes as ct
    private = os.environ["RPROBE_MIRROR_STATION"]
    assert station_name() == private
    assert args.phase=="render" or private.casefold()!="winsta0"
    assert ws.GetUserObjectInformation(ws.GetThreadDesktop(win32api.GetCurrentThreadId()),con.UOI_NAME)==os.environ["RPROBE_MIRROR_DESKTOP"]
    out = args.evidence_dir.resolve()
    serial = f"127.0.0.1:{args.port}"
    assert "RProbeDesk_WindowsQA" in subprocess.check_output([args.adb,"-s","emulator-5590","emu","avd","name"], text=True)
    subprocess.run([args.adb,"connect",serial], capture_output=True, check=True, timeout=10)
    fixture = Fixture(args.adb, serial)
    assert fixture.sh("getprop","ro.kernel.qemu") == "1" and fixture.sh("getprop","ro.build.version.sdk") == "30"
    assert fixture.sh("cat","/data/local/tmp/rprobedesk-qa-target") == "RProbeDesk-qa-target-5590"
    sizes = re.findall(r"(?:Physical|Override) size:\s*(\d+)x(\d+)", fixture.sh("wm","size"))
    if not sizes: raise RuntimeError("Cannot read target display size")
    portrait_size = tuple(sorted(map(int, sizes[-1])))
    old_auto = fixture.sh("settings","get","system","accelerometer_rotation")
    old_rotation = fixture.sh("settings","get","system","user_rotation")
    installed = "package:"+fixture.package in fixture.sh("pm","list","packages",fixture.package).splitlines()
    wm = subprocess.run(fixture.prefix + ["shell","wm","help"],capture_output=True).stdout
    rotate = "set-user-rotation" if b"set-user-rotation [" in wm else "user-rotation"
    child = window = None
    config=(args.app_libs/"RProbeDesk.cfg").read_text(encoding="utf-8")
    version=re.search(r"-Djpackage\.app-version=([^\s]+)",config).group(1)
    results = dict(version=version, target=serial)
    try:
        assert "Success" in fixture.run("install","-r",str(args.fixture.resolve()))
        fixture.sh("wm",rotate,"lock","0")
        fixture.start(seed_clipboard=False)
        env = dict(os.environ)
        env["PATH"] = str(args.adb.resolve().parent)+os.pathsep+str(args.scrcpy.resolve().parent)+os.pathsep+env["PATH"]
        env["SDL_RENDER_DRIVER"] = "software"
        src = Path(__file__).with_name("MirrorLauncher.java").resolve()
        child = subprocess.Popen([args.java,"-Dcompose.application.resources.dir="+str(args.app_libs.resolve()/"resources"),"-cp",str(args.app_libs.resolve()/"*"),str(src),str(args.scrcpy.resolve()),
                                  str(args.adb.resolve()),str(args.port),str(out)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                 stderr=subprocess.STDOUT, env=env, text=True, encoding="utf-8", errors="replace")
        lines = queue.Queue()
        def read():
            for line in child.stdout:
                print(line.rstrip(), flush=True)
                lines.put(line.strip())
        threading.Thread(target=read, daemon=True).start()
        def reply(prefix):
            limit = time.monotonic()+45
            while time.monotonic()<limit:
                try:
                    line=lines.get(timeout=.3)
                except queue.Empty:
                    if child.poll() is not None: raise RuntimeError("MirrorLauncher exited")
                    continue
                if line.startswith(prefix): return line[len(prefix):]
            raise RuntimeError("Missing response "+prefix)
        def command(value):
            child.stdin.write(value+"\n")
            child.stdin.flush()
        reply("FIXTURE_SESSION=ready")
        def screenshot(rotation):
            command(rotation)
            assert reply("PNG=")==rotation
            image=Image.open(out/f"target-{rotation}.png")
            image.load()
            assert image.format=="PNG" and ImageStat.Stat(image.convert("L")).stddev[0]>18
            results[rotation+"_png"]=image.size
            return image
        portrait=screenshot("portrait")
        def open_window(base, name):
            command("open")
            since=int(reply("START_MILLIS="))
            p=int(reply("SCRCPY_PID="))
            desktop=ws.GetThreadDesktop(win32api.GetCurrentThreadId())
            def windows():
                try: return [int(h) for h in desktop.EnumDesktopWindows()]
                except gui.error: return []
            try:
                # SDL creates a hidden bootstrap window before the video surface is visible.
                view=Window(p,"RProbeDesk · "+serial,
                            (lambda: [h for h in windows() if gui.IsWindowVisible(h)]) if args.phase=="render" else windows)
            except RuntimeError:
                print("private desktop windows",[(gui.GetClassName(h),win32process.GetWindowThreadProcessId(h)[1],
                      gui.GetWindowText(h)) for h in windows()], flush=True)
                raise
            print("SDL client",view.size(),"visible",gui.IsWindowVisible(view.hwnd),flush=True)
            if not gui.IsWindowVisible(view.hwnd):
                view.resize(500,850)
                gui.ShowWindow(view.hwnd,con.SW_SHOWNOACTIVATE)
            if args.phase=="render":
                error=capture(view,base,out/f"{name}.png")
                results[name]={"first_verified_frame_ms":round(time.time_ns()/1_000_000)-since,"mean_gray_error":error}
            return view
        window=open_window(portrait,"first-frame")
        fixture.sh("wm",rotate,"lock","1")
        wait_for("landscape",lambda: (d:=fixture.video())[0]>d[1])
        # The display changes dimensions before Android finishes laying out the rotated activity.
        wait_for("rotated fixture content", lambda: any(n.get("text")=="TL" for n in fixture.nodes()))
        landscape=screenshot("landscape")
        if args.phase=="render": capture(window,landscape,out/"landscape-frame.png")
        assert portrait.size == portrait_size and landscape.size == portrait_size[::-1]
        fixture.sh("wm",rotate,"lock","0")
        wait_for("portrait",lambda: (d:=fixture.video())[1]>d[0])
        editor=fixture.editor()
        ex,ey=fixture.center(editor)
        fixture.sh("input","tap",str(round(ex)),str(round(ey)))
        assert fixture.editor().get("text","") in ("","A1 / 测试")
        window.stroke(ord("7"))
        wait_for("mirror keyboard delivery",lambda: fixture.editor().get("text")=="7")
        expected="7"
        if args.phase=="control":
            # A clipboard lives in a window station. Never open the interactive (user) clipboard.
            assert station_name()==private and private.casefold()!="winsta0"
            owner=gui.CreateWindow("STATIC","",con.WS_POPUP,0,0,1,1,0,0,0,None)
            win32clipboard.OpenClipboard(owner)
            try:
                win32clipboard.EmptyClipboard()
                win32clipboard.SetClipboardText("主控验收",con.CF_UNICODETEXT)
            finally: win32clipboard.CloseClipboard()
            window.key(0x12,True)
            window.stroke(ord("V"))
            window.key(0x12,False)
            expected+="主控验收"
            wait_for("host MOD+V",lambda: fixture.editor().get("text")==expected,7)
            gui.DestroyWindow(owner)
            results["host_to_android"]=expected
        # This station is not an input desktop. Dispatch the key to its verified Win32 keyboard focus.
        local=gui.CreateWindow("EDIT","",con.WS_OVERLAPPEDWINDOW|con.WS_VISIBLE,20,20,300,160,0,0,0,None)
        thread,_=win32process.GetWindowThreadProcessId(window.hwnd)
        attach=ct.windll.user32.AttachThreadInput
        assert attach(win32api.GetCurrentThreadId(),thread,True)
        try:
            gui.SetActiveWindow(window.hwnd)
            gui.SetFocus(window.hwnd)
            assert gui.GetFocus()==window.hwnd
            gui.SetActiveWindow(local)
            gui.SetFocus(local)
            focused=gui.GetFocus()
            assert focused==local and focused!=window.hwnd
            gui.PostMessage(focused,con.WM_KEYDOWN,ord("8"),1)
            gui.PostMessage(focused,con.WM_KEYUP,ord("8"),0xc0000001)
            wait_for("local edit key",lambda: (gui.PumpWaitingMessages() or gui.GetWindowText(local)=="8"))
            assert fixture.editor().get("text")==expected
            results["unfocused_input"]={"local":"8","target":expected}
        finally:
            attach(win32api.GetCurrentThreadId(),thread,False)
            gui.DestroyWindow(local)
        window.send(con.WM_CLOSE)
        command("closed")
        assert reply("RETAINED_SESSION=")=="yes"
        fixture.start(seed_clipboard=False)
        window=open_window(screenshot("portrait"),"reopened-frame")
        if args.phase=="render": results["motion"] = measure_motion(window, fixture)
        window.send(con.WM_CLOSE)
        command("closed")
        assert reply("RETAINED_SESSION=")=="yes"
        command("done")
        assert child.wait(timeout=12)==0
        if args.phase == "render":
            # The private desktop capture rate can be lower than the client's presentation rate.
            # Use the same shipped client and options for its independent rendered-frame counters.
            stats = subprocess.run([str(args.scrcpy.resolve()), "--serial="+serial, "--no-clipboard-autosync",
                                    "--no-audio", "--print-fps", "--time-limit=9"],
                                   env={**env, "ADB":str(args.adb.resolve()),
                                        "SCRCPY_SERVER_PATH":str(args.scrcpy.resolve().parent/"scrcpy-server")},
                                   capture_output=True, timeout=24, text=True, errors="replace")
            rates = list(map(int, re.findall(r"(\d+) fps\b", stats.stdout+stats.stderr)))
            assert stats.returncode == 0 and len(rates) >= 5 and max(rates) > 0, (stats.returncode, rates)
            results["client_fps_samples"] = rates
        results["session_after_each_close"]=True
        (out/f"mirror-{args.phase}.json").write_text(json.dumps(results,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    finally:
        if window and gui.IsWindow(window.hwnd): window.send(con.WM_CLOSE)
        if child and child.poll() is None:
            child.stdin.close()
            try: child.wait(timeout=8)
            except subprocess.TimeoutExpired: child.terminate()
        if old_rotation.isdigit(): fixture.sh("wm",rotate,"lock",old_rotation)
        if old_auto=="1": fixture.sh("wm",rotate,"free")
        if not installed: fixture.run("uninstall",fixture.package)


if __name__=="__main__":
    if not __debug__: raise SystemExit("Run without Python -O")
    parser=argparse.ArgumentParser()
    parser.add_argument("--phase", choices=("render","control"), default="render")
    parser.add_argument("--port", type=int, default=5591)
    for option in ("app-libs","scrcpy","adb","java","fixture","evidence-dir"):
        parser.add_argument("--"+option,type=Path,required=True)
    opts=parser.parse_args()
    opts.evidence_dir.mkdir(parents=True,exist_ok=True)
    if "RPROBE_MIRROR_STATION" in os.environ:
        import contextlib, traceback
        with (opts.evidence_dir / "isolation-run.log").open("w",encoding="utf-8") as log, contextlib.redirect_stdout(log):
            try: run_result(opts)
            except Exception: traceback.print_exc(file=log); raise
    else:
        isolated_child(opts)
