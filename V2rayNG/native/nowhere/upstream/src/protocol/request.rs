// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Compact SOCKS5-compatible target-address codec.

use std::fmt;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::str::FromStr;

use anyhow::{Context, Result, bail};
use tokio::io::{AsyncRead, AsyncReadExt};

use super::util::{DOMAIN_LEN_MAX, validate_domain_bytes, validate_port};

pub const TARGET_ATYP_IPV4: u8 = 0x01;
pub const TARGET_ATYP_DOMAIN: u8 = 0x03;
pub const TARGET_ATYP_IPV6: u8 = 0x04;

pub const TARGET_IPV4_LEN: usize = 1 + 4 + 2;
pub const TARGET_IPV6_LEN: usize = 1 + 16 + 2;
pub const TARGET_MAX_ENCODED_LEN: usize = 1 + 1 + DOMAIN_LEN_MAX + 2;

#[derive(Clone, Debug, Eq, Hash, PartialEq)]
pub enum Target {
    Ip(SocketAddr),
    Domain { host: String, port: u16 },
}

impl Target {
    pub fn ip(address: SocketAddr) -> Result<Self> {
        validate_port(address.port(), "protocol::request::Target::ip")?;
        Ok(Self::Ip(address))
    }

    pub fn domain(host: impl Into<String>, port: u16) -> Result<Self> {
        let host = host.into();
        validate_domain_bytes(host.as_bytes(), "protocol::request::Target::domain")?;
        validate_port(port, "protocol::request::Target::domain")?;
        Ok(Self::Domain { host, port })
    }

    pub fn encoded_len(&self) -> Result<usize> {
        match self {
            Self::Ip(address) => {
                validate_port(address.port(), "protocol::request::Target::encoded_len")?;
                Ok(match address.ip() {
                    IpAddr::V4(_) => TARGET_IPV4_LEN,
                    IpAddr::V6(_) => TARGET_IPV6_LEN,
                })
            }
            Self::Domain { host, port } => {
                validate_domain_bytes(host.as_bytes(), "protocol::request::Target::encoded_len")?;
                validate_port(*port, "protocol::request::Target::encoded_len")?;
                Ok(1 + 1 + host.len() + 2)
            }
        }
    }
}

impl fmt::Display for Target {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Ip(address) => address.fmt(formatter),
            Self::Domain { host, port } => write!(formatter, "{host}:{port}"),
        }
    }
}

impl FromStr for Target {
    type Err = anyhow::Error;

    fn from_str(value: &str) -> Result<Self> {
        if let Ok(address) = SocketAddr::from_str(value) {
            return Self::ip(address);
        }
        let Some((host, raw_port)) = value.rsplit_once(':') else {
            bail!("protocol::request::Target::from_str: missing port");
        };
        if host.contains(':') {
            bail!("protocol::request::Target::from_str: IPv6 address must be bracketed");
        }
        let port = raw_port
            .parse::<u16>()
            .context("protocol::request::Target::from_str: invalid port")?;
        Self::domain(host, port)
    }
}

impl TryFrom<&str> for Target {
    type Error = anyhow::Error;

    fn try_from(value: &str) -> Result<Self> {
        value.parse()
    }
}

impl TryFrom<String> for Target {
    type Error = anyhow::Error;

    fn try_from(value: String) -> Result<Self> {
        value.parse()
    }
}

pub fn encode_target_into(target: &Target, output: &mut [u8]) -> Result<usize> {
    let encoded_len = target.encoded_len()?;
    if output.len() < encoded_len {
        bail!(
            "protocol::request::encode_target_into: output too short: need {encoded_len}, got {}",
            output.len()
        );
    }

    match target {
        Target::Ip(SocketAddr::V4(address)) => {
            output[0] = TARGET_ATYP_IPV4;
            output[1..5].copy_from_slice(&address.ip().octets());
            output[5..7].copy_from_slice(&address.port().to_be_bytes());
        }
        Target::Ip(SocketAddr::V6(address)) => {
            output[0] = TARGET_ATYP_IPV6;
            output[1..17].copy_from_slice(&address.ip().octets());
            output[17..19].copy_from_slice(&address.port().to_be_bytes());
        }
        Target::Domain { host, port } => {
            output[0] = TARGET_ATYP_DOMAIN;
            output[1] = host.len() as u8;
            output[2..2 + host.len()].copy_from_slice(host.as_bytes());
            output[2 + host.len()..encoded_len].copy_from_slice(&port.to_be_bytes());
        }
    }
    Ok(encoded_len)
}

