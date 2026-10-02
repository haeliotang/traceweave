//! Experimental private alignment proof backend. No private exchange is enabled.

use ark_bls12_381::Fr;
use ark_ff::{PrimeField, ToConstraintField};
use ark_crypto_primitives::crh::sha256::{constraints::Sha256Gadget, digest::Digest, Sha256};
use ark_r1cs_std::{alloc::AllocVar, boolean::Boolean, convert::ToBitsGadget, eq::EqGadget, fields::fp::FpVar};
use ark_relations::r1cs::{ConstraintSynthesizer, ConstraintSystemRef, SynthesisError};
use ark_r1cs_std::uint8::UInt8;

pub const SCALE: u64 = 1_000_000;

/// Reject an unsatisfied centralized synthetic witness before generating a proof.
/// This is not an MPC check: the caller already holds the entire witness.
pub fn check_witness<C: ConstraintSynthesizer<Fr>>(circuit: C) -> Result<(), SynthesisError> {
    let constraints = ark_relations::r1cs::ConstraintSystem::new_ref();
    circuit.generate_constraints(constraints.clone())?;
    if !constraints.is_satisfied()? { return Err(SynthesisError::Unsatisfiable); }
    Ok(())
}

/// Separate proof relation for packed-v2 uniform permutation couplings.
/// The prover still knows both models; this is not distributed proof generation.
#[derive(Clone)]
pub struct PackedUniformAlignmentCircuit(pub AlignmentCircuit);

impl PackedUniformAlignmentCircuit {
    pub fn public_inputs(&self) -> Vec<Fr> { self.0.public_inputs() }
}

impl ConstraintSynthesizer<Fr> for PackedUniformAlignmentCircuit {
    fn generate_constraints(self, cs: ConstraintSystemRef<Fr>) -> Result<(), SynthesisError> {
        self.0.generate_bound_constraints(cs, true)
    }
}

/// Allocate an integer witness and enforce its range in the circuit itself.
pub fn bounded_witness(cs: ConstraintSystemRef<Fr>, value: Fr) -> Result<FpVar<Fr>, SynthesisError> {
    bounded_integer(cs, value, SCALE, 20)
}

fn bounded_integer(cs: ConstraintSystemRef<Fr>, value: Fr, maximum: u64, bits: usize) -> Result<FpVar<Fr>, SynthesisError> {
    let variable = FpVar::new_witness(cs.clone(), || Ok(value))?;
    let slack = FpVar::new_witness(cs, || Ok(Fr::from(maximum) - value))?;
    for bounded in [&variable, &slack] {
        for bit in bounded.to_bits_le()?.iter().skip(bits) {
            bit.enforce_equal(&Boolean::FALSE)?;
        }
    }
    (&variable + &slack).enforce_equal(&FpVar::Constant(Fr::from(maximum)))?;
    Ok(variable)
}

#[derive(Clone)]
pub struct AlignmentCircuit {
    pub first: Vec<Vec<u64>>,
    pub second: Vec<Vec<u64>>,
    pub transport: Vec<Vec<u64>>,
    pub first_mass: Vec<u64>,
    pub second_mass: Vec<u64>,
    pub objective: Fr,
    pub bindings: Option<AlignmentBindings>,
}

#[derive(Clone)]
pub struct AlignmentBindings {
    pub session: [u8; 32],
    pub nonces: [[u8; 32]; 2],
    pub commitments: [[u8; 32]; 2],
}

fn commitment_header(owner: u8, nodes: usize) -> Vec<u8> {
    let mut bytes = b"traceform.gw.commit.v1\0".to_vec();
    bytes.extend([owner, nodes as u8]);
    bytes.extend((SCALE as u32).to_le_bytes());
    bytes
}

