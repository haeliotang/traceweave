// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! PC-0 contract tests: the same logical state must produce the same canonical bytes through
//! any adapter, and resume must never replay an external effect.

use std::collections::BTreeMap;
use traceweave_portable::{
    authorize, AuditChain, CheckpointError, CheckpointStep, Clock, Decision, EffectOutcome,
    Grant, GrantRequest, StepStatus, SubstrateState, TaskCheckpoint, TaskStatus, Version,
    STATE_FORMAT_VERSION,
};

fn version(actor: &str, counter: u64, value: Option<&str>) -> Version {
    let mut clock = BTreeMap::new();
    clock.insert(actor.to_string(), counter);
    Version {
        clock: Clock(clock),
        value: value.map(str::to_string),
    }
}

fn grant(agent: &str, task: &str, capability: &str, scope: &str, expires_at: i64, revoked: bool) -> Grant {
    Grant {
        agent_id: agent.into(),
        task_id: task.into(),
        capability: capability.into(),
        scope: scope.into(),
        expires_at,
        revoked,
    }
}

fn task_with_effect() -> (TaskCheckpoint, SubstrateState) {
    let steps = vec![CheckpointStep {
        index: 0,
        capability: "calendar".into(),
        scope: "write".into(),
        status: StepStatus::Pending,
        evidence: Vec::new(),
    }];
    let checkpoint = TaskCheckpoint::new("task-1", "research deadline", steps);
    let mut state = SubstrateState::new();
    state.put_task(checkpoint.clone()).unwrap();
    (checkpoint, state)
}

#[test]
fn tombstone_wins_and_cannot_be_resurrected() {
    let live = version("a", 1, Some("hello"));
    let tombstone = version("a", 2, None);
    let merged = traceweave_portable::merge(&[live.clone()], &[tombstone.clone()]).unwrap();
    assert_eq!(merged, traceweave_portable::MergeResult::Tombstoned);

    let resurrect = version("b", 1, Some("hello again"));
    let merged = traceweave_portable::merge(&[tombstone], &[resurrect]).unwrap();
    assert_eq!(merged, traceweave_portable::MergeResult::Tombstoned);
}

#[test]
fn concurrent_edits_remain_separate_candidates() {
    let left = version("a", 1, Some("left"));
    let right = version("b", 1, Some("right"));
    let merged = traceweave_portable::merge(&[left.clone()], &[right.clone()]).unwrap();
    match merged {
        traceweave_portable::MergeResult::Live(mut versions) => {
            versions.sort();
            let mut expected = vec![left, right];
            expected.sort();
            assert_eq!(versions, expected);
        }
        traceweave_portable::MergeResult::Tombstoned => panic!("unexpected tombstone"),
    }
}

#[test]
fn resolve_advances_the_choosing_actor_clock() {
    let left = version("a", 1, Some("left"));
    let right = version("b", 1, Some("right"));
    let resolved = traceweave_portable::resolve(&[left, right], 1, "c").unwrap();
    assert_eq!(resolved.value.as_deref(), Some("right"));
    assert_eq!(resolved.clock.0.get("a"), Some(&1));
    assert_eq!(resolved.clock.0.get("b"), Some(&1));
    assert_eq!(resolved.clock.0.get("c"), Some(&1));
}

#[test]
fn completed_effect_is_never_replayed() {
    let (mut checkpoint, _) = task_with_effect();
    checkpoint.begin_effect("op-1", "calendar", "write", 10).unwrap();
    checkpoint.finish_effect("op-1", EffectOutcome::Succeeded, 11).unwrap();
    assert_eq!(
        checkpoint.begin_effect("op-1", "calendar", "write", 12),
        Err(CheckpointError::AlreadyPerformed("op-1".into()))
    );
    let view = checkpoint.resume_view();
    assert!(view.retryable_effects.is_empty());
    assert!(view.effects_needing_reconciliation.is_empty());
}

#[test]
fn crash_between_begin_and_finish_needs_reconciliation_after_restore() {
    let (mut checkpoint, mut state) = task_with_effect();
    checkpoint.begin_effect("op-1", "calendar", "write", 10).unwrap();
    state.put_task(checkpoint).unwrap();

    // Simulated process restart: only canonical bytes survive.
    let bytes = state.canonical_bytes().unwrap();
    let mut restored = SubstrateState::from_canonical_bytes(&bytes).unwrap();
    let mut checkpoint = restored.tasks.remove("task-1").unwrap();

    assert_eq!(
        checkpoint.begin_effect("op-1", "calendar", "write", 20),
        Err(CheckpointError::NeedsReconciliation("op-1".into()))
    );
    checkpoint.reconcile_effect("op-1", EffectOutcome::Succeeded, 21).unwrap();
    assert_eq!(
        checkpoint.begin_effect("op-1", "calendar", "write", 22),
        Err(CheckpointError::AlreadyPerformed("op-1".into()))
    );
}

