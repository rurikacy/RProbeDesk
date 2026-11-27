use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::jintArray;
use jni::JNIEnv;

// Return UTF-16 offsets into the existing JVM text. The UI keeps the text for display/export.
pub(super) fn fields(line: &str) -> [i32; 9] {
    fn digits(bytes: &[u8]) -> bool {
        !bytes.is_empty() && bytes.iter().all(u8::is_ascii_digit)
    }
    let text = line.trim_start_matches(|c: char| c.is_ascii_whitespace());
    let mut spans = [(0usize, 0usize); 5];
    let mut used = 0;
    let mut pos = 0;
    let bytes = text.as_bytes();
    while used < spans.len() {
        let start = pos;
        while pos < bytes.len() && !bytes[pos].is_ascii_whitespace() {
            pos += 1;
        }
        if start == pos || pos == bytes.len() {
            break;
        }
        spans[used] = (start, pos);
        used += 1;
        while pos < bytes.len() && bytes[pos].is_ascii_whitespace() {
            pos += 1;
        }
    }
    let continuation = ["\t", "    ", "Caused by:", "Suppressed:"]
        .iter()
        .any(|p| line.starts_with(p));
    let unmatched = [if continuation { -1 } else { 0 }, 0, 0, 0, 0, 0, 0, 0, 0];
    if used != 5 {
        return unmatched;
    }
    let date = &bytes[spans[0].0..spans[0].1];
    let date = if date.len() == 10 && digits(&date[..4]) && date[4] == b'-' {
        &date[5..]
    } else {
        date
    };
    let time = &bytes[spans[1].0..spans[1].1];
    let pid = &bytes[spans[2].0..spans[2].1];
    let tid = &bytes[spans[3].0..spans[3].1];
    let priority = &bytes[spans[4].0..spans[4].1];
    if date.len() != 5
        || date[2] != b'-'
        || !digits(&date[..2])
        || !digits(&date[3..])
        || time.len() < 10
        || time[2] != b':'
        || time[5] != b':'
        || !matches!(time[8], b'.' | b',')
        || !digits(&time[..2])
        || !digits(&time[3..5])
        || !digits(&time[6..8])
        || !digits(&time[9..])
        || !digits(pid)
        || !digits(tid)
        || priority.len() != 1
        || !b"VDIWEFA".contains(&priority[0])
    {
        return unmatched;
    }
    let Some((tag, _)) = text[pos..].split_once(':') else {
        return unmatched;
    };
    if tag.is_empty() {
        return unmatched;
    }
    let start = line.len() - text.len();
    let at = (start + pos) as i32;
    let level = if priority[0] == b'A' {
        b'F'
    } else {
        priority[0]
    };
    [
        i32::from(level),
        (start + spans[2].0) as i32,
        (start + spans[2].1) as i32,
        at,
        at + tag.encode_utf16().count() as i32,
        start as i32,
        (start + spans[1].1) as i32,
        (start + spans[3].0) as i32,
        (start + spans[3].1) as i32,
    ]
}

#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_logFields(
    mut env: JNIEnv,
    _: JClass,
    lines: JObjectArray,
) -> jintArray {
    let result = (|| -> jni::errors::Result<_> {
        let count = env.get_array_length(&lines)?;
        if count > 64 {
            env.throw_new("java/lang/IllegalArgumentException", "Oversized log batch")?;
            return Err(jni::errors::Error::JavaException);
        }
        let mut packed = Vec::with_capacity(count as usize * 5);
        for i in 0..count {
            let row = JString::from(env.get_object_array_element(&lines, i)?);
            let value: String = env.get_string(&row)?.into();
            packed.extend_from_slice(&fields(&value)[..5]);
            env.delete_local_ref(row)?;
        }
        let output = env.new_int_array(packed.len() as i32)?;
        env.set_int_array_region(&output, 0, &packed)?;
        Ok(output.into_raw())
    })();
    match result {
        Ok(value) => value,
        Err(e) => {
            if matches!(env.exception_check(), Ok(false)) {
                let _ = env.throw_new("java/io/IOException", e.to_string());
            }
            std::ptr::null_mut()
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn handles_offsets_for_supplementary_characters_and_stack_continuations() {
        let line = "2026-11-27 10:11:12.555  131  42 A 💻中文 : message";
        let f = fields(line);
        assert_eq!(f[0], i32::from(b'F'));
        assert_eq!(&line[f[1] as usize..f[2] as usize], "131");
        assert_eq!(f[4] - f[3], 5);
        assert_eq!(
            &line[f[5] as usize..f[6] as usize],
            "2026-11-27 10:11:12.555"
        );
        assert_eq!(&line[f[7] as usize..f[8] as usize], "42");
        assert_eq!(fields("    at example")[0], -1);
        assert_eq!(fields("11-27 10:11:12.bad 131 42 A tag: dropped")[0], 0);
    }
}
