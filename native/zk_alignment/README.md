# Experimental alignment proof backend

This independent Rust module pins Arkworks 0.5 dependencies and locks their
resolved versions. It implements scalar-field bounds, circuit-enforced integer
ranges, metric symmetry/zero diagonals/triangle inequalities, exact transport
marginals, and the public squared GW numerator. A synthetic two-node test now
generates randomized Groth16 proofs and verifies them. It does not exchange
private inputs or deploy to Android.

The field is larger than the integer objective bound. Every input is constrained
to 20 bits together with its slack, with input + slack = 1000000. Their sum
cannot wrap the scalar field, so both are nonnegative integers and input is
at most 1000000. Tests reject overflow and the negative field representative.
This avoids the generic comparator's constant-path assertion in the pinned
library without dropping the in-circuit range check.

Run `cargo test --locked --offline` after dependencies have been fetched.

Constraint fixtures reject changed objectives, marginals and asymmetric metrics.
The proof fixture also rejects modified public objectives and proof group
elements and checks that repeated proofs use different randomness. Its temporary
setup uses the operating system random source. The optional bound circuit also
proves SHA256 commitments to the actual private metric and mass variables, with
private 32-byte nonces and a public 32-byte session. Owner position, node count,
scale and protocol domain are included; integers use four-byte little-endian
encoding. Public inputs pack the objective, session and both commitments in that
order. Its proof verifies and rejects altered session or commitment inputs.
Unbound arithmetic circuits remain separate experimental fixtures.

A nonzero uniform-coupling fixture generates and verifies the exact numerator
SCALE^4/2 and rejects a zero public numerator. A separate triangle-violating
three-node fixture has valid marginals and the correct arithmetic objective;
its circuit is unsatisfied, so the rejection is not due to a wrong objective.

Commitments must still be authenticated as belonging to the model owners, and
verifiers must enforce unique sessions; binding alone is not replay prevention.
Tests use fixed synthetic nonces, not production randomness or owner identities.

`fixture <new-directory>` generates a synthetic bound public package using OS
randomness and temporary local setup. It writes only verifying_key.bin (728
bytes for this fixture), proof.bin (192 bytes), and public_inputs.bin (224 bytes).
It does not write a proving key, witness or nonces. `verify <directory>` reads
those public files with exact proof/input lengths, canonical point validation
and no trailing encodings. This independent public-only CLI verifies the package
on the host. The verifier now requires a second argument: a trusted verifying-key
SHA256 supplied from the caller's configuration, independently of the package.
It rejects mismatched keys before decoding proof material, and limits file reads
before allocating their full contents. A pin is not a setup ceremony or owner
authentication. Pixel accepts the known fixture pin and rejects a mismatched pin.
The Android release verifier was then built with the isolated
official Rust 1.94.0 toolchain and Android NDK r27c. Pixel 6a verifies the original
synthetic public package and rejects the modified objective package with exit 1.
Device binary and all three public-file SHA256 match the host. Evidence is in
artifacts/alignment_v1/zk_public_cli_pixel.json. This is a standalone executable,
not yet an APK SDK component or two-owner encrypted computation.

The verifier also requires a third argument: a persistent session-ledger path
from trusted application configuration. After successful verification it locks
the ledger, rejects a used session, appends its SHA256 and syncs file/directory.
The ledger holds at most 4096 sessions (131072 bytes) and fails closed at capacity
or on malformed records. Android uses libc flock because Rust's standard file
lock API is unsupported there. Four concurrent host submissions have one winner;
Pixel accepts the first submission and rejects it in a subsequent process, with
only one 32-byte ledger entry. Deleting or substituting the ledger defeats replay
protection; production must keep it in the app's private persistent storage.

The Android SDK now requires two distinct trusted owner public keys. Both owners
sign a domain-separated approval containing their ordered position, the trusted
verifying-key SHA256, all packed public inputs and the exact proof. Standard
SHA256withECDSA verifies both signatures before proof verification or session
consumption. The app takes bounded private snapshots first to prevent public
package replacement between signature and proof checks. The host Java fixture
accepts valid approvals and rejects a replacement owner or changed statement;
private signing keys stay in memory. This is trusted-key authentication, not
identity enrollment or validation that a model represents a particular person.
The updated APK was installed and verified a new signed synthetic session on
Pixel, then rejected its replay. A modified owner signature is rejected by Java
before proof verification and session consumption. The original signature was
restored; the private ledger stays at 64 bytes (the two accepted fixture sessions).
Evidence: artifacts/alignment_v1/zk_apk_owner_authentication.json.

Next: owner enrollment policy and device authentication validation; production
setup provenance; two-owner secure computation; and on-device verification.
Local synthetic setup will be explicitly experimental and will not establish
production parameter trust or privacy between two model owners.

## Packed-v2 uniform permutation proof relation

`PackedUniformAlignmentCircuit` is an explicit separate relation. It requires
equal 1..16-node shapes dividing SCALE, authenticated-format public bindings,
uniform masses and transport entries restricted to 0 or SCALE/n. Existing row and
column marginals therefore enforce a permutation coupling. Metric symmetry,
zero diagonals, triangle inequalities and ranges still constrain the complete
private matrices; the exact public squared-loss numerator is computed from them.
SHA256 binds the `traceform.gw.uniform-packed.v2` domain, ordered owner, shape,
SCALE, uniform mass, public session, private nonce and strictly upper-triangular
20-bit little-endian distances, with zero byte padding. Tests match independent
packed vectors and reject modified bindings, invalid metrics and fractional
(nonpermutation) transport.

`fixture --packed-uniform NEW_PUBLIC_DIRECTORY [NEW_PRIVATE_DIRECTORY]`
generates a two-node synthetic public proof with nonzero numerator SCALE^4/8 and
a fresh local experimental setup. Its public directory contains only the
verifying key, randomized proof and seven canonical scalar inputs. The public
CLI accepts the new independently pinned key and rejects modified public fields
before consuming a session; a valid submission consumes exactly one session and
replay is rejected. The existing fixture syntax and v1 relation remain available.

This proves existence of a valid uniform permutation coupling with the claimed
loss. It does not prove optimality or the exact private search algorithm. The
synthetic prover knows both models. MPC-based distributed proof generation,
production setup/key enrollment and end-to-end owner-approved Android integration
remain open; a v1 proof/key must not silently be used for packed-v2 commitments.

The packed-v2 public proof also passes four actual signed Android SDK cases:
wrong owner signature, doubly approved invalid statement, valid nonzero proof
and replay after terminating and restarting the app process. The private proof
ledger has no consumed session records for rejected inputs and has one 32-byte record after valid
verification and replay. `zk_packed_uniform_v2_signed_sdk_android.json` binds the
APK, source hashes, four fresh nonces and changed application PIDs. This covers
synthetic owner-approved public verification; MPC-result/proof integration,
private distributed proving and real enrollment remain open.
