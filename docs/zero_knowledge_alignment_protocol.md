# Private alignment arithmetic contract

`alignment_statement.py` defines an exact plaintext witness relation shared with
the experimental Rust proof backend. The Python validator does not generate or verify cryptographic proofs, encrypt
inputs, or exchange models. Its output explicitly reports zero knowledge false.

The relation supports 1–16 nodes per side. Distances and masses use integer scale
1,000,000. Distances must be symmetric, zero diagonal, bounded and satisfy the
integer triangle inequality. Each mass vector sums to scale; transport entries
are nonnegative bounded integers with exact row and column marginals.

The squared GW objective numerator is

`sum((A[i,j] - B[k,l])² * T[i,k] * T[j,l])`.

Its denominator is scale to the fourth power. The numerator is at most that
denominator because transport mass sums to scale and distance differences are
bounded by scale. A proof field must exceed this bound, and the circuit must
range-constrain every distance, mass and transport entry; merely checking field
equalities would permit wraparound witnesses. Floating-point Sinkhorn output
cannot be passed off as this exact integer relation without a marginal-preserving
quantization step and a reported approximation error.

`quantize_alignment_witness` now performs that local conversion: distances round
up to scale, uniform masses distribute integer remainders deterministically, and
transport floors are completed to exact marginals in fractional-remainder order.
The result must pass the full integer metric validator. This is feasible rounding,
not an optimal transport algorithm. The summary reports maximum distance and
transport errors, transport L1 error, and the conservative objective error bound
`4 * distance_error + 2 * transport_l1_error`. The local comparison CLI exposes
this summary with `--integer-statement`; it does not export the integer witness
or generate a proof. Its existing floating coupling report remains sensitive.

Floating inputs are also checked before rounding: symmetry and triangle
inequalities allow only 1e-12 absolute numerical tolerance, and diagonals must
be zero. Regression fixtures reject asymmetric and triangle-violating inputs
that would otherwise become valid integer structures after ceiling. The integer
validator still independently checks the resulting witness without tolerance.

The separate experimental backend in `native/zk_alignment` implements this
bounded relation with Groth16 over BLS12-381. SHA-256 commitments bind the same
constrained metric and mass variables to ordered owners, private nonces and a
public session. The public statement contains the objective, session and both
commitments. It contains no model matrix, mass vector or transport witness.
Synthetic tests reject changed statements, proof points, invalid metrics and
incorrect marginals. This proves feasibility and the stated objective, not
global optimality or biological equivalence.

The APK verifier checks two owner signatures over the proof, public statement
and pinned verification-key digest before verifying the proof. Its private,
locked session ledger rejects replay across process restarts. Pixel 6a synthetic
fixtures accepted a fresh signed session, rejected replay, and rejected a changed
owner signature before consuming a session. These receipts are recorded in
`artifacts/alignment_v1/zk_apk_owner_authentication.json`. Trusted owner public
keys are fixture configuration; real owner enrollment is not implemented.

Private protocol work remains: secure computation between model owners so
neither owner nor a third-party prover receives both models; production setup
and verification-key trust; real owner enrollment; and an enforceable
output-leakage/query policy. The synthetic fixture uses a temporary local setup,
not a production ceremony. The proof hides the witness from its verifier, but
its centralized prover still knows both input models. A private computation
result must be bound to these same authenticated inputs and objective; an
unrelated private score computation cannot close this gap.

The current local solver and witness validator require access to both inputs.
No external cohort, raw observations or model transfer is enabled.

## Unknown-objective computation approval primitives

A separate internal v2 request format permits both owners to approve a comparison
before its objective is known. `OwnerAuthentication.verifyComputation` reads
`bindings.bin` (192 bytes) and two bounded ECDSA signatures. Each signature covers
`traceform.gw.compute-approval.v2` plus NUL, the ordered owner byte, the trusted
circuit SHA-256 as 64 lowercase ASCII characters, and the exact canonical binding
bytes. The body contains the public session and ordered model commitments in the
same two-scalar-per-binding encoding used by the complete statement. Both trusted
owner keys must be distinct. All unused scalar padding must be zero. The objective
and a Groth16 proof are absent from this pre-computation request. V1 proof-approval
signatures are not valid v2 computation approvals.

`PrivateAlignmentOutput.verifyBindings` is package-private. It validates the full
849-bit accepted output, bounds the original-precision objective, compares the
session/commitment encoding exactly with approved bindings and returns a complete
224-byte statement containing the computed objective. The existing public
`verify` still requires exact equality with the whole expected statement, including
the objective. Neither primitive dispatches a private computation or authorizes
replay. They are not exposed as an unapproved score-discovery endpoint.

Synthetic host regressions cover distinct-owner signatures, circuit/domain/body
changes, noncanonical signed padding, high objective bits, all binding-byte
mutations and preservation of the existing strict verification. All354 host tests
pass in38.438s; an uninstalled Android probe builds and verifies its signature.
`private_unknown_objective_primitives_host.json` binds sources and scope. The
one-time session reservation, native execution wiring, approved packed-v2 public
proof and real owner enrollment remain unfinished. Earlier device SDK receipts
cover the complete expected-statement API, not these new primitives.

## One-use computation session reservation

`ComputationSessions.reserve` is an internal pre-execution operation requiring
an active canonical matching query lease. It records SHA256(session) in the
app-private `computation_sessions.bin` ledger, bounded to4096 tags. It forces
the append and parent directory before returning. A completed reservation is
never released after failed/interrupted computation. Replay requires a new
session and new owner approvals. Malformed/torn or over-capacity ledgers reject
further computation rather than silently resetting prior reservations. The
signature verifier must run before reservation; the executor must reserve before
sending any private input. Those call sites are not integrated yet.

