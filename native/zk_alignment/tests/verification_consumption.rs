use ark_crypto_primitives::crh::sha256::{digest::Digest, Sha256};
use std::{fs, process::Command};

#[test]
fn rejected_proof_does_not_consume_session_before_valid_submission() {
    let root = std::env::temp_dir().join(format!("traceform-invalid-proof-{}", std::process::id()));
    let package = root.join("public");
    fs::create_dir(&root).unwrap();
    let setup = Command::new(env!("CARGO_BIN_EXE_fixture")).arg(&package).output().unwrap();
    assert!(setup.status.success(), "{}", String::from_utf8_lossy(&setup.stderr));
    let pin: String = Sha256::digest(fs::read(package.join("verifying_key.bin")).unwrap())
        .iter().map(|byte| format!("{byte:02x}")).collect();
    let ledger = root.join("sessions.bin");
    let inputs = fs::read(package.join("public_inputs.bin")).unwrap();
    let mut modified = inputs.clone();
    modified[0] ^= 1;
    fs::write(package.join("public_inputs.bin"), modified).unwrap();
    let verify = || Command::new(env!("CARGO_BIN_EXE_verify")).arg(&package).arg(&pin).arg(&ledger).output().unwrap();
    let rejected = verify();
    assert!(!rejected.status.success());
    assert!(String::from_utf8_lossy(&rejected.stderr).contains("proof rejected"));
    assert!(!ledger.exists());
    fs::write(package.join("public_inputs.bin"), inputs).unwrap();
    assert!(verify().status.success());
    let accepted = fs::read(&ledger).unwrap();
    assert_eq!(accepted.len(), 32);
    let replay = verify();
    assert!(!replay.status.success());
    assert!(String::from_utf8_lossy(&replay.stderr).contains("replayed session"));
    assert_eq!(fs::read(&ledger).unwrap(), accepted);
    fs::remove_dir_all(root).unwrap();
}
