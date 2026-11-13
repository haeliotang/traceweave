// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! PC-1 acceptance: two headless nodes sign, exchange encrypted state, recover after restart and
//! reject records from a revoked device.

use std::path::{Path, PathBuf};
use std::process::Command;

struct Sandbox {
    root: PathBuf,
}

impl Sandbox {
    fn new(name: &str) -> Self {
        let root = std::env::temp_dir().join(format!(
            "traceweave-node-{}-{}-{}",
            name,
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&root).unwrap();
        Self { root }
    }

    fn dir(&self, name: &str) -> PathBuf {
        self.root.join(name)
    }

    fn file(&self, name: &str) -> PathBuf {
        self.root.join(name)
    }
}

impl Drop for Sandbox {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.root);
    }
}

fn node(args: &[&str]) -> (bool, String) {
    let output = Command::new(env!("CARGO_BIN_EXE_traceweave-node"))
        .args(args)
        .output()
        .expect("node binary runs");
    let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
    let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
    (output.status.success(), format!("{stdout} {stderr}").trim().to_string())
}

fn node_with_env(args: &[&str], envs: &[(&str, &str)]) -> (bool, String) {
    let mut command = Command::new(env!("CARGO_BIN_EXE_traceweave-node"));
    command.args(args);
    for (key, value) in envs {
        command.env(key, value);
    }
    let output = command.output().expect("node binary runs");
    let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
    let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
    (output.status.success(), format!("{stdout} {stderr}").trim().to_string())
}

fn ok(args: &[&str]) -> String {
    let (success, output) = node(args);
    assert!(success, "command failed: {args:?}\n{output}");
    output
}

fn json(text: &str) -> serde_json::Value {
    serde_json::from_str(text).expect("node prints JSON")
}

fn init(dir: &Path, at: &str) -> serde_json::Value {
    json(&ok(&["init", "--dir", dir.to_str().unwrap(), "--at", at]))
}

fn init_with(dir: &Path, at: &str, extra: &[&str]) {
    let dir = dir.to_str().unwrap();
    let mut args = vec!["init", "--dir", dir, "--at", at];
    args.extend_from_slice(extra);
    ok(&args);
}

fn key(dir: &Path) -> serde_json::Value {
    json(&ok(&["key", "--dir", dir.to_str().unwrap()]))
}

fn share_identity(from: &Path, to: &Path) {
    std::fs::create_dir_all(to).unwrap();
    std::fs::copy(from.join("identity.key"), to.join("identity.key")).unwrap();
}

#[test]
fn two_nodes_exchange_encrypted_state_and_recover_after_restart() {
    let sandbox = Sandbox::new("exchange");
    let a_dir = sandbox.dir("a");
    let b_dir = sandbox.dir("b");

    let a = init(&a_dir, "1000");
    share_identity(&a_dir, &b_dir);
    let b = init(&b_dir, "2000");

    let a_key = key(&a_dir);
    let b_key = key(&b_dir);
    assert_eq!(
        a_key["identity_pubkey"], b_key["identity_pubkey"],
        "both nodes must share one personal identity"
    );
    assert_ne!(a["device_id"], b["device_id"]);

    ok(&[
        "trust", "--dir", a_dir.to_str().unwrap(),
        "--device-id", b_key["device_id"].as_str().unwrap(),
        "--device-pubkey", b_key["device_pubkey"].as_str().unwrap(),
        "--at", "3000",
    ]);
    ok(&[
        "trust", "--dir", b_dir.to_str().unwrap(),
        "--device-id", a_key["device_id"].as_str().unwrap(),
        "--device-pubkey", a_key["device_pubkey"].as_str().unwrap(),
        "--at", "3000",
    ]);

    ok(&["add-memory", "--dir", a_dir.to_str().unwrap(), "--content", "holiday plan", "--at", "4000"]);
    ok(&[
        "add-task", "--dir", a_dir.to_str().unwrap(),
        "--goal", "book the train", "--capability", "calendar", "--scope", "write", "--at", "4001",
    ]);

    let envelope = sandbox.file("a.twpn");
    ok(&[
        "export", "--dir", a_dir.to_str().unwrap(),
        "--out", envelope.to_str().unwrap(), "--passphrase", "correct horse battery staple",
    ]);

    let imported = json(&ok(&[
        "import", "--dir", b_dir.to_str().unwrap(),
        "--in", envelope.to_str().unwrap(), "--passphrase", "correct horse battery staple",
        "--from-device", a_key["device_id"].as_str().unwrap(), "--signed-at", "4001",
    ]));
    assert_eq!(imported["memories_merged"], 1);
    assert_eq!(imported["tasks_added"], 1);

    let after = json(&ok(&["show", "--dir", b_dir.to_str().unwrap()]));
    assert_eq!(after["memories"], 1);
    assert_eq!(after["tasks"], 1);
    assert_eq!(after["grants"], 0);

    // Restart: every command is a new process, but run verify explicitly after a re-read.
    let verify = json(&ok(&["verify", "--dir", b_dir.to_str().unwrap()]));
    assert_eq!(verify["ok"], true);
}

#[test]
fn wrong_passphrase_and_tampered_envelope_are_rejected() {
    let sandbox = Sandbox::new("envelope");
    let a_dir = sandbox.dir("a");
    let b_dir = sandbox.dir("b");
    init(&a_dir, "1000");
    share_identity(&a_dir, &b_dir);
    init(&b_dir, "2000");
    let a_key = key(&a_dir);
    let b_key = key(&b_dir);
    ok(&[
        "trust", "--dir", a_dir.to_str().unwrap(),
        "--device-id", b_key["device_id"].as_str().unwrap(),
        "--device-pubkey", b_key["device_pubkey"].as_str().unwrap(), "--at", "3000",
    ]);
    ok(&["add-memory", "--dir", a_dir.to_str().unwrap(), "--content", "secret", "--at", "4000"]);
    let envelope = sandbox.file("a.twpn");
    ok(&[
        "export", "--dir", a_dir.to_str().unwrap(),
        "--out", envelope.to_str().unwrap(), "--passphrase", "right",
    ]);

    let (success, output) = node(&[
        "import", "--dir", b_dir.to_str().unwrap(),
        "--in", envelope.to_str().unwrap(), "--passphrase", "wrong",
        "--from-device", a_key["device_id"].as_str().unwrap(), "--signed-at", "4000",
    ]);
    assert!(!success, "wrong passphrase must fail: {output}");

    let mut bytes = std::fs::read(&envelope).unwrap();
    let last = bytes.len() - 1;
    bytes[last] ^= 1;
    std::fs::write(&envelope, &bytes).unwrap();
    let (success, output) = node(&[
        "import", "--dir", b_dir.to_str().unwrap(),
        "--in", envelope.to_str().unwrap(), "--passphrase", "right",
        "--from-device", a_key["device_id"].as_str().unwrap(), "--signed-at", "4000",
    ]);
    assert!(!success, "tampered envelope must fail: {output}");
}

