"""Read a separate packaged process with the Windows Java Access Bridge on a private desktop."""

import argparse
import ctypes as c
import json
import os
import re
import tempfile
import time
import uuid
from pathlib import Path

import win32api
import win32con
import win32event
import win32gui
import win32job
import win32process
import win32service


class Context(c.Structure):
    _fields_ = [(n, c.c_wchar * 1024) for n in ("name","description")]
    _fields_ += [(n, c.c_wchar * 256) for n in ("role","role_us","states","states_us")]
    _fields_ += [(n, c.c_int) for n in ("index","children","x","y","width","height",
                                         "component","action","selection","text","interfaces")]


class Action(c.Structure):
    _fields_ = [("name", c.c_wchar * 256)]


class Available(c.Structure):
    _fields_ = [("count", c.c_int), ("items", Action * 256)]


class Requested(c.Structure):
    _fields_ = [("count", c.c_int), ("items", Action * 32)]


def check(exe, output, scratch, *, environment=None, inspect=None):
    binary = exe.parent / "runtime" / "bin" / "windowsaccessbridge-64.dll"
    assert binary.is_file() and "jdk.accessibility" in (exe.parent / "runtime" / "release").read_text()
    name = "RProbeDesk-Q11-" + uuid.uuid4().hex
    desktop = win32service.CreateDesktop(name, 0, win32con.GENERIC_ALL, None)
    desktop.SetThreadDesktop()
    startup = win32process.STARTUPINFO()
    station = win32service.GetUserObjectInformation(win32service.GetProcessWindowStation(), win32con.UOI_NAME)
    startup.lpDesktop = station + "\\" + name
    env = {**os.environ, "LOCALAPPDATA": scratch,
           "JAVA_TOOL_OPTIONS": "-Djavax.accessibility.assistive_technologies=com.sun.java.accessibility.AccessBridge"}
    env.update(environment or {})
    # Some jpackage launchers create a child process to host the window.
    job = win32job.CreateJobObject(None, name)
    handle, thread, _, _ = win32process.CreateProcess(str(exe), None, None, None, False,
                                                      win32con.CREATE_SUSPENDED, env, str(exe.parent), startup)
    try:
        win32job.AssignProcessToJobObject(job, handle)
        win32process.ResumeThread(thread)
    except Exception:
        win32api.TerminateProcess(handle, 1)
        raise
    finally:
        thread.Close()
    jab = c.CDLL(str(binary))
    ref = c.c_longlong
    ptr = c.POINTER
    vm = c.c_long()
    root = ref()
    held = []
    hwnd = None

    def bind(symbol, result, *parameters):
        method = getattr(jab, symbol)
        method.restype = result
        method.argtypes = parameters
        return method

    init = bind("Windows_run", None)
    java_window = bind("isJavaWindow", c.c_int, c.c_void_p)
    from_hwnd = bind("getAccessibleContextFromHWND", c.c_int, c.c_void_p, ptr(c.c_long), ptr(ref))
    info = bind("getAccessibleContextInfo", c.c_int, c.c_long, ref, ptr(Context))
    child_of = bind("getAccessibleChildFromContext", ref, c.c_long, ref, c.c_int)
    actions = bind("getAccessibleActions", c.c_int, c.c_long, ref, ptr(Available))
    execute = bind("doAccessibleActions", c.c_int, c.c_long, ref, ptr(Requested), ptr(c.c_int))
    release = bind("releaseJavaObject", None, c.c_long, ref)

    def wait(until, timeout=18):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            win32gui.PumpWaitingMessages()
            result = until()
            if result:
                return result
            time.sleep(.08)
        raise AssertionError("系统桥接等待超时：" + until.__name__)

    def find_window():
        if win32process.GetExitCodeProcess(handle) != 259:
            raise AssertionError(f"Packaged launcher exited with code {win32process.GetExitCodeProcess(handle)}")
        try:
            windows = desktop.EnumDesktopWindows()
        except win32gui.error:
            return None
        for window in windows:
            if win32gui.GetWindowText(window) != "RProbeDesk" or not java_window(int(window)):
                continue
            owner = win32api.OpenProcess(win32con.PROCESS_QUERY_INFORMATION, False,
                                         win32process.GetWindowThreadProcessId(window)[1])
            try:
                if win32job.IsProcessInJob(owner, job):
                    return int(window)
            finally:
                owner.Close()
        return None

    def tree():
        entries = []
        def visit(node):
            item = Context()
            if not info(vm, node, c.byref(item)):
                return
            entries.append((node, item))
            for i in range(item.children):
                child = child_of(vm, node, i)
                if child:
                    held.append(child)
                    visit(child)
        visit(root)
        return entries

    def act(node):
        offered = Available()
        assert actions(vm, node, c.byref(offered)) and offered.count
        requested = Requested()
        requested.count = 1
        requested.items[0].name = offered.items[0].name
        failure = c.c_int()
        assert execute(vm, node, c.byref(requested), c.byref(failure))
        return requested.items[0].name

    try:
        init()
        try:
            hwnd = wait(find_window, timeout=60)
        except AssertionError:
            print("Private test desktop windows:", [(win32gui.GetClassName(w),win32gui.GetWindowText(w))
                for w in desktop.EnumDesktopWindows()], flush=True)
            raise
        assert from_hwnd(hwnd, c.byref(vm), c.byref(root))
        pages = ["设备","面板","应用","文件","Shell","日志","设置"]
        def navigation():
            nodes = tree()
            if all(any(n.name == label and n.action for _, n in nodes) for label in pages):
                return nodes
            return None
        before = wait(navigation)
        if inspect is not None:
            inspect(desktop, win32process.GetWindowThreadProcessId(hwnd)[1], hwnd, tree, act, wait)
            return
        target = next(node for node, n in before if n.name == "Shell" and n.action)
        action = act(target)
        def shell_page():
            return any("设备 Shell 命令" in (n.name, n.description) for _, n in tree())
        wait(shell_page)
        config = (exe.parent/"app"/"RProbeDesk.cfg").read_text(encoding="utf-8")
        version = re.search(r"-Djpackage\.app-version=([^\s]+)", config).group(1)
        exposed = {page: next({"role": n.role_us, "states": n.states_us} for _, n in before
                              if n.name == page and n.action) for page in pages}
        results = dict(app=exe.name, version=version, bridge=binary.name, navigation=exposed, action=action,
                       activated="Shell", observed="设备 Shell 命令")
        output.write_text(json.dumps(results, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    finally:
        for node in reversed(held):
            release(vm, node)
        if root.value:
            release(vm, root)
        if hwnd:
            win32gui.PostMessage(hwnd, win32con.WM_CLOSE, 0, 0)
        if win32event.WaitForSingleObject(handle, 2500) == win32event.WAIT_TIMEOUT:
            win32job.TerminateJobObject(job, 1)
            win32event.WaitForSingleObject(handle, 2500)
        handle.Close()
        job.Close()


if __name__ == "__main__":
    if not __debug__:
        raise SystemExit("请使用启用断言的 Python")
    parser = argparse.ArgumentParser()
    parser.add_argument("--exe", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parent/"windows-bridge.json")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as scratch:
        check(args.exe.resolve(strict=True), args.output, scratch)
