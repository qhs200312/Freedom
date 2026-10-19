// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Protected Windows named pipes and per-user telemetry registry access.

use anyhow::{Result, bail};
use sha2::{Digest, Sha256};
use std::{
    io,
    os::windows::{ffi::OsStrExt, fs::MetadataExt, io::AsRawHandle},
    path::{Path, PathBuf},
};
use tokio::net::windows::named_pipe::{
    ClientOptions, NamedPipeClient, NamedPipeServer, ServerOptions,
};
use windows_sys::Win32::{
    Foundation::*,
    Security::{Authorization::*, *},
    Storage::FileSystem::*,
    System::{Pipes::*, Threading::*},
};

pub(in crate::telemetry) type Stream = Box<dyn LocalStream>;
pub(in crate::telemetry) trait LocalStream:
    tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send
{
}
impl LocalStream for NamedPipeServer {}
impl LocalStream for NamedPipeClient {}
fn wide(value: &std::ffi::OsStr) -> Vec<u16> {
    value.encode_wide().chain(Some(0)).collect()
}
fn sid() -> Result<String> {
    unsafe { sid_from_process(GetCurrentProcess()) }
}
fn sid_from_process(process: HANDLE) -> Result<String> {
    unsafe {
        let mut token = std::ptr::null_mut();
        if OpenProcessToken(process, TOKEN_QUERY, &mut token) == 0 {
            return Err(io::Error::last_os_error().into());
        }
        let result = (|| -> Result<String> {
            let mut len = 0;
            GetTokenInformation(token, TokenUser, std::ptr::null_mut(), 0, &mut len);
            let mut buf = vec![0usize; (len as usize).div_ceil(std::mem::size_of::<usize>())];
            if GetTokenInformation(token, TokenUser, buf.as_mut_ptr().cast(), len, &mut len) == 0 {
                return Err(io::Error::last_os_error().into());
            }
            let user = &*(buf.as_ptr().cast::<TOKEN_USER>());
            let mut text = std::ptr::null_mut();
            if ConvertSidToStringSidW(user.User.Sid, &mut text) == 0 {
                return Err(io::Error::last_os_error().into());
            }
            let mut n = 0;
            while *text.add(n) != 0 {
                n += 1;
            }
            let result = String::from_utf16_lossy(std::slice::from_raw_parts(text, n));
            LocalFree(text.cast());
            Ok(result)
        })();
        CloseHandle(token);
        result
    }
}
struct Security(PSECURITY_DESCRIPTOR);
impl Security {
    fn new() -> Result<Self> {
        let sid = sid()?;
        let text = wide(std::ffi::OsStr::new(&format!("O:{sid}D:P(A;;FA;;;{sid})")));
        let mut sd = std::ptr::null_mut();
        if unsafe {
            ConvertStringSecurityDescriptorToSecurityDescriptorW(
                text.as_ptr(),
                1,
                &mut sd,
                std::ptr::null_mut(),
            )
        } == 0
        {
            return Err(io::Error::last_os_error().into());
        }
        Ok(Self(sd))
    }
    fn attrs(&self) -> SECURITY_ATTRIBUTES {
        SECURITY_ATTRIBUTES {
            nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: self.0,
            bInheritHandle: 0,
        }
    }
}
impl Drop for Security {
    fn drop(&mut self) {
        unsafe {
            LocalFree(self.0);
        }
    }
}
fn user_key() -> Result<String> {
    Ok(Sha256::digest(sid()?.as_bytes())[..16]
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect())
}
pub(in crate::telemetry) fn directory() -> PathBuf {
    std::env::temp_dir().join(format!(
        "nowhere-telemetry-{}",
        user_key().unwrap_or_else(|_| "unavailable".to_owned())
    ))
}
fn protect(path: &Path) -> Result<()> {
    let security = Security::new()?;
    let name = wide(path.as_os_str());
    if unsafe {
        SetFileSecurityW(
            name.as_ptr(),
            OWNER_SECURITY_INFORMATION
                | DACL_SECURITY_INFORMATION
                | PROTECTED_DACL_SECURITY_INFORMATION,
            security.0,
        )
    } == 0
    {
        return Err(io::Error::last_os_error().into());
    }
    Ok(())
}
fn descriptor_text(sd: PSECURITY_DESCRIPTOR) -> Result<String> {
    let mut text = std::ptr::null_mut();
    if unsafe {
        ConvertSecurityDescriptorToStringSecurityDescriptorW(
            sd,
            1,
            OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
            &mut text,
            std::ptr::null_mut(),
        )
    } == 0
    {
        return Err(io::Error::last_os_error().into());
    }
    let mut n = 0;
    unsafe {
        while *text.add(n) != 0 {
            n += 1;
        }
    }
    let value = unsafe { String::from_utf16_lossy(std::slice::from_raw_parts(text, n)) };
    unsafe {
        LocalFree(text.cast());
    }
    Ok(value)
}
fn validate(path: &Path) -> Result<()> {
    let m = std::fs::symlink_metadata(path)?;
    if m.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0 {
        bail!("unsafe telemetry object");
    }
    let name = wide(path.as_os_str());
    let mut sd = std::ptr::null_mut();
    let status = unsafe {
        GetNamedSecurityInfoW(
            name.as_ptr(),
            SE_FILE_OBJECT,
            OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            &mut sd,
        )
    };
    if status != 0 {
        return Err(io::Error::from_raw_os_error(status as i32).into());
    }
    let result = (|| -> Result<()> {
        let expected = Security::new()?;
        if descriptor_text(sd)? != descriptor_text(expected.0)? {
            bail!("unsafe telemetry permissions");
        }
        Ok(())
    })();
    unsafe {
        LocalFree(sd);
    }
    result
}
pub(in crate::telemetry) fn validate_directory(path: &Path) -> Result<()> {
    validate(path)?;
    if !path.is_dir() {
        bail!("unsafe directory");
    }
    Ok(())
}
pub(in crate::telemetry) fn prepare_directory() -> Result<PathBuf> {
    let path = directory();
    if !path.is_absolute() {
        bail!("temporary directory must be absolute");
    }
    let security = Security::new()?;
    let attrs = security.attrs();
    let name = wide(path.as_os_str());
    if unsafe { CreateDirectoryW(name.as_ptr(), &attrs) } == 0 {
        let e = io::Error::last_os_error();
        if e.raw_os_error() != Some(ERROR_ALREADY_EXISTS as i32) {
            return Err(e.into());
        }
    }
    validate_directory(&path)?;
    Ok(path)
}
pub(in crate::telemetry) fn read_registry(path: &Path) -> Result<Vec<u8>> {
    validate_directory(&directory())?;
    validate(path)?;
    if !path.is_file() || std::fs::metadata(path)?.len() > 4096 {
        bail!("invalid registry");
    }
    use std::io::Read;
    let mut payload = Vec::new();
    std::fs::File::open(path)?
        .take(4097)
        .read_to_end(&mut payload)?;
    if payload.len() > 4096 {
        bail!("invalid registry");
    }
    Ok(payload)
}
pub(in crate::telemetry) fn publish(path: &Path, payload: &[u8]) -> Result<()> {
    use std::io::Write;
    validate_directory(&directory())?;
    let tmp = path.with_extension("pending");
    let mut created = false;
    let result = (|| -> Result<()> {
        let security = Security::new()?;
        let attrs = security.attrs();
        let name = wide(tmp.as_os_str());
        let handle = unsafe {
            CreateFileW(
                name.as_ptr(),
                GENERIC_WRITE,
                0,
                &attrs,
                CREATE_NEW,
                FILE_ATTRIBUTE_NORMAL,
                std::ptr::null_mut(),
            )
        };
        if handle == INVALID_HANDLE_VALUE {
            return Err(io::Error::last_os_error().into());
        }
        created = true;
        use std::os::windows::io::FromRawHandle;
        let mut f = unsafe { std::fs::File::from_raw_handle(handle) };
        f.write_all(payload)?;
        drop(f);
        protect(&tmp)?;
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
    format!(
        r"\\.\pipe\nowhere-telemetry-{}-{id}",
        user_key().unwrap_or_default()
    )
}
fn valid_endpoint(value: &str) -> bool {
    value
        .strip_prefix(&format!(
            r"\\.\pipe\nowhere-telemetry-{}-",
            user_key().unwrap_or_default()
        ))
        .is_some_and(|id| id.len() == 32 && id.bytes().all(|b| b.is_ascii_hexdigit()))
}
fn create(name: &str, first: bool) -> Result<NamedPipeServer> {
    let security = Security::new()?;
    let mut attrs = security.attrs();
    Ok(unsafe {
        ServerOptions::new()
            .first_pipe_instance(first)
            .reject_remote_clients(true)
            .max_instances(17)
            .in_buffer_size(4096)
            .out_buffer_size(8192)
            .create_with_security_attributes_raw(
                name,
                (&mut attrs as *mut SECURITY_ATTRIBUTES).cast(),
            )?
    })
}
pub(in crate::telemetry) struct Listener {
    pending: Option<NamedPipeServer>,
    name: String,
}
impl Listener {
    pub(in crate::telemetry) fn bind(name: &str) -> Result<Self> {
        if !valid_endpoint(name) {
            bail!("invalid endpoint");
        }
        Ok(Self {
            pending: Some(create(name, true)?),
            name: name.to_owned(),
        })
    }
    pub(in crate::telemetry) async fn accept(&mut self) -> Result<Stream> {
        if self.pending.is_none() {
            self.pending = Some(create(&self.name, false)?);
        }
        self.pending
            .as_ref()
            .expect("pending pipe")
            .connect()
            .await?;
        let stream = self.pending.take().expect("connected pipe");
        self.pending = Some(create(&self.name, false)?);
        Ok(Box::new(stream))
    }
}
pub(in crate::telemetry) async fn connect(name: &str, pid: u32) -> Result<Stream> {
    if !valid_endpoint(name) {
        bail!("invalid endpoint");
    }
    loop {
        match ClientOptions::new().open(name) {
            Ok(pipe) => {
                let mut server_pid = 0;
                if unsafe { GetNamedPipeServerProcessId(pipe.as_raw_handle(), &mut server_pid) }
                    == 0
                    || server_pid != pid
                {
                    bail!("server identity mismatch");
                }
                let process =
                    unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, server_pid) };
                if process.is_null() {
                    bail!("server identity unavailable");
                }
                let server_user = sid_from_process(process);
                unsafe {
                    CloseHandle(process);
                }
                if server_user? != sid()? {
                    bail!("server user mismatch");
                }
                return Ok(Box::new(pipe));
            }
            Err(e) if e.raw_os_error() == Some(ERROR_PIPE_BUSY as i32) => {
                tokio::time::sleep(std::time::Duration::from_millis(20)).await
            }
            Err(e) => return Err(e.into()),
        }
    }
}
pub(in crate::telemetry) fn namespace() -> String {
    user_key().unwrap_or_default()
}
pub(in crate::telemetry) fn cleanup(_endpoint: &str) {}
