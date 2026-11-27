use crate::runtime::runtime;
use crate::wireless::{self, device};
use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use std::cell::RefCell;
use std::io::{self, ErrorKind};
use std::path::Path;

fn string(env: &mut JNIEnv, value: &JString) -> io::Result<String> {
    env.get_string(value)
        .map(Into::into)
        .map_err(io::Error::other)
}
fn result<T: Default>(env: &mut JNIEnv, value: io::Result<T>) -> T {
    match value {
        Ok(v) => v,
        Err(e) => {
            crate::transport_error::throw(env, &e);
            T::default()
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_openDial(mut env: JNIEnv, _: JClass) -> i64 {
    result(&mut env, wireless::new_dial())
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_cancelDial(_: JNIEnv, _: JClass, id: i64) {
    wireless::cancel_dial(id);
}

#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_directConnect(
    mut env: JNIEnv,
    _: JClass,
    dial: i64,
    endpoint: JString,
    dir: JString,
    expected: JString,
) -> i64 {
    let v = (|| {
        let addr = string(&mut env, &endpoint)?
            .parse()
            .map_err(io::Error::other)?;
        let path = string(&mut env, &dir)?;
        let pin = string(&mut env, &expected)?;
        runtime()?.block_on(wireless::dial(dial, addr, Path::new(&path), pin))
    })();
    result(&mut env, v)
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_directPeer(
    mut env: JNIEnv,
    _: JClass,
    h: i64,
) -> jstring {
    let v = (|| {
        env.new_string(device(h)?.pin())
            .map(|s| s.into_raw())
            .map_err(io::Error::other)
    })();
    result(&mut env, v)
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_directOpen(
    mut env: JNIEnv,
    _: JClass,
    h: i64,
    name: JString,
) -> i64 {
    let v = (|| runtime()?.block_on(device(h)?.open(&string(&mut env, &name)?)))();
    result(&mut env, v)
}
fn batch(env: &JNIEnv, arr: &JByteArray, off: i32, len: i32) -> io::Result<usize> {
    let capacity = env.get_array_length(arr).map_err(io::Error::other)?;
    if off < 0 || !(0..=65536).contains(&len) || off > capacity - len {
        return Err(io::Error::new(
            ErrorKind::InvalidInput,
            "Invalid ADB stream buffer range",
        ));
    }
    Ok(len as usize)
}

// The blocking worker owns this buffer until it returns from JNI. No Java array is
// pinned during network I/O. Idle workers exit and release their scratch allocation.
thread_local! { static SCRATCH: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) }; }
fn buffered<T>(size: usize, op: impl FnOnce(&mut [u8]) -> io::Result<T>) -> io::Result<T> {
    SCRATCH.with(|slot| {
        let mut bytes = slot.try_borrow_mut().map_err(io::Error::other)?;
        if bytes.len() < size {
            bytes.resize(size, 0);
        }
        op(&mut bytes[..size])
    })
}
fn jbytes(bytes: &mut [u8]) -> &mut [i8] {
    // Both types have the same layout and every bit pattern is valid. The exclusive
    // reborrow ends before Rust accesses the buffer again.
    unsafe { std::slice::from_raw_parts_mut(bytes.as_mut_ptr().cast(), bytes.len()) }
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_directRead(
    mut env: JNIEnv,
    _: JClass,
    h: i64,
    ch: i64,
    arr: JByteArray,
    off: i32,
    len: i32,
    idle_ms: i32,
) -> i32 {
    let v = (|| {
        let size = batch(&env, &arr, off, len)?;
        buffered(size, |bytes| {
            if !(0..=300_000).contains(&idle_ms) {
                return Err(ErrorKind::InvalidInput.into());
            }
            let n = runtime()?.block_on(device(h)?.read(ch, bytes, idle_ms))?;
            if n > 0 {
                env.set_byte_array_region(&arr, off, jbytes(&mut bytes[..n as usize]))
                    .map_err(io::Error::other)?;
            }
            Ok(n)
        })
    })();
    result(&mut env, v)
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_directWrite(
    mut env: JNIEnv,
    _: JClass,
    h: i64,
    ch: i64,
    arr: JByteArray,
    off: i32,
    len: i32,
) {
    let v = (|| {
        let size = batch(&env, &arr, off, len)?;
        buffered(size, |bytes| {
            env.get_byte_array_region(&arr, off, jbytes(bytes))
                .map_err(io::Error::other)?;
            runtime()?.block_on(device(h)?.write(ch, bytes))
        })
    })();
    result(&mut env, v)
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_directCloseStream(
    _: JNIEnv,
    _: JClass,
    h: i64,
    ch: i64,
) {
    if let Ok(d) = device(h) {
        d.close_stream(ch);
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_directClose(_: JNIEnv, _: JClass, h: i64) {
    wireless::close(h)
}

#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_onTransportEnd(
    mut env: JNIEnv,
    _: JClass,
    h: i64,
    callback: JObject,
) {
    let v = (|| {
        let d = device(h)?;
        if callback.is_null() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Missing transport listener",
            ));
        }
        let cb = env.new_global_ref(callback).map_err(io::Error::other)?;
        let vm = env.get_java_vm().map_err(io::Error::other)?;
        let runtime = runtime()?;
        if d.observing.swap(true, std::sync::atomic::Ordering::AcqRel) {
            return Err(io::Error::new(
                io::ErrorKind::AlreadyExists,
                "Transport already observed",
            ));
        }
        runtime.spawn(async move {
            let why = d.ended().await;
            let _ = tokio::task::spawn_blocking(move || {
                if let Ok(mut env) = vm.attach_current_thread_as_daemon() {
                    let _ = env.with_local_frame(4, |env| -> jni::errors::Result<()> {
                        let code = crate::transport_error::code(&why);
                        let message = env.new_string(why.to_string())?;
                        env.call_method(
                            &cb,
                            "ended",
                            "(ILjava/lang/String;)V",
                            &[code.into(), (&message).into()],
                        )?;
                        Ok(())
                    });
                    if env.exception_check().unwrap_or(false) {
                        let _ = env.exception_describe();
                        let _ = env.exception_clear();
                    }
                }
            })
            .await;
        });
        Ok(())
    })();
    result(&mut env, v)
}