A synthetic host process halts after reservation with the lease still held;
a fresh process rejects the same session. Incorrect/closed/mismatched leases,
short sessions, malformed and capacity-exceeded ledgers are rejected. The same
fixture runs in two independent Android JVMs under the shell UID and passes,
including file/directory synchronization and restart rejection. The probe and
only its synthetic directory were removed. This is runtime evidence, not an
app-private approved-computation test, power-loss guarantee or tamper-resistant
counter. `private_computation_session_reservation_android.json` binds the source,
probe, two exits and limits. All355 host tests pass in40.459s; an uninstalled
probe APK builds and verifies its signature. Native unknown-score wiring,
application-private end-to-end verification, packed-v2 public proof approvals
and real owner enrollment remain open.

## Approved unknown-objective SDK entry

`UniformPrivateAlignment.computeApproved` now connects the v2 owner request to
private computation. It holds the matching query lease throughout verification
and execution, reads both trusted-owner signatures over the pinned circuit and
canonical bindings, and checks the existing uniform16 resource admission.
The private circuit is copied into the immutable bounded snapshot and its full
SHA verified. A pre-launch callback reserves the session after configuration,
private-input shape, resources and circuit verification, immediately before
starting the participant. If starting or computing subsequently fails, the
reservation remains consumed. Replays are rejected before another child starts.
The native result's objective is discovered and bounded; its session and model
commitments must match the signed request. The public known-statement APIs retain
whole-statement comparison, including the expected objective.

The host synthetic-process test verifies the exact ordering: bad signatures,
wrong circuit or unavailable resources do not create a reservation; the child
observes the durable reservation before reading private stdin; a nonzero unknown
score is accepted; replay and failed-computation retry do not launch another
child. Temporary snapshots are removed. All356 tests pass in39.131s, and the
uninstalled probe APK builds and verifies its signature. Evidence:
`approved_unknown_computation_host.json`. This is orchestrated host behavior,
not native MPC or Android app execution of the new signed entry. Those device
checks, packed-v2 public proof approval and real-owner enrollment remain open.

## Approval matching before public reveal

A real two-party native regression exposed an approval gap in the initial SDK
entry: replacing one valid private model while retaining the original two valid
signatures produced a valid public score before `verifyBindings` rejected the
changed commitment. `approved_binding_pre_reveal_failure.json` preserves this
failure. The benign pre-fix signed Android case computed a nonzero unknown score
and rejected same-process replay; its process-restart attempt stopped at memory
admission and does not prove cross-process replay.

The approved entry now pins a separate circuit SHA
`ba00463c74f7899a069305ab7edf8041234dea76ae6fb5a430182191c924dca2`.
Each party supplies its private 6224-bit model/session/nonce block followed by
768 approved binding bits decoded from its authenticated canonical request.
Inside the circuit, both request vectors must agree and match the actual session
and computed model commitments before any output is revealed. A mismatch masks
all 849 output bits, including validity, objective and commitments. The old
known-statement circuit and API keep their existing pin and input shape.

The compact circuit has3794901 gates and6992 inputs per party. Twenty-two
plaintext cases and twenty-two actual separate-party host MPC cases pass,
including changed valid models, one-sided request changes and mutations at the
session/commitment byte boundaries. These receipts are
`approved_binding_pre_reveal_plaintext.json` and
`approved_binding_pre_reveal_native.json`. All356 host tests pass in44.574s.
This is targeted synthetic regression evidence, not a security audit or owner
enrollment. The approved circuit still needs Android verification and a new
resource measurement; the existing conservative admission/reserve checks remain
in force. Packed-v2 public proof approvals remain separate open work.

The subsequent guarded Android changed-valid-model case passes:
`approved_binding_guard_android_success.json` matches the fresh job nonce,
separate Mac output (all849 zero bits), successful witness rejection, terminal
job and cleanup. Device elapsed is34203.627ms with1027751936 bytes available
before entry. An intervening expired synthetic-certificate failure consumed its
session and is retained separately; the successful retry used renewed fake
identities and a fresh signed session. All356 host tests pass in43.172s and twelve
frozen checks pass after removing the test app and USB forward. This closes the
single changed-model device regression, not normal guarded unknown-score
coverage, cross-process app replay, resource-withdrawal/cancellation, packed-v2
proof binding or real enrollment.

## Packed-v2 public proof relation and Android public verifier

The Rust backend now has an explicit `PackedUniformAlignmentCircuit`. It proves
full valid metrics, uniform masses, permutation coupling and the exact squared
loss, while hashing the packed-v2 strict upper-triangle 20-bit variables with
owner, shape, scale, uniform mass, nonce and session. Its nonzero two-node proof
and negative fixtures pass. A separate synthetic public package with numerator
SCALE^4/8 passes actual Pixel public-only verification. Four altered public
fields are rejected without creating the ledger, the valid proof consumes 32
bytes, and replay from a separate process is rejected without growing it.
`zk_packed_uniform_v2_android.json` binds source, key/package/verifier hashes and
six exits. The verifier comes from the existing release candidate; its generic
pinned Groth16 reader accepts the new independently generated v2 key. Temporary
public files and ledger were removed, and the primary app was not updated.

This relation proves a valid permutation coupling with the stated loss, not
optimality or the exact search algorithm. Its synthetic prover knows both models.
Owner-approved SDK verification, distributed private proving and production
setup/key trust remain open. Existing v1 interfaces retain their commitment
encoding and relation; no v1 certificate is substituted for v2 commitments.
