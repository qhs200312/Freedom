// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Portal startup URL field order, outlet selection, defaults, and credential redaction tests.

use super::*;

#[test]
fn startup_url_has_fixed_field_order_and_omits_credentials() {
    for (query, expected) in [
        (
            "",
            "portal://127.0.0.1:2000?out=direct&tls=1&dial=auto&morph=1&rate=0&etar=0",
        ),
        (
            "?net=%ZZ&socks=%FF&unknown=%ZZ",
            "portal://127.0.0.1:2000?out=direct&tls=1&dial=auto&morph=1&rate=0&etar=0",
        ),
        (
            "?etar=20&rate=10&dial4=127.0.0.1&morph=1&out=a%3Ab:p%40ss@localhost:1080",
            "portal://127.0.0.1:2000?out=localhost:1080&tls=1&dial4=127.0.0.1&dial6=auto&morph=1&rate=10&etar=20",
        ),
        (
            "?out=egress&unknown=secret",
            "portal://127.0.0.1:2000?out=egress&tls=1&morph=1&rate=0&etar=0",
        ),
        (
            "?morph=0",
            "portal://127.0.0.1:2000?out=direct&tls=1&dial=auto&morph=0&rate=0&etar=0",
        ),
    ] {
        let portal = Portal::new(
            Url::parse(&format!(
                "portal://0123456789abcdef0123456789abcdef@127.0.0.1:2000{query}"
            ))
            .unwrap(),
            Logger,
        )
        .unwrap();
        assert_eq!(portal.inner.effective, expected);
        assert_eq!(
            portal.inner.morph_keys.is_some(),
            !query.contains("morph=0")
        );
    }
}
