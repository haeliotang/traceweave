# Native Android prototype and canonical-vector SDK

The standalone `org.traceform.edge` APK runs without Termux, PRoot or Python. It
contains an ARM64 C++ library, an Android foreground service, private SQLite
storage and a start/stop activity. It is development-signed and debuggable; it is
not a consumer release or a validated medical product.

`NativeCore` provides robust scaling, kinetic quantization and Monte Carlo
occupancy from caller-supplied time-conditioned CDF tables. `ModelEngine` loads
the model/normalizer asset pair. The generated `traceform-sdk.jar` requires the
matching `libtraceform.so` and assets in its host app. Input arrays must follow
the normalizer's feature ordering and use its canonical raw-feature units.

`artifacts/native_android_v1/native_128_feature_source_index.json` indexes all
128 asset feature names against `EdgeService.java`, `FeatureReadings.java`,
`NativeWifiSource.java`, `NativeMemoryPressure.java` and `NativeUsageSource.java`,
with source hashes and exact line expressions. It locates 37 literal `put`
assignments and five other name references; 86 names remain unresolved by this
literal index. The five referenced features now have manually traced production
paths: accelerometer/gyroscope vector jerk and the three higher accelerometer
derivatives. Sensor callbacks pass through `derivative` to `put(name, ...)` or
the `HIGHER_ACCEL_NAMES` loop. Invalid vectors, inadequate history and time gaps
can still leave these coordinates missing. Together, 42 names have located
literal or reviewed variable-dispatch paths; this is source coverage only.
The memory-pressure path reads kernel-wide PSI `some avg10` as a fraction;
procfs access denial or malformed input remains missing. This does not infer
process-specific pressure or replace a denied source with available-memory estimates.
These counts describe source text, not device capability: even a located
assignment can produce missing, imputed, derived or proxy values. The index
does not establish complete native acquisition or independent observability.

The exported Binder descriptor is `org.traceform.edge.Canonical`. Transaction 1
takes two double arrays: canonical raw features and normalized phase velocity.
It returns a JSON quantization result after `writeNoException`. Access requires
the signature permission `org.traceform.edge.CANONICAL_INPUT`; arbitrary apps
cannot submit vectors. This interface does not transfer raw observations to a
server. The manifest now requests INTERNET for the packaged private-computation
runtime's loopback TLS channels; that runtime connects only to 127.0.0.1.

The built-in sensor adapter currently supplies 22 observed, derived or proxy
features from acceleration, gyroscope, magnetic field, light, pressure, battery,
UTC phases and device state. Per-feature provenance distinguishes these kinds
and excludes stale values. The remaining 106 canonical features are explicitly
median-imputed and listed. This adapter is **not** a port of the complete Python
128-feature extraction logic, and its tokens are ineligible for interventions.
The complete existing Python chain remains separately supported while feature
extraction migration is unfinished. Normalization/quantization parity tests are
canonical-vector tests, not sensor-extraction parity tests.

Build with `scripts/build_native_android.py` and explicit NDK, build-tools and
android.jar paths. The script verifies APK signatures and writes source/artifact
digests. Outputs live in ignored `android/build/`. Its signing key is local
development material and must not be published as a release identity.

Pixel 6a first benchmark: 300 rollouts × 120 steps took 3.48824ms, with occupancy
mass conserved, using a synthetic CDF. Eight canonical-vector parity cases
passed with maximum error 2.73e-12. These numbers do not cover sensor acquisition,
kernel fitting or counterfactual branch overhead. Process PSS with the activity
visible was 110128KB; this includes ART, the activity, collection and storage,
and does not measure the original core-engine memory requirement.

Latest rebuild adds semantic model/normalizer SHA-256 bindings. Device benchmark
was 1.066854ms for the same synthetic workload; canonical-vector and Binder
parity still pass. Normal stop reports running=false. Shell service access is
rejected for missing CANONICAL_INPUT signature permission.

The build also produces `traceform-core-benchmark`, a standalone ARM64
executable using the same C++ core with 128 dimensions, 16 states, 300 rollouts
and 120 steps. It includes model matrices, CDFs, draws, outputs and process
runtime in `getrusage` peak RSS. Five Pixel runs measured 4,370,432–4,485,120
bytes and 1.356–1.765ms, with mass conserved. These runs satisfy the 5,000,000
byte and 15ms bounds for this synthetic core workload. They do not establish
these bounds for continuous SDE fitting, larger CDF tables or the full Android
service. The C ABI has no retained heap state; JNI caller copies are outside
this standalone measurement.

The builder now accepts `--private-participant` to package the separately built
ARM64 TLS private-computation executable as
`lib/arm64-v8a/libtraceform_private_participant.so`. The build report records its
digest and size. The v4 runtime was packaged, signed and installed on Pixel;
its extracted bytes match the tested standalone executable. Launching it under
the app UID with no arguments returns the expected usage rejection (exit 2).
Receipt: `artifacts/alignment_v1/private_participant_packaged_pixel.json`.
This receipt verifies packaging and executable access only. App-owned
circuit/credential provisioning remains required before app-private computation.
The 7,993,400-byte executable is outside the core benchmark's memory scope.

`PrivateParticipant.run(command, privateBits, publicInputs)` is included in the
SDK. Trusted app configuration must supply executable paths, circuit and TLS
arguments. Private bits are bounded and written exclusively to child stdin.
Concurrent bounded readers drain stdout/stderr; the participant has a 120-second
deadline and is terminated during cleanup. Exactly one valid public output must
match the caller's 224-byte statement. Process failure, duplicate output,
invalid witness, excess output and timeout reject. Host process tests cover
these boundaries with synthetic participants; they do not establish actual
app-private MPC execution or production circuit/credential provisioning.

The cancellation regression initially failed with `cancellation status lost`:
waiting for the child consumed the calling thread's interrupt flag. Cleanup now
always stops the worker pool, including interruption during the child reap,
and restores the flag before propagating cancellation. The host regression
also verifies no participant worker remains active and the recorded child PID
no longer exists after cancellation. This is process lifecycle evidence on the
host, not an Android lifecycle or background-policy acceptance test.

The debuggable activity's `verifyPrivateParticipant` fixture now runs both
packaged TLS participants under the app UID, reading only synthetic inputs and
temporary synthetic credentials from app-private files. It pins the known
2×2 optimizer circuit digest and checks both outputs against the exact public
statement. The first run failed because GrapheneOS denied the INTERNET runtime
permission (native socket creation led to `bind: Bad file descriptor`). After a
temporary permission grant, both participants passed; permission was revoked
after completion. Receipt: `private_participant_in_apk_pixel.json`. This proves
synthetic app-process invocation and statement checking, not separate owners,
production credential enrollment, proof generation or Android lifecycle safety.

The debug fixture now admits one active task per app process and records
ignored duplicate requests separately. Pixel's immediate duplicate request
was ignored and the original task passed. Activity destruction interrupts the
task it owns; fixture cleanup waits for participant workers to stop. The
Activity-recreation cancellation attempt produced no fresh receipt, so its
device cancellation behavior remains unverified. Temporary network permission
was revoked. Receipt: `private_participant_single_flight_pixel.json`.

A debug-only `finishPrivateFixture` option subsequently finishes the activity
500ms after both participant tasks are submitted. Pixel recorded a live task
at `onDestroy`, followed by `InterruptedException` 129ms later; inspection found
zero packaged participant processes. A subsequent normal fixture completed
successfully, and temporary network permission was revoked. Receipt:
`private_participant_activity_cancel_pixel.json`. This establishes this explicit
debug Activity-exit path, not arbitrary process death, background restrictions
or separate-owner production lifecycle behavior.

`PrivateParticipant.runPinnedCircuit` copies a caller-supplied public circuit
to a unique app-private cache file, with a 64MiB size limit, and checks SHA-256
against caller-trusted configuration before starting the participant. It
replaces the circuit argument with that verified snapshot and deletes the
snapshot after execution or rejection. The caller must supply an app-private
cache directory and trusted executable/TLS configuration; this is not a circuit
enrollment service. The raw command API remains for compatibility.

Host tests mutate the original circuit while the synthetic child runs: the
snapshot stays unchanged, a subsequent digest mismatch rejects and temporary
snapshots are removed. The APK fixture now uses this path for both participants;
Pixel passed and retained zero circuit snapshots. Network permission was
revoked after completion. Receipt: `private_participant_pinned_snapshot_pixel.json`.
The size limit covers the tested 2×2/2×3 circuits; larger private computations
are not accepted as practical solely because the generator supports them.

The pinned launcher also snapshots command configuration and the public
statement in memory before copying the circuit. It decodes the public session's
canonical field packing, rejects nonzero session padding and requires the TLS
session argument to match before any circuit snapshot or participant launch.
A host regression first demonstrated mismatching configuration was accepted;
the updated test confirms rejection before the synthetic child can mutate its
source fixture. Padding checks pass and Pixel's matching nonzero session fixture
still succeeds. Temporary network permission was revoked. Receipt:
`private_participant_session_preflight_pixel.json`. This preflight does not
authenticate owners or authorize a query.

`ApprovedPrivateAlignment.run` snapshots the bounded public package, checks the
verifying-key pin and both owner signatures, then invokes the pinned-circuit
launcher. `ProofVerifier.computeAndVerify` connects this preflight to private
computation and the existing output/proof/replay validation. Signatures approve
the exact statement, proof bytes and trusted key pin; verification of the
cryptographic proof still occurs after computation. Host tests use real P256
signatures and a synthetic child: swapped signatures, replaced key and malformed
proof bytes all reject before child launch, while approved input launches and
matches the statement. The full Python suite passed 278 tests; the new SDK/APK
was built, signed and installed. The combined API has not yet been exercised
with an actual MPC result and matching Groth16 package on Pixel. Real owner
enrollment, query policy and distributed proof generation remain open.

For the combined device test, the Rust synthetic `fixture` generator optionally
accepts a second, separate local directory for matching participant inputs.
That directory is mode 0700 on Unix and cannot contain or be contained by the
public package directory. It exports only synthetic role-specific bit strings;
no production model is read. The public directory retains only verification
material. An independent decoder matched both exported inputs' SHA-256 model
commitments and session to the generated public statement. Fresh synthetic
owners signed that exact statement/proof/key pin; host proof verification and
replay rejection passed, and nested private export was rejected. Receipt:
`combined_fixture_materials_host.json`. These prepared materials have not yet
been exercised through the combined APK API, and the prover remains local and
centralized.

The debug fixture's `approvedPrivateFixture` path subsequently exercised the
combined API on Pixel using those fresh materials. Runtime role 1 calls
`ProofVerifier.computeAndVerify`; role 2 calls the approved pinned launcher.
Both signatures authorize private input release, the internally optimized
MPC statement matches the signed public inputs, and Groth16 verifies and
consumes the app-private session. Rechecking the same output/package fails
with `replayed session` (exit 1). The fixture passes, temporary snapshots are
absent and the network permission was revoked. Receipt:
`approved_private_alignment_combined_pixel.json`. This is an actual integrated
synthetic execution under one app UID. It does not establish separate owner
isolation, distributed proving, production enrollment or full next-stage acceptance.

The combined API now reads the trusted app-private used-session ledger after
signature approval and before participant launch. Completed sessions, malformed
ledger lengths and exhausted capacity reject without private computation or
ledger modification. Host tests verify those cases and an empty-ledger success.
Pixel rejected the previously consumed synthetic session with `replayed session`
without granting network permission, and the ledger digest stayed unchanged.
Receipt: `approved_private_alignment_replay_preflight_pixel.json`. The final
Rust verifier retains its authoritative locked consumption check. This readonly
preflight does not atomically reserve concurrent pending sessions; a fresh-session
device rerun and query reservation policy remain outstanding.

The updated preflight subsequently passed a fresh synthetic session on Pixel
without clearing its existing ledger. Both approved participants computed the
matching statement; Groth16 verified and the ledger grew from 128 to 160 bytes,
exactly one entry. Proof replay rejected. After revoking network permission,
rerunning that newly consumed session failed in preflight with `replayed session`
and the ledger stayed at 160 bytes. Matching private/public commitments were
independently decoded before provisioning. Receipt:
`approved_private_alignment_fresh_preflight_pixel.json`. This completes the
fresh-session device rerun for this synthetic configuration. Atomic pending-query
reservation and separate-owner production acceptance remain open.

`ProofVerifier.computeAndVerify` now holds an `AlignmentQueryLease` from before
approval/computation through final proof verification. Cooperating combined API
calls permit one active query per app-private directory; overlapping calls fail
immediately, including different sessions. This trades query concurrency for
bounded admission. A persistent lock-file inode is retained; file existence
does not indicate an active query. Process termination releases the OS lock.

The first host test found a cross-process probe could acquire the lock after a
same-process duplicate opened and closed another channel. The implementation
now rejects same-process duplicates before opening a channel, using canonical
path identity. Host tests pass same-process rejection, cross-process rejection
and acquisition after killing the holder. Pixel passes same-app duplicate
rejection and reacquisition after normal release, without network permission.
The full Python suite passes 279 tests. Receipt: `alignment_query_lease_pixel.json`.
Android cross-process crash recovery and the guarded combined API's fresh-session
execution remain unverified. Low-level APIs that bypass the lease are not covered;
this is not a complete multi-owner query authorization policy.

The leased combined API subsequently passed a fresh synthetic session on Pixel.
Owner approval, private optimization and proof verification succeeded; proof
replay rejected and the ledger grew from 160 to 192 bytes. The debug lock test
then reacquired and released the query lease successfully. Network permission
was revoked. Receipt: `alignment_query_lease_combined_fresh_pixel.json`. This
completes the fresh normal-path combined execution with the lease. Android
cross-process crash recovery, verifier cancellation cleanup and separate-owner
production policy still require evidence.

A host regression for the proof-verifier subprocess initially reproduced lost
cancellation status and a live child after the caller returned. The verifier
now terminates and waits for its child in a common cleanup path on success,
failure, timeout or cancellation; interrupted status is restored before the
exception propagates. The test waits for the synthetic verifier's PID before
interrupting and confirms that PID is absent afterward. Minimal Android/JSON
stubs exercise this process boundary, not proof cryptography. The full suite
passes 280 tests, and the rebuilt APK was signed and installed. Receipt:
`proof_verifier_cancellation_host.json`. Android cancellation during the proof
verification phase has not yet been directly exercised.

A debug fixture then exercised the actual packaged Android verifier while it
waited for a synthetic public-key FIFO. The first observation attempt used
unavailable task `children` files and failed acceptance. The corrected observer
checks only matching app-UID processes and the unique synthetic package path.
Pixel observed the live child, interrupted its caller and confirmed child
absence, preserved interrupt status, unchanged replay ledger and successful
query-lease reacquisition. The FIFO/cache directory is removed. No network
permission was granted. Receipt: `proof_verifier_cancellation_pixel.json`.
This proves cancellation during public-input waiting in the real verifier
process; it does not directly exercise interruption during proof CPU work or
Android cross-process lock crash recovery.

ARM64 now uses explicit double-precision NEON intrinsics for normalization and
the quantizer's position, velocity, speed and dot-product reductions. Odd tails
use scalar arithmetic; other architectures retain the scalar path. Decimal
rounding and finite-input checks remain in place. The installed APK passes eight
canonical-vector cases with maximum error 1.82e-12. Its JNI 300×120 synthetic
benchmark took 6.692ms. Five standalone runs of this final build measured
4,366,336–4,521,984 bytes peak RSS and 1.062–3.172ms. Vector instructions were
verified in both exported normalization and quantization functions. Monte Carlo
CDF traversal remains scalar C++; these measurements do not establish that
NEON itself accelerates the Monte Carlo traversal.

Native acquisition now derives the canonical magnetic horizontal/vertical
component ratio with a 0.5µT denominator floor and cap of 10. It also exposes
the canonical pressure-velocity/window-deviation combination as a proxy;
this is not measured buoyancy energy. The latter requires a fresh pressure
derivative and a populated variance window, and becomes missing when either
input is unavailable. Host checks cover warmup, invalid vectors, limits and
staleness. The installed Pixel APK passes the matching synthetic fixture
(`native_magnetic_buoyancy_test.json`); this does not establish live sensor
coverage or full 128-feature acquisition. No permissions were added.

Native electrical feature progress: the service now uses available battery
voltage/current to expose delta-V/delta-I impedance and I²R loss as proxies.
Only consecutive valid samples within 5s and absolute current changes of
at least 40,000µA qualify; unsupported inputs, smaller changes and gaps are
missing, without temperature-based defaults. The cached battery broadcast
does not establish simultaneous voltage/current measurement or identified
internal resistance. Battery temperature still does not fill device temperature.
Ten related host tests and the installed Pixel synthetic fixture pass;
`native_electrical_test.json` and `native_electrical_build.json` bind the result.
Full native 128-feature acquisition and roadmap acceptance remain open.

`PeriodicTransitionKernel` is now exported in the SDK. Its constructor takes
an IANA timezone, vocabulary size, epoch-millisecond/state arrays, segment
identities, explicit continuity flags and maximum gap. Segment identity must
bind model, boot, continuity epoch and source mode; callers must supply these
identities correctly. Same-segment adjacent pairs produce the empirical global
prior; absent source transitions fall back to self transitions. Query rows use
the same exact-phase daily/weekly Von Mises weight and prior strength 5 as
Python, without six-hour bins. `cdf(epochMillis, stepMillis, horizon)` returns
the layout consumed by `NativeCore.rollouts`. Storage and calculation scale
with the input records; the SDK does not silently truncate caller history.

Host parity covers UTC, Kathmandu, New York DST, weekly boundaries, excluded
segment/discontinuity pairs and unobserved sources. The installed Pixel APK
passed 300×120 native rollouts driven by this kernel on 78 synthetic pairs.
Monday/saturday target probabilities were 0.8408205426928945 and
0.15792085527239086. This first fixture CDF construction took 90.94ms; it is
not a <15ms end-to-end forecast claim. Receipt/build bindings are
`native_periodic_kernel_test.json` and `native_periodic_kernel_build.json`.
Live service history-to-kernel integration and predictive validation remain
open. This test does not activate sensors, interventions or network access.

Native history forecast integration: signature-protected Binder transaction 2
and `CanonicalClient.forecast` now read the retained 30-day warm trajectory
through a separate read-only database connection. The caller supplies IANA
zone, as-of instant, step, horizon, runs and seed. Workload limits bound IPC
allocation. Rows require matching model/normalizer SHA-256 bindings, known
tokens, source and session identities. Excluded rows break continuity rather
than joining their neighbors; session changes and >300s gaps are excluded.
Source SHA-256 binds length-prefixed retained payloads in query order. Results
report source coverage, age, eligible pairs and separate CDF/native timings.
No interventions are enabled by this API.

A device regression first demonstrated stale initial-state misuse, then passed
after >300s-old initial states became unavailable. The synthetic SQLite fixture
also verifies digest exclusions, no cross-session transitions, read-only row
counts and no-history fallback. Actual retained Pixel history has 20 matching
records, 46 exclusions and 11 eligible pairs. Current forecasting is unavailable
because that native history is about 6.9h old. An explicitly historical query
at its final retained timestamp completes 300×120 rollouts (229.01ms CDF build,
6.74ms native execution). Raw frames, states and probability trajectories were
not exported into repository evidence. This is history-to-kernel integration,
not fresh live predictive validation. Binder transport of this new transaction
has not yet been directly exercised. Receipts are
`native_history_forecast_test.json` and `native_history_forecast_build.json`;
the earlier stale behavior is preserved as before-fix evidence. Eleven affected
host tests pass; the Android-specific database regression runs on the device.

Exact CDF construction now computes one temporal phase per forecast step and
accumulates each observed pair into its source row directly. It removes repeated
per-source timezone conversion and full pair scans while preserving prior,
weights, order within each row and normalization. Host parity now also compares
full CDFs with independently generated Python probabilities in three timezones.
Eleven affected host tests pass. The same installed Pixel synthetic 16-state,
78-pair, 120-step profile measured 448.12–481.69ms before and 142.23–152.08ms
after across five runs. Before profiling, 1,920 phase calls alone took 144.90ms.
Monday/saturday probabilities and mass conservation are unchanged. Profile
receipts and source bindings are `native_periodic_profile_{before,after}.json`
and matching build reports. This remains above the end-to-end performance target.

The optimized real retained-history path also passes its device regressions:
current-state prediction stays unavailable when stale, while explicit historical
as-of prediction completes (106.06ms CDF, 8.33ms native rollout). Scope and
source digest are in `native_history_forecast_cdf_optimized_test.json`; no raw
frames, tokens or probability trajectory were exported. Continued optimization,
fresh native-history collection and predictive validation remain required.

The existing C++ core now implements exact Von Mises weight accumulation and
CDF normalization through `traceform_periodic_cdf`. `NativeCore.periodicCdf`
exposes the bounded JNI interface; `PeriodicTransitionKernel.nativeCdf` supplies
IANA-aware phase arrays, and the native history forecast uses this path. The
portable Java CDF remains a reference. The JNI/core reject inconsistent shapes,
unnormalized priors, invalid state indices, nonfinite phases and overflowing
phase differences. An overflow regression failed before its targeted fix and
passes afterward. Empty-history JNI behavior is exercised on the device.

Thirteen affected host tests pass, including independently generated Python
CDFs in UTC/Kathmandu/New York. Pixel's same 16-state/78-pair/120-step fixture
has zero maximum CDF difference from Java; five native construction timings
were 30.94ms initially and 18.99–19.60ms subsequently (previous Java path
142.23–152.08ms). Its 300 native rollouts took 10.42ms. This does not establish
<15ms for construction plus execution or for a full 30-day history. Receipts:
`native_periodic_cpp_test.json` and `native_periodic_cpp_build.json`.

