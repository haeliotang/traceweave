//! Experimental private alignment proof backend. No private exchange is enabled.

use ark_bls12_381::Fr;
use ark_ff::{PrimeField, ToConstraintField};
use ark_crypto_primitives::crh::sha256::{constraints::Sha256Gadget, digest::Digest, Sha256};
use ark_r1cs_std::{alloc::AllocVar, boolean::Boolean, convert::ToBitsGadget, eq::EqGadget, fields::fp::FpVar};
use ark_relations::r1cs::{ConstraintSynthesizer, ConstraintSystemRef, SynthesisError};
use ark_r1cs_std::uint8::UInt8;

pub const SCALE: u64 = 1_000_000;

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
        let (n, m) = (self.first.len(), self.second.len());
        if !(1..=16).contains(&n) || !(1..=16).contains(&m)
            || self.first.iter().any(|r| r.len() != n) || self.second.iter().any(|r| r.len() != m)
            || self.transport.len() != n || self.transport.iter().any(|r| r.len() != m)
            || self.first_mass.len() != n || self.second_mass.len() != m {
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
        let mut all_mass_vars = Vec::new();
        for (masses, rows) in [(&self.first_mass, true), (&self.second_mass, false)] {
            let mass_vars: Vec<_> = masses.iter().map(|v| bounded_witness(cs.clone(), Fr::from(*v))).collect::<Result<_, _>>()?;
            mass_vars.iter().cloned().sum::<FpVar<Fr>>().enforce_equal(&scale)?;
            for (index, mass) in mass_vars.iter().enumerate() {
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
                let mut bytes: Vec<_> = commitment_header(owner as u8, metrics[owner].len()).iter().map(|v| UInt8::constant(*v)).collect();
                bytes.extend(session.iter().cloned());
                bytes.extend(UInt8::new_witness_vec(cs.clone(), &binding.nonces[owner])?);
                for variable in metrics[owner].iter().flatten().chain(all_mass_vars[owner].iter()) {
                    let bits = variable.to_bits_le()?;
                    bytes.extend(bits[..32].chunks(8).map(UInt8::from_bits_le));
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
