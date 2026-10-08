# Stage 11–14 completion audit

Updated through 2026-10-09, including the ordered four-step work and guarded
sixteen-node follow-through (baselines `7a39a57` and `91d6659`). Historical full-suite counts retain
their original test scope.

This audit retains the scope of the supplied roadmap. It distinguishes available
implementation and bounded evidence from the evidence needed for full completion.
It is not a release approval or a claim that the four stages are complete.

| Original requirement | Current evidence and boundary | Completion evidence still required |
| --- | --- | --- |
| Continuous 128D drift and diffusion | `traceform/dynamics/continuous.py`; controlled full-rank second moments in `full_128d_covariance_recovery_host.json` | Held-out empirical drift/diffusion accuracy with source-bound observations; the legacy archive has 5961 26D rows and zero eligible 128D pairs, while device 128D inventories and chronological diagnostics now exist; neither establishes scientific acceptance |
| Euler–Maruyama simulation | `additive_sde_refinement_host.json`: known 1D OU convergence | Validation of the intended empirical 128D model and appropriate time steps |
| Long-range/fractional memory | Additive fractional driver and residual profile diagnostics exist; source explicitly disclaims empirical identification | Source-bound identification, uncertainty and comparison with Brownian/null alternatives; archived H=0.0623 is a finite-lag MSD diagnostic, not an accepted driver |
| Conservative potential inversion | Smoothed graph potential/curvature diagnostics exist | Evidence that a conservative potential describes the empirical drift, or explicit qualification of the nonconservative component |
| Continuous daily and weekly kernel | Existing smoothing implementations and `continuous_time_validation*.json` | Verify current release retains the accepted boundary-continuity and weekly-conditioning behavior |
| Configurable haptics/notifications/volume/color | Actuator and restoration implementations and bounded device receipts exist | Current-source device verification and separately authorized physical use; synthetic dispatch is not efficacy |
| Intervention after recommendation and CSD | Authorized field daemon restored active; prolonged-dwell trigger deployed with source checks (`intervention_trigger_prolonged_dwell_20261004.json`) | Actual execution under the new trigger and decision-to-actuator evidence; deployment receipt records no delivered intervention yet |
| 5–15 minute follow-up | Delivery-acknowledgment feedback clock with invalid-clock censoring; one authorized real pulse with complete ten-minute post-delivery window in a separate device-private diagnostic ledger | Natural-trigger follow-up and suitable controls; diagnostic state change is not causal attribution |
| Contextual bandit reward/fatigue | `traceform/intervention/closed_loop.py` and native policy | Current-source policy validation; causal efficacy requires suitable intervention/control evidence |
| Hot 48h 1Hz 128D | Primary upgrade exercised 48h retention; bounded isolated sampling and provenance audit pass | Long-term 1Hz retention and complete source observability; Termux field sampling has a different cadence |
| Warm 30d 30s tokens/potential | Warm migration check passed during primary upgrade; daily device-local SQLite snapshots retain 14 days | Long-term end-to-end retained-source consistency; daily backups do not replace the warm-tier contract |
| Cold topology/energy under 500KB | Admission gate and lineage/export evidence | Empirically meaningful bifurcation parameters and bounded long-duration retention; small synthetic archives do not prove years of validity |
| Sunday automatic audit/calibration/reordering | `native_current_weekly_probe_android.json` passes complete synthetic debug task | Actual production Sunday delivery and authorized source-bound calibration; debug job does not establish those |
| Atomic model activation and recovery | Registry/admission/publication recovery receipts; later orphan cleanup separately checked | Bind the final release to all relevant changes and verify final device behavior |
| Zero-raw-upload manifold comparison | Separate-party input authenticated MPC tests, including 14 standalone sixteen-node cases | Real-owner enrollment, authorization/query controls and independent security assessment |
| GW alignment | Uniform permutation search with exact final loss; general-mass small-shape transport checks | Preserve bounded/non-global scope; uniform permutation descent is not unrestricted GW optimality |
| Android private SDK | Fourteen known-statement cases plus18 guarded sixteen-node cases and2 CPU-phase cancellation/resource-permission withdrawal checks in `guarded_unknown_score_sdk16_android.json`; aborted children reaped, snapshots removed and sessions retained; bounded real allocation verifies admission rejection and readmission; real CPU-phase pressure verifies production withdrawal, cleanup, app-restart replay and fresh-session recovery | Actual system LMK remains open; bounded real withdrawal is not a worst-case memory proof; production privacy/deployment gates remain separate |
| Packed-v2 public proof and owner approval | Dedicated uniform-permutation proof relation; previous six public/four signed SDK cases and two-node known-statement binding; five sixteen-node actual discovered-output proof/signature cases plus actual-app-restart replay in `discovered_unknown_score16_proof_android.json` | Distributed private proving, real owner enrollment and production key/setup provenance; the synthetic prover holds both models; v1 certificates cannot silently authorize v2 outputs |
| Unknown-score private computation workflow | Signed unknown-score SDK entry and durable reservation;22 host MPC guard cases;18 guarded sixteen-node Android/Mac cases and2 CPU-phase abort checks; actual discovered statement bound to a centralized synthetic signed proof; computation and proof ledgers unchanged after app restart | Distributed private proving and real-owner enrollment remain open; bounded case coverage is not a security audit |
| Native <15ms / <5MB core | `native_current_monthly_compute_android.json`: isolated fitted synthetic compute passes | Preserve measured workload scope; it does not prove whole-app/fit/history-scan/private-MPC memory below 5MB |
| Standalone APK/Service/Binder | Native APK and actual separate-process Binder/lifecycle evidence exist | Primary upgraded to `23c1065` with same signer, rollback rehearsal, eight checks and bounded collection; long-term behavior and release identity remain open |
| Complete native 128-feature acquisition | 51 extractor outputs and digital-context formulas ported with host and device parity; dataflow and observability registry distinguish measured inputs, proxies and missing inputs | Verify intended quantities on hardware; 12 port outputs lack required native inputs, and parity on identical base inputs does not establish 128 independently measured quantities |
| Final USB deployment | `primary_app_upgrade_20261004.json`: primary installed in place, data kept, rollback rehearsed and health checks passed | Long-term operation and actual Sunday task; receipt ended with primary force-stopped, Termux running; development signer is not release identity |
| Repository publication | Public implementation and bounded source/build evidence are pushed to `https://github.com/haeliotang/traceform.git`; selected device-derived inventory aggregates are also committed; two newer local evaluation reports remain untracked | Verify each new push against the remote commit; publication does not close remaining scientific or deployment gates |