The actual retained-history path passes SQLite regressions and still rejects
stale current-state prediction. Explicit historical calculation took 17.85ms
CDF plus 8.88ms rollout, with the same 20 matching rows and 11 valid pairs.
`native_history_forecast_cpp_test.json` contains only aggregate metadata;
no raw observations, state token or probability trajectory were exported.
Fresh live forecasting, Binder transport validation and whole-roadmap
acceptance remain open.

Native query-phase sequences now reuse the IANA offset until the next timezone
transition. Local day/week arithmetic uses floor division/remainders, including
negative epochs, and each query retains checked timestamp arithmetic. At a
crossed transition or a large step spanning several transitions, the offset and
next transition are refreshed at the actual query instant. This removes repeated
ZonedDateTime conversion for every step without assuming fixed-offset timezones.
Thirteen affected host tests pass. Python phase/CDF comparisons cover UTC,
Kathmandu, New York and Lord Howe. The device independently compares sequence
phases against point conversion, including pre-1970 timestamps and 120-day steps
that span multiple transitions. Native-vs-Java CDF maximum error remains zero.

Two Pixel profiles show variability: first native construction measured 4.91ms
(15.23ms initial call, subsequent trials 4.95–5.59ms), with 7.53ms rollouts;
the final build measured 7.78ms (19.23ms initial, subsequent 8.86–9.52ms), with
9.46ms rollouts. No stable end-to-end <15ms claim is made. Final historical-as-of
calculation measured 5.36ms CDF plus 8.97ms rollout, while stale current forecasting
remains unavailable. Receipts `native_periodic_phase_sequence_test.json`,
`native_periodic_phase_sequence_final_test.json`, and
`native_history_forecast_phase_sequence_test.json` preserve these distinct scopes;
matching build reports bind the sources. Actual full-30-day scaling, cold-start
latency, fresh live collection and new Binder transaction verification remain open.

SDK binding no longer starts foreground collection: `onCreate` loads the immutable
model only, and `onStartCommand` explicitly initializes notification, sensor
listeners, sampling thread and storage. A real Android regression with capture
resource access forbidden failed before the change (`notification` requested)
and passes after it; the test did not enable actual sensors. The service now
runs in the separate `:edge` process, retaining the signature permission.

Pixel directly exercised forecast transaction 2 through Binder: client PID
12942 and service PID 12985 differed, historical occupancy probabilities
matched local computation exactly, invalid request bounds were rejected,
collection remained unstarted and retained frame counts were unchanged.
Unbinding destroys a bound-only service without writing capture status or
storage checkpoints. Receipts/source binding are
`native_bound_lifecycle_test.json`, `native_forecast_sdk_test.json`, and
`native_forecast_sdk_build.json`. Thirteen affected host tests pass; lifecycle
and actual Binder tests run on Android. This proves authorized same-UID
cross-process calls, not other-certificate denial or full SDK consumer onboarding.
Explicit capture startup in the new process and fresh-install no-history behavior
still need acceptance checks. Existing user silence/intervention settings were
not altered. Full roadmap acceptance and publication remain open.

No-history SDK behavior is now explicit: a missing history file returns
`available=false`, `reason=no_retained_history`, zero record/pair counts and
model/normalizer binding metadata, without creating a database or initiating
collection. Parameter bounds, IANA timezone and timestamp arithmetic are checked
before history availability, including the missing-file path. The same validation
also applies to direct history forecasts rather than depending on populated rows.

A real Android Binder regression uses an isolated absent-history path while
forbidding capture-resource access. It first failed with SQLiteCantOpenDatabase,
then passed after the fix; valid calls leave the path absent, invalid horizon
and timezone calls are rejected. No existing database was deleted, replaced or
exported. Receipts/build: `native_no_history_sdk_test.json` and
`native_no_history_sdk_build.json`. Existing historical cross-process prediction
also passes (client PID14953/service PID15001), with identical occupancy, no
collection and unchanged frame count (`native_no_history_existing_sdk_test.json`).
Thirteen affected host tests pass. This is a no-history-path regression rather
than a destructive uninstall/reinstall fresh-consumer acceptance. Full new-device
onboarding, other-certificate denial, explicit collection lifecycle and complete
roadmap acceptance remain open.

SDK signature denial now has direct different-application evidence. A temporary
client with no network permission requested CANONICAL_INPUT but was signed by a
different development certificate. Its installed UID10211 differed from target
UID10196. PackageManager reported the target exported service requires
org.traceform.edge.CANONICAL_INPUT; the client permission was denied, and Android
raised SecurityException on explicit service binding. Certificate SHA-256
fingerprints were verified distinct. No data transaction was submitted.

The initial harness incorrectly required the permission name in the exception;
Android used generic binding-denial wording. That first receipt was not accepted.
The corrected harness verifies the actual target service permission/exported
metadata and denied client permission, and passes. Probe source/build/signature
and result evidence are `sdk_permission_probe_{initial,initial_build,build,pixel}.json`.
The temporary client was then uninstalled; package lookup confirms removal.
The real Traceform installation and history were retained. Sources and explicit
build recipe reside under `android/security_probe` and
`scripts/build_sdk_permission_probe.py`; build keys/APKs stayed outside the repo.
This establishes protected binding denial for a differently signed client, not
raw Binder-handle transaction denial, production signing readiness or a complete
application threat review. All-roadmap acceptance remains open.

The standalone benchmark now times normalization, kinetic quantization, exact
periodic CDF construction and 300×120 rollouts together. It has two explicit
synthetic histories: 78 pairs and 86,400 pairs (30 days at 30s). Phase preparation
and database fitting remain outside the timer. This broader workload exposed
CDF roundoff: a zero-probability tail could yield 1.0000000000000002 before the
terminal 1, causing rollout validation failure. A host regression failed first;
C++ and portable Java now bound normalized cumulative entries at 1. External
invalid CDF rejection remains unchanged. Fourteen affected tests and all 284
host tests pass; installed APK periodic and cross-process SDK checks also pass.

Initial shell-launched peak-RSS readings around 11.8MB included resource history
preserved across exec. The original readings are retained in
`native_periodic_core_scaling_pixel.json`. The benchmark now forks before
allocating/testing and reports child peak plus launcher peak separately.
Linux resource-statistics preservation is documented at
https://man7.org/linux/man-pages/man2/getrusage.2.html .
The child includes its own native runtime and all test arrays; the launcher,
Java/ART, SQLite and full Android service remain outside that scope.

Five small-history runs took 1.21–2.37ms with 2,617,344–2,707,456-byte child peak
RSS; three monthly runs took 246.27–257.43ms with 4,243,456–4,333,568-byte peaks.
Monthly CDF construction took 245.22–256.37ms while rollouts took 1.00–1.01ms.
All conserve mass. Small-history combined computation passes the stated <15ms
and <5,000,000-byte gate; monthly combined computation fails latency despite
passing memory. This does not establish a full-month Android <15ms claim.
`native_periodic_core_isolated_scaling_pixel.json` and its build report bind this
evidence; `native_periodic_cdf_tail_fix_{pixel,sdk_pixel}.json` record APK checks.
Month-scale time-kernel construction is now the demonstrated scaling bottleneck;
full roadmap acceptance and publication remain open.

### 2026-10-01：复用三角函数后的精确周期核

原生核将查询相位的 sin/cos 缓存在预测窗口内，并将观测相位的 sin/cos 移到预测步循环外；逐对累加顺序保持不变。超大相位沿用原来的差值求余行为，非有限差值仍拒绝。未使用分桶或核近似。

284 项 Python 测试通过（20.562 秒）。Pixel 6a 应用已更新，Java/C++ CDF 最大误差 2.220446049250313e-16；跨进程 SDK 回执通过，未启动采集，帧数不变。独立子进程合成基准：78 对历史最大 5.034139 ms、2,670,592 B；86,400 对历史最大 84.962280 ms、4,218,880 B。月度时间核仍未满足 15 ms 门槛；这里的内存和时间不包含 Java、SQLite、相位准备或整个 Android 服务。

证据：`artifacts/native_android_v1/native_periodic_trig_reuse_pixel.json`、对应 build、fixture_pixel 和 sdk_pixel 回执。

### 2026-10-01：绑定客户端存在时显式停止采集

界面停止按钮和调试 stop intent 改为向服务发送 STOP_COLLECTION 动作。服务独立停止采集：禁止新采样、取消待执行任务、注销监听、移除前台通知，在工作线程收尾数据库和状态后退出。服务保持绑定期间，历史预测 SDK 不因停止采集而失效；收尾期间的重新启动请求延后执行，销毁时取消重启。

新增 Android 隔离回归：先观察缺少 stopCollection 入口的失败，再检查合成 HandlerThread 退出、60 秒排队任务取消、隔离状态 running=false，以及绑定 SDK 可用。禁止系统采集资源访问，未接入真实传感器。部署后回归通过；真实跨进程历史 SDK 检查通过（collection_started=false、帧数不变）。285 项 Python 测试通过（19.696 秒）。没有实测真实传感器采集期间停止/立即重启，也未把这项合成检查解释成完整生命周期验收。

证据：`artifacts/native_android_v1/native_bound_stop_pixel.json`、`native_bound_stop_sdk_pixel.json`、`native_bound_stop_build.json`。

### 2026-10-01：真实跨进程停止命令派发

SDK 返回 service_collection_stopped 元数据，便于区分未启动与显式停止。调试检查保持真实跨进程 Binder 绑定，通过与停止按钮相同的服务动作发送停止请求，再核对停止确认和历史预测。Pixel 6a 回执 passed=true、stop_command_acknowledged=true、collection_started=false、帧数不变，历史占用概率与本地计算一致，非法请求仍拒绝。客户端 PID 29222、服务 PID 29251，完成时间 1790864007048。

证据：`artifacts/native_android_v1/native_bound_stop_command_pixel.json` 和对应 build 回执。此测试没有启动真实传感器，不证明采集中停止/重启。另已识别初始化失败分支仍依赖 stopSelf：保持绑定时需要独立清理已申请资源，尚待故障注入回归与修复。

### 2026-10-01：初始化失败时释放采集线程

采集初始化失败分支现在先取消待重启请求并显式 stopCollection，再 stopSelf，避免绑定客户端存在时工作线程继续驻留。将数据库/传感器初始化主体提取为同类私有方法，供隔离故障注入复用，不改变正常初始化步骤。

Android 回归先观察合成工作线程未退出的失败（1790864181482）；修复后数据库打开故障确实触发，工作线程退出、延迟采样取消、隔离状态停止、历史 SDK 保持可用（1790864240205）。测试使用手动附加 Service，stopSelf 的 ActivityManager 边界未接入真实框架；其空指针只在测试包装层识别，资源释放断言独立检查。没有初始化真实传感器或写入真实历史数据库。正常合成停止与真实跨进程停止命令/历史 SDK 也通过，帧数不变。285 项 Python 测试通过（20.156 秒）。最新 APK 已部署。

证据：`artifacts/native_android_v1/native_bound_initialization_before_pixel.json`、`native_bound_initialization_after_pixel.json`、`native_bound_initialization_stop_pixel.json`、`native_bound_initialization_sdk_pixel.json`、对应 build 回执。此项不证明所有故障位置或真实采集中停止/重启已验收。

### 2026-10-01：只读夜间显示能力 SDK

CanonicalClient.nightDisplayCapabilities(IBinder) 使用事务 3，经已有 CANONICAL_INPUT 签名权限检查，返回 NightDisplay 能力/设置状态及服务进程信息。查询不启动采集、不申请写权限、不写设置；unset/null 和 permission_denied 与有效读取分开。能力表示服务账号的权限和当前设备资源，不表示调用方可改变显示。

Pixel 6a 客户端 PID26609/服务 PID26640 的真实 Binder 验证通过，资源与本地读取一致，collection_started=false、settings_writes=0。原生相关 15 项测试通过（5.042 秒）；新 APK 已部署。证据：artifacts/native_android_v1/night_display_sdk_pixel.json、night_display_sdk_build.json。色温写入及恢复尚未实现，不能据此宣称物理执行器已完成。

### 2026-10-01：受保护的色温调整实现

包内 NightDisplay.adjust 检查设备范围、用户默认关闭状态、私有静默文件与 WRITE_SECURE_SETTINGS。它只调整既有手动夜间显示，读回后短暂保持，再按未发生外部改变的条件恢复。色温、启用状态或自动模式改变时保留外部选择。receipt 的 settings_write_attempts 是尝试次数，delivered 仅表示设置读回，不表示光学测量或人体反应。模拟测试与实际未授权零写入检查已在 Pixel 6a 通过。此方法未作为 SDK 写事务开放；用户入口、所需权限声明/授权、策略接入和进程死亡恢复仍未完成。

### 2026-10-01：待恢复色温记录

包内调整在设置前保存并读回 AtomicFile 的原值/目标值记录；未完成的记录拒绝覆盖。NightDisplay.recover 显式处理已恢复、仍为本次目标、外部接管与权限丢失，始终不可作为新干预结果计奖。Pixel 私有临时文件重新读取和模拟恢复通过，未真实改变屏幕。自动恢复调用点、跨进程互斥及用户/权限/策略入口尚未接通，不能把该存储原语视为完成进程死亡恢复。

### 2026-10-01：色温操作锁

色温调整与恢复全程使用独立 color_temperature.lock；底层复用现有查询锁，公开对齐入口保持不变。主机 JVM 的跨进程拒绝和终止释放通过，Android 同进程调整/恢复重叠拒绝与释放后重入通过。锁文件不会与私有对齐查询争用；实际色温写入及 Android 跨进程操作尚未验收。

## 2026-10-01：屏幕色温恢复任务

调整前先保存 AtomicFile 待恢复记录，再注册持久化 JobScheduler 任务；注册失败不执行目标设置写入。任务在 `:edge` 进程调用互斥恢复路径，待恢复记录仍存在或发生异常时请求退避重试。停止回调使旧工作线程不能向已停止的任务报告完成。没有待恢复记录时不读写设置、不采集传感器、不计为干预或反馈。新增 RECEIVE_BOOT_COMPLETED 普通权限及 BIND_JOB_SERVICE 保护的服务；未授予设置写入权限。

Pixel 6a 安装成功。任务最初处于 waiting，随后通过系统测试命令强制调度；同一 nonce 的回执由不同进程产生，reason=no_pending_recovery、settings_write_attempts=0、retry=false。303 项测试通过（23.232 秒）。证据：`artifacts/native_android_v1/color_recovery_job_pixel.json`。这里只证明持久化任务注册和强制调度无操作路径；未验证自然调度、重启/断电、真实色温恢复或系统对恢复时限的保证。JobScheduler 为尽力调度，不保证 30 秒内运行。

## 2026-10-01：准备失败与外部设置竞争

复现：beforeAdjustment 保存记录后注册任务失败，同时外部把色温设为目标值；原清理逻辑仍写回原值。回归先失败，再增加本次写入尝试门槛，使准备失败路径不读写设置，保留待恢复记录。已经尝试设置但抛异常的路径仍执行条件恢复。回归通过；303 项测试通过（22.635 秒）。修复 APK 已安装 Pixel 6a，模拟/权限保护回执通过、实际设置写入 0 次；未验证真实设置变更。证据：`artifacts/native_android_v1/color_preparation_failure_pixel.json`。恢复 Job 异常目前仅写日志，失败回执持久化仍待补齐；消费者不得把旧成功回执视为新一次任务结果。

## 2026-10-01：恢复任务异常回执

真机调试注入在设置访问前抛 IOException，先复现新任务仍保留旧成功回执。修复后正常和异常结果均使用 AtomicFile 保存本次 nonce、进程、完成时间及 retry；恢复异常不计为干预/反馈，设置写入次数为未知 null，避免把可能部分执行的恢复误报为零。保存回执失败会请求重试并保留日志。

Pixel 6a 修复 APK 安装成功，强制调度后异常回执 nonce 与注册一致、服务和客户端进程不同、retry=true。303 项测试通过（23.123 秒）。异常注入仅限可调试应用、无待恢复记录检查入口；测试任务已取消，调度器确认找不到该任务。证据：`artifacts/native_android_v1/color_recovery_exception_pixel.json`。尚未验证真实设置异常、磁盘写失败、系统自然退避或重启恢复。读取回执仍必须核对本次任务标识；若持久化本身失败，旧文件可能保留。

## 2026-10-01：原生谱 JNI 对象与实际应用延迟

SDK 新增 AutoCloseable `FittedPeriodicSpectrum`，按 256 条批次复制合法输入拟合，再复用登记的原生对象查询。句柄为进程内单调 ID，查询拒绝未登记/关闭 ID，不暴露原始指针；注册表锁保护查询/关闭，Java 同步方法阻止关闭与自身查询竞争。调用方须用 try-with-resources 或明确 close；不提供 GC 自动回收，历史资格/模型/时区绑定与缓存管理仍由下一步接口负责。构建打包原生实现及 Java 类。

Pixel 6a APK 安装成功，完整 86400 条合成历史、16 状态、120 步 CDF 与精确核最大误差 7.772e-16；关闭包装器、已释放句柄、伪造句柄及非法拟合均拒绝，SDK jar 包含新类。309 项测试通过（26.047 秒）。应用内首次拟合 1562.659 ms，五次 CDF 46.489/50.753/44.313/44.026/45.350 ms，明确未通过 15 ms 门槛，与独立进程 4 ms 结果存在待定位差异。未因概率和生命周期通过而宣告性能通过；没有完整应用内存证据。

证据：`artifacts/native_android_v1/periodic_spectrum_sdk_pixel.json`。新 API 已部署真机，但生产 HistoryForecast 仍未选择此路径。下一步分别测量原生计算、JNI 复制及运行环境成本，确认差异来源后再接有资格和来源绑定的窗口缓存。所有输入为合成，不启动传感器或修改设置。

## 2026-10-01：JNI 分段墙钟及线程 CPU 计时

谱查询内部增加进程内对象最新查询计时，调试 fixture 保存锁等待、输入复制/分配、原生 CDF、输出复制、总墙钟及 CDF 段线程 CPU 时间；CPU 时钟不可用标 -1。接口只在包内使用，不显示于产品页面。计时不更改核公式或线程优先级。APK 已部署 Pixel 6a，18 项受影响原生测试通过（7.603 秒）。

同样整月输入的五次原生 CDF 墙钟 40.901–69.768 ms，线程 CPU 38.303–66.232 ms；锁等待 0.002–0.008 ms，输入分配/复制 0.481–1.759 ms，输出 0.446–1.106 ms。Java 查询总计 42.287–73.263 ms，仍未达 15 ms；概率最大差 7.772e-16，所有生命周期保护通过。原生计算占主导，JNI 复制或纯等待无法解释主要差距；尚未证明具体 CPU 调度/频率/编译原因，不能归因于未经观测的环境。证据 `artifacts/native_android_v1/periodic_spectrum_sdk_profile_pixel.json`。下一步核对线程 CPU 范围及共享库/独立二进制的计算代码。

## 2026-10-01：CPU 范围对照与应用线程变化

CDF 在共享库及独立二进制均为 1268 字节、317 条指令，助记符序列相同；apply_delta 均为 820 字节。只证明结构一致，不证明完整二进制、链接或环境等价。新增 debug fixture 自身线程 CPU 范围/优先级记录，不更改优先级或应用亲和性。测试线程开始 CPU 0–7、结束 CPU 0–3，优先级均 0；这证明范围在测试期间变化，尚未记录每条指令具体运行 CPU。

同一独立合成流式基准：默认预测 4.342 ms、CDF 3.391 ms；仅该测试进程以 taskset 0f 限制到 CPU 0–3 后预测 32.884 ms、CDF 30.445 ms，CDF 指纹一致。新应用回执查询 57.060–66.652 ms，原生墙钟 54.158–63.840 ms、线程 CPU 51.609–60.938 ms，仍未过 15 ms。CPU 范围是差距的有证据贡献因素，但受限独立进程仍快于应用，不能把全部差异归因于单一因素。没有观测频率或温度，不对此猜测；没有修改应用或系统设置。

诊断 APK 已部署 Pixel 6a，完整概率误差与句柄保护再次通过。证据 `artifacts/native_android_v1/periodic_spectrum_cpu_scope_pixel.json`。下一步在 CPU 0–3 条件下优化和复测，再核对真实 SDK 端到端限制；独立核心默认条件通过不能充当应用性能通过。

## 2026-10-01：采用 8 步共享矩载入

谱 CDF 每次处理最多 8 个预测步，共享每个频率的矩载入；每个步的频率累加顺序、全部 145 频率和核公式不变。最后不足 8 步只读取和写入有效步。主机 54 组状态/历史/长度组合输出逐字节一致；正式测试补充 1/7/8/9/20/120 步下追加、删除半数、清空后的精确 CDF 比较。309 项测试通过（27.212 秒）。

ARM 整月精确 CDF 最大差 7.772e-16，共享随机数模拟结果一致；CPU 0–3 两个测量顺序下原实现预测 33.073/31.676 ms，新实现 22.773/22.499 ms，诊断 CDF 指纹相同。采用修改，APK 已部署 Pixel 6a。实际 JNI 测试线程始终 CPU 0–3、优先级 0；五次查询 31.057/29.989/30.933/30.673/31.873 ms，原生 CDF 28.341–29.466 ms、线程 CPU 27.348–28.170 ms，复制/分配仍另外计时。完整概率及关闭/伪造/非法输入保护通过。首次拟合 1678.230 ms，与预测单列。

证据 `artifacts/native_android_v1/periodic_spectrum_step_block_pixel.json`。应用 15 ms 门槛仍未通过，完整应用内存和历史窗口缓存仍未验收；不能把独立核心或当前改善替代最终性能要求。

## 2026-10-01：服务拥有且来源绑定的谱拟合缓存

新增单快照 `SpectrumForecastCache`，Binder 预测对 ≤16 状态使用 order-18 谱路径；其余保留精确核。每次仍读取并核对当前 30 天窗口：缓存键覆盖所有选中载荷的长度前缀 SHA-256（包括排除记录），并绑定模型版本/摘要、归一化器摘要、时区和有序词表。边界、会话、源模式和资格来自核对后的 payload/连续性屏障；历史内容不同就重新拟合，同样内容的新查询时刻重新生成查询相位。原 source_sha256 仍只描述接受的记录，不改变其含义。拟合失配前关闭旧快照，失败时保持空缓存；同步查询/关闭，服务销毁关闭缓存。

