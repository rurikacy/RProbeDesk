use crate::logcat::fields;
use jni::objects::{JByteArray, JClass, JString};
use jni::{sys::jbyteArray, JNIEnv};
use std::collections::{HashMap, VecDeque};
use std::sync::{
    atomic::{AtomicI64, Ordering},
    Arc, LazyLock, Mutex,
};

const ROWS: usize = 1500;
const LINE_BYTES: usize = 16_384;
struct Row {
    text: String,
    fields: [i32; 9],
}
#[derive(Default)]
struct Capture {
    rows: VecDeque<Row>,
    partial: Vec<u8>,
    truncated: bool,
    written: u64,
}
impl Capture {
    fn row(&mut self, text: &str, truncated: bool) {
        let text = text.strip_suffix('\r').unwrap_or(text);
        let mut units = 0;
        let end = if text.len() <= 4096 {
            text.len()
        } else {
            text.char_indices()
                .find_map(|(i, c)| {
                    units += c.len_utf16();
                    (units > 4096).then_some(i)
                })
                .unwrap_or(text.len())
        };
        let mut line = text[..end].to_owned();
        if truncated || end < text.len() {
            line.push_str("〔行已截断〕");
        }
        if self.rows.len() == ROWS {
            self.rows.pop_front();
        }
        self.rows.push_back(Row {
            fields: fields(&line),
            text: line,
        });
        self.written += 1;
    }
    fn finish(&mut self) {
        if !self.partial.is_empty() || self.truncated {
            let mut bytes = std::mem::take(&mut self.partial);
            let text = String::from_utf8_lossy(&bytes);
            self.row(&text, self.truncated);
            bytes.clear();
            self.partial = bytes;
            self.truncated = false;
        }
    }
    fn append(&mut self, bytes: &[u8]) {
        for block in bytes.split_inclusive(|b| *b == b'\n') {
            let end = block.last() == Some(&b'\n');
            let content = &block[..block.len() - usize::from(end)];
            if end && self.partial.is_empty() && !self.truncated {
                self.row(
                    &String::from_utf8_lossy(&content[..content.len().min(LINE_BYTES)]),
                    content.len() > LINE_BYTES,
                );
            } else {
                let take = content.len().min(LINE_BYTES - self.partial.len());
                self.partial.extend_from_slice(&content[..take]);
                self.truncated |= take < content.len();
                if end {
                    self.finish();
                }
            }
        }
    }
    // Big-endian sequence, cumulative evictions, followed by UTF-8 length, nine
    // UTF-16 field offsets/level, and text. Only records newer than the cursor cross JNI.
    fn delta(&self, after: u64) -> Vec<u8> {
        if after >= self.written {
            return Vec::new();
        }
        let evicted = self.written - self.rows.len() as u64;
        let mut out = Vec::new();
        out.extend(self.written.to_be_bytes());
        out.extend(evicted.to_be_bytes());
        for row in self
            .rows
            .iter()
            .skip(after.saturating_sub(evicted) as usize)
        {
            out.extend((row.text.len() as u32).to_be_bytes());
            for field in row.fields {
                out.extend(field.to_be_bytes());
            }
            out.extend(row.text.as_bytes());
        }
        out
    }
}