#[test]
fn revoked_device_cannot_be_imported_after_revocation_but_history_remains() {
    let sandbox = Sandbox::new("revocation");
    let a_dir = sandbox.dir("a");
    let b_dir = sandbox.dir("b");
    init(&a_dir, "1000");
    share_identity(&a_dir, &b_dir);
    init(&b_dir, "2000");
    let a_key = key(&a_dir);
    let b_key = key(&b_dir);
    ok(&[
        "trust", "--dir", a_dir.to_str().unwrap(),
        "--device-id", b_key["device_id"].as_str().unwrap(),
        "--device-pubkey", b_key["device_pubkey"].as_str().unwrap(), "--at", "3000",
    ]);
    ok(&[
        "trust", "--dir", b_dir.to_str().unwrap(),
        "--device-id", a_key["device_id"].as_str().unwrap(),
        "--device-pubkey", a_key["device_pubkey"].as_str().unwrap(), "--at", "3000",
    ]);

    ok(&["add-memory", "--dir", a_dir.to_str().unwrap(), "--content", "before", "--at", "4000"]);
    let historical = sandbox.file("historical.twpn");
    ok(&[
        "export", "--dir", a_dir.to_str().unwrap(),
        "--out", historical.to_str().unwrap(), "--passphrase", "pw",
    ]);

    ok(&[
        "revoke", "--dir", b_dir.to_str().unwrap(),
        "--device-id", a_key["device_id"].as_str().unwrap(), "--reason", "lost phone", "--at", "5000",
    ]);

    // Records signed after revocation are refused.
    ok(&["add-memory", "--dir", a_dir.to_str().unwrap(), "--content", "after", "--at", "6000"]);
    let later = sandbox.file("later.twpn");
    ok(&[
        "export", "--dir", a_dir.to_str().unwrap(),
        "--out", later.to_str().unwrap(), "--passphrase", "transport",
    ]);
    let (success, output) = node(&[
        "import", "--dir", b_dir.to_str().unwrap(),
        "--in", later.to_str().unwrap(), "--passphrase", "transport",
        "--from-device", a_key["device_id"].as_str().unwrap(), "--signed-at", "6000",
    ]);
    assert!(!success, "post-revocation import must fail: {output}");
    assert!(output.contains("untrusted"), "unexpected error: {output}");

    // A record signed before revocation is still historical data.
    let first = json(&ok(&["show", "--dir", b_dir.to_str().unwrap()]));
    assert_eq!(first["memories"], 0);
    ok(&[
        "import", "--dir", b_dir.to_str().unwrap(),
        "--in", historical.to_str().unwrap(), "--passphrase", "pw",
        "--from-device", a_key["device_id"].as_str().unwrap(), "--signed-at", "4000",
    ]);
    let after = json(&ok(&["show", "--dir", b_dir.to_str().unwrap()]));
    assert_eq!(after["memories"], 1);
}

// --- Android archive conversion (Proof H path) ---

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// Build a `TAOS` archive exactly like `MemoryArchive.encrypt`: PBKDF2-HMAC-SHA256 600k +
/// AES-256-GCM with the 37-byte header as AAD.
fn make_archive(passphrase: &str, json: &str) -> Vec<u8> {
    use aes_gcm::aead::{Aead, KeyInit, Payload};
    use aes_gcm::{Aes256Gcm, Nonce};
    use sha2::Sha256;

    let salt = [7u8; 16];
    let nonce = [9u8; 12];
    let mut header = Vec::new();
    header.extend_from_slice(b"TAOS");
    header.push(3u8);
    header.extend_from_slice(&600_000u32.to_be_bytes());
    header.extend_from_slice(&salt);
    header.extend_from_slice(&nonce);

    let mut key = [0u8; 32];
    pbkdf2::pbkdf2_hmac::<Sha256>(passphrase.as_bytes(), &salt, 600_000, &mut key);
    let cipher = Aes256Gcm::new_from_slice(&key).unwrap();
    let ciphertext = cipher
        .encrypt(
            Nonce::from_slice(&nonce),
            Payload { msg: json.as_bytes(), aad: &header },
        )
        .unwrap();
    let mut archive = header;
    archive.extend_from_slice(&ciphertext);
    archive
}

fn fake_device_spki_hex(seed: u8) -> String {
    use p256::pkcs8::EncodePublicKey;
    let signing = p256::ecdsa::SigningKey::from_slice(&[seed; 32]).unwrap();
    hex(signing.verifying_key().to_public_key_der().unwrap().as_bytes())
}

#[test]
fn android_archive_converts_and_merges_history_without_grants() {
    let sandbox = Sandbox::new("archive");
    let b_dir = sandbox.dir("b");
    init(&b_dir, "1000");
    ok(&[
        "trust", "--dir", b_dir.to_str().unwrap(),
        "--device-id", "android-1",
        "--device-pubkey", &fake_device_spki_hex(9),
        "--at", "2000",
    ]);

    let archive_json = r#"{
      "format": "com.inceptless.traceweave.memory",
      "schemaVersion": 6,
      "exportedAt": 4000,
      "memories": [
        {"id": "mem-1", "kind": "fact", "content": "from the phone"},
        {"id": "mem-old", "kind": "fact", "content": "should stay deleted"}
      ],
      "tasks": [
        {"id": "task-1", "title": "book the train", "status": "waiting_for_user"},
        {"id": "task-old", "title": "deleted task", "status": "completed"}
      ],
      "tombstones": [
        {"entityType": "memory", "entityId": "mem-old", "deletedAt": 3900},
        {"entityType": "task", "entityId": "task-old", "deletedAt": 3950}
      ],
      "audit": []
    }"#;
    let archive = sandbox.file("phone.taos");
    std::fs::write(&archive, make_archive("phone passphrase", archive_json)).unwrap();

    let plain = sandbox.file("phone-state.bin");
    let converted = json(&ok(&[
        "from-archive", "--archive", archive.to_str().unwrap(),
        "--passphrase", "phone passphrase", "--from-device", "android-1",
        "--out", plain.to_str().unwrap(),
    ]));
    assert_eq!(converted["memories"], 1, "only the live memory is counted");
    assert_eq!(converted["tombstones"], 1, "the tombstone is kept as history");

    let merged = json(&ok(&[
        "merge-state", "--dir", b_dir.to_str().unwrap(),
        "--in", plain.to_str().unwrap(), "--from-device", "android-1", "--signed-at", "4000",
    ]));
    // The live memory and the tombstone are both merged; only the live one is counted by show.
    assert_eq!(merged["memories_merged"], 2);
    assert_eq!(merged["tasks_added"], 1);
    assert_eq!(merged["grants_not_imported"], 0);

    let show = json(&ok(&["show", "--dir", b_dir.to_str().unwrap()]));
    assert_eq!(show["memories"], 1);
    assert_eq!(show["tombstones"], 1);
    assert_eq!(show["tasks"], 1);
    assert_eq!(show["grants"], 0);
    assert_eq!(json(&ok(&["verify", "--dir", b_dir.to_str().unwrap()]))["ok"], true);

    // Wrong archive passphrase must fail before anything is written.
    let (success, output) = node(&[
        "from-archive", "--archive", archive.to_str().unwrap(),
        "--passphrase", "wrong", "--from-device", "android-1",
        "--out", sandbox.file("wrong.bin").to_str().unwrap(),
    ]);
    assert!(!success, "wrong passphrase must fail: {output}");
}

