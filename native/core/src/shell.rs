use jni::objects::{JClass, JObject};
use jni::sys::jint;
use jni::JNIEnv;

type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;

fn frame(header: i64) -> Result<(i32, i32)> {
    let kind = (header >> 32) as i32;
    let length = header as i32;
    if !(1..=4).contains(&kind)
        || (kind == 4 && length != 0)
        || !(0..=1_048_576).contains(&length)
        || (kind == 3 && length != 1)
    {
        return Err("目标返回了无效的 Shell 数据".into());
    }
    Ok((kind, length))
}

fn execute(env: &mut JNIEnv, io: &JObject, script_size: i32) -> Result<i32> {
    if script_size < 0 || io.is_null() {
        return Err("Invalid Shell input".into());
    }
    let mut offset = 0;
    while offset < script_size {
        let size = (script_size - offset).min(32768);
        env.call_method(
            io,
            "writeInput",
            "(III)V",
            &[0.into(), offset.into(), size.into()],
        )?;
        offset += size;
    }
    env.call_method(io, "writeInput", "(III)V", &[4.into(), 0.into(), 0.into()])?;
    loop {
        let header = env.call_method(io, "readHeader", "()J", &[])?.j()?;
        let (kind, mut remaining) = frame(header)?;
        if kind == 3 {
            return Ok(env.call_method(io, "exitCode", "()I", &[])?.i()?);
        }
        while remaining > 0 {
            let size = remaining.min(32768);
            env.call_method(io, "consume", "(II)V", &[kind.into(), size.into()])?;
            remaining -= size;
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_shell(
    mut env: JNIEnv,
    _: JClass,
    io: JObject,
    script_size: jint,
) -> jint {
    match execute(&mut env, &io, script_size) {
        Ok(code) => code,
        Err(error) => {
            if matches!(env.exception_check(), Ok(false)) {
                let _ = env.throw_new("java/io/IOException", error.to_string());
            }
            -1
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn frame_bounds_and_exit() {
        assert_eq!(frame((3_i64 << 32) | 1).unwrap(), (3, 1));
        assert!(frame(3_i64 << 32).is_err());
        assert!(frame((1_i64 << 32) | 1_048_577).is_err());
        assert!(frame((2_i64 << 32) | u32::MAX as i64).is_err());
    }
}
