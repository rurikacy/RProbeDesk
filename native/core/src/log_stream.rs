use crate::logcat::fields;
use jni::objects::{JByteArray, JClass, JString};
use jni::{sys::jbyteArray, JNIEnv};
use std::collections::{HashMap, VecDeque};
use std::sync::{
    atomic::{AtomicI64, AtomicUsize, Ordering},
    Arc, LazyLock, Mutex,
};

const ROWS: usize = 1500;
const LINE_BYTES: usize = 16_384;
const MAX_CAPTURES: usize = 32;
const CAPTURE_BYTES: usize = 1024 * 1024;
const PAGE_BYTES: usize = 256 * 1024;
const PAGE_ROWS: usize = 256;
const HEADER: usize = 24;
const CHUNK: usize = 65_536;
const TEXT_UNITS: usize = 4096;
const TRUNCATED: &str = "〔行已截断〕";

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
    text_bytes: usize,
    scratch: Vec<i8>,
}
impl Capture {
    fn row(&mut self, text: &str, truncated: bool) {
        let text = text.strip_suffix('\r').unwrap_or(text);
        let mut end = text.len().min(LINE_BYTES);
        while !text.is_char_boundary(end) {
            end -= 1;
        }
        let mut clipped = truncated || end < text.len();
        if clipped || end > TEXT_UNITS {
            let mut units = 0;
            let mut with_marker = 0;
            for (pos, ch) in text[..end].char_indices() {
                units += ch.len_utf16();
                if units <= TEXT_UNITS - TRUNCATED.encode_utf16().count() {
                    with_marker = pos + ch.len_utf8();
                }
                if units > TEXT_UNITS {
                    clipped = true;
                    break;
                }
            }
            if clipped {
                end = with_marker;
            }
        }
        let suffix = if clipped { TRUNCATED } else { "" };
        let mut line = String::with_capacity(end + suffix.len());
        line.push_str(&text[..end]);
        line.push_str(suffix);
        if self.rows.capacity() == 0 {
            self.rows.reserve_exact(ROWS);
        }
        // Each live capture has a fixed share, including its deque, input scratch, one
        // serialized page and headroom for UTF-8 conversion and an incoming record.
        let fixed = self.rows.capacity() * std::mem::size_of::<Row>()
            + CHUNK
            + LINE_BYTES
            + PAGE_BYTES
            + 4 * LINE_BYTES
            + std::mem::size_of::<Capture>();
        while self.rows.len() >= ROWS || fixed + self.text_bytes + line.capacity() > CAPTURE_BYTES {
            let Some(old) = self.rows.pop_front() else {
                break;
            };
            self.text_bytes -= old.text.capacity();
        }
        self.text_bytes += line.capacity();
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
                if self.partial.len() + take > self.partial.capacity() {
                    self.partial.reserve_exact(LINE_BYTES - self.partial.len());
                }
                self.partial.extend_from_slice(&content[..take]);
                self.truncated |= take < content.len();
                if end {
                    self.finish();
                }
            }
        }
    }
    // Header: last sequence in THIS page, cumulative evictions, newest sequence.
    // The oldest available sequence is evictions + 1. More pages exist while end < newest.
    fn delta(&self, after: u64) -> Vec<u8> {
        if after >= self.written {
            return Vec::new();
        }
        let evicted = self.written - self.rows.len() as u64;
        let first = after.max(evicted);
        let rows = self
            .rows
            .range((first - evicted) as usize..)
            .take(PAGE_ROWS);
        let mut bytes = HEADER;
        let count = rows
            .clone()
            .take_while(|row| {
                let next = bytes + 40 + row.text.len();
                if next > PAGE_BYTES {
                    return false;
                }
                bytes = next;
                true
            })
            .count();
        let mut out = Vec::with_capacity(bytes);
        out.extend((first + count as u64).to_be_bytes());
        out.extend(evicted.to_be_bytes());
        out.extend(self.written.to_be_bytes());
        for row in rows.take(count) {
            out.extend((row.text.len() as u32).to_be_bytes());
            for field in row.fields {
                out.extend(field.to_be_bytes());
            }
            out.extend(row.text.as_bytes());
        }
        out
    }
}