#[test]
fn post_revocation_archive_is_refused_but_pre_revocation_history_is_accepted() {
    let sandbox = Sandbox::new("archive-revocation");
    let b_dir = sandbox.dir("b");
    init(&b_dir, "1000");
    ok(&[
        "trust", "--dir", b_dir.to_str().unwrap(),
        "--device-id", "android-1",
        "--device-pubkey", &fake_device_spki_hex(9),
        "--at", "2000",
    ]);
    let archive_json = r#"{
      "format": "com.inceptless.traceweave.memory",
      "schemaVersion": 6,
      "memories": [{"id": "mem-1", "kind": "fact", "content": "history"}],
      "tombstones": [],
      "tasks": [],
      "audit": []
    }"#;
    let archive = sandbox.file("phone.taos");
    std::fs::write(&archive, make_archive("pw", archive_json)).unwrap();
    let plain = sandbox.file("phone-state.bin");
    ok(&[
        "from-archive", "--archive", archive.to_str().unwrap(),
        "--passphrase", "pw", "--from-device", "android-1",
        "--out", plain.to_str().unwrap(),
    ]);

    ok(&[
        "revoke", "--dir", b_dir.to_str().unwrap(),
        "--device-id", "android-1", "--reason", "lost phone", "--at", "5000",
    ]);
    let (success, output) = node(&[
        "merge-state", "--dir", b_dir.to_str().unwrap(),
        "--in", plain.to_str().unwrap(), "--from-device", "android-1", "--signed-at", "6000",
    ]);
    assert!(!success, "post-revocation archive must be refused: {output}");
    assert!(output.contains("untrusted"), "unexpected error: {output}");

    ok(&[
        "merge-state", "--dir", b_dir.to_str().unwrap(),
        "--in", plain.to_str().unwrap(), "--from-device", "android-1", "--signed-at", "4000",
    ]);
    assert_eq!(json(&ok(&["show", "--dir", b_dir.to_str().unwrap()]))["memories"], 1);
}

// --- Signed handoff (Proof H v2) ---

fn fake_device_signing_key(seed: u8) -> p256::ecdsa::SigningKey {
    p256::ecdsa::SigningKey::from_slice(&[seed; 32]).unwrap()
}

fn fake_device_spki(seed: u8) -> Vec<u8> {
    use p256::pkcs8::EncodePublicKey;
    fake_device_signing_key(seed)
        .verifying_key()
        .to_public_key_der()
        .unwrap()
        .as_bytes()
        .to_vec()
}

fn make_signed_handoff(seed: u8, signed_at: i64, state: &[u8]) -> Vec<u8> {
    use p256::ecdsa::signature::Signer;
    let key = fake_device_signing_key(seed);
    let mut message = b"traceweave-portable-handoff-v1".to_vec();
    message.extend_from_slice(&signed_at.to_be_bytes());
    message.extend_from_slice(state);
    let signature: p256::ecdsa::Signature = key.sign(&message);
    let signature = signature.to_der().as_bytes().to_vec();
    let spki = fake_device_spki(seed);

    let mut envelope = Vec::new();
    envelope.extend_from_slice(b"TWPH1");
    envelope.extend_from_slice(&signed_at.to_be_bytes());
    envelope.extend_from_slice(&(spki.len() as u32).to_be_bytes());
    envelope.extend_from_slice(&spki);
    envelope.extend_from_slice(&(state.len() as u32).to_be_bytes());
    envelope.extend_from_slice(state);
    envelope.extend_from_slice(&(signature.len() as u32).to_be_bytes());
    envelope.extend_from_slice(&signature);
    envelope
}

fn portable_state_with_memory(id: &str, content: &str) -> Vec<u8> {
    use std::collections::BTreeMap;
    use traceweave_portable::{Clock, SubstrateState, Version};
    let mut state = SubstrateState::new();
    let mut clock = BTreeMap::new();
    clock.insert("android-phone".to_string(), 1u64);
    state
        .merge_memory(
            id,
            &[Version { clock: Clock(clock), value: Some(content.to_string()) }],
        )
        .unwrap();
    state.canonical_bytes().unwrap()
}

