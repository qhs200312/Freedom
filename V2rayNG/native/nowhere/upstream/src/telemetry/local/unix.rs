// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Protected Unix-domain sockets and per-user telemetry registry access.

use anyhow::{Result, bail};
use std::io;
use std::os::unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use tokio::net::{UnixListener, UnixStream};

pub(in crate::telemetry) type Stream = UnixStream;
pub(in crate::telemetry) struct Listener(UnixListener);
pub(in crate::telemetry) fn directory() -> PathBuf {
    std::env::temp_dir().join(format!(
        "nowhere-telemetry-{}",
        super::super::process::process_uid()
    ))
}
pub(in crate::telemetry) fn validate_directory(path: &Path) -> Result<()> {
    let m = std::fs::symlink_metadata(path)?;
    if !m.is_dir() || m.uid() != super::super::process::process_uid() || m.mode() & 0o777 != 0o700 {
        bail!("unsafe telemetry directory");
    }
    Ok(())
}
pub(in crate::telemetry) fn prepare_directory() -> Result<PathBuf> {
    if namespace() == "unavailable" {
        bail!("namespace identity unavailable");
    }
    let path = directory();
    if !path.is_absolute() {
        bail!("temporary directory must be absolute");
    }
    match std::fs::DirBuilder::new().mode(0o700).create(&path) {
        Ok(()) => (),
        Err(e) if e.kind() == io::ErrorKind::AlreadyExists => (),
        Err(e) => return Err(e.into()),
    }
    validate_directory(&path)?;
    Ok(path)
}
fn private_object(path: &Path, socket: bool) -> Result<()> {
    use std::os::unix::fs::FileTypeExt;
    validate_directory(&directory())?;
    let m = std::fs::symlink_metadata(path)?;
    if m.uid() != super::super::process::process_uid()
        || m.mode() & 0o777 != 0o600
        || (socket && !m.file_type().is_socket())
        || (!socket && !m.is_file())
    {
        bail!("unsafe telemetry object");
    }
    Ok(())
}
pub(in crate::telemetry) fn read_registry(path: &Path) -> Result<Vec<u8>> {
    use std::io::Read;
    private_object(path, false)?;
    let f = std::fs::OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(path)?;
    let m = f.metadata()?;
    if m.uid() != super::super::process::process_uid() || m.mode() & 0o777 != 0o600 || !m.is_file()
    {
        bail!("unsafe telemetry registry");
    }
    let mut data = Vec::new();
    f.take(4097).read_to_end(&mut data)?;
    if data.len() > 4096 {
        bail!("oversized registry");
    }
    Ok(data)
}
pub(in crate::telemetry) fn publish(path: &Path, payload: &[u8]) -> Result<()> {
    use std::io::Write;
    validate_directory(&directory())?;
    let tmp = path.with_extension("pending");
    let mut created = false;
    let result = (|| -> Result<()> {
        let mut f = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW)
            .open(&tmp)?;
        created = true;
        f.write_all(payload)?;
        if path.exists() {
            bail!("registry already exists");
        }
        std::fs::rename(&tmp, path)?;
        Ok(())
    })();
    if result.is_err() && created {
        let _ = std::fs::remove_file(tmp);
    }
    result
}
pub(in crate::telemetry) fn endpoint(id: &str) -> String {
    directory()
        .join(format!("{}.sock", &id[..16]))
        .to_string_lossy()
        .into_owned()
}
fn validate_endpoint(value: &str) -> Result<()> {
    let p = Path::new(value);
    if p.parent() != Some(directory().as_path())
        || p.extension().and_then(|v| v.to_str()) != Some("sock")
    {
        bail!("invalid local endpoint");
    }
    Ok(())
}
impl Listener {
    pub(in crate::telemetry) fn bind(endpoint: &str) -> Result<Self> {
        validate_endpoint(endpoint)?;
        let listener = UnixListener::bind(endpoint)?;
        if let Err(e) = std::fs::set_permissions(endpoint, std::fs::Permissions::from_mode(0o600)) {
            let _ = std::fs::remove_file(endpoint);
            return Err(e.into());
        }
        Ok(Self(listener))
    }
    pub(in crate::telemetry) async fn accept(&mut self) -> Result<Stream> {
        let (stream, _) = self.0.accept().await?;
        if stream.peer_cred()?.uid() != super::super::process::process_uid() {
            bail!("peer identity mismatch");
        }
        Ok(stream)
    }
}
pub(in crate::telemetry) async fn connect(endpoint: &str, _pid: u32) -> Result<Stream> {
    validate_endpoint(endpoint)?;
    private_object(Path::new(endpoint), true)?;
    let stream = UnixStream::connect(endpoint).await?;
    if stream.peer_cred()?.uid() != super::super::process::process_uid() {
        bail!("server identity mismatch");
    }
    #[cfg(target_os = "linux")]
    if stream.peer_cred()?.pid() != Some(_pid as i32) {
        bail!("server process mismatch");
    }
    Ok(stream)
}
pub(in crate::telemetry) fn cleanup(endpoint: &str) {
    if validate_endpoint(endpoint).is_ok() && validate_directory(&directory()).is_ok() {
        let _ = std::fs::remove_file(endpoint);
    }
}
pub(in crate::telemetry) fn namespace() -> String {
    #[cfg(target_os = "linux")]
    {
        let identity = (|| -> std::io::Result<String> {
            let boot = std::fs::read_to_string("/proc/sys/kernel/random/boot_id")?;
            let pid = std::fs::read_link("/proc/self/ns/pid")?;
            let user = std::fs::read_link("/proc/self/ns/user")?;
            Ok(format!(
                "{};{};{}",
                boot.trim(),
                pid.display(),
                user.display()
            ))
        })();
        identity.unwrap_or_else(|_| "unavailable".to_owned())
    }
    #[cfg(not(target_os = "linux"))]
    {
        String::new()
    }
}
