//! Windows STLS data path. Pairing and the classic transport keep using Platform-Tools.
use crate::runtime::runtime;
use libadb::auth::Authenticator;
use libadb::keys::{rsa::rand_core::OsRng, store, KeyName};
use libadb::tls::{TlsClientConfig, TlsIdentity};
use libadb::transport::tls::{MaybeTls, MaybeTlsWrite};
use libadb::{Connection, ConnectionConfig, HostBanner, TokioTcp};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{ring, verify_tls13_signature};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, SignatureScheme};
use std::collections::HashMap;
use std::io;
use std::net::SocketAddr;
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, LazyLock, Mutex, OnceLock};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::{watch, OwnedSemaphorePermit, Semaphore};
use tokio::task::JoinHandle;
use tokio::time::timeout;
use x509_cert::der::{Decode, Encode};

const DEADLINE: Duration = Duration::from_secs(15);
type Writer = libadb::split::Writer<MaybeTlsWrite<TokioTcp>>;
type Channel = libadb::channel::ChannelId;

#[derive(Debug)]
struct Peer {
    expected: String,
    observed: OnceLock<String>,
    changed: AtomicBool,
}

impl ServerCertVerifier for Peer {
    fn verify_server_cert(
        &self,
        leaf: &CertificateDer<'_>,
        _: &[CertificateDer<'_>],
        _: &ServerName<'_>,
        _: &[u8],
        _: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        let invalid = |_| rustls::Error::InvalidCertificate(rustls::CertificateError::BadEncoding);
        let cert = x509_cert::Certificate::from_der(leaf.as_ref()).map_err(invalid)?;
        let spki = cert
            .tbs_certificate
            .subject_public_key_info
            .to_der()
            .map_err(invalid)?;
        let pin = format!("tls:{}", crate::trust::fingerprint(&spki));
        if !self.expected.is_empty() && pin != self.expected {
            self.changed.store(true, Ordering::Relaxed);
            return Err(rustls::Error::InvalidCertificate(
                rustls::CertificateError::ApplicationVerificationFailure,
            ));
        }
        self.observed
            .set(pin)
            .map_err(|_| rustls::Error::General("Repeated device certificate".into()))?;
        Ok(ServerCertVerified::assertion())
    }
    fn verify_tls12_signature(
        &self,
        _: &[u8],
        _: &CertificateDer<'_>,
        _: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Err(rustls::Error::PeerIncompatible(
            rustls::PeerIncompatible::Tls12NotOffered,
        ))
    }
    fn verify_tls13_signature(
        &self,
        msg: &[u8],
        cert: &CertificateDer<'_>,
        signature: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls13_signature(
            msg,
            cert,
            signature,
            &ring::default_provider().signature_verification_algorithms,
        )
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        ring::default_provider()
            .signature_verification_algorithms
            .supported_schemes()
    }
}

struct TlsOnly;
impl Authenticator for TlsOnly {
    type Error = &'static str;
    async fn sign(&mut self, _: &[u8]) -> Result<Vec<u8>, Self::Error> {
        Err("TLS was required")
    }
    fn public_key(&self) -> &[u8] {
        &[]
    }
    fn offer_public_key(&self) -> bool {
        false
    }
}

struct Stream {
    ch: Channel,
    cancel: watch::Sender<bool>,
    _slot: OwnedSemaphorePermit,
}

pub(super) struct Device {
    writer: Writer,
    pump: JoinHandle<()>,
    pin: String,
    channels: Mutex<HashMap<i64, Arc<Stream>>>,
    ended: watch::Sender<Option<(io::ErrorKind, String)>>,
    pub(super) observing: AtomicBool,
    slots: Arc<Semaphore>,
}
impl Drop for Device {
    fn drop(&mut self) {
        self.pump.abort();
    }
}

static DEVICES: LazyLock<Mutex<HashMap<i64, Arc<Device>>>> = LazyLock::new(Mutex::default);
static NEXT: AtomicI64 = AtomicI64::new(1);
fn id() -> i64 {
    NEXT.fetch_add(1, Ordering::Relaxed)
}
fn err(e: impl std::fmt::Display) -> io::Error {
    io::Error::other(e.to_string())
}
fn transport_failure(e: libadb::Error<libadb::transport::tls::TlsError<io::Error>>) -> io::Error {
    use libadb::transport::tls::TlsError;
    use libadb::Error::*;
    let kind = match e {
        Io(TlsError::Io(source)) => return source,
        Io(TlsError::Tls(rustls::Error::InvalidCertificate(_))) | Auth(_) => {
            io::ErrorKind::PermissionDenied
        }
        Io(TlsError::HandshakeClosed | TlsError::WriteZero)
        | ChannelClosed
        | UnexpectedEof
        | Disconnected => io::ErrorKind::NotConnected,
        NoFreeChannels | ReceiveBufferFull => io::ErrorKind::WouldBlock,
        _ => io::ErrorKind::InvalidData,
    };
    io::Error::new(kind, e)
}
fn denied(msg: &str) -> io::Error {
    io::Error::new(io::ErrorKind::PermissionDenied, msg)
}

// Only distinguish the two protocols on an explicitly selected endpoint. No key or device
// command is sent on this socket. The actual application transport verifies its own peer.
async fn offers_tls(addr: SocketAddr) -> io::Result<bool> {
    let mut socket = TcpStream::connect(addr).await?;
    let banner = b"host::features=shell_v2;\0";
    let connect = u32::from_le_bytes(*b"CNXN");
    let mut bytes = Vec::with_capacity(24 + banner.len());
    for field in [
        connect,
        0x0100_0001,
        1048576,
        banner.len() as u32,
        banner.iter().map(|&b| u32::from(b)).sum(),
        !connect,
    ] {
        bytes.extend_from_slice(&field.to_le_bytes());
    }
    bytes.extend_from_slice(banner);
    socket.write_all(&bytes).await?;
    let mut header = [0u8; 24];
    socket.read_exact(&mut header).await?;
    if !header[..4].iter().zip(&header[20..]).all(|(a, b)| *a == !b) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Invalid ADB packet magic",
        ));
    }
    match &header[..4] {
        b"STLS" => Ok(true),
        b"AUTH" | b"CNXN" => Ok(false),
        _ => Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Unexpected ADB greeting",
        )),
    }
}