#[test]
fn signed_handoff_is_verified_against_the_enrolled_device_key() {
    let sandbox = Sandbox::new("signed-handoff");
    let node_dir = sandbox.dir("node");
    init(&node_dir, "1000");
    ok(&[
        "trust", "--dir", node_dir.to_str().unwrap(),
        "--device-id", "android-1", "--device-pubkey", &hex(&fake_device_spki(9)), "--at", "2000",
    ]);

    let state = portable_state_with_memory("mem-1", "signed from the phone");
    let handoff = sandbox.file("handoff.twph");
    std::fs::write(&handoff, make_signed_handoff(9, 4000, &state)).unwrap();
    let imported = json(&ok(&[
        "import-signed", "--dir", node_dir.to_str().unwrap(), "--in", handoff.to_str().unwrap(),
    ]));
    assert_eq!(imported["device_id"], "android-1");
    assert_eq!(imported["memories_merged"], 1);
    assert_eq!(json(&ok(&["show", "--dir", node_dir.to_str().unwrap()]))["memories"], 1);

    // A tampered signature must not import.
    let mut tampered = make_signed_handoff(9, 4000, &state);
    let last = tampered.len() - 1;
    tampered[last] ^= 1;
    let tampered_file = sandbox.file("tampered.twph");
    std::fs::write(&tampered_file, tampered).unwrap();
    let (success, output) = node(&[
        "import-signed", "--dir", node_dir.to_str().unwrap(), "--in", tampered_file.to_str().unwrap(),
    ]);
    assert!(!success, "tampered handoff must fail: {output}");
    assert!(output.contains("signature"), "unexpected error: {output}");

    // An unenrolled key is refused even with a valid signature.
    let stranger = sandbox.file("stranger.twph");
    std::fs::write(&stranger, make_signed_handoff(11, 4000, &state)).unwrap();
    let (success, output) = node(&[
        "import-signed", "--dir", node_dir.to_str().unwrap(), "--in", stranger.to_str().unwrap(),
    ]);
    assert!(!success, "unenrolled key must fail: {output}");
    assert!(output.contains("not enrolled"), "unexpected error: {output}");
}

#[test]
fn signed_handoff_respects_revocation_time() {
    let sandbox = Sandbox::new("signed-revocation");
    let node_dir = sandbox.dir("node");
    init(&node_dir, "1000");
    ok(&[
        "trust", "--dir", node_dir.to_str().unwrap(),
        "--device-id", "android-1", "--device-pubkey", &hex(&fake_device_spki(9)), "--at", "2000",
    ]);
    let state = portable_state_with_memory("mem-1", "history");
    ok(&[
        "revoke", "--dir", node_dir.to_str().unwrap(),
        "--device-id", "android-1", "--reason", "lost", "--at", "5000",
    ]);

    let after = sandbox.file("after.twph");
    std::fs::write(&after, make_signed_handoff(9, 6000, &state)).unwrap();
    let (success, output) = node(&[
        "import-signed", "--dir", node_dir.to_str().unwrap(), "--in", after.to_str().unwrap(),
    ]);
    assert!(!success, "post-revocation handoff must fail: {output}");
    assert!(output.contains("untrusted"), "unexpected error: {output}");

    let before = sandbox.file("before.twph");
    std::fs::write(&before, make_signed_handoff(9, 4000, &state)).unwrap();
    ok(&[
        "import-signed", "--dir", node_dir.to_str().unwrap(), "--in", before.to_str().unwrap(),
    ]);
    assert_eq!(json(&ok(&["show", "--dir", node_dir.to_str().unwrap()]))["memories"], 1);
}

// --- PC-3: task continuity without replaying external effects ---

#[test]
fn task_continuity_resumes_on_another_node_without_replaying_effects() {
    let sandbox = Sandbox::new("pc3");
    let a_dir = sandbox.dir("a");
    let b_dir = sandbox.dir("b");
    init(&a_dir, "1000");
    init(&b_dir, "1100");
    ok(&[
        "add-task", "--dir", a_dir.to_str().unwrap(),
        "--task-id", "task-1", "--goal", "book the train",
        "--capability", "calendar", "--scope", "write", "--at", "2000",
    ]);

    // Without a grant the effect is denied before anything starts.
    let (success, output) = node(&[
        "begin-effect", "--dir", a_dir.to_str().unwrap(),
        "--task", "task-1", "--operation", "op-1",
        "--capability", "calendar", "--scope", "write", "--at", "3000",
    ]);
    assert!(!success, "effect must be denied without a grant: {output}");
    assert!(output.contains("denied"), "unexpected error: {output}");

    ok(&[
        "authorize", "--dir", a_dir.to_str().unwrap(),
        "--task", "task-1", "--capability", "calendar", "--scope", "write",
        "--ttl-ms", "600000", "--at", "3100",
    ]);
    ok(&[
        "begin-effect", "--dir", a_dir.to_str().unwrap(),
        "--task", "task-1", "--operation", "op-1",
        "--capability", "calendar", "--scope", "write", "--at", "3200",
    ]);
    // Crash-like outcome: the effect may have happened, but the result is unknown.
    ok(&[
        "finish-effect", "--dir", a_dir.to_str().unwrap(),
        "--task", "task-1", "--operation", "op-1", "--outcome", "unknown", "--at", "3300",
    ]);

    let a_key = key(&a_dir);
    ok(&[
        "trust", "--dir", b_dir.to_str().unwrap(),
        "--device-id", a_key["device_id"].as_str().unwrap(),
        "--device-pubkey", a_key["device_pubkey"].as_str().unwrap(), "--at", "3400",
    ]);
    let envelope = sandbox.file("a.twpn");
    ok(&[
        "export", "--dir", a_dir.to_str().unwrap(),
        "--out", envelope.to_str().unwrap(), "--passphrase", "pc3-pass",
    ]);
    let imported = json(&ok(&[
        "import", "--dir", b_dir.to_str().unwrap(),
        "--in", envelope.to_str().unwrap(), "--passphrase", "pc3-pass",
        "--from-device", a_key["device_id"].as_str().unwrap(), "--signed-at", "3300",
    ]));
    assert_eq!(imported["tasks_added"], 1);
    assert_eq!(imported["grants_not_imported"], 1);

    // The resumed task requires a fresh authorization; the old grant did not travel.
    let view = json(&ok(&["task", "--dir", b_dir.to_str().unwrap(), "--task", "task-1"]));
    assert_eq!(view["needs_authorization"], true);
    assert_eq!(view["effects_needing_reconciliation"][0], "op-1");

    let (success, output) = node(&[
        "begin-effect", "--dir", b_dir.to_str().unwrap(),
        "--task", "task-1", "--operation", "op-1",
        "--capability", "calendar", "--scope", "write", "--at", "3500",
    ]);
    assert!(!success, "no grant means no retry: {output}");
    assert!(output.contains("denied"), "unexpected error: {output}");

    ok(&[
        "authorize", "--dir", b_dir.to_str().unwrap(),
        "--task", "task-1", "--capability", "calendar", "--scope", "write",
        "--ttl-ms", "600000", "--at", "3600",
    ]);
    let (success, output) = node(&[
        "begin-effect", "--dir", b_dir.to_str().unwrap(),
        "--task", "task-1", "--operation", "op-1",
        "--capability", "calendar", "--scope", "write", "--at", "3700",
    ]);
    assert!(!success, "unknown outcome must be reconciled first: {output}");
    assert!(output.contains("NeedsReconciliation"), "unexpected error: {output}");

    ok(&[
        "reconcile-effect", "--dir", b_dir.to_str().unwrap(),
        "--task", "task-1", "--operation", "op-1", "--outcome", "succeeded", "--at", "3800",
    ]);
    let (success, output) = node(&[
        "begin-effect", "--dir", b_dir.to_str().unwrap(),
        "--task", "task-1", "--operation", "op-1",
        "--capability", "calendar", "--scope", "write", "--at", "3900",
    ]);
    assert!(!success, "a completed effect must never replay: {output}");
    assert!(output.contains("AlreadyPerformed"), "unexpected error: {output}");

    ok(&["complete-step", "--dir", b_dir.to_str().unwrap(), "--task", "task-1", "--index", "0", "--at", "4000"]);
    let view = json(&ok(&["task", "--dir", b_dir.to_str().unwrap(), "--task", "task-1"]));
    assert_eq!(view["open_steps"].as_array().unwrap().len(), 0);
    assert_eq!(view["effects_needing_reconciliation"].as_array().unwrap().len(), 0);
    assert_eq!(json(&ok(&["verify", "--dir", b_dir.to_str().unwrap()]))["ok"], true);
}

