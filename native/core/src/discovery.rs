use jni::{
    objects::{JClass, JObject},
    JNIEnv,
};
use mdns_sd::{ServiceDaemon, ServiceEvent};
use std::collections::BTreeMap;
use std::thread;
use std::time::{Duration, Instant};

const SERVICES: [&str; 3] = [
    "_adb._tcp",
    "_adb-tls-connect._tcp",
    "_adb-tls-pairing._tcp",
];
const LIMIT: usize = 128;

fn browse(env: &mut JNIEnv, callback: &JObject) -> Result<(), Box<dyn std::error::Error>> {
    let daemon = ServiceDaemon::new()?;
    // Always stop the sockets/thread, including an exception in a Java callback.
    let result = (|| {
        let mut streams = Vec::new();
        for service in SERVICES {
            let domain = format!("{service}.local.");
            streams.push((service, daemon.browse(&domain)?, domain));
        }
        let mut records = BTreeMap::new();
        let until = Instant::now() + Duration::from_millis(3500);
        while Instant::now() < until && env.call_method(callback, "active", "()Z", &[])?.z()? {
            for (service, receiver, domain) in &streams {
                for event in receiver.try_iter().take(64) {
                    match event {
                        ServiceEvent::ServiceResolved(info) => {
                            if records.len() >= LIMIT && !records.contains_key(&info.fullname) {
                                continue;
                            }
                            let suffix = format!(".{domain}");
                            if let Some(name) = info.fullname.strip_suffix(&suffix) {
                                let addresses = info
                                    .addresses
                                    .iter()
                                    .take(8)
                                    .map(ToString::to_string)
                                    .collect::<Vec<_>>();
                                records.insert(
                                    info.fullname.clone(),
                                    (name.to_owned(), *service, info.port, addresses),
                                );
                            }
                        }
                        ServiceEvent::ServiceRemoved(_, fullname) => {
                            records.remove(&fullname);
                        }
                        _ => {}
                    }
                }
            }
            thread::sleep(Duration::from_millis(75));
        }
        for (_, (name, service, port, ips)) in records {
            for ip in ips {
                env.with_local_frame(8, |env| -> jni::errors::Result<()> {
                    let name = env.new_string(&name)?;
                    let ip = env.new_string(ip)?;
                    let service = env.new_string(service)?;
                    env.call_method(
                        callback,
                        "found",
                        "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V",
                        &[
                            (&name).into(),
                            (&ip).into(),
                            i32::from(port).into(),
                            (&service).into(),
                        ],
                    )?;
                    Ok(())
                })?;
            }
        }
        Ok(())
    })();
    let _ = daemon.shutdown();
    result
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