fn packed_uniform_header(owner: u8, nodes: usize) -> Vec<u8> {
    let mut bytes = b"traceform.gw.uniform-packed.v2\0".to_vec();
    bytes.extend([owner, nodes as u8]);
    bytes.extend((SCALE as u32).to_le_bytes());
    bytes.extend((SCALE as u32 / nodes as u32).to_le_bytes());
    bytes
}

pub fn packed_uniform_model_commitment(owner: u8, metric: &[Vec<u64>], session: &[u8; 32], nonce: &[u8; 32]) -> Result<[u8; 32], SynthesisError> {
    let nodes = metric.len();
    if owner > 1 || !(1..=16).contains(&nodes) || SCALE % nodes as u64 != 0
        || metric.iter().any(|row| row.len() != nodes || row.iter().any(|value| *value > SCALE)) {
        return Err(SynthesisError::Unsatisfiable);
    }
    let mut bytes = packed_uniform_header(owner, nodes);
    bytes.extend(session);
    bytes.extend(nonce);
    let mut payload = vec![0u8; (nodes * (nodes - 1) / 2 * 20 + 7) / 8];
    let mut position = 0;
    for i in 0..nodes { for j in i + 1..nodes { for bit in 0..20 {
        payload[position / 8] |= (((metric[i][j] >> bit) & 1) as u8) << (position % 8);
        position += 1;
    }}}
    bytes.extend(payload);
    Ok(Sha256::digest(bytes).into())
}

pub fn model_commitment(owner: u8, metric: &[Vec<u64>], masses: &[u64], session: &[u8; 32], nonce: &[u8; 32]) -> [u8; 32] {
    let mut bytes = commitment_header(owner, metric.len());
    bytes.extend(session);
    bytes.extend(nonce);
    for value in metric.iter().flatten().chain(masses.iter()) {
        bytes.extend((*value as u32).to_le_bytes());
    }
    Sha256::digest(bytes).into()
}

impl AlignmentCircuit {
    pub fn public_inputs(&self) -> Vec<Fr> {
        let mut inputs = vec![self.objective];
        if let Some(binding) = &self.bindings {
            for bytes in [&binding.session, &binding.commitments[0], &binding.commitments[1]] {
                inputs.extend(ToConstraintField::<Fr>::to_field_elements(bytes.as_slice()).unwrap());
            }
        }
        inputs
    }
}

impl ConstraintSynthesizer<Fr> for AlignmentCircuit {
    fn generate_constraints(self, cs: ConstraintSystemRef<Fr>) -> Result<(), SynthesisError> {
        self.generate_bound_constraints(cs, false)
    }
}

