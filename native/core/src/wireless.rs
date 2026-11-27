//! Windows STLS data path. Pairing and the classic transport keep using Platform-Tools.
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
use tokio::runtime::{Builder, Runtime};
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

pub(super) struct Device {
    writer: Writer,
    pump: JoinHandle<()>,
    pin: String,
    channels: Mutex<HashMap<i64, Channel>>,
}
impl Drop for Device {
    fn drop(&mut self) {
        self.pump.abort();
    }
}

static RUNTIME: LazyLock<io::Result<Runtime>> = LazyLock::new(|| {
    Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
});
static DEVICES: LazyLock<Mutex<HashMap<i64, Arc<Device>>>> = LazyLock::new(Mutex::default);
static NEXT: AtomicI64 = AtomicI64::new(1);
fn id() -> i64 {
    NEXT.fetch_add(1, Ordering::Relaxed)
}
fn err(e: impl std::fmt::Display) -> io::Error {
    io::Error::other(e.to_string())
}
pub(super) fn runtime() -> io::Result<&'static Runtime> {
    RUNTIME.as_ref().map_err(err)
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
        return Err(err("Invalid ADB packet magic"));
    }
    match &header[..4] {
        b"STLS" => Ok(true),
        b"AUTH" | b"CNXN" => Ok(false),
        _ => Err(err("Unexpected ADB greeting")),
    }
}

pub(super) async fn connect(addr: SocketAddr, key_dir: &Path, expected: String) -> io::Result<i64> {
    if !timeout(DEADLINE, offers_tls(addr)).await.map_err(err)?? {
        return if expected.is_empty() {
            Ok(0)
        } else {
            Err(denied("已核对的 TLS 设备返回了明文连接"))
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
        .map_err(err)??;
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
    .map_err(err)?
    .map_err(|e| match e {
        libadb::Error::Auth(libadb::error::AuthError::TlsClosedBeforeConnect) => io::Error::new(
            io::ErrorKind::ConnectionReset,
            "目标在 TLS 握手后关闭了连接",
        ),
        libadb::Error::Auth(_) => denied("无线调试身份认证失败，请在目标设备核对配对状态"),
        _ if peer.changed.load(Ordering::Relaxed) => {
            denied("目标 TLS 身份核验失败，请手动核对设备")
        }
        _ => err(e),
    })?;
    let pin = peer
        .observed
        .get()
        .cloned()
        .ok_or_else(|| denied("设备没有提供经过签名验证的 TLS 身份"))?;
    let (reader, writer) = conn.split().map_err(err)?;
    let mut devices = DEVICES.lock().map_err(err)?;
    if devices.len() >= 32 {
        return Err(err("并行 TLS 会话已达上限"));
    }
    let handle = id();
    devices.insert(
        handle,
        Arc::new(Device {
            writer,
            pin,
            channels: Mutex::default(),
            pump: tokio::spawn(async move {
                let _ = reader.run().await;
            }),
        }),
    );
    Ok(handle)
}
pub(super) fn device(handle: i64) -> io::Result<Arc<Device>> {
    DEVICES
        .lock()
        .map_err(err)?
        .get(&handle)
        .cloned()
        .ok_or_else(|| err("无线会话已关闭"))
}
pub(super) fn close(handle: i64) {
    if let Ok(mut devices) = DEVICES.lock() {
        if let Some(d) = devices.remove(&handle) {
            d.pump.abort();
        }
    }
}
impl Device {
    pub(super) fn pin(&self) -> &str {
        &self.pin
    }
    pub(super) async fn open(&self, name: &str) -> io::Result<i64> {
        if name.contains('\0') || name.len() > 1024 {
            return Err(err("Invalid service"));
        }
        let mut dest = name.as_bytes().to_vec();
        dest.push(0);
        let channel = timeout(DEADLINE, self.writer.open_channel(&dest))
            .await
            .map_err(err)?
            .map_err(err)?;
        let handle = id();
        self.channels.lock().map_err(err)?.insert(handle, channel);
        Ok(handle)
    }
    fn channel(&self, handle: i64) -> io::Result<Channel> {
        self.channels
            .lock()
            .map_err(err)?
            .get(&handle)
            .copied()
            .ok_or_else(|| err("ADB stream closed"))
    }
    pub(super) async fn read(&self, h: i64, buf: &mut [u8]) -> io::Result<i32> {
        match self.writer.read_channel(self.channel(h)?, buf).await {
            Ok(n) => Ok(n as i32),
            Err(libadb::Error::ChannelClosed) => Ok(-1),
            Err(e) => Err(err(e)),
        }
    }
    pub(super) async fn write(&self, h: i64, data: &[u8]) -> io::Result<()> {
        timeout(DEADLINE, self.writer.write_channel(self.channel(h)?, data))
            .await
            .map_err(err)?
            .map_err(err)
    }
    pub(super) async fn close_stream(&self, h: i64) {
        let channel = self
            .channels
            .lock()
            .ok()
            .and_then(|mut channels| channels.remove(&h));
        if let Some(ch) = channel {
            let _ = timeout(DEADLINE, self.writer.close_channel(ch)).await;
        }
    }
}
