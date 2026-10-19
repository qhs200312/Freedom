// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! SOCKS5 outlet parsing, duplicate parameters, credentials, and redacted configuration tests.

use super::super::test_support::parse;

#[test]
fn parses_disabled_and_endpoint_forms() {
    for raw in [
        "portal://secret@127.0.0.1:2000",
        "portal://secret@127.0.0.1:2000?out=direct",
        "portal://secret@127.0.0.1:2000?out=egress",
    ] {
        assert!(parse(raw).unwrap().is_none());
    }

    let domain = parse("portal://secret@127.0.0.1:2000?out=proxy.test:1080")
        .unwrap()
        .unwrap();
    assert_eq!(domain.endpoint(), "proxy.test:1080");

    let ipv6 = parse("portal://secret@127.0.0.1:2000?out=[::1]:1080")
        .unwrap()
        .unwrap();
    assert_eq!(ipv6.endpoint(), "[::1]:1080");
}

#[test]
fn parses_percent_encoded_credentials_without_exposing_them() {
    let config =
        parse("portal://secret@127.0.0.1:2000?out=user%3Aname:p%40ss%26word@proxy.test:1080")
            .unwrap()
            .unwrap();
    let credentials = config.credentials().unwrap();
    assert_eq!(credentials.0, "user:name");
    assert_eq!(credentials.1, "p@ss&word");
    assert_eq!(config.endpoint(), "proxy.test:1080");
    let debug = format!("{config:?}");
    assert!(!debug.contains("user"));
    assert!(!debug.contains("pass"));
}

#[test]
fn rejects_ambiguous_or_invalid_configuration() {
    for raw in [
        "portal://secret@127.0.0.1:2000?out=user@proxy.test:1080",
        "portal://secret@127.0.0.1:2000?out=:pass@proxy.test:1080",
        "portal://secret@127.0.0.1:2000?out=user:@proxy.test:1080",
        "portal://secret@127.0.0.1:2000?out=user:p:ass@proxy.test:1080",
        "portal://secret@127.0.0.1:2000?out=user:p+ass@proxy.test:1080",
        "portal://secret@127.0.0.1:2000?out=proxy.test:0",
        "portal://secret@127.0.0.1:2000?out=::1:1080",
        "portal://secret@127.0.0.1:2000?out=user:%GG@proxy.test:1080",
    ] {
        assert!(parse(raw).is_err(), "accepted {raw}");
    }
}

#[test]
fn duplicate_out_uses_the_first_value() {
    let config = parse("portal://secret@127.0.0.1:2000?out=proxy.test:1080&out=other.test:1080")
        .unwrap()
        .unwrap();

    assert_eq!(config.endpoint(), "proxy.test:1080");
}

#[test]
fn malformed_unknown_query_key_is_ignored() {
    let config = parse("portal://secret@127.0.0.1:2000?%FF=x&out=proxy.test:1080")
        .unwrap()
        .unwrap();

    assert_eq!(config.endpoint(), "proxy.test:1080");
}

#[test]
fn credential_lengths_follow_rfc_1929() {
    let username = "u".repeat(255);
    let password = "p".repeat(255);
    let accepted =
        format!("portal://secret@127.0.0.1:2000?out={username}:{password}@proxy.test:1080");
    assert!(parse(&accepted).is_ok());

    let username = "u".repeat(256);
    let rejected = format!("portal://secret@127.0.0.1:2000?out={username}:p@proxy.test:1080");
    assert!(parse(&rejected).is_err());
}
