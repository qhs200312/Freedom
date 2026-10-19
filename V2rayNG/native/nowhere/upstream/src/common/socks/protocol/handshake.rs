// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! SOCKS5 method negotiation and username/password authentication.

use anyhow::{Context, Result, bail};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use super::{AUTH_NONE, AUTH_PASSWORD, AUTH_UNACCEPTABLE, AUTH_VERSION, SOCKS_VERSION};

pub(crate) async fn negotiate<S>(stream: &mut S, credentials: Option<(&str, &str)>) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let method = if credentials.is_some() {
        AUTH_PASSWORD
    } else {
        AUTH_NONE
    };
    stream.write_all(&[SOCKS_VERSION, 1, method]).await?;
    let mut response = [0u8; 2];
    stream.read_exact(&mut response).await?;
    if response[0] != SOCKS_VERSION {
        bail!("common::socks::negotiate: invalid SOCKS version");
    }
    if response[1] == AUTH_UNACCEPTABLE {
        bail!("common::socks::negotiate: proxy rejected authentication method");
    }
    if response[1] != method {
        bail!("common::socks::negotiate: proxy selected an unadvertised authentication method");
    }

    if let Some((username, password)) = credentials {
        let username = username.as_bytes();
        let password = password.as_bytes();
        let mut request = Vec::with_capacity(3 + username.len() + password.len());
        request.extend_from_slice(&[AUTH_VERSION, username.len() as u8]);
        request.extend_from_slice(username);
        request.push(password.len() as u8);
        request.extend_from_slice(password);
        stream.write_all(&request).await?;
        stream.read_exact(&mut response).await?;
        if response[0] != AUTH_VERSION || response[1] != 0 {
            bail!("common::socks::negotiate: username/password authentication failed");
        }
    }
    Ok(())
}

pub(crate) async fn authenticate<S>(stream: &mut S, credentials: Option<(&str, &str)>) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let mut header = [0u8; 2];
    stream
        .read_exact(&mut header)
        .await
        .context("common::socks::authenticate: failed to read method header")?;
    if header[0] != SOCKS_VERSION || header[1] == 0 {
        bail!("common::socks::authenticate: invalid method negotiation");
    }
    let mut methods = [0u8; u8::MAX as usize];
    let methods = &mut methods[..header[1] as usize];
    stream
        .read_exact(methods)
        .await
        .context("common::socks::authenticate: failed to read methods")?;
    let selected = if credentials.is_some() {
        AUTH_PASSWORD
    } else {
        AUTH_NONE
    };
    if !methods.contains(&selected) {
        stream
            .write_all(&[SOCKS_VERSION, AUTH_UNACCEPTABLE])
            .await?;
        bail!("common::socks::authenticate: required method was not offered");
    }
    stream.write_all(&[SOCKS_VERSION, selected]).await?;

    if let Some(credentials) = credentials {
        authenticate_password(stream, credentials).await?;
    }
    Ok(())
}

async fn authenticate_password<S>(stream: &mut S, expected: (&str, &str)) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let mut header = [0u8; 2];
    stream.read_exact(&mut header).await?;
    if header[0] != AUTH_VERSION || header[1] == 0 {
        let _ = stream.write_all(&[AUTH_VERSION, 1]).await;
        bail!("common::socks::authenticate_password: invalid auth header");
    }
    let mut username = [0u8; u8::MAX as usize];
    let username = &mut username[..header[1] as usize];
    stream.read_exact(username).await?;
    let password_len = stream.read_u8().await? as usize;
    if password_len == 0 {
        let _ = stream.write_all(&[AUTH_VERSION, 1]).await;
        bail!("common::socks::authenticate_password: empty password");
    }
    let mut password = [0u8; u8::MAX as usize];
    let password = &mut password[..password_len];
    stream.read_exact(password).await?;

    let accepted = constant_time_equal(username, expected.0.as_bytes())
        & constant_time_equal(password, expected.1.as_bytes());
    stream
        .write_all(&[AUTH_VERSION, u8::from(!accepted)])
        .await?;
    if !accepted {
        bail!("common::socks::authenticate_password: credentials rejected");
    }
    Ok(())
}

fn constant_time_equal(left: &[u8], right: &[u8]) -> bool {
    let max_len = left.len().max(right.len());
    let mut diff = left.len() ^ right.len();
    for index in 0..max_len {
        diff |= usize::from(
            left.get(index).copied().unwrap_or(0) ^ right.get(index).copied().unwrap_or(0),
        );
    }
    diff == 0
}
