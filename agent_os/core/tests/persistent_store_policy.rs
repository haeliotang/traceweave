// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

use agent_os_core::store::AgentStore;

#[test]
fn production_build_rejects_file_backed_plaintext_store() {
    let path = std::env::temp_dir().join(format!("agent-os-plaintext-{}.sqlite", std::process::id()));
    let _ = std::fs::remove_file(&path);

    assert!(AgentStore::open(path.to_str().unwrap()).is_err());
    assert!(!path.exists());
}
