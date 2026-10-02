//! Synthetic proof package generator; never reads device models.
use ark_bls12_381::{Bls12_381, Fr};
use ark_ff::Field;
use ark_groth16::Groth16;
use ark_serialize::CanonicalSerialize;
use rand::{rngs::OsRng, RngCore};
use std::{error::Error, fs, path::PathBuf};
use traceform_zk_alignment::{check_witness, model_commitment, packed_uniform_model_commitment, AlignmentBindings, AlignmentCircuit, PackedUniformAlignmentCircuit, SCALE};

fn main() -> Result<(), Box<dyn Error>> {
    let mut arguments = std::env::args().skip(1);
    let first_argument = arguments.next().ok_or("output directory required")?;
    let packed = first_argument == "--packed-uniform";
    let destination = PathBuf::from(if packed { arguments.next().ok_or("output directory required")? } else { first_argument });
    let private_destination = arguments.next().map(PathBuf::from);
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
    let second_metric = if packed { vec![vec![0, SCALE / 2], vec![SCALE / 2, 0]] } else { metric.clone() };
    let masses = vec![500000, 500000];
    let mut rng = OsRng;
    let mut session = [0u8; 32];
    let mut nonces = [[0u8; 32]; 2];
    rng.fill_bytes(&mut session);
    for nonce in &mut nonces { rng.fill_bytes(nonce); }
    let commitments = if packed {
        [packed_uniform_model_commitment(0, &metric, &session, &nonces[0])?,
         packed_uniform_model_commitment(1, &second_metric, &session, &nonces[1])?]
    } else {
        [model_commitment(0, &metric, &masses, &session, &nonces[0]),
         model_commitment(1, &second_metric, &masses, &session, &nonces[1])]
    };
    let circuit = AlignmentCircuit {
        first: metric.clone(), second: second_metric, transport: vec![vec![500000, 0], vec![0, 500000]],
        first_mass: masses.clone(), second_mass: masses,
        objective: if packed { Fr::from(SCALE).pow([4]) / Fr::from(8u64) } else { Fr::from(0u64) },
        bindings: Some(AlignmentBindings { session, nonces, commitments }),
    };
    let inputs = circuit.public_inputs();
    // Abort before setup/proving; rejecting a published invalid proof is too late.
    if packed { check_witness(PackedUniformAlignmentCircuit(circuit.clone()))?; }
    else { check_witness(circuit.clone())?; }
    let key = if packed {
        Groth16::<Bls12_381>::generate_random_parameters_with_reduction(PackedUniformAlignmentCircuit(circuit.clone()), &mut rng)?
    } else { Groth16::<Bls12_381>::generate_random_parameters_with_reduction(circuit.clone(), &mut rng)? };
    let proof = if packed {
        Groth16::<Bls12_381>::create_random_proof_with_reduction(PackedUniformAlignmentCircuit(circuit), &key, &mut rng)?
    } else { Groth16::<Bls12_381>::create_random_proof_with_reduction(circuit, &key, &mut rng)? };
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
            let distance = if packed && owner == 1 { SCALE / 2 } else { SCALE };
            for value in [0u64, distance, distance, 0, 500000, 500000] {
                for bit in 0..21 { bits.push(if (value >> bit) & 1 == 1 { '1' } else { '0' }); }
            }
            for value in session.iter().chain(nonces[owner].iter()) {
                for bit in 0..8 { bits.push(if (value >> bit) & 1 == 1 { '1' } else { '0' }); }
            }
            // Runtime role 1 supplies owner 1; role 2 supplies owner 0.
            fs::write(path.join(format!("{}.bits", 2 - owner)), bits)?;
        }
    }
    println!("{{\"scope\":\"synthetic_local_setup_public_package_only\",\"commitment\":\"{}\"}}",
             if packed { "uniform_packed_v2" } else { "full_metric_mass_v1" });
    Ok(())
}
