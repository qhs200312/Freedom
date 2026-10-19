// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Egress configuration defaults, target dial policy, and credential redaction tests.

use super::*;
use crate::common::DialPolicy;

#[test]
fn endpoint_accepts_only_one_quic_carrier_or_a_compact_port() {
    const KEY: &str = "0123456789abcdef0123456789abcdef";
    for endpoint in [
        "localhost:2000",
        "localhost/udp:2000",
        "localhost/udp4:2000",
        "localhost/udp6:2000",
    ] {
        let egress = Egress::new(
            Url::parse(&format!("egress://{KEY}@{endpoint}")).unwrap(),
            Logger,
        )
        .unwrap();
        assert_eq!(egress.config.net, crate::vector::config::CarrierMode::Udp);
        assert_eq!(egress.config.endpoint(), endpoint);
    }
    for endpoint in [
        "localhost/tcp:2000",
        "localhost/tcp4:2000",
        "localhost/tcp6:2000",
        "localhost/tcp:2000/udp:2001",
        "localhost/udp:2001/tcp:2000",
    ] {
        let error = Egress::new(
            Url::parse(&format!("egress://{KEY}@{endpoint}")).unwrap(),
            Logger,
        )
        .err()
        .unwrap();
        assert!(
            error.to_string().contains("only a QUIC carrier"),
            "{endpoint}: {error}"
        );
    }
}

#[test]
fn fixed_quic_and_effective_defaults_ignore_unknown_options_and_hide_credentials() {
    let egress = Egress::new(
        Url::parse(
            "egress://0123456789abcdef0123456789abcdef@localhost:2000?net=tcp&unknown=bad&unused=secret&dial4=192.0.2.254",
        )
        .unwrap(),
        Logger,
    )
    .unwrap();
    assert_eq!(egress.config.net, crate::vector::config::CarrierMode::Udp);
    assert_eq!(egress.config.dial_policy, DialPolicy::default());
    assert_eq!(
        egress.effective,
        "egress://localhost:2000?sni=localhost&pin=none&dial4=192.0.2.254&dial6=auto&morph=1&rate=0&etar=0"
    );
    assert!(!egress.effective.contains("secret"));
    assert!(!egress.effective.contains("unknown="));
    assert!(!egress.effective.contains("unused="));
}
