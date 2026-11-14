// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! PC-2 simulation: enrollment, revocation, rotation and recovery without a second phone.

use p256::ecdsa::signature::Signer;
use p256::ecdsa::SigningKey;
use p256::pkcs8::EncodePublicKey;
use traceweave_portable::{
    fingerprint, recover_state, CheckpointStep, Clock, DeviceEnrollment, DeviceRevocation,
    IdentityBook, IdentityError, IdentityRotation, StepStatus, SubstrateState, TaskCheckpoint,
    Trust, Version, IDENTITY_FORMAT_VERSION,
};

fn key(seed: u8) -> SigningKey {
    SigningKey::from_slice(&[seed; 32]).unwrap()
}

fn spki(signing: &SigningKey) -> Vec<u8> {
    signing.verifying_key().to_public_key_der().unwrap().as_bytes().to_vec()
}

fn sign(signing: &SigningKey, message: &[u8]) -> Vec<u8> {
    let signature: p256::ecdsa::Signature = signing.sign(message);
    signature.to_der().as_bytes().to_vec()
}

fn enrollment(root: &SigningKey, device: &SigningKey, device_id: &str, at: i64) -> DeviceEnrollment {
    let mut record = DeviceEnrollment {
        version: IDENTITY_FORMAT_VERSION,
        device_id: device_id.to_string(),
        device_pubkey: spki(device),
        enrolled_at: at,
        signer_pubkey: spki(root),
        signature: Vec::new(),
    };
    record.signature = sign(root, &record.signing_bytes().unwrap());
    record
}

fn revocation(root: &SigningKey, device_id: &str, at: i64) -> DeviceRevocation {
    let mut record = DeviceRevocation {
        version: IDENTITY_FORMAT_VERSION,
        device_id: device_id.to_string(),
        revoked_at: at,
        reason: "lost phone".to_string(),
        signer_pubkey: spki(root),
        signature: Vec::new(),
    };
    record.signature = sign(root, &record.signing_bytes().unwrap());
    record
}

fn rotation(old: &SigningKey, new: &SigningKey, at: i64) -> IdentityRotation {
    let mut record = IdentityRotation {
        version: IDENTITY_FORMAT_VERSION,
        new_pubkey: spki(new),
        rotated_at: at,
        signer_pubkey: spki(old),
        signature: Vec::new(),
    };
    record.signature = sign(old, &record.signing_bytes().unwrap());
    record
}

fn sample_state() -> SubstrateState {
    let mut state = SubstrateState::new();
    state
        .merge_memory(
            "mem-1",
            &[Version {
                clock: Clock(std::collections::BTreeMap::from([("phone".to_string(), 1)])),
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
            evidence: Vec::new(),
        }],
    );
    checkpoint.begin_effect("op-1", "calendar", "write", 10).unwrap();
    state.put_task(checkpoint).unwrap();
    state.add_grant(traceweave_portable::Grant {
        agent_id: "agent-a".into(),
        task_id: "task-1".into(),
        capability: "calendar".into(),
        scope: "write".into(),
        expires_at: 1_000,
        revoked: false,
    });
    state
}

#[test]
fn enrollment_revocation_and_historical_acceptance() {
    let root = key(4);
    let phone = key(7);
    let mut book = IdentityBook::genesis(spki(&root), 0).unwrap();

    book.enroll(enrollment(&root, &phone, "phone-1", 10)).unwrap();
    assert_eq!(book.trust("phone-1"), Trust::Active);
    assert_eq!(
        book.devices["phone-1"].fingerprint,
        fingerprint(&spki(&phone))
    );
    assert!(book.can_accept_record("phone-1", 11));

    book.revoke(revocation(&root, "phone-1", 20)).unwrap();
    assert_eq!(book.trust("phone-1"), Trust::Revoked { revoked_at: 20 });
    assert!(book.can_accept_record("phone-1", 19));
    assert!(!book.can_accept_record("phone-1", 20));
    assert!(!book.can_accept_record("phone-1", 21));
    assert_eq!(book.trust("unknown"), Trust::Unknown);
}