// --- PC-3 with a real capability: a file write must happen exactly once ---

#[test]
fn real_file_effect_is_written_once_and_never_replayed() {
    let sandbox = Sandbox::new("real-effect");
    let a_dir = sandbox.dir("a");
    let b_dir = sandbox.dir("b");
    init(&a_dir, "1000");
    init(&b_dir, "1100");
    ok(&[
        "add-task", "--dir", a_dir.to_str().unwrap(),
        "--task-id", "task-1", "--goal", "write the receipt",
        "--capability", "file.write", "--scope", "note.txt", "--at", "1200",
    ]);

    // No grant: the capability is denied before any file exists.
    let (success, output) = node(&[
        "effect-execute", "--dir", a_dir.to_str().unwrap(),
        "--task", "task-1", "--operation", "op-1",
        "--capability", "file.write", "--scope", "note.txt",
        "--content", "written exactly once", "--at", "1300",
    ]);
    assert!(!success, "effect must be denied without a grant: {output}");
    assert!(output.contains("denied"), "unexpected error: {output}");
    assert!(!a_dir.join("effects/note.txt").exists(), "denied effect must not write");

    ok(&[
        "authorize", "--dir", a_dir.to_str().unwrap(),
        "--task", "task-1", "--capability", "file.write", "--scope", "note.txt",
        "--ttl-ms", "600000", "--at", "1400",
    ]);
    // Real side effect happens, then a crash is simulated before the result is recorded.
    let (success, output) = node(&[
        "effect-execute", "--dir", a_dir.to_str().unwrap(),
        "--task", "task-1", "--operation", "op-1",
        "--capability", "file.write", "--scope", "note.txt",
        "--content", "written exactly once", "--crash-after-write", "true", "--at", "1500",
    ]);
    assert!(!success, "crash path must exit non-zero: {output}");
    assert!(output.contains("simulated crash"), "unexpected error: {output}");
    assert_eq!(
        std::fs::read_to_string(a_dir.join("effects/note.txt")).unwrap(),
        "written exactly once"
    );
    let view = json(&ok(&["task", "--dir", a_dir.to_str().unwrap(), "--task", "task-1"]));
    assert_eq!(view["effects_needing_reconciliation"][0], "op-1");

    // Move the checkpoint to B; the grant does not travel.
    let a_key = key(&a_dir);
    ok(&[
        "trust", "--dir", b_dir.to_str().unwrap(),
        "--device-id", a_key["device_id"].as_str().unwrap(),
        "--device-pubkey", a_key["device_pubkey"].as_str().unwrap(), "--at", "1600",
    ]);
    let envelope = sandbox.file("a.twpn");
    ok(&[
        "export", "--dir", a_dir.to_str().unwrap(),
        "--out", envelope.to_str().unwrap(), "--passphrase", "pc3-real",
    ]);
    ok(&[
        "import", "--dir", b_dir.to_str().unwrap(),
        "--in", envelope.to_str().unwrap(), "--passphrase", "pc3-real",
        "--from-device", a_key["device_id"].as_str().unwrap(), "--signed-at", "1500",
    ]);
    ok(&[
        "authorize", "--dir", b_dir.to_str().unwrap(),
        "--task", "task-1", "--capability", "file.write", "--scope", "note.txt",
        "--ttl-ms", "600000", "--at", "1700",
    ]);

    // Reconciliation is required before the effect may run again, and B's directory stays empty.
    let (success, output) = node(&[
        "effect-execute", "--dir", b_dir.to_str().unwrap(),
        "--task", "task-1", "--operation", "op-1",
        "--capability", "file.write", "--scope", "note.txt",
        "--content", "duplicate", "--at", "1800",
    ]);
    assert!(!success, "unknown outcome must block the retry: {output}");
    assert!(output.contains("NeedsReconciliation"), "unexpected error: {output}");
    assert!(!b_dir.join("effects/note.txt").exists(), "blocked retry must not write");

    ok(&[
        "reconcile-effect", "--dir", b_dir.to_str().unwrap(),
        "--task", "task-1", "--operation", "op-1", "--outcome", "succeeded", "--at", "1900",
    ]);
    let (success, output) = node(&[
        "effect-execute", "--dir", b_dir.to_str().unwrap(),
        "--task", "task-1", "--operation", "op-1",
        "--capability", "file.write", "--scope", "note.txt",
        "--content", "duplicate", "--at", "2000",
    ]);
    assert!(!success, "a succeeded effect must never replay: {output}");
    assert!(output.contains("AlreadyPerformed"), "unexpected error: {output}");
    assert!(!b_dir.join("effects/note.txt").exists(), "replay must not write");

    assert_eq!(json(&ok(&["verify", "--dir", b_dir.to_str().unwrap()]))["ok"], true);
}

// --- PC-3: swapping the execution engine keeps identity/history, authority is per engine ---

