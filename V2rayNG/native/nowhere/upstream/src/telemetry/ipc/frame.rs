// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Bounded telemetry frame I/O with cancellation-safe reads.

use std::time::Duration;

use anyhow::{Context, Result, bail};
use serde::Serialize;
use serde::de::DeserializeOwned;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use super::WRITE_TIMEOUT;
use crate::telemetry::local::Reader;
use crate::telemetry::{MAX_FRAME_SIZE, ServerMessage};

pub(crate) struct TelemetryReader {
    pub(super) inner: FrameReader<Reader>,
}

impl TelemetryReader {
    pub(crate) async fn next_message(&mut self) -> Result<ServerMessage> {
        self.inner.next().await
    }
}

pub(super) async fn write_frame<W, T>(writer: &mut W, value: &T) -> Result<()>
where
    W: AsyncWrite + Unpin,
    T: Serialize,
{
    let payload = serde_json::to_vec(value).context("telemetry: failed to encode JSON frame")?;
    if payload.len() > MAX_FRAME_SIZE {
        bail!("telemetry: encoded frame exceeds {MAX_FRAME_SIZE} bytes");
    }
    write_payload_with_timeout(writer, &payload, WRITE_TIMEOUT).await
}

pub(super) async fn write_payload_with_timeout<W>(
    writer: &mut W,
    payload: &[u8],
    timeout: Duration,
) -> Result<()>
where
    W: AsyncWrite + Unpin,
{
    tokio::time::timeout(timeout, write_payload(writer, payload))
        .await
        .context("telemetry: timed out writing frame")?
}

async fn write_payload<W>(writer: &mut W, payload: &[u8]) -> Result<()>
where
    W: AsyncWrite + Unpin,
{
    writer
        .write_u32(payload.len() as u32)
        .await
        .context("telemetry: failed to write frame length")?;
    writer
        .write_all(payload)
        .await
        .context("telemetry: failed to write frame payload")?;
    writer
        .flush()
        .await
        .context("telemetry: failed to flush frame")?;
    Ok(())
}

pub(super) struct FrameReader<R> {
    inner: R,
    length_bytes: [u8; 4],
    length_read: usize,
    payload: Vec<u8>,
    payload_read: usize,
    pub(super) started_at: Option<tokio::time::Instant>,
}

impl<R> FrameReader<R>
where
    R: AsyncRead + Unpin,
{
    pub(super) fn new(inner: R) -> Self {
        Self {
            inner,
            length_bytes: [0; 4],
            length_read: 0,
            payload: Vec::new(),
            payload_read: 0,
            started_at: None,
        }
    }

    fn check_deadline(&self) -> Result<()> {
        if self
            .started_at
            .is_some_and(|start| start.elapsed() >= Duration::from_secs(5))
        {
            bail!("partial frame timeout");
        }
        Ok(())
    }

    pub(super) async fn next<T: DeserializeOwned>(&mut self) -> Result<T> {
        self.read_frame().await
    }
    async fn read_frame<T>(&mut self) -> Result<T>
    where
        T: DeserializeOwned,
    {
        while self.length_read < self.length_bytes.len() {
            self.check_deadline()?;
            let read = self.inner.read(&mut self.length_bytes[self.length_read..]);
            let count = if let Some(start) = self.started_at {
                tokio::time::timeout_at(start + Duration::from_secs(5), read)
                    .await
                    .context("partial frame timeout")?
            } else {
                read.await
            }
            .context("telemetry: failed to read frame length")?;
            if count == 0 {
                bail!("telemetry: connection closed while reading frame length");
            }
            self.started_at
                .get_or_insert_with(tokio::time::Instant::now);
            self.length_read += count;
        }

        if self.payload.is_empty() {
            let length = u32::from_be_bytes(self.length_bytes) as usize;
            if length == 0 || length > MAX_FRAME_SIZE {
                bail!("telemetry: invalid frame length {length}");
            }
            self.payload.resize(length, 0);
        }

        while self.payload_read < self.payload.len() {
            self.check_deadline()?;
            let count = tokio::time::timeout_at(
                self.started_at.expect("frame started") + Duration::from_secs(5),
                self.inner.read(&mut self.payload[self.payload_read..]),
            )
            .await
            .context("partial frame timeout")?
            .context("telemetry: failed to read frame payload")?;
            if count == 0 {
                bail!("telemetry: connection closed while reading frame payload");
            }
            self.payload_read += count;
        }

        let payload = std::mem::take(&mut self.payload);
        self.length_bytes = [0; 4];
        self.length_read = 0;
        self.payload_read = 0;
        self.started_at = None;
        serde_json::from_slice(&payload).context("telemetry: failed to decode JSON frame")
    }
}

#[cfg(test)]
#[path = "../../tests/telemetry/ipc/frame.rs"]
mod tests;
