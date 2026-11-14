// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! PC-0 parity with the existing Rust implementation.
//!
//! The portable crate is meant to become the single state contract. Until the Android-side
//! modules are replaced, this test pins the behaviour the two implementations already share:
//! tombstone dominance, concurrent candidates, clock dominance and conflict resolution. If the
//! core semantics change, this test fails instead of letting the two layers drift apart.

use std::collections::BTreeMap;

fn portable_clock(pairs: &[(&str, u64)]) -> traceweave_portable::Clock {
    traceweave_portable::Clock(
        pairs.iter().map(|(actor, counter)| (actor.to_string(), *counter)).collect(),
    )
}

fn core_clock(pairs: &[(&str, u64)]) -> agent_os_core::causal_memory::Clock {
    agent_os_core::causal_memory::Clock(
        pairs.iter().map(|(actor, counter)| (actor.to_string(), *counter)).collect(),
    )
}

fn portable_version(pairs: &[(&str, u64)], value: Option<&str>) -> traceweave_portable::Version {
    traceweave_portable::Version {
        clock: portable_clock(pairs),
        value: value.map(str::to_string),
    }
}

fn core_version(pairs: &[(&str, u64)], value: Option<&str>) -> agent_os_core::causal_memory::Version {
    agent_os_core::causal_memory::Version {
        clock: core_clock(pairs),
        value: value.map(str::to_string),
    }
}

/// Normalize both results to a sorted list of (canonical clock, value); an empty list means a
/// tombstone in the core API and `MergeResult::Tombstoned` in the portable API.
fn normalize_portable(result: traceweave_portable::MergeResult) -> Vec<(BTreeMap<String, u64>, Option<String>)> {
    match result {
        traceweave_portable::MergeResult::Tombstoned => Vec::new(),
        traceweave_portable::MergeResult::Live(versions) => versions
            .into_iter()
            .map(|version| (version.clock.0, version.value))
            .collect(),
    }
}

fn normalize_core(versions: Vec<agent_os_core::causal_memory::Version>) -> Vec<(BTreeMap<String, u64>, Option<String>)> {
    // The core API represents a tombstone as a surviving `value: None` candidate; the portable
    // API names that outcome `MergeResult::Tombstoned`. Normalize both to "no live candidates".
    if versions.iter().any(|version| version.value.is_none()) {
        return Vec::new();
    }
    versions.into_iter().map(|version| (version.clock.0, version.value)).collect()
}

#[test]
fn merge_semantics_match_the_existing_core() {
    let cases: Vec<(Vec<((&str, u64), Option<&str>)>, Vec<((&str, u64), Option<&str>)>)> = vec![
        // live edit dominates an older live edit
        (
            vec![((&"android", 1), Some("v1"))],
            vec![((&"android", 2), Some("v2"))],
        ),
        // concurrent edits from two devices stay separate candidates
        (
            vec![((&"android", 1), Some("from phone"))],
            vec![((&"mac", 1), Some("from laptop"))],
        ),
        // tombstone wins over an offline edit and cannot be resurrected
        (
            vec![((&"android", 2), None)],
            vec![((&"mac", 1), Some("offline edit"))],
        ),
        // conflicting content under the same clock is rejected by both implementations
        // (covered by the explicit error test below)
    ];

    for (left, right) in cases {
        let portable_left: Vec<_> = left.iter().map(|((actor, counter), value)| portable_version(&[(*actor, *counter)], *value)).collect();
        let portable_right: Vec<_> = right.iter().map(|((actor, counter), value)| portable_version(&[(*actor, *counter)], *value)).collect();
        let core_left: Vec<_> = left.iter().map(|((actor, counter), value)| core_version(&[(*actor, *counter)], *value)).collect();
        let core_right: Vec<_> = right.iter().map(|((actor, counter), value)| core_version(&[(*actor, *counter)], *value)).collect();

        let mut portable = normalize_portable(traceweave_portable::merge(&portable_left, &portable_right).unwrap());
        let mut core = normalize_core(agent_os_core::causal_memory::merge(&core_left, &core_right).unwrap());
        portable.sort();
        core.sort();
        assert_eq!(portable, core, "merge mismatch for {left:?} vs {right:?}");
    }
}

#[test]
fn resolve_semantics_match_the_existing_core() {
    let portable_choices = vec![
        portable_version(&[("android", 1)], Some("left")),
        portable_version(&[("mac", 1)], Some("right")),
    ];
    let core_choices = vec![
        core_version(&[("android", 1)], Some("left")),
        core_version(&[("mac", 1)], Some("right")),
    ];
    let portable = traceweave_portable::resolve(&portable_choices, 1, "newdevice").unwrap();
    let core = agent_os_core::causal_memory::resolve(&core_choices, 1, "newdevice").unwrap();
    assert_eq!(portable.value, core.value);
    assert_eq!(portable.clock.0, core.clock.0);
}

#[test]
fn conflicting_value_under_one_clock_is_rejected_by_both() {
    let portable = traceweave_portable::merge(
        &[portable_version(&[("android", 1)], Some("a"))],
        &[portable_version(&[("android", 1)], Some("b"))],
    );
    let core = agent_os_core::causal_memory::merge(
        &[core_version(&[("android", 1)], Some("a"))],
        &[core_version(&[("android", 1)], Some("b"))],
    );
    assert!(portable.is_err());
    assert!(core.is_err());
}
