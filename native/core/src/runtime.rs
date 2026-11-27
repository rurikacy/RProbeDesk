use std::io;
use std::sync::LazyLock;
use tokio::runtime::{Builder, Runtime};

// One executor per application process. Platform adapters submit work after releasing
// registry locks. Android keeps socket ownership in its AndroidKeyStore-backed adapter.
static RUNTIME: LazyLock<io::Result<Runtime>> = LazyLock::new(|| {
    Builder::new_multi_thread()
        .thread_name("rprobe-core")
        .worker_threads(2)
        .max_blocking_threads(4)
        .enable_all()
        .build()
});

pub(crate) fn runtime() -> io::Result<&'static Runtime> {
    RUNTIME.as_ref().map_err(io::Error::other)
}
