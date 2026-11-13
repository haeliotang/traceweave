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
      "tombstones": [{"entityType": "memory", "entityId": "mem-old", "deletedAt": 3900}],
      "tasks": [{"id": "task-1", "title": "book the train", "status": "waiting_for_user"}],
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
