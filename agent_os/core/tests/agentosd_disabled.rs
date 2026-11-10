// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

use std::process::Command;

#[test]
fn agentosd_refuses_to_start_until_transport_is_authenticated() {
    let output = Command::new(env!("CARGO_BIN_EXE_agentosd"))
        .arg("--start")
        .output()
        .unwrap();

    assert_eq!(output.status.code(), Some(78));
    assert!(String::from_utf8_lossy(&output.stderr).contains("disabled"));
}
