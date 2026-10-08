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
uses three parties for witness extension and a BN254 example. The official
[generate-proof reference](https://docs.taceo.io/docs/co-circom/cli/generate-proof/)
explicitly lists both BN254 and BLS12-381, with REP3 and SHAMIR protocols.
BLS12-381 support is therefore documented; it is not an established curve
incompatibility. Compatibility with Traceform's exact Arkworks relation, keys,
witness layout and Android deployment remains unverified.

The implementation's
[protocol description](https://core.taceo.io/articles/honest-majority-mpc-for-cosnarks/)
identifies its replicated/Shamir protocols as honest-majority, and describes
REP3 as three computing parties. This does not establish privacy for two
computing owners with one dishonest participant. Setting a CLI threshold or
running three processes controlled by two owners does not supply independent
honest parties. A local three-process experiment would remain synthetic and
would not satisfy the unchanged two-owner requirement.

| Compatibility gate | Verified fact | Remaining evidence |
| --- | --- | --- |
| Curve | CLI documents BLS12-381 | Exact serialization, keys and public statement compatibility |
| Circuit/witness | Circom MPC witness generation and snarkjs artifacts documented | Preserve every packed-v2 constraint and seven public fields; independently establish relation equivalence |
| Parties/privacy | REP3/Shamir honest-majority model documented | Compatible two-owner security model and private witness generation |
| Runtime | Rust libraries and CLI are available | Pinned dependencies, Android build, resource and abort tests |

These are documentary facts, not a build, protocol run or dependency acceptance.

The [original collaborative-zksnark implementation](https://github.com/alex-ozdemir/collaborative-zksnark)
explicitly describes itself as insecure and intended for benchmarking. It is a
research reference, not an accepted production replacement.

No new dependency, remote prover, third computing party or private-model export
has been enabled. The authorized isolated synthetic work retains two MPC
participants and a centralized synthetic prover. It does not change the
production trust model or introduce a third computing party.

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
7. Check the complete extended witness before proving and bind the accepted
   shares to the proof-generation stage. A guarded GW result checks its own
   Boolean relation; it does not check every wire of a separate R1CS witness.
   [Malicious Security in Collaborative zk-SNARKs](https://eprint.iacr.org/2025/1026.pdf)
   demonstrates privacy attacks involving invalid witnesses and changed shares.
   Its honest-majority results do not establish security for two owners with one
   dishonest participant. This remains a protocol acceptance requirement.

Distributed private proving depends on a compatible protocol and witness-generation
path, not just a new verifier adapter. Current synthetic tests and builds remain
available; final privacy acceptance and full deployment remain open.

For an unknown-score computation, the Android caller can now obtain the exact
224-byte discovered statement with
`PrivateAlignmentOutput.encodePublicStatement(outputBits)`. This uses the same
strict output parser as binding verification and returns a fresh array. Owners
must approve this complete statement and the resulting proof; the computation
request approves only circuit/session/model bindings. Encoding does not generate
a proof, establish an authenticated computation or replace
`ProofVerifier.verifyPrivateAlignment`, which checks the approved proof package
against every byte of the actual MPC output before invoking the native verifier.

The centralized synthetic `fixture` generator checks satisfaction of its exact
R1CS before setup/proving. Invalid objectives, commitments, metrics and couplings
are rejected before proof generation. This local check holds the complete
witness and supplies no evidence of private distributed witness validation.

## Discovered-statement synthetic validation

`scripts/verify_guarded_private_sdk_usb.py` uses only fake TLS/owner identities
and generated sixteen-node metrics in `org.traceform.edge.synthetic`. Every
case receives a fresh session; existing computation reservations are retained.
The guarded circuit pin, 929 MiB admission and 192 MiB running reserve remain
unchanged. Invalid metric, session, nonce, model and approval cases must expose
only an all-zero result and leave no circuit snapshot.

For CPU cancellation and resource-permission withdrawal, the synthetic Mac
participant reports entry into authenticated MPC setup. The host then observes
at least 50 additional Android child CPU ticks before signalling the isolated
fixture. It uses the production admission/running-memory guard and reservation
primitives. A worker interrupt or an additional synthetic permission rejection
must reap the child, remove its snapshot and retain the spent session. This
does not induce system OOM, establish actual low-memory delivery, or measure a
worst-case memory bound. `TRACEFORM_PHASE_PROBE` is enabled only for the synthetic
host build and reveals no private inputs or intermediate wires.

The final nonzero computation exports its actual 224-byte statement from Android.
`discovered_fixture` consumes both synthetic witnesses and the independently
reconstructed permutation coupling; it rejects statement/commitment mismatch
and checks the complete sixteen-node packed-v2 R1CS before setup/proving. Its
host thread uses a bounded 64 MiB stack for the large R1CS expression/drop path.
Neither witness reconstruction nor this stack allocation occurs in the Android
public verifier.

Both synthetic owners subsequently sign the exact discovered statement, proof
and verifying-key pin using the separate proof-approval domain. Android calls
`ProofVerifier.verifyPrivateAlignment` against its retained actual MPC output.
Wrong owner signatures, a freshly signed different statement, a well-encoded
invalid proof and replay must be rejected without consuming an additional proof
session. This validates mechanical composition; the centralized prover still
knows both models and nonces. Real enrollment, distributed private witness
generation, production setup provenance and independent security review remain
open.

With the rebuilt isolated probe installed, fake identities in `RUNTIME/fixture`,
the pinned circuit and synthetic host participant available, run:

```sh
python3 scripts/verify_guarded_private_sdk_usb.py \
  --adb /opt/homebrew/bin/adb --serial SYNTHETIC_TEST_DEVICE \
  --runtime RUNTIME --openssl OPENSSL --prover DISCOVERED_FIXTURE \
  --output LOCAL_SYNTHETIC_REPORT.json
```

The driver removes its dedicated USB forward. Remove the isolated application
after recording its receipts; do not install it over the primary collector.

Recorded on 2026-10-08: eighteen normal/adversarial guarded cases and two CPU
abort cases pass in `guarded_unknown_score_sdk16_android.json`. Five discovered
statement proof/signature cases, separate host public verification and subsequent
actual-app-restart proof/computation replay rejection pass in
`discovered_unknown_score16_proof_android.json`. Both receipts are bounded
synthetic evidence; neither changes this document's incomplete collaborative
prover status.
