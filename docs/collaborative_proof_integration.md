# Collaborative proof integration decision

Status: incomplete; no collaborative prover is integrated. This note records
source review on 2026-10-02, not protocol acceptance or a dependency selection.

The current `fixture.rs` constructs both private metrics and nonces in one
process and invokes ordinary Arkworks Groth16. Separate private MPC inputs and
public proof verification have device evidence, but their conjunction does not
make this centralized prover private. Splitting a centrally generated witness
would likewise leave the original disclosure unchanged.

## Candidate evidence

The [co-snarks repository](https://github.com/TaceoLabs/co-snarks) exposes
collaborative witness-generation and Groth16 tooling for Circom. It states that
the software is experimental and unaudited, and notes different licenses for
some components. Its [official quick start](https://docs.taceo.io/docs/getting-started/quick-start-co-circom/)
uses three parties for witness extension and a BN254 example. This is not
confirmation of compatibility with Traceform's two-owner, BLS12-381 Arkworks
relation or Android deployment. Those compatibility checks remain unperformed.

The [original collaborative-zksnark implementation](https://github.com/alex-ozdemir/collaborative-zksnark)
explicitly describes itself as insecure and intended for benchmarking. It is a
research reference, not an accepted production replacement.

No new dependency, remote prover, third computing party or private-model export
has been enabled. A pending user choice concerns only whether an isolated local
three-node synthetic evaluation is acceptable; it does not authorize a production
trust-model change. Until resolved, retain the two-owner computation architecture.

## Required integration invariants

1. Each owner's metric and nonce enter only that owner's process. Witness
   generation, including SHA256 intermediate assignments, must remain shared;
   no coordinator may reconstruct either complete model or complete witness.
2. Preserve the packed-v2 relation: valid bounded metric, uniform masses,
   feasible permutation coupling, exact objective, session and both same-input
   commitments. Feasibility does not certify global GW optimality.
3. Preserve the existing seven-scalar public statement and canonical 224-byte
   encoding, or introduce a separately approved version. Never reuse a proof,
   verifying-key pin or owner certificate for a different relation or curve.
4. Authenticate participants and bind their computation to the session and
   circuit. Check approved model commitments before revealing the result.
   Persist query reservations and reject replay after application restart.
5. Bind the proof to the actual MPC result and obtain both owners' approval of
   that exact statement/proof. A successful centralized synthetic proof is
   insufficient evidence for this invariant.
6. Pin and review protocol dependencies and setup provenance. Validate private
   witness generation, adversarial aborts, malformed shares, independent verifier
   acceptance, owner approvals, restart replay and device resource behavior.

The next implementation depends on a compatible protocol and witness-generation
path, not just a new verifier adapter. Current synthetic tests and builds remain
available; final privacy acceptance and full deployment remain open.
