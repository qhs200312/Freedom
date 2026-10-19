// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Portable process identity for protected local IPC.

#[cfg(all(not(target_os = "linux"), not(windows)))]
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::Result;

#[cfg(all(not(target_os = "linux"), not(windows)))]
fn now_unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .min(u64::MAX as u128) as u64
}

pub(super) fn process_uid() -> u32 {
    #[cfg(unix)]
    {
        unsafe { libc::geteuid() }
    }
    #[cfg(not(unix))]
    {
        0
    }
}

pub(super) fn process_incarnation(pid: u32) -> Result<u64> {
    #[cfg(all(not(target_os = "linux"), not(windows)))]
    {
        let _ = pid;
        Ok(now_unix_ms())
    }
    #[cfg(windows)]
    return read_process_incarnation(pid)
        .ok_or_else(|| anyhow::anyhow!("telemetry process identity unavailable"));
    #[cfg(target_os = "linux")]
    read_process_incarnation(pid)
        .ok_or_else(|| anyhow::anyhow!("telemetry: failed to read /proc/{pid}/stat start time"))
}

pub(crate) fn read_process_incarnation(pid: u32) -> Option<u64> {
    #[cfg(windows)]
    return windows_creation(pid);
    #[cfg(all(not(target_os = "linux"), not(windows)))]
    {
        let _ = pid;
        None
    }
    #[cfg(target_os = "linux")]
    {
        let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
        let close = stat.rfind(')')?;
        stat.get(close + 2..)?
            .split_ascii_whitespace()
            .nth(19)?
            .parse()
            .ok()
    }
}

pub(crate) fn process_is_alive(pid: u32) -> bool {
    #[cfg(unix)]
    {
        let result = unsafe { libc::kill(pid as libc::pid_t, 0) };
        result == 0 || std::io::Error::last_os_error().raw_os_error() != Some(libc::ESRCH)
    }
    #[cfg(windows)]
    {
        use windows_sys::Win32::{Foundation::*, System::Threading::*};
        let process = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
        if process.is_null() {
            return std::io::Error::last_os_error().raw_os_error()
                != Some(ERROR_INVALID_PARAMETER as i32);
        }
        let mut code = 0;
        let ok = unsafe { GetExitCodeProcess(process, &mut code) };
        unsafe {
            CloseHandle(process);
        }
        ok == 0 || code == 259
    }
}

#[cfg(windows)]
fn windows_creation(pid: u32) -> Option<u64> {
    use windows_sys::Win32::{Foundation::*, System::Threading::*};
    let process = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
    if process.is_null() {
        return None;
    }
    let mut created = FILETIME {
        dwLowDateTime: 0,
        dwHighDateTime: 0,
    };
    let mut exit = created;
    let mut kernel = created;
    let mut user = created;
    let ok = unsafe { GetProcessTimes(process, &mut created, &mut exit, &mut kernel, &mut user) };
    unsafe {
        CloseHandle(process);
    }
    if ok == 0 {
        return None;
    }
    let ticks = (u64::from(created.dwHighDateTime) << 32) | u64::from(created.dwLowDateTime);
    Some(ticks.checked_sub(116_444_736_000_000_000)? / 10_000)
}