impl AlignmentCircuit {
    fn generate_bound_constraints(self, cs: ConstraintSystemRef<Fr>, packed: bool) -> Result<(), SynthesisError> {
        let (n, m) = (self.first.len(), self.second.len());
        if !(1..=16).contains(&n) || !(1..=16).contains(&m)
            || self.first.iter().any(|r| r.len() != n) || self.second.iter().any(|r| r.len() != m)
            || self.transport.len() != n || self.transport.iter().any(|r| r.len() != m)
            || self.first_mass.len() != n || self.second_mass.len() != m {
            return Err(SynthesisError::Unsatisfiable);
        }
        if packed && (n != m || SCALE % n as u64 != 0 || self.bindings.is_none()) {
            return Err(SynthesisError::Unsatisfiable);
        }
        let zero = FpVar::Constant(Fr::from(0u64));
        let scale = FpVar::Constant(Fr::from(SCALE));
        let mut metrics = Vec::new();
        for matrix in [&self.first, &self.second] {
            let variables: Vec<Vec<_>> = matrix.iter().map(|row| row.iter().map(|v|
                bounded_witness(cs.clone(), Fr::from(*v))).collect()).collect::<Result<_, _>>()?;
            for i in 0..matrix.len() {
                variables[i][i].enforce_equal(&zero)?;
                for j in 0..matrix.len() {
                    variables[i][j].enforce_equal(&variables[j][i])?;
                    for k in 0..matrix.len() {
                        let value = Fr::from(matrix[i][k]) + Fr::from(matrix[k][j]) - Fr::from(matrix[i][j]);
                        let slack = bounded_integer(cs.clone(), value, 2 * SCALE, 21)?;
                        slack.enforce_equal(&(&variables[i][k] + &variables[k][j] - &variables[i][j]))?;
                    }
                }
            }
            metrics.push(variables);
        }
        let transport: Vec<Vec<_>> = self.transport.iter().map(|row| row.iter().map(|v|
            bounded_witness(cs.clone(), Fr::from(*v))).collect()).collect::<Result<_, _>>()?;
        if packed {
            let uniform = FpVar::Constant(Fr::from(SCALE / n as u64));
            for value in transport.iter().flatten() {
                (value * (value - &uniform)).enforce_equal(&zero)?;
            }
        }
        let mut all_mass_vars = Vec::new();
        for (masses, rows) in [(&self.first_mass, true), (&self.second_mass, false)] {
            let mass_vars: Vec<_> = masses.iter().map(|v| bounded_witness(cs.clone(), Fr::from(*v))).collect::<Result<_, _>>()?;
            mass_vars.iter().cloned().sum::<FpVar<Fr>>().enforce_equal(&scale)?;
            for (index, mass) in mass_vars.iter().enumerate() {
                if packed { mass.enforce_equal(&FpVar::Constant(Fr::from(SCALE / n as u64)))?; }
                let sum = if rows { transport[index].iter().cloned().sum::<FpVar<Fr>>() }
                    else { transport.iter().map(|row| row[index].clone()).sum::<FpVar<Fr>>() };
                sum.enforce_equal(mass)?;
            }
            all_mass_vars.push(mass_vars);
        }
        let mut objective = zero;
        for i in 0..n { for j in 0..n { for k in 0..m { for l in 0..m {
            let difference = &metrics[0][i][j] - &metrics[1][k][l];
            objective += &difference * &difference * &transport[i][k] * &transport[j][l];
        }}}}
        objective.enforce_equal(&FpVar::new_input(cs.clone(), || Ok(self.objective))?)?;
        if let Some(binding) = self.bindings {
            let session = UInt8::new_input_vec(cs.clone(), &binding.session)?;
            for owner in 0..2 {
                let header = if packed { packed_uniform_header(owner as u8, metrics[owner].len()) }
                    else { commitment_header(owner as u8, metrics[owner].len()) };
                let mut bytes: Vec<_> = header.iter().map(|v| UInt8::constant(*v)).collect();
                bytes.extend(session.iter().cloned());
                bytes.extend(UInt8::new_witness_vec(cs.clone(), &binding.nonces[owner])?);
                if packed {
                    let mut payload = Vec::new();
                    for i in 0..n { for j in i + 1..n {
                        payload.extend_from_slice(&metrics[owner][i][j].to_bits_le()?[..20]);
                    }}
                    while payload.len() % 8 != 0 { payload.push(Boolean::FALSE); }
                    bytes.extend(payload.chunks(8).map(UInt8::from_bits_le));
                } else {
                    for variable in metrics[owner].iter().flatten().chain(all_mass_vars[owner].iter()) {
                        let bits = variable.to_bits_le()?;
                        bytes.extend(bits[..32].chunks(8).map(UInt8::from_bits_le));
                    }
                }
                let digest = Sha256Gadget::digest(&bytes)?;
                digest.0.enforce_equal(&UInt8::new_input_vec(cs.clone(), &binding.commitments[owner])?)?;
            }
        }
        Ok(())
    }
}

/// Check that the proof scalar field cannot wrap the bounded GW objective.
pub fn objective_fits_field() -> bool {
    // SCALE^4 = 10^24 < 2^80; a larger scalar field prevents integer wraparound
    // only when the eventual circuit also constrains every witness range.
    Fr::MODULUS_BIT_SIZE > 80
}

