// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! SOCKS5 outlet parsing fixture shared by configuration and dialing tests.

use super::SocksConfig;
use anyhow::Result;
use url::Url;

pub(super) fn parse(raw: &str) -> Result<Option<SocksConfig>> {
    SocksConfig::from_url(&Url::parse(raw).unwrap())
}