内存 SQLite 合成 fixture 证明首次拟合、重复命中、零重复拟合时间、与精确模拟一致，排除 payload 变化（接受记录 source_sha256 不变）仍使缓存失效，时区失效及关闭后拒绝。实际只读 Binder 远程重复查询也命中，与本地精确参考占用概率一致；帧数未增加、未启动采集。APK 已安装 Pixel 6a，309 项测试通过（26.611 秒）；初次 host 编译因测试清单缺少新增 Java 类失败，补齐后受影响测试及全套通过。证据 `artifacts/native_android_v1/forecast_spectrum_cache_pixel.json`，仅保留聚合回执。

这是拟合复用，尚非增量窗口维护：每次仍扫描并核对全部选中历史，也构造临时核对象。合成 3 个合法转移的精确 CDF 7.015 ms，谱缓存重复 CDF 32.120 ms；实际历史 Binder 重复 CDF 26.216 ms。固定谱查询成本使小历史可能退化，15 ms 和整体端到端/内存门槛仍未过。下一步降低谱三角/矩查询固定成本，并测量按历史规模选择后端；不能以缓存命中宣告性能完成。谱结果在已验证误差范围内属于近似，report 显式标记 cdf_backend=cpp_order18_fitted_spectrum，eligible_for_intervention 仍为 false。

## 2026-10-01：频率三角递推，每 16 项重新计算

采用角度相加递推生成查询频率的 cos/sin，并每 16 项以直接三角函数重新锚定，仍使用全部 145 频率、相同权重与矩累加顺序。浮点结果有变化，不宣称逐位相同或提供完整机器误差界。主机 54 组与直接计算最大 CDF 差 9.992e-16；ARM 整月与精确核最大差 7.772e-16，共享随机数 300 模拟占用计数一致。

CPU 0–3 两种测量顺序：原实现总预测 22.640/22.692 ms，递推 21.240/21.377 ms，收益小但可复现，故采用。APK 已部署 Pixel 6a；应用五次 CDF 29.169/29.180/29.292/29.138/28.519 ms，原生计算 26.343–27.179 ms、线程 CPU 25.615–25.790 ms，仍未达 15 ms。首次拟合 1637.740 ms 单列；完整概率及关闭/非法句柄保护通过。309 项测试通过（31.056 秒）。实际 Binder 缓存复用、精确参考一致、不采集和帧数不变回归通过。

证据 `artifacts/native_android_v1/periodic_spectrum_recurrence_pixel.json`。三角生成改动只带来小幅收益，不能据此把原生时间全部归因于三角函数；后续仍需分解矩计算以及缓存侧相位/窗口核对成本。完整性能、窗口增量维护及整体应用内存仍未验收。

## 2026-10-01：按合法转移数量选择预测后端

独立顺序执行的 APK JNI 合成对照使用相同预计算相位、16 状态、120 步：1024 条时精确核 26.207–32.062 ms、拟合查询 39.602–43.867 ms；2048 条时精确核 48.582–62.478 ms、拟合查询 39.571–49.908 ms；4096 条时精确核 93.615–110.412 ms、拟合查询 40.754–44.052 ms。最大 CDF 差均低于 1e-12；首次拟合另计，不含历史读取。采用经验切换点：缓存存在、状态数 ≤16 且合法转移 ≥2048 才用谱路径，其余使用精确核。这不证明其他步数、设备或负载下的最优切换点。

SHA-256 十六进制输出改为字符查表，避免每个字节调用格式化器；全部 256 字节、前导零、空输入和 Python hashlib 对照测试通过，接受记录摘要不变。新增缓存键、相位及查询计时。优化前并发测量与优化后独立测量条件不同，不能把全部时间变化归因于格式化器。

最新 APK 已安装 Pixel 6a。合成 3 个合法转移使用精确核；2057 个合法转移验证重复命中、零重复拟合、精确模拟一致、排除 payload 变化失效、时区失效与关闭拒绝。重复 CDF 构建 18.984 ms（键 6.152 ms、相位 1.835 ms、查询 9.134 ms），仍超过 15 ms，且不含历史扫描。只读远程 Binder 返回后端策略通过、精确占用一致、帧数不变及未启动采集；小历史重复 CDF 3.867 ms，cache_hit=false 属预期。310 项测试通过（26.681 秒），diff 检查通过。首次读取了旧回执，发现时间戳未更新后重新运行并等待新回执；旧回执未用于当前验收。

证据 `artifacts/native_android_v1/forecast_adaptive_backend_pixel.json` 绑定当前 APK 与相关来源，并将早先独立后端对照标记为旧版本证据。缓存仍全窗口扫描；整月应用性能、完整应用内存及其余阶段要求未验收，尚未提交或推送。

## 2026-10-01：采用 16 步共享谱矩载入

在临时副本中顺序测试 8/16/32，再逆序 32/16/8，固定独立测试进程 CPU 0–3。8 步总预测 21.307/21.162 ms；16 步 17.241/17.589 ms；32 步 18.802/18.755 ms。各次 CDF 指纹相同，质量守恒，独立子进程峰值 RSS 约 2.92–3.07 MB；不代表整个应用内存。首次探针因未打包共享 C++ 运行时无法启动，改为静态链接后产生上述测量，失败启动没有性能结果。

采用 16 步分块，全部 145 频率与各步累加顺序保留，栈上的相位数组增加；未采用 32 步。先补充 15/16/17/31/32/33 步边界测试并在原实现通过，再改实现；追加/删除半数/清空的精确核对照继续通过。310 项测试通过（26.064 秒），diff 检查通过。

新 APK 已安装 Pixel 6a。整月 86400 合成转移、16 状态、120 步查询耗时 21.165/18.722/21.536/22.210/21.155 ms；最大 CDF 差 7.772e-16，关闭包装器及已释放/伪造/非法拟合保护通过。首次拟合 1693.949 ms 单列。线程允许 CPU 从 0–7 变为 0–3，优先级始终 0；没有观测频率或逐指令执行 CPU，不把改善全部归因于某一环境因素。

证据 `artifacts/native_android_v1/periodic_spectrum_block16_pixel.json` 绑定源码、测试与 APK。应用查询仍未达 15 ms，且不含历史扫描、完整预测和应用内存验收；全部阶段未完成，未提交或推送。

## 2026-10-02：短相位窗口的局部多项式谱求值

全部 145 个拟合频率保留；当 horizon≥64 且各周相位到圆周中点的最短差均≤0.02 时，用 18 阶局部 Taylor 系数与 Horner 求值代替逐步谱矩点积。超范围及短窗口继续使用 16 步频率求和。依据指数幂级数（https://dlmf.nist.gov/4.2#E19），推导并计算条件精确算术余项：epsilon=4 sum a_f(f*0.02)^19/19!≈5.172e-21；若矩为实际记录的理想和，N≤1000000，归一化行 L1 界 2N epsilon/(5+N exp(-8))≤3.039e-17。此计算不包括谱截断、浮点系数/矩累积、三角函数/相位及 Horner 舍入，不能宣称完整机器误差界。

临时 32 阶探针 CPU 0–3 总预测 15.322/15.735 ms，对照 16 步 16.974/17.281 ms；18 阶降为 10.526/10.401 ms、CDF 8.044/7.961 ms。独立子进程 RSS 约 2.97 MB，首次拟合约 1.2 秒及更新另计。结果指纹发生变化，不宣称逐位一致。ARM 整月历史十二组跨周/相位半径内外对照最大 CDF 差 8.882e-16，共同随机数 300 次占用计数一致。

新增 63/64/65 步及跨周半径边界测试，先在原实现通过；正式修改后 312 项测试通过（27.225 秒）。APK 已安装真机，整月精确核误差 7.772e-16，生命周期保护和只读 Binder 策略/概率一致、帧数不变及不启动采集通过。应用五次查询 19.745/17.218/19.638/19.121/23.611 ms，线程始终允许 CPU 0–3，首次拟合 2449.925 ms 单列；没有温度/频率观测，未证明相比上一版应用性能稳定改善。

证据 `artifacts/native_android_v1/periodic_spectrum_local_polynomial_pixel.json` 与对应 `.patch`。独立核心受限 CPU 合成测量低于 15 ms，但实际应用查询仍超过门槛，完整历史扫描/预测与应用内存未验收；整体未完成，未提交或推送。

## 2026-10-02：旋转谱矩复用与完整 APK 计算链计时

每条边先将 cosine/sine 矩旋转到查询中点，偶数/奇数阶分别复用对应矩；各频率只旋转一次，系数构建由双乘加改为单项乘法。全部频率、18 阶、半径与回退规则保持。浮点结合顺序变化，不宣称逐位一致。CPU 0–3 两种顺序的独立整月预测：原局部展开 10.283/10.502 ms，新实现 9.241/9.223 ms；十二组边界精确 CDF 最大差 8.882e-16，共同随机数占用计数一致。采用修改，312 项测试通过（25.887 秒），APK 安装成功。

第一次 APK 整月 CDF 五次 11.069/11.767/12.944/10.193/10.226 ms；不能以此宣告完整目标通过。随后新增调试夹具，在同一计时段调用 128D normalize、16 节点 quantize、拟合 CDF 和 300×120 rollouts，包括 JNI 分配/复制；随机数、相位和首次拟合预先完成，明确排除历史扫描。新 APK 安装后测得完整计算链 26.254/23.120/27.103/22.859/25.709 ms，全部质量守恒及误差/生命周期检查通过，性能仍失败。本轮 CDF 14.268–16.835 ms、首次拟合 2447.061 ms；两次线程允许范围均从 CPU 0–7 到 0–3，未测频率和温度，不推测波动原因。

证据 `artifacts/native_android_v1/periodic_spectrum_rotated_pipeline_pixel.json` 绑定最新源码、夹具和 APK，保留先前较快 CDF 与后续完整链结果。全套测试是在只增加调试计时夹具前完成，夹具之后编译和设备实测通过；不将调试源码变更表述为再次全套通过。15 ms、完整应用内存、历史增量维护与其余阶段事项仍未验收，整体未完成，未提交或推送。

## 2026-10-02：推演查找优化与计算链分阶段计时

调试夹具拆分 normalize/quantize/CDF/rollouts 的入口到返回计时；第一次分解显示前两项约 0.4–1.7 ms、CDF 13–18 ms、rollouts 6.7–9.1 ms，各项包含其 JNI 成本，不能等同纯核计算。

16 状态采用固定四次 CDF 比较，<= 保留 upper_bound 对精确边界及重复 CDF 的行为，最大返回 15 保留原尾部夹紧；其余状态数继续通用查找。新增 15/16/17 状态下边界前后 nextafter、重复值、零概率及允许的近 1 尾部测试，先在原实现通过，再修改正式核。CPU 0–3 独立两种顺序对照：通用 rollout 2.259/2.260 ms，新路径 1.996/1.958 ms，总预测 9.180/9.247→8.969/9.024 ms，CDF 指纹保持，收益小且可复现。313 项测试通过（26.478 秒），APK 安装真机。

新 APK 完整合成链 18.869/16.398/15.996/16.656/16.534 ms，全部仍超过 15 ms；CDF 11.336–11.963 ms，推演入口 4.560–5.448 ms。归一化和量化仍另列，质量守恒、精确 CDF 最大差 8.882e-16及生命周期保护通过。线程允许 CPU 始终 0–3；首次拟合 1674.701 ms 单列，未观测频率/温度，不能把前后应用全部改善归因于 ~0.3 ms 的查找收益。

证据 `artifacts/native_android_v1/periodic_pipeline_search_pixel.json` 绑定当前核、测试、夹具与 APK。下一步直接比较保留原生 CDF 的融合推演入口以测量跨接口复制成本；历史扫描、完整应用内存、完整阶段验收仍未完成，尚未提交或推送。

## 2026-10-02：SDK 融合谱查询与推演入口

新增 FittedPeriodicSpectrum.rollouts(prior,queryPhases,runs,initial,draws)，CDF 保留在原生缓冲区，直接调用现有 traceform_rollouts，仅返回占用 long[]。Java owner 同步查询/关闭，原生注册表锁保护句柄查找到计算结束；1..16 状态、1..2880 步、1..10000 runs、初态及长度先验证，prior/phase/draw 数值由已有核心校验；bad_alloc 映射 OOM。原 cdf API 保留，HistoryForecast/缓存服务尚未切换。

SDK 夹具对比相同随机数下融合与分离路径占用计数逐项一致，检查关闭 owner、伪造句柄、draw 数量错、NaN draw 和单步空 draws。APK 已安装真机，精确 CDF 最大差 8.882e-16，其他生命周期保护继续通过。313 项测试通过（25.379 秒）；融合 JNI 执行验证依赖设备夹具，本地主机测试不代表 Android JNI 执行覆盖。

五次完整合成计算链：分离 25.100/22.652/21.979/22.061/21.610 ms；融合 20.721/21.688/22.280/21.484/20.195 ms。每轮先分离再融合，没有随机顺序，不能宣称已证明稳定或显著收益；一个对应试次融合较慢。首次拟合 2441.919 ms 单列，线程允许范围从 CPU 0–7 到 0–3，没有频率/温度观测。所有完整链仍高于 15 ms，融合未解决整体性能门槛。

证据 `artifacts/native_android_v1/periodic_spectrum_fused_pixel.json` 绑定源码与 APK。新 SDK 提供减少 CDF 往返复制的入口，但服务仍保留已验证路径；后续继续定位系数构建与推演复制成本。完整性能、完整应用内存及其余阶段事项未验收，整体未完成，未提交或推送。

## 2026-10-02：模型拥有的局部展开系数复用

谱快照增加 states²×19 个 double 的局部系数与中心/有效标记（16 状态增加 38912 字节，摘要总计 635016 字节）。合法 apply_delta 增删使其失效；非法 delta 不改变模型。查询先核对全部 canonical 相位，只有所有点到既有中心的圆周距离≤0.02且 horizon≥64 时复用，否则尝试新中心重建或走频率回退。先验不缓存，每次重新添加并归一化。C++ cdf 从 const 改为可更新缓存，需要调用方串行；JNI 的既有注册表互斥锁覆盖查询、delta/拟合拥有关系与关闭，Java owner 同步保持。

新增同一摘要上的近邻相位/越界相位、先验变化及删除/清空之后的精确 CDF 对照，原实现先通过再接入缓存。正式实现 313 项测试通过（26.221 秒）。临时探针一次编译因基准引用旧 const 头文件而链接失败，复制基准到匹配头文件目录后编译运行成功，没有运行不匹配二进制。第一次基准元数据插入改变输出索引使测试失败，移到末尾保持既有顺序后全套通过。没有将这些失败当运行性能数据。

当前源码独立基准 CPU 0–3：首次未计入暖查询的完整预热链 10.039 ms，复用后预测 5.916 ms（CDF 3.717 ms、rollout 1.959 ms），摘要 635016 字节、子进程 RSS 2908160 字节；首次拟合 1192.128 ms、1000 次增删 25.100 ms 单列。新增 warmup 字段明确首次系数构建不在暖查询计时内。十二组 ARM 边界合成对照最大差 8.882e-16，共同随机数占用一致。

新 APK 已安装真机：首次 CDF 17.823 ms，四次重复 9.876/9.278/10.213/10.237 ms。分离完整链 16.115–19.766 ms；融合暖链 15.067/14.157/13.883/14.381/15.245 ms，仍有两次超过 15 ms，不能宣告门槛通过。首次拟合 1744.105 ms 单列；线程始终允许 CPU 0–3，生命周期、draw 保护与融合输出一致通过。证据 `artifacts/native_android_v1/periodic_spectrum_query_cache_pixel.json` 绑定来源与 APK。完整应用内存、首次/失配成本、全窗口扫描及其余阶段仍未验收；未提交或推送。

## 2026-10-02：8 步并行 Horner 与首次融合复测

局部多项式每次求值最多 8 个预测步，各步的 Horner 顺序及次数保持，复用每阶系数载入；最后不足 8 步只读写有效输入和输出。先在原实现补充 127/128/129/132 步尾部测试并通过，采用后 313 项测试通过（25.807 秒），之后仅新增首次查询调试夹具，重新构建和设备实测通过。独立 CPU 0–3 两种顺序：暖完整预测 5.960/5.956→4.000/4.095 ms，首次预热完整链 9.873/9.496→8.096/8.253 ms，CDF 指纹相同，质量守恒。不把单一指纹当通用逐位证明。

新增 fresh snapshot fixture：先单列拟合，再计时 normalize→quantize→融合 CDF/300×120 rollouts 的第一次查询，与精确 CDF 共同随机数占用逐项对照。APK 已安装真机，三轮独立夹具均通过误差、占用及保护检查；首次融合完整链分别 13.441/11.797/18.128 ms，第三轮超过 15 ms。每轮五次暖融合链均低于 15 ms（合计范围 6.789–14.841 ms），不能以暖查询或前两轮掩盖首次失败。拟合、SQL 历史读取、相位/随机数准备单列或排除；未观测频率和温度，不推测波动来源。

证据 `artifacts/native_android_v1/periodic_spectrum_horner_block_pixel.json` 绑定当前来源与 APK，保留三轮全部回执。暖计算链得到改善，首次/失配系数构建仍需优化，完整应用内存及其余阶段要求未验收。历史服务仍使用分离入口；未提交或推送。


## 2026-10-02：历史服务接入融合推演与完整计时边界

SpectrumForecastCache 现在调用 FittedPeriodicSpectrum.rollouts，HistoryForecast 的合格大窗口直接接收占用 long[]，避免 CDF 返回 Java 后再次送回原生。小窗口保留精确路径，历史绑定、全载荷键、内容/时区失效、关闭保护和共同随机数保持。融合路径 cdf_build_ms/native_rollout_ms 为 null，不能分别计时；spectrum_query_ms 包含 CDF 与 rollouts 及 JNI，新增 rollout_backend/spectrum_query_scope。两条路径都报告 forecast_compute_ms（键、拟合、相位、CDF、推演），另列 history_scan_ms、kernel_build_ms、draw_preparation_ms；概率序列化未计入 compute。内部调试调用方改读新的完整计算字段，消费旧计时字段的客户端须按 rollout_backend 处理 null。

313 项本地测试通过（26.876 秒），APK 已安装。新合成 SQLite 回执 1790881940052 通过融合占用与精确参考一致、单步空 draws/新查询时刻、复用无重拟合、排除 payload/时区失效、关闭拒绝和历史不变。2057 个合法转移的重复查询：扫描/解析 10307.778 ms，核构建 1458.523 ms，随机数准备 399.256 ms，键 5.676 ms、相位 1.591 ms、融合查询 10.724 ms，计算合计 19.668 ms。完整历史性能仍失败，结果改变下一步重点：减少重复历史解析和核重建，而非继续仅优化暖 CDF。

只读真实跨进程 Binder 回执 1790881996515 通过，客户端 PID 28464、服务 PID 28493，未启动采集，帧数不变，非法请求拒绝，历史占用与本地精确参考一致。保留的小历史选择精确核，重复计算 13.385 ms；这不证明大窗口融合 Binder 性能。证据 artifacts/native_android_v1/history_forecast_fused_pixel.json 绑定当前来源/APK，保留合成和 Binder 结果。完整内存、历史增量维护及其余阶段仍未完成，未提交或推送。


## 2026-10-02：同一已核对历史的核对象复用

服务缓存增加一个 PeriodicTransitionKernel 与绑定键。每次查询仍解析并核对全部选中历史，键包含全载荷摘要（含排除记录）、模型版本/摘要、归一化器摘要、时区和有序词表；完全相同才复用不可外部修改的核，相位仍按本次查询时刻生成。内容/时区变化重建，关闭同时清空核引用。新增 kernel_cache_hit/history_key_ms；键构建提前到核查找前，forecast_compute_ms 的 scope 明确排除 history_key_ms，spectrum_key_ms 保留为同一值的兼容诊断字段，不能把两个键计时相加。核计时仍含临时数组复制，不等同纯查找。

313 项测试通过（26.628 秒），APK 安装成功。合成回执 1790882324637 验证重复命中、排除载荷/时区失效、新查询时刻、单步预测、精确占用一致、历史不变和关闭拒绝。2057 合法转移：重复核构建 21.628 ms（上轮 1458.523 ms）、融合查询 6.157 ms、计算 8.879 ms，历史扫描/解析仍 10358.730 ms，随机数准备 358.186 ms、键 2.809 ms；跨轮环境未控制，不把计算差异全部归因核缓存。完整历史性能仍失败。

只读跨进程回执 1790882368280 通过，服务 PID 30547/客户端 30510，未启动采集、帧数不变、非法请求拒绝，保留的小历史精确核输出一致，重复计算 14.638 ms；不代表大窗口融合 Binder 验收。证据 artifacts/native_android_v1/history_kernel_cache_pixel.json 绑定当前源码/APK。核保留历史相位和转移数组，完整内存未验收；重复 JSON 解析、增量窗口和其余阶段继续推进，未提交或推送。


## 2026-10-02：按载荷摘要复用 Warm 历史解析字段

缓存按当前窗口 bucket 保存 SHA-256、载荷内时间戳、词表索引、段身份和原始连续性标记，不保存原始 JSON。每次仍读取全部选中行并重新计算其载荷 SHA-256；相同模型版本/摘要、归一化器摘要、有序词表及相同载荷摘要才复用解析字段。SQLite ts 与解析时间戳每次核对，排除记录和当前行序连续性屏障仍重新处理。成功扫描后整体替换映射，窗口外条目移除；失败扫描不发布半成品，close 清空引用。source_sha256 长度前缀格式保持；不把该缓存称为增量 SQLite 窗口。