#[test]
fn forged_stale_and_duplicate_enrollments_are_rejected() {
    let root = key(4);
    let phone = key(7);
    let attacker = key(9);
    let mut book = IdentityBook::genesis(spki(&root), 0).unwrap();

    // Signature made by another key while claiming the root as signer.
    let mut forged = enrollment(&root, &phone, "phone-1", 10);
    forged.signature = sign(&attacker, &forged.signing_bytes().unwrap());
    assert_eq!(book.enroll(forged), Err(IdentityError::BadSignature));

    book.enroll(enrollment(&root, &phone, "phone-1", 10)).unwrap();
    assert_eq!(
        book.enroll(enrollment(&root, &phone, "phone-1", 11)),
        Err(IdentityError::DuplicateDevice("phone-1".into()))
    );

    // Rotation at 100 makes the old key stale for later records.
    let next = key(5);
    book.rotate(rotation(&root, &next, 100)).unwrap();
    assert_eq!(
        book.enroll(enrollment(&root, &phone, "phone-2", 110)),
        Err(IdentityError::StaleSigner)
    );
    book.enroll(enrollment(&next, &phone, "phone-2", 110)).unwrap();
    assert_eq!(book.trust("phone-2"), Trust::Active);
}

#[test]
fn recovery_keeps_history_but_clears_authority() {
    let state = sample_state();
    let restored = recover_state(&state);

    assert_eq!(restored.memories, state.memories);
    assert_eq!(restored.tasks["task-1"].goal, "book the train");
    assert_eq!(restored.tasks["task-1"].needs_authorization, true);
    assert!(restored.grants.is_empty(), "recovery must not restore grants");
    assert_eq!(restored.audit, state.audit);
    // A side effect started before recovery still needs reconciliation, not a blind retry.
    let mut recovered_task = restored.tasks["task-1"].clone();
    assert_eq!(
        recovered_task.begin_effect("op-1", "calendar", "write", 30),
        Err(traceweave_portable::CheckpointError::NeedsReconciliation("op-1".into()))
    );
}

#[test]
fn identity_book_round_trips_and_rejects_non_canonical_order() {
    let root = key(4);
    let next = key(5);
    let phone = key(7);
    let mut book = IdentityBook::genesis(spki(&root), 0).unwrap();
    book.enroll(enrollment(&root, &phone, "phone-1", 10)).unwrap();
    book.rotate(rotation(&root, &next, 100)).unwrap();

    let bytes = book.canonical_bytes().unwrap();
    let restored = IdentityBook::from_canonical_bytes(&bytes).unwrap();
    assert_eq!(restored.canonical_bytes().unwrap(), bytes);

    // Same records, wrong root order: still valid JSON, but not canonical.
    let mut json: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    let roots = json.get_mut("roots").unwrap().as_array_mut().unwrap();
    roots.reverse();
    let reordered = serde_json::to_vec(&json).unwrap();
    assert_eq!(
        IdentityBook::from_canonical_bytes(&reordered),
        Err(IdentityError::NonCanonical)
    );
}

#[test]
fn wrong_version_is_rejected() {
    let root = key(4);
    let book = IdentityBook::genesis(spki(&root), 0).unwrap();
    let mut json: serde_json::Value = serde_json::from_slice(&book.canonical_bytes().unwrap()).unwrap();
    json["version"] = serde_json::json!(IDENTITY_FORMAT_VERSION + 1);
    let bytes = serde_json::to_vec(&json).unwrap();
    assert!(matches!(
        IdentityBook::from_canonical_bytes(&bytes),
        Err(IdentityError::UnsupportedVersion(_))
    ));
}

#[test]
fn golden_identity_book_bytes_are_stable() {
    let root = key(4);
    let next = key(5);
    let phone = key(7);
    let mut book = IdentityBook::genesis(spki(&root), 0).unwrap();
    book.enroll(enrollment(&root, &phone, "phone-1", 10)).unwrap();
    book.revoke(revocation(&root, "phone-1", 20)).unwrap();
    book.rotate(rotation(&root, &next, 100)).unwrap();

    let bytes = book.canonical_bytes().unwrap();
    use sha2::{Digest, Sha256};
    let digest: String = Sha256::digest(&bytes).iter().map(|byte| format!("{byte:02x}")).collect();
    println!("identity golden digest: {digest} ({} bytes)", bytes.len());
    assert_eq!(
        digest,
        "0578a9ca5bf946b28f54ffdecb9ead190e11abfc190f40ed0db78ced778b60ea",
        "identity record format changed; bump IDENTITY_FORMAT_VERSION and document the migration"
    );
}
