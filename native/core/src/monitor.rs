use jni::objects::{JClass, JLongArray, JObject, JString};
use jni::sys::jobject;
use jni::JNIEnv;

#[derive(Default)]
struct Reading {
    cpu: Vec<i64>,
    fallback: Option<f64>,
    memory: Option<i64>,
    available: Option<i64>,
    gpu: Option<f64>,
    load: Option<f64>,
    cores: Option<i32>,
    disk: Option<f64>,
    disk_total: Option<i64>,
    disk_used: Option<i64>,
    level: Option<i32>,
    scale: Option<i32>,
    status: Option<i32>,
    temperature: Option<i32>,
    test_battery: bool,
}

fn percent(raw: &str) -> Option<f64> {
    raw.trim_end_matches('%')
        .trim()
        .parse()
        .ok()
        .filter(|v| (0.0..=100.0).contains(v))
}

impl Reading {
    fn parse(text: &str) -> Self {
        let mut r = Self::default();
        for line in text.lines().map(str::trim) {
            if let Some(stat) = line.strip_prefix("cpu ") {
                if let Ok(fields) = stat
                    .split_whitespace()
                    .take(8)
                    .map(str::parse::<i64>)
                    .collect::<Result<Vec<_>, _>>()
                {
                    if fields.len() >= 4 && fields.iter().all(|&v| v >= 0) {
                        r.cpu = fields;
                    }
                }
            } else if let Some((value, _)) = line.split_once("% TOTAL:") {
                r.fallback = percent(value);
            } else if let Some((key, value)) = line.split_once(':') {
                let value = value.trim();
                match key {
                    "MemTotal" => {
                        r.memory = value.strip_suffix("kB").and_then(|v| v.trim().parse().ok())
                    }
                    "MemAvailable" => {
                        r.available = value.strip_suffix("kB").and_then(|v| v.trim().parse().ok())
                    }
                    "level" => r.level = value.parse().ok(),
                    "scale" => r.scale = value.parse().ok(),
                    "status" => r.status = value.parse().ok(),
                    "temperature" => r.temperature = value.parse().ok(),
                    _ => {}
                }
            } else if let Some((key, value)) = line.split_once('=') {
                match key {
                    "gpu" => r.gpu = percent(value.split('@').next().unwrap_or("")),
                    "disk" => r.disk = percent(value),
                    "diskTotalKiB" => r.disk_total = value.parse().ok(),
                    "diskUsedKiB" => r.disk_used = value.parse().ok(),
                    "load" => {
                        r.load = value
                            .parse()
                            .ok()
                            .filter(|n: &f64| n.is_finite() && *n >= 0.0)
                    }
                    "cores" => r.cores = value.parse().ok().filter(|n| *n > 0),
                    "batterySource" => r.test_battery = value == "test_battery",
                    _ => {}
                }
            }
        }
        r
    }

    fn cpu_percent(&self, previous: &[i64]) -> Option<f64> {
        if self.cpu.len() != previous.len() || self.cpu.is_empty() {
            return self.fallback;
        }
        let mut sum = 0i128;
        let mut idle = 0i128;
        for (index, (&now, &old)) in self.cpu.iter().zip(previous).enumerate() {
            if old < 0 || old > now {
                return self.fallback;
            }
            let diff = i128::from(now - old);
            sum += diff;
            if index == 3 || index == 4 {
                idle += diff;
            }
        }
        if sum == 0 {
            self.fallback
        } else {
            Some(100.0 * (sum - idle) as f64 / sum as f64)
        }
    }
}

fn dto<'a>(
    env: &mut JNIEnv<'a>,
    text: JString,
    previous: JLongArray,
) -> jni::errors::Result<JObject<'a>> {
    let input: String = env.get_string(&text)?.into();
    let r = Reading::parse(&input);
    let size = env.get_array_length(&previous)?.clamp(0, 8) as usize;
    let mut old = [0i64; 8];
    env.get_long_array_region(&previous, 0, &mut old[..size])?;
    let cpu = r.cpu_percent(&old[..size]);
    let ticks = env.new_long_array(r.cpu.len() as i32)?;
    env.set_long_array_region(&ticks, 0, &r.cpu)?;
    env.new_object(
        "dev/rprobedesk/MonitorReading",
        "([JDJJDDIDJJIIIIZ)V",
        &[
            (&ticks).into(),
            cpu.unwrap_or(f64::NAN).into(),
            r.memory.unwrap_or(-1).into(),
            r.available.unwrap_or(-1).into(),
            r.gpu.unwrap_or(f64::NAN).into(),
            r.load.unwrap_or(f64::NAN).into(),
            r.cores.unwrap_or(0).into(),
            r.disk.unwrap_or(f64::NAN).into(),
            r.disk_total.unwrap_or(-1).into(),
            r.disk_used.unwrap_or(-1).into(),
            r.level.unwrap_or(-1).into(),
            r.scale.unwrap_or(0).into(),
            r.status.unwrap_or(0).into(),
            r.temperature.unwrap_or(i32::MIN).into(),
            (r.test_battery as u8).into(),
        ],
    )
}

#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_metrics(
    mut env: JNIEnv,
    _: JClass,
    text: JString,
    old: JLongArray,
) -> jobject {
    match dto(&mut env, text, old) {
        Ok(value) => value.into_raw(),
        Err(e) => {
            if matches!(env.exception_check(), Ok(false)) {
                let _ = env.throw_new("java/lang/IllegalStateException", e.to_string());
            }
            std::ptr::null_mut()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn reset_and_overflow_safe_delta() {
        let reading = Reading::parse("cpu 8 8 8 8\n3.5% TOTAL: 0\ngpu=101\nload=NaN");
        assert_eq!(reading.cpu_percent(&[9, 0, 0, 0]), Some(3.5));
        assert_eq!(reading.cpu_percent(&[0; 4]), Some(75.0));
        assert_eq!((reading.gpu, reading.load), (None, None));
    }
}