313 项测试通过（26.839 秒），随后只强化调试夹具的测试顺序：把窗口清理放在模型绑定失效之前，防止后者掩盖缓存未清理。再次构建安装后回执 1790882873929 通过，2060 行首次解析、重复全部复用，排除载荷变化仅 1 行重新解析；缓存命中后 SQLite ts 单独变化仍拒绝，载荷改成畸形 JSON 仍拒绝，删除重建核，空窗口清理与模型绑定变化分别验证，精确占用和只读约束保持。时间戳检查的异常或畸形内容没有被缓存遮蔽。

首轮（强化测试顺序前，生产实现相同）扫描/解析 572.302 ms、计算 9.031 ms；最终轮 828.841 ms、计算 14.494 ms，核复制/查找 35.551 ms、随机数准备 402.796 ms、键 4.943 ms、融合查询 10.999 ms。上一轮全量解析 10358.730 ms，改善主要目标得到支持，但跨轮环境未控制且完整历史性能仍未验收。typed map 增加 Java 堆占用，完整 5 MB 内存要求未验证，不能以解析提速代替内存验收。

只读跨进程回执 1790882934350 通过（服务 PID 1442/客户端 1413），未启动采集、帧数不变、非法请求拒绝，小历史精确输出一致，重复计算 12.363 ms。证据 artifacts/native_android_v1/history_parsed_rows_cache_pixel.json 绑定最终来源/APK；首轮 APK 摘要未记录，仅作为明确标记的初步对照。随机数准备、窗口全量扫描、缓存内存和其余阶段继续推进，未提交或推送。


## 2026-10-02：保持 Java Random 序列的原生随机数准备

HistoryForecast 改用包内 NativeCore.randomDraws(seed,count)。JNI 使用 48 位 LCG（初态 XOR 0x5deece66d、乘数同值、加数 0xb、48 位掩码），按 nextDouble 的 26+27 位组合乘 2^-53；无符号整数计算保持负 seed 的低位语义。count 限制 0..1000000，NewDoubleArray 失败保持 JVM 异常，256 个 double 的栈块生成后复制，避免额外完整原生数组；复制异常立即返回。原有 supplied-draw rollout API 保持。

313 项本地测试通过（27.205 秒），之后仅调整调试计时在 JSONObject 构建之前捕获，重新构建安装通过。设备回执 1790883223572 对 7 个 seed（0/1/-1/42/Long.MIN/MAX/混合位）及 0/1/255/256/257/35700 长度全部逐位等于 java.util.Random.nextDouble，非法 count 拒绝；原生 JNI 覆盖由真机提供，本地 Python 测试不代表 JNI 执行。历史占用与精确参考、解析/核缓存失效、时间戳/畸形内容拒绝及独立窗口清理继续通过。

独立暖生成 35700 个随机数 3.457 ms；历史重复随机数准备 1.897 ms（上一版 Java 路径 402.796 ms），两次测量时点不同，不声称所有负载固定耗时。历史扫描 546.540 ms、核复制/查找 33.484 ms、键 4.941 ms、融合查询 8.640 ms、计算 11.566 ms；扫描、核和随机数准备未计入 compute，完整历史性能与首次查询仍未验收。只读跨进程回执 1790883304554 通过（服务 PID 4413/客户端 4384），未启动采集、帧数不变、非法请求拒绝，小历史精确输出一致，重复计算 5.617 ms。

证据 artifacts/native_android_v1/history_native_random_pixel.json 绑定当前源码/APK。缓存额外堆占用、完整内存、窗口全量扫描和其余阶段继续推进；未提交或推送。


## 2026-10-02：窗口内段身份字符串共享

先加入真机引用计数回归，原实现回执 1790883515370 明确失败（duplicate retained segment identity strings），再修改 HistoryForecast。当前查询用局部 Map 按完整字符串内容选择一个共享引用，相同内容的 ParsedRow 改为引用该身份；时间戳、载荷摘要、词表索引和连续性标记保持。每次查询独立池，没有全局 intern 或跨窗口驻留。不同身份保持不同内容，SHA 源绑定格式不变。

313 项测试通过（26.718 秒），APK 已安装。新回执 1790883655574 通过先前失败的检查及全部历史、随机序列、生命周期和失效回归。7 行小窗口（6 合法行）保留两个不同会话对象，共享字符总数 461，逐行字符总数 1382；2060 行窗口（2059 合法行）共享一个对象，保留字符数 237，逐行字符数 487983。检查使用 IdentityHashMap 区分真实对象引用和字符串相等，证明消除重复引用；不是堆字节或 RSS 测量，也不证明满月/完整 5 MB 门槛。

重复扫描 828.439 ms、核复制/查找 32.874 ms、随机准备 1.689 ms、键 4.607 ms、融合查询 9.295 ms、计算 12.442 ms。只读跨进程回执 1790883725064 通过（服务 PID 6830/客户端 6783），未启动采集、帧数不变、非法请求拒绝，小历史精确输出一致，重复计算 6.403 ms。证据 artifacts/native_android_v1/history_segment_sharing_pixel.json 绑定当前源码/APK并保留失败基线。行对象、摘要、映射和核数组仍占用内存，满月保留堆/完整内存、窗口扫描及其余阶段继续推进；未提交或推送。


## 2026-10-02：原生拓扑 CSD 与滞留诊断接入服务

新增纯 Java CriticalSlowingDetector：300 帧固定坐标窗口、线性去趋势、u/v 残差方差、投影残差 lag-1 相关、300 次诊断历史的样本标准差，当前值纳入历史，与 Python 现有检测规则一致；20 帧开始计算，10 次诊断后标准化，阈值 2.5。非有限坐标拒绝，continuous=false 重置窗口/基线/滞留，token 变化重置滞留。诊断基于拓扑坐标，不能直接解释为人体风险或干预效果。

ModelEngine 从模型读取 grid_coord 并随量化返回，构建夹具新增 Python 参考网格坐标，真机 8 组 canonical vector/速度的映射和量化通过（最大误差 1.819e-12）。EdgeService 的采集代码追加 dwell_steps、csd 指标与 csd_dwell_gate（预警且 dwell>30），采样异常及停止时重置检测器；eligible_for_intervention 保持 false，尚未接入蒙特卡洛反事实比较、情境策略和执行器授权链。

新增主机测试用相同 700 帧合成坐标序列对照现有 Python EdgeManifoldDaemon，方差/lag-1 误差≤1e-10，预警、滞留计数逐项一致，覆盖 300 帧窗口滚动、长间隔重置、正例和无效输入；全套 314 项通过（27.556 秒）。APK 已部署，合成 Android 回执 1790884312480 通过 801 帧诊断、线性趋势去除、间隔/token 重置和网格映射，collection_started=false、actuator_calls=0。合成坐标测试不代表真实模型一致的人类轨迹或物理闭环验收。

合成服务停止/资源释放回执 1790884351673 和数据库打开失败释放回执 1790884354435 通过；真实只读跨进程 SDK 回执 1790884360276 通过（服务 PID 10166/客户端 10036），帧数不变、未启动采集、非法请求拒绝，小历史精确输出一致。没有运行真实传感器采样来验证新增字段的端到端记录。证据 artifacts/native_android_v1/native_csd_pixel.json 绑定当前源码/APK。原生闭环门控、反馈、执行器、完整内存/性能及其余阶段仍未完成；未提交或推送。


## 2026-10-02：独立原生 CLAMP_DWELL 双分支与严格 5% 阈值

新增有界 C++/JNI 双分支占用计算，基线保持原 rollout，情景达到 max_dwell_steps 后从全局非自身分布抽样，无非自身转移时均匀选择其他词表状态。每步消费一个 supplied draw；单状态、单步空 draw、无效 draw 和非法边界覆盖。主机测试对照 Python 相同 supplied draws，显式对齐 CDF 状态顺序；Python Random.choice 与原生浮点抽样不承诺同 seed 逐路径一致。该强制离开情景不是物理执行器因果效应。

纯 Java 比较每步 JSD、峰值、终端选定风险集合净变化，校验两分支质量守恒。回归先复现恰好 5% 因浮点减法误推荐，再以整数占用计数差修复 Java/Python；5% 不推荐、6% 推荐。全套 317 项通过（26.415 秒）。APK 已安装，真机合成回执 1790885100656 通过每分支 300 条/12 步、基线一致、clamp fallback、单步及非法输入；原生计算 0.673 ms，未计 CDF/随机数准备/指标/历史，不代表完整 120 步性能验收。未开始采集，执行器调用为 0。

Python 源码和回归测试经差异核对、备份、原子替换同步手机。首次 14 项中两项因 Termux 缺少 UTC 时区数据失败；指定已有 Debian usr/share/zoneinfo 后 14 项通过（2.978 秒），未安装依赖。更新前后十二项冻结栈校验全部通过；长期运行解释器没有重启，新模块在下次加载生效。

证据 artifacts/native_android_v1/native_counterfactual_pixel.json 绑定当前来源/APK。独立 MC 组件尚未接入 EdgeService CSD/滞留门控、历史预测、情境反馈及执行器授权链；完整内存、冷启动性能、科学验证和其余阶段仍未完成。未提交或推送。


## 2026-10-02：历史反事实只读 Binder SDK

CanonicalClient.counterfactual 新增签名权限保护的事务 4：时间/步长/步数/运行数/seed、正 maxDwell 和显式选定状态集合。服务拒绝未知/重复状态，复用现有历史来源摘要、模型/归一化器绑定、连续性排除与新鲜度规则。单分支事务 2 保持既有后端；双分支使用同一精确 Von Mises CDF 和全局先验，基线 seed、情景 seed+1，返回基线/情景占用、JSD 和终端集合净变化。eligible_for_intervention 始终 false，返回模型强制离开情景标记；显式状态集合不构成人体风险或执行器授权。大历史双分支精确核速度尚未验收。

本地 317 项通过（27.669 秒），APK 已安装。合成历史回执 1790885818064 验证基线占用等于原精确预测、单步比较中性、120 步情景完整、SQLite 行数不变，既有缓存关闭/失效回归保持。设备初次休眠锁屏导致回执未更新，旧回执不作为本次证据；唤醒后按完成时间确认新结果。

跨进程回执 1790885876944 通过（服务 PID 21547、客户端 PID 21502）：保留历史反事实与同来源本地比较逐项一致，未知/重复比较状态拒绝，帧数不变，未启动采集。部署前后十二项冻结栈通过。证据 artifacts/native_android_v1/history_counterfactual_sdk_pixel.json 绑定当前源码/APK/SDK。SDK 原生只读反事实已接通，但自动 CSD→MC→情境反馈→执行器授权链、完整性能/内存与其余阶段仍未完成；未提交或推送。


## 2026-10-02：CSD/滞留自动触发原生影子比较

EdgeService 在帧事务提交后调用单任务后台比较：连续帧、CSD tipping、dwell>30，且服务未停止/销毁才接受；比较使用 Warm 来源绑定历史、当前 token 集合、CLAMP_DWELL 10 步、120 步/300 条、30 秒步长。此 token 集合只表达模型情景。计算不阻塞采样线程；15 分钟单调时钟冷却从接受尝试开始，即使失败也保留。token 改变、连续性中断、门控失效、采样异常、停止及销毁使未发布工作失效；不发布旧结果。销毁关闭执行线程，失败记录日志或不可用回执，不授予政策信用。

影子回执保留摘要和比较指标，移除两分支完整占用数组；eligible_for_intervention=false、actuator_calls=0。不调用执行器。冷却目前仅存服务内存，重启持久化、情境策略、试验账本和延迟反馈尚未接入。

本地 318 项通过（27.368 秒），新增实际后台任务测试覆盖单任务、15 分钟边界、失效/关闭后禁止发布。APK 已安装，合成服务回执 1790886217814 使用独立 40 行 SQLite，禁止系统物理资源访问；验证不连续/30 步/未达 CSD 阈值不触发，31 步预警触发，重复冷却、历史不变和影子权限边界。合成强制离开显示风险变化 -0.9633，仅是数值情景，不支持实际干预效果。

服务停止回执 1790886233905 通过；只读跨进程回执 1790886259928 通过（服务 PID 25384、客户端 PID 25355），历史反事实一致、非法请求拒绝、未启动采集、帧数不变。部署前后十二项冻结校验通过。证据 artifacts/native_android_v1/native_shadow_comparison_pixel.json 绑定当前源码/APK。未运行真实传感器采集触发链；完整闭环、性能/内存与其余阶段继续推进，未提交或推送。


## 2026-10-02：自动影子比较绑定精确触发帧

Warm 每桶保存最新观测，后台查询指定 trigger as-of 时刻；后续采样覆盖同桶后，查询可能只能取得较旧末帧。先加入真机回归，失败回执 1790886395475：older initial history accepted as trigger frame。修复要求可用结果的 history_end_epoch_ms 精确等于触发时刻且 initial_state 等于触发 token；否则 available=false、reason=trigger_history_binding_mismatch，并移除 counterfactual 和完整占用后才发布。

修复已部署，回执 1790886446837 同时通过正常绑定比较、旧一秒末帧拒绝、门控/冷却/历史不变回归；未采集、执行器调用 0。单任务调度主机测试通过（1 项，0.443 秒），部署前后十二项冻结校验通过。证据 artifacts/native_android_v1/shadow_trigger_binding_pixel.json 保留失败基线和当前源码/APK绑定。检查发生在计算后，未保存独立触发快照，桶覆盖仍可能造成不可用尝试；不将较旧预测当作触发帧结果。其余阶段继续推进，未提交或推送。


## 2026-10-02：原生情境策略与持久影子试验账本

新增 ContextualPolicy：none 固定 25%，四个候选 pulse/notification/volume_duck/color_temperature 平分 15% 探索概率，(reward+1)/(n+2) 最大者额外 60%，平分时按声明顺序选择；记录实际动作概率。疲劳冷却 15/30/60 分钟，最多三次最近非 none 已评估结果中连续零奖励决定，最新成功重置。纯策略无执行器调用；色温为原生候选扩展，不宣称现有 Python 已支持该候选。

ShadowTrialLedger 独立 intervention_trials.sqlite 原子记录 id、情境、动作/倾向、创建时刻、最小来源绑定帧、shadow 状态和概率回执；读取已有策略聚合，shadow 不赋予奖励信用。情境含模型版本、模型/归一化器摘要、token、CSD 事件和本地六小时相位。账本重开后全局 15 分钟冷却保持，另有情境疲劳冷却；时钟倒退保守拒绝。旧终态试验 30 天清理，聚合保留。计算失败或未达推荐阈值不创建 trial。后台发布前才记录，失效结果不写 trial；影子比较回执附 policy。原单任务尝试冷却仍为内存状态，持久冷却覆盖已记录 proposal。

本地 319 项通过（28.437 秒），概率/抽样边界、随机对照、经验优选、无效输入和疲劳上限覆盖；其后仅将模型摘要加入情境键并更新 Android 夹具，重新构建真机验证。最终 APK 已部署：策略回执 1790886893175 通过重开冷却 899999/900000 ms 边界、疲劳/成功重置、预置统计选优和 shadow 无信用。统计为合成人工预置，不能作为真实反馈学习证据。

自动服务回执 1790886898848 验证 CSD→来源绑定 MC→shadow trial 链（1 条 trial、0 条 policy），门控、冷却、历史不变和旧触发来源拒绝保持；无采集、执行器调用 0。部署前后十二项冻结校验通过。证据 artifacts/native_android_v1/native_shadow_policy_pixel.json 绑定最终源码/APK。延迟反馈、执行器授权与实际效果、完整性能/内存、长期存储和其余阶段仍未完成，未提交或推送。


## 2026-10-02：原生延迟反馈窗口与原子奖励更新

FeedbackWindow 使用原始/上一/当前来源身份（模型版本及摘要、归一化器摘要、session、source_mode）、墙上时间和 elapsed monotonic 时间。10 分钟后 token 是否不同作为 0/1 观测关联奖励；15 分钟之后删失，边界 900000 ms 保留。continuous=false、来源变化、非正间隔、monotonic gap>5 分钟、墙上时间倒退或累计墙上/单调时间偏差>1 秒均删失。首次观察也从原始帧检查间隔，较 Python 原有第一帧无 previous 时更保守，避免单个十分钟末帧掩盖期间缺测。

NativeFeedbackLedger 仅处理 observing 且 delivered/eligible_for_outcome 严格 JSON true 的试验；失败/不合格回执删失。原子事务更新 outcome/status 和情境 policy 聚合；终态不再处理，避免重复信用。等待期仅保存最小绑定上一帧，原始交付回执保持。不存在账本时不创建文件；采样提交后观察已有账本，初始化采集时将 queued/observing 标为 censored_restart。生产提案保持 shadow，不进入奖励更新；真实 observing 授权交付链尚未接通。

本地 320 项通过（28.351 秒），纯反馈测试覆盖十分钟边界、15 分钟边界、来源/连续性/时钟删失、未观测长间隔和奖励值。APK 已安装，合成 SQLite 回执 1790887302075 验证失败交付/来源变化无信用、十分钟一次性更新（n=1,reward=1）、重复不更新、重启删失与影子不赋奖。observing 状态及交付回执均为夹具显式合成，未调用执行器或采集真实人类反馈。

自动影子链回执 1790887308890、服务停止回执 1790887335800 通过；部署前后十二项冻结校验通过。证据 artifacts/native_android_v1/native_feedback_pixel.json 绑定当前来源/APK。增加的逐帧 SQLite 反馈处理尚未实测持续 CPU/内存；实际执行器授权/交付/恢复链和其余阶段继续推进，未提交或推送。


## 2026-10-02：原生执行器入口、音量恢复日志与关闭保护

NativeMicroActuator 提供 none/pulse/notification/volume_duck/color_temperature 入口，执行前检查 interventions.enabled（默认 false）及私有 .silent，haptics_enabled 默认 false。震动限定 100 ms，API 回执不代表物理或人体响应；通知检查启用状态、频道阻止和本应用活动通知回读；色温沿用已有权限/手动模式/恢复日志控制。MODIFY_AUDIO_SETTINGS 正常权限加入 manifest 支持音乐音量接口，未请求或授予 WRITE_SECURE_SETTINGS 等提升权限。

VolumeAdjustment 临时降低音乐流一档、目标回读后才确认交付，保持 2 秒后 finally 条件恢复；外部不同值保留，恢复失败不给 outcome 资格，中断恢复并保留线程中断标记。VolumeJournal 使用 AtomicFile、独立 magic、落盘/readback、拒绝覆写和重开校验；VolumeRecoveryJob 持久任务在修改前调度，恢复仅在当前仍为目标值时写回，保留外部值。调整/恢复共享文件锁。相同目标值的外部写入无法靠值比较区分，不能声称证明完整外部操作所有权。

本地 321 项通过（28.596 秒）；新增模拟控制测试覆盖回读失败、恢复失败、外部改动、写入后异常、中断和恢复。APK 已部署，原生保护回执 1790887839718 验证 9 个关闭/静默入口、haptics 默认关闭、日志重开/损坏/拒绝覆写/操作重叠及无待恢复时无物理资源访问，physical_api_calls=0。仅改动独立夹具偏好并清理，真实 interventions 设置未启用。合成反馈回归 1790887839871 通过，部署前后十二项冻结校验通过。

证据 artifacts/native_android_v1/native_actuator_guards_pixel.json 绑定当前源码/APK。正向 Android 实际交付、音量恢复任务调度/重启尚未验证；入口尚未接入活动试验授权和异步交付 worker。原生产影子模式保持，完整闭环、性能/内存和其余阶段继续推进，未提交或推送。


## 2026-10-02：Android 持久音量恢复任务无操作调度验证

新增 debug 入口只在 app-private volume_pending.bin 不存在且持有共享音量锁时登记测试任务，避免夹具处理真实待恢复调整。登记回执 1790888044409：job 34714、persisted=true、客户端 PID 13979。Android 实际开始并通过 jobFinished 结束；执行回执 1790888076888 的 nonce 与登记一致，服务 PID 14307、no_pending_volume_recovery、volume_write_attempts=0、retry=false。无记录路径在获取 AudioManager 前返回，没有音量设置访问。没有因首次回执缺失重建任务，而是核对同一调度任务的系统状态和最终回执。

APK 已部署，受影响的音量控制主机测试通过（1 项，0.325 秒），前后十二项冻结校验通过。证据 artifacts/native_android_v1/volume_recovery_scheduler_pixel.json 绑定来源/APK。只证明 Android 持久任务登记与独立服务执行链，无真实待恢复记录、未重启、不代表有待恢复时的物理恢复或重启存活验收。活动交付链和其余阶段继续推进，未提交或推送。


## 2026-10-02：显式活动模式的异步交付与试验状态连接

服务生产采样携带最小绑定 trigger（包含原始 elapsed clock）进入比较；只有 enabled=true、mode=active、非 .silent、服务未停止/销毁且触发帧年龄≤5 秒时，推荐提案才使用活动状态。旧 enabled 单一色温偏好不会独自启动整套动作；默认 mode=shadow，haptics 默认关闭。活动触发帧须与模型/摘要/归一化器/session/token/as-of/原生来源精确匹配。none 随机对照直接 observing，其余 queued；影子试验保持 shadow。

NativeDeliveryWorker 单任务异步执行，采样线程和比较发布锁不承担两秒调整等待；执行前 NativeMicroActuator 再检查授权/静默。回执严格布尔映射：未交付 delivery_failed，交付但不允许 outcome→censored_delivery，交付且允许→observing；只有 queued 行可更新，晚到/重复回执不复活终态。停止中断任务以执行控制器 finally 恢复，持久删失 queued/observing；关闭线程资源。交付异常转换成失败回执，日志或持久失败不赋奖励。

停止发布屏障的回归先在真机失败（1790888774279：stop flag delayed until publication barrier released）。修复先置 stopped=true，再使比较发布失效、取消交付并删失待定试验，避免停机期间晚到提案重新排队。最终 APK 已安装，回执 1790888830868 通过严格回执状态、随机 none 对照、停止屏障/删失及晚到回执拒绝；无物理 API 调用。活动 flag/回执均为独立合成账本夹具，未启用真实偏好。