## Next execution order

1. Use new independent holdout data for any revised normalizer/manifold candidate;
   the October 8 multi-day candidate failed its topology gate and remains inactive.
   Empirical dynamics accuracy still requires separate evidence.
2. Observe actual authorized interventions and complete follow-up windows under the
   deployed prolonged-dwell trigger; separate recommendations from causal evidence.
3. Verify long-term collection, actual Sunday calibration and actual system LMK recovery.
   Bounded production withdrawal under real running-memory pressure now passes.
   Primary upgrade and rollback rehearsal are already recorded, not pending installation.
4. Complete distributed private proving with the unchanged two-owner security
   requirement, production setup provenance, real-owner enrollment and independent
   security assessment. Synthetic discovered-statement binding is now recorded.

## October 9 prospective readiness and natural gate audit

A device-local read-only audit at October 8 22:15 UTC (October 9 local time)
verifies the frozen plan, 2,048 training frames and code archive, with unchanged
file hashes before/after. The October 9–12 UTC holdout has not started: zero
holdout rows and `pending` with no blockers are expected. Every future date is
labeled `not_due`, not missing coverage. Seven retained snapshots plus the live
store are inventoried; their current availability does not guarantee future
four-day coverage. Evaluate only after October 13 00:00 UTC, preserving the
fixed minimum of 2,300 frames per day and coordinate binding. No scores, model
selection or activation occur in this audit.

The recorded last-24-hour natural gates contain 138 CSD candidates, all rejected
at `dwell_below_threshold`. All 138 actuator snapshots show enabled, not silent
and not environment-disabled. Retained October 7/8 frames have maximum dwell 3
against the unchanged threshold 60 and zero frames above threshold. These
receipts do not exercise downstream counterfactual/history/cooldown checks;
natural trial and failed-delivery ledgers are empty. This explains observed
non-delivery without lowering the gate or proving lack of intervention value.

