use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use std::collections::HashMap;
use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, Shutdown, SocketAddr, TcpStream};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, LazyLock, Mutex};
use std::time::{Duration, Instant};

// A connection to the local server ties every numeric transport ID to its server lifetime.
struct Tracker {
    socket: TcpStream,
    serial: String,
    transport_id: i64,
}

static NEXT: AtomicI64 = AtomicI64::new(1);
static TRACKERS: LazyLock<Mutex<HashMap<i64, Arc<Tracker>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

fn frame(mut socket: &TcpStream) -> io::Result<String> {
    let mut header = [0; 4];
    socket.read_exact(&mut header)?;
    let size = std::str::from_utf8(&header)
        .ok()
        .and_then(|s| u16::from_str_radix(s, 16).ok())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid ADB server frame"))?;
    let mut bytes = vec![0; usize::from(size)];
    socket.read_exact(&mut bytes)?;
    String::from_utf8(bytes).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
}

fn transport(list: &str, serial: &str) -> Option<i64> {
    list.lines().find_map(|line| {
        let mut words = line.split_whitespace();
        if words.next() != Some(serial) || words.next() != Some("device") {
            return None;
        }
        words.find_map(|w| w.strip_prefix("transport_id:")?.parse().ok())
    })
}

fn subscribe(serial: String, port: i32) -> io::Result<i64> {
    let port = u16::try_from(port)
        .ok()
        .filter(|p| *p != 0)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "Invalid ADB server port"))?;
    let timeout = Duration::from_secs(8);
    let mut socket =
        TcpStream::connect_timeout(&SocketAddr::from((Ipv4Addr::LOCALHOST, port)), timeout)?;
    socket.set_read_timeout(Some(timeout))?;
    socket.set_write_timeout(Some(timeout))?;
    socket.write_all(b"0014host:track-devices-l")?;
    let mut status = [0; 4];
    socket.read_exact(&mut status)?;
    if &status != b"OKAY" {
        let reason = if &status == b"FAIL" {
            frame(&socket)?
        } else {
            "Invalid ADB server status".into()
        };
        return Err(io::Error::other(reason));
    }
    // "adb connect" may return before the server publishes the final transport state.
    let deadline = Instant::now() + timeout;
    let transport_id = loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "等待设备就绪超时"));
        }
        socket.set_read_timeout(Some(remaining))?;
        let snapshot = frame(&socket)?;
        if let Some(id) = transport(&snapshot, &serial).filter(|id| *id > 0) {
            break id;
        }
        if snapshot.lines().any(|line| {
            let mut parts = line.split_whitespace();
            parts.next() == Some(serial.as_str()) && parts.next() == Some("unauthorized")
        }) {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "目标尚未授权此主控",
            ));
        }
    };
    // A subscription is idle while the device list is unchanged. closeTracker interrupts read_exact.
    socket.set_read_timeout(None)?;
    let mut trackers = TRACKERS
        .lock()
        .map_err(|_| io::Error::other("ADB tracker registry unavailable"))?;
    if trackers.len() >= 64 {
        return Err(io::Error::other("并行设备连接已达上限"));
    }
    let handle = NEXT.fetch_add(1, Ordering::Relaxed);
    trackers.insert(
        handle,
        Arc::new(Tracker {
            socket,
            serial,
            transport_id,
        }),
    );
    Ok(handle)
}

#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_track(
    mut env: JNIEnv,
    _: JClass,
    serial: JString,
    port: i32,
) -> i64 {
    let result = env
        .get_string(&serial)
        .map(String::from)
        .map_err(io::Error::other)
        .and_then(|serial| subscribe(serial, port));
    match result {
        Ok(handle) => handle,
        Err(e) => {
            crate::transport_error::throw(&mut env, &e);
            0
        }
    }
}

fn tracker(id: i64) -> Option<Arc<Tracker>> {
    TRACKERS.lock().ok()?.get(&id).cloned()
}

