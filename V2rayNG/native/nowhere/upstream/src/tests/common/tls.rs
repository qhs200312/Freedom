// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! TLS configuration tests.

use super::*;
use crate::common::Logger;

fn new_server_configs(
    parsed_url: &Url,
    logger: Logger,
) -> Result<(TLSMode, Arc<rustls::ServerConfig>, quinn::ServerConfig)> {
    new_server_configs_with_reload_interval(
        parsed_url,
        std::time::Duration::from_secs(60 * 60),
        logger,
    )
}

#[test]
fn certificate_sha256_uses_lowercase_hex() {
    let cert = rustls::pki_types::CertificateDer::from(b"abc".to_vec());
    assert_eq!(
        certificate_sha256(&cert),
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    );
}

#[test]
fn server_tls_config_explicitly_disables_early_data() {
    let (_, tls, _quic) = new_server_configs(
        &Url::parse("portal://secret@127.0.0.1:2000?tls=1").unwrap(),
        Logger,
    )
    .unwrap();

    assert_eq!(tls.max_early_data_size, 0);
    assert!(!tls.send_half_rtt_data);
}

#[test]
fn server_offers_only_nw3() {
    let (_, tls, _) = new_server_configs(
        &Url::parse("portal://secret@127.0.0.1:2000?tls=1&unknown=ignored").unwrap(),
        Logger,
    )
    .unwrap();

    assert_eq!(tls.alpn_protocols, [b"nw3".to_vec()]);
}
