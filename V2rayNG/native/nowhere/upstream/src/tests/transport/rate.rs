// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Transport rate-limiter tests.

use std::time::Duration;

use super::*;

#[test]
fn rate_limiter_is_disabled_without_limits() {
    assert!(RateLimiter::new(0, 0).is_none());
    assert!(RateLimiter::new(1, 0).is_some());
    assert!(RateLimiter::new(0, 1).is_some());
}

#[test]
fn rate_limiter_keeps_disabled_direction_empty() {
    let read_only = RateLimiter::new(1, 0).unwrap();
    assert!(read_only.read.is_some());
    assert!(read_only.write.is_none());

    let write_only = RateLimiter::new(0, 1).unwrap();
    assert!(write_only.read.is_none());
    assert!(write_only.write.is_some());
}

#[test]
fn reserve_debits_future_budget_when_empty() {
    let tb = TokenBucket::new(100, 0);
    let base = token_bucket_now();
    assert_eq!(tb.reserve(base, 50), Duration::from_millis(500));
    assert_eq!(
        tb.reserve(base + Duration::from_millis(500), 50),
        Duration::from_millis(500)
    );
}

#[test]
fn budget_refills_to_capacity() {
    let tb = TokenBucket::new(100, 100);
    let base = token_bucket_now();
    tb.spend(base, 80);
    assert_eq!(tb.budget(base + Duration::from_millis(500)), 70);
    assert_eq!(tb.budget(base + Duration::from_secs(10)), 100);
}

#[test]
fn configure_clamps_existing_budget_to_new_capacity() {
    let tb = TokenBucket::new(100, 100);
    let base = token_bucket_now();
    tb.spend(base, 30);
    assert_eq!(tb.budget(base), 70);

    tb.configure(base, 100, 50);
    assert_eq!(tb.budget(base), 50);
}

#[test]
fn delay_until_available_respects_minimum_delay() {
    let tb = TokenBucket::new(100, 0);
    let base = token_bucket_now();
    assert_eq!(
        tb.delay_until_available(base, 1, Duration::from_millis(20)),
        Duration::from_millis(20)
    );

    let tb = TokenBucket::new(100, 100);
    assert_eq!(
        tb.delay_until_available(base, 1, Duration::from_millis(20)),
        Duration::ZERO
    );
}

#[test]
fn reset_restores_capacity() {
    let tb = TokenBucket::new(100, 100);
    let base = token_bucket_now();
    tb.spend(base, 90);
    assert_eq!(tb.budget(base), 10);

    tb.reset(base + Duration::from_secs(1));
    assert_eq!(tb.budget(base + Duration::from_secs(1)), 100);
}
