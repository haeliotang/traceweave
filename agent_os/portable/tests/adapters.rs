// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Adapter parity: two different host adapters, one logical state, one canonical envelope.

use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use traceweave_portable::{
    AuditChain, CheckpointStep, Clock, DeviceIdentityPort, EffectOutcome, Grant, StateStorePort,
    StepStatus, SubstrateState, TaskCheckpoint, Version,
};

struct MockDeviceIdentity {
    fingerprint: [u8; 32],
}

impl DeviceIdentityPort for MockDeviceIdentity {
    fn device_fingerprint(&self) -> [u8; 32] {
        self.fingerprint
    }

    fn verify_device_signature(&self, message: &[u8], signature: &[u8]) -> bool {
        signature == Sha256::digest(message).as_slice()
    }
}

/// A macOS-like adapter: state kept in memory during the process.
struct MemoryStore {
    bytes: Option<Vec<u8>>,
}

impl StateStorePort for MemoryStore {
    fn load_state(&self) -> Result<Option<Vec<u8>>, String> {
        Ok(self.bytes.clone())
    }

    fn save_state(&mut self, canonical_bytes: &[u8]) -> Result<(), String> {
        self.bytes = Some(canonical_bytes.to_vec());
        Ok(())
    }
}

/// A Linux reference-node-like adapter: state kept in a single file-like slot.
struct FileStore {
    contents: Vec<u8>,
}

impl StateStorePort for FileStore {
    fn load_state(&self) -> Result<Option<Vec<u8>>, String> {
        Ok((!self.contents.is_empty()).then(|| self.contents.clone()))
    }

    fn save_state(&mut self, canonical_bytes: &[u8]) -> Result<(), String> {
        self.contents = canonical_bytes.to_vec();
        Ok(())
    }
}

fn sample_state(grants: &[Grant]) -> SubstrateState {
    let mut state = SubstrateState::new();
    state
        .merge_memory(
            "mem-1",
            &[Version {
                clock: Clock(BTreeMap::from([("android".to_string(), 1)])),
                value: Some("holiday plan".to_string()),
            }],
        )
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

    for grant in grants {
        state.add_grant(grant.clone());
    }

    let mut audit = AuditChain::new();
    audit.append(b"task-created");
    audit.append(b"effect-completed");
    state.audit = audit;
    state
}

fn grant(agent: &str) -> Grant {
    Grant {
        agent_id: agent.into(),
        task_id: "task-1".into(),
        capability: "calendar".into(),
        scope: "write".into(),
        expires_at: 1_000,
        revoked: false,
    }
}

#[test]
fn two_adapters_produce_the_same_canonical_state() {
    // The adapters insert grants in a different order, as separate installations would.
    let first = sample_state(&[grant("agent-b"), grant("agent-a")]);
    let second = sample_state(&[grant("agent-a"), grant("agent-b")]);

    let mut memory = MemoryStore { bytes: None };
    memory.save_state(&first.canonical_bytes().unwrap()).unwrap();

    let mut file = FileStore { contents: Vec::new() };
    file.save_state(&second.canonical_bytes().unwrap()).unwrap();

    assert_eq!(memory.load_state().unwrap(), file.load_state().unwrap());

    let restored = SubstrateState::from_canonical_bytes(&memory.load_state().unwrap().unwrap()).unwrap();
    assert_eq!(restored.canonical_bytes().unwrap(), memory.load_state().unwrap().unwrap());
    assert!(restored.audit.verify());
}

#[test]
fn device_identity_port_is_the_only_identity_input() {
    let identity = MockDeviceIdentity {
        fingerprint: [7u8; 32],
    };
    let message = b"device-enrollment-challenge";
    let signature = Sha256::digest(message).to_vec();

    assert_eq!(identity.device_fingerprint(), [7u8; 32]);
    assert!(identity.verify_device_signature(message, &signature));
    assert!(!identity.verify_device_signature(b"other", &signature));
    assert!(!identity.verify_device_signature(message, b"short"));
}