#[cfg(test)]
mod tests {
    use ark_bls12_381::{Bls12_381, Fr};
    use ark_ff::Field;
    use ark_groth16::{prepare_verifying_key, Groth16};
    use ark_serialize::CanonicalSerialize;
    use ark_std::rand::rngs::OsRng;
    use ark_relations::r1cs::{ConstraintSynthesizer, ConstraintSystem};

    #[test]
    fn witness_preflight_rejects_before_proof_generation() {
        let first = vec![vec![0, super::SCALE], vec![super::SCALE, 0]];
        let second = vec![vec![0, super::SCALE / 2], vec![super::SCALE / 2, 0]];
        let session = [7u8; 32];
        let nonces = [[11u8; 32], [13u8; 32]];
        let commitments = [super::packed_uniform_model_commitment(0, &first, &session, &nonces[0]).unwrap(),
                           super::packed_uniform_model_commitment(1, &second, &session, &nonces[1]).unwrap()];
        let circuit = super::AlignmentCircuit {
            first, second, transport: vec![vec![500000, 0], vec![0, 500000]],
            first_mass: vec![500000; 2], second_mass: vec![500000; 2],
            objective: Fr::from(super::SCALE).pow([4]) / Fr::from(8u64),
            bindings: Some(super::AlignmentBindings { session, nonces, commitments }),
        };
        super::check_witness(super::PackedUniformAlignmentCircuit(circuit.clone())).unwrap();
        for case in 0..4 {
            let mut invalid = circuit.clone();
            if case == 0 { invalid.objective += Fr::from(1u64); }
            if case == 1 { invalid.bindings.as_mut().unwrap().nonces[1][0] ^= 1; }
            if case == 2 { invalid.transport = vec![vec![250000; 2]; 2]; }
            if case == 3 { invalid.first[0][0] = 1; }
            assert!(matches!(super::check_witness(super::PackedUniformAlignmentCircuit(invalid)),
                             Err(ark_relations::r1cs::SynthesisError::Unsatisfiable)));
        }
    }

    #[test]
    fn packed_uniform_bytes_match_independent_twenty_bit_vectors() {
        for (owner, distance, nonce, expected) in [
            (0, super::SCALE, 11, "6c5425cb1303767363171cd60e6960d6d70a75cc036f6b1ea02fda1c78866f4a"),
            (1, super::SCALE / 2, 13, "d88de420011838e9bf0b8436f5a4399aafeee407ebf2ca7f12f3936b76a39fcd"),
        ] {
            let metric = vec![vec![0, distance], vec![distance, 0]];
            let digest = super::packed_uniform_model_commitment(owner, &metric, &[7; 32], &[nonce; 32]).unwrap();
            let actual = digest.iter().map(|byte| format!("{:02x}", byte)).collect::<String>();
            assert_eq!(actual, expected);
        }
        for (nodes, expected) in [
            (1, "3b29ed3439cc5470dec74c2920973f1e2578fe06ca61bc2435574218f86446ea"),
            (4, "10fdbb2705973e7ae2d2af2d8093f96c6a5b3cd8838b8ce0fdcc05b1c46b84ae"),
            (16, "0e51db5a55bb7f64737951006d76f276bf61df3f4602b3df881c977c3cb013ae"),
        ] {
            let metric = (0..nodes).map(|i: u64| (0..nodes).map(|j: u64|
                (i * i).abs_diff(j * j) * 1000).collect::<Vec<_>>()).collect::<Vec<_>>();
            let digest = super::packed_uniform_model_commitment(1, &metric, &[7; 32], &[13; 32]).unwrap();
            assert_eq!(digest.iter().map(|byte| format!("{:02x}", byte)).collect::<String>(), expected);
        }
        for metric in [vec![], vec![vec![0]; 3], vec![vec![0, super::SCALE + 1]; 2]] {
            assert!(super::packed_uniform_model_commitment(0, &metric, &[7; 32], &[11; 32]).is_err());
        }
        assert!(super::packed_uniform_model_commitment(2, &[vec![0]], &[7; 32], &[11; 32]).is_err());
    }

