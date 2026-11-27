use jni::{
    objects::{JClass, JObject},
    JNIEnv,
};
use mdns_sd::{ScopedIp, ServiceDaemon, ServiceEvent};
use std::collections::BTreeMap;
use std::io;
use std::sync::{Arc, Condvar, LazyLock, Mutex};
use std::time::{Duration, Instant};

const SERVICES: [&str; 3] = [
    "_adb._tcp",
    "_adb-tls-connect._tcp",
    "_adb-tls-pairing._tcp",
];
const LIMIT: usize = 128;
const IDLE: Duration = Duration::from_secs(8);
const TTL: Duration = Duration::from_secs(120);
#[derive(Clone, PartialEq)]
struct Record {
    name: String,
    service: &'static str,
    port: u16,
    addresses: Vec<String>,
}
#[derive(Default)]
struct Cache {
    revision: u64,
    records: BTreeMap<String, (Instant, Record)>,
    error: Option<String>,
    stopped: bool,
}
struct Discovery {
    cache: Mutex<Cache>,
    changed: Condvar,
    last_request: Mutex<Instant>,
}
static SERVICE: LazyLock<Mutex<Option<Arc<Discovery>>>> = LazyLock::new(Mutex::default);

fn service() -> io::Result<Arc<Discovery>> {
    let mut slot = SERVICE
        .lock()
        .map_err(|_| io::Error::other("Discovery registry unavailable"))?;
    if let Some(current) = slot.as_ref() {
        *current
            .last_request
            .lock()
            .map_err(|_| io::Error::other("Discovery unavailable"))? = Instant::now();
        return Ok(current.clone());
    }
    let current = Arc::new(Discovery {
        cache: Mutex::default(),
        changed: Condvar::new(),
        last_request: Mutex::new(Instant::now()),
    });
    let worker = current.clone();
    std::thread::Builder::new()
        .name("rprobe-discovery".into())
        .spawn(move || {
            let result = run(&worker);
            if let Ok(mut c) = worker.cache.lock() {
                c.stopped = true;
                c.error = result.err().map(|e| e.to_string());
                worker.changed.notify_all();
            }
            if let Ok(mut slot) = SERVICE.lock() {
                if slot
                    .as_ref()
                    .is_some_and(|active| Arc::ptr_eq(active, &worker))
                {
                    *slot = None;
                }
            }
        })?;
    *slot = Some(current.clone());
    Ok(current)
}

fn apply(c: &mut Cache, service: &'static str, event: ServiceEvent, now: Instant) -> bool {
    match event {
        ServiceEvent::ServiceResolved(info) => {
            if c.records.len() >= LIMIT && !c.records.contains_key(&info.fullname) {
                return false;
            }
            let Some(name) = info.fullname.strip_suffix(&format!(".{service}.local.")) else {
                return false;
            };
            let mut addresses: Vec<_> = info
                .addresses
                .iter()
                .filter_map(|ip| match ip {
                    ScopedIp::V4(v) => Some(v.addr().to_string()),
                    ScopedIp::V6(v)
                        if v.addr().is_unicast_link_local() && v.scope_id().index != 0 =>
                    {
                        Some(format!("{}%{}", v.addr(), v.scope_id().index))
                    }
                    ScopedIp::V6(v) if !v.addr().is_unicast_link_local() => {
                        Some(v.addr().to_string())
                    }
                    _ => None,
                })
                .collect();
            addresses.sort();
            addresses.dedup();
            addresses.truncate(8);
            let record = Record {
                name: name.to_owned(),
                service,
                port: info.port,
                addresses,
            };
            let changed = c
                .records
                .get(&info.fullname)
                .is_none_or(|old| old.1 != record);
            c.records.insert(info.fullname.clone(), (now + TTL, record));
            changed
        }
        ServiceEvent::ServiceRemoved(_, fullname) => c.records.remove(&fullname).is_some(),
        _ => false,
    }
}