type Registry = Mutex<HashMap<i64, Arc<Mutex<Capture>>>>;
static LOGS: LazyLock<Registry> = LazyLock::new(Mutex::default);
static NEXT: AtomicI64 = AtomicI64::new(1);
fn access<T>(id: i64, op: impl FnOnce(&mut Capture) -> T) -> Result<T, &'static str> {
    let log = LOGS
        .lock()
        .map_err(|_| "Log registry unavailable")?
        .get(&id)
        .cloned()
        .ok_or("Log capture closed")?;
    let mut data = log.lock().map_err(|_| "Log capture unavailable")?;
    Ok(op(&mut data))
}
fn fail(env: &mut JNIEnv, message: &str) {
    if matches!(env.exception_check(), Ok(false)) {
        let _ = env.throw_new("java/io/IOException", message);
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_openLog(mut env: JNIEnv, _: JClass) -> i64 {
    if let Ok(mut logs) = LOGS.lock() {
        if logs.len() < 32 {
            let id = NEXT.fetch_add(1, Ordering::Relaxed);
            logs.insert(id, Arc::default());
            return id;
        }
    }
    fail(&mut env, "Log capture limit reached");
    0
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_appendLog(
    mut env: JNIEnv,
    _: JClass,
    id: i64,
    array: JByteArray,
    size: i32,
) {
    if !(0..=65536).contains(&size) || env.get_array_length(&array).map_or(true, |n| size > n) {
        fail(&mut env, "Invalid log data range");
        return;
    }
    // A single reusable copy per I/O thread; the Java array is not pinned across a lock.
    thread_local! { static TEMP: std::cell::RefCell<Vec<i8>> = const { std::cell::RefCell::new(Vec::new()) }; }
    TEMP.with(|slot| {
        let Ok(mut buf) = slot.try_borrow_mut() else {
            fail(&mut env, "Log input is busy");
            return;
        };
        buf.resize(size as usize, 0);
        if env.get_byte_array_region(&array, 0, &mut buf).is_ok() {
            // jbyte and u8 have identical size/alignment. No aliases mutate during parsing.
            let bytes = unsafe { std::slice::from_raw_parts(buf.as_ptr().cast(), buf.len()) };
            if let Err(e) = access(id, |l| l.append(bytes)) {
                fail(&mut env, e)
            }
        }
    });
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_noteLog(
    mut env: JNIEnv,
    _: JClass,
    id: i64,
    text: JString,
) {
    if let Ok(value) = env.get_string(&text).map(String::from) {
        if let Err(e) = access(id, |l| l.row(&value, false)) {
            fail(&mut env, e)
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_finishLog(
    mut env: JNIEnv,
    _: JClass,
    id: i64,
) {
    if let Err(e) = access(id, Capture::finish) {
        fail(&mut env, e)
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_readLog(
    mut env: JNIEnv,
    _: JClass,
    id: i64,
    after: i64,
) -> jbyteArray {
    if after < 0 {
        fail(&mut env, "Invalid log cursor");
        return std::ptr::null_mut();
    }
    match access(id, |l| l.delta(after as u64)) {
        Ok(bytes) => env
            .byte_array_from_slice(&bytes)
            .map_or(std::ptr::null_mut(), |v| v.into_raw()),
        Err(e) => {
            fail(&mut env, e);
            std::ptr::null_mut()
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_closeLog(_: JNIEnv, _: JClass, id: i64) {
    if let Ok(mut logs) = LOGS.lock() {
        logs.remove(&id);
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn utf8_fragments_and_overflow_preserve_recent_records() {
        let mut c = Capture::default();
        for chunk in "11-27 12:00:01.123 12 34 E 中文: 💻\r\n    at line\n\n尾"
            .as_bytes()
            .chunks(1)
        {
            c.append(chunk);
        }
        c.finish();
        assert!(c.rows[0].text.ends_with("💻"));
        assert_eq!(c.rows[1].fields[0], -1);
        assert_eq!(c.rows[2].text, "");
        assert_eq!(c.rows[3].text, "尾");
        for _ in 0..24_000 {
            c.append(b"row\n");
        }
        assert_eq!(c.rows.len(), ROWS);
        let packet = c.delta(0);
        assert_eq!(&packet[8..16], &(24_004u64 - ROWS as u64).to_be_bytes());
        assert!(c.delta(24_004).is_empty());
        c.append(&vec![b'x'; 200_000]);
        assert_eq!(c.partial.len(), LINE_BYTES);
        c.finish();
        assert!(c.rows.back().unwrap().text.ends_with("〔行已截断〕"));
    }
}