    #[test]
    fn packed_uniform_relation_requires_bound_equal_divisible_shape() {
        for (n, m, bound) in [(1, 1, false), (2, 1, true), (3, 3, true)] {
            let circuit = super::PackedUniformAlignmentCircuit(super::AlignmentCircuit {
                first: vec![vec![0; n]; n], second: vec![vec![0; m]; m],
                transport: vec![vec![0; m]; n], first_mass: vec![0; n], second_mass: vec![0; m],
                objective: Fr::from(0u64), bindings: if bound { Some(super::AlignmentBindings {
                    session: [7; 32], nonces: [[11; 32], [13; 32]], commitments: [[0; 32]; 2],
                }) } else { None },
            });
            assert!(circuit.generate_constraints(ConstraintSystem::new_ref()).is_err());
        }
    }

    #[test]
    fn packed_uniform_proof_binds_models_and_rejects_nonpermutation_transport() {
        let first = vec![vec![0, super::SCALE], vec![super::SCALE, 0]];
        let second = vec![vec![0, super::SCALE / 2], vec![super::SCALE / 2, 0]];
        let masses = vec![super::SCALE / 2; 2];
        let session = [7u8; 32];
        let nonces = [[11u8; 32], [13u8; 32]];
        let commitments = [super::packed_uniform_model_commitment(0, &first, &session, &nonces[0]).unwrap(),
                           super::packed_uniform_model_commitment(1, &second, &session, &nonces[1]).unwrap()];
        let objective = Fr::from(super::SCALE).pow([4]) / Fr::from(8u64);
        let fixture = super::PackedUniformAlignmentCircuit(super::AlignmentCircuit {
            first, second, transport: vec![vec![500000, 0], vec![0, 500000]],
            first_mass: masses.clone(), second_mass: masses, objective,
            bindings: Some(super::AlignmentBindings { session, nonces, commitments }),
        });
        let mut rng = OsRng;
        let key = Groth16::<Bls12_381>::generate_random_parameters_with_reduction(fixture.clone(), &mut rng).unwrap();
        let proof = Groth16::<Bls12_381>::create_random_proof_with_reduction(fixture.clone(), &key, &mut rng).unwrap();
        let verifier = prepare_verifying_key(&key.vk);
        assert!(Groth16::<Bls12_381>::verify_proof(&verifier, &proof, &fixture.public_inputs()).unwrap());
        for case in 0..8 {
            let mut circuit = fixture.clone();
            if case == 1 { circuit.0.objective += Fr::from(1u64); }
            if case == 2 { circuit.0.bindings.as_mut().unwrap().session[0] ^= 1; }
            if case == 3 { circuit.0.bindings.as_mut().unwrap().nonces[1][0] ^= 1; }
            if case == 4 { circuit.0.bindings.as_mut().unwrap().commitments[0][0] ^= 1; }
            if case == 5 { circuit.0.transport = vec![vec![250000; 2]; 2]; }
            if case == 6 { circuit.0.first_mass[0] += 1; circuit.0.first_mass[1] -= 1; }
            if case == 7 { circuit.0.first[0][0] = 1; }
            let cs = ConstraintSystem::new_ref();
            circuit.clone().generate_constraints(cs.clone()).unwrap();
            assert_eq!(cs.borrow().unwrap().instance_assignment[1..], circuit.public_inputs());
            assert_eq!(cs.is_satisfied().unwrap(), case == 0);
            if matches!(case, 1 | 2 | 4) {
                assert!(!Groth16::<Bls12_381>::verify_proof(&verifier, &proof, &circuit.public_inputs()).unwrap());
            }
        }
        let mut v1 = fixture.0.clone();
        v1.bindings.as_mut().unwrap().commitments = [
            super::model_commitment(0, &v1.first, &v1.first_mass, &session, &nonces[0]),
            super::model_commitment(1, &v1.second, &v1.second_mass, &session, &nonces[1])];
        assert!(!Groth16::<Bls12_381>::verify_proof(&verifier, &proof, &v1.public_inputs()).unwrap());
    }

