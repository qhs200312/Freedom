// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Generated Shared Key length, lowercase encoding, and uniqueness tests.

use super::*;

#[test]
fn generated_keys_are_128_bit_lowercase_hex() {
    let first = generate_key().unwrap();
    let second = generate_key().unwrap();
    for key in [&first, &second] {
        assert_eq!(key.len(), 32);
        assert!(
            key.bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
        );
    }
    assert_ne!(first, second);
}
