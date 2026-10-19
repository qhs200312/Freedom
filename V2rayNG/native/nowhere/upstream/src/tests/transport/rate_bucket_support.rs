// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Token-bucket state controls used by internal tests.

use super::*;

impl TokenBucket {
    pub(crate) fn configure(&self, now: Duration, rate: i64, capacity: i64) {
        let mut inner = self.inner.lock().expect("token bucket poisoned");
        inner.refill(now);
        inner.rate = normalize_rate(rate);
        inner.capacity = capacity.max(0);
        if inner.budget > inner.capacity {
            inner.budget = inner.capacity;
        }
    }

    pub(crate) fn budget(&self, now: Duration) -> i64 {
        let mut inner = self.inner.lock().expect("token bucket poisoned");
        inner.refill(now);
        inner.budget
    }

    pub(crate) fn spend(&self, now: Duration, bytes: i64) {
        if bytes <= 0 {
            return;
        }
        let mut inner = self.inner.lock().expect("token bucket poisoned");
        inner.refill(now);
        inner.budget -= bytes;
    }

    pub(crate) fn delay_until_available(
        &self,
        now: Duration,
        bytes: i64,
        min_delay: Duration,
    ) -> Duration {
        if bytes <= 0 {
            return Duration::ZERO;
        }
        let mut inner = self.inner.lock().expect("token bucket poisoned");
        inner.refill(now);
        if bytes <= inner.budget {
            return Duration::ZERO;
        }
        let delay = duration_for_bytes(bytes - inner.budget, inner.rate);
        delay.max(min_delay)
    }
}