    #[test]
    fn commitments_bind_actual_metric_mass_and_session_variables() {
        let metric = vec![vec![0, super::SCALE], vec![super::SCALE, 0]];
        let masses = vec![500000, 500000];
        let session = [7u8; 32];
        let nonces = [[11u8; 32], [13u8; 32]];
        let commitments = [super::model_commitment(0, &metric, &masses, &session, &nonces[0]),
                           super::model_commitment(1, &metric, &masses, &session, &nonces[1])];
        let fixture = super::AlignmentCircuit {
            first: metric.clone(), second: metric, transport: vec![vec![500000, 0], vec![0, 500000]],
            first_mass: masses.clone(), second_mass: masses, objective: Fr::from(0u64),
            bindings: Some(super::AlignmentBindings { session, nonces, commitments }),
        };
        let mut rng = OsRng;
        let key = Groth16::<Bls12_381>::generate_random_parameters_with_reduction(fixture.clone(), &mut rng).unwrap();
        let proof = Groth16::<Bls12_381>::create_random_proof_with_reduction(fixture.clone(), &key, &mut rng).unwrap();
        let verifier = prepare_verifying_key(&key.vk);
        assert!(Groth16::<Bls12_381>::verify_proof(&verifier, &proof, &fixture.public_inputs()).unwrap());
        for case in 0..4 {
            let mut circuit = fixture.clone();
            let binding = circuit.bindings.as_mut().unwrap();
            if case == 1 { binding.session[0] ^= 1; }
            if case == 2 { binding.commitments[0][0] ^= 1; }
            if case == 3 { binding.nonces[1][0] ^= 1; }
            let expected_inputs = circuit.public_inputs();
            if case == 1 || case == 2 {
                assert!(!Groth16::<Bls12_381>::verify_proof(&verifier, &proof, &expected_inputs).unwrap());
            }
            let cs = ConstraintSystem::new_ref();
            circuit.generate_constraints(cs.clone()).unwrap();
            assert_eq!(cs.borrow().unwrap().instance_assignment[1..], expected_inputs);
            assert_eq!(cs.is_satisfied().unwrap(), case == 0);
        }
    }

    #[test]
    fn nonzero_objective_proof_matches_exact_uniform_loss() {
        let objective = Fr::from(super::SCALE).pow([4]) / Fr::from(2u64);
        let circuit = super::AlignmentCircuit {
            first: vec![vec![0, super::SCALE], vec![super::SCALE, 0]],
            second: vec![vec![0, super::SCALE], vec![super::SCALE, 0]],
            transport: vec![vec![250000, 250000], vec![250000, 250000]],
            first_mass: vec![500000, 500000], second_mass: vec![500000, 500000], objective, bindings: None,
        };
        let mut rng = OsRng;
        let key = Groth16::<Bls12_381>::generate_random_parameters_with_reduction(circuit.clone(), &mut rng).unwrap();
        let proof = Groth16::<Bls12_381>::create_random_proof_with_reduction(circuit, &key, &mut rng).unwrap();
        let verifier = prepare_verifying_key(&key.vk);
        assert!(Groth16::<Bls12_381>::verify_proof(&verifier, &proof, &[objective]).unwrap());
        assert!(!Groth16::<Bls12_381>::verify_proof(&verifier, &proof, &[Fr::from(0u64)]).unwrap());
    }

