// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! Status display value formatting tests.

use super::*;

#[test]
fn formats_byte_totals_with_iec_units() {
    assert_eq!(bytes(0), "0 B");
    assert_eq!(bytes(1_536), "1.50 KiB");
    assert_eq!(bytes(10 * (1 << 20)), "10.0 MiB");
    assert_eq!(bytes(u64::MAX), "16384 PiB");
}

#[test]
fn panel_aligns_labels_and_values() {
    assert_eq!(
        panel(
            "NOWHERE PROBE",
            &[("Result", "OK".to_owned()), ("Setup", "1.2 ms".to_owned()),],
        ),
        "NOWHERE PROBE\n─────────────\nResult  OK\n Setup  1.2 ms"
    );
}

#[test]
fn duration_uses_compact_consistent_precision() {
    assert_eq!(format_duration(Duration::from_micros(1_250)), "1.2 ms");
    assert_eq!(format_duration(Duration::from_millis(25)), "25 ms");
}
