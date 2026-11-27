mod adb_server;
mod discovery;
mod log_stream;
mod logcat;
mod monitor;
mod runtime;
mod session;
mod shell;
mod sync;
mod transport_error;
mod trust;
#[cfg(windows)]
mod wireless;
#[cfg(windows)]
mod wireless_jni;

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jlong};
use jni::JNIEnv;
use std::time::SystemTime;

struct Streams<'a, 'local> {
    env: &'a mut JNIEnv<'local>,
    io: JObject<'local>,
}

impl sync::Channel for Streams<'_, '_> {
    fn packet(&mut self, tag: [u8; 4], value: u32, payload: bool) -> sync::Result<()> {
        let header = sync::header(tag, value);
        self.env.call_method(
            &self.io,
            "writePacket",
            "(JZ)V",
            &[header.into(), (payload as jboolean).into()],
        )?;
        Ok(())
    }

    fn reply(&mut self) -> sync::Result<([u8; 4], u32)> {
        let header = self
            .env
            .call_method(&self.io, "readHeader", "()J", &[])?
            .j()?;
        Ok(sync::parse_header(header))
    }

    fn next_chunk(&mut self) -> sync::Result<i32> {
        Ok(self
            .env
            .call_method(&self.io, "readSource", "()I", &[])?
            .i()?)
    }

    fn receive(&mut self, count: u32) -> sync::Result<()> {
        self.env
            .call_method(&self.io, "receiveChunk", "(I)V", &[(count as i32).into()])?;
        Ok(())
    }

    fn failure(&mut self, count: u32) -> sync::Result<String> {
        let result = self
            .env
            .call_method(
                &self.io,
                "failureMessage",
                "(I)Ljava/lang/String;",
                &[(count as i32).into()],
            )?
            .l()?;
        let string = JString::from(result);
        let message = self.env.get_string(&string)?.into();
        Ok(message)
    }
}

// All references belong to this JVM call. Java exceptions (including cancellation)
// retain their original type. File contents stay in the caller's reusable byte array.
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_transfer<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    io: JObject<'local>,
    upload: jboolean,
    name_length: i32,
    expected: jlong,
) {
    let mut channel = Streams { env: &mut env, io };
    let result = if upload != 0 {
        let time = SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .unwrap_or_default();
        sync::send(&mut channel, name_length, expected, time.as_secs() as u32)
    } else {
        sync::receive(&mut channel, name_length)
    };
    if let Err(error) = result {
        if matches!(env.exception_check(), Ok(false)) {
            let _ = env.throw_new("java/io/IOException", error.to_string());
        }
    }
}