完整 322 项通过（28.538 秒），后台测试覆盖非阻塞调用、单任务、停止中断清理、旧发布拒绝与关闭拒绝。默认影子链 1790888835496、原生关闭/静默保护 1790888829517 通过。实际 interventions.xml 不存在（默认关闭），未改写真实启用设置。前后十二项冻结校验通过。证据 artifacts/native_android_v1/native_trial_delivery_pixel.json 绑定当前源码/APK并保留失败基线。活动服务实际交付/人类反馈未执行，持续性能/内存、冷查询和其余科学/阶段门槛仍开放；未提交或推送。


## 2026-10-02：大历史双分支谱缓存连接（真机验收待解锁）

HistoryForecast 在 eligible_pairs≥2048、状态≤16 且服务缓存可用时，共用已绑定历史的拟合谱 CDF，供原生 baseline/scenario 双分支计算；其余保留精确 Von Mises。CDF 查询复用同一 snapshot，历史内容/时区变更仍走原缓存失效逻辑。计时区分 CDF、双分支核心和包含指标/场景 JSON 的计算总段；不包含历史扫描、key/kernel 构建和抽样准备，不能当完整请求耗时。谱近似显式标记，不承诺一般数据与精确核逐样本一致。

新增 2060 行确定性轮转合成夹具检查双分支缓存命中、两臂精确参考一致，尚待本次真机执行，不代表随机转移总体近似误差验收。本地 322 项通过（28.455 秒），APK 构建并安装；前后十二项冻结校验通过，diff 空白检查通过。真机 Activity 因锁屏暂停，旧回执未采纳，已请求解锁。证据 artifacts/native_android_v1/history_counterfactual_cache_pending.json 绑定源码/APK；没有新性能结论。默认干预保持关闭，未提交或推送，完整阶段验收仍开放。


## 2026-10-02：大历史双分支缓存真机回执及首次拟合覆盖

此前锁屏暂停的夹具恢复并产出新回执 1790889525282，首次热核心 15.92749 ms、包含指标/场景 JSON 的计算 40.07906 ms、历史扫描 613.159261 ms；保存为 history_counterfactual_cache_first_pixel.json，绑定此前源码/APK，旧回执没有替代此次结果。

补充独立 counterfactual cache 首次拟合/重复复用/移位单步/时区失效夹具，重新构建安装并通过真机回执 1790889808848。冷核心 61.131673 ms，独立热核心 15.097249 ms，共用已有快照热核心 14.85734 ms；共用热请求计算段 38.251668 ms、历史扫描 585.768555 ms。两臂确定性轮转参考一致，eligible_pairs=2057，历史未改动。夹具只证明该合成轨迹和生命周期，不证明一般谱近似与精确核逐样本一致、干预效应或真实长窗性能。

前后十二项冻结校验通过。证据 artifacts/native_android_v1/history_counterfactual_cache_lifecycle_pixel.json 绑定当前源码/APK；322 项本地测试结果来自夹具扩展前，新增 Java 夹具已编译并真机执行通过。热核心超过 15 ms 的样本和冷核心超标均保留；完整请求、30 日内存及全阶段目标未验收，继续处理历史扫描/拷贝等开销，未提交或推送。


## 2026-10-02：原生 Cold 累计转移的模型内容摘要绑定

原生 ColdTrajectory.append 原先只比较 session/model_version/normalizer/source，未比较 model_sha256，也未核对帧摘要与已登记模型 bundle 的摘要。新增合成回归先在 Pixel 6a 失败（1790890024890：model content boundary entered cold graph），确认同版本名、不同模型内容的边会进入长期图。

修复将 model_sha256 纳入相邻帧边界，并与 cold_models.bundle 的摘要匹配。跨内容或缺少摘要的边不累计；两帧摘要相同但与已登记模型不符时拒绝写入。原有归一化器、会话、时间、连续性和外键检查保留。独立内存 SQLite 夹具还验证幂等迁移、失败回滚、模型/状态上限及压缩超限不替换旧检查点。

更新构建并部署，真机新回执 1790890138470 全部通过；本地 322 项通过（28.860 秒），diff 空白检查与前后十二项冻结校验通过。证据 artifacts/native_android_v1/cold_model_content_binding_pixel.json 绑定修复前后源码/APK并保留失败回执。未导出或重算真实历史，不推断现存统计是否曾被污染；默认影子/静默设置保持，分叉参数族选择待明确，原生每周完整演化与其余阶段仍开放。未提交或推送。


## 2026-10-02：原生保留 Hot 流式审计组件

NativeWeeklyAudit 在单个数据库事务内读取至多四个登记模型，再流式扫描全部 frames。检查时间列/整数 elapsed clock、模型/归一化摘要、非空来源身份、状态词汇、128D 有限数值坐标与速度。坏 JSON、缺失或错误绑定、错误坐标累计为 invalid_records 并切断前后连续性；跨身份/间断/时钟不一致不会作为连续拟合对。取消抛出中断并结束事务，不发布部分审计结果。来源摘要逐条长度绑定时间列和原始 payload，不输出原始轨迹。

纯数据候选可用标志只在无无效记录且至少两对连续观测时成立，candidate_activation_authorized 恒 false；尚不评估拓扑应变，不拟合或激活候选。扫描事务的生产并发影响和完整 48 小时耗时/内存尚未测量，不能声称零阻塞或 5MB 验收。

APK 构建安装，Pixel 6a 合成回执 1790890589798 通过：四条干净记录三对连续观测；摘要变更或坏 JSON 各识别一条无效记录，仅保留一对；重复源摘要稳定，非数值坐标拒绝，中断释放事务。前后十二项冻结校验、diff 空白检查通过。本轮受影响验证为编译和实际 Android SQLite 夹具，没有把此前 322 项结果表述为本轮新全套。证据 artifacts/native_android_v1/native_weekly_audit_pixel.json 绑定源码/APK。实际周任务、应变/候选/势能/激活/回滚仍需接通，原生每周完整目标未验收；未提交或推送。


## 2026-10-02：原生最近两节点拓扑应变评估接入流式审计

TopologyStrain 沿用现有位置平方距离与动量方向/速度距离，按原节点顺序稳定选择最近两节点；以曼哈顿网格距离>1统计非邻接。ModelEngine 提供内部评估入口，NativeWeeklyAudit 只评估摘要匹配当前活动模型的有效坐标/速度，报告样本数、平均能量距离和 topographic_error，模型摘要与完整源摘要共同保留。其它模型的保留记录仍做来源审计，不混入当前模型的应变均值。

36 组 1D/3D/128D 随机合成案例与 Python compute_energy_distance 对照，最近两节点、邻接判断一致，能量误差小于测试的 1e-9 精度；覆盖零速度、静止中心、并列稳定排序和非有限/溢出坐标。完整 323 项通过（29.591 秒），APK 部署，真机审计回执 1790890861737 验证四条有效记录的指标与当前模型单帧参考一致，错误摘要/坏 JSON 排除并保留原取消/坐标检查。前后十二项冻结校验和 diff 空白检查通过。

证据 artifacts/native_android_v1/native_topology_strain_pixel.json 绑定源码、测试、APK。指标只是能量最近两节点诊断，不等于稳定性/吸引子或校准收益；候选输入标志仍为保留全流基本可用性，不构成特定活动模型的训练/留出门槛。完整原生增量校准、势能排序、激活/迁移/回滚和周调度继续实现；完整目标未完成，未提交或推送。


## 2026-10-02：原生六轮 warm-start SOM 更新组件

IncrementalManifoldFit 克隆既有中心、速度中心和网格，固定六轮、初始学习率 0.1，依进度冷却学习率/邻域宽度，沿用既有 kinetic energy BMU 与高斯邻域公式。调用方逐样本提供归一化坐标、速度及顺序，组件不重新拟合归一化器、不加载整段轨迹、不激活生产模型；完成后拒绝额外更新，读回数组是副本。训练记录上限 172800 对应所要求的 48h/1Hz 输入规模，生产编排尚未接入。

1D/128D 共享样本顺序的六轮更新结果与 Python 公式逐元素在 1e-10 测试精度内一致。该对照不证明 Java/Python RNG 固定种子顺序相同，也不代替真实数据留出收益。测试还确认输入/输出数组隔离。全套 324 项通过（29.029 秒），APK 部署，Pixel 6a 回执 1790891164365 验证 12 条合成样本、72 次更新、非有限输入拒绝不污染候选、有限中心与原生产模型隔离，candidate_activated=false。前后十二项冻结校验和 diff 空白检查通过。

证据 artifacts/native_android_v1/native_incremental_fit_pixel.json 绑定源码、测试、APK。保留数据顺序遍历/固定种子索引、训练/留出门槛、网格适配、候选持久化、势能排序、迁移/激活/回滚和周调度继续接通；完整每周自演化未完成，未提交或推送。


## 2026-10-02：原生数据库训练／留出候选链（真机回执待锁屏恢复）

NativeCandidateCalibration 先在同一事务内完成来源审计，再选取当前模型/归一化摘要一致的 100..172800 条记录，按时间划分前 70% 训练、后 30% 留出。仅保留时间索引，六轮 Java Random(42) Fisher-Yates 顺序读取训练帧，以既有更新器拟合；留出比较基线与候选的平均能量距离、状态覆盖及最近两节点拓扑误差。候选门槛沿用严格 >5% 距离改善、覆盖不低于 min(2,基线状态数)、拓扑误差不升高。输出候选中心/速度及来源/父模型摘要，candidate_activated=false。

新增 100 条独立合成数据库夹具检查时间边界、70/30 数量、仅修改留出末帧不改变拟合中心/速度、来源摘要改变和事务释放。APK 编译、签名验证、安装完成，前后十二项冻结校验和 diff 空白检查通过。当前 Activity PID 16328 被锁屏暂停，现有回执 1790891164365 是上一轮旧结果，本轮未采纳，不能宣称新夹具通过。本轮未重复此前 324 项主机测试；受影响的 Java 代码已编译，Android SQLite 数值与泄漏验证待新回执。

证据 artifacts/native_android_v1/native_candidate_calibration_pending.json 绑定源码/APK及等待状态。生产编排需在独立不可变数据库快照运行，避免长事务阻塞采样；完整 48h 拟合耗时/内存未测。网格适配、候选原子持久化、势能排序、迁移/激活/回滚和周调度仍未接通，完整目标未完成，未提交或推送。


## 2026-10-02：候选时间划分与留出隔离新回执、接受门槛独立测试

上轮锁屏等待的数据库夹具恢复并取得新回执 1790891599166：100 条按时间拆分 70/30，训练末帧早于留出首帧，只修改留出末帧不改变候选中心/速度，来源摘要改变，事务释放。合成候选 accepted=true、candidate_activated=false；证据 native_candidate_calibration_first_pixel.json 绑定此前部署源码/APK，旧 pending 检查点标记为历史已解决。该结果只证明列出的合成数据库路径，不是实际保留数据收益或原生每周完整执行。

接受条件抽取到 CalibrationGate 并接回训练链，独立主机测试覆盖 >5% 与恰好 5% 边界、覆盖减少、拓扑恶化、单状态覆盖、零基线及非有限/负分数和无效计数。全套 325 项通过（30.344 秒），当前 APK 构建签名验证、安装完成，冻结校验通过。当前版本的同一数据库夹具复验已启动，读取回执仍是 1790891599166，本轮尚未采纳为当前构建的复验成功。

证据 artifacts/native_android_v1/native_calibration_gate_validation.json 绑定当前源码/APK及等待状态。没有重启未完成夹具；只有确认先前回归已完成后才部署新门槛版本。独立快照、候选持久化、网格适配、势能排序、迁移/激活/回滚和原生周调度继续推进；真实生产模型未改变，完整目标未完成，未提交或推送。


## 2026-10-02：独立每周输入快照及只读事务兼容修复（待设备完成基线）

门槛版本新回执 1790891821245 已验证 70/30 和留出隔离，保存为 native_calibration_gate_pixel.json，原 validation 等待状态更新为通过；该回执不含随后新增的快照验证。

WeeklySnapshot 在独立缓存路径事务复制保留 Hot 帧及至多四个模型绑定，时间窗为 (as_of−48h,as_of]，上限 172800 帧；源事务在复制结束释放。快照开启 query_only，退出/失败删除数据库及关联日志文件。新增夹具检查源摘要一致、后续源记录删除不影响候选、源事务释放和快照写拒绝，已构建并安装初版。完整 48h 复制耗时/临时空间、进程死亡后遗留文件清理尚未验收。

本机 SQLite 复现 query_only 与 BEGIN IMMEDIATE 不兼容（attempt to write a readonly database）。本地 Android 35 SDK 确认 beginTransactionReadOnly 存在；源复制、审计、拟合改用该接口，受保护快照明确要求 API35。该修复 APK 已编译签名验证，但未安装，以保留当前已启动的初版夹具；当前读取仍为前一门槛回执，无新快照结果，不能宣称 Android 失败/修复验证。

证据 artifacts/native_android_v1/weekly_snapshot_pending.json 绑定当前修复源码及本地 APK，区分设备初版与未部署的修复。冻结校验及 diff 空白检查通过，未重复此前 325 项主机测试。真实生产模型未变，独立快照、候选持久化/网格适配/势能/迁移/激活/回滚和周调度继续推进，完整目标未完成，未提交或推送。


## 2026-10-02：只读快照修复真机通过与受保护后台合成入口

初版快照回执 1790892123305 确认 Android SQLiteReadOnlyDatabaseException（SQLITE_READONLY），失败记录保存于 weekly_snapshot_failure_pixel.json；失败 APK 摘要未保留，不能为它补写当前修复版摘要。Android35 只读事务修复后，新回执 1790892446622 通过：复制来源摘要一致、源事务释放、源后续删除不影响快照候选输入、快照写拒绝。weekly_snapshot_fix_pixel.json 绑定先前部署修复源码/APK，旧 pending 检查点标记为已解决。

安装后台验证入口前，先核对旧夹具线程存在并保留任务；随后线程消失且新回执确认终止，才更新 APK。NativeVerificationReceiver 仅在调试构建接受固定合成验证 action，manifest 要求调用方 DUMP 权限；通过 goAsync 执行独立内存/缓存夹具，AtomicFile 写单独回执，不启动服务、采集、执行器或生产模型激活。后台广播完成，新回执 1790892592072 验证 audit/六轮更新/70-30 留出隔离/受保护快照全部通过。该证据包含允许的 ADB 调用路径，尚未用独立普通应用验证权限拒绝。

前后十二项冻结校验和 diff 空白检查通过。本轮没有重复此前 325 项主机测试，受影响验证为 API35 编译和 Android SQLite 实际合成回归。证据 artifacts/native_android_v1/weekly_snapshot_receiver_pixel.json 绑定当前源码/manifest/APK。后台入口是调试验证工具，不代表周调度已实现；完整 48h 快照性能/临时空间、崩溃遗留清理及候选持久化/网格适配/势能/迁移/激活/回滚仍开放。完整目标未完成，未提交或推送。


## 2026-10-02：原生接受候选的摘要绑定与原子保存

ModelEngine.candidateBundle 复核 accepted/未激活、父模型/归一化/源摘要、零无效源记录、70/30 时间边界和原留出门槛；检查候选有限且维度匹配。候选模型保留 parent_model_version/parent_model_sha256，移除旧 model_version/model_sha256，避免变化后的参数继承旧内容身份。保存模型/固定归一化器/网格与验证报告；验证报告移除重复的中心数组。

NativeCandidateStore 按完整文件 SHA256 命名，AtomicFile 保存并重读验证，跨进程文件租约串行保存；同摘要重复保存只验证已有文件，不覆写损坏文件。每束≤256000 字节、最多四个最终候选，达到上限拒绝新候选；实际轮换/活跃引用保留由后续注册表完成，不能将拒绝第五个候选当成长期自动轮换已实现。read 检查长度、摘要和协议。尚未模拟断电或 AtomicFile 内部同步失败，不声称这些崩溃模式均已验收。

受影响 5 项主机测试通过（3.025 秒），APK 构建安装，Pixel 6a 后台合成回执 1790892998934 通过来源/父模型重读、重复保存摘要一致、拒绝候选不保存和损坏拒绝；独立缓存夹具文件清理，candidate_activated=false。前后十二项冻结校验与 diff 空白检查通过，未重复此前 325 项全套。证据 artifacts/native_android_v1/native_candidate_store_pixel.json 绑定源码/APK。网格适配、势能排序、引用/迁移/激活/回滚及周调度仍待接通，完整目标未完成，未提交或推送。


## 2026-10-02：训练数据驱动的原生网格适配

GridAdaptation 按训练帧最近两个节点的计数，贪心交换已有节点坐标，严格降低非相邻节点对的总计数；按 token 排序选择并列方案，与 Python adapt_grid_structure 保持一致。不会新增状态或改变 token 身份。NativeCandidateCalibration 仅用前 70% 训练数据选择网格，再做六轮增量训练，用后 30% 留出数据检查距离、覆盖和拓扑门槛。候选保存验证网格是原坐标的排列，并持久化适配后的网格。

主机 36 组 4/9/16 节点案例与实际 Python 交换算法一致，包含零计数、不变输入、非法对角计数及取消；其中包含实际降低损失的案例。全套 326 项测试通过（31.765 秒）。新版 APK 构建、签名验证和 Pixel 6a 安装完成，后台合成回执 1790893552979 通过：修改留出帧不改变拟合中心或网格，持久化网格与候选一致，快照隔离、接受候选保存、摘要绑定和损坏拒绝保持通过。真机这组数据训练非相邻计数为零，未发生坐标交换；实际交换证据来自主机案例，不将其写成真机结构改善。

前后十二项冻结核验通过。证据 artifacts/native_android_v1/native_grid_adaptation_pixel.json 绑定源码及 APK。此步骤尚未实现势能排序、候选引用/迁移/激活/回滚或原生每周调度；完整目标继续推进，尚未提交或推送。


## 2026-10-02：原生训练漂移投影与能量排序（真机 Job 仍待完成）

EmpiricalGraphPotential 在至多 16 个候选中心、128D 坐标上累计高斯核加权位移/时间暴露，采用相对最大对数权重缩放，避免远中心权重整体下溢；至少两对连续训练观测后做完整图漂移线积分的最小二乘势能投影，保留残差 RMS 和保守分量比例。零漂移比例未定义，JSON 用 null。固定 bandwidth=1、mobility=1，与 Python 现有约定一致，不代表物理参数已辨识。输出能量排序 token 列表，不改 token 身份或激活模型。

NativePotentialCalibration 在独立、已审计快照上只读取训练时间边界以内的帧；严格排除模型/归一化、会话/来源、连续标志或时钟间隔边界。候选报告绑定父模型、归一化器、完整快照摘要及训练截止时间；候选保存复核中心维度/数值、能量排序、残差、来源绑定及训练对数，并保存势能、移除验证报告中的重复势能。无可用训练对的情况明确标记 unavailable，原有 representation holdout accepted 本身仍不等于完整周任务 ready。

主机 6 组 1/3/128D 高斯经验漂移及图投影对照 Python 通过，覆盖中心远离样本的极小权重、不变内部状态的非法输入/取消、零漂移比例未定义；327 项全套通过（31.801 秒）。随后 Android Job 生命周期修复后，受影响 5 项主机测试通过（3.040 秒），API35 编译签名验证通过，APK 已安装。

初版完整后台夹具超出广播执行期限，系统交付记录确认 60.008 秒 TIMEOUT、anrCount=1，进程已终止且未产生新势能回执；证据 native_potential_receiver_timeout.json 保留该版本源码/APK摘要。修复为 DUMP 保护广播只派发一次 NativeVerificationJob（34715），由 BIND_JOB_SERVICE 保护 JobService 承载合成计算、取消时中断并释放事务；派发及完成通过唯一 nonce 绑定。当前 nonce=1b57ef3d-8af2-41ca-88fc-1691b5ca96c5、派发时刻 1790894286224，系统仍显示 active ready，实际 traceform-debug 线程正在计算；尚无匹配的新完成回执，不能宣称真机势能/生命周期修复通过。不重复派发或重启该任务。

证据 native_training_potential_pending.json 绑定当前源码/APK及待完成状态；部署前冻结核验通过，任务结束后的后置核验待做。完整 48h 拟合性能与临时空间未验收，当前合成执行耗时也需继续调查。此调试 Job 不是原生每周日演化调度。模型引用/迁移/激活/回滚、原生周调度及其他原有科学/性能/群体隐私缺项继续推进；完整目标未完成，未提交或推送。


## 2026-10-02：原生训练势能 Job 完成及分阶段性能测量

新回执 1790894825436 与 nonce=1b57ef3d-8af2-41ca-88fc-1691b5ca96c5 匹配，完整合成夹具通过。训练势能计入 69 对；改动单个训练会话帧排除两条跨会话边，留出改变不改变势能，候选保存重读保留势能且拒绝错误归一化器绑定。系统 Job 已不存在，任务终止及通过由新回执共同确认，未重复派发。前后十二项冻结校验通过，临时 USB 调试转发已删除；调试连接未成功，不将其写成热点定位证据。

native_training_potential_pixel.json 绑定实际部署源码/APK和回执，旧 pending 标记已解决。派发到完成 539212 毫秒，包括调度/启动、四次校准和其他夹具步骤，不能当成单次拟合耗时。计算线程 CPU 计数在运行中持续增加，初版广播的 60 秒期限确实不足；JobService 生命周期修复已完成同一夹具。完整 48h 规模性能依然未验收。

为定位长耗时，当前新增审计、训练选择/网格、六轮拟合、留出评分、势能投影的单调时钟分段计时，并记录编码前夹具耗时；尚未根据猜测做性能修改。该计时版需要部署并取得新的匹配回执；此前成功回执属于未计时版。原有模型引用/迁移/激活/回滚、周日演化调度及其他完整目标事项继续推进，未提交或推送。


