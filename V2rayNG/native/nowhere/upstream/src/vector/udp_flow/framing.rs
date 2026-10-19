// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Cancellation-safe UDP-over-stream packet decoding.

use super::*;

#[derive(Default)]
pub(super) struct UotReadState {
    header: [u8; 2],
    pub(super) header_read: usize,
    payload_len: Option<usize>,
    payload_read: usize,
}

impl UotReadState {
    pub(super) async fn read_packet(
        &mut self,
        reader: &mut BoxReader,
        payload: &mut Vec<u8>,
    ) -> Result<Option<usize>> {
        while self.header_read != self.header.len() {
            let read = reader
                .read(&mut self.header[self.header_read..])
                .await
                .context("vector::udp_flow::UotReadState: failed to read packet length")?;
            if read == 0 {
                if self.header_read == 0 {
                    payload.clear();
                    return Ok(None);
                }
                bail!("vector::udp_flow::UotReadState: truncated packet length");
            }
            self.header_read += read;
        }

        let payload_len = *self
            .payload_len
            .get_or_insert_with(|| u16::from_be_bytes(self.header) as usize);
        payload.resize(payload_len, 0);
        while self.payload_read != payload_len {
            let read = reader
                .read(&mut payload[self.payload_read..])
                .await
                .context("vector::udp_flow::UotReadState: failed to read packet payload")?;
            if read == 0 {
                bail!("vector::udp_flow::UotReadState: truncated packet payload");
            }
            self.payload_read += read;
        }

        self.header_read = 0;
        self.payload_len = None;
        self.payload_read = 0;
        Ok(Some(payload_len))
    }
}

#[cfg(test)]
#[path = "../../tests/vector/udp_flow/framing.rs"]
mod tests;
