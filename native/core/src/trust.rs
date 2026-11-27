use base64::{engine::general_purpose::STANDARD, Engine};
use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jboolean, jstring},
    JNIEnv,
};
use sha2::{Digest, Sha256};

pub(super) fn fingerprint(spki: &[u8]) -> String {
    STANDARD.encode(Sha256::digest(spki))
}

#[repr(i32)]
#[derive(Debug, PartialEq)]
enum Decision {
    Accept,
    Confirm,
    RequirePair,
    RejectKey,
    RejectPlaintext,
}
fn decide(
    expected: &str,
    current: &str,
    previous: &str,
    known: bool,
    paired: bool,
    require_pair: bool,
) -> Decision {
    use Decision::*;
    if current.is_empty() {
        return if expected.starts_with("tls:") || !previous.is_empty() || paired {
            RejectPlaintext
        } else {
            Accept
        };
    }
    if !expected.is_empty() && expected != current {
        return RejectKey;
    }
    if current == previous {
        return Accept;
    }
    if !previous.is_empty() && (!expected.is_empty() || !paired) {
        return RejectKey;
    }
    if previous.is_empty() && (known || current == expected) {
        return Accept;
    }
    if require_pair && !paired {
        RequirePair
    } else {
        Confirm
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_fingerprint(
    mut env: JNIEnv,
    _: JClass,
    spki: JByteArray,
) -> jstring {
    if !matches!(env.get_array_length(&spki), Ok(1..=65536)) {
        if matches!(env.exception_check(), Ok(false)) {
            let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid SPKI size");
        }
        return std::ptr::null_mut();
    }
    let result = env
        .convert_byte_array(spki)
        .and_then(|der| env.new_string(fingerprint(&der)));
    match result {
        Ok(s) => s.into_raw(),
        Err(e) => {
            if matches!(env.exception_check(), Ok(false)) {
                let _ = env.throw_new("java/lang/IllegalStateException", e.to_string());
            }
            std::ptr::null_mut()
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_rprobedesk_NativeCore_trust(
    mut env: JNIEnv,
    _: JClass,
    expected: JString,
    current: JString,
    previous: JString,
    known: jboolean,
    paired: jboolean,
    require_pair: jboolean,
) -> i32 {
    let values = [expected, current, previous].map(|s| env.get_string(&s).map(String::from));
    match values {
        [Ok(e), Ok(c), Ok(p)] => {
            decide(&e, &c, &p, known != 0, paired != 0, require_pair != 0) as i32
        }
        _ => Decision::RejectKey as i32,
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn identity_and_endpoint_have_independent_lifetimes() {
        use Decision::*;
        assert_eq!(decide("tls:a", "tls:a", "", false, false, true), Accept);
        assert_eq!(decide("tls:a", "tls:b", "", true, true, false), RejectKey);
        assert_eq!(
            decide("tls:a", "", "", false, false, false),
            RejectPlaintext
        );
        assert_eq!(decide("", "tls:b", "tls:a", false, true, true), Confirm);
        assert_eq!(decide("", "tls:a", "", false, false, true), RequirePair);
        assert_eq!(decide("", "tls:a", "", false, false, false), Confirm);
    }
}