    #[test]
    fn triangle_violation_fails_inside_constraints() {
        let circuit = super::AlignmentCircuit {
            first: vec![vec![0, super::SCALE, 0], vec![super::SCALE, 0, 0], vec![0, 0, 0]],
            second: vec![vec![0]], transport: vec![vec![333334], vec![333333], vec![333333]],
            first_mass: vec![333334, 333333, 333333], second_mass: vec![super::SCALE],
            objective: Fr::from(2u64) * Fr::from(super::SCALE).pow([2])
                * Fr::from(333334u64) * Fr::from(333333u64), bindings: None,
        };
        let cs = ConstraintSystem::new_ref();
        circuit.generate_constraints(cs.clone()).unwrap();
        assert!(!cs.is_satisfied().unwrap());
    }

    #[test]
    fn randomized_proofs_verify_and_reject_tampering() {
        let circuit = super::AlignmentCircuit {
            first: vec![vec![0, super::SCALE], vec![super::SCALE, 0]],
            second: vec![vec![0, super::SCALE], vec![super::SCALE, 0]],
            transport: vec![vec![500000, 0], vec![0, 500000]],
            first_mass: vec![500000, 500000], second_mass: vec![500000, 500000],
            objective: Fr::from(0u64), bindings: None,
        };
        // Local synthetic setup only: not a production ceremony or trusted key.
        let mut rng = OsRng;
        let key = Groth16::<Bls12_381>::generate_random_parameters_with_reduction(circuit.clone(), &mut rng).unwrap();
        let verifier = prepare_verifying_key(&key.vk);
        let proof = Groth16::<Bls12_381>::create_random_proof_with_reduction(circuit.clone(), &key, &mut rng).unwrap();
        assert!(Groth16::<Bls12_381>::verify_proof(&verifier, &proof, &[circuit.objective]).unwrap());
        assert!(!Groth16::<Bls12_381>::verify_proof(&verifier, &proof, &[Fr::from(1u64)]).unwrap());
        let second = Groth16::<Bls12_381>::create_random_proof_with_reduction(circuit, &key, &mut rng).unwrap();
        let mut first_bytes = Vec::new();
        let mut second_bytes = Vec::new();
        proof.serialize_compressed(&mut first_bytes).unwrap();
        second.serialize_compressed(&mut second_bytes).unwrap();
        assert_ne!(first_bytes, second_bytes);
        let mut modified = proof;
        modified.a = -modified.a;
        assert!(!Groth16::<Bls12_381>::verify_proof(&verifier, &modified, &[Fr::from(0u64)]).unwrap());
    }

    #[test]
    fn alignment_constraints_reject_modified_objective_and_marginals() {
        let fixture = super::AlignmentCircuit {
            first: vec![vec![0, super::SCALE], vec![super::SCALE, 0]],
            second: vec![vec![0, super::SCALE], vec![super::SCALE, 0]],
            transport: vec![vec![500000, 0], vec![0, 500000]],
            first_mass: vec![500000, 500000], second_mass: vec![500000, 500000],
            objective: Fr::from(0u64), bindings: None,
        };
        for case in 0..4 {
            let mut circuit = fixture.clone();
            if case == 1 { circuit.objective = Fr::from(1u64); }
            if case == 2 { circuit.transport[0][0] -= 1; }
            if case == 3 { circuit.first[0][1] -= 1; }
            let cs = ConstraintSystem::new_ref();
            circuit.generate_constraints(cs.clone()).unwrap();
            assert_eq!(cs.is_satisfied().unwrap(), case == 0);
        }
    }

    #[test]
    fn ranges_reject_overflow_and_negative_field_representatives() {
        for (value, expected) in [(Fr::from(0u64), true), (Fr::from(super::SCALE), true),
                                  (Fr::from(super::SCALE + 1), false), (-Fr::from(1u64), false)] {
            let cs = ConstraintSystem::new_ref();
            let _ = super::bounded_witness(cs.clone(), value).unwrap();
            assert_eq!(cs.is_satisfied().unwrap(), expected);
        }
    }

    #[test]
    fn scalar_field_exceeds_integer_objective_bound() {
        assert!(super::objective_fits_field());
    }
}
