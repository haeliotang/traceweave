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
