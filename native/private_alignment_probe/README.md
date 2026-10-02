# Private-computation backend compatibility probe

This isolated probe establishes that the pinned EMP v0.3.0 stack compiles and
executes a small authenticated-garbling circuit on the ARM macOS host. It is not
the Traceform private GW implementation, an Android build or a security audit.
Exact upstream commits and the temporary loopback transport patch are recorded
in `dependencies.json`. No dependency is added to the production APK or Rust
crate. All external headers and libraries reside under `/private/tmp`.

The upstream circuit has four inputs per side, four pairwise AND gates, an
inversion of the first AND result, and a final AND of that inversion with the
second AND result. Its SHA256 is pinned by `run_smoke.py`. Three independent input
pairs give public outputs `000010`, `111100` and `100000`. Both processes must
exit successfully and emit the expected six bits. Each process receives only
its own four synthetic input bits; the test harness knows both synthetic inputs.
This tests correct execution, not confidentiality against a malicious process
or a compromised host. Backend roles are important: at this pinned version,
ALICE supplies the `n2` input block and BOB supplies `n1`.

The host needs the SDK's C++ headers explicitly, as do the existing native-core
tests. Configure EMP-tool with `OPENSSL_ROOT_DIR=/opt/homebrew/opt/openssl@3`,
`CMAKE_CXX_FLAGS=-isystem /Library/Developer/CommandLineTools/SDKs/MacOSX.sdk/usr/include/c++/v1`,
and `CMAKE_INSTALL_PREFIX=/private/tmp/traceform-emp-prefix-v030`. Build only the
`emp-tool` target and install into that temporary prefix. Configure and install
EMP-OT headers with that same prefix and `CMAKE_FOLDER` pointing to it. The
installed NetIO header's listener must be restricted to `INADDR_LOOPBACK` before
compiling the probe. Do not silently substitute unpatched upstream networking.

Compile `smoke.cpp` as C++11 with `-O3 -pthread -march=armv8-a+simd+crypto+crc`,
the SDK header path, the temporary EMP include directory, the EMP-ag2pc checkout
include directory and OpenSSL headers. Link the temporary `libemp-tool.dylib`,
OpenSSL SSL/crypto libraries, and add the temporary library directory to rpath.
Then run:

```sh
python3 native/private_alignment_probe/run_smoke.py \
  --binary /private/tmp/traceform-private-alignment-smoke-v1 \
  --circuit /private/tmp/traceform-emp-ag2pc-v030/test/ands.txt \
  --output artifacts/alignment_v1/private_backend_arm_host_smoke.json
```

Each run owns and reaps both child processes, including timeout paths. The
protocol test is explicit; it is not run by the ordinary Python unit-test suite.

Work still required includes broad validation and practical sizing of the bounded metric/mass/GW circuit, checking its
same-input commitment outputs against authenticated application statements,
owner identity and transport authentication, Android cross-compilation,
output-query limits, adversarial transcript tests and review of the pinned
implementation. A successful Boolean smoke test does not close any of these
requirements or turn the existing centralized Groth16 prover into a distributed
prover.

## Bounded 80-bit loss contribution

`generate_loss_cell.cpp` records one arithmetic building block: two private
21-bit signed input encodings must each lie in `[0, 1000000]`; the public output
is a validity bit followed by the **unsigned** 80-bit numerator
`(first - second)^2 * 1000000^2`. Invalid inputs give validity false and numerator
zero. This is one contribution with a public coefficient, not the complete GW
sum, a transport solver, a metric validator or authenticated model commitments.
The maximum `1000000^4 = 10^24` exceeds the signed 80-bit range but fits its
unsigned bit representation; the output must never be decoded as signed or
truncated to 64 bits.

The pinned plaintext recorder has a constant-output problem: it stores no real
wire id for public constant labels. Directly revealing constant-zero output
bits can therefore reference input wire zero. An invalid odd input reproduced
validity false with the erroneous numerator 4095. The generator now XORs every
loss output with an explicit private-input-derived zero wire before revealing
it. This preserves the value while ensuring each exported output has a wire.
No upstream cryptographic implementation was modified for this correction.