Retained dated October 8 prediction receipts contain 81 starts, 80 successes and
one failure (`latest segment requires two continuous pairs`), no timeout and no
retained started job missing its terminal receipt. The 119 undated historical
failures remain separate. Numerical success does not establish forecast accuracy.
Final health is active with unchanged physical/edge PIDs 12155/27649, 55 frames
in 30 minutes and maximum interval 44.18 seconds. Fifteen affected host tests
and twelve frozen checks before/after pass. Detailed records stay device-private;
aggregate evidence is in `prospective_readiness_and_natural_gate_audit_20261009.json`.

## October 9 cached-process pressure and interrupted-snapshot recovery

A subsequent explicit user authorization permits exactly one 64 MiB-floor retest.
Automatic review allows it. The holder reaches its unchanged 1024 MiB cap and
observes a minimum MemAvailable of 367,260 kB (358.65 MiB); it never reaches the
64 MiB pre-allocation boundary. The target survives and the pressure child exits
zero. This still does not verify actual system LMK. Controlled cleanup separately
records USER_REQUESTED (reason 10), removes one orphan snapshot, rejects replay
without ledger mutation, and completes a new authenticated session in 30.608
seconds with Android/Mac/reference parity (ledger 32 to 64 bytes). Three focused
host tests and twelve final frozen checks pass. Collectors retain PIDs 12155/27649.
The isolated app and helper are removed; committed test defaults return to the
160 MiB floor. See `authorized_64mib_cached_lmk_android.json`; its source hashes
and explicit one-run overrides bind the temporary 64 MiB test configuration.


Three bounded real-pressure attempts allocate up to 880, 896 and 960 MiB.
The isolated cached app survives all three; actual system LMK remains unverified.
The final attempt uses a 160 MiB kernel MemAvailable pre-allocation floor and
observes a minimum of 162,772 kB; this floor is not a guaranteed minimum for
changing system memory. A proposed 64 MiB floor is rejected by automatic approval
review and is not executed. No further pressure follows that rejection.

The SDK now removes abandoned `private-circuit-approved-*.txt` regular snapshots
under the matching exclusive query lease before owner/replay/admission checks.
It preserves legacy files, unrelated names and symlinks. A regression first
reproduces retained snapshots after spent-session replay; nine affected host
tests now pass, including wrong-lease rejection and idempotent cleanup.

Two controlled abrupt stops verify cleanup and replay recovery. Android records
reason 10 (USER_REQUESTED), not reason 3 (LOW_MEMORY); neither closes the LMK gate.
The final rebuilt isolated candidate is first prepared at cached priority 905
without pressure. After its controlled stop, the snapshot count changes from
one to zero, replay is rejected and the 96-byte ledger is unchanged. A fresh
approved authenticated computation completes in 32.116 seconds, matches the
independent Android/Mac reference and appends exactly 32 bytes (128 total).
The prepared interrupted request is waiting for its peer, not in authenticated
CPU-phase MPC. CPU-phase system-LMK recovery also remains open.

All twelve frozen checks pass before and after. At 19:04 UTC October 8 (October 9
local time), field collection has 55 frames in 30 minutes, a maximum interval of
39.24 seconds and active mode without a silence marker. Physical/edge PIDs
12155/27649 remain unchanged. The isolated app and pressure helper are removed.
The primary APK, field runtime and production 929/192 MiB thresholds are unchanged.
See `cached_lmk_attempt_and_snapshot_recovery_android.json` and
`cached_lmk_snapshot_recovery_host.json`. Historical full-suite counts retain
their original scope.

## October 8 real running-memory withdrawal and recovery

`running_real_memory_withdrawal_android.json` records one authenticated sixteen-node
MPC request with a separate Mac participant. After the host observes the real
independent MPC phase and 63 additional Android child CPU ticks, the isolated
fixture starts a disposable anonymous-memory holder. It never injects a permission
rejection, worker interrupt or low-memory flag. The holder uses 16 MiB steps, a
768 MiB cap, 120-second timeout, parent-death cleanup and OOM score 1000. Its
208 MiB floor checks kernel MemAvailable before allocation; Android MemoryInfo
is a different, changing source and is not guaranteed above that floor.

At 528 MiB allocated, Android reports `lowMemory=true`, available memory
200,339,456 bytes and threshold 226,492,416 bytes. The unchanged production
resource check throws `private alignment memory reserve exhausted`. The peer
exits without a public result. Both computation and pressure children are reaped;
the pinned circuit snapshot is removed; the reserved session remains spent.
An actual isolated-app restart rejects replay before admission or circuit access
and leaves the 64-byte ledger unchanged. A new approved session then completes
normally in 30.899 seconds, with Android/Mac output matching the independent
reference. Exactly one 32-byte reservation is appended, leaving 96 bytes.
The ledger also retains the first startup attempt; it is never reset.

