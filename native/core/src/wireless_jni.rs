use crate::wireless::{self, device, runtime};
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
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
            if matches!(env.exception_check(), Ok(false)) {
                let class = if e.kind() == ErrorKind::PermissionDenied {
                    "dev/rprobedesk/PeerRejected"
                } else {
                    "java/io/IOException"
                };
                let _ = env.throw_new(class, e.to_string());
            }
            T::default()
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_directConnect(
    mut env: JNIEnv,
    _: JClass,
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
        runtime()?.block_on(wireless::connect(addr, Path::new(&path), pin))
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
    if off < 0
        || !(0..=65536).contains(&len)
        || off
            .checked_add(len)
            .is_none_or(|end| end > env.get_array_length(arr).unwrap_or(-1))
    {
        return Err(io::Error::new(
            ErrorKind::InvalidInput,
            "Invalid ADB stream buffer range",
        ));
    }
    Ok(len as usize)
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
) -> i32 {
    let v = (|| {
        let mut bytes = vec![0; batch(&env, &arr, off, len)?];
        let n = runtime()?.block_on(device(h)?.read(ch, &mut bytes))?;
        if n > 0 {
            let signed: Vec<i8> = bytes[..n as usize].iter().map(|&b| b as i8).collect();
            env.set_byte_array_region(&arr, off, &signed)
                .map_err(io::Error::other)?;
        }
        Ok(n)
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
        let mut signed = vec![0; batch(&env, &arr, off, len)?];
        env.get_byte_array_region(&arr, off, &mut signed)
            .map_err(io::Error::other)?;
        let bytes: Vec<u8> = signed.into_iter().map(|b| b as u8).collect();
        runtime()?.block_on(device(h)?.write(ch, &bytes))
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
    if let (Ok(rt), Ok(d)) = (runtime(), device(h)) {
        rt.spawn(async move {
            d.close_stream(ch).await;
        });
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_directClose(_: JNIEnv, _: JClass, h: i64) {
    wireless::close(h)
}