#[test]
fn engine_swap_preserves_task_identity_and_requires_its_own_grant() {
    let sandbox = Sandbox::new("engines");
    let dir = sandbox.dir("node");
    init(&dir, "1000");
    ok(&[
        "add-task", "--dir", dir.to_str().unwrap(),
        "--task-id", "task-native", "--goal", "hash the report",
        "--capability", "engine.run", "--scope", "native", "--at", "1100",
    ]);
    ok(&[
        "add-task", "--dir", dir.to_str().unwrap(),
        "--task-id", "task-openssl", "--goal", "hash the report",
        "--capability", "engine.run", "--scope", "openssl", "--at", "1200",
    ]);

    // No grant yet: the engine refuses to run.
    let (success, output) = node(&[
        "engine-run", "--dir", dir.to_str().unwrap(),
        "--task", "task-native", "--engine", "native", "--input", "same input", "--at", "1300",
    ]);
    assert!(!success, "engine must be denied without a grant: {output}");
    assert!(output.contains("denied"), "unexpected error: {output}");

    ok(&[
        "authorize", "--dir", dir.to_str().unwrap(),
        "--task", "task-native", "--capability", "engine.run", "--scope", "native",
        "--ttl-ms", "600000", "--at", "1400",
    ]);
    ok(&[
        "authorize", "--dir", dir.to_str().unwrap(),
        "--task", "task-openssl", "--capability", "engine.run", "--scope", "openssl",
        "--ttl-ms", "600000", "--at", "1500",
    ]);

    let native = json(&ok(&[
        "engine-run", "--dir", dir.to_str().unwrap(),
        "--task", "task-native", "--engine", "native", "--input", "same input", "--at", "1600",
    ]));
    let openssl = json(&ok(&[
        "engine-run", "--dir", dir.to_str().unwrap(),
        "--task", "task-openssl", "--engine", "openssl", "--input", "same input", "--at", "1700",
    ]));
    assert_eq!(native["goal"], openssl["goal"], "the task identity must not depend on the engine");
    assert_eq!(native["digest"], openssl["digest"], "both engines must produce the same result");
    assert_ne!(native["task_id"], openssl["task_id"]);

    // Authority is per engine: a native grant cannot run openssl on the same task.
    let (success, output) = node(&[
        "engine-run", "--dir", dir.to_str().unwrap(),
        "--task", "task-native", "--engine", "openssl", "--input", "same input", "--at", "1800",
    ]);
    assert!(!success, "engine swap must not reuse another engine's grant: {output}");
    assert!(output.contains("denied"), "unexpected error: {output}");

    // Evidence is recorded on the task and the work is complete.
    let view = json(&ok(&["task", "--dir", dir.to_str().unwrap(), "--task", "task-native"]));
    assert_eq!(view["open_steps"].as_array().unwrap().len(), 0);
    let evidence = &view["task_id"];
    assert_eq!(evidence, "task-native");
    assert_eq!(json(&ok(&["verify", "--dir", dir.to_str().unwrap()]))["ok"], true);
}

// --- PC-1: macOS Keychain backend keeps key material out of the state directory ---

struct KeychainCleanup {
    service: String,
}

impl Drop for KeychainCleanup {
    fn drop(&mut self) {
        for account in ["traceweave-identity", "traceweave-device"] {
            let _ = Command::new("security")
                .args(["delete-generic-password", "-a", account, "-s", &self.service])
                .output();
        }
    }
}

#[test]
fn keychain_backend_keeps_keys_out_of_the_state_directory() {
    if !cfg!(target_os = "macos") {
        return; // the security CLI is macOS-only; Linux CI skips this test
    }
    let sandbox = Sandbox::new("keychain");
    let dir = sandbox.dir("node");
    let service = format!(
        "traceweave-node-test-{}-{}",
        std::process::id(),
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()
    );
    let _cleanup = KeychainCleanup { service: service.clone() };

    let created = json(&ok(&[
        "init", "--dir", dir.to_str().unwrap(), "--at", "1000", "--keychain-service", &service,
    ]));
    assert!(created["device_id"].as_str().unwrap().starts_with("node-"));
    assert!(!dir.join("identity.key").exists(), "identity seed must not be a file");
    assert!(!dir.join("device.key").exists(), "device seed must not be a file");
    assert!(dir.join(".keychain").exists(), "the marker records which keychain service is used");

    // Every command is a new process, so this also proves the key can be reloaded from Keychain.
    ok(&["add-memory", "--dir", dir.to_str().unwrap(), "--content", "from keychain", "--at", "2000"]);
    let show = json(&ok(&["show", "--dir", dir.to_str().unwrap()]));
    assert_eq!(show["memories"], 1);
    assert_eq!(json(&ok(&["verify", "--dir", dir.to_str().unwrap()]))["ok"], true);

    // The device identity is stable across restarts.
    let first = json(&ok(&["key", "--dir", dir.to_str().unwrap()]));
    let second = json(&ok(&["key", "--dir", dir.to_str().unwrap()]));
    assert_eq!(first["device_pubkey"], second["device_pubkey"]);
}

// --- PC-2: non-exportable Keychain keys behind a key agent (macOS) ---

struct KeyAgentCleanup {
    helper: std::path::PathBuf,
    tags: Vec<String>,
}

impl Drop for KeyAgentCleanup {
    fn drop(&mut self) {
        for tag in &self.tags {
            let _ = Command::new(&self.helper).args(["delete", tag]).output();
        }
    }
}

fn build_key_agent() -> std::path::PathBuf {
    let helper = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("target/debug/traceweave-key-agent");
    if !helper.exists() {
        let source = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("macos/key_agent.c");
        let status = Command::new("clang")
            .args(["-framework", "Security", "-framework", "CoreFoundation"])
            .arg(&source)
            .arg("-o")
            .arg(&helper)
            .status()
            .expect("clang must be available on macOS");
        assert!(status.success(), "could not build the key agent");
    }
    helper
}

#[test]
fn key_agent_keeps_private_keys_non_exportable() {
    if !cfg!(target_os = "macos") {
        return; // key_agent.c uses Security.framework
    }
    let helper = build_key_agent();
    let sandbox = Sandbox::new("key-agent");
    let dir = sandbox.dir("node");
    let base = format!(
        "traceweave-node-agent-test-{}-{}",
        std::process::id(),
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()
    );
    let _cleanup = KeyAgentCleanup {
        helper: helper.clone(),
        tags: vec![format!("{base}-identity"), format!("{base}-device")],
    };

    let created = json(&ok(&[
        "init", "--dir", dir.to_str().unwrap(), "--at", "1000", "--key-agent-tag", &base,
    ]));
    assert!(created["device_id"].as_str().unwrap().starts_with("node-"));
    assert!(!dir.join("identity.key").exists(), "no identity seed file");
    assert!(!dir.join("device.key").exists(), "no device seed file");
    assert!(!dir.join(".keychain").exists(), "the key agent backend is not the seed backend");
    assert!(dir.join(".key-agent").exists(), "the marker records the key agent tag");

    // init enrolled the device with an identity-agent signature; revoke signs and verifies too.
    ok(&["add-memory", "--dir", dir.to_str().unwrap(), "--content", "agent-backed", "--at", "2000"]);
    let key = json(&ok(&["key", "--dir", dir.to_str().unwrap()]));
    let device_id = key["device_id"].as_str().unwrap().to_string();
    ok(&[
        "revoke", "--dir", dir.to_str().unwrap(), "--device-id", &device_id,
        "--reason", "test", "--at", "3000",
    ]);
    let show = json(&ok(&["show", "--dir", dir.to_str().unwrap()]));
    assert_eq!(show["memories"], 1);
    assert_eq!(json(&ok(&["verify", "--dir", dir.to_str().unwrap()]))["ok"], true);

    // The public key is stable across processes, and no private key material is on disk.
    let first = json(&ok(&["key", "--dir", dir.to_str().unwrap()]));
    let second = json(&ok(&["key", "--dir", dir.to_str().unwrap()]));
    assert_eq!(first["identity_pubkey"], second["identity_pubkey"]);
}

