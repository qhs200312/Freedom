// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Exporter-bound Egress authentication and the ACCEPTED registration barrier.

use crate::protocol::{AUTH_EXPORTER_LABEL, AuthTransport, encode_auth_frame};
use anyhow::{Context, Result};

pub(super) async fn register(connection: &quinn::Connection, key: [u8; 32]) -> Result<()> {
    let mut exporter = [0; 32];
    connection
        .export_keying_material(&mut exporter, AUTH_EXPORTER_LABEL, b"")
        .map_err(|e| anyhow::anyhow!("exporter failed: {e:?}"))?;
    let (mut send, mut recv) = connection.open_bi().await?;
    send.write_all(&encode_auth_frame(key, AuthTransport::Quic, &exporter))
        .await?;
    send.finish()?;
    let accepted = tokio::time::timeout(crate::common::handshake_timeout(), async {
        let registration = async {
            let mut byte = [0];
            recv.read_exact(&mut byte).await?;
            recv.read_to_end(0).await?;
            Ok::<_, anyhow::Error>(byte[0])
        };
        tokio::pin!(registration);
        let mut discarded = 0;
        loop {
            tokio::select! {
                biased;
                datagram = connection.read_datagram() => {
                    datagram?;
                    discarded += 1;
                    if discarded % 64 == 0 {
                        tokio::task::yield_now().await;
                    }
                },
                result = &mut registration => break result,
            }
        }
    })
    .await
    .context("registration timeout")??;
    match accepted {
        0 => {}
        1 => anyhow::bail!("Egress registration rejected: slot occupied"),
        _ => anyhow::bail!("invalid Egress registration reply"),
    }
    Ok(())
}