The first host connection fails before authenticated MPC or pressure starts.
Cancellation confirms the waiting participant and snapshot are cleared. Adding
the existing bounded transient-handshake retry behavior allows the real-pressure
run to proceed (15 startup retries). Recovery runs separately from the verified
withdrawal checkpoint, without repeating pressure (13 startup retries).
`scripts/verify_running_private_memory_usb.py` combines withdrawal, restart replay
and fresh-session recovery; `--resume-recovery` resumes an existing verified
withdrawal receipt. `aborted_replay` labels the actual restart test accurately.

This closes bounded real-pressure production withdrawal, cleanup and recovery.
It does not induce or establish system LMK, OS callback delivery, worst-case
memory usage, real-owner enrollment or distributed private proving. Production
929 MiB admission and 192 MiB reserve are unchanged; the primary APK and field
runtime source are not modified. Physical PID 12155 and edge PID 27649 remain
throughout the observed checks. After cleanup, the 18:00 UTC device-private audit
finds 56 frames in 30 minutes, maximum interval 38.78 seconds, active mode and no
silence marker. Field trajectories and detailed readiness reports remain private.
The isolated app and dedicated forward are removed. Ten affected host tests pass;
existing frozen device checks pass before and after. Historical full-suite counts
retain their original scope. See `running_real_memory_withdrawal_host.json`.

## October 8 bounded pressure and post-delivery feedback

`bounded_real_memory_pressure_android.json` records actual incompressible anonymous
allocations in a disposable isolated-app child, sampled against Android MemoryInfo.
The fixed 32 MiB step, 768 MiB cap, 256 MiB available-memory floor, 120-second child
timeout, parent-death cleanup and child OOM score 1000 bound the test. The final
source/build run reaches production admission rejection at 384 MiB allocated and
954,855,424 available bytes, then releases all allocations, exits zero and passes
production admission again. An earlier run rejected at 448 MiB. The measured
MemoryInfo low-memory threshold is 226,492,416 bytes; `lowMemory` remains false.
No production threshold is modified. This proves real-pressure admission and
recovery only; it did not cover running MPC withdrawal or system LMK. The later
running-pressure check below supplies separate withdrawal/recovery evidence. The reproduction driver is
`scripts/verify_private_memory_pressure_usb.py`; install the built isolated APK
before running it. This fixture does not start a collector or enroll real owners.

A regression reproduces natural feedback evaluated only 560 seconds after actual
actuator completion. `ClosedLoopIntervention.observe` now uses a finite delivery
completion timestamp for real actions, requires 600 seconds afterward and applies
the 900-second timeout to that same clock. Missing/invalid acknowledgment clocks
are censored and do not update policy. Randomized no-action controls retain their
decision clock. Evaluated outcomes retain the clock, start time and elapsed window.
Continuity/source binding gates, action propensities and dwell/cooldown thresholds
are unchanged. All 37 affected host tests pass; the historical full suite was not
rerun; see `bounded_pressure_feedback_host.json`. Twelve existing frozen device
checks pass, and the isolated pressure application is removed after verification.
The separate 26-test closed-loop/maintenance run also passes (overlapping
coverage, not an additive count).

The feedback source is deployed with a device-local rollback copy and verified
hash. The edge daemon is gracefully restarted once; physical PID 12155 remains.
The 17:28 UTC private health audit finds 55 frames in the last 30 minutes, a
39.13-second maximum interval, active configuration and no silence marker.
Natural trials remain zero, so no natural outcome or efficacy is claimed.
Device trajectories and detailed field/readiness reports remain private.

A six-hour thread heartbeat now checks collection, natural feedback, Sunday
calibration and frozen readiness using the retained daily snapshots plus live
store. It stays quiet while unchanged or pending. The fixed October 9–12 UTC
window is still pending, with no current readiness blockers; no score is computed
before October 13 00:00 UTC. The heartbeat never changes frozen materials,
thresholds or production calibration and never repeats physical pressure tests.

## October 8 guarded sixteen-node follow-through

Starting from `91d6659`, the isolated Android/Mac run passes eighteen guarded
normal/adversarial cases: identity, transposition, reversal, two nonzero/large
objective cases, invalid metric/mass bounds, session/nonce changes, model
replacement and each approved binding block. Invalid witnesses expose all-zero
peer output. Each request uses a fresh session; the driver never resets the
computation ledger. See `guarded_unknown_score_sdk16_android.json`.