pub(super) async fn connect(addr: SocketAddr, key_dir: &Path, expected: String) -> io::Result<i64> {
    if !timeout(DEADLINE, offers_tls(addr))
        .await
        .map_err(|_| io::ErrorKind::TimedOut)??
    {
        return if expected.is_empty() {
            Ok(0)
        } else {
            Err(crate::transport_error::identity(
                "已核对的 TLS 设备返回了明文连接",
            ))
        };
    }
    let key = store::load(
        key_dir,
        &mut OsRng,
        KeyName::new("RProbeDesk").map_err(err)?,
    )
    .map_err(|_| denied("无法读取当前 Platform-Tools 主控密钥，请核对已配对的 Windows 用户"))?;
    let identity = TlsIdentity::from_key(&key, &mut OsRng).map_err(err)?;
    let peer = Arc::new(Peer {
        expected,
        observed: OnceLock::new(),
        changed: AtomicBool::new(false),
    });
    let mut config = (**TlsClientConfig::adb(&identity).map_err(err)?.rustls()).clone();
    config.dangerous().set_certificate_verifier(peer.clone());
    let tls =
        TlsClientConfig::from_rustls(Arc::new(config), ServerName::try_from("adb").map_err(err)?);
    let socket = timeout(DEADLINE, TcpStream::connect(addr))
        .await
        .map_err(|_| io::ErrorKind::TimedOut)??;
    socket.set_nodelay(true)?;
    let conn = timeout(
        DEADLINE,
        Connection::connect_tls(
            MaybeTls::plain(TokioTcp::new(socket)),
            TlsOnly,
            HostBanner::Features(&[libadb::Feature::ShellV2]),
            ConnectionConfig::new(),
            &tls,
        ),
    )
    .await
    .map_err(|_| io::ErrorKind::TimedOut)?
    .map_err(|e| match e {
        _ if peer.changed.load(Ordering::Relaxed) => {
            crate::transport_error::identity("目标 TLS 身份核验失败，请手动核对设备")
        }
        libadb::Error::Auth(libadb::error::AuthError::TlsClosedBeforeConnect) => io::Error::new(
            io::ErrorKind::ConnectionReset,
            "目标在 TLS 握手后关闭了连接",
        ),
        libadb::Error::Auth(_) => denied("无线调试身份认证失败，请在目标设备核对配对状态"),
        _ => transport_failure(e),
    })?;
    let pin = peer
        .observed
        .get()
        .cloned()
        .ok_or_else(|| denied("设备没有提供经过签名验证的 TLS 身份"))?;
    let (reader, writer) = conn.split().map_err(err)?;
    let mut devices = DEVICES.lock().map_err(err)?;
    if devices.len() >= 32 {
        return Err(io::Error::new(
            io::ErrorKind::WouldBlock,
            "并行 TLS 会话已达上限",
        ));
    }
    let handle = id();
    let (ended, _) = watch::channel(None);
    let notify = ended.clone();
    devices.insert(
        handle,
        Arc::new(Device {
            writer,
            pin,
            channels: Mutex::default(),
            ended,
            observing: AtomicBool::new(false),
            slots: Arc::new(Semaphore::new(16)),
            pump: tokio::spawn(async move {
                let reason = match reader.run().await {
                    Ok(never) => match never {},
                    Err(error) => transport_failure(error),
                };
                notify.send_replace(Some((reason.kind(), reason.to_string())));
            }),
        }),
    );
    Ok(handle)
}
static DIALS: LazyLock<Mutex<HashMap<i64, watch::Sender<bool>>>> = LazyLock::new(Mutex::default);
pub(super) fn new_dial() -> io::Result<i64> {
    let mut slots = DIALS.lock().map_err(err)?;
    if slots.len() >= 8 {
        return Err(io::ErrorKind::WouldBlock.into());
    }
    let id = id();
    slots.insert(id, watch::channel(false).0);
    Ok(id)
}
pub(super) fn cancel_dial(id: i64) {
    if let Some(tx) = DIALS.lock().ok().and_then(|mut slots| slots.remove(&id)) {
        tx.send_replace(true);
    }
}
pub(super) async fn dial(id: i64, addr: SocketAddr, dir: &Path, pin: String) -> io::Result<i64> {
    let mut cancel = DIALS
        .lock()
        .map_err(err)?
        .get(&id)
        .map(watch::Sender::subscribe)
        .ok_or(io::ErrorKind::Interrupted)?;
    tokio::select! {
        biased;
        _ = cancel.wait_for(|v| *v) => Err(io::ErrorKind::Interrupted.into()),
        result = connect(addr,dir,pin) => result,
    }
}
pub(super) fn device(handle: i64) -> io::Result<Arc<Device>> {
    DEVICES
        .lock()
        .map_err(err)?
        .get(&handle)
        .cloned()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotConnected, "无线会话已关闭"))
}
pub(super) fn close(handle: i64) {
    let removed = DEVICES
        .lock()
        .ok()
        .and_then(|mut devices| devices.remove(&handle));
    if let Some(d) = removed {
        d.ended
            .send_replace(Some((io::ErrorKind::NotConnected, "设备会话已关闭".into())));
        d.pump.abort();
    }
}
async fn stopped<T: Clone>(mut rx: watch::Receiver<Option<T>>) -> Option<T> {
    rx.wait_for(Option::is_some)
        .await
        .ok()
        .and_then(|v| v.clone())
}
impl Device {
    pub(super) async fn ended(&self) -> io::Error {
        let (kind, message) = stopped(self.ended.subscribe())
            .await
            .unwrap_or_else(|| (io::ErrorKind::NotConnected, "设备会话已关闭".into()));
        io::Error::new(kind, message)
    }
    pub(super) fn pin(&self) -> &str {
        &self.pin
    }
    pub(super) async fn open(&self, name: &str) -> io::Result<i64> {
        if name.contains('\0') || name.len() > 1024 {
            return Err(err("Invalid service"));
        }
        let mut dest = name.as_bytes().to_vec();
        dest.push(0);
        let slot = self
            .slots
            .clone()
            .try_acquire_owned()
            .map_err(|_| io::Error::new(io::ErrorKind::WouldBlock, "设备通道数已达上限"))?;
        let channel = tokio::select! {
            reason = self.ended() => return Err(reason),
            result = timeout(DEADLINE, self.writer.open_channel(&dest)) =>
                result.map_err(|_| io::ErrorKind::TimedOut)?.map_err(transport_failure)?,
        };
        let handle = id();
        let (cancel, _) = watch::channel(false);
        self.channels.lock().map_err(err)?.insert(
            handle,
            Arc::new(Stream {
                ch: channel,
                cancel,
                _slot: slot,
            }),
        );
        Ok(handle)
    }
    fn channel(&self, handle: i64) -> io::Result<Arc<Stream>> {
        self.channels
            .lock()
            .map_err(err)?
            .get(&handle)
            .cloned()
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotConnected, "ADB stream closed"))
    }
    pub(super) async fn read(&self, h: i64, buf: &mut [u8], idle_ms: i32) -> io::Result<i32> {
        let stream = self.channel(h)?;
        let mut cancel = stream.cancel.subscribe();
        let read = async {
            match self.writer.read_channel(stream.ch, buf).await {
                Ok(n) => Ok(n as i32),
                Err(libadb::Error::ChannelClosed) => Ok(-1),
                Err(e) => Err(transport_failure(e)),
            }
        };
        tokio::select! {
            biased;
            _ = cancel.wait_for(|v| *v) => Err(io::ErrorKind::Interrupted.into()),
            reason = self.ended() => Err(reason),
            _ = tokio::time::sleep(Duration::from_millis(idle_ms as u64)), if idle_ms > 0 => Err(io::ErrorKind::TimedOut.into()),
            value = read => value,
        }
    }
    pub(super) async fn write(&self, h: i64, data: &[u8]) -> io::Result<()> {
        let stream = self.channel(h)?;
        let mut cancel = stream.cancel.subscribe();
        tokio::select! {
            biased;
            _ = cancel.wait_for(|v| *v) => Err(io::ErrorKind::Interrupted.into()),
            reason = self.ended() => Err(reason),
            result = timeout(DEADLINE, self.writer.write_channel(stream.ch, data)) =>
                result.map_err(|_| io::ErrorKind::TimedOut)?.map_err(transport_failure),
        }
    }
    pub(super) fn close_stream(self: Arc<Self>, h: i64) {
        let stream = self
            .channels
            .lock()
            .ok()
            .and_then(|mut channels| channels.remove(&h));
        if let Some(stream) = stream {
            stream.cancel.send_replace(true);
            if let Ok(rt) = runtime() {
                rt.spawn(async move {
                    tokio::select! {
                        _ = self.ended() => (),
                        _ = timeout(DEADLINE, self.writer.close_channel(stream.ch)) => (),
                    }
                });
            }
        }
    }
}
