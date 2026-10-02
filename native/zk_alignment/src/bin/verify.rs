//! Verify a bound synthetic statement without private witnesses.
use ark_bls12_381::{Bls12_381, Fr};
use ark_crypto_primitives::crh::sha256::{digest::Digest, Sha256};
use ark_groth16::{prepare_verifying_key, Groth16, Proof, VerifyingKey};
use ark_serialize::CanonicalDeserialize;
use std::{error::Error, fs::{File, OpenOptions}, io::{Read, Seek, SeekFrom, Write}, path::{Path, PathBuf}};

fn lock_ledger(file: &File) -> std::io::Result<()> {
    #[cfg(target_os = "android")]
    {
        use std::os::fd::AsRawFd;
        // The borrowed file keeps the descriptor valid during this syscall.
        if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX) } != 0 {
            return Err(std::io::Error::last_os_error());
        }
        Ok(())
    }
    #[cfg(not(target_os = "android"))]
    file.lock()
}

fn consume_session(path: &Path, session: &[u8]) -> Result<(), Box<dyn Error>> {
    let tag = Sha256::digest(session);
    let mut ledger = OpenOptions::new().create(true).truncate(false).read(true).write(true).open(path)?;
    lock_ledger(&ledger)?;
    let mut prior = Vec::new();
    (&mut ledger).take(131073).read_to_end(&mut prior)?;
    if prior.len() % 32 != 0 || prior.len() > 131072 { return Err("invalid session ledger".into()); }
    if prior.chunks(32).any(|entry| entry == tag.as_slice()) { return Err("replayed session".into()); }
    if prior.len() == 131072 { return Err("session ledger capacity reached".into()); }
    ledger.seek(SeekFrom::End(0))?;
    ledger.write_all(&tag)?;
    ledger.sync_all()?;
    File::open(path.parent().unwrap_or(Path::new(".")))?.sync_all()?;
    // Closing the descriptor releases its exclusive lock, including error paths.
    Ok(())
}

fn read_bounded(path: &Path, maximum: usize) -> Result<Vec<u8>, Box<dyn Error>> {
    let mut bytes = Vec::new();
    File::open(path)?.take((maximum + 1) as u64).read_to_end(&mut bytes)?;
    if bytes.len() > maximum { return Err("public package file exceeds size limit".into()); }
    Ok(bytes)
}

fn trusted_key_matches(bytes: &[u8], expected: &str) -> Result<bool, Box<dyn Error>> {
    if expected.len() != 64 || !expected.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) {
        return Err("trusted key SHA256 must be 64 lowercase hexadecimal characters".into());
    }
    let actual: String = Sha256::digest(bytes).iter().map(|byte| format!("{byte:02x}")).collect();
    Ok(actual == expected)
}

fn main() -> Result<(), Box<dyn Error>> {
    let args: Vec<_> = std::env::args().collect();
    if args.len() != 4 { return Err("usage: verify PUBLIC_PACKAGE TRUSTED_KEY_SHA256 SESSION_LEDGER".into()); }
    let source = PathBuf::from(&args[1]);
    let key_bytes = read_bounded(&source.join("verifying_key.bin"), 4096)?;
    if !trusted_key_matches(&key_bytes, &args[2])? { return Err("untrusted verifying key".into()); }
    let proof_bytes = read_bounded(&source.join("proof.bin"), 192)?;
    let input_bytes = read_bounded(&source.join("public_inputs.bin"), 224)?;
    if key_bytes.len() > 4096 || proof_bytes.len() != 192 || input_bytes.len() != 224 {
        return Err("invalid bound statement package size".into());
    }
    for offset in [32, 96, 160] {
        if input_bytes[offset + 31] != 0 || input_bytes[offset + 33..offset + 64].iter().any(|byte| *byte != 0) {
            return Err("noncanonical packed public bytes".into());
        }
    }
    let mut key_reader = key_bytes.as_slice();
    let key = VerifyingKey::<Bls12_381>::deserialize_compressed(&mut key_reader)?;
    let mut proof_reader = proof_bytes.as_slice();
    let proof = Proof::<Bls12_381>::deserialize_compressed(&mut proof_reader)?;
    let mut input_reader = input_bytes.as_slice();
    let inputs: Vec<Fr> = (0..7).map(|_| Fr::deserialize_compressed(&mut input_reader)).collect::<Result<_, _>>()?;
    if !key_reader.is_empty() || !proof_reader.is_empty() || !input_reader.is_empty() || key.gamma_abc_g1.len() != 8 {
        return Err("invalid bound statement encoding".into());
    }
    if !Groth16::<Bls12_381>::verify_proof(&prepare_verifying_key(&key), &proof, &inputs)? {
        return Err("proof rejected".into());
    }
    let mut session = input_bytes[32..63].to_vec();
    session.push(input_bytes[64]);
    consume_session(Path::new(&args[3]), &session)?;
    println!("{{\"verified\":true,\"session_consumed\":true,\"scope\":\"local_pinned_proof_verification_and_replay_rejection_not_owner_authentication\"}}");
    Ok(())
}

#[cfg(test)]
mod tests {
    use ark_crypto_primitives::crh::sha256::digest::Digest;

    #[test]
    fn ledger_accepts_last_slot_then_rejects_capacity_without_modification() {
        let path = std::env::temp_dir().join(format!("traceform-capacity-{}", std::process::id()));
        let mut records = Vec::new();
        for index in 0..4095u64 {
            records.extend_from_slice(&super::Sha256::digest(index.to_le_bytes()));
        }
        std::fs::write(&path, &records).unwrap();
        super::consume_session(&path, &[7u8; 32]).unwrap();
        let full = std::fs::read(&path).unwrap();
        assert_eq!(full.len(), 131072);
        assert_eq!(super::consume_session(&path, &[8u8; 32]).unwrap_err().to_string(), "session ledger capacity reached");
        assert_eq!(super::consume_session(&path, &[7u8; 32]).unwrap_err().to_string(), "replayed session");
        assert_eq!(std::fs::read(&path).unwrap(), full);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn malformed_and_oversized_ledgers_fail_without_truncation() {
        for length in [1, 31, 33, 131073, 131104] {
            let path = std::env::temp_dir().join(format!("traceform-corrupt-{}-{length}", std::process::id()));
            let contents = vec![9u8; length];
            std::fs::write(&path, &contents).unwrap();
            assert_eq!(super::consume_session(&path, &[7u8; 32]).unwrap_err().to_string(), "invalid session ledger");
            assert_eq!(std::fs::read(&path).unwrap(), contents);
            std::fs::remove_file(path).unwrap();
        }
    }

    #[test]
    fn concurrent_session_consumption_has_one_winner_and_survives_reopen() {
        let path = std::env::temp_dir().join(format!("traceform-replay-{}", std::process::id()));
        let workers: Vec<_> = (0..4).map(|_| {
            let path = path.clone();
            std::thread::spawn(move || super::consume_session(&path, &[7u8; 32]).is_ok())
        }).collect();
        let successes = workers.into_iter().map(|worker| worker.join().unwrap() as usize).sum::<usize>();
        assert_eq!(successes, 1);
        assert!(super::consume_session(&path, &[7u8; 32]).is_err());
        assert_eq!(std::fs::metadata(&path).unwrap().len(), 32);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn key_pin_rejects_replacement_and_invalid_digest() {
        let digest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        assert!(super::trusted_key_matches(b"", digest).unwrap());
        assert!(!super::trusted_key_matches(b"replacement key", digest).unwrap());
        assert!(super::trusted_key_matches(b"", "not a key pin").is_err());
    }
}
