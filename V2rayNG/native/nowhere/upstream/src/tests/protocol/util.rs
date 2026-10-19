// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Tests for domain and port validation.

use super::*;

#[test]
fn domain_validation_accepts_ascii_idna_wire_names() {
    for domain in [b"example.com".as_slice(), b"xn--bcher-kva.example", b"a"] {
        validate_domain_bytes(domain, "test").unwrap();
    }
}

#[test]
fn domain_validation_rejects_unsafe_and_oversized_names() {
    for domain in [
        b"".as_slice(),
        b"bad host",
        b"bad:host",
        b"bad[host]",
        b"bad/host",
        b"bad@host",
        b"bad_host",
        b"bad..host",
        b"-bad.host",
        b"bad-.host",
        b"bad.host.",
        b"line\nbreak",
        &[0xff],
    ] {
        assert!(validate_domain_bytes(domain, "test").is_err());
    }
    assert!(validate_domain_bytes(&vec![b'a'; DOMAIN_LEN_MAX + 1], "test").is_err());
}

#[test]
fn port_validation_rejects_only_zero() {
    assert!(validate_port(0, "test").is_err());
    validate_port(1, "test").unwrap();
    validate_port(u16::MAX, "test").unwrap();
}
