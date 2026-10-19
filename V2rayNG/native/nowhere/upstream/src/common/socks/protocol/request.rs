// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! SOCKS5 command requests and server replies.

use anyhow::{Context, Result, bail};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use super::{REPLY_SUCCEEDED, SOCKS_VERSION, SocksAddress, encode_address, read_address};

pub(crate) async fn send_command<S>(
    stream: &mut S,
    command: u8,
    target: &SocksAddress,
) -> Result<SocksAddress>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let mut request = Vec::with_capacity(25);
    request.extend_from_slice(&[SOCKS_VERSION, command, 0]);
    encode_address(&mut request, target)?;
    stream.write_all(&request).await?;

    let mut header = [0u8; 4];
    stream.read_exact(&mut header).await?;
    if header[0] != SOCKS_VERSION || header[2] != 0 {
        bail!("common::socks::send_command: invalid proxy response");
    }
    if header[1] != REPLY_SUCCEEDED {
        bail!(
            "common::socks::send_command: proxy command failed with reply {}",
            header[1]
        );
    }
    read_address(stream, header[3]).await
}

pub(crate) struct SocksRequest {
    pub(crate) command: u8,
    pub(crate) address: SocksAddress,
}

pub(crate) async fn read_request<S>(stream: &mut S) -> Result<SocksRequest>
where
    S: AsyncRead + Unpin,
{
    let mut header = [0u8; 4];
    stream
        .read_exact(&mut header)
        .await
        .context("common::socks::read_request: failed to read header")?;
    if header[0] != SOCKS_VERSION || header[2] != 0 {
        bail!("common::socks::read_request: invalid request header");
    }
    let address = read_address(stream, header[3]).await?;
    Ok(SocksRequest {
        command: header[1],
        address,
    })
}

pub(crate) async fn write_reply<S>(stream: &mut S, reply: u8, address: &SocksAddress) -> Result<()>
where
    S: AsyncWrite + Unpin,
{
    let mut message = Vec::with_capacity(25);
    message.extend_from_slice(&[SOCKS_VERSION, reply, 0]);
    encode_address(&mut message, address)?;
    stream
        .write_all(&message)
        .await
        .context("common::socks::write_reply: failed to write reply")
}
