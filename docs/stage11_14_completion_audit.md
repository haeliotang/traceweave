# Stage 11–14 completion audit

This audit retains the scope of the supplied roadmap. It distinguishes available
implementation and bounded evidence from the evidence needed for full completion.
It is not a release approval or a claim that the four stages are complete.

| Original requirement | Current evidence and boundary | Completion evidence still required |
| --- | --- | --- |
| Continuous 128D drift and diffusion | `traceform/dynamics/continuous.py`; controlled full-rank second moments in `full_128d_covariance_recovery_host.json` | Held-out empirical drift/diffusion accuracy with source-bound observations; current archived sequence has 5961 legacy 26D rows and zero eligible 128D pairs (`empirical_128d_input_readiness.json`), so controlled moments remain insufficient |
| Euler–Maruyama simulation | `additive_sde_refinement_host.json`: known 1D OU convergence | Validation of the intended empirical 128D model and appropriate time steps |
| Long-range/fractional memory | Additive fractional driver and residual profile diagnostics exist; source explicitly disclaims empirical identification | Source-bound identification, uncertainty and comparison with Brownian/null alternatives; reported H=0.054 is not automatically an accepted driver |
| Conservative potential inversion | Smoothed graph potential/curvature diagnostics exist | Evidence that a conservative potential describes the empirical drift, or explicit qualification of the nonconservative component |
| Continuous daily and weekly kernel | Existing smoothing implementations and `continuous_time_validation*.json` | Verify current release retains the accepted boundary-continuity and weekly-conditioning behavior |
| Configurable haptics/notifications/volume/color | Actuator and restoration implementations and bounded device receipts exist | Current-source device verification and separately authorized physical use; synthetic dispatch is not efficacy |
| Intervention after recommendation and CSD | Closed-loop policy exists; defaults retain shadow/silent behavior | Current-source decision-to-actuator checks, explicit operational enablement and actual safe execution |
| 5–15 minute follow-up | Feedback and retention implementation; `native_feedback_pixel.json` | Authorized real follow-up with complete observation window and attribution limitations |
| Contextual bandit reward/fatigue | `traceform/intervention/closed_loop.py` and native policy | Current-source policy validation; causal efficacy requires suitable intervention/control evidence |
| Hot 48h 1Hz 128D | Tiered storage and native retention implementations | Current production release retaining the correct bound and source provenance |
| Warm 30d 30s tokens/potential | Warm SQL typing, snapshots and retention receipts | Current production migration and end-to-end retained-source consistency |
| Cold topology/energy under 500KB | Admission gate and lineage/export evidence | Empirically meaningful bifurcation parameters and bounded long-duration retention; small synthetic archives do not prove years of validity |
| Sunday automatic audit/calibration/reordering | `native_current_weekly_probe_android.json` passes complete synthetic debug task | Actual production Sunday delivery and authorized source-bound calibration; debug job does not establish those |
| Atomic model activation and recovery | Registry/admission/publication recovery receipts; later orphan cleanup separately checked | Bind the final release to all relevant changes and verify final device behavior |
| Zero-raw-upload manifold comparison | Separate-party input authenticated MPC tests, including 14 standalone sixteen-node cases | Real-owner enrollment, authorization/query controls and independent security assessment |
| GW alignment | Uniform permutation search with exact final loss; general-mass small-shape transport checks | Preserve bounded/non-global scope; uniform permutation descent is not unrestricted GW optimality |
| Android private SDK | Fourteen sixteen-node SDK cases in `uniform_private_sdk16_cases_completed_pixel.json`, with invalid witnesses rejected and snapshots cleaned | Packaged waiting-for-TLS cancellation/resource-permission withdrawal passes in `private_participant_lifecycle_android.json`; sixteen-node CPU-phase and actual low-memory withdrawal remain open, alongside unknown-score proof integration |
| Packed-v2 public proof and owner approval | Dedicated uniform-permutation proof relation; six Android public-verifier cases and four signed SDK cases, including app-process restart replay; actual separate-party two-node known-statement MPC/proof binding in `zk_packed_uniform_v2_mpc_binding_android.json` | Unknown-score proof integration, distributed private proving and production key/setup provenance; v1 certificates cannot silently authorize v2 outputs |
| Unknown-score private computation workflow | Signed unknown-score SDK entry, durable replay reservation; benign pre-fix Android case; circuit-level pre-reveal approval guard with 22 real host MPC cases | Guarded changed-model Android case passes after observed resource recovery and synthetic certificate rotation; normal unknown-score guarded coverage, process-restart app replay and packed-v2 proof binding remain open |
| Native <15ms / <5MB core | `native_current_monthly_compute_android.json`: isolated fitted synthetic compute passes | Preserve measured workload scope; it does not prove whole-app/fit/history-scan/private-MPC memory below 5MB |
| Standalone APK/Service/Binder | Native APK and actual separate-process Binder/lifecycle evidence exist | Build and install final production sources; current primary app is older |
| Complete native 128-feature acquisition | Native feature provenance and `native_128_feature_source_index.json` account for all 128 names; the literal source index leaves variable dispatch/helper chains unresolved | Resolve producer paths and verify each intended observed feature on hardware; a name or assignment is not evidence of independent observability |
| Final USB deployment | Independent probes have been installed and removed | Install the final production build with the existing signer and verify the requested operational behavior |
| Repository publication | User requested a progress snapshot before full acceptance; implementation, README and bounded evidence committed locally | Verify push to `https://github.com/haeliotang/traceform.git` and matching remote commit; publication does not close remaining scientific or deployment gates |

## Next execution order

1. Finish application SDK valid and rejection coverage using separate-party
   synthetic inputs and matched nonce/source receipts.
2. Implement the unknown-objective approval workflow and packed-v2 public
   statement/proof binding; retain explicit owner authorization and replay controls.
3. Resolve the empirical/model-family and real intervention requirements with
   suitable data and authority. Keep unperformed scientific experiments open.
4. Build the final production candidate, verify integrated behavior and resource
   scope on USB hardware, then publish the reviewed repository state.

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
The archived 26D state sequence remains ineligible; no empirical 128D fit has
been performed using this tool.

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