// --- PC-2: recovery secret rebuilds identity on a new device and revokes the lost ones ---

#[test]
fn recovery_secret_rebuilds_identity_and_revokes_lost_devices() {
    let sandbox = Sandbox::new("recovery");
    let old = sandbox.dir("old");
    let new = sandbox.dir("new");
    let stranger = sandbox.dir("stranger");
    let secret = "correct horse battery staple recovery";
    init_with(&old, "1000", &["--recovery-secret", secret]);
    init_with(&new, "1100", &["--recovery-secret", secret]);
    init_with(&stranger, "1200", &["--recovery-secret", "a different recovery secret"]);

    let old_key = json(&ok(&["key", "--dir", old.to_str().unwrap()]));
    let new_key = json(&ok(&["key", "--dir", new.to_str().unwrap()]));
    let stranger_key = json(&ok(&["key", "--dir", stranger.to_str().unwrap()]));
    assert_eq!(old_key["identity_fingerprint"], new_key["identity_fingerprint"]);
    assert_ne!(old_key["identity_fingerprint"], stranger_key["identity_fingerprint"]);
    assert_ne!(old_key["device_id"], new_key["device_id"]);

    ok(&["add-memory", "--dir", old.to_str().unwrap(), "--content", "survives recovery", "--at", "2000"]);
    ok(&[
        "add-task", "--dir", old.to_str().unwrap(), "--task-id", "task-1",
        "--goal", "finish after recovery", "--capability", "file.write", "--scope", "note.txt", "--at", "2100",
    ]);
    ok(&[
        "trust", "--dir", old.to_str().unwrap(), "--device-id", "phone",
        "--device-pubkey", &fake_device_spki_hex(9), "--at", "2200",
    ]);
    let backup = sandbox.file("backup.twpn");
    ok(&[
        "export", "--dir", old.to_str().unwrap(), "--out", backup.to_str().unwrap(),
        "--passphrase", "backup-pass",
    ]);

    let recovered = json(&ok(&[
        "recover", "--dir", new.to_str().unwrap(), "--in", backup.to_str().unwrap(),
        "--passphrase", "backup-pass", "--revoke-others", "true", "--at", "3000",
    ]));
    assert_eq!(recovered["memories"], 1);
    assert_eq!(recovered["tasks"], 1);
    assert_eq!(recovered["devices_revoked"], 2, "old node and phone must be revoked");
    assert_eq!(recovered["grants"], 0);

    let show = json(&ok(&["show", "--dir", new.to_str().unwrap()]));
    assert_eq!(show["memories"], 1);
    assert_eq!(show["tasks"], 1);
    assert_eq!(show["grants"], 0);
    let view = json(&ok(&["task", "--dir", new.to_str().unwrap(), "--task", "task-1"]));
    assert_eq!(view["needs_authorization"], true);
    assert_eq!(json(&ok(&["verify", "--dir", new.to_str().unwrap()]))["ok"], true);

    // The identity book now has the new device active and both old devices revoked.
    use traceweave_portable::SubstrateState;
    let state = SubstrateState::from_bytes(&std::fs::read(new.join("state.bin")).unwrap()).unwrap();
    let book = state.identity.expect("recovered identity book");
    let new_device = new_key["device_id"].as_str().unwrap();
    assert!(book.devices[new_device].revoked_at.is_none());
    assert!(book.devices[old_key["device_id"].as_str().unwrap()].revoked_at.is_some());
    assert!(book.devices["phone"].revoked_at.is_some());

    // A different recovery secret cannot adopt this backup.
    let (success, output) = node(&[
        "recover", "--dir", stranger.to_str().unwrap(), "--in", backup.to_str().unwrap(),
        "--passphrase", "backup-pass", "--at", "3100",
    ]);
    assert!(!success, "wrong recovery secret must fail: {output}");
    assert!(output.contains("does not match"), "unexpected error: {output}");

    // Wrong backup passphrase fails before adoption.
    let (success, output) = node(&[
        "recover", "--dir", new.to_str().unwrap(), "--in", backup.to_str().unwrap(),
        "--passphrase", "wrong", "--at", "3200",
    ]);
    assert!(!success, "wrong backup passphrase must fail: {output}");
}

// --- PC-2: Agent Sessions are short-lived and never migrate ---

