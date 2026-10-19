// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Local-only transport and protected registry operations.

#[cfg(unix)]
#[path = "local/unix.rs"]
mod platform;
#[cfg(windows)]
#[path = "local/windows.rs"]
mod platform;
pub(super) use platform::*;
pub(super) type Reader = tokio::io::ReadHalf<Stream>;

#[cfg(unix)]
pub(super) const TRANSPORT: &str = "unix_socket";
#[cfg(windows)]
pub(super) const TRANSPORT: &str = "named_pipe";
