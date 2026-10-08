# Stage 11–14 completion audit

Updated against published evidence through 2026-10-04 (repository baseline `d3330fd`),
with October 8 maintenance and fitted white-noise prediction repairs noted below.

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
| 5–15 minute follow-up | Feedback and retention implementation; `native_feedback_pixel.json` | Authorized real follow-up with complete observation window and attribution limitations |
| Contextual bandit reward/fatigue | `traceform/intervention/closed_loop.py` and native policy | Current-source policy validation; causal efficacy requires suitable intervention/control evidence |
| Hot 48h 1Hz 128D | Primary upgrade exercised 48h retention; bounded isolated sampling and provenance audit pass | Long-term 1Hz retention and complete source observability; Termux field sampling has a different cadence |
| Warm 30d 30s tokens/potential | Warm migration check passed during primary upgrade; daily device-local SQLite snapshots retain 14 days | Long-term end-to-end retained-source consistency; daily backups do not replace the warm-tier contract |
| Cold topology/energy under 500KB | Admission gate and lineage/export evidence | Empirically meaningful bifurcation parameters and bounded long-duration retention; small synthetic archives do not prove years of validity |
| Sunday automatic audit/calibration/reordering | `native_current_weekly_probe_android.json` passes complete synthetic debug task | Actual production Sunday delivery and authorized source-bound calibration; debug job does not establish those |
| Atomic model activation and recovery | Registry/admission/publication recovery receipts; later orphan cleanup separately checked | Bind the final release to all relevant changes and verify final device behavior |
| Zero-raw-upload manifold comparison | Separate-party input authenticated MPC tests, including 14 standalone sixteen-node cases | Real-owner enrollment, authorization/query controls and independent security assessment |
| GW alignment | Uniform permutation search with exact final loss; general-mass small-shape transport checks | Preserve bounded/non-global scope; uniform permutation descent is not unrestricted GW optimality |
| Android private SDK | Fourteen sixteen-node SDK cases in `uniform_private_sdk16_cases_completed_pixel.json`, with invalid witnesses rejected and snapshots cleaned | Packaged waiting-for-TLS cancellation/resource-permission withdrawal passes in `private_participant_lifecycle_android.json`; sixteen-node CPU-phase and actual low-memory withdrawal remain open, alongside unknown-score proof integration |
| Packed-v2 public proof and owner approval | Dedicated uniform-permutation proof relation; six Android public-verifier cases and four signed SDK cases, including app-process restart replay; actual separate-party two-node known-statement MPC/proof binding in `zk_packed_uniform_v2_mpc_binding_android.json` | Unknown-score proof integration, distributed private proving and production key/setup provenance; v1 certificates cannot silently authorize v2 outputs |
| Unknown-score private computation workflow | Signed unknown-score SDK entry, durable replay reservation; benign pre-fix Android case; circuit-level pre-reveal approval guard with 22 real host MPC cases | Guarded changed-model Android case passes after observed resource recovery and synthetic certificate rotation; normal unknown-score guarded coverage, process-restart app replay and packed-v2 proof binding remain open |
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
3. Verify long-term collection, actual Sunday calibration and real low-memory behavior.
   Primary upgrade and rollback rehearsal are already recorded, not pending installation.
4. Complete guarded unknown-score Android coverage, packed-v2 binding and distributed
   private proving, then real-owner enrollment and independent security assessment.

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