fn wait_for_disconnect(t: &Tracker) -> io::Result<&'static str> {
    let mut deadline = None;
    loop {
        let remaining = deadline.map(|d: Instant| d.saturating_duration_since(Instant::now()));
        if remaining.is_some_and(|d| d.is_zero()) {
            return Ok("目标 ADB 连接持续离线");
        }
        t.socket.set_read_timeout(remaining)?;
        let list = match frame(&t.socket) {
            Err(e)
                if deadline.is_some()
                    && matches!(
                        e.kind(),
                        io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock
                    ) =>
            {
                return Ok("目标 ADB 连接持续离线")
            }
            result => result?,
        };
        if transport(&list, &t.serial) == Some(t.transport_id) {
            deadline = None;
            continue;
        }
        let state = list.lines().find_map(|line| {
            let mut words = line.split_whitespace();
            if words.next() != Some(t.serial.as_str()) {
                return None;
            }
            let state = words.next()?;
            let id = words.find_map(|w| w.strip_prefix("transport_id:")?.parse::<i64>().ok());
            Some((state, id))
        });
        match state {
            Some(("unauthorized", _)) => {
                return Err(io::Error::new(
                    io::ErrorKind::PermissionDenied,
                    "目标设备已撤销授权",
                ))
            }
            Some((_, Some(id))) if id != t.transport_id => return Ok("目标 ADB 传输标识已变更"),
            Some(("offline", _)) | None => {
                // A short offline announcement may be followed by the same transport. The
                // original subscription must stay open throughout, including the grace period.
                deadline.get_or_insert_with(|| Instant::now() + Duration::from_secs(2));
            }
            Some(_) => return Ok("目标设备已离开调试状态"),
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_transportId(
    _: JNIEnv,
    _: JClass,
    id: i64,
) -> i64 {
    tracker(id).map_or(-1, |t| t.transport_id)
}

#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_waitForDisconnect(
    mut env: JNIEnv,
    _: JClass,
    id: i64,
) -> jstring {
    let reason = match tracker(id) {
        None => "设备会话已关闭".to_owned(),
        Some(t) => match wait_for_disconnect(&t) {
            Ok(reason) => reason.to_owned(),
            Err(e) if e.kind() == io::ErrorKind::PermissionDenied => {
                let _ = env.throw_new("dev/rprobedesk/PeerRejected", e.to_string());
                return std::ptr::null_mut();
            }
            Err(e) => format!("本地 ADB 状态订阅已中断 · {e}"),
        },
    };
    env.new_string(reason)
        .map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_closeTracker(_: JNIEnv, _: JClass, id: i64) {
    if let Ok(mut trackers) = TRACKERS.lock() {
        if let Some(t) = trackers.remove(&id) {
            let _ = t.socket.shutdown(Shutdown::Both);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn same_transport_recovers_but_replacement_and_revocation_end_the_session() {
        use std::net::TcpListener;
        for (last, expected) in [
            ("a device transport_id:5", "目标 ADB 传输标识已变更"),
            ("a unauthorized transport_id:4", "目标设备已撤销授权"),
        ] {
            let listen = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
            let mut sender = TcpStream::connect(listen.local_addr().unwrap()).unwrap();
            let (socket, _) = listen.accept().unwrap();
            let t = Tracker {
                socket,
                serial: "a".into(),
                transport_id: 4,
            };
            for list in [
                "a offline transport_id:4",
                "",
                "a device transport_id:4",
                last,
            ] {
                write!(sender, "{:04x}{}", list.len(), list).unwrap();
            }
            let observed = wait_for_disconnect(&t);
            if last.contains("unauthorized") {
                assert_eq!(
                    observed.unwrap_err().kind(),
                    io::ErrorKind::PermissionDenied
                );
            } else {
                assert_eq!(observed.unwrap(), expected);
            }
        }
    }

    #[test]
    fn unrelated_device_notifications_do_not_invalidate_selected_transport() {
        assert_eq!(
            transport("a device transport_id:4\nb offline transport_id:2", "a"),
            Some(4)
        );
        assert_eq!(
            transport("a offline transport_id:4\nb device transport_id:2", "a"),
            None
        );
    }
}
