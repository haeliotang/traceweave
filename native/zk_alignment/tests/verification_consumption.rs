use ark_crypto_primitives::crh::sha256::{digest::Digest, Sha256};
use std::{fs, process::Command};

#[test]
fn packed_public_package_rejects_modified_inputs_and_replay() {
    let root = std::env::temp_dir().join(format!("traceform-packed-proof-{}", std::process::id()));
    let package = root.join("public");
    fs::create_dir(&root).unwrap();
    let setup = Command::new(env!("CARGO_BIN_EXE_fixture")).current_dir(&root)
        .arg("--packed-uniform").arg(&package).output().unwrap();
    assert!(setup.status.success(), "{}", String::from_utf8_lossy(&setup.stderr));
    let inputs = fs::read(package.join("public_inputs.bin")).unwrap();
    assert_eq!(inputs.len(), 224);
    let mut expected = [0u8; 32];
    expected[..16].copy_from_slice(&(1_000_000u128.pow(4) / 8).to_le_bytes());
    assert_eq!(&inputs[..32], &expected);
    let mut names = fs::read_dir(&package).unwrap().map(|entry| entry.unwrap().file_name()).collect::<Vec<_>>();
    names.sort();
    assert_eq!(names, ["proof.bin", "public_inputs.bin", "verifying_key.bin"].map(std::ffi::OsString::from));
    let pin: String = Sha256::digest(fs::read(package.join("verifying_key.bin")).unwrap())
        .iter().map(|byte| format!("{byte:02x}")).collect();
    let ledger = root.join("sessions.bin");
    let verify = || Command::new(env!("CARGO_BIN_EXE_verify")).arg(&package).arg(&pin).arg(&ledger).output().unwrap();
    for offset in [0, 32, 96, 160] {
        let mut changed = inputs.clone(); changed[offset] ^= 1;
        fs::write(package.join("public_inputs.bin"), changed).unwrap();
        assert!(!verify().status.success());
        assert!(!ledger.exists());
    }
    fs::write(package.join("public_inputs.bin"), inputs).unwrap();
    assert!(verify().status.success());
    assert_eq!(fs::metadata(&ledger).unwrap().len(), 32);
    let replay = verify();
    assert!(!replay.status.success());
    assert!(String::from_utf8_lossy(&replay.stderr).contains("replayed session"));
    assert_eq!(fs::metadata(&ledger).unwrap().len(), 32);
    fs::remove_dir_all(root).unwrap();
}

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
