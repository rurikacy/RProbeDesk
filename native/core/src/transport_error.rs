use jni::{objects::JThrowable, JNIEnv};
use std::{fmt, io};

#[derive(Debug)]
struct IdentityChange(&'static str);
impl fmt::Display for IdentityChange {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.0)
    }
}
impl std::error::Error for IdentityChange {}
#[cfg(any(windows, test))]
pub fn identity(detail: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::PermissionDenied, IdentityChange(detail))
}
pub(crate) fn code(e: &io::Error) -> i32 {
    use io::ErrorKind::*;
    if let Some(source) = e
        .get_ref()
        .and_then(|cause| cause.downcast_ref::<std::sync::Arc<io::Error>>())
    {
        return code(source);
    }
    match e.kind() {
        TimedOut => 1,
        NetworkUnreachable | HostUnreachable | NetworkDown | AddrNotAvailable => 2,
        ConnectionRefused => 3,
        PermissionDenied if e.get_ref().is_some_and(|e| e.is::<IdentityChange>()) => 5,
        PermissionDenied => 4,
        ConnectionReset | ConnectionAborted | NotConnected | BrokenPipe | UnexpectedEof => 6,
        InvalidData | InvalidInput | Unsupported => 7,
        Interrupted => 8,
        WouldBlock | OutOfMemory => 10,
        _ => 9,
    }
}
pub fn throw(env: &mut JNIEnv, e: &io::Error) {
    if !matches!(env.exception_check(), Ok(false)) {
        return;
    }
    let code = code(e);
    let class = if matches!(code, 4 | 5) {
        "dev/rprobedesk/PeerRejected"
    } else {
        "dev/rprobedesk/TransportFailure"
    };
    if let Ok(message) = env.new_string(e.to_string()) {
        if let Ok(failure) = env.new_object(
            class,
            "(ILjava/lang/String;)V",
            &[code.into(), (&message).into()],
        ) {
            let _ = env.throw(JThrowable::from(failure));
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn codes_follow_typed_causes_even_when_the_diagnostic_is_identical() {
        for (kind, expected) in [
            (io::ErrorKind::TimedOut, 1),
            (io::ErrorKind::NetworkUnreachable, 2),
            (io::ErrorKind::ConnectionRefused, 3),
            (io::ErrorKind::PermissionDenied, 4),
            (io::ErrorKind::UnexpectedEof, 6),
            (io::ErrorKind::InvalidData, 7),
            (io::ErrorKind::Interrupted, 8),
            (io::ErrorKind::Other, 9),
            (io::ErrorKind::WouldBlock, 10),
        ] {
            assert_eq!(code(&io::Error::new(kind, "same")), expected);
        }
        assert_eq!(code(&identity("same")), 5);
        for (error, expected) in [
            (identity("pin changed"), 5),
            (io::ErrorKind::PermissionDenied.into(), 4),
            (io::ErrorKind::WouldBlock.into(), 10),
            (io::ErrorKind::Interrupted.into(), 8),
        ] {
            assert_eq!(
                code(&io::Error::new(error.kind(), std::sync::Arc::new(error))),
                expected
            );
        }
    }
}