pub fn decode_target(input: &[u8]) -> Result<(Target, usize)> {
    let Some(&address_type) = input.first() else {
        bail!("protocol::request::decode_target: missing address type");
    };
    match address_type {
        TARGET_ATYP_IPV4 => {
            if input.len() < TARGET_IPV4_LEN {
                bail!("protocol::request::decode_target: truncated IPv4 target");
            }
            let address = Ipv4Addr::new(input[1], input[2], input[3], input[4]);
            let port = u16::from_be_bytes([input[5], input[6]]);
            Ok((
                Target::ip(SocketAddr::new(IpAddr::V4(address), port))?,
                TARGET_IPV4_LEN,
            ))
        }
        TARGET_ATYP_IPV6 => {
            if input.len() < TARGET_IPV6_LEN {
                bail!("protocol::request::decode_target: truncated IPv6 target");
            }
            let octets: [u8; 16] = input[1..17].try_into().expect("fixed IPv6 address");
            let port = u16::from_be_bytes([input[17], input[18]]);
            Ok((
                Target::ip(SocketAddr::new(IpAddr::V6(Ipv6Addr::from(octets)), port))?,
                TARGET_IPV6_LEN,
            ))
        }
        TARGET_ATYP_DOMAIN => {
            let Some(&domain_len) = input.get(1) else {
                bail!("protocol::request::decode_target: missing domain length");
            };
            let domain_len = domain_len as usize;
            validate_domain_bytes(
                input.get(2..2 + domain_len).ok_or_else(|| {
                    anyhow::anyhow!("protocol::request::decode_target: truncated domain")
                })?,
                "protocol::request::decode_target",
            )?;
            let encoded_len = 1 + 1 + domain_len + 2;
            if input.len() < encoded_len {
                bail!("protocol::request::decode_target: truncated domain port");
            }
            let host = String::from_utf8(input[2..2 + domain_len].to_vec())
                .expect("ASCII domain is UTF-8");
            let port = u16::from_be_bytes([input[encoded_len - 2], input[encoded_len - 1]]);
            Ok((Target::domain(host, port)?, encoded_len))
        }
        value => bail!("protocol::request::decode_target: unknown address type: {value}"),
    }
}

pub async fn read_request<R: AsyncRead + Unpin>(reader: &mut R) -> Result<Target> {
    let mut address_type = [0; 1];
    reader
        .read_exact(&mut address_type)
        .await
        .context("protocol::request::read_request: failed to read address type")?;

    match address_type[0] {
        TARGET_ATYP_IPV4 => {
            let mut tail = [0; TARGET_IPV4_LEN - 1];
            reader
                .read_exact(&mut tail)
                .await
                .context("protocol::request::read_request: failed to read IPv4 target")?;
            let mut encoded = [0; TARGET_IPV4_LEN];
            encoded[0] = TARGET_ATYP_IPV4;
            encoded[1..].copy_from_slice(&tail);
            Ok(decode_target(&encoded)?.0)
        }
        TARGET_ATYP_IPV6 => {
            let mut tail = [0; TARGET_IPV6_LEN - 1];
            reader
                .read_exact(&mut tail)
                .await
                .context("protocol::request::read_request: failed to read IPv6 target")?;
            let mut encoded = [0; TARGET_IPV6_LEN];
            encoded[0] = TARGET_ATYP_IPV6;
            encoded[1..].copy_from_slice(&tail);
            Ok(decode_target(&encoded)?.0)
        }
        TARGET_ATYP_DOMAIN => {
            let mut length = [0; 1];
            reader
                .read_exact(&mut length)
                .await
                .context("protocol::request::read_request: failed to read domain length")?;
            let domain_len = length[0] as usize;
            if domain_len == 0 || domain_len > DOMAIN_LEN_MAX {
                bail!("protocol::request::read_request: invalid domain length: {domain_len}");
            }
            let mut encoded = [0; TARGET_MAX_ENCODED_LEN];
            encoded[0] = TARGET_ATYP_DOMAIN;
            encoded[1] = length[0];
            let encoded_len = 1 + 1 + domain_len + 2;
            reader
                .read_exact(&mut encoded[2..encoded_len])
                .await
                .context("protocol::request::read_request: failed to read domain target")?;
            Ok(decode_target(&encoded[..encoded_len])?.0)
        }
        value => bail!("protocol::request::read_request: unknown address type: {value}"),
    }
}

#[cfg(test)]
#[path = "../tests/protocol/request.rs"]
mod tests;
