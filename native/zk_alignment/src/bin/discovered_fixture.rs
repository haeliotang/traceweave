// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Centralized synthetic sixteen-node proof of an already discovered statement.
//! The input contains both fake owners' witnesses: this is never a private prover.
use ark_bls12_381::{Bls12_381, Fr};
use ark_groth16::Groth16;
use ark_serialize::{CanonicalDeserialize, CanonicalSerialize};
use rand::rngs::OsRng;
use std::{error::Error, fs, io::Read, path::Path};
use traceform_zk_alignment::{check_witness, packed_uniform_model_commitment,
    AlignmentBindings, AlignmentCircuit, PackedUniformAlignmentCircuit, SCALE};

fn parse(bytes: &[u8]) -> Result<PackedUniformAlignmentCircuit, Box<dyn Error>> {
    if bytes.len() != 3400 || &bytes[..8] != b"TFGW16V2" { return Err("exact synthetic sixteen-node witness required".into()); }
    let mut offset = 8;
    let mut matrices = Vec::new();
    for _ in 0..3 {
        let mut matrix = vec![vec![0; 16]; 16];
        for row in &mut matrix { for value in row {
            *value = u32::from_le_bytes(bytes[offset..offset + 4].try_into()?) as u64;
            offset += 4;
        }}
        matrices.push(matrix);
    }
    let session = bytes[offset..offset + 32].try_into()?; offset += 32;
    let nonces = [bytes[offset..offset + 32].try_into()?, bytes[offset + 32..offset + 64].try_into()?]; offset += 64;
    let statement = &bytes[offset..];
    let objective = Fr::deserialize_compressed(&statement[..32])?;
    let commitments = [packed_uniform_model_commitment(0, &matrices[0], &session, &nonces[0])?,
        packed_uniform_model_commitment(1, &matrices[1], &session, &nonces[1])?];
    let circuit = PackedUniformAlignmentCircuit(AlignmentCircuit {
        first: matrices[0].clone(), second: matrices[1].clone(), transport: matrices[2].clone(),
        first_mass: vec![SCALE / 16; 16], second_mass: vec![SCALE / 16; 16], objective,
        bindings: Some(AlignmentBindings { session, nonces, commitments }),
    });
    let mut encoded = Vec::new();
    for value in circuit.public_inputs() { value.serialize_compressed(&mut encoded)?; }
    if encoded != statement { return Err("witness does not match discovered statement".into()); }
    Ok(circuit)
}

fn run() -> Result<(), Box<dyn Error>> {
    let args: Vec<_> = std::env::args().collect();
    if args.len() != 3 { return Err("usage: discovered_fixture SYNTHETIC_WITNESS PUBLIC_OUTPUT".into()); }
    let mut bytes = Vec::new();
    fs::File::open(&args[1])?.take(3401).read_to_end(&mut bytes)?;
    let circuit = parse(&bytes)?;
    // Both binding and the complete witness must pass before setup or output creation.
    check_witness(circuit.clone())?;
    eprintln!("SYNTHETIC_WITNESS_CHECKED");
    let mut rng = OsRng;
    let key = Groth16::<Bls12_381>::generate_random_parameters_with_reduction(circuit.clone(), &mut rng)?;
    let proof = Groth16::<Bls12_381>::create_random_proof_with_reduction(circuit.clone(), &key, &mut rng)?;
    let output = Path::new(&args[2]); fs::create_dir(output)?;
    let mut encoded = Vec::new(); key.vk.serialize_compressed(&mut encoded)?;
    fs::write(output.join("verifying_key.bin"), encoded)?;
    let mut encoded = Vec::new(); proof.serialize_compressed(&mut encoded)?;
    fs::write(output.join("proof.bin"), encoded)?;
    fs::write(output.join("public_inputs.bin"), &bytes[3176..])?;
    println!("{{\"scope\":\"centralized_synthetic_sixteen_node_discovered_statement_not_distributed_private_proof\"}}");
    Ok(())
}

fn main() -> Result<(), String> {
    // The sixteen-node R1CS has a deep expression/drop path. Bound its host stack.
    std::thread::Builder::new().stack_size(64 * 1024 * 1024)
        .spawn(|| run().map_err(|error| error.to_string())).map_err(|error| error.to_string())?
        .join().map_err(|_| "synthetic prover thread failed".to_string())?
}

#[cfg(test)]
mod tests {
    use super::*;
    fn witness() -> Vec<u8> {
        let mut bytes = b"TFGW16V2".to_vec();
        for matrix in 0..3 { for i in 0..16 { for j in 0..16 {
            let value: u32 = if matrix == 2 && i == j { 62500 } else { 0 };
            bytes.extend(value.to_le_bytes());
        }}}
        let session = [7; 32]; let nonces = [[11; 32], [13; 32]];
        bytes.extend(session); bytes.extend(nonces[0]); bytes.extend(nonces[1]);
        let circuit = AlignmentCircuit { first: vec![vec![0; 16]; 16], second: vec![vec![0; 16]; 16],
            transport: vec![], first_mass: vec![], second_mass: vec![], objective: Fr::from(0u64),
            bindings: Some(AlignmentBindings { session, nonces, commitments: [
                packed_uniform_model_commitment(0, &vec![vec![0; 16]; 16], &session, &nonces[0]).unwrap(),
                packed_uniform_model_commitment(1, &vec![vec![0; 16]; 16], &session, &nonces[1]).unwrap()] }) };
        for value in circuit.public_inputs() { value.serialize_compressed(&mut bytes).unwrap(); }
        bytes
    }
    #[test] fn rejects_shape_and_discovered_binding_changes() {
        let valid = witness(); assert!(parse(&valid).is_ok());
        for index in [0, 12, 3080, 3112, 3144, 3208, 3272, 3336] {
            let mut changed = valid.clone(); changed[index] ^= 1;
            assert!(parse(&changed).is_err(), "changed binding at {index}");
        }
        assert!(parse(&valid[..3399]).is_err());
        let mut extra = valid; extra.push(0); assert!(parse(&extra).is_err());
    }
    #[test] fn checks_complete_sixteen_node_witness() {
        std::thread::Builder::new().stack_size(64 * 1024 * 1024).spawn(|| {
        let circuit = parse(&witness()).unwrap(); check_witness(circuit.clone()).unwrap();
        for case in 0..3 {
            let mut invalid = circuit.clone();
            if case == 0 { invalid.0.transport[0][0] -= 1; }
            if case == 1 { invalid.0.objective += Fr::from(1u64); }
            if case == 2 { invalid.0.first[0][0] = 1; }
            assert!(check_witness(invalid).is_err());
        }
        }).unwrap().join().unwrap();
    }
}