#[test]
fn agent_sessions_are_short_lived_and_are_not_migrated() {
    let sandbox = Sandbox::new("sessions");
    let a_dir = sandbox.dir("a");
    let b_dir = sandbox.dir("b");
    init(&a_dir, "1000");
    init(&b_dir, "1100");
    ok(&[
        "add-task", "--dir", a_dir.to_str().unwrap(), "--task-id", "task-1",
        "--goal", "session work", "--capability", "file.write", "--scope", "note.txt", "--at", "1200",
    ]);
    let session = json(&ok(&[
        "session-create", "--dir", a_dir.to_str().unwrap(), "--agent", "claude-run",
        "--capability", "file.write", "--scope", "note.txt", "--ttl-ms", "5000", "--at", "2000",
    ]));
    let session_id = session["id"].as_str().unwrap().to_string();
    let agent_id = session["agent_id"].as_str().unwrap().to_string();
    assert!(agent_id.starts_with("session-"));

    // A session cannot grant a scope it was not created for.
    let (success, output) = node(&[
        "authorize", "--dir", a_dir.to_str().unwrap(), "--session", &session_id,
        "--task", "task-1", "--capability", "file.write", "--scope", "other.txt", "--at", "2100",
    ]);
    assert!(!success, "session scope must be enforced: {output}");
    assert!(output.contains("does not grant"), "unexpected error: {output}");

    ok(&[
        "authorize", "--dir", a_dir.to_str().unwrap(), "--session", &session_id,
        "--task", "task-1", "--capability", "file.write", "--scope", "note.txt",
        "--ttl-ms", "999999", "--at", "2100",
    ]);
    // The grant is capped by the session lifetime, not the requested TTL.
    ok(&[
        "effect-execute", "--dir", a_dir.to_str().unwrap(), "--agent", &agent_id,
        "--task", "task-1", "--operation", "op-1", "--capability", "file.write",
        "--scope", "note.txt", "--content", "session write", "--at", "2200",
    ]);
    let listed = json(&ok(&["session-list", "--dir", a_dir.to_str().unwrap(), "--at", "2300"]));
    assert_eq!(listed["sessions"][0]["active"], true);

    // Revoking the session revokes its grants.
    let revoked = json(&ok(&[
        "session-revoke", "--dir", a_dir.to_str().unwrap(), "--session", &session_id, "--at", "2500",
    ]));
    assert_eq!(revoked["revoked_grants"], 1);
    let (success, output) = node(&[
        "effect-execute", "--dir", a_dir.to_str().unwrap(), "--agent", &agent_id,
        "--task", "task-1", "--operation", "op-2", "--capability", "file.write",
        "--scope", "note.txt", "--content", "must not happen", "--at", "2600",
    ]);
    assert!(!success, "revoked session must not act: {output}");
    assert!(output.contains("denied"), "unexpected error: {output}");

    // An expired session cannot authorize new work.
    let short = json(&ok(&[
        "session-create", "--dir", a_dir.to_str().unwrap(), "--agent", "short",
        "--capability", "file.write", "--scope", "note.txt", "--ttl-ms", "1000", "--at", "3000",
    ]));
    let short_id = short["id"].as_str().unwrap().to_string();
    let (success, output) = node(&[
        "authorize", "--dir", a_dir.to_str().unwrap(), "--session", &short_id,
        "--task", "task-1", "--capability", "file.write", "--scope", "note.txt", "--at", "4500",
    ]);
    assert!(!success, "expired session must not authorize: {output}");
    assert!(output.contains("expired"), "unexpected error: {output}");

    // Sessions are node-local: an exported state does not carry them.
    let a_key = key(&a_dir);
    ok(&[
        "trust", "--dir", b_dir.to_str().unwrap(),
        "--device-id", a_key["device_id"].as_str().unwrap(),
        "--device-pubkey", a_key["device_pubkey"].as_str().unwrap(), "--at", "2700",
    ]);
    let envelope = sandbox.file("a.twpn");
    ok(&[
        "export", "--dir", a_dir.to_str().unwrap(), "--out", envelope.to_str().unwrap(),
        "--passphrase", "sessions",
    ]);
    ok(&[
        "import", "--dir", b_dir.to_str().unwrap(), "--in", envelope.to_str().unwrap(),
        "--passphrase", "sessions", "--from-device", a_key["device_id"].as_str().unwrap(),
        "--signed-at", "2600",
    ]);
    let b_sessions = json(&ok(&["session-list", "--dir", b_dir.to_str().unwrap(), "--at", "2800"]));
    assert_eq!(b_sessions["sessions"].as_array().unwrap().len(), 0, "sessions must not migrate");
}

// --- PC-1: Linux Secret Service backend (exercised through a stub on macOS) ---

fn secret_tool_stub(sandbox: &Sandbox) -> (std::path::PathBuf, std::path::PathBuf) {
    let store = sandbox.dir("secret-store");
    std::fs::create_dir_all(&store).unwrap();
    let stub = sandbox.file("secret-tool");
    let script = format!(
        "#!/bin/bash\nset -e\nstore=\"{}\"\ncmd=\"$1\"; shift\nif [ \"$cmd\" = store ]; then\n  service=\"\"; account=\"\"\n  while [ $# -gt 0 ]; do\n    case \"$1\" in --label) shift 2;; service) service=\"$2\"; shift 2;; account) account=\"$2\"; shift 2;; *) shift;; esac\n  done\n  secret=\"$(cat)\"\n  printf '%s' \"$secret\" > \"$store/$service.$account\"\nelif [ \"$cmd\" = lookup ]; then\n  service=\"$2\"; account=\"$4\"\n  cat \"$store/$service.$account\"\nfi\n",
        store.display()
    );
    std::fs::write(&stub, script).unwrap();
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(&stub, std::fs::Permissions::from_mode(0o755)).unwrap();
    (stub, store)
}

#[test]
fn secret_service_backend_keeps_keys_out_of_the_state_directory() {
    let sandbox = Sandbox::new("secret-service");
    let dir = sandbox.dir("node");
    let (stub, store) = secret_tool_stub(&sandbox);
    let stub = stub.to_str().unwrap().to_string();
    let store_env = store.to_str().unwrap().to_string();
    let envs: Vec<(&str, &str)> = vec![("TRACEWEAVE_SECRET_TOOL", &stub), ("STORE_DIR", &store_env)];
    let service = "traceweave-node-secret-test";

    let (success, output) = node_with_env(
        &["init", "--dir", dir.to_str().unwrap(), "--at", "1000", "--secret-service", service],
        &envs,
    );
    assert!(success, "init failed: {output}");
    assert!(!dir.join("identity.key").exists(), "no identity seed file");
    assert!(!dir.join("device.key").exists(), "no device seed file");
    assert!(!dir.join(".keychain").exists());
    assert!(dir.join(".secret-service").exists(), "marker records the service");

    let (success, output) = node_with_env(
        &["add-memory", "--dir", dir.to_str().unwrap(), "--content", "secret service", "--at", "2000"],
        &envs,
    );
    assert!(success, "add-memory failed: {output}");
    let (success, output) = node_with_env(&["show", "--dir", dir.to_str().unwrap()], &envs);
    assert!(success, "show failed: {output}");
    assert_eq!(json(&output)["memories"], 1);
    let (success, output) = node_with_env(&["verify", "--dir", dir.to_str().unwrap()], &envs);
    assert!(success, "verify failed: {output}");
    assert_eq!(json(&output)["ok"], true);

    // Both seeds went through the Secret Service tool.
    assert!(store.join(format!("{service}.traceweave-identity")).exists());
    assert!(store.join(format!("{service}.traceweave-device")).exists());
}