## 2026-10-02：校准分段计时通过与 SDK 依赖闭包修复

计时回执 1790895703253 与 nonce=3ea0cb6c-9a08-4ae4-a8fa-06c80ad70e99 匹配，完整合成夹具通过，Job 已不存在。首轮审计 17513.639ms、训练选择/网格 11652.816ms、六轮拟合 66992.603ms、留出评分 4104.420ms、势能 9549.254ms，编码前总计 109812.732ms。六轮拟合约占 61%，不是 JSON 解析的已测百分比。一次 JDWP 栈采样显示该阶段正在 NativeCandidateCalibration.frame→JSONObject/JSONTokener 解析；已恢复全部线程、退出调试器并删除临时 USB 转发。整体夹具执行 558163.510ms，包含四次校准及其他验证，且计时受该调试采样影响，不能当成纯性能基准。

native_calibration_stage_timing_pixel.json 绑定已安装计时 APK 及其构建源摘要；native_calibration_stack_sample.json 保存单次执行栈及扰动边界。下一步对重复帧解码做针对性验证和优化，不能将此 100 帧夹具成绩外推为完整 48h 或 15ms 门槛通过。

SDK 回归先失败，确认生成的 traceform-sdk.jar 缺少 CalibrationGate、IncrementalManifoldFit、NativePotentialCalibration、TopologyStrain 及其 Result。build_sdk_jar 现在保留原有公开入口，并用 JDK jdeps 递归收集包内依赖，最终 18 个类，构建时拒绝缺失的编译输出。回归随后通过，全套 328 项通过（32.207 秒）；独立目录全构建/签名验证通过，未在运行中的计时 Job 期间替换设备 APK。native_sdk_dependency_closure.json 绑定 SDK/源码及原失败，证明包内符号依赖闭包，不替代完整 Android SDK 消费端实测。模型注册/迁移/激活/回滚与周日演化调度仍待接通，原有完整目标保持，未提交或推送。

计时任务结束后的十二项冻结核验通过，证据已记录；当前没有待观察的验证 Job。


## 2026-10-02：训练向量一次解码、临时文件完整性与崩溃租约清理

NativeCandidateCalibration 在训练准备阶段按时间顺序一次解码向量/速度，同时计算网格对计数并写独立 TrainingVectors 二进制文件；六轮拟合仍用相同 JavaRandom42/Fisher-Yates 顺序，改为随机读取精确 double 位模式，不再每轮解析同一训练 JSON。审计、留出评分和势能仍按原路径读取 JSON；fit_preparation_json_decodes 只计准备阶段，不能当成全流程 JSON 解码数。文件头绑定完整源摘要及维度/样本数，逐记录绑定时间戳和 CRC32，读取检查长度、头部、时间戳、校验及有限坐标。CRC32 检查意外损坏，不是对恶意同 UID 写入的密码学认证。

1/128D、16 节点、70 样本、六轮共享随机序列的主机回归证明文件读取与直接向量拟合逐位一致；记录/来源头损坏、截断、错误时间戳和取消拒绝，正常关闭删除文件。全训练上限的文件最多 249177676 字节，使用磁盘换取重复解码减少，不声称整个 App 内存低于 5MB。完整 48h 临时空间/耗时尚未实测。

一次解码 v1 APK 已构建安装，第一任务 nonce=4b4a008a-a73f-43f5-a277-fb86bd78c62b 因系统调度 constraints 终止，无新回执；线程已退出，停止后无 weekly-vectors 文件，native_training_spool_scheduling_stop.json 绑定该版本。另有以前遗留的 weekly SQLite/日志文件，未擅自删除，独立快照遗留清理仍开放。确认终止后派发新的受控验证 nonce=a2d40005-8749-46c8-be08-9a43d7c8cb47；系统 run -f 返回 FORCED，但实际 WITHIN_QUOTA 未满足、Job waiting，尚未开始。任务保留，不调整系统配额，也不把受控命令返回当成算法通过。

崩溃遗留回归先失败：子进程 halt 后文件未被下一次拟合回收。v2 持有 FileChannel 跨进程租约，构造时只清理能识别 v2 头部且无活跃租约的遗弃文件；同进程和另一仍存活进程的文件保留，退出进程的文件回收。旧版本、无法识别头部及符号链接不删除。全套 330 项通过（33.207 秒，随后追加跨进程存活案例后受影响两项通过 1.038 秒）；v2 独立目录构建签名验证通过，未替换仍排队的 v1 验证 APK，v2 Android 租约兼容性尚未实测。

native_training_spool_validation.json 区分已安装 v1、仅本地构建 v2、主机证据和排队状态。首次停止后的十二项冻结核验通过，当前排队任务结束后的核验待做。真机性能改善尚未获得新匹配回执；模型注册/迁移/激活/回滚、原生周调度和原有全部科学/隐私/性能缺项继续推进，完整目标未完成，未提交或推送。


## 2026-10-02：原生模型注册表与服务启动读取

NativeModelRegistry 保存完整 core_json 字节串的不可变摘要快照，使用跨进程租约和 AtomicFile 更新 current/previous 引用。候选激活重验父模型、原归一化/参数、留出门槛和可用势能，拒绝过期 expectedVersion；回滚检查指针记录的当前版本并验证上一快照，因此活动快照损坏时仍可恢复。原生校准版本使用独立摘要命名协议，不声称与 Python 模型摘要格式等价。

EdgeService 在创建时读取活动快照，整个服务生命周期固定模型；资产数值一致性检查仍针对 APK 资产模型，active_model.json 分别记录活动模型及资产检查身份。不提供运行中热切换，也未调用生产候选激活。新增合成 Android 用例覆盖重新打开注册表、过期激活拒绝和活动快照损坏后的回滚；尚未真机执行。

独立目录构建及签名验证通过，APK 3900319 字节；相关主机测试 5 项通过，但不构成 Android 注册表运行证据。native_model_registry_validation.json 绑定此次构建源码与 APK 摘要。已排队的真机 Job 34715 本次检查仍 waiting，未替换其 APK。四个 Cold 命名空间后的长期迁移/保留策略、每周调度及激活策略仍未完成，整体部署和仓库推送未完成。


## 2026-10-02：原生周维护时间窗

WeeklyMaintenanceClock 按设备 ZoneId 的本地周日 02:00–06:00 计算执行窗口；凌晨 02:00 是路线图“周日凌晨”的实现选择。窗口内尚未完成的任务可立即执行，结束边界或已完成的周日键推至下一周。周日日期键避免跨年 ISO 周编号歧义。夏令时缺失小时按 java.time 时区规则顺延，不使用固定 604800 秒推导本地时间。主机测试覆盖 Kathmandu 的 45 分钟偏移、窗口边界、已完成去重、美国夏令时起止及跨年，1 项通过（0.47 秒）。此组件尚未接入 JobScheduler，也不是每周任务已执行的证据。


## 2026-10-02：原生周维护 Job 接入（运行未验收）

WeeklyMaintenanceJob（34716）在采集服务初始化后安排持久化本地周日时间窗任务，执行前再次检查当前时区/窗口；同窗口已安排任务不重复替换。后台线程持有独立跨进程维护租约，从只读源数据库复制 48h 快照，再运行原生审计、拓扑/增量校准、留出门槛与训练势能。接受候选写入私有 model_candidates，回执记录 inactive；不修改活动模型。停止时中断后台计算且抑制完成回执；系统中断可重试，维护锁/回执故障使用系统退避而非立即循环排队。周末窗口外的延迟任务仅重新安排，不在工作日执行校准。

构建与签名校验通过，APK 3904415 字节；相关主机 6 项通过（2.62 秒），覆盖时钟、租约与 Python 维护，不覆盖 Android JobService 生命周期。native_weekly_maintenance_validation.json 绑定实际构建摘要并核对源码一致。尚未安装此版本或运行真实周维护。四候选后的保留策略、Cold/Warm 生命周期准入与自动激活仍开放；本实现不能证明自动自演化全部完成。


## 2026-10-02：候选文件滚动保留

第五个候选保存的主机回归先复现 four retained candidate bundles maximum；NativeCandidateStore 改为持有原跨进程租约，按修改时间/文件名稳定排序，校验待删除候选摘要与协议后回收最旧文件，成功保存后最多四个摘要命名 base 文件。幂等保存保护该目标文件。损坏的待回收候选保留并报错。回归直接运行生产存储代码，JSON/模型/AtomicFile 使用窄主机替身，因此不证明 Android 原子恢复或模型校验。真机合成用例新增连续保存五个候选及保留上限检查，但尚未执行。

回收发生在新候选写入前，后续写失败可能少保留一个旧 inactive 候选；备份孤儿及崩溃恢复边界未验收。它不回收已复制到注册表的模型快照，也不解决 Cold/Warm 四版本长期准入。独立 APK 构建及签名验证通过，native_candidate_retention_validation.json 绑定当前源码与 APK。已排队真机验证任务本轮仍 waiting，最终部署与推送未完成。


## 2026-10-02：注册/周维护/保留改动后主机全套回归

python3 -m pytest -q 完成：346 项测试、156 个子测试通过，耗时 38.00 秒。README 测试徽章更新为当前数量。native_registry_weekly_host_regression.json 记录关键原生源码摘要；全套主机测试并不执行 Android 注册表/周维护生命周期夹具，不能据此判定每周自演化已验收。同一真机验证 Job 34715 本次仍 waiting，无新回执。长期 Cold/Warm 模型引用迁移、分叉参数族、真实完整规模性能/科学/群体隐私证据以及最终部署推送继续开放。


## 2026-10-02：新版注册/周维护/保留 APK 测试部署

部署前十二项冻结核验通过。旧验证 nonce=a2d40005-8749-46c8-be08-9a43d7c8cb47 复核仍 waiting；因验证代码已被新版取代，明确取消，随后系统 get-job-state 确认 unknown。不是因观察超时而推断终止。主构建目录使用原开发签名生成新版 3904415 字节 APK，覆盖安装成功；pm path 对应已安装 base.apk SHA256=f0a892d46b840d967f96d076c19548e44c0e5aec86bccb7160d452110cb3ad28 与本地构建一致。安装后十二项冻结核验通过。

新合成派发 nonce=90628b4d-3800-458f-b08e-7934666ca73b，scheduled_epoch_ms=1790899415055，Job34715 当前 active ready，尚未出现匹配回执。旧通过回执 nonce=3ea0cb6c-9a08-4ae4-a8fa-06c80ad70e99 不作为本轮结果。native_registry_weekly_deployment_pending.json 绑定真实安装摘要及派发身份。候选激活/回滚只在独立合成目录验证；不调系统配额、不开真实执行器、不导出真实轨迹。任务完成后的冻结核验待做；整体最终验收和推送仍未完成。


## 2026-10-02：候选备份身份纳入保留边界

主机新增 backup-only 场景先复现 oldest retained：原枚举仅计 .json，遗漏 .json.bak，下一次保存可超过四个候选身份。现在对摘要命名主/备份文件按身份去重，每个身份经 AtomicFile.openRead 恢复与摘要/协议校验后再排序回收。主机相关 3 项通过（1.58 秒）；AtomicFile 是窄替身，不据此宣称 Android 恢复完成。真机夹具新增备份重命名恢复断言；独立目录构建签名通过，但尚未部署此修复。partial .new 文件回收仍未实现。

已安装的验证 nonce=90628b4d-3800-458f-b08e-7934666ca73b 仍 active ready，最新读取回执 nonce 仍为旧任务身份，不能认定通过；当前任务覆盖前一版注册/回滚/滚动保留，不包含本轮备份修复。native_candidate_backup_retention_validation.json 绑定新修复源码与构建身份，保留已安装版本的独立证据。


## 2026-10-02：真机注册重验失败与周维护锁重试修复

nonce=90628b4d-3800-458f-b08e-7934666ca73b 新匹配回执 passed=false：IllegalArgumentException: candidate changed fixed normalization or model parameters，execution_ms_before_receipt_encoding=362790.285658。系统确认 Job34715 已 unknown，随后十二项冻结核验通过。失败源为注册激活重验路径，尚未定位具体字段；不能根据前置步骤已执行而宣称新训练优化或注册/回滚通过。native_registry_weekly_failure_pixel.json 保存实际安装 APK/源码绑定和完整失败回执；生产模型未激活。

独立主机回归复现周维护遇到模型锁 SecurityException 或 SQLiteDatabaseLockedException 后错误写入 completed_window。修复将这些访问异常请求系统退避重试并阻止完成回执。真实 WeeklyMaintenanceJob 控制流在平台替身下运行，相关 5 项通过（2.42 秒）；不等价于 Android JobScheduler 运行证据。独立构建/签名验证通过，未部署；native_weekly_retry_validation.json 绑定新修复。后续需定位注册重验差异并取得新真机回执，完整目标仍未完成，未推送。


## 2026-10-02：Android JSON 负零往返比较修复

短真机复现先失败（1790900055966）：未修改的模型 JSON 往返被文本比较拒绝，signed_zero_text_equal=false；资产 medians 中确有负零。ModelJson 将两侧都经相同 JSONObject 往返再比较，注册重验及 Cold 同版本注册使用同一规则；原始快照/模型摘要、父版本和科学门槛校验保持。修复版真机新回执 1790900150997 passed=true：未修改模型被接受，IQR 修改和额外字段拒绝，Cold 往返注册通过。前后十二项冻结核验通过，model_json_roundtrip_fix_pixel.json 保留两版 APK/源码及失败→通过证据。该方法仍要求对象键次序一致，不声称通用 JSON 规范化或位级 signed-zero 保真。

修复版完整合成校准/注册/回滚/保留重新派发：nonce=cce177df-87ef-45c0-be50-7d12d47d6086、scheduled_epoch_ms=1790900189545，Job34715 当前 active ready，尚无匹配完成回执；native_registry_json_fixed_pending.json 绑定实际安装构建。此次完整测试包括候选备份保留修复，但不执行真实周维护生命周期或生产激活。整体目标继续开放，最终验收/推送未完成。


## 2026-10-02：Warm Token 相对势能字段接通（运行未验收）

已注册势能模型的 ModelEngine.process 输出量化 Token 对应 relative_energy，并标记 state_potential_available=true 与经验漂移投影/零号规约范围；资产模型没有拟合势能时明确 null/false，不把 energy_distance 当作势能。WarmTrajectory 保留这三个字段，新增迁移夹具检查原有桶与回填桶的最后代表帧势能均保留。完整注册夹具新增候选 Token 势能对应关系及资产不可用检查；两项新 Android 检查尚未执行。

独立构建/签名验证通过，APK 3908511 字节；相关主机 3 项通过（1.07 秒），不覆盖新 Android 字段/迁移执行。native_warm_state_potential_validation.json 绑定源码与构建，未部署本改动。已安装 JSON 修复版任务 nonce=cce177df-87ef-45c0-be50-7d12d47d6086 本轮仍 active ready；该任务不覆盖本轮 Warm 字段。Cold 四版本后的历史保留/准入、自动激活及原有科学隐私性能目标继续开放，未推送。


## 2026-10-02：周维护快照扩展到 Warm（内容审计仍开放）

WeeklySnapshot 在同一源只读事务中复制 Hot 48h 与 Warm 30d，Warm 上限 86400 个 30s 桶；目标独立查询只读。warmIncluded 区分源表缺失与空表，不把缺失数据默认当作已审计。合成夹具新增源 Warm 删除后的快照隔离断言，尚未真机执行。独立构建/签名通过，native_full_retained_snapshot_validation.json 绑定源码/APK，未部署此版。Warm 内容的时钟、版本/势能与来源摘要校验尚未实现，因此不是“全量审计完成”。

当前安装的 JSON 修复版 nonce=cce177df-87ef-45c0-be50-7d12d47d6086 本轮观察仍 active ready；读取回执仍为此前失败任务 nonce=90628b4d-3800-458f-b08e-7934666ca73b，不能转作本轮结果。Cold 长期版本准入及完整科学、性能、隐私要求仍未完成，未推送。


## 2026-10-02：Warm 内容审计门槛接入与回滚失败定位

NativeWarmAudit 流式校验保留 Warm 记录的整数时间桶/代表时间/桶帧数、模型/归一化绑定、Token、有限量化距离及可选 Token 势能，并对完整 bucket/ts/n/payload 字节计算独立来源摘要。缺少 Warm 源表或审计无效时，周维护不进入拟合。新增短合成 Android 用例覆盖来源摘要稳定、计数篡改、来源改变、畸形 JSON 和取消事务释放，尚未执行。独立构建/签名通过，相关主机 2 项通过（0.93 秒），不覆盖实际 WarmAudit 数据库执行；native_warm_audit_validation.json 绑定此未部署版本。Cold 转移图完整审计仍开放。

已安装 JSON 修复版 nonce=cce177df-87ef-45c0-be50-7d12d47d6086 新匹配回执 1790900635513 passed=false：IllegalStateException: corrupt active snapshot rollback failed。任务 unknown，结束后十二项冻结核验通过。注册参数重验已不再成为此次终止点，但不能据此宣称整体注册/回滚通过。资产 center_x 有28个负零、center_v有30个负零；位模式往返损失是待验证原因，尚未定论。native_registry_json_fixed_failure_pixel.json 保留已安装源码/APK及完整回执。生产模型未激活；后续需修复回滚并取得新匹配回执，整体目标未完成，未推送。


## 2026-10-02：快照负零位模式恢复与 Warm 审计首个真机证据

短真机复现先失败（1790900969749）：registry snapshot bit mismatch: changed=60, zero_sign_changes=60；包含2个 median、28个中心位置和30个中心速度负零符号。registrySnapshot 现在记录四个数组的 negative_zero_indices，读取时验证整型、升序、范围及目标零值再恢复 -0.0。原数组逐位门槛保持。修复版真机回执 1790901105521 passed=true：完整数组位模式保留、越界符号下标拒绝、参数/额外字段拒绝以及 Cold 往返注册通过。model_zero_bits_fix_pixel.json 绑定失败→通过的两版 APK/源码及回执。旧快照缺少符号元数据时不会自动获得历史位保真；完整损坏活动快照回滚仍需新回执验证。

Warm 审计短合成真机回执 1790901125567 passed=true：摘要稳定、计数篡改拒绝、来源变化识别、畸形 JSON 拒绝、取消事务释放；native_warm_audit_first_pixel.json 绑定已安装版本。不据此推断真实保留数据已通过审计，未读取/导出真实历史。前后十二项冻结核验通过，相关主机 2 项通过（1.08 秒）。

完整合成验证再次派发 nonce=616e4ed4-4fe3-426a-adf7-9efdc8e2120f、scheduled_epoch_ms=1790901182670，Job34715 active ready。native_registry_zero_fixed_pending.json 绑定当前已安装源码/APK及身份，尚无匹配完成回执；该任务包含位模式修复、Warm 快照与 Token 势能字段检查。Cold 图完整审计、长期模型准入/迁移、生产自动激活及原有科学/隐私/性能门槛仍开放，未推送。


## 2026-10-02：Warm SQL 类型与原值摘要边界

新增主机回归先复现 fractional SQL column accepted via getLong：REAL 1.5 帧数或1000.5时间经整数读取被截断，旧绑定检查可接受；旧摘要也会丢失原始变化。NativeWarmAudit v2 先检查 SQLite 列的实际类型，要求 bucket/ts/n 为 INTEGER、payload 为 TEXT，摘要按原类型编码精确整数/浮点位/文本/二进制/空值；traceform.native-warm-source.v2 明确区别旧摘要方案。WeeklySnapshot 复制时也拒绝非法类型，避免先截断后审计。短 Warm 夹具新增实际 SQLite REAL 场景，完整快照夹具新增非法帧数拒绝及源事务释放断言，尚未真机执行。

受影响主机 2 项通过（0.95 秒）：运行生产 WarmAudit，但 Cursor/JSON/平台是窄替身，不能替代 Android 类型语义证据。独立 APK 构建/签名通过，native_warm_sql_types_validation.json 绑定未部署版本。正在跟踪的已安装验证 nonce=616e4ed4-4fe3-426a-adf7-9efdc8e2120f 本轮仍 active ready；最新读取回执还是旧失败 nonce=cce177df-87ef-45c0-be50-7d12d47d6086，因此本轮完整注册/回滚结果仍未确认。未提交或推送，完整目标未完成。


## 2026-10-02：Cold 快照复制与系统停止证据

WeeklySnapshot 在与模型、Hot 和 Warm 相同的源只读事务内复制 cold_states 与 cold_edges；状态最多64条、转移边最多1024条，身份列要求 TEXT，计数要求非负 INTEGER，复制可取消。完整合成夹具增加源边删除后快照仍保留7次转移和全部模型状态的断言。独立 Android 构建/签名通过，native_cold_snapshot_build_validation.json 绑定源码/APK；此版本尚未部署，新增夹具尚未真机运行。图谱关系、模型词表和模型内容的完整 Cold 审计仍未完成。

此前已安装完整验证 nonce=616e4ed4-4fe3-426a-adf7-9efdc8e2120f 已结束：系统记录 STOP constraints，运行391196ms；后台配额窗口执行1200039ms，允许1200000ms。任务状态 unknown，无匹配新回执，旧失败回执不能作为本轮算法结果。未更改系统配额。停止后十二项冻结核验通过；native_registry_zero_fixed_scheduling_stop.json 保存身份、安装绑定与停止证据，pending 文件已更新为终止状态。

本轮完整主机回归348项、156项子测试通过（36.75秒），不替代 Android 快照隔离、完整注册/回滚或生产自动演化验收。未提交或推送；科学、隐私、性能和长期版本生命周期门槛仍开放。


## 2026-10-02：原生 Cold 图谱审计与 Warm v2 真机验证

NativeColdAudit 在独立只读事务中校验最多4模型/64状态/1024边：模型版本与归一化身份字段、唯一有界词表、状态全集、边的模型/源/目标引用、非负 SQLite INTEGER 计数、总计数溢出及取消事务释放。排序后的模型原文、状态和计数以长度定界形成独立摘要。周维护在 Warm 审计和拟合之前执行此门槛，失败不进入拟合。审计不重新认证模型内容摘要，不重放历史，也不确认物理能量或分叉参数。