static LIVE: AtomicUsize = AtomicUsize::new(0);
#[derive(Default)]
struct Slot(Mutex<Capture>);
impl Drop for Slot {
    fn drop(&mut self) {
        LIVE.fetch_sub(1, Ordering::AcqRel);
    }
}
type Registry = Mutex<HashMap<i64, Arc<Slot>>>;
static LOGS: LazyLock<Registry> = LazyLock::new(Mutex::default);
static NEXT: AtomicI64 = AtomicI64::new(1);
fn access<T>(id: i64, op: impl FnOnce(&mut Capture) -> T) -> Result<T, &'static str> {
    let log = LOGS
        .lock()
        .map_err(|_| "Log registry unavailable")?
        .get(&id)
        .cloned()
        .ok_or("Log capture closed")?;
    let mut data = log.0.lock().map_err(|_| "Log capture unavailable")?;
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
        // All opens hold the registry lock. Concurrent drops can only free a slot.
        if LIVE.load(Ordering::Acquire) < MAX_CAPTURES {
            LIVE.fetch_add(1, Ordering::AcqRel);
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
    if let Err(e) = access(id, |capture| {
        let mut buf = std::mem::take(&mut capture.scratch);
        if buf.capacity() < size as usize {
            buf.reserve_exact(size as usize - buf.len());
        }
        buf.resize(size as usize, 0);
        if env.get_byte_array_region(&array, 0, &mut buf).is_ok() {
            // jbyte and u8 share layout. The detached buffer stays alive for this borrow.
            let bytes = unsafe { std::slice::from_raw_parts(buf.as_ptr().cast(), buf.len()) };
            capture.append(bytes);
        }
        capture.scratch = buf;
    }) {
        fail(&mut env, e)
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_noteLog(
    mut env: JNIEnv,
    _: JClass,
    id: i64,
    text: JString,
) {
    if !matches!(
        env.call_method(&text, "length", "()I", &[])
            .and_then(|v| v.i()),
        Ok(0..=16384)
    ) {
        fail(&mut env, "Invalid log marker length");
        return;
    }
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
pub extern "system" fn Java_dev_rprobedesk_NativeCore_readLogPage(
    mut env: JNIEnv,
    _: JClass,
    id: i64,
    after: i64,
) -> jbyteArray {
    if after < 0 {
        fail(&mut env, "Invalid log cursor");
        return std::ptr::null_mut();
    }
    match access(id, |l| {
        let bytes = l.delta(after as u64);
        env.byte_array_from_slice(&bytes)
            .map_or(std::ptr::null_mut(), |v| v.into_raw())
    }) {
        Ok(array) => array,
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
    fn pages_keep_sequence_and_fit_byte_and_record_budgets() {
        let mut capture = Capture::default();
        for index in 1..=24_000 {
            capture.row(&format!("中文💻 {index}"), false);
        }
        let mut cursor: u64 = 0;
        let mut seen = 0;
        while cursor < capture.written {
            let packet = capture.delta(cursor);
            let number = |pos| u64::from_be_bytes(packet[pos..pos + 8].try_into().unwrap());
            let (end, evicted, latest) = (number(0), number(8), number(16));
            assert_eq!(latest, 24_000);
            assert_eq!(evicted, latest - ROWS as u64);
            let first = cursor.max(evicted);
            assert!(end > first && end - first <= PAGE_ROWS as u64);
            assert!(packet.len() <= PAGE_BYTES);
            let mut pos = HEADER;
            for sequence in first + 1..=end {
                let size = u32::from_be_bytes(packet[pos..pos + 4].try_into().unwrap()) as usize;
                pos += 40;
                assert_eq!(
                    &packet[pos..pos + size],
                    format!("中文💻 {sequence}").as_bytes()
                );
                pos += size;
                seen += 1;
            }
            assert_eq!(pos, packet.len());
            cursor = end;
        }
        assert_eq!(seen, ROWS);
    }

    #[test]
    fn independent_captures_bound_long_rows_and_serialization() {
        let input = "测💻".repeat(5000);
        let mut captures: Vec<_> = (0..MAX_CAPTURES).map(|_| Capture::default()).collect();
        std::thread::scope(|scope| {
            for capture in &mut captures {
                let input = &input;
                scope.spawn(move || {
                    for _ in 0..180 {
                        capture.row(input, false);
                    }
                    let page = capture.delta(0);
                    assert!(page.len() <= PAGE_BYTES && page.capacity() <= PAGE_BYTES);
                    assert!(capture.rows.len() < 180);
                    assert!(capture.rows.back().unwrap().text.encode_utf16().count() <= TEXT_UNITS);
                    assert_eq!(
                        capture.text_bytes,
                        capture
                            .rows
                            .iter()
                            .map(|r| r.text.capacity())
                            .sum::<usize>()
                    );
                });
            }
        });
        let used: usize = captures
            .iter()
            .map(|c| {
                c.text_bytes
                    + c.rows.capacity() * std::mem::size_of::<Row>()
                    + CHUNK
                    + LINE_BYTES
                    + PAGE_BYTES
                    + 4 * LINE_BYTES
                    + std::mem::size_of::<Capture>()
            })
            .sum();
        assert!(used <= MAX_CAPTURES * CAPTURE_BYTES);
    }

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
