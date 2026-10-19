// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Test-only inspection of unauthenticated admission counters.

use super::*;

impl UnauthenticatedAdmission {
    pub(in crate::portal) fn active(&self) -> usize {
        self.state
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .total
    }
}
#[test]
fn unauthenticated_budgets_bound_sources_and_reclaim_permissions() {
    let admission = Arc::new(UnauthenticatedAdmission::new());
    let mut held = Vec::new();
    for _ in 0..MAX_UNAUTHENTICATED_PER_SOURCE {
        held.push(admission.try_acquire("127.0.0.1".parse().unwrap()).unwrap());
    }
    assert!(
        admission
            .try_acquire("127.0.0.1".parse().unwrap())
            .is_none()
    );
    held.pop();
    assert!(
        admission
            .try_acquire("127.0.0.1".parse().unwrap())
            .is_some()
    );
    drop(held);
    assert_eq!(admission.active(), 0);
    let mut held = Vec::new();
    for index in 0..MAX_UNAUTHENTICATED_CONNECTIONS {
        held.push(
            admission
                .try_acquire(IpAddr::V4(Ipv4Addr::new(
                    10,
                    0,
                    (index / 255) as u8,
                    index as u8,
                )))
                .unwrap(),
        );
    }
    assert!(
        admission
            .try_acquire("192.0.2.1".parse().unwrap())
            .is_none()
    );
    drop(held);
    assert_eq!(admission.active(), 0);
    let held = (0..MAX_UNAUTHENTICATED_PER_SOURCE)
        .map(|i| {
            admission
                .try_acquire(format!("2001:db8:1:2::{i:x}").parse().unwrap())
                .unwrap()
        })
        .collect::<Vec<_>>();
    assert!(
        admission
            .try_acquire("2001:db8:1:2::ffff".parse().unwrap())
            .is_none()
    );
    assert!(
        admission
            .try_acquire("2001:db8:1:3::1".parse().unwrap())
            .is_some()
    );
    drop(held);
    assert_eq!(admission.active(), 0);
}