`verify_loss_cell.py` independently evaluates every exported Boolean gate and
compares the result with Python arbitrary-precision arithmetic before checking
both protocol processes. Eight cases include the full `10^24` bound, both
distance directions, equal distances, an odd input, an upper-bound violation
and the signed negative encoding. All eight passed on the ARM host; receipts
are in `artifacts/alignment_v1/private_loss_cell_arm_host.json`. Compile the
generator with the same temporary EMP-tool library and SDK flags, then run:

```sh
/private/tmp/traceform-generate-loss-cell-v1 /private/tmp/traceform-loss-cell-v1.txt
python3 native/private_alignment_probe/verify_loss_cell.py \
  --binary /private/tmp/traceform-private-alignment-smoke-v2 \
  --circuit /private/tmp/traceform-loss-cell-v1.txt \
  --output artifacts/alignment_v1/private_loss_cell_arm_host.json
```

The protocol runner was extended to accept equal private-input blocks of up to
21 bits and up to 81 public output bits. The original three small-circuit cases
were rerun with that same binary and passed. Android and adversarial protocol
validation remain outstanding.

The current runner also supports unequal input blocks, each bounded by 11088
bits (the generator's largest 16-node first-owner block). This limit does not
establish that the corresponding large circuit is practical on Android.

## Complete feasible-coupling relation generator

`generate_gw_relation.cpp` records metric, mass and transport constraints and
the full squared GW sum, with generator arguments for 1–16 nodes on each side.
Metrics have bounded nonnegative entries, zero diagonals, exact symmetry and
triangle inequalities. Masses sum to SCALE; transport entries are bounded and
row/column sums match those same private mass variables. Marginal and triangle
calculations use 32 bits, sufficient for the largest 16-node bounded sum. Valid
objective sums are bounded by SCALE⁴ and use unsigned 80-bit output. Invalid
witnesses produce only validity false and a zero numerator.

The first input block holds the first metric, its masses and the transport; the
second holds the second metric and its masses. Under this pinned backend,
runtime BOB supplies the first block and runtime ALICE supplies the second.
These role names must not be silently confused with semantic owner positions
when adding signatures or commitment binding. The current runner accepts each
party's input through a bounded standard-input pipe, with one binary line and
EOF. Private bits are absent from process arguments. This remains a synthetic
test interface; authenticated real-model ingestion is not implemented.

The initial recorded 2×2 circuit had 907651 gates and 81 public output bits. All seven
synthetic cases passed independent gate evaluation and both protocol processes:
diagonal zero loss, uniform nonzero loss, asymmetry, nonzero diagonal, marginal
mismatch, negative transport and out-of-range mass. Receipts are in
`artifacts/alignment_v1/private_gw_relation_arm_host.json`. The same extended
runner was also used to rerun the three small-circuit and eight loss-cell cases.
Android resource use and malicious transcript tampering remain outstanding.

```sh
/private/tmp/traceform-generate-gw-relation-v1 2 2 /private/tmp/traceform-gw-relation-2x2-v1.txt
python3 native/private_alignment_probe/verify_gw_relation.py \
  --binary /private/tmp/traceform-private-alignment-smoke-v3 \
  --circuit /private/tmp/traceform-gw-relation-2x2-v1.txt \
  --output artifacts/alignment_v1/private_gw_relation_arm_host.json
```

This computes an objective for a supplied feasible transport. It does not
privately optimize that transport, bind the models to SHA-256 commitments,
generate a distributed Groth16 proof, authenticate real owners or enforce their
query policy. It is therefore not complete encrypted cross-owner alignment.

### Bounded intermediate products and rectangular validation

The current generator uses 40-bit intermediate products: the squared absolute
distance difference and each product of two transport entries are bounded by
SCALE², which is below 2⁴⁰. They are explicitly zero-extended as unsigned values
before the final 80-bit multiply and accumulation. Valid witnesses retain the
same exact objective. Invalid witnesses are masked after all range, metric and
marginal checks; their intermediate modular arithmetic cannot produce an
accepted score.

The 2×2 circuit now has 311603 gates, compared with the initial 907651. Nine
cases passed independent gate evaluation and both protocol processes, including
odd distances and mixed transport values. The 2×3 circuit has 698281 gates and
also passed nine cases. Its triangle-negative fixture is symmetric, bounded,
zero-diagonal and has valid masses and transport marginals, but distance 900000
exceeds the two-edge sum 400000 + 400000. It is rejected with validity false and
zero score. Valid rectangular cases retain exactly balanced integer remainders.

Receipts are `private_gw_relation_arm_host_optimized.json` and
`private_gw_relation_2x3_arm_host.json` in `artifacts/alignment_v1`. Gate reduction
is not a device latency or memory result. No larger-node or Android execution is
claimed. Generate the rectangular circuit with arguments `2 3`, then pass
`--second-nodes 3` to the verifier; it checks the corresponding input-block sizes.

### Exact cubic objective expansion

The latest generator avoids enumerating all `n²m²` loss terms. With exact
transport marginals, the same numerator is

`sum(Aij² * pi * pj) + sum(Bkl² * qk * ql) - 2 * sum((A*T)il * (T*B)il)`.

Computing the two inner matrix products takes `n²m + nm²` products. Marginal
constraints bound each inner sum by SCALE², so it retains the existing unsigned
40-bit intermediate representation. The two square sums and cross sum use 81
bits: an intermediate sum can reach twice SCALE⁴ even though the final squared
loss lies in `[0, SCALE⁴]`. After subtraction the output returns to 80 bits.
Invalid witnesses are still masked by the same metric and marginal constraints.

The 2×2 circuit now has 261549 gates; 2×3 has 434095. The corresponding nine-case
receipts are `private_gw_relation_expanded_2x2_arm_host.json` and
`private_gw_relation_expanded_2x3_arm_host.json`. The independent Python witness
validator still evaluates the original four-index sum, so it does not mirror
this expansion. Android practicality, larger-instance execution, private
transport optimization and commitment binding remain unverified.

### Bounded standard-input interface

The v4 runner takes only role, port and circuit path as arguments. It rejects
empty, nonbinary, oversized, wrong-dimension and multiline input before opening
the protocol connection; the former input argument is also rejected. Each test
harness closes the party's input pipe before waiting for protocol completion.
The same v4 binary passed all three small-circuit cases, eight loss-cell cases,
nine 2×2 cases and nine 2×3 cases. Receipts are the `private_*stdin*arm_host.json`
files in `artifacts/alignment_v1`. Historical receipts above retain their
original binary hashes and interfaces. No private input file is created by this
interface. The harness still knows both synthetic inputs, so these results do
not demonstrate confidentiality against a compromised host or provide owner
identity, commitment binding or transport authentication.

### Same-input GW commitment binding

The optional fifth generator argument selects the pinned EMP-tool SHA-256
compression circuit. `commitment.h` adapts its reversed word/bit ordering and
standard multi-block SHA padding. The exact preimage matches the existing Rust
Groth16 circuit: domain `traceform.gw.commit.v1` plus NUL, semantic owner byte,
node-count byte, SCALE as u32 little endian, 32-byte session, private 32-byte
nonce, then the same constrained row-major metric and masses as u32 little
endian. Transport is not part of the model commitment.

Both parties supply their own session copy and private nonce. The relation
requires the sessions to match. Public output contains validity, the unsigned
80-bit numerator, session bytes, first-owner digest and second-owner digest.
Bytes use least-significant-bit order within each byte. All 849 output bits are
zero for invalid witnesses. Valid commitments refer to the exact metric/mass
variables used in the objective, rather than a separately supplied model.
The application must compare these public outputs with its authenticated
statement; signatures and Groth16 verification are not connected to this probe.

The SHA adapter passed 25 independent gate-evaluation cases for 1–4 nodes,
both semantic owner positions, and changes to model, session and nonce.
`verify_commitment.py` pins the upstream SHA circuit hash and compares with
Python's independent SHA-256 implementation. The bound 2×2 and 2×3 relations
each passed 12 independent gate evaluations and both protocol processes,
including mismatched sessions, invalid metrics and invalid marginals. Receipts
are `private_commitment_gate_adapter_arm_host.json` and
`private_gw_bound_commitments_{2x2,2x3}_arm_host.json`. The v5 runner permits
11600 input bits per party (the 16-node first block plus session and nonce)
and 849 output bits, through the same bounded stdin interface.

```sh
/private/tmp/traceform-generate-gw-relation-v4 2 3 \
  /private/tmp/traceform-gw-relation-bound-2x3-v1.txt \
  /private/tmp/traceform-emp-tool-v030/emp-tool/circuits/files/bristol_fashion/sha256.txt
python3 native/private_alignment_probe/verify_gw_relation.py \
  --binary /private/tmp/traceform-private-alignment-smoke-v5 \
  --circuit /private/tmp/traceform-gw-relation-bound-2x3-v1.txt \
  --second-nodes 3 --bound-commitments \
  --output artifacts/alignment_v1/private_gw_bound_commitments_2x3_arm_host.json
```

This remains a supplied feasible transport, not a private transport optimizer.
No production owner enrollment, authenticated transport, distributed proof,
Android runtime cost, query policy or adversarial security audit is established.

`traceform.dynamics.private_alignment_output.verify_private_alignment_output`
now requires an exact valid 849-bit output, a numerator no greater than SCALE⁴,
and a byte-for-byte match to the existing canonical 224-byte seven-field public
statement. Each 32-byte session/digest is packed as 31 bytes in the first field
and one in the second, with zero padding. Swapped owner positions, changed
objective/session/commitments and noncanonical padding are rejected. The bound
protocol harness calls this check on each valid process output; the corresponding
receipts are `private_gw_statement_match_{2x2,2x3}_arm_host.json`. This is a
statement consistency check. The caller must authenticate owners and verify the
proof separately; a matching output is not evidence of either action.

### Isolated Android execution

The same runner was cross-compiled for Android API 26 ARM64 using NDK r27c,
EMP-tool's execution-state translation unit, and statically linked official
OpenSSL 3.5.8 (`f4dc4d58b48d346a8270183f89acf826d459b0ca`). OpenSSL was
configured out of tree with `android-arm64 -D__ANDROID_API__=26 no-shared
no-tests`; `make -j4 build_libs` produced its two static archives. The compile
uses the generated OpenSSL headers before upstream headers, temporary patched
EMP-tool/OT headers, and the pinned EMP-ag2pc checkout. It retains the loopback
listener restriction, C++11, `-O3 -pthread -march=armv8-a+simd+crypto+crc`, and
`-static-libstdc++`. No Android crypto library is installed globally.

Pixel 6a ran both parties locally with separate bounded stdin pipes. Three
small-circuit cases and all 12 bound cases for each of 2×2 and 2×3 passed.
Both parties' public outputs matched independent gate evaluation, exact Python
GW arithmetic and the canonical public-statement byte encoding. Reports are
`private_backend_pixel_smoke.json`, `private_gw_statement_match_2x2_pixel.json`
and `private_gw_statement_match_2x3_pixel.json`. Their `binary_sha256` identifies
the host ADB wrapper; `private_android_build_v1.json` records the actual Android
binary digest, confirmed on device, and its source/dependency binding.

This tests synthetic parties on one device. It does not establish cross-device
authenticated transport, real-owner privacy, private transport optimization,
production APK protocol integration or an adversarial security audit. The
6.9 MB executable also does not satisfy the separate computation-core memory
target; no private-protocol memory or latency bound is claimed here.

### Experimental mutual TLS transport

Compile `smoke.cpp` with `-DTRACEFORM_TLS_PROBE` to select `tls_io.h`. The
arguments after role/port/circuit are local certificate, local private-key
path, peer trust-certificate path and exact SHA256 fingerprint of the peer's
DER certificate. It requires TLS 1.3, verifies the peer chain, requires a peer
certificate on both sides and checks the provisioned exact fingerprint.
Each auxiliary Fpre connection performs the same checks. This isolated process
supports one participant identity, provisioned before auxiliary connections.

An initial wrong-pin test reproduced a hang: the rejected peer closed while
the other party began the backend's unbounded auxiliary reconnect loop. Each
TLS channel now exchanges a readiness byte only after its local chain and pin
checks succeed, before MPC setup. Three wrong-identity cases (wrong fingerprint,
wrong trust certificate, mismatched local key) now make both parties exit with
no public circuit output. The runnable rejection harness is
`verify_tls_rejection.py`, with explicit identities/binary/circuit/output paths.
Synthetic private TLS keys reside only in the temporary fixture directory.

The ARM host passed all 12 bound 2×2 cases over this transport after the fix;
receipts are `private_tls_identity_rejection_arm_host.json` and
`private_tls_gw_statement_match_2x2_arm_host_v2.json`. The connection is still
loopback only. These results do not establish Android TLS execution, real-owner
enrollment, remote addressing, certificate rotation, bounded peer availability,
or a reviewed production transport. The explicit test harness imposes a timeout
and reaps its subprocesses; that does not add an internal connection deadline.

The same TLS implementation was cross-compiled with the Android OpenSSL
archives and NDK r27c. Pixel passed all three wrong-identity cases and all 12
bound 2×2 cases; receipts are `private_tls_identity_rejection_pixel.json`,
`private_tls_gw_statement_match_2x2_pixel.json` and
`private_tls_android_sources_v1.json`. A further Mac/Pixel test over temporary
USB forwarding passed the small circuit on both devices, but the initial bound
GW execution timed out at 60 seconds. Its cause is not yet identified. The
initial evidence is in `private_tls_crossdevice_initial_probe.json`; it must not
be counted as successful full cross-device GW. Temporary forwards were removed.

The timeout was subsequently reproduced as an initial TLS unexpected EOF on
Mac while Pixel waited for a connection. ADB forwarding can accept local TCP
before the remote listener exists. `run_usb_client.py` retries only that precise
initial handshake failure for at most three seconds; certificate/pin rejection
and any failure after the backend emits circuit dimensions remain terminal.
It retains only that party's bounded input in memory. Three regression tests
cover input preservation, terminal authentication/MPC failures and the retry
deadline. This is an ADB test adapter, not a general protocol retry policy.

After correction, all 12 bound 2×2 cases passed with Pixel and Mac running one
party each over mutually authenticated TLS, including all auxiliary channels.
Receipts are `private_tls_gw_statement_match_2x2_crossdevice.json` and
`private_tls_crossdevice_sources_v1.json`. The initial failed receipt remains
historical evidence. The temporary USB forward was removed after completion.
Real-owner enrollment, private transport optimization, protocol/session framing,
application integration and production security review remain outstanding.

### Per-channel protocol binding

The bound TLS runner now requires one further argument: a 64-character lower
hex public transport-session identifier. It is independent of the private
witness's model-commitment session; the eventual application must provision and
check their association. After certificate verification/readiness and before
MPC bytes, each channel exchanges an exact 77-byte frame: eight-byte protocol
domain/version, runtime role, big-endian u32 channel sequence, 32-byte public
transport-session identifier, and SHA256 of the circuit file. The primary
channel uses sequence zero; auxiliary channels use their deterministic creation
order. The peer must have the opposite role and all other fields must match.
The probe assumes the caller controls an immutable trusted circuit file; this
peer agreement does not establish a trusted circuit catalog or approval.

`verify_tls_binding.py` passed six host cases: matching execution, session and
circuit mismatch, plus a correctly authenticated synthetic peer sending a wrong
role, sequence or protocol version. Invalid frames are rejected before MPC.
The updated runner also passed the three wrong-identity cases and all 12 bound
2×2 GW cases. Receipts are `private_tls_channel_binding_arm_host.json`,
`private_tls_bound_identity_rejection_arm_host.json` and
`private_tls_bound_gw_2x2_arm_host.json`. Earlier Android/cross-device receipts
refer to earlier binaries and do not validate this new framing version.

The framing version was then cross-compiled and its Pixel-only 12-case GW
and three wrong-identity checks passed. Its first cross-device execution exposed
an additional ADB race at an auxiliary connection: primary TLS/frame exchange
succeeded, but local forwarded TCP accepted before Pixel's next listener was
ready. Whole-MPC retry is intentionally prohibited by the USB bootstrap adapter.

The auxiliary server now binds/listens first, then sends its channel sequence
over the authenticated primary TLS channel. The client requires that sequence
before connecting. Auxiliary connections still perform certificate, pin and
frame checks. After this correction, all six host metadata tests and all 12
Mac/Pixel GW cases passed. Receipts are
`private_tls_channel_binding_ready_arm_host.json`,
`private_tls_channel_ready_gw_2x2_crossdevice.json` and
`private_tls_auxiliary_ready_sources.json`. The latter records the exact two
binaries and confirms the deployed Pixel digest. USB forwarding was removed.
This remains an isolated protocol probe, with real-owner registration, private
optimization, production application wiring and overall acceptance outstanding.

The auxiliary-readiness protocol now uses frame version **2**. Before this
change, a legacy version-1 authenticated frame was accepted and the regression
test timed out in the incompatible auxiliary phase. Version-1 and unknown
version-3 frames now fail the exact channel comparison before MPC. Seven
metadata cases passed, and all 12 version-2 Mac/Pixel GW cases passed after
deployment. Receipts are `private_tls_protocol_v2_binding_arm_host.json`,
`private_tls_protocol_v2_gw_2x2_crossdevice.json` and
`private_tls_protocol_v2_sources.json`. The last receipt binds the deployed
Android digest and host binary to current source. Temporary forwarding was
removed. Historical version-1 receipts remain compatibility evidence for those
specific earlier binaries, not acceptance of mixing protocol versions.

### Private integer transport descent

The generator's optional final `--optimize` argument (after the SHA circuit
path) removes the transport from the first owner's private inputs. It builds a
private northwest coupling from both parties' constrained masses, then visits
every public row-pair/column-pair once. For direction `D=(ei-ej)(ek-el)^T`, the
exact objective change is `-4*g*delta - 8*Aij*Bkl*delta²`, with
`g=sum((Aia-Aja)*Tab*(Bbk-Bbl))`. The feasible delta interval is
`[-min(Tik,Tjl), min(Til,Tjk)]`. Its quadratic coefficient is nonpositive for
the constrained nonnegative metric entries, so each cycle's minimum occurs at
an endpoint. Private comparisons select only strict improvements. The final
coupling is again constrained to bounded entries and exact row/column sums.

