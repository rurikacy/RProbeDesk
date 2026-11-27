use jni::{objects::JClass, JNIEnv};
use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{LazyLock, Mutex};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
enum Phase {
    Disconnected,
    Discovering,
    Pairing,
    Authenticating,
    Connecting,
    Connected,
    Suspect,
    Reconnecting,
    Backoff,
    AuthenticationFailed,
    DeviceUnavailable,
}

struct Session {
    phase: Phase,
    failures: u8,
    retries: u8,
}

impl Session {
    fn new() -> Self {
        Self {
            phase: Phase::Disconnected,
            failures: 0,
            retries: 0,
        }
    }

    // Low word is the delay before the next operation in milliseconds.
    fn observe(&mut self, event: i32, entropy: u32) -> i64 {
        use Phase::*;
        let (phase, delay) = match event {
            0 => (Discovering, 0),
            1 => (Pairing, 0),
            2 => (Connecting, 0),
            3 => (Authenticating, 0),
            4 => {
                self.failures = 0;
                self.retries = 0;
                (Connected, 12_000)
            }
            5 if matches!(self.phase, Connected | Suspect) => {
                self.failures = self.failures.saturating_add(1);
                if self.failures < 3 {
                    (Suspect, 1000)
                } else {
                    (DeviceUnavailable, 0)
                }
            }
            6 if matches!(
                self.phase,
                DeviceUnavailable | Reconnecting | Discovering | Connecting | Authenticating
            ) =>
            {
                let ceiling = 1000u32 << self.retries.min(5);
                self.retries = self.retries.saturating_add(1);
                (Backoff, ceiling / 2 + entropy % (ceiling / 2 + 1))
            }
            7 if self.phase == Backoff => (Reconnecting, 0),
            8 => (AuthenticationFailed, 0),
            9 => (DeviceUnavailable, 0),
            _ => return -1,
        };
        self.phase = phase;
        ((phase as i64) << 32) | i64::from(delay)
    }
}

static NEXT: AtomicI64 = AtomicI64::new(1);
static SESSIONS: LazyLock<Mutex<HashMap<i64, Session>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_openSession(_: JNIEnv, _: JClass) -> i64 {
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    let Ok(mut sessions) = SESSIONS.lock() else {
        return 0;
    };
    sessions.insert(id, Session::new());
    id
}

#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_signal(
    _: JNIEnv,
    _: JClass,
    id: i64,
    event: i32,
    entropy: i32,
) -> i64 {
    let Ok(mut sessions) = SESSIONS.lock() else {
        return -1;
    };
    sessions
        .get_mut(&id)
        .map_or(-1, |s| s.observe(event, entropy as u32))
}

#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_closeSession(_: JNIEnv, _: JClass, id: i64) {
    if let Ok(mut sessions) = SESSIONS.lock() {
        sessions.remove(&id);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn transient_failures_reset_and_auth_rejection_stops_retry() {
        let mut s = Session::new();
        for event in [2, 3, 4, 5, 5, 4, 5] {
            s.observe(event, 9);
        }
        assert_eq!(s.phase, Phase::Suspect);
        s.observe(5, 0);
        s.observe(5, 0);
        assert_eq!(s.phase, Phase::DeviceUnavailable);
        for i in 0..8 {
            let ceiling = 1000u32 << i.min(5);
            let delay = s.observe(6, 98123) as u32;
            assert!((ceiling / 2..=ceiling).contains(&delay));
            s.observe(7, 0);
        }
        s.observe(8, 0);
        assert_eq!(s.observe(6, 0), -1);
    }
}
