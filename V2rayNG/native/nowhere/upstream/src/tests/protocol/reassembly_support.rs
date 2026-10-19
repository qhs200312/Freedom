// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Reassembly state accessors used by internal tests.

use super::*;

impl<R> DatagramReassembler<R> {
    pub(crate) fn slot_count(&self) -> usize {
        self.slots.len()
    }

    pub(crate) fn reserved_bytes(&self) -> usize {
        self.reserved_bytes
    }
}

impl DatagramReassembler<()> {
    pub(crate) fn push(
        &mut self,
        flow_id: FlowId,
        fragment: OwnedUdpFragment,
        now: Instant,
    ) -> ReassemblyOutcome {
        self.push_with(flow_id, fragment, now, |_| Some(()))
    }
}