The signed gradient uses 64 bits; candidate arithmetic uses 85 bits because
its intermediate magnitude is at most 13*SCALE⁴, below 2⁸⁴. Valid accepted
objectives still use unsigned 80-bit public output. The coupling and improvement
decisions remain private; public output retains the existing 849-bit statement
format. The exact Python oracle independently checks the final score with the
original four-index sum and tests the cycle polynomial against interior points.

Four reference tests passed. The recorded 2×2 circuit has 926361 gates and
638 inputs per party; 2×3 has 1637823 gates with 638/764 inputs. Twelve 2×2
and eleven 2×3 host protocol cases passed. The 2×2 circuit was deployed to Pixel
and all twelve cases passed there, including zero-mass rows, invalid mass totals
and swapped mass distributions whose score decreased to zero. Receipts are
`private_transport_optimizer_{2x2,2x3}_arm_host.json` and
`private_transport_optimizer_2x2_pixel.json`. Tests require dedicated ports when
run concurrently. A port collision interrupted the first rectangular run;
the independent-port rerun passed, and the interrupted run is not acceptance.

This is one bounded descent sweep, not a stationary-point or global-optimum
certificate. The existing entropic plaintext solver is unchanged. GW's
nonconvex nature is described in the
[original optimization paper](https://proceedings.mlr.press/v48/peyre16.html).
Larger-node practical execution, additional sweeps,
real-owner enrollment, query controls and final application integration remain
outstanding. Generator support for up to 16 nodes is not evidence of practical
16-node private optimization on Android.

Mac/Pixel execution of the private optimizer subsequently passed all twelve
2×2 and eleven 2×3 cases. Runtime party 1 runs on Pixel and party 2 on Mac;
each process receives only its own synthetic metric, masses, session and nonce.
The circuit constructs the coupling internally. The harness knows both synthetic
inputs to calculate independent expected outputs; this is not evidence against
a compromised shared test host. Both parties' public objectives and commitment
outputs match the oracle, and invalid inputs produce masked zero output.
Receipts are `private_transport_optimizer_{2x2,2x3}_crossdevice.json` and
`private_transport_optimizer_crossdevice_sources_v1.json`. Device binary and
circuit digests match host artifacts. The dedicated USB forward was removed.
The full Python suite passed 276 tests after these additions.

### Transport session matches the circuit statement

An additional regression reproduced a gap: matching TLS sessions could differ
from the session in an accepted 849-bit circuit statement, and both processes
still exited successfully. The TLS runtime now compares that public statement
session with its authenticated transport session before publishing valid
output. A mismatch raises `transport statement session rejected`; invalid
statements retain their all-zero masking. This check applies to the bound
849-bit format, leaving smaller compatibility circuits unchanged.

The new `verify_transport_statement.py` tests matching, mismatching and invalid
masked cases. All three passed on Mac and all three on Pixel with the v4
runtime. Receipts are `private_tls_statement_session_arm_host.json` and
`private_tls_statement_session_pixel.json`. Existing v3 receipts describe
the earlier runtime, whose independent transport/model sessions are no longer
accepted by v4. Synthetic session provisioning and test wrappers must now use
the same public session as the circuit inputs. This adds session consistency,
not enrollment, trusted circuit selection, query authorization or replay policy.

The GW verifier's `--bound-transport-session` option supplies each synthetic
case's model session to its launcher as `TRACEFORM_TEST_SESSION`. The v4
Mac/Pixel launchers use that exact public session for TLS; private bits remain
on stdin. All twelve 2×2 private-optimizer cases then passed across Mac and
Pixel, including changed sessions, changed nonces and masked session mismatch.
Receipt: `private_transport_optimizer_v4_2x2_crossdevice.json`. The temporary
USB forwarding was removed. All eleven 2×3 cases subsequently passed with v4
as well, recorded in `private_transport_optimizer_v4_2x3_crossdevice.json`.
`private_transport_optimizer_v4_crossdevice_sources.json` binds both reports
to the host/Pixel binaries and current runtime/verification sources. The
rectangular run's temporary forwarding was also removed. Both shapes now have
cross-device evidence with transport-to-statement session consistency.

### Uniform 16-node private permutation descent

`generate_gw_relation.cpp --uniform-permutation` adds a separate bounded mode
for equal cardinalities with exactly uniform masses; the public node count must
divide 1000000. Each participant still supplies only its own metric, masses,
session and nonce. The circuit starts with the identity permutation, visits
pairs in lexicographic order once, and privately accepts strictly improving
transpositions. It retains the existing metric validation, commitments and
transport-to-statement session binding. General masses continue to use
`--optimize`; all eleven 2×3 protocol regressions passed after this addition.

For the aligned second metric B, swapping i and j changes the unweighted
squared-distance sum by
`4 * sum_r((A[i,r]-A[j,r]) * (B[i,r]-B[j,r]))`, excluding i and j.
The final numerator multiplies that sum by the squared uniform mass. The
Python oracle checks the resulting coupling again with the original four-index
objective. This searches permutation couplings with one sweep; it is neither
unrestricted transport descent nor a global optimum certificate.

The corrected 16-node circuit has 15807751 gates, 6224 private input bits per
party, 849 public output bits, and occupies 514780763 bytes. Ten recorded-gate
cases and all ten separate-party mutually authenticated host protocol cases
passed, including nonuniform-mass rejection, metric rejection, mismatched
sessions and an objective exceeding signed 64-bit range. Host executions took
7.80–9.79 seconds per case. A single process RSS sample was 496336 KiB per
party, not a measured peak. Receipts are
`uniform_permutation_16_plain_corrected.json` and
`uniform_permutation_16_private_host.json` under `artifacts/alignment_v1`.
The four-node private protocol also passed all ten cases.

Narrowing multiplication exposed an unsigned-square sign-extension error in
the large-objective case. Zero extension fixed the reproduced failure; the
same boundary case passed again at both four and sixteen nodes. Rejected
intermediate circuits are not accepted artifacts. These are synthetic input
experiments with test identities, not real-owner enrollment or a security
audit. Android execution and application integration remain separate gates.

### Packed commitments and bounded low-memory search

The uniform modes now provide separate eight-bit (`--packed-quantized-permutation`)
and six-bit (`--packed-lowmemory-permutation`) searches. They shift distances by
12 or 14 bits only for the single lexicographic transposition sweep. The final
objective uses the original full-precision metric; a candidate worse than the
identity is rejected. These modes do not certify an unrestricted or globally
optimal GW coupling. The four-node rejection fixture exercises that fallback
for both precisions.

The packed commitment has its own `traceform.gw.uniform-packed.v2` domain. Its
message is the domain plus NUL, owner byte, node byte, LE32 scale, LE32 uniform
mass, session32, nonce32, then lexicographic strict-upper-triangle distances as
20-bit LSB-first values, padded with zero bits to whole bytes. The circuit
validates the original complete metric and all uniform masses before acceptance;
only then are diagonal, lower triangle and masses determined by the committed
values. General-mass v1 commitments retain their original encoding.

Constant folding, bounded common-gate reuse and dead-gate elimination preserve
private-input and public-output order. An unsigned 20-bit square primitive has
812 independent integer/recorded-gate checks. Its initial exported circuit
failed because of the recorder's public-constant wire convention; explicit
zero-wire output materialization corrected that reproduced failure. The six-bit
16-node circuit has 3790251 gates and occupies 116672749 bytes. Four-node
15-case and sixteen-node 14-case recorded-gate and separate-party host protocol
checks pass. Host protocol elapsed range is 1.77–1.90 seconds; a separate
identical-input run measures maximum RSS of 831766528/941408256 bytes for the
two participants. The full Python suite passes 349 tests. Evidence and source
bindings: `lowmemory_packed_permutation_16_private_host.json`.

These are synthetic test inputs and identities. Pixel cross-device execution
has a separate receipt; standalone execution does not establish Android SDK
integration. The general SDK entry's 64 MiB circuit-snapshot limit excludes this
16-node circuit; the subsequent fixed-circuit entry is described below. Packed-v2 commitments also require their own approved
statement/proof integration rather than reuse of a v1 certificate. Real-owner
enrollment, independent security review and empirical cohort results remain
separate requirements.


The separate Pixel 6a/API37 and Mac run subsequently passed all fourteen
16-node cases, with each participant consuming only its own synthetic input.
Elapsed times were 14.11–15.74s per case; maximum device RSS sampled every .2s
was 628152 KiB. The 790000 KiB RSS / 196608 KiB available-memory boundaries
were not triggered. The final participant exit was confirmed, the dedicated
USB forward removed, and only the created test directory deleted. All twelve
frozen verifiers passed; the primary application was unchanged. Receipt:
`lowmemory_packed_permutation_16_crossdevice.json`. This establishes bounded
standalone cross-device protocol execution, while SDK snapshot admission and
approved packed-v2 statement integration remain outstanding.

The Android `UniformPrivateAlignment` entry now admits the fixed six-bit
16-node circuit into a 128 MiB immutable snapshot, keeping the general entry's
64 MiB bound. It preserves complete expected-output verification and checks
initial memory/disk admission plus the running system-memory reserve. Host
boundary and subprocess-cleanup tests pass; the suite totals 351 tests.
The first isolated application JobService test was rejected at memory admission
before native computation. `uniform_private_sdk16_admission_android.json`
records that terminal failure and cleanup. Application execution is not yet
accepted, and this API does not replace approved proof/owner integration.

The fixed-circuit admission budget is now derived from the accepted fourteen
standalone Pixel cases: 628152 KiB maximum sampled RSS plus 20% headroom rounds
up to 737 MiB, plus the unchanged 192 MiB system reserve gives 929 MiB.
This is a measured admission estimate, not a worst-case memory certificate.
The earlier 960 MiB diagnostic run rejected before computation with 987168768
bytes available; its terminal receipt remains separate.
`uniform_private_sdk16_pixel.json` records the subsequent identical-input SDK
case passing inside an isolated Android JobService, with a separate Mac peer.
Both exact outputs match the independent reference. The app recorded
1001312256 bytes available at entry and 31332.142ms elapsed. Its immutable
snapshot was deleted, the job became terminal, and temporary app/forward/files
were removed. The full host suite passes 351 tests in 38.166s. This is one SDK
case, not all fourteen SDK cases, real enrollment or packed-v2 proof approval.

All fourteen uniform16 cases subsequently passed through the application SDK,
including explicit rejection of invalid witnesses, session mismatch and an
80-bit valid objective. The isolated Android JobService and separate Mac peer
consume independent synthetic inputs. Every accepted receipt matches its
fresh dispatch nonce and terminal job; snapshots are absent after completion.
`uniform_private_sdk16_cases_completed_pixel.json` preserves the changed-nonce
initial memory-admission rejection and its retry after observed resource
recovery. Device elapsed range is28.177–33.807s. The test app and dedicated
forward were removed and twelve frozen verifiers passed. Full host suite:
352 tests in39.761s. SDK execution still requires an expected full statement;
unknown-score authorization, packed-v2 proof approvals and real enrollment
remain separate work.

The signed unknown-score SDK now uses the dedicated
`--approved-packed-lowmemory-permutation` circuit. Each input block has6992 bits:
6224 private model/session/nonce bits followed by768 approved session/commitment
bits. Both approval vectors must agree with actual commitments inside the
circuit before all849 public output bits are unmasked. Use `--approved-bindings`
with both uniform verification scripts. The old6224-bit known-statement circuit
remains unchanged. The confirmed pre-fix changed-model disclosure regression and
22 passing separate-party host cases are preserved under
`approved_binding_pre_reveal_*.json`. All356 host tests pass in44.574s. The
current guarded Android attempt stopped at memory admission (946552832 bytes
available), so it does not establish device computation success.
