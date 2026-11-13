// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Identity in the canonical state envelope: version migration, revocation enforcement, and
//! recovery that keeps history but drops authority.

use p256::ecdsa::signature::Signer;
use p256::ecdsa::SigningKey;
use p256::pkcs8::EncodePublicKey;
use std::collections::BTreeMap;
use traceweave_portable::{
    recover_state, Clock, DeviceEnrollment, DeviceRevocation, IdentityBook, StateError,
    SubstrateState, Version, LEGACY_STATE_FORMAT_VERSION, STATE_FORMAT_VERSION,
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
        version: traceweave_portable::IDENTITY_FORMAT_VERSION,
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
        version: traceweave_portable::IDENTITY_FORMAT_VERSION,
        device_id: device_id.to_string(),
        revoked_at: at,
        reason: "lost phone".to_string(),
        signer_pubkey: spki(root),
        signature: Vec::new(),
    };
    record.signature = sign(root, &record.signing_bytes().unwrap());
    record
}

fn version(actor: &str, counter: u64, value: &str) -> Version {
    Version {
        clock: Clock(BTreeMap::from([(actor.to_string(), counter)])),
        value: Some(value.to_string()),
    }
}

fn state_with_identity() -> (SubstrateState, SigningKey, SigningKey) {
    let root = key(4);
    let phone = key(7);
    let mut book = IdentityBook::genesis(spki(&root), 0).unwrap();
    book.enroll(enrollment(&root, &phone, "phone-1", 10)).unwrap();

    let mut state = SubstrateState::new();
    state.set_identity(book);
    state
        .merge_memory_from_device("phone-1", 11, "mem-1", &[version("phone-1", 1, "holiday plan")])
        .unwrap();
    (state, root, phone)
}

#[test]
fn version_one_envelope_is_upgraded_to_version_two() {
    // Build a canonical v2 envelope without identity, then present it as a v1 envelope.
    let state = SubstrateState::new();
    let v2_bytes = state.canonical_bytes().unwrap();
    let mut json: serde_json::Value = serde_json::from_slice(&v2_bytes).unwrap();
    json["version"] = serde_json::json!(LEGACY_STATE_FORMAT_VERSION);
    let v1_bytes = serde_json::to_vec(&json).unwrap();

    // Canonical-only readers reject old bytes explicitly.
    assert_eq!(
        SubstrateState::from_canonical_bytes(&v1_bytes),
        Err(StateError::UnsupportedVersion(LEGACY_STATE_FORMAT_VERSION))
    );

    // Migration-aware readers upgrade in memory and re-encode as v2.
    let migrated = SubstrateState::from_bytes(&v1_bytes).unwrap();
    assert_eq!(migrated.version, STATE_FORMAT_VERSION);
    assert!(migrated.identity.is_none());
    assert_eq!(migrated.canonical_bytes().unwrap(), v2_bytes);
}

#[test]
fn revoked_device_records_are_rejected_after_revocation() {
    let (mut state, root, _phone) = state_with_identity();

    // Before revocation the device contributes normally.
    state
        .merge_memory_from_device("phone-1", 12, "mem-2", &[version("phone-1", 2, "note")])
        .unwrap();

    state
        .identity
        .as_mut()
        .unwrap()
        .revoke(revocation(&root, "phone-1", 20))
        .unwrap();

    // A record signed after revocation is refused...
    assert_eq!(
        state.merge_memory_from_device("phone-1", 21, "mem-3", &[version("phone-1", 3, "late edit")]),
        Err(StateError::UntrustedDevice("phone-1".into()))
    );
    // ...but a record it signed before revocation is still historical data.
    state
        .merge_memory_from_device("phone-1", 19, "mem-4", &[version("phone-1", 3, "early edit")])
        .unwrap();
    // Unknown devices are refused too.
    assert_eq!(
        state.merge_memory_from_device("unknown", 0, "mem-5", &[version("x", 1, "data")]),
        Err(StateError::UntrustedDevice("unknown".into()))
    );
}

#[test]
fn identity_book_travels_in_the_canonical_envelope() {
    let (state, _root, _phone) = state_with_identity();
    let bytes = state.canonical_bytes().unwrap();
    let restored = SubstrateState::from_canonical_bytes(&bytes).unwrap();
    assert_eq!(restored.identity, state.identity);
    assert_eq!(restored.canonical_bytes().unwrap(), bytes);
    assert!(!String::from_utf8(bytes).unwrap().is_empty());
}

#[test]
fn recovery_keeps_identity_and_records_but_drops_authority() {
    let (mut state, _root, _phone) = state_with_identity();
    state.add_grant(traceweave_portable::Grant {
        agent_id: "agent-a".into(),
        task_id: "task-1".into(),
        capability: "calendar".into(),
        scope: "write".into(),
        expires_at: 1_000,
        revoked: false,
    });
    let identity_before = state.identity.clone();

    let restored = recover_state(&state);
    assert_eq!(restored.identity, identity_before, "trust history must survive");
    assert_eq!(restored.memories, state.memories, "memory records must survive");
    assert!(restored.grants.is_empty(), "grants must not survive recovery");
    assert!(restored.tasks.values().all(|task| task.needs_authorization));
}
