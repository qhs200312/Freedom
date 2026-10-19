// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Runtime defaults and helpers for environment and URL-derived configuration.

use std::collections::HashMap;
use std::time::Duration;

use anyhow::{Context, Result, bail};
use percent_encoding::percent_decode_str;
use url::Url;

pub const DEFAULT_RATE_LIMIT: i32 = 0;

pub fn query_first(parsed_url: &Url, allowed: &[&str]) -> Result<HashMap<String, String>> {
    let mut values = HashMap::with_capacity(allowed.len());
    let Some(query) = parsed_url.query() else {
        return Ok(values);
    };
    for pair in query.split('&') {
        let (raw_key, raw_value) = pair.split_once('=').unwrap_or((pair, ""));
        let Ok(key) = decode_query_component(raw_key, "query key") else {
            continue;
        };
        if !allowed.contains(&key.as_str()) || values.contains_key(&key) {
            continue;
        }
        values.insert(key, decode_query_component(raw_value, "query value")?);
    }
    Ok(values)
}

pub(crate) fn first_raw_query_value<'a>(parsed_url: &'a Url, name: &str) -> Option<&'a str> {
    let query = parsed_url.query()?;
    query.split('&').find_map(|pair| {
        let (raw_key, raw_value) = pair.split_once('=').unwrap_or((pair, ""));
        decode_query_component(raw_key, "query key")
            .is_ok_and(|key| key == name)
            .then_some(raw_value)
    })
}

fn decode_query_component(raw: &str, name: &str) -> Result<String> {
    let bytes = raw.as_bytes();
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'%' {
            if index + 2 >= bytes.len()
                || !bytes[index + 1].is_ascii_hexdigit()
                || !bytes[index + 2].is_ascii_hexdigit()
            {
                bail!("invalid percent encoding in {name}");
            }
            index += 3;
        } else {
            index += 1;
        }
    }
    percent_decode_str(raw)
        .decode_utf8()
        .with_context(|| format!("invalid UTF-8 in {name}"))
        .map(|value| value.into_owned())
}

pub fn env_int(name: &str, default_value: i32) -> i32 {
    std::env::var(name)
        .ok()
        .and_then(|s| s.parse::<i32>().ok())
        .filter(|v| *v >= 0)
        .unwrap_or(default_value)
}

pub fn env_duration(name: &str, default_value: Duration) -> Duration {
    std::env::var(name)
        .ok()
        .and_then(|s| humantime::parse_duration(&s).ok())
        .unwrap_or(default_value)
}

pub fn rate_limit_bytes_per_second(mbps: i32) -> u64 {
    if mbps <= 0 { 0 } else { mbps as u64 * 125_000 }
}

pub fn tcp_data_buf_size() -> usize {
    env_int("NOW_TCP_DATA_BUF_SIZE", 32 * 1024) as usize
}

pub fn udp_data_buf_size() -> usize {
    env_int("NOW_UDP_DATA_BUF_SIZE", 64 * 1024) as usize
}

pub fn tcp_read_timeout() -> Duration {
    env_duration("NOW_TCP_READ_TIMEOUT", Duration::from_secs(30))
}

pub fn udp_idle_timeout() -> Duration {
    env_duration("NOW_UDP_IDLE_TIMEOUT", Duration::from_secs(2 * 60))
}

pub fn handshake_timeout() -> Duration {
    env_duration("NOW_HANDSHAKE_TIMEOUT", Duration::from_secs(5))
}

pub fn flow_setup_timeout() -> Duration {
    env_duration("NOW_FLOW_SETUP_TIMEOUT", Duration::from_secs(20))
}

pub fn service_cooldown() -> Duration {
    env_duration("NOW_SERVICE_COOLDOWN", Duration::from_secs(3))
}

pub fn shutdown_timeout() -> Duration {
    env_duration("NOW_SHUTDOWN_TIMEOUT", Duration::from_secs(5))
}

#[cfg(test)]
#[path = "../tests/common/config.rs"]
mod tests;
