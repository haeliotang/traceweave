//! Synthetic proof package generator; never reads device models.
use ark_bls12_381::{Bls12_381, Fr};
use ark_groth16::Groth16;
use ark_serialize::CanonicalSerialize;
use rand::{rngs::OsRng, RngCore};
use std::{error::Error, fs, path::PathBuf};
use traceform_zk_alignment::{model_commitment, AlignmentBindings, AlignmentCircuit, SCALE};

fn main() -> Result<(), Box<dyn Error>> {
    let destination = PathBuf::from(std::env::args().nth(1).ok_or("output directory required")?);
    let private_destination = std::env::args().nth(2).map(PathBuf::from);
    fs::create_dir(&destination)?;
    if let Some(path) = &private_destination {
        fs::create_dir(path)?;
        #[cfg(unix)] {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
        }
        if path.canonicalize()?.starts_with(destination.canonicalize()?)
            || destination.canonicalize()?.starts_with(path.canonicalize()?) {
            return Err("public and private fixture directories must be separate".into());
        }
    }
    let metric = vec![vec![0, SCALE], vec![SCALE, 0]];
    let masses = vec![500000, 500000];
    let mut rng = OsRng;
    let mut session = [0u8; 32];
    let mut nonces = [[0u8; 32]; 2];
    rng.fill_bytes(&mut session);
    for nonce in &mut nonces { rng.fill_bytes(nonce); }
    let commitments = [model_commitment(0, &metric, &masses, &session, &nonces[0]),
                       model_commitment(1, &metric, &masses, &session, &nonces[1])];
    let circuit = AlignmentCircuit {
        first: metric.clone(), second: metric, transport: vec![vec![500000, 0], vec![0, 500000]],
        first_mass: masses.clone(), second_mass: masses, objective: Fr::from(0u64),
        bindings: Some(AlignmentBindings { session, nonces, commitments }),
    };
    let inputs = circuit.public_inputs();
    let key = Groth16::<Bls12_381>::generate_random_parameters_with_reduction(circuit.clone(), &mut rng)?;
    let proof = Groth16::<Bls12_381>::create_random_proof_with_reduction(circuit, &key, &mut rng)?;
    let mut bytes = Vec::new();
    key.vk.serialize_compressed(&mut bytes)?;
    fs::write(destination.join("verifying_key.bin"), bytes)?;
    let mut bytes = Vec::new();
    proof.serialize_compressed(&mut bytes)?;
    fs::write(destination.join("proof.bin"), bytes)?;
    let mut bytes = Vec::new();
    for input in inputs { input.serialize_compressed(&mut bytes)?; }
    fs::write(destination.join("public_inputs.bin"), bytes)?;
    if let Some(path) = private_destination {
        // Synthetic local inputs only; never place witnesses in the public package.
        for owner in 0..2 {
            let mut bits = String::new();
            for value in [0u64, SCALE, SCALE, 0, 500000, 500000] {
                for bit in 0..21 { bits.push(if (value >> bit) & 1 == 1 { '1' } else { '0' }); }
            }
            for value in session.iter().chain(nonces[owner].iter()) {
                for bit in 0..8 { bits.push(if (value >> bit) & 1 == 1 { '1' } else { '0' }); }
            }
            // Runtime role 1 supplies owner 1; role 2 supplies owner 0.
            fs::write(path.join(format!("{}.bits", 2 - owner)), bits)?;
        }
    }
    println!("{{\"scope\":\"synthetic_local_setup_public_package_only\"}}");
    Ok(())
}
