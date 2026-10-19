// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Owned relay payloads and buffered asynchronous I/O with flush-aware reads.

use super::Buffers;
use bytes::Bytes;
use std::future::{Future, poll_fn};
use std::io;
use std::pin::Pin;
use std::task::Poll;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
pub(crate) trait AsyncReadAny: AsyncRead + Send + Unpin {}
impl<T: AsyncRead + Send + Unpin> AsyncReadAny for T {}
pub(crate) trait AsyncWriteAny: AsyncWrite + Send + Unpin {}
impl<T: AsyncWrite + Send + Unpin> AsyncWriteAny for T {}
pub(crate) async fn read_with_flush<T>(
    read: impl Future<Output = io::Result<T>>,
    writer: &mut (impl AsyncWrite + Unpin),
) -> io::Result<T> {
    tokio::pin!(read);
    match poll_fn(|cx| Poll::Ready(read.as_mut().poll(cx))).await {
        Poll::Ready(result) => result,
        Poll::Pending => {
            writer.flush().await?;
            read.await
        }
    }
}

pub(crate) async fn read_owned(
    reader: &mut Pin<Box<dyn AsyncReadAny>>,
    buffers: &Buffers,
) -> io::Result<Option<Bytes>> {
    read_owned_from(reader, buffers).await
}
pub(crate) async fn read_owned_from<R: AsyncRead + Unpin>(
    reader: &mut R,
    buffers: &Buffers,
) -> io::Result<Option<Bytes>> {
    let mut payload = buffers.get_tcp_buffer();
    let count = reader.read(&mut payload).await?;
    Ok((count != 0).then(|| Bytes::from_owner(payload).slice(..count)))
}
pub(crate) async fn write_owned(
    writer: &mut Pin<Box<dyn AsyncWriteAny>>,
    chunk: Bytes,
) -> io::Result<()> {
    writer.write_all(&chunk).await
}
pub(crate) async fn write_owned_to<W: AsyncWrite + Unpin>(
    writer: &mut W,
    chunk: Bytes,
) -> io::Result<()> {
    writer.write_all(&chunk).await
}

#[cfg(test)]
#[path = "../tests/transport/owned_io.rs"]
mod tests;