Two additional runs observe authenticated MPC setup and at least fifty further
Android child CPU ticks before worker cancellation or synthetic resource
permission withdrawal. Both reap the child, remove its snapshot and reject
reuse of the spent session. The production 929 MiB admission and 192 MiB running
reserve are unchanged. This is not an induced system OOM or proof of actual
low-memory callback delivery.

`discovered_unknown_score16_proof_android.json` records the final nonzero
849-bit actual MPC output exported as a 224-byte statement. A centralized
synthetic generator verifies matching commitments and the complete sixteen-node
packed-v2 R1CS before setup/proving. Both fake owners then approve that exact
statement/proof/key pin. Five Android cases pass: wrong owner signature, signed
statement replacement, well-encoded invalid proof, valid proof and replay.
Only the valid proof consumes a proof session. A subsequent actual application
process restart rejects both proof and computation replay; the 32-byte proof
ledger and cumulative 672-byte computation ledger are unchanged. The latter
includes one successful computation from the earlier driver-state regression.
Separate host public verification passes. Public synthetic proof, key, statement,
owner public keys and signatures are included; private keys and witnesses are not.

`guarded_unknown_score_builds.json` binds both isolated APKs and packaged native
libraries. The proof-scope label update preserves the isolated UID, discovered
output and ledgers after the MPC jobs finish. The rebuilt Android participant
matches the packaged binary byte-for-byte; the reused public verifier and the
synthetic host's separate OpenSSL dependencies are recorded explicitly.
`guarded_unknown_score_host.json` records 28 affected host tests and two new Rust
tests, without replacing historical full-suite counts. A driver regression first
rejects Android's terminal-job exit 23, then passes after handling its explicit
missing-job response. The sixteen-node synthetic prover uses a bounded 64 MiB
host thread stack after reproducing test-stack exhaustion.