fn run(state: &Arc<Discovery>) -> Result<(), Box<dyn std::error::Error>> {
    let daemon = ServiceDaemon::new()?;
    let result = (|| {
        daemon.set_ip_check_interval(3)?;
        let mut streams = Vec::new();
        for name in SERVICES {
            streams.push((name, daemon.browse(&format!("{name}.local."))?));
        }
        let interfaces = daemon.monitor()?;
        loop {
            let mut select = flume::Selector::new();
            for (name, rx) in &streams {
                select = select.recv(rx, move |event| event.ok().map(|e| (*name, Some(e))));
            }
            let next = select
                .recv(&interfaces, |event| {
                    event.ok().and_then(|e| match e {
                        mdns_sd::DaemonEvent::IpAdd(_) | mdns_sd::DaemonEvent::IpDel(_) => {
                            Some(("", None))
                        }
                        _ => None,
                    })
                })
                .wait_timeout(Duration::from_millis(500));
            let now = Instant::now();
            {
                let mut c = state
                    .cache
                    .lock()
                    .map_err(|_| "Discovery cache unavailable")?;
                if c.stopped {
                    break;
                }
                let before = c.records.len();
                c.records.retain(|_, (expiry, _)| *expiry > now);
                let mut changed = c.records.len() != before;
                match next {
                    Ok(Some((name, Some(e)))) => changed |= apply(&mut c, name, e, now),
                    Ok(Some((_, None))) => {
                        c.records.clear();
                        changed = true;
                        crate::session::network_changed();
                    }
                    _ => {}
                }
                if changed {
                    c.revision = c.revision.wrapping_add(1);
                    state.changed.notify_all();
                }
            }
            // Synchronize retirement with service() so a concurrent new demand cannot
            // attach to a daemon that just decided to shut down.
            let mut slot = SERVICE
                .lock()
                .map_err(|_| "Discovery registry unavailable")?;
            if now.saturating_duration_since(
                *state
                    .last_request
                    .lock()
                    .map_err(|_| "Discovery unavailable")?,
            ) >= IDLE
            {
                if slot.as_ref().is_some_and(|s| Arc::ptr_eq(s, state)) {
                    *slot = None;
                }
                break;
            }
        }
        Ok(())
    })();
    let _ = daemon.shutdown();
    result
}

fn publish(env: &mut JNIEnv, callback: &JObject, records: &[Record]) -> jni::errors::Result<()> {
    env.call_method(callback, "begin", "()V", &[])?;
    for record in records {
        for ip in &record.addresses {
            env.with_local_frame(8, |env| -> jni::errors::Result<()> {
                let name = env.new_string(&record.name)?;
                let ip = env.new_string(ip)?;
                let ty = env.new_string(record.service)?;
                env.call_method(
                    callback,
                    "found",
                    "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V",
                    &[
                        (&name).into(),
                        (&ip).into(),
                        i32::from(record.port).into(),
                        (&ty).into(),
                    ],
                )?;
                Ok(())
            })?;
        }
    }
    env.call_method(callback, "complete", "()V", &[])?;
    Ok(())
}

fn browse(env: &mut JNIEnv, callback: &JObject) -> Result<(), Box<dyn std::error::Error>> {
    let state = service()?;
    let until = Instant::now() + Duration::from_millis(3500);
    let mut seen = None;
    while env.call_method(callback, "active", "()Z", &[])?.z()? {
        let now = Instant::now();
        if now >= until {
            break;
        }
        let mut c = state
            .cache
            .lock()
            .map_err(|_| "Discovery cache unavailable")?;
        if let Some(e) = &c.error {
            return Err(e.clone().into());
        }
        if c.stopped {
            break;
        }
        if seen != Some(c.revision) {
            seen = Some(c.revision);
            let snapshot: Vec<_> = c
                .records
                .values()
                .filter(|(expiry, _)| *expiry > now)
                .map(|(_, r)| r.clone())
                .collect();
            drop(c);
            publish(env, callback, &snapshot)?;
        } else {
            c = state
                .changed
                .wait_timeout(c, (until - now).min(Duration::from_millis(200)))
                .map_err(|_| "Discovery unavailable")?
                .0;
            drop(c);
        }
    }
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_stopDiscovery(_: JNIEnv, _: JClass) {
    let current = SERVICE.lock().ok().and_then(|mut slot| slot.take());
    if let Some(s) = current {
        if let Ok(mut c) = s.cache.lock() {
            c.stopped = true;
            s.changed.notify_all();
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_discover(
    mut env: JNIEnv,
    _: JClass,
    callback: JObject,
) {
    if let Err(error) = browse(&mut env, &callback) {
        if matches!(env.exception_check(), Ok(false)) {
            let _ = env.throw_new("java/io/IOException", error.to_string());
        }
    }
}
