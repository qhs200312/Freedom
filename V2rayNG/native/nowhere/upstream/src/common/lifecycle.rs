// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Platform shutdown signals and cleanup outcomes.

use anyhow::{Context, Result};
pub(crate) struct ShutdownSignals {
    #[cfg(unix)]
    interrupt: tokio::signal::unix::Signal,
    #[cfg(unix)]
    terminate: tokio::signal::unix::Signal,
}

impl ShutdownSignals {
    pub(crate) fn new() -> Result<Self> {
        #[cfg(unix)]
        {
            use tokio::signal::unix::{SignalKind, signal};

            Ok(Self {
                interrupt: signal(SignalKind::interrupt())
                    .context("common::lifecycle: failed to install SIGINT handler")?,
                terminate: signal(SignalKind::terminate())
                    .context("common::lifecycle: failed to install SIGTERM handler")?,
            })
        }
        #[cfg(not(unix))]
        Ok(Self {})
    }

    pub(crate) async fn recv(&mut self) -> Result<()> {
        #[cfg(unix)]
        {
            tokio::select! {
                value = self.interrupt.recv() => {
                    value.ok_or_else(|| anyhow::anyhow!("common::lifecycle: SIGINT stream closed"))?;
                    Ok(())
                }
                value = self.terminate.recv() => {
                    value.ok_or_else(|| anyhow::anyhow!("common::lifecycle: SIGTERM stream closed"))?;
                    Ok(())
                }
            }
        }
        #[cfg(not(unix))]
        {
            tokio::signal::ctrl_c()
                .await
                .context("common::lifecycle: failed to receive Ctrl+C")?;
            Ok(())
        }
    }
}