保留原签名的主构建已安装 Pixel 6a，实际安装 APK SHA-256 与本地一致。Cold 短合成回执1790902261105 passed=true：计数类型/符号、状态/边/模型绑定拒绝、摘要稳定与变化、取消事务释放。Warm v2 新回执1790902279208 passed=true，包含真实 Android SQLite REAL 时间/计数拒绝。两个回执分别保存 native_cold_audit_first_pixel.json 与 native_warm_sql_types_pixel.json，绑定源码/APK；前后十二项冻结检查通过，相关主机3项通过（1.09秒）。未读取真实轨迹，未触发执行器。完整周验证、快照复制隔离、生产自动激活及长期版本准入仍待验收，未提交或推送。


## 2026-10-02：短快照隔离真机验证

WeeklySnapshotFixture 将存储验证与耗时校准分离，使用私有临时 SQLite 和独立缓存目录，未访问真实历史。Pixel 6a 新回执1790902467161 passed=true：Hot 48h/Warm 30d 的开闭时间边界、Cold 来源摘要完整复制、源删除后的三层数据隔离、目标查询只读、Warm/Cold 分数计数拒绝、取消事务释放、正常/失败临时文件删除，以及缺失 Warm 表区别于空表均通过。该夹具的 Warm/Hot 字面负载只验证复制行为，不声称通过轨迹语义审计。

主构建已安装且实际 APK 摘要与本地一致；native_short_snapshot_pixel.json 绑定源码、安装包和回执。前后十二项冻结核验通过，相关主机2项通过（0.68秒）。本次验证不覆盖48小时满量复制、跨连接并发写或进程崩溃恢复；完整注册/回滚及生产自动演化仍未取得本轮通过证据，未提交或推送。


## 2026-10-02：注册激活与损坏快照回滚的独立真机证据

NativeRegistryFixture 使用明确标注 synthetic_metrics_not_measured_fit_or_scientific_acceptance 的合成候选，仅运行私有缓存目录中的候选存储和模型注册路径，避免为存储验证重复拟合。其指标为测试输入，不是测量收益；生产模型未激活。Pixel 6a 新回执1790902708069 passed=true：过期版本激活不写指针、激活重开身份一致、过期候选重放拒绝、损坏活动快照拒绝及回滚四组基线数组位模式一致均通过。

主构建已部署，实际 APK 摘要匹配；native_registry_short_pixel.json 绑定源码/APK/回执。前后十二项冻结核验通过，相关主机2项通过（0.66秒）。此结果确认负零修复后的独立存储回滚路径，不将先前因系统配额停止的完整周用例改写为通过。真实候选收益、完整周维护、Cold/Warm 长期准入和生产自动激活仍待验收，未提交或推送。


## 2026-10-02：SQLite BLOB 拒绝与失败清理证据

检查最初怀疑 getString 会将 BLOB 静默转换为 TEXT；真机基线回执1790902851616 实际报 SQLiteException: Unable to convert BLOB to string，否定了该假设。夹具原先只接受 IllegalArgumentException，导致误判；现在接受 Android SQLite 的直接类型拒绝，并继续严格检查源事务释放与临时目录为空。生产 WeeklySnapshot 没有因此修改。

更新后真机回执1790902912021 passed=true：Hot BLOB 负载与 Cold 模型 BLOB 均被拒绝，失败后事务与文件清理通过，既有快照边界/隔离/只读检查仍通过。native_snapshot_blob_rejection_pixel.json 保存两版构建与回执、当前实际安装 APK 摘要；前后十二项冻结核验通过，相关主机2项通过（0.67秒）。这是类型边界证据，不是生命周期准入实现；长期模型切换与自动激活仍待完成，未提交或推送。


## 2026-10-02：候选父谱系与节点身份绑定

当前原生增量 warm-start 沿用父节点 Token 身份；候选 core 现在携带继承的 cold_state_namespace 与完整 token_migration 身份映射。注册读取要求两字段成对存在、谱系为64位十六进制身份、映射覆盖全部 Token 且保持 warm-start 节点身份。候选重验生成父模型绑定字段，不能任意更换谱系。旧候选两字段均缺失时按原有核心字段校验，旧模型按自身版本独立谱系处理，不静默合并历史。资产 Cold bundle 保持原格式；已标定模型的归档保留谱系信息。

Pixel 6a 新合成回执1790903205877 passed=true：父谱系继承、外部 Token 映射拒绝及原激活/回滚路径通过。native_candidate_lineage_pixel.json 绑定当前源码和实际安装 APK；前后十二项冻结核验通过，相关主机2项通过（0.70秒）。这只建立候选与模型的谱系绑定契约，Cold 累积图仍按版本保存，尚未改为共享谱系图；Warm 版本保留、图迁移事务、崩溃协调和生产自动激活继续开放，未提交或推送。


## 2026-10-02：Cold 版本图转谱系图的事务迁移

ColdLineage.migrateOnce 在自己的写事务内执行旧图审计，创建谱系身份/模型映射/累积边与迁移标记。相同谱系必须绑定相同归一化及完整词表；新增映射沿用 warm-start 身份 Token，旧模型无谱系字段时保持独立版本身份。计数相加使用 Math.addExact，标记与计数同事务提交；已标记重试不重放旧图，未标记但目标表非空拒绝迁移。旧图保留不删除。当前生产服务未调用此入口，避免在写入和导出尚未切换时误称存储闭环。

Pixel 6a 合成回执1790903439132 passed=true：同谱系两版本7+7计数合并14，重复执行不重复累加，归一化冲突拒绝，旧图14次计数保持不变。Android 实际事务路径通过；native_cold_lineage_migration_pixel.json 绑定源码/APK/回执，实际安装包与本地一致，前后十二项冻结核验通过，相关主机2项通过（0.67秒）。词表冲突、溢出、取消和崩溃恢复的专门用例以及生产写入/导出/周快照接入仍需完成，未提交或推送。


## 2026-10-02：谱系迁移失败原子性边界

扩展 ColdLineage 合成用例分别建立词表冲突、旧图全局计数超过 long 可表示范围，以及线程取消场景。分别要求 IllegalArgumentException、ArithmeticException 和 InterruptedException 拒绝；每次失败后确认所有 cold_lineage* 目标表和标记均不存在、源事务结束、旧版本计数逐值保持。溢出在源图审计的精确加法处拒绝，不声称执行到了迁移边相加分支。原迁移合并及重试幂等用例继续执行。

Pixel 6a 新回执1790903632886 passed=true；native_cold_lineage_failure_boundaries_pixel.json 绑定安装 APK 与源码，实际安装摘要一致。前后十二项冻结核验通过，相关主机2项通过（0.65秒）。生产未调用迁移，异常回滚不等于进程断电/崩溃恢复证据；持续写入、图导出、周快照和长期模型保留接入继续开放，未提交或推送。


## 2026-10-02：迁移后的版本/谱系双图事务递增

ColdTrajectory.append 现在用自己的 SQLite 事务包住完整计数操作（可嵌入帧写入外层事务）。存在已完成谱系迁移时，同一次合格转移调用 ColdLineage.appendRegistered：要求已注册版本映射、已绑定词表及非负 INTEGER 原计数，Math.addExact 后写入谱系边。无谱系表的旧数据库继续原路径；存在未完成标记则拒绝写入。生产初始化仍未调用 migrateOnce，等待图导出和快照接入，避免半完成切换。

Pixel 6a 合成回执1790903844355 passed=true：实际 append 路径使版本图和谱系图从14同步增至15；人为将谱系边置 Long.MAX_VALUE 后，递增溢出拒绝，版本图回滚保持15、谱系边保持原上限、事务释放。既有迁移边界检查通过。native_cold_lineage_dual_count_pixel.json 绑定源码和实际安装摘要；前后十二项冻结核验通过，相关主机2项通过（0.67秒）。新模型映射准入、图导出/审计/周快照、长期版本回收和生产切换仍未完成，未提交或推送。


## 2026-10-02：谱系图审计与 gzip 导出接入

ColdLineage.snapshot 要求调用方已有源事务，校验最多4谱系/4注册版本映射/1024边、唯一有界词表、模型归一化与谱系/词表绑定、边引用和非负 INTEGER 计数。对固定排序字段表示计算独立摘要，明确是标签谱系，不声称概率或人类状态语义等价。NativeColdAudit 将 lineage_graph 纳入结果，保留原版本图摘要字段；ColdTrajectory.export 在相同源事务把谱系图加入 checkpoint，继续原 gzip/500000字节/原子替换约束。无谱系表的旧路径保留；未标记且非空目标拒绝。

Pixel 6a 新合成回执1790904141895 passed=true：谱系15次计数和重复摘要稳定、非法目标边拒绝并释放事务、真实 gzip 导出解压后的计数及谱系摘要与审计一致，原迁移/双图计数/失败边界通过。native_cold_lineage_audit_export_pixel.json 绑定源码/APK；安装摘要匹配，前后十二项冻结核验通过，相关主机2项通过（0.66秒）。本轮未验证谱系满配导出体积或周快照完整复制，也未启用生产迁移；长期版本回收和自动激活仍开放，未提交或推送。


## 2026-10-02：谱系图周快照复制

WeeklySnapshot 在与模型、版本边、Hot 和 Warm 相同的源只读事务/目标写事务中调用 ColdLineage.copySnapshot，复制有界谱系身份、模型映射、累积边和已知迁移标记。复制前后对固定排序字段表示计算摘要并比较，不一致回滚。旧库无谱系图时不创建伪迁移；已有标记要求已知协议值。

Pixel 6a 新回执1790904348175 passed=true：源谱系摘要在快照保持，源版本边和谱系边删除后目标仍保留7次，目标图审计通过，原保留窗口/只读/类型拒绝/取消/清理用例通过。native_cold_lineage_snapshot_pixel.json 绑定源码及实际安装摘要；前后十二项冻结核验通过，相关主机2项通过（0.67秒）。这完成短合成谱系快照接入，不覆盖满量/跨连接并发/崩溃恢复或完整周任务；新版本准入、长期回收和生产迁移仍未完成，未提交或推送。


## 2026-10-02：新版本谱系准入与完整用例 ANR

ColdTrajectory.register 在新注册和幂等重开分支的同一事务调用 ColdLineage.registerModel；迁移库要求模型版本/归一化/词表与谱系字段绑定，完整身份 Token 映射，已有谱系的归一化及词表相同，已注册版本映射不可更改。新模型注册或谱系准入失败时模型、状态与映射一起回滚。旧库无谱系表继续原行为，四模型上限保持，长期回收尚未实现。

扩展完整广播用例触发系统 am_anr(pid4139, VERIFY_COLD_LINEAGE)，随后 bg anr 杀死进程；仅旧回执1790904141895存在，不能认定本轮完整用例通过。native_lineage_admission_anr_pixel.json 保存安装绑定与系统终止证据。随后拆分独立短准入用例，实际 Android 新回执1790904764682 passed=true：重复准入幂等，归一化冲突完整注册回滚。native_lineage_admission_short_pixel.json 绑定当前安装摘要/源码；前后十二项冻结核验通过，相关主机2项通过（0.67秒）。完整迁移用例需改为可完成的后台验证入口；生产迁移、长期回收和自动激活仍开放，未提交或推送。


## 2026-10-02：完整谱系用例改为后台并取得新回执

VERIFY_COLD_LINEAGE 广播现在仅派发 NativeVerificationJob 的 cold_lineage 夹具，保存 UUID nonce 与调度时间；与 weekly 共用单一作业号34715并拒绝覆盖已待执行任务。完成写入按任务锁与停止互斥，系统 onStopJob 记录 nonce/fixture/job_id/stop_reason（此停止记录分支尚未实际触发验证）。完整谱系结果独立写 cold_lineage_job_test.json，不混用旧广播回执或完整周拟合结果。

后台 nonce bd0a6ca9-686e-446b-932d-418760d5fff3 新匹配回执1790905020205 passed=true，耗时64270.865876ms，任务随后 unknown。完整迁移失败边界、版本准入、双图递增/溢出回滚、谱系审计与 gzip 一致性共同通过，超过广播时限的原 ANR 不转记为成功。native_cold_lineage_job_pixel.json 绑定安装摘要、源码、派发与完成；前后十二项冻结核验通过，相关主机2项通过（0.65秒）。生产迁移仍未启用；完整周拟合/候选注册组合、长期回收和自动激活仍待验收，未提交或推送。


## 2026-10-02：版本元数据回收实现与待执行边界

ColdLineage.pruneVersions 在独立写事务中要求已完成谱系迁移和已注册活跃版本，保护显式活跃/回滚版本，以及 frames 和 warm_trajectory 所有 model_version 引用。使用 SQLite json_extract 读取独立引用；缺表、非文本/无效/未知版本拒绝回收，不跳过不明记录。只删除不被保护的版本边、状态、模型映射和模型 bundle，不删除谱系身份或累积边；提交前重新校验谱系快照。归档文件回收和生产激活协调尚未接入。

新增隔离合成用例覆盖 Hot/Warm/回滚引用保护、缺少版本字段时拒绝、无引用版本幂等回收及累积14次计数保留。独立 Android 构建通过，native_lineage_retention_build_validation.json 绑定此未安装版本；相关主机2项通过（0.66秒），不验证 Android SQLite json_extract 实际行为。当前完整周任务 nonce 7e291e43-a3b6-427c-a14a-8a88d09da7d1 本轮仍 active ready，回执仍为旧失败 nonce cce177df-87ef-45c0-be50-7d12d47d6086。未安装新包、未打断或重新派发。长期容量准入和生产自动演化仍未完成，未提交或推送。


## 2026-10-02：30天周版本引用的容量边界

30天 Warm 可以覆盖五个以上周版本，四版本槽位不能支撑每周更新。已迁移且标记协议有效的库现在允许最多8个保留版本，仍只允许4个独立标签谱系；未迁移库保持4模型上限。注册、Cold 审计、周快照按模型容量限定状态/版本边数，谱系边仍限1024；Warm 模型加载最多8个。版本回收仍保护 Hot/Warm 引用与活跃/回滚，不能为腾槽位删除被引用模型。

新增不加载资产的小元数据合成用例：8版本可注册并审计，9版本拒绝，6个 Warm 引用加活跃/回滚全部保护，清空 Warm 后回收6槽。独立 Android 构建通过，native_lineage_capacity_build_validation.json 绑定未部署代码；尚未验证真机用例或8个完整128D模型体积。完整周任务7e291e43-a3b6-427c-a14a-8a88d09da7d1 本轮仍 active ready，只有旧失败回执，未安装新包或重新派发。生产切换、容量压力下的安全延后和自动激活仍开放，未提交或推送。

## 2026-10-02：完整周合成验证完成与独立 SQLite 回收证据

完整周任务 nonce 7e291e43-a3b6-427c-a14a-8a88d09da7d1 完成回执1790905852673 passed=true，匹配派发身份，执行729089.591827ms，任务随后 unknown。安装包摘要075fa5e2c09bf6207000a4bf3b7991c4550a843479f9c1c99d8fa46bececf993。六轮拟合、70/30时序留出隔离、训练向量位模式一致、势能边界、快照源隔离、候选保存/保留，以及注册激活和损坏活动快照回滚共同通过。native_weekly_after_lineage_pixel.json 保存完整构建绑定和回执，pending 记录更新为完成状态。前后十二项冻结核验通过；不代表真实历史校准、Sunday生命周期或生产激活通过。

未安装的新容量/回收构建以独立 Android shell 进程执行，仅创建内存 SQLite 和128维双 Token 合成模型：8版本审计/第9版拒绝、6个 Warm 引用与活跃/回滚保护、Hot 引用保护、无效引用拒绝、解除引用后的幂等回收和累计谱系计数保留均通过，进程退出0。native_lineage_capacity_retention_android_sqlite.json 绑定 APK 706812df3b2ddb56254de7af92bfe74e5b5afd17543464b5474e92182b1de7e4、DEX、完整探针源码及结果。两个上传临时文件已删除。本证据验证实际 Android SQLite json_extract 行为，不覆盖应用UID/生命周期、8个完整模型的500KB归档门槛、模型文件回收或真实周更新节奏。

生产协调仍未接通。ColdTrajectory.transitions 目前统计保留版本边，回收后可能降低；累计谱系计数位于 lineage_graph，接通回收前须明确消费者字段语义。注册文件保留当前只保护活动/回滚指针，尚未绑定 Hot/Warm 引用；不能直接启用自动激活。整体目标继续开放，未提交或推送。

## 2026-10-02：注册备份容量修复与显式引用保护

独立 Android 进程复现原注册回收缺陷：第三次激活后将最旧快照移到 `.bak`，第四次激活出现5个快照身份，违反4个上限。崩溃缓冲区记录PID18564及 `backup-only registry snapshot escaped capacity: 5`；进程退出137源于未捕获测试断言，不是周任务配额停止。修复将 `.json`/`.json.bak` 按摘要去重，通过 AtomicFile 恢复并重验模型，再按时间/文件名回收非保护文件；不静默删除损坏快照。同一用例随后通过。

NativeModelRegistry 新增接受 retainedVersions 的激活入口，最多8个有效版本；解析现有快照，将这些版本的所有文件与活动/回滚文件共同保护。非资产且缺失的引用拒绝切换。新入口容量为8，原入口保持4。调用者必须提供一致的 Hot/Warm 引用集合并协调数据库写入，本入口不自行读取数据库，也没有证明跨数据库/文件原子性。

真实 Android 文件/AtomicFile、合成128维双 Token 模型验证通过：8个引用全部受保护时拒绝第9个版本且活动指针不变，未知引用拒绝，释放旧资产引用后回收该槽并保存新模型。已有陈旧激活拒绝、继承谱系、损坏快照拒绝及回滚位模式用例共同通过。native_registry_reference_retention_android.json 绑定依赖APK、优先加载的DEX、覆盖源码、完整探针及退出0结果，明确这不是已安装应用执行。独立完整构建/签名通过，相关主机3项通过（1.122秒），结束后十二项冻结核验通过；测试临时文件已删除。

自动生产切换仍需数据库事务、模型注册、活动指针、进程模型重载及中断恢复协调；容量拒绝不能记为周更新成功。新代码尚未部署，未提交或推送。

## 2026-10-02：共用保留版本引用事务

ColdLineage.retainedVersions 提取 Hot/Warm 所引用的已注册版本集合，强制调用者持有 SQLite 事务；缺少任一源表、非文本或未知版本拒绝。Hot/Warm 行数分别超过172800/86400也拒绝读取，避免引用扫描没有记录数边界。数据库版本回收已改用该共用入口；注册文件激活的显式集合入口可接收同一快照，但生产协调尚未调用它，不能声称跨数据库和文件切换已经完成。

独立 Android SQLite 内存合成用例通过：事务外读取被拒绝、事务内返回实际 Warm 引用，并重新通过8版本容量、Hot/Warm/回滚保护、无效引用拒绝及幂等回收/累计计数保留。native_retained_reference_transaction_android.json 绑定优先DEX、依赖构建、覆盖源码和退出0结果；不覆盖满容量行数或多连接并发。临时设备文件已清理，新代码未安装，未提交或推送。

## 2026-10-02：数据库准入先于注册指针发布

NativeModelRegistry.activateWithHistory 在注册锁内开启独立历史数据库事务，要求已完成谱系迁移，读取共用 Hot/Warm 引用集合并保护文件。候选仍通过原父版本/势能/固定参数门槛；先保存和重验快照，注册当前与候选 Cold 模型及谱系，校验图并提交数据库，最后发布 AtomicFile 活动指针。异常路径释放事务；提交前取消回滚准入，提交后取消保留旧指针和可重试的已注册候选。没有将数据库与文件称为同一原子事务，未自动回收数据库槽位或启用真实周切换。

独立 Android 合成数据库/文件测试通过：正常发布前完成准入；通过阻塞 current.json.new 注入发布失败，旧活动指针不变且数据库保留已准入候选，解除阻塞后重试不重复注册；数据库 INSERT 拒绝触发器注入准入失败，活动指针不变、数据库事务释放；无绑定历史引用拒绝切换。已有注册/损坏回滚/备份容量/八版本引用保护回归同时通过。native_history_model_admission_android.json 绑定依赖APK、覆盖DEX及源码、完整探针和退出0结果。独立完整构建/签名通过，相关主机3项通过，结束后十二项冻结核验通过；测试临时文件已删除。

证据不覆盖真实进程死亡、跨连接并发、容量压力下的准入回收、服务重载或 Sunday 自动执行。生产维护仍保存候选而不调用此入口；新代码未安装，整体目标未完成，未提交或推送。

## 2026-10-02：模型重载准入与服务切换调用点

NativeModelRegistry.activeIfChanged 在注册锁下先比较活动版本；有变化时读取快照，并在独立数据库事务中要求 Cold 模型的 normalizer/bundle 与所加载模型一致，未准入模型拒绝返回。EdgeService 在每次采样前调用该入口；模型变化时发布 volatile 引用并重置速度/CSD、待处理比较、Warm/Cold前帧和 shadowToken，下一帧成为连续性边界。Binder 历史查询捕获一次模型引用，使 Token、模型身份及预测字段在一次请求内一致。未变化时仍读取小指针文件；其新增耗时/CPU尚未测量。

独立 Android 合成数据库夹具通过：已准入新模型可返回、相同版本返回无变化、删除其数据库准入后拒绝重载且事务释放，再通过准入/发布失败重试及注册/保留回归。native_model_reload_admission_android.json 绑定依赖APK、覆盖DEX、源码及实际结果，临时文件已删除。4项相关主机组件回归通过（1.732秒）。这些检查不执行 EdgeService 的真实采样或重置调用点；服务改动仅编译通过，尚未部署或验证生命周期。真实周维护仍未调用自动激活入口，目标继续开放，未提交或推送。