#[test]
fn unknown_outcome_blocks_retry_until_reconciled() {
    let (mut checkpoint, _) = task_with_effect();
    checkpoint.begin_effect("op-1", "calendar", "write", 10).unwrap();
    checkpoint.finish_effect("op-1", EffectOutcome::Unknown, 11).unwrap();
    assert_eq!(
        checkpoint.begin_effect("op-1", "calendar", "write", 12),
        Err(CheckpointError::NeedsReconciliation("op-1".into()))
    );
    checkpoint.reconcile_effect("op-1", EffectOutcome::Failed, 13).unwrap();
    checkpoint.begin_effect("op-1", "calendar", "write", 14).unwrap();
    assert_eq!(checkpoint.effects["op-1"].attempts, 2);
}

#[test]
fn policy_decision_does_not_depend_on_grant_order() {
    let request = GrantRequest {
        agent_id: "agent-a",
        task_id: "task-1",
        capability: "calendar",
        scope: "write",
    };
    let allowed = grant("agent-a", "task-1", "calendar", "write", 100, false);
    let expired = grant("agent-a", "task-1", "calendar", "read", 50, false);
    let revoked = grant("agent-a", "task-1", "calendar", "delete", 100, true);
    let forward = authorize(&[revoked.clone(), expired.clone(), allowed.clone()], &request, 60);
    let backward = authorize(&[allowed, expired, revoked], &request, 60);
    assert_eq!(forward, Decision::Allowed);
    assert_eq!(forward, backward);
}

#[test]
fn policy_reports_expiry_and_revocation() {
    let request = GrantRequest {
        agent_id: "agent-a",
        task_id: "task-1",
        capability: "calendar",
        scope: "write",
    };
    let expired = grant("agent-a", "task-1", "calendar", "write", 100, false);
    let revoked = grant("agent-a", "task-1", "calendar", "write", i64::MAX, true);
    assert_eq!(authorize(&[expired], &request, 101), Decision::Expired);
    assert_eq!(authorize(&[revoked], &request, 0), Decision::Revoked);
    assert_eq!(
        authorize(
            &[grant("agent-a", "task-2", "calendar", "write", 100, false)],
            &request,
            0
        ),
        Decision::TaskMismatch
    );
    assert_eq!(authorize(&[], &request, 0), Decision::NoGrant);
}

#[test]
fn canonical_bytes_ignore_adapter_insertion_order() {
    let mut first = SubstrateState::new();
    first.add_grant(grant("agent-b", "task-2", "file", "read", 10, false));
    first.add_grant(grant("agent-a", "task-1", "calendar", "write", 20, false));

    let mut second = SubstrateState::new();
    second.add_grant(grant("agent-a", "task-1", "calendar", "write", 20, false));
    second.add_grant(grant("agent-b", "task-2", "file", "read", 10, false));

    assert_eq!(first.canonical_bytes().unwrap(), second.canonical_bytes().unwrap());
}

#[test]
fn canonical_envelope_round_trips_and_rejects_non_canonical_bytes() {
    let (checkpoint, mut state) = task_with_effect();
    state.put_task(checkpoint).unwrap();
    state.add_grant(grant("agent-b", "task-2", "file", "read", 10, false));
    state.add_grant(grant("agent-a", "task-1", "calendar", "write", 20, false));
    state.merge_memory("mem-1", &[version("a", 1, Some("hello"))]).unwrap();
    let bytes = state.canonical_bytes().unwrap();
    let restored = SubstrateState::from_canonical_bytes(&bytes).unwrap();
    assert_eq!(restored.canonical_bytes().unwrap(), bytes);

    let mut json: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    let grants = json.get_mut("grants").unwrap().as_array_mut().unwrap();
    grants.reverse();
    let reordered = serde_json::to_vec(&json).unwrap();
    assert!(SubstrateState::from_canonical_bytes(&reordered).is_err());
}

#[test]
fn unknown_format_version_is_rejected() {
    let state = SubstrateState::new();
    let mut json: serde_json::Value = serde_json::from_slice(&state.canonical_bytes().unwrap()).unwrap();
    json["version"] = serde_json::json!(STATE_FORMAT_VERSION + 1);
    let bytes = serde_json::to_vec(&json).unwrap();
    assert!(SubstrateState::from_canonical_bytes(&bytes).is_err());
}

#[test]
fn audit_chain_detects_tampering() {
    let mut chain = AuditChain::new();
    chain.append(b"task-created");
    chain.append(b"grant-issued");
    chain.append(b"task-completed");
    assert!(chain.verify());
    assert_eq!(chain.len(), 3);

    let mut tampered = chain.clone();
    tampered.entries_mut()[1].payload_hash[0] ^= 1;
    assert!(!tampered.verify());

    let mut truncated = chain.clone();
    truncated.entries_mut().remove(1);
    assert!(!truncated.verify());
}

#[test]
fn resume_view_lists_open_work_and_authorization_need() {
    let (checkpoint, _) = task_with_effect();
    let view = checkpoint.resume_view();
    assert_eq!(view.status, TaskStatus::Queued);
    assert_eq!(view.open_steps, vec![0]);
    assert!(view.needs_authorization);
    assert_eq!(view.task_id, "task-1");
}
