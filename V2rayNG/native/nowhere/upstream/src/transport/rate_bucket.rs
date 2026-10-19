// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Token-bucket accounting for transport rate limits.

use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

const UNLIMITED_BYTES_PER_SECOND: i64 = 1_i64 << 40;

static TOKEN_BUCKET_START: OnceLock<Instant> = OnceLock::new();

#[derive(Debug)]
pub struct TokenBucket {
    inner: Mutex<TokenBucketInner>,
}

#[derive(Debug)]
struct TokenBucketInner {
    rate: i64,
    capacity: i64,
    budget: i64,
    updated_at: Duration,
}

impl TokenBucket {
    pub fn new(rate: i64, capacity: i64) -> Self {
        let rate = normalize_rate(rate);
        let capacity = capacity.max(0);
        Self {
            inner: Mutex::new(TokenBucketInner {
                rate,
                capacity,
                budget: capacity,
                updated_at: token_bucket_now(),
            }),
        }
    }

    pub fn reserve(&self, now: Duration, bytes: i64) -> Duration {
        if bytes <= 0 {
            return Duration::ZERO;
        }
        let mut inner = self.inner.lock().expect("token bucket poisoned");
        inner.refill(now);
        if bytes <= inner.budget {
            inner.budget -= bytes;
            return Duration::ZERO;
        }

        let missing = bytes - inner.budget;
        let delay = duration_for_bytes(missing, inner.rate);
        inner.budget = 0;
        inner.updated_at += delay;
        inner.updated_at.saturating_sub(now)
    }

    pub fn reset(&self, now: Duration) {
        let mut inner = self.inner.lock().expect("token bucket poisoned");
        inner.budget = inner.capacity;
        inner.updated_at = now;
    }

    pub(super) async fn wait(&self, bytes: i64) {
        let sleep = self.reserve(token_bucket_now(), bytes);
        if !sleep.is_zero() {
            tokio::time::sleep(sleep).await;
        }
    }
}

impl TokenBucketInner {
    fn refill(&mut self, now: Duration) {
        if now <= self.updated_at {
            return;
        }
        if self.capacity > 0 {
            let added = (self.rate as f64 * (now - self.updated_at).as_secs_f64()).trunc() as i64;
            self.budget += added;
            if self.budget > self.capacity {
                self.budget = self.capacity;
            }
        }
        self.updated_at = now;
    }
}

pub(super) fn token_bucket_now() -> Duration {
    TOKEN_BUCKET_START.get_or_init(Instant::now).elapsed()
}

fn duration_for_bytes(bytes: i64, rate: i64) -> Duration {
    let rate = normalize_rate(rate);
    let nanos = (bytes as f64 / rate as f64 * 1_000_000_000.0).trunc() as u64;
    if nanos == 0 {
        Duration::from_nanos(1)
    } else {
        Duration::from_nanos(nanos)
    }
}

fn normalize_rate(rate: i64) -> i64 {
    if rate <= 0 {
        UNLIMITED_BYTES_PER_SECOND
    } else {
        rate
    }
}

#[cfg(test)]
#[path = "../tests/transport/rate_bucket_support.rs"]
mod test_support;