## 2026-10-02：周候选接入准入控制流（未部署）

WeeklyMaintenanceJob 的合格候选保存后，在可写历史库完成谱系迁移并调用 activateWithHistory；成功记录 activated_model_version/candidate_activated=true，写周回执时不再强制覆盖为false。一般准入拒绝保持 accepted=true、candidate_activated=false及明确 activation_pending/error；注册锁或SQLite锁异常请求重试且不写完成回执。生产旧安装包保持原候选保存行为，未派发真实维护。

主机回归先失败于 accepted weekly candidate was not activated or receipt overwrote activation，修复后运行真实 Job 控制流通过。扩展验证容量拒绝保留候选及两类准入锁退避；相关3项主机检查通过，Android源码编译通过。native_weekly_admission_control_flow.json 绑定源码及失败/通过边界，平台、拟合和准入调用在本检查中均为替身，不是 Android Sunday 或真实校准证据。

待补关键中断边界：活动指针发布后、周回执写入前进程停止时，重试必须识别该窗口已经切换，不能再次拟合并二次激活。此幂等衔接尚未实现，因此当前改动不应部署；服务重载生命周期及性能也仍待验证。未提交或推送，完整目标未完成。

## 2026-10-02：周窗口与活动指针共同发布

activateWeeklyWithHistory 把 weekly_window、候选摘要和已激活版本与活动指针写入同一个 AtomicFile。相同窗口重试在读取候选或启动数据库准入前返回已发布身份；回滚和非周切换保留最近周标记。读取指针要求三个周字段同时存在且日期/摘要有效。WeeklyMaintenanceJob 在拟合前读取该记录，恢复 accepted/candidate_activated/activation_recovered 回执，不将恢复称为新拟合结果。

独立 Android 合成测试通过：注册器重开可恢复发布窗口；相同窗口以不存在的候选摘要重试也不重复准入或修改指针；回滚仍保留窗口幂等性。准入、发布失败、注册回滚及保留回归共同通过。native_weekly_publication_idempotence_android.json 绑定依赖APK、覆盖DEX、源码和退出0结果。真实 Job 控制流主机替身检查证明发布记录恢复时跳过会失败的拟合路径，相关3项通过。临时测试文件已清理，未安装新代码。

该证据覆盖重开和重试语义，没有执行真实进程强杀、Android Sunday调度、服务重载生命周期或性能测量；这些仍需后续验证。整体目标继续开放，未提交或推送。

## 2026-10-02：周维护整合构建与完整 unittest 回归

当前源码通过 `python3 -m unittest discover -s tests -v`：334个测试、36.561秒、退出0。该计数是 unittest 发现范围，不能直接替代其他测试运行器的历史计数；日志中的 physical_runner 锁冲突是临时目录内的预期互斥测试，未运行真实采集。整合后的独立 Android 包3933087字节，v2/v3签名验证通过。native_weekly_integrated_host_build.json 保存完整日志、当前源摘要及构建绑定，未安装该包。

当前仍缺真实服务模型切换/连续性重置、进程强杀恢复、Sunday运行及新增检查开销的真机证据；科学、隐私及性能验收边界也未据本轮测试关闭。整体目标未完成，未提交或推送。

同一当前源码的完整 pytest 复核通过：334 passed、156 subtests passed、33.51秒、退出0；日志加入 native_weekly_integrated_host_build.json。README测试徽章按当前334项更新，历史348项记录保留为旧范围结果，不推断计数变化等于行为回退。

## 2026-10-02：服务重载实际调用点验证

独立 Android shell 进程构造真实 EdgeService 对象并挂接仅提供临时文件目录的 Context，在128维双 Token 合成模型和内存SQLite中执行私有 refreshModel 方法。通过：已准入新版本替换服务模型并写正确重载回执；速度连续性、CSD dwell、Warm前桶、Cold前帧及shadowToken重置；未变化时不再重置连续性。既有准入/周幂等/文件保留回归共同通过。

128次未变化检查均值0.1781165ms、P95 0.207845ms、最大0.224202ms（包含反射调用）。这是短批次合成环境的小指针检查耗时，不是完整采样/推演的15ms门槛、稳态CPU或资产模型切换耗时。native_service_refresh_callsite_android.json 绑定依赖APK、覆盖DEX、源码和退出0结果，结束后十二项冻结核验通过，临时文件已删除。证据不执行 onCreate、Android服务绑定、传感器采集或进程强杀恢复。新代码仍未安装，目标继续开放，未提交或推送。

## 2026-10-02：发布后真实独立进程中断恢复

在唯一合成目录创建持久SQLite、候选及注册文件；独立写入探针发布窗口2026-10-04后停驻，尚未写 weekly_maintenance.json。命令行核对PID31647确属该合成探针后，仅向它发SIGKILL；执行句柄终止，退出137。新进程PID31990重开数据库及注册器，恢复已发布周记录，确认活动模型已有数据库准入；同窗口以不存在候选摘要重试，指针字节与模型数量2均保持不变。读取进程退出0，两进程模型版本一致。

native_weekly_publication_process_kill_android.json 保存完整探针、执行身份、SIGKILL边界、不同进程回执、依赖APK/覆盖DEX/源码和清理证据。结束后十二项冻结核验通过，独立合成目录与上传文件已删除。该证据覆盖真实Android进程死亡，但不是系统JobService停止/退避、真实Sunday窗口、应用服务绑定/采集或用户历史执行。整体目标继续开放，新代码未部署，未提交或推送。

## 2026-10-02：数据库满容量回收并入准入事务

ColdLineage 的独立回收入口共用 pruneVersionsInTransaction；注册激活在数据库已满保留容量时，完成候选/父版本重验后，于同一历史写事务回收无引用旧版本，再准入新版本。Hot/Warm、当前与回滚版本继续保护，谱系身份和累积边不删除。准入异常会回滚数据库删除；文件回收仍不与SQLite原子化，也不承诺保留已无引用版本的注册文件。

实际 Android 合成8版本容量测试通过：所有版本受 Warm 引用时拒绝第9版且指针/数量不变；解除最旧引用后，注入INSERT准入拒绝，旧数据库版本恢复、指针不变、事务释放；解除故障后回收并准入，容量仍8、累计谱系计数11保持。已有重载、周幂等、注册回滚和文件保留回归共同通过。native_history_capacity_admission_android.json 绑定依赖APK、覆盖DEX、源码和退出0结果；相关3项主机检查通过，结束后十二项冻结核验通过，临时文件已删除。

该结果是128维双 Token 合成版本池，不覆盖8个完整资产模型的500KB归档门槛或真实周更新节奏。新改动未部署，整体目标继续开放，未提交或推送。

## 2026-10-02：八个完整模型 Cold 归档体积

独立 Android 测试以项目资产的完整128维/16状态模型为起点，使用固定随机种子48生成七个有完整参数和势能字段的合成候选；每轮改变中心并保持固定归一化，合成验收指标不是实测拟合。保留八个模型，填充2048条版本转移边和256条共享谱系边后，生产 ColdTrajectory.export 实际输出368998字节，低于500000字节；模型数量8与转移总计1021403526254吻合，进程退出0。

native_cold_eight_full_model_size_android.json 保存完整探针、资产来源输入、执行依赖与DEX绑定、实际归档摘要及清理证据。唯一合成目录和上传文件已删除，未安装或读取用户历史。该结果关闭此具体完整形状夹具的体积未知项，不能证明所有未来拟合参数、四谱系最大密度或其他高熵内容均低于500KB；生产超限拒绝仍是必要门槛。真实生命周期与其他科学/隐私/性能目标继续开放，未提交或推送。

## 2026-10-02：Cold 归档上限接入激活前准入

先复现失败：向保留且受引用保护的旧模型 bundle 加入固定种子高熵填充，新候选仍可激活，归档上限没有成为发布门槛。修复在历史准入事务提交前运行 NativeColdAudit 和生产 ColdTrajectory.export；使用独立临时归档验证500000字节上限，成功/失败均清理归档及.tmp文件。超限异常回滚数据库准入，活动指针不发布。

同一 Android 合成用例修复后通过：超限拒绝、旧指针和数据库数量3保持、事务释放、临时文件无泄漏，恢复有界内容后重试可激活。native_cold_archive_admission_gate_android.json 保存先失败退出1到通过退出0、源码/DEX/依赖绑定和完整探针。相关3项主机检查通过，上传临时文件已删除。该检查针对高熵合成填充，不替代经验模型有效性或真实生命周期证据。新代码未部署，整体目标仍未完成，未提交或推送。

## 2026-10-02：版本边整数计数溢出拒绝

新增 Android 回归先失败：未迁移版本边的SQLite n+1没有拒绝Long.MAX_VALUE递增。追加现在在原事务内要求已有n为非负INTEGER，通过Math.addExact计算下一值再绑定写入；错误路径回滚，随后谱系递增仍共用该事务。

修复后未迁移与已迁移两个分支通过：Long.MAX_VALUE递增拒绝、INTEGER原值保持、谱系计数保持；REAL 1.5计数拒绝且原值保持、事务释放。原有 Cold 幂等回填/模型边界/词汇绑定/命名空间容量与原子大小拒绝用例也在Android中通过。native_cold_counter_bound_android.json 绑定失败退出1到通过退出0、源码、DEX与完整探针；上传文件已删除。该门槛限于每条边的64位计数，不证明总计无限增长或多年真实数据有效。新改动未部署，未提交或推送。

## 2026-10-02：平台方法版本保护

本地 Android35 SDK 的 api-versions.xml 标记 SQLiteDatabase.beginTransactionReadOnly since35、JobParameters.getStopReason since31。WeeklySnapshot已有API35拒绝门槛；Warm/Cold审计现在同样在旧系统抛出明确 UnsupportedOperationException，避免调用不存在的方法。调试停止回执在API31以下记录 stop_reason=null/stop_reason_available=false，不伪造停止原因。APK最低安装API26未改变，但受保护周快照/审计明确要求35，不声称完整功能在26可用。

主机Warm回归先在旧版本替身下出现 NoSuchMethodError，修复后API26/34在调用缺失方法前被拒绝，API35的整数/浮点SQL检查继续通过。相关3项主机检查与Android源码编译通过；native_android_version_guards.json 保存本地SDK元数据摘要和源绑定。这不是旧Android真机执行或完整平台兼容性认证，其他验收继续开放，未部署新改动或推送。

## 2026-10-02：保留版本与累计谱系计数分开输出

ColdTrajectory.countSummary 在同一数据库事务中报告 retained_version_transitions 与累计谱系计数/可用性；已迁移时校验协议、最多1024条非负INTEGER边及整数总计。未迁移时累计字段为null/不可用。服务状态保留旧 cold_transitions 的保留版本含义，另提供 cold_counts；Cold归档及短回执均带 count_summary。回收导致版本计数降低不再只有一个无法区分的数字。

独立 Android SQLite 合成用例通过：迁移前累计不可用；两个版本合计14次，回收一版后保留版本计数7、累计谱系14、可用=true；已有容量/引用保护/幂等回收回归通过。native_cold_count_summary_android.json 绑定源码、依赖APK、覆盖DEX与退出0结果，上传文件已删除。服务与归档调用点编译通过，但未运行真实状态UI或多年历史。新增统计检查的整条采样开销仍待测，新代码未部署或推送。

## 2026-10-02：真实系统绑定与销毁后的规范输入拒绝

独立测试包与主应用同签名，使用独立UID/私有目录，实际由Android绑定同一EdgeService代码的独立进程；不调用startService，也不启动采集。先复现：解绑并确认能力接口已拒绝后，旧Binder仍接受规范向量。修复在规范输入处理的同步块内检查destroyed和engine，已销毁或未初始化时拒绝。

同用例修复后通过；仓库内lifecycle_probe及build_native_lifecycle_probe.py最终重建/签名/执行同样通过，确认服务进程与探针进程不同、无edge.sqlite、无采集、解绑销毁后拒绝。最终APK SHA256 d62a5f5b20b530cf13d8a0c6c37485d3be472e5ee93deed0b1cfba7ec7486966，设备回执sdk_int=37；构建使用Android35 SDK，不能据此把当前设备称作API35。native_os_bound_service_lifecycle_android.json保存失败/通过、最终源码及依赖绑定。十二项冻结核验通过；独立测试包及其合成数据已卸载，主应用未替换。

此证据覆盖系统创建、绑定和解绑销毁，不覆盖采集服务长期运行、JobService停止或Sunday周更新。整体目标继续开放，新生产代码未部署或推送。

## 2026-10-02：实际调试JobService系统超时停止

独立测试包增加现有NativeVerificationJob/Receiver的声明，派发唯一合成weekly任务nonce b95cf7c1-0939-40e5-9557-2654fa1a6cf6。初始待机桶NEVER使WITHIN_QUOTA不满足，不能当作运行中；仅将此测试包改为active后，系统查询确认active ready。随后对明确包名/任务34715触发系统timeout，实际onStopJob写入同nonce、stop_reason=3/available=true。调度器随后退出23并明确unknown；私有cache为空，没有成功完成回执；独立进程16002线程快照无traceform-debug-weekly工作线程。

native_debug_job_system_stop_android.json绑定APK/源码、运行与终态、停止回执、临时目录及线程证据。独立包及合成数据已卸载，主应用未替换。此检查覆盖现有调试长任务的系统超时取消，不等同生产WeeklyMaintenanceJob的Sunday拟合/退避/真实历史或所有停止原因，也不把停止当作算法通过。目标继续开放，新生产代码未部署或推送。

## 2026-10-02：当前设备月度计算链性能复测

重新构建当前C++核及独立APK测量，设备getprop确认为Pixel 6a/API37。86400对合成历史、128维归一化、16状态量化、300×120推演：独立暖计算1.169841ms、CDF0.264119ms、推演0.874471ms，首次查询1.511679ms，核心子进程峰值2916352B。首次谱拟合258.512289ms及1000对remove/add4.207235ms另计，不能并入15ms通过。

实际APK线程允许CPU0–3，五次分离计算链7.855–9.740ms，融合链6.665–7.992ms，首次融合查询11.488647ms，均低于15ms。最大精确CDF差8.881784197001252e-16、相同随机数占用计数一致，句柄/非法输入检查通过。首次拟合2516.859173ms，另一次2107.973675ms。此结果是当前测试包/系统的合成范围，不能追溯替换旧运行的失败结果，未观测频率或温度，也不推测历史差异原因。

native_current_monthly_compute_android.json保存实际回执、APK/依赖/源码和独立基准摘要；独立测试包及基准已移除，主应用未更新。证明当前拟合后计算链满足15ms及独立核心内存5MB门槛，不证明SQLite历史扫描、拟合总延迟、完整应用内存或连续实时增量维护。其余科学/生命周期/隐私验收继续开放，未推送。

## 2026-10-02：当前完整候选构建与全套主机验证

最新工作树python3 -m unittest discover -s tests -q通过334项，35.482秒；git diff --check通过。完整原生APK重新编译/构建并验证v2/v3签名，3937183B，SHA256 16e28dbadc1a1bb0eb6f3e498e1595f5c206e305952736c4471d1f7d9193411c；使用既有开发签名，包含公开验证器与私有参与者运行库。native_current_release_candidate_build.json绑定完整生产源摘要、库摘要与测试范围。

该候选尚未安装，未提交或推送。主机验证不替代Android最新完整周夹具、生产Sunday窗口、经验连续动力学/分叉识别、真实干预因果反馈或全状态群体隐私验收；这些仍需按各自范围取得证据。

## 2026-10-02：废弃注册表写入与准入归档回收

新增独立Android回归先退出1：格式有效但尚未发布的SHA.json.new未被回收。现在activate持有注册表lease后、读取候选/数据库准入前，删除精确匹配SHA.json.new或cold-admission-数字.json.gz/.tmp的废弃文件；不删除current.json.new、bak、已发布快照或其他名字，删除失败则抛错。注册表锁保证不会并发删除同入口仍在写的文件。

同用例修复后通过，无关文件字节保持，激活与回滚身份保持；数据库准入失败/指针发布故障/重试幂等/周标记回滚和bak容量回收回归在Android共同通过，终态退出0。相关5项主机测试通过(1.159s)，diff检查通过。native_registry_temporary_reclamation_android.json保存失败到通过、DEX/库/源绑定和完整探针；独立探针目录已删除，主应用未替换。

这是构造废弃文件的恢复测试，不是每个AtomicFile/归档写边界真实SIGKILL。先前完整周链任务nonce b7964708-7e32-4693-b9b2-f0062350bda9仍在独立包运行，源绑定不含本次清理改动，不将它称作修改后全链验证。新生产代码未部署或推送，目标继续开放。

## 2026-10-02：候选源绑定的完整周链合成任务通过

本次独立Job34715、nonce b7964708-7e32-4693-b9b2-f0062350bda9已经终止：系统查询明确unknown/退出23，completed_epoch_ms1790912038989，passed=true，execution_ms741738.029822。回执nonce与派发一致。覆盖实际SQLite源摘要/类型与跨会话边界、快照隔离/只读/释放、六轮拟合、70/30留出数据不污染拟合或势能、训练spool位一致、候选持久化/容量回收、注册激活与损坏回滚。

native_current_weekly_probe_android.json绑定探针APK cb4a2c2abe9cd87ae8093eb70dcdc5deb2b993f187ec4ea47346d363eeac2523及完整候选依赖16e28dbadc1a1bb0eb6f3e498e1595f5c206e305952736c4471d1f7d9193411c。pending记录已明确转为terminal_passed。结束后独立包与合成数据卸载，十二项冻结核验通过；主应用未替换。此构建不含随后加入的注册临时文件清理，该分支已有独立Android回归，不伪称本次全链包含它。此结果也不等同生产Sunday调度、真实历史校准或人类有效性验收。目标继续开放，未推送。

## 2026-10-02: fixed uniform16 private SDK coverage

UniformPrivateAlignment executes only the accepted fixed six-bit permutation
circuit with original-precision final loss, complete expected-statement checking,
128 MiB immutable snapshot and resource admission. All fourteen separate-party
synthetic SDK cases now have matching-nonce passing receipts in
uniform_private_sdk16_cases_completed_pixel.json. Android runs one participant
inside the isolated JobService; Mac runs the other with only its own input.
Cases include transpositions, reversal, differing scales, an 80-bit objective,
invalid metric/mass shapes, session mismatch and nonce changes. Invalid models
require the explicit witness-rejected result; other exceptions remain failures.
Each task becomes terminal and its app-private snapshot is removed.

Device elapsed range is28176.922–33807.041ms. The first changed-nonce attempt
failed initial memory admission at963072000 available bytes. Its failure remains
in the receipt. After terminal confirmation and observed resource recovery,
one retry passed at1001070592 available bytes, with unchanged929 MiB admission
and192 MiB running reserve. This is not a peak-memory guarantee. The test app
and USB forward were removed; twelve frozen verifiers passed. All352 host
tests pass in39.761s, including independent Java checking of prepared statement
encoding, high objective bits, altered fields and invalid-witness transport
session handling. The primary app was unchanged. Unknown-score approvals,
packed-v2 public proof/owner integration and final production publication remain
open; synthetic test identities do not establish real enrollment or security.

## Current-source SDK candidate (2026-10-02)

The generated SDK now exports `UniformPrivateAlignment` and its recursive
package dependencies. A regression first reproduced an external client's
failure to import the unknown-score entry, then passed after the export fix.
The regression compiles current Android sources, builds the SDK, compiles an
external consumer and checks the package dependency closure.

A current-source development candidate APK builds and verifies its signature;
see `artifacts/alignment_v1/current_source_sdk_export_candidate.json`. Core JNI
is rebuilt; the public verifier and private participant reuse the existing
candidate's native executables. This candidate has not replaced the primary
app. The current device memory observation is below the unchanged sixteen-node
admission threshold, so no new unknown-score device run was started.

Approved unknown-score requests now perform a read-only durable session check
under the query lease, after owner authentication and before memory admission
or circuit snapshot creation. The launch-time reservation rechecks the same
ledger and retains its fsync behavior. A spent-session low-memory regression
failed before the change and passes afterward; an unlaunched fresh request
still consumes no session. Full host tests pass (357, 42.346s), followed by the
final expanded resource regression (2, 3.358s). The development candidate was
rebuilt and its signature verified. This does not establish normal unknown-score
or app-restart replay acceptance on Android; the primary app remains unchanged.

The native subset now derives `conn_wifi_rssi_norm` from available Wi-Fi
transport RSSI on API 29+. It uses clipped `(dBm + 100) / 70`, matching the
Python coordinate; absent transport info and invalid RSSI remain missing.
With multiple available Wi-Fi transports, it uses the strongest reported RSSI.
No SSID, BSSID, scan result or location identifier is retained by this path.
Hardware availability and current-source device acceptance remain unverified.

The production service and isolated read-only Wi-Fi probe share
`NativeWifiSource.read`. Host fixtures cover absent/redacted transport info,
invalid RSSI, selection of the strongest available Wi-Fi transport, disconnects
and the API 26 guard. A capability probe does not establish production service
retention, continuous sampling or overall acquisition acceptance.

Collection restart resets cached feature values, sensor timestamps, derivative
references and rolling windows before listeners and sampling begin. A quick
stop/start therefore requires fresh readings and normal history warm-up; it
cannot derive features across the stopped interval. The controlled Java
regression covers removed readings and motion, pressure, electrical, optical
and ordinal-history warm-up. It does not replace device lifecycle validation.

Native usage counts require explicit usage-access permission. The source retains
only counts from the last 120 events within a 24-hour query, caches for at most
60 seconds with the original query timestamp, and clears on collection reset,
permission withdrawal or clock discontinuity. Activity resumes are capped at 50;
interactive/keyguard-hidden checks at 20 are a proxy for unlocks. These are event
counts rather than per-second rates, and do not establish exact historical
Python dumpsys-window parity. The query scan is bounded to 50,000 events; denial,
null services, invalid order or overflow remains missing. No package names or
individual usage events are retained.
