use crate::runtime::runtime;
use jni::{
    objects::{JClass, JObject, JString},
    JNIEnv,
};
use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant};
use tokio::task::JoinHandle;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
enum Phase {
    Disconnected = 0,
    Discovering = 1,
    Pairing = 2,
    Authenticating = 3,
    Connecting = 4,
    Connected = 5,
    Suspect = 6,
    Reconnecting = 7,
    Backoff = 8,
    AuthenticationFailed = 9,
    DeviceUnavailable = 10,
    Closing = 11,
}

struct Session {
    phase: Phase,
    failures: u8,
    retries: u8,
    deadline: Instant,
    timer: Option<(i64, JoinHandle<()>)>,
    network: tokio::sync::watch::Sender<u64>,
    identity: String,
    transport: Option<(i64, jni::objects::GlobalRef)>,
}

impl Session {
    fn new() -> Self {
        Self {
            phase: Phase::Disconnected,
            failures: 0,
            retries: 0,
            deadline: Instant::now(),
            timer: None,
            network: tokio::sync::watch::channel(0).0,
            identity: String::new(),
            transport: None,
        }
    }

    // Low word is the delay before the next operation in milliseconds.
    fn observe(&mut self, event: i32, entropy: u32) -> i64 {
        use Phase::*;
        let (phase, delay) = match event {
            0 if matches!(self.phase, Disconnected | Reconnecting | Discovering) => {
                (Discovering, 0)
            }
            1 if self.phase == Disconnected => (Pairing, 0),
            2 if matches!(
                self.phase,
                Disconnected | Reconnecting | Discovering | Connecting | Authenticating
            ) =>
            {
                (Connecting, 0)
            }
            3 if self.phase == Connecting => (Authenticating, 0),
            4 if matches!(self.phase, Authenticating | Connected | Suspect) => {
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
            8 if !matches!(self.phase, Closing | AuthenticationFailed) => (AuthenticationFailed, 0),
            9 if !matches!(self.phase, Closing | AuthenticationFailed) => (DeviceUnavailable, 0),
            10 => (Closing, 0),
            _ => return -1,
        };
        self.deadline = Instant::now() + Duration::from_millis(u64::from(delay));
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
    if sessions.len() >= 32 {
        return 0;
    }
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
pub extern "system" fn Java_dev_rprobedesk_NativeCore_ownTransport(
    env: JNIEnv,
    _: JClass,
    id: i64,
    close: JObject,
) -> i64 {
    if close.is_null() {
        return -1;
    }
    let Ok(close) = env.new_global_ref(close) else {
        return -1;
    };
    let Ok(mut sessions) = SESSIONS.lock() else {
        return -2;
    };
    let Some(s) = sessions.get_mut(&id) else {
        return -2;
    };
    if s.phase != Phase::Authenticating || s.transport.is_some() {
        return -1;
    }
    let generation = NEXT.fetch_add(1, Ordering::Relaxed);
    s.transport = Some((generation, close));
    generation
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_releaseTransport(
    mut env: JNIEnv,
    _: JClass,
    id: i64,
    generation: i64,
) {
    let resource = SESSIONS.lock().ok().and_then(|mut sessions| {
        let s = sessions.get_mut(&id)?;
        if s.transport.as_ref()?.0 != generation {
            return None;
        }
        s.transport.take()
    });
    if let Some((_, close)) = resource {
        let _ = env.call_method(close, "run", "()V", &[]);
    }
}

// Positive handles are generation IDs and are never recycled. Each generation accepts
// exactly one timer. Its callback is delivered outside the registry lock.
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_bindSession(
    mut env: JNIEnv,
    _: JClass,
    id: i64,
    identity: JString,
) -> i32 {
    let Some(identity) = env.get_string(&identity).ok().map(String::from) else {
        return -1;
    };
    if identity.len() > 1024 {
        return -1;
    }
    let Ok(mut sessions) = SESSIONS.lock() else {
        return -2;
    };
    let Some(session) = sessions.get_mut(&id) else {
        return -2;
    };
    if !session.identity.is_empty() && session.identity != identity {
        return -3;
    }
    session.identity = identity;
    0
}

#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_schedule(
    env: JNIEnv,
    _: JClass,
    id: i64,
    ticket: i64,
    callback: JObject,
) -> i32 {
    let Ok(rt) = runtime() else {
        return -2;
    };
    if callback.is_null() {
        return -1;
    }
    let (Ok(vm), Ok(callback)) = (env.get_java_vm(), env.new_global_ref(callback)) else {
        return -1;
    };
    let Ok(mut sessions) = SESSIONS.lock() else {
        return -2;
    };
    let Some(s) = sessions.get_mut(&id) else {
        return -2;
    };
    if let Some((_, task)) = &s.timer {
        if !task.is_finished() {
            return -4;
        }
    }
    let at = s.deadline;
    let backoff = s.phase == Phase::Backoff;
    let mut network = s.network.subscribe();
    s.timer = Some((
        ticket,
        rt.spawn(async move {
            tokio::select! {
                _ = tokio::time::sleep_until(at.into()) => (),
                _ = network.changed(), if backoff => tokio::time::sleep(Duration::from_millis((id as u64 * 37) % 251)).await,
            }
            let _ = tokio::task::spawn_blocking(move || {
            {
                let Ok(mut sessions) = SESSIONS.lock() else {
                    return;
                };
                let Some(s) = sessions.get_mut(&id) else {
                    return;
                };
                if s.timer.as_ref().map(|t| t.0) != Some(ticket) {
                    return;
                }
                s.timer.take();
            }
                if let Ok(mut env) = vm.attach_current_thread_as_daemon() {
                if env.call_method(&callback, "run", "()V", &[]).is_err()
                    && env.exception_check().unwrap_or(false)
                {
                    let _ = env.exception_describe();
                    let _ = env.exception_clear();
                }
            } }).await;
        }),
    ));
    0
}

#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_cancelTimer(
    _: JNIEnv,
    _: JClass,
    id: i64,
    ticket: i64,
) {
    if let Ok(mut sessions) = SESSIONS.lock() {
        if let Some(s) = sessions.get_mut(&id) {
            if s.timer.as_ref().map(|t| t.0) == Some(ticket) {
                if let Some((_, job)) = s.timer.take() {
                    job.abort();
                }
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_closeSession(
    mut env: JNIEnv,
    _: JClass,
    id: i64,
) {
    let removed = SESSIONS
        .lock()
        .ok()
        .and_then(|mut sessions| sessions.remove(&id));
    if let Some(mut s) = removed {
        s.observe(10, 0);
        if let Some((_, close)) = s.transport.take() {
            let _ = env.call_method(close, "run", "()V", &[]);
        }
        if let Some((_, task)) = s.timer.take() {
            task.abort();
        }
    }
}

pub(crate) fn network_changed() {
    if let Ok(mut sessions) = SESSIONS.lock() {
        for (id, state) in sessions.iter_mut() {
            if state.phase == Phase::Backoff {
                state.deadline = Instant::now() + Duration::from_millis((*id as u64 * 37) % 251);
                state.network.send_modify(|n| *n = n.wrapping_add(1));
            }
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_networkChanged(_: JNIEnv, _: JClass) {
    network_changed();
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn invalid_transitions_do_not_reopen_a_closed_or_rejected_session() {
        let mut s = Session::new();
        assert_eq!(s.observe(4, 0), -1);
        assert_eq!(s.phase, Phase::Disconnected);
        for event in [2, 3, 8] {
            assert!(s.observe(event, 0) >= 0);
        }
        for event in [0, 2, 4, 6, 7, 9, 999] {
            assert_eq!(s.observe(event, 0), -1);
        }
        assert!(s.observe(10, 0) >= 0);
        assert!(s.observe(10, 0) >= 0);
        assert_eq!(s.observe(2, 0), -1);
    }
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
