// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Vector carrier selection, defaults, query validation, and effective configuration tests.

use super::*;
fn parse(query: &str) -> Result<VectorConfig> {
    VectorConfig::from_url(
        &Url::parse(&format!(
            "vector://0123456789abcdef0123456789abcdef@localhost:2000?in=127.0.0.1:1080&{query}"
        ))
        .unwrap(),
    )
}
#[test]
fn defaults_and_unknown_options_are_ignored() {
    let config = parse("unused=garbage&net=%ZZ&unknown=%ZZ").unwrap();
    assert_eq!(config.net, CarrierMode::Tcp);
    assert_eq!(
        config.effective_url(),
        "vector://localhost:2000?in=127.0.0.1:1080&sni=localhost&pin=none&morph=1&rate=0&etar=0"
    );
    assert!(!config.effective_url().contains("secret"));
    assert!(!config.effective_url().contains("unused="));
    assert!(!config.effective_url().contains("unknown="));
    assert_eq!(parse("net=udp&net=bad").unwrap().net, CarrierMode::Tcp);
    assert!(parse("net=bad&net=udp").is_ok());
}
#[test]
fn recognized_invalid_values_and_missing_ingress_fail() {
    for query in ["morph=2", "rate=-1", "etar=no", "pin=bad"] {
        assert!(parse(query).is_err(), "{query}");
    }
    let url = Url::parse("vector://0123456789abcdef0123456789abcdef@localhost:2000").unwrap();
    assert!(VectorConfig::from_url(&url).is_err());
    assert!(PortalClientConfig::from_probe_url(&url).is_ok());
}

#[test]
fn endpoint_selects_one_carrier_and_address_family() {
    const KEY: &str = "0123456789abcdef0123456789abcdef";
    for (endpoint, mode, family) in [
        (
            "localhost:2000",
            CarrierMode::Tcp,
            crate::common::AddressFamily::Any,
        ),
        (
            "localhost/tcp:2000",
            CarrierMode::Tcp,
            crate::common::AddressFamily::Any,
        ),
        (
            "localhost/tcp4:2000",
            CarrierMode::Tcp,
            crate::common::AddressFamily::V4,
        ),
        (
            "localhost/tcp6:2000",
            CarrierMode::Tcp,
            crate::common::AddressFamily::V6,
        ),
        (
            "localhost/udp:2000",
            CarrierMode::Udp,
            crate::common::AddressFamily::Any,
        ),
        (
            "localhost/udp4:2000",
            CarrierMode::Udp,
            crate::common::AddressFamily::V4,
        ),
        (
            "localhost/udp6:2000",
            CarrierMode::Udp,
            crate::common::AddressFamily::V6,
        ),
    ] {
        let url = Url::parse(&format!(
            "vector://{KEY}@{endpoint}?in=127.0.0.1:1080&net=invalid"
        ))
        .unwrap();
        let config = VectorConfig::from_url(&url).unwrap();
        assert_eq!(config.net, mode, "{endpoint}");
        let carrier = if mode == CarrierMode::Tcp {
            config.tcp_endpoint()
        } else {
            config.udp_endpoint()
        }
        .unwrap();
        assert_eq!(carrier.family, family);
        assert_eq!(config.endpoint(), endpoint);
        assert!(!config.effective_url().contains("net="));
    }
    for endpoint in ["localhost/tcp:2000/udp:2001", "localhost/udp:2001/tcp:2000"] {
        let url = Url::parse(&format!("vector://{KEY}@{endpoint}")).unwrap();
        let error = PortalClientConfig::from_probe_url(&url).unwrap_err();
        assert!(error.to_string().contains("exactly one carrier"));
    }
}
#[test]
fn ingress_credentials_split_before_single_decode_and_are_redacted() {
    let url = Url::parse(
        "vector://0123456789abcdef0123456789abcdef@localhost:2000?in=a%3Ab:p%2540ss@127.0.0.1:1080",
    )
    .unwrap();
    let config = VectorConfig::from_url(&url).unwrap();
    assert_eq!(
        config.socks.credentials.as_ref().unwrap().as_pair(),
        ("a:b", "p%40ss")
    );
    assert!(!config.effective_url().contains("p%40ss"));
}

#[test]
fn morph_defaults_on_and_honors_explicit_values() {
    for scheme in ["vector", "egress", "nowhere"] {
        for (query, enabled) in [("", true), ("morph=0", false), ("morph=1", true)] {
            let url = Url::parse(&format!(
                "{scheme}://0123456789abcdef0123456789abcdef@localhost:2000?{query}"
            ))
            .unwrap();
            let query = query_first(&url, &["morph"]).unwrap();
            let config = PortalClientConfig::parse(&url, &query).unwrap();
            assert_eq!(config.morph, enabled, "{url}");
            assert_eq!(config.morph_keys.is_some(), enabled, "{url}");
        }
    }
}
