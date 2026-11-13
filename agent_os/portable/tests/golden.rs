// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Golden canonical fixture: every adapter and every OS must produce exactly these bytes.
//!
//! The digest is pinned so a change in field order, map ordering, grant ordering or audit
//! encoding is a test failure rather than a silent protocol change. Update it only together
//! with a state format version bump and a compatibility note.

use std::collections::BTreeMap;
use traceweave_portable::{
    AuditChain, CheckpointStep, Clock, EffectOutcome, Grant, StepStatus, SubstrateState,
    TaskCheckpoint, Version,
};

fn golden_state() -> SubstrateState {
    let mut state = SubstrateState::new();

    // mem-1: two concurrent edits from two devices (both survive as candidates).
    for (actor, counter, value) in [("android", 1u64, "holiday plan"), ("mac", 1u64, "holiday plan v2")] {
        let mut clock = BTreeMap::new();
        clock.insert(actor.to_string(), counter);
        state
            .merge_memory("mem-1", &[Version { clock: Clock(clock), value: Some(value.to_string()) }])
            .unwrap();
    }

    // mem-2: tombstoned, must never come back.
    let mut tombstone = BTreeMap::new();
    tombstone.insert("android".to_string(), 2u64);
    state
        .merge_memory("mem-2", &[Version { clock: Clock(tombstone), value: None }])
        .unwrap();

    let mut checkpoint = TaskCheckpoint::new(
        "task-1",
        "book the train",
        vec![CheckpointStep {
            index: 0,
            capability: "calendar".into(),
            scope: "write".into(),
            status: StepStatus::Pending,
            evidence: vec!["sha256:abc".into()],
        }],
    );
    checkpoint.begin_effect("op-1", "calendar", "write", 10).unwrap();
    checkpoint.finish_effect("op-1", EffectOutcome::Succeeded, 11).unwrap();
    state.put_task(checkpoint).unwrap();

    state.add_grant(Grant {
        agent_id: "agent-b".into(),
        task_id: "task-1".into(),
        capability: "file".into(),
        scope: "read".into(),
        expires_at: 1_000,
        revoked: false,
    });
    state.add_grant(Grant {
        agent_id: "agent-a".into(),
        task_id: "task-1".into(),
        capability: "calendar".into(),
        scope: "write".into(),
        expires_at: 2_000,
        revoked: false,
    });

    let mut audit = AuditChain::new();
    audit.append(b"task-created");
    audit.append(b"effect-completed");
    state.audit = audit;
    state
}

fn sha256_hex(bytes: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    Sha256::digest(bytes).iter().map(|byte| format!("{byte:02x}")).collect()
}

#[test]
fn golden_canonical_bytes_are_stable() {
    let state = golden_state();
    let bytes = state.canonical_bytes().unwrap();
    let digest = sha256_hex(&bytes);
    assert_eq!(
        digest,
        "bf57923ad8cfa7006d3fa3f0e85343dbe2374e047172f5603b7a42ff86506fbb",
        "canonical state format changed; bump STATE_FORMAT_VERSION and document the migration"
    );

    let restored = SubstrateState::from_canonical_bytes(&bytes).unwrap();
    assert_eq!(restored.canonical_bytes().unwrap(), bytes);
    assert!(restored.audit.verify());
}