The twelve existing frozen device checks pass during testing and after cleanup.
The isolated app and dedicated USB forward are removed; neither primary
collector nor active field daemon is restarted. This closes bounded mechanical
composition only. Distributed two-owner private proving, real enrollment,
production setup provenance and independent security assessment remain open.
Actual system LMK is also unverified; the later running-pressure section separately
records production memory-guard withdrawal. See
[protocol scope and reproduction](collaborative_proof_integration.md#discovered-statement-synthetic-validation).

## October 3–4 engineering evidence

The latest recorded full host suite passes 569 tests with 182 subtests (JDK17),
reported in `artifacts/native_android_v1/foreground_service_full_test.json`.
This documentation update does not rerun that suite. Binding parse caching reduces
bounded isolated-service mean tick cost from 234.5ms to 177.9ms; the final 719-frame
run has zero over-period ticks and zero invalid provenance records. Whole-service
cost and memory are separate from the <15ms / <5MB compute-core requirement.

`low_memory_recovery_isolated_android.json` records trim signals tolerated and
SIGKILL recovery with a new session and no cross-session continuity. It does not
induce actual lmkd pressure or verify private-MPC withdrawal; no dedicated
`onTrimMemory`/`onLowMemory` handler exists in the tested service.

Daily device-local snapshots retain 14 days. The published normalizer candidate
report rejects the single-window candidate on its forecast gate; no activation is
recorded. The newer untracked two-snapshot evaluation also remains unaccepted due
to insufficient independent holdout days. Comparisons use persistence within each
normalization, not a common error scale. See
[feature evidence and candidate scope](continuous_feature_provenance.md) and
[recorded deployment state](pixel6a_deployment.md).

The earlier 960 MiB SDK admission failures remain failures. The current fixed
circuit uses an explicitly derived 929 MiB initial estimate (measured standalone
peak plus 20% headroom and 192 MiB reserve), not a certified worst-case bound.
The single SDK success does not close remaining protocol, privacy or science gates.

`independent_position_msd_counterexample.json` records a controlled sequence of
8,192 independent uniform 2D positions. Its population MSD is 4/3 at every
positive lag; the measured curve yields a near-zero MSD slope without temporal
dependence. This counterexample prevents treating low fitted H alone as evidence
of fractional memory or a physical confinement mechanism. It supplies no
empirical identification or acceptance of the intended continuous model.

Before dispatching an isolated unknown-score synthetic fixture, run
`python3 scripts/preflight_private_computation_fixture.py --fixture FIXTURE_DIR
--circuit APPROVED_CIRCUIT.txt`. It checks the pinned guarded circuit, canonical
approval encoding, each party's session and packed-v2 model commitment, and both
ECDSA signatures through the production Java approval verifier. It performs no
device operation or objective computation. Fixture DER keys are synthetic test
identities; passing this check does not establish real-owner enrollment, private
proof generation or production acceptance. A fixture signed for the earlier
unguarded circuit must be rejected rather than dispatched to the guarded SDK.

## Empirical continuous holdout entry

`scripts/validate_continuous_holdout.py` rejects incomplete or mixed coordinate
bindings, nonchronological partitions and temporal overlap before fitting.
The model is fitted only on training records; holdout increments supply
drift-vs-persistence errors and exposure-weighted full residual covariance
compared with predicted noise covariance (2D convention). Cross-coordinate
terms are retained. These are finite-lag diagnostics, not identified true drift,
fractional memory, conservative-potential validity or causal-effect acceptance.
The archived 26D state sequence remains ineligible. This archived-data result
does not describe the current device database; private local device diagnostics
require separate source/provenance review and publication authority.

For existing device storage, the same diagnostic can read one SQLite hot-frame
snapshot without exporting trajectory rows:
`python3 scripts/validate_continuous_holdout.py --sqlite-file EDGE.sqlite
--split-time TIMEZONE_AWARE_ISO_TIMESTAMP --output-file LOCAL_REPORT.json`.
The split is explicit: earlier records train, records at or after it hold out.
Both partition digests bind length-prefixed UTF-8 frames from the same read
transaction. The report contains aggregate diagnostics and covariance matrices;
its publication requires the applicable device-derived-data authorization.

All 33 affected continuous-model tests pass (9.149s), including deterministic
increment baseline checks, temporal-leakage rejection and an independent
correlated two-coordinate covariance example. These controlled tests validate
the reporting path and do not supply the missing empirical records.

## October 8 maintenance receipt repair

Hourly maintenance previously replaced the detailed weekly calibration result
with a report containing only `last_calibration_week`. The maintenance script
now carries `last_calibration_receipt` forward, retaining the attempt timestamp,
week, forced/Sunday trigger, source audit, potential result and calibration
acceptance or rejection. A regression first reproduces the lost receipt, then
verifies preservation through the next ordinary maintenance run. All 19 affected
maintenance and daily-snapshot tests pass. This does not reconstruct previously
lost receipts or establish successful Sunday calibration.

## October 8 fitted white-noise prediction repair

A deterministic 128D seven-pair fixture first reproduces Cholesky rejection of
an empirical Gram covariance. Fitted white-noise rollouts now sample independent
anchor/pair Gaussian residual factors with exactly the same position-dependent
mixture covariance. Collecting increment and drift terms avoids dense covariance
construction and factorization at every step. This introduces no ridge, frozen
white-noise covariance or diffusion-convention change. The fractional driver and
custom coefficient models retain their existing path. Changed public anchors
fall back to the original covariance validation; invalid covariance remains
rejected. Public model serialization does not contain fitting increments.

All 77 affected tests pass; full covariance equality is checked at two query
locations, alongside finite seeded low-rank trajectories and invalid-model
rejection. The seed remains reproducible within the new implementation, but its
paths differ from the old Cholesky sampler. Prediction output records the noise
sampler. See `artifacts/forecasting_v2/continuous_factor_host.json` for source
hashes and test scope. The previously recorded 569-test full suite was not rerun.

A bounded complete device prediction succeeds in the isolated evaluation path;
the two prediction modules are deployed with backups and matching source hashes.
Acquisition and the active daemon continue without a restart. Twelve frozen
checks pass before and after deployment. Device-derived evaluation details stay
private. This establishes bounded execution, not long-term timeout absence,
held-out predictive validity or intervention efficacy. The multi-day normalizer
candidate remains inactive because its manifold topology gate failed; no gate
was relaxed on the evaluated holdout window.

## October 8 ordered field validation

`scripts/audit_field_runtime.py` reads retained hot snapshots and rotated events
without exporting rows. It deduplicates frame identities, rejects conflicting
copies, reports UTC-day gaps/continuity and modality availability categories,
and distinguishes supported triggers blocked by dwell from events requiring
counterfactual/policy review. Unmatched events cannot establish their gate reason.
Reported availability is not measurement validity or complete uptime. The older
replay entry now includes `PROLONGED_DWELL`, with a failing-before regression;
its legacy tipping-only histogram remains explicitly separate.

`scripts/verify_controlled_field_feedback.py` executed one authorized 100ms pulse
with an API acknowledgment and completed a real ten-minute post-delivery frame
window. Its separate diagnostic ledger contains an observational outcome; it
does not add a natural-trigger trial or modify the production policy. Delivery
acknowledgment is not participant response, and a state change is not a causal
effect. Device-derived reports and frames remain private. Natural triggered
trials, suitable controls and effect validation remain open.

A fixed training snapshot, coordinate bindings, code archive and four future UTC
days are frozen on the device under the
[prospective forecast protocol](prospective_forecast_protocol.md). Missing days
or coordinate changes block evaluation; the window cannot be replaced after
seeing results. The accepted production calibration schedule remains independent.
Future scores and scientific acceptance are not yet available.

`interrupted_unknown_score_restart_android.json` records a newly built isolated
application reserving a synthetic signed unknown-score session, followed by an
actual app-process restart and replay through `computeApproved`. The PID changes;
replay is rejected before resource admission, circuit reads or child creation.
The 32-byte reservation ledger stays unchanged and no circuit snapshot exists.
The fixture intentionally models an interrupted request, not successful MPC.
No objective, completed sixteen-node case or distributed proof is supplied.
Old temporary circuit/runtime/dependency files are missing, so their historical
receipts are not reused as current execution. The isolated app is removed.

All 36 affected host tests pass without skips; see
`artifacts/forecasting_v2/field_validation_tools_host.json`. The full suite is not
rerun. Twelve frozen checks pass before and after device work. The physical
collector, active daemon, production model and intervention thresholds retain
their prior identities/settings; diagnostics require no production restart.

## October 8 four-step follow-through

1. **Guarded normal computation and completed-app replay.** Fixed EMP and
   OpenSSL dependency commits were restored; the regenerated compacted circuit
   matches the accepted `ba00463c…` digest exactly. One nonzero sixteen-node
   `different_scale` case completes through the Android approved unknown-score
   entry with a separate Mac peer in 28.789 seconds. Both parties' 849 output bits
   match the independent integer reference. After force-stop, a new app process
   rejects the spent signed request before admission, circuit access or child
   creation; the ledger stays unchanged and no circuit snapshot remains.
   See `completed_unknown_score_restart_android.json` and
   `guarded_unknown_score_rebuilt_dependencies.json`. The first scheduling
   attempt was blocked by the newly installed synthetic app's NEVER standby
   bucket, before session reservation; only that app's bucket was changed for
   the retry. The app and dedicated USB forward are removed. This closes one
   normal guarded case and its completed-app replay, not all guarded coverage,
   distributed private proof or real-owner enrollment.
2. **Executable prospective evaluation.**
   `scripts/evaluate_prospective_forecast.py` verifies the frozen training digest,
   code archive, fixed parameters, exact October 9–12 UTC window, per-day coverage,
   coordinate bindings and duplicate identities. Pending/blocked receipts contain
   no scores. On readiness it runs only the verified archived source in a separate
   process, excluding cached bytecode and verified macOS metadata sidecars,
   with full covariance and all four discrete
   baselines. No fitting uses holdout records and no activation follows. Device
   readiness passes with status `pending`; closing time is October 13 00:00 UTC.
   Pass every relevant retained daily snapshot as well as the live store; a 48h
   live store alone cannot cover the four-day window. Missing days or switched
   bindings block this experiment rather than selecting replacement days.
3. **Prediction lifecycle and daily operation records.** Each worker has an
   identifier and trigger binding, UTC start/completion timestamps and monotonic
   elapsed time. The daemon records launch failures, invalid/stale receipts,
   900-second timeout and shutdown. Hourly maintenance updates device-private
   `prediction_daily_runtime.json`; undated historical events remain separate.
   Reporting failure does not disable calibration or compaction. A complete
   300×30 device prediction and the production receipt-polling path pass after
   sufficient post-restart frames accumulate. The initial insufficient-segment
   failure remains logged. This checks execution, not accuracy or long-term
   timeout absence.
4. **Natural intervention blocker records.** `INTERVENTION_GATE` distinguishes
   duplicate frames, off mode, dwell below threshold, insufficient current-segment
   history, non-recommended counterfactuals, global cooldown and context cooldown.
   It includes current actuator silence/disable flags; failed-delivery receipts
   retain the actual blocking flags. Rejections consume no policy draw or trial.
   Dwell/history/counterfactual thresholds and action propensities are unchanged.
   These records and private audit aggregates support subsequent field review;
   natural-trigger trials and causal-effect evidence remain open.

All 70 affected host tests pass without skips. The diagnostic source closure is
deployed with matching hashes and a rollback
backup. Only the edge daemon is gracefully restarted once, preserving its active
configuration; the physical collector retains its process identity. Twelve frozen
checks pass before and after the device work. Device-derived prediction, feedback,
readiness and gate reports remain private. Updated affected host validation is
recorded in `ordered_followthrough_host.json`; the historical full suite is not
rerun.

## Conditional Brownian calibration

`ContinuousDynamics.estimate_memory_irregular(records, coordinate=0,
brownian_bootstrap_draws=99, seed=0)` optionally calibrates the maximum
fractional-Gaussian profile gain against independent Brownian residuals on the
same observed time grid. The 19-point H grid and profiled noise scale are reused
for each draw; the Monte Carlo p-value is `(1 + exceedances)/(1 + draws)`.
The default remains zero draws, preserving the original diagnostic. Calibration
is conditional on the fitted drift and a single coordinate: it excludes drift
estimation uncertainty and multiple-coordinate selection, and does not establish
empirical memory or scientific acceptance. Draws are bounded to 999.

## External conditions for full acceptance

Real intervention evaluation requires a named participant, permitted actuator
and observation window. The participant has authorized field operation, and the
October 4 trigger receipt records an active daemon. A controlled execution and
ten-minute feedback window now pass; natural-trigger trials and causal-effect
evidence remain to be established. Cross-individual validation requires a second independently held
device and enrolled owner identity. Synthetic identities and a Mac peer prove
controlled protocol behavior, not cross-individual generalization. Neither
external requirement can be replaced by additional unit tests.

Distributed private proving remains an implementation requirement. The current
TACEO documentation supports BLS12-381, but its documented honest-majority
protocols do not resolve Traceform's two-owner security requirement. See
[the compatibility gates](collaborative_proof_integration.md). Full completion
remains unclaimed. Primary-app upgrade is separately recorded in the October 4
receipt and does not close scientific or privacy requirements.

## Within-token CSD diagnostic

The legacy grid-coordinate diagnostic cannot warn during a dwell as long as its
entire window: every coordinate is then identical. In the field configuration,
both the window and dwell threshold are 60 frames. Controlled regression
reproduces this obstruction even when the normalized input changes within one
Token. It is an implementation limitation, not evidence of absence of physical
critical slowing.

`phone_edge_manifold_daemon.py --csd-input-space continuous` explicitly selects
normalized continuous inputs instead. It uses the mean detrended variance
across input coordinates and lag-one autocorrelation of their RMS radial
projection. Each frame records `csd_input_space`, and alert metrics record the
input dimension. The same 2.5 threshold and 60-step gate are retained. The
default remains `grid` to preserve archived diagnostic semantics; the CLI
selection survives model activation. Invalid, missing or dimension-changing
inputs reset diagnostic history.

A controlled constant-Token example warns after the dwell threshold when
continuous variation changes, while the legacy grid path and flat continuous
control do not. This validates reachability, not calibration of biological CSD
or intervention efficacy. Normalized inputs may include proxies and imputation;
a 128-dimensional vector does not certify 128 measured quantities. Previous
records retain their original diagnostic space and are not retroactively
reinterpreted. Device activation and real follow-up need separate verification.

Native Android production now uses the normalized-vector diagnostic as well.
Its existing 300-frame window and 30-step gate differ from the Termux 60/60
configuration; the old native configuration was not subject to that exact
60/60 obstruction. The legacy grid method remains available for archived
fixtures. A 128-dimensional, constant-Token controlled sequence checks Java
against Python variance, lag-one correlation and tipping decisions; flat
input, dimension changes and invalid normalized vectors have separate checks.
Native frame metadata identifies the new diagnostic space. Host parity and a
candidate build alone do not establish deployment or efficacy. Primary deployment
is now separately supported by `primary_app_upgrade_20261004.json`; that bounded
check does not establish intervention efficacy.
