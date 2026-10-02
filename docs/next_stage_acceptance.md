# Next-stage acceptance ledger

The requested end state includes implementation, USB deployment to Pixel 6a,
device verification, and push to https://github.com/haeliotang/traceform.
Tests alone do not establish scientific or human intervention efficacy.

## Required work and evidence

- [ ] Feature provenance: observed, derived, proxy, and missing fields remain distinguishable; model and normalizer digests accompany stream records.
  - New stream schema binds semantic model/normalizer SHA-256 digests, source mode, boot/continuity epoch, actual interval, missing/supplied input inventory and upstream provenance. Field presence is explicitly unverified measurement status. Per-feature provenance classification remains open.
- [ ] Time contract: actual frame intervals, timezone-aware daily/weekly phases, no transitions across missing, reversed, or discontinuous timestamps.
  - Actual online frame interval and nonpositive sandbox gap regressions implemented; 141 local tests pass. Remaining continuity and provenance work is open.
  - Shared segment checks now exclude cross-model, cross-boot, cross-replay and explicit discontinuity pairs from sandbox, periodic fitting and holdout evaluation. Short negative clock rollback resets state. 151 local tests pass.
- [ ] Smooth daily/weekly transition kernel: normalized, continuous, sparse-data shrinkage; boundary, timezone, weekly-effect tests.
  - Continuous Von Mises product kernel is integrated into sandbox sampling, with global prior shrinkage, model-version continuity, timezone parameter, and bounded CDF cache. CLI accepts explicit records and labels evidence scope. 145 local tests pass; device benchmark and predictive holdout acceptance remain open.
- [ ] Predictive evaluation: common state vocabulary, chronological holdout, persistence/first-order/dwell/time-kernel comparisons, calibration and uncertainty. Record results without assuming improvement.
  - Implemented chronological paired evaluator, training-only vocabulary, log loss/Brier/ECE, unknown-state coverage, day bootstrap, source hash and explicit replay segment selection. Existing stream has five replay resets; first segment is single-state with one holdout day and lacks model provenance, so it is ineligible for model selection. This is a data acceptance failure, not demonstrated predictive gain.
- [ ] Continuous dynamics: empirical drift/diffusion estimation and continuous rollout with numerical convergence checks; distinguish white-noise SDE from fractional-memory hypotheses.
  - Added local continuous drift/full diffusion covariance estimator and Euler-Maruyama rollout, retaining normalized vectors/velocities in stream/hot storage. Tests cover actual intervals, deterministic step refinement, correlated Brownian variance and invalid covariance/version provenance. Historical raw→daemon→SQLite→128D continuous fitting smoke succeeded (19 valid pairs). Numerical/empirical holdout validation and device performance remain open; no potential or fractional-memory inference is claimed.
- [ ] Intervention loop: sandbox decisions, configurable actuators, execution receipts, cooldown/silence, delayed outcome tracking, randomized no-action comparator and adaptive policy. Observed transitions are not causal proof.
  - Added background pulse/notification executor with actual API return-code receipts, contextual epsilon-greedy policy with logged propensities and 25% randomized no-action comparator, 10-minute outcome tracking, 15-minute cooldown, gap/version/restart censoring and atomic reward updates. Daemon gates proposals on CSD plus dwell and sandbox recommendation, using current segment and measured median interval. Replay remains shadow even if active is selected. 162 local tests pass; device API execution and prospective outcomes are still unverified.
- [ ] Storage: bounded hot/warm tiers and cold sufficient statistics; crash recovery, idempotence, retention and active-reader integration tests; preserve frozen source evidence.
  - Added transactional SQLite hot/warm/cold store, 48h/30d retention, replay watermark, occupancy/energy summaries and continuity-aware cold transition counts. Daemon writes store and supports tiered-only manifold output; sandbox reads hot records; compact_edge_storage.py exports labeled warm summaries. 153 local tests pass. Raw producer/event lifecycle, scheduler integration, model-version count bound and device verification remain open; no 500KB claim.
- [ ] Weekly evolution: device scheduler, candidate versions, held-out acceptance, token migration, atomic activation, rollback, failure receipts.
  - Added immutable digest-bound model/normalizer/validation bundles, compare-and-swap activation, rollback, four-version retention and daemon hot switching. Guarded calibration uses chronological 70/30 train/holdout, frozen normalizer, >=5% holdout-distance improvement and coverage gate, with bijective minimum-cost token migration. Maintenance CLI compacts storage, runs Sunday-local-time weekly calibration once per week and saves receipts; Termux JobScheduler wrapper is present and phone tools exist. Local historical candidate smoke passed (84 train/36 holdout, mean distance 41.03→18.99) in temporary registry only. Persisted hourly device job 11401 is registered and has run. Identical-weight registry activation and rollback were observed by the live daemon, then the original baseline restored. Actual weekly candidate quality and longer-window acceptance remain open.
- [ ] Population alignment: structural alignment implementation and tests; explicit privacy threat model. No zero-knowledge claim without a cryptographic protocol and verification.
  - Added local entropic squared-loss GW solver, log-domain Sinkhorn, permutation-invariant initialization, multi-start search, marginal/residual diagnostics and model-file CLI. Synthetic permutation/unequal-cardinality tests pass. Existing model comparison converges after finite-Sinkhorn marginal rounding (marginal error 1.39e-17, objective 0.04667); this establishes a feasible local fixed point, not global optimality or human equivalence. Explicit privacy threat model rejects zero-knowledge/encryption claims and performs no model sharing.
- [ ] Native packaging: compute core and Android foreground-service APK/SDK; build, parity, lifecycle and device benchmark evidence. Performance targets are measured, not presumed.
  - C++ C ABI core implements robust scaling, kinetic quantization and time-conditioned CDF rollouts. Host tests prove 128D normalization/energy and shared-draw trajectory parity; 172 total local tests pass. Official NDK/build-tools/API archives checksum-verified; standalone APK and signature-protected Binder SDK built and installed. Eight canonical-vector parity cases and Binder parity pass; normal service stop writes running=false. Latest synthetic 300×120 benchmark is 1.067ms. Sensor bridge covers only 7 measured features and explicitly imputes 121; full extraction migration and <5MB memory target remain open.
- [ ] USB deployment: source digest, dependency/runtime checks, rollback package, on-device tests, live stream and health receipts.
- [ ] Repository publication: review diff, commit, push, verify remote commit identity.

## Baseline

2026-10-01: clean initial worktree; origin matches requested repository.
Pixel 6a USB serial [REDACTED_DEVICE_SERIAL] is reachable. Local baseline: 139 tests passed.
After time regressions and fixes: 141 tests passed. No new device runtime deployed yet.

## Latest integration evidence

187 local tests pass. Pixel 6a now runs the updated Python chain in tiered storage / shadow intervention mode with its existing silence flag preserved. Original runtime code was backed up to `traceform-runtime-rollback-20261001.tar` on the device before replacement. Maintenance runs on-device after supplying UTC/Asia-Kathmandu TZif data. Android JobScheduler confirms persisted job 11401 at a one-hour interval with a successful run. The wrapper uses the Android system timezone and explicitly supplies the TZif path. Full roadmap acceptance remains incomplete; open boxes are intentional.

## Rolling source storage progress

Added an independent v2 physical-acquisition fork and rolling persistence adapter;
frozen `pixel_physical` and `pixel_physical_runner_v1.py` remain byte-unchanged.
Hourly/64MiB segments expire after their last write is older than 48h. Segment
granularity can retain the oldest record for up to approximately 49h; this does
not yet prove exact per-record 48h retention. The reader drains multiple inode
rotations, waits for partial lines and resets continuity after missing segments.
Crash-tail and repeated-clock regressions failed before fixes and pass afterward.
Six rolling tests pass in native Termux; two mocked acquisition/recovery tests
pass in the existing Debian runtime. Native Termux lacks NumPy required by the
frozen sensor/audio acquisition imports, so v2 acquisition currently uses the
existing Debian environment. The live v1 producer has not been replaced yet;
v2 supervision/cutover, historical source preservation and event-log lifecycle
remain required before this storage item can be accepted.

## Event lifecycle integration

CSD/model-activation/policy outcome events now use 8MiB hourly JSONL segments
with 30-day retention. Hourly maintenance also removes idle expired tails.
A regression proved that replay must not create lock files inside read-only
frozen source directories; historical replay now uses an ordinary read-only
file handle, while live follow uses the rotation-aware reader. 197 local tests
pass; seven rolling tests and the real-runner read-only replay test pass in
native Termux. A 20-frame historical daemon smoke completed successfully.
Updated daemon source was installed and its recorded PID identity checked
before restarting the live runtime in unchanged silent/shadow mode.
The frozen v1 physical producer remains active pending v2 supervision cutover.

## V2 singleton/recovery preparation

The v2 runner now derives lock/PID paths from its selected ledger directory,
closes rejected lock handles, preserves the actual owner's PID when another
instance fails to acquire ownership, and releases ownership when acquisition
initialization raises. Regressions reproduced all three failures before fixes.
199 local tests pass; four v2 acquisition/ownership tests pass in the existing
on-device Debian environment without live sensor API calls. Frozen v1 source
files remain unchanged. V2 durable desired-state supervision and production
cutover remain pending; these tests do not establish automatic recovery.

## V2 production cutover and recovery

The frozen v1 control interface recorded STOPPED intent but its process remained
alive and held the shared lock. Cutover was withheld until native, identity-bound
pidfd SIGTERM actually terminated PID 14730 and the lock probe returned stopped.
Existing v1 observation/transition/episode files were moved to an independent
`physical_v1_cutover_20261001` archive on the phone, with byte counts/SHA-256
manifest; no raw observations were uploaded. V2 now owns the shared lock and
produces real observations read by the active 128D daemon. A controlled SIGTERM
exit of PID 21689 was confirmed terminal; persisted RUNNING intent recovered
PID 22211, and subsequent source-frame growth was observed. Android persisted
job 11402 checks supervision every 15 minutes; its forced execution succeeded.
204 local tests and five native-device supervision tests pass. Monotonic,
boot-bound recovery cooldown fixes a reproduced wall-clock rollback failure.
Termux Python lacks pidfd wrappers, so Android API31+ Bionic interfaces (verified
against NDK headers) supply process-bound signaling. Reboot/power-loss recovery,
source freshness supervision, exact raw per-record retention and native sensor
migration still require acceptance work. Process existence is not claimed as
healthy acquisition.

Post-recovery health limitation: source frames and the SQLite watermark continue
to advance, and file-descriptor diagnostics show the live reader at the current
source inode/EOF. However, after recovery most physical modalities report
temporarily unavailable and API calls take their timeout budget (~63s total
per frame). V2's initial frames and the last archived v1 frame had available
modalities. Native Termux battery probes also time out (exit 124), including
with an explicit Android PATH and the API app in foreground. Thus process
recovery is verified but sensing health is not accepted. Diagnosis of global
Termux API timeout remains the immediate next action; restarting a process
is not proof of restoring physical observations.

## API preflight and persistent sensing limitation

207 local tests and six native-device supervision tests pass. Supervision now
requires a successful, parseable native Termux battery reply before launching
acquisition; this checks battery IPC only. Empty objects, non-boolean presence,
failed exit status, invalid JSON and timeouts are rejected by regression tests.
An identity-bound restart of the API app process restored a native battery
reply temporarily, and v2 subsequently started PID 27971. Five new frames
advanced the downstream watermark through 2026-10-01T03:41:42.762936Z, but the
latest frame still reported unavailable motion/environment/device relation and
temporary place unavailability. Thus the preflight gate is implemented, while
persistent sensor health and the API ANR trigger remain unresolved. No healthy
full-chain acceptance or repository publication is claimed.

Sequential native API isolation reproduced a narrower trigger: after resetting
API PID 29239, native/PRoot battery calls, combined hardware sensors, sensor
cleanup, last-known network location, Wi-Fi connection and audio-route replies
all succeeded. Battery replies immediately after each also succeeded. A fresh
`termux-location -r once` then exceeded its 15-second budget, followed by a
battery reply exceeding five seconds. This is evidence of global reply failure
after the single-shot location path, not proof of the underlying mechanism.
V2 desired state is temporarily STOPPED for bounded diagnosis; the downstream
daemon remains alive. No sampling health acceptance is claimed.

## Bounded location request restoration

The previous isolation process terminated with exit 1, as expected after its
post-location battery timeout. V2 now forks frame assembly explicitly and uses
Termux API's server-bounded `updates` branch (30-second timer, 35-second client
budget), without modifying frozen v1. Concatenated streamed replies, last-known
fallback, stale/unknown age rejection and request-duration freshness bounds
are tested. 211 local tests and four Debian-on-device location tests pass.
PID 29874 produced a real frame with available motion, environment, connection,
device relation and audio, and unavailable place; its location call ended in
31.177 seconds. The downstream watermark reached 03:51:36.153239Z. This process
then stopped cleanly for the final age-bound update, and deployment restarted
with launcher PID 30286 after another valid battery preflight. Persistent health
remains subject to observation; unavailable position is not fabricated.

Final deployed source digests were read back and match all three local v2
modules. PID 30299 produced two fresh frames at 03:53:36 and 03:54:17; location
requests completed in ~31.2 seconds, connection/device replies stayed available
and audio succeeded when sampled. Motion/environment returned unavailable
without reaching their timeout; this is a remaining distinct sensing fault.
The bounded worker restores API responsiveness but does not establish complete
modality health. The receipt contains availability/timing/source digests only.

## Background sensor access restoration

System sensor registration histories showed zero active time and suspension
due to sensor access restriction; OTHER_SENSORS was granted and global sensor
privacy was disabled. The API process had procState 10 and Termux had no
foreground service. Starting the normal Termux activity established its
notification-backed foreground service (ID 1337). All eight requested hardware
sensor keys then returned successfully, including after returning to Home;
battery, cached-location, Wi-Fi and audio-route replies remained successful.
No sensor permission, app-op or privacy setting was weakened.

Startup preflight now requires actual finite acceleration plus an environmental
reading as well as a valid battery reply, and releases the probe listeners.
Regression first reproduced battery-only acceptance of empty sensor events.
Invalid/incomplete/nonfinite sensor values and timeout cleanup are covered.
213 local tests and four native-Termux preflight tests pass. V2 restart passed
this gate (launcher PID 31674). Persistent health and reboot session/service
restoration remain open; a visible activity is not a deployment requirement,
but the notification-backed foreground service is currently required.

Recovered production PID 31678 emitted a fully available six-modality frame
at 04:01:05.701287Z, including a bounded GPS update with conservative age
upper bound 30.77 seconds. Following frames at 04:01:45 and 04:02:15 retained
available motion/environment/connectivity/device relation; position/audio
were intentionally not sampled by the unchanged adaptive policy. The source
rotated across 04:00, so current-file row count reset normally; this is not
evidence loss. Source digests and privacy-preserving live receipts are saved.

## Per-record physical retention deployment

Regression tests reproduced retention of expired boundary-prefix records and
consumption through an unlinked expired inode. Timestamp-based clipping, atomic
replacement/cursor recovery, equality at the cutoff, failed-replacement recovery
and idle maintenance now pass. The first maintenance deployment used run-as HOME
and checked no raw files; a wrapper regression reproduced this path error, and
the corrected explicit Termux directory now checks all three production ledgers.
219 host tests, eleven native rolling tests and one native idle maintenance test
pass. The maintenance test required the deployed PYTHONTZPATH configuration.

The ~29.7MB synthetic on-device workload took 0.126s for first indexing and
0.00054/0.00042s for unchanged-file checks. Four deployed source hashes match
local files. A rollback source archive was saved before deployment. Daemon
18805 was stopped through an identity-bound pidfd and restarted as 4153;
acquisition restarted as 4221. The hot watermark advanced to 04:16:45.976184Z
in unchanged silent/shadow mode. Persisted job 11401 accepted a forced-run request. The saved maintenance
receipt confirms the direct production run; a fresh receipt proving completion
of that forced scheduler run has not yet been observed. This implements per-record cutoff at cleanup,
not continuous wall-clock expiry: idle records can wait for the next hourly
job, which Android may defer. Cold statistics, warm trajectory semantics and
full storage acceptance remain open.

## Bounded cumulative label-lineage cold layer

Cold storage no longer needs one matrix for every weekly model version: accepted
calibration preserves a baseline label namespace while actual model provenance
remains in hot/warm records. Namespace changes and actual model/boot/gap changes
still interrupt adjacent transitions. Normalizer mismatch, a fifth independent
namespace, a seventeenth token and oversize legacy identifiers are rejected
transactionally without dropping existing counts. The topology limit matches
the current 4×4 model; unrelated lineages are not silently combined.

Maintenance now produces an atomically replaced, lossless gzip cold checkpoint
with cumulative matrices and up to four model bundles, enforcing a 500,000-byte
file limit before replacing the accepted file. Size failure and old-schema
migration/count preservation regressions pass. 226 host tests, eight native
storage tests and three native calibration tests pass. Calibration lineage tests
mock the quality scores; they verify metadata inheritance, not predictive gain.

The final production reader is PID 11730 (identity-bound restart). Export and
SQLite both contain 156 transitions at 04:40:47.761503Z. The checkpoint is 16,320
bytes with two model bundles; attributable cold table pages are 24,576 bytes and
registry JSON files 85,589 bytes. The measured sum is 126,485 bytes, excluding
hot/warm tables, rollback snapshots and transient/free pages. Model bundle
content digests and four deployed source hashes match. Silent/shadow mode is
preserved. Warm ordered-trajectory storage and complete storage lifecycle
acceptance still remain open. The scheduled-maintenance forced-run request from
the previous turn still lacks a fresh independent completion receipt; direct
maintenance execution is verified separately.

## Ordered warm trajectory deployment

Warm storage now retains the latest observed token and quantization distance
per UTC 30-second bucket, with actual timestamps, provenance, contributing
frame counts and conservative continuity/missing-bucket markers. It does not
interpolate absent observations or equate quantization distance with an inferred
potential. Upgrade streams retained hot frames once; old occupancy summaries
are not used to reconstruct order. Transaction rollback, intrabucket breaks,
source/model/boot boundaries, missing buckets, migration idempotence and
30-day retention/export after hot expiry are covered by regression tests.

231 host tests and 13 native storage tests pass. Production reader PID 15666
was restarted through an identity-bound handle after source and database
rollback snapshots were saved. Both deployed source hashes match. The saved
receipt contains 202 ordered buckets through 04:59:34.981997Z, matching the hot
watermark, with 48 discontinuous rows and 40 rows following missing buckets.
Silent/shadow mode remains active. Actual history spans about 2h18m; this verifies
current operation and tested retention rules, not a 30-day device observation
period. Potential inversion, the 1Hz hot acquisition target and complete
storage/scheduler lifecycle acceptance remain open.

## Additive fractional-noise simulation

The continuous simulation CLI accepts an explicit `--hurst` parameter. Exact
fractional Gaussian increment covariance is factored on the actual simulation
grid, including a shortened final interval. Spatial correlation uses the fitted
covariance frozen at the initial vector. This implements an additive fractional
driver without applying Ito integration rules to state-dependent fractional
noise. H=0.25 negative increment correlation, subdiffusive terminal variance,
H=0.5 independent increments and invalid exponent/grid rejection are tested.
The new regressions first failed because the fractional methods were absent.

219 host tests plus 14 subtests pass; six continuous-dynamics tests pass in
native Termux Python on Pixel 6a. The simulation code and CLI are deployed.
H is supplied rather than inferred from recorded data, dense temporal
factorization has quadratic memory/cubic setup cost, and no fractional edge
performance or empirical memory acceptance is claimed. Potential inversion,
memory identification and integration with forecasting remain open.

Covariance definition: Jost, *Transformation formulas for fractional Brownian
motion*, https://www.sciencedirect.com/science/article/pii/S0304414906000226.

## Discrete drift-potential inversion

Continuous simulation now exports a relative graph potential alongside the
fitted drift/diffusion. Trapezoidal drift line integrals on the complete anchor
graph are projected onto energy differences by least squares, with anchor zero
fixing the additive gauge. The scalar mobility convention is explicit. Every
edge retains its projected difference and nonconservative residual; conservative
fraction is undefined for a zero field. This is a discrete projection of the
estimated drift, not equilibrium, detailed balance or thermodynamic energy.

Regression first failed with absent inversion methods. Quadratic potential
recovery, a rotational field with nonzero residual, residual divergence zero,
mobility scaling and invalid inputs now pass. 221 host tests plus 14 subtests
pass, as do eight native continuous-dynamics tests after deployment to Pixel 6a.
Empirical potential stability, a continuous gradient representation, memory
identification and forecasting integration remain open.

Projection reference: Jiang, Lim, Yao and Ye, *Statistical ranking and
combinatorial Hodge theory*, https://arxiv.org/abs/0811.1067.

## Continuous potential query

Gaussian-kernel smoothing now exposes energy and an analytic gradient at a
continuous vector. The simulation report includes this query at its initial
vector, with conservative drift, empirical drift and their vector residual.
Smoothing does not reproduce every graph anchor energy or exactly recover the
empirical drift; the graph conservative fraction is not a claim about this
smoothed vector field. Query provenance must match model anchors and version.

The new regression first failed with the missing query method. Central finite
differences agree with the analytic gradient; adding an energy constant changes
energy but not gradient; drift plus residual reconstructs the empirical drift.
222 host tests plus 14 subtests and nine native continuous-dynamics tests pass.
The source, simulation CLI and tests are deployed to Pixel 6a. Forecasting,
empirical potential stability and data-derived memory remain unfinished.

## Residual memory diagnostic

The fitted continuous model now estimates a variance-time slope from drift
residuals in a single regular segment, with at least 64 increments and 16
nonoverlapping blocks per reported scale. It reports H=slope/2, scale variances,
fit R-squared and whether H lies in (0,1), without clipping invalid estimates.
Model changes, declared discontinuities and unequal/nonpositive intervals are
rejected. The CLI's optional `--memory-records-file` must be strictly later than
all training records and has a separate source digest; the estimate remains a
diagnostic and does not automatically replace a simulation H parameter.

The synthetic H=0.25 recovery regression first failed with the absent method.
223 host tests plus 14 subtests pass; ten native dynamics tests pass after
deployment. A synthetic CLI check verifies independent-later report generation
and rejection of overlapping input without writing a report. This assumes
stationary additive fractional Gaussian residuals: measurement noise, omitted
drift and finite samples can produce similar scaling. Real-stream estimation,
uncertainty, predictive validation and automatic forecasting integration remain
open; no empirical fractional-memory conclusion is accepted yet.

## Live memory-input eligibility audit

At 05:15:07.495038Z the hot store contains 231 records across 12 continuity
segments. The longest segment has 40 records and intervals of 30.010967 to
40.650320 seconds. It fails both the 64-increment minimum and the regular-grid
requirement of the variance-time diagnostic. No interpolation or empirical H
result was produced. The reader's live process identity matches PID 15666 and
its recorded boot/start identity. The privacy-preserving input receipt saves
the audited source digest, which predates the following provenance fix.

A regression then reproduced acceptance of unversioned residual records when
the model version was also unset. The estimator now rejects absent fitted
representation provenance. 224 host tests plus 14 subtests and eleven native
dynamics tests pass after deployment. Supporting irregular observation times
and an independent later fitting/validation segment remains necessary for
real-stream memory identification.

## Actual-grid fractional Gaussian profile

The continuous model now supports `estimate_memory_irregular`, using exact
increment covariance on actual observation intervals and Gaussian likelihood
with a profiled scalar noise variance. It estimates one explicitly selected
coordinate, not independent pooled evidence across correlated features. The
H grid is 0.05–0.95 in 0.05 steps; the report includes all likelihood scores,
the chosen scale variance and a boundary-optimum flag. Times are normalized by
their mean interval, whose seconds value is recorded. No interpolation,
covariance jitter or automatic online H activation is performed.

Input is bounded to 32–128 consecutive increments for dense factorization.
Missing representation provenance, model/stream discontinuity, invalid times,
coordinate mismatch and zero residual noise are rejected. The CLI accepts
`--memory-method irregular --memory-coordinate INDEX` with its existing
strictly-later input constraint. The synthetic irregular H=0.25 regression first
failed with the absent method. 225 host tests plus 14 subtests pass, as do twelve
native dynamics tests after deployment. This is a parametric likelihood under
additive fractional Gaussian residual assumptions; empirical independent-segment
evaluation, uncertainty calibration and predictive acceptance remain open.

## Live independent-segment memory diagnostic

A device-local run on one 50-record continuity segment trains on its first 17
records and diagnoses coordinate zero using the later 33 records. Training ends
05:02:46.355230Z; validation spans 05:03:22.913149Z to 05:20:33.076623Z. Only
timing, source/input digests and diagnostic summaries are saved off-device.
The fitted eight-anchor graph has edge residual RMS 1.23774 and conservative
fraction 0.70534; these describe the discrete projection, not explained
predictive variance. Training plus diagnostic took 1.259 seconds in native
Termux Python, not a Monte Carlo latency measurement.

The H grid optimum is 0.55, with log likelihood only 0.03969 above the Brownian
H=0.5 candidate. This does not support a claim of subdiffusive memory or a
calibrated rejection of Brownian dynamics. The receipt binds the source version
used before the next reporting change. Reports now include this explicit
Brownian contrast, labeled as an in-sample profile comparison rather than
significance or predictive gain. Its regression first failed with the absent
field; 225 host tests plus 14 subtests and twelve native dynamics tests pass
after deployment. Prospective predictive validation and calibrated uncertainty
remain open; this H value was not activated online.

## Native feature freshness and evidence kinds

The Android subset service now attaches per-feature evidence kind, source,
monotonic age and age limit. Linear-acceleration snapshot derivatives expire
after five seconds; light/pressure on-change samples have a conservative
300-second cached-value limit. Missing/future/stale/nonfinite inputs are median
imputed with missing provenance instead of remaining measured indefinitely.
Battery level ratio and status conversions are derived from the queried sticky
broadcast; its read time is not proof of the hardware's original measurement
time. Pressure is directly observed. The acceleration RMS remains a spatial
snapshot calculation, explicitly not temporal-window RMS or full Python parity.

A plain Java regression covers cutoff equality, stale/future values, derived
kind, missing input and invalid replacement. It first failed with the absent
reading helper. 226 host tests plus 14 subtests pass. The built APK's source
binding and SHA are saved separately from earlier build receipts. Installation
succeeded; the first start only foregrounded an existing activity, so a fresh
activity start was required to deliver the request. The new live status reports
36 frames, one directly observed feature, five derived features and 122 missing
features. The service was then stopped normally, with running=false at 39
frames. Full original acquisition remains the production path; this is a
partial native adapter, not complete 128-feature native acceptance.

## Native rotational and gravity-direction inputs

The subset adapter now registers gyroscope and accelerometer events. Rotational
kinetic energy follows the Python formula 0.5 times squared angular velocity,
capped at 50 and rounded to five decimals. Tilt and screen alignment follow
the existing accelerometer-including-gravity formulas and rounding; their
source label does not claim isolated gravity or motion-free orientation.
Undefined near-zero orientation and nonfinite gyro vectors are missing instead
of fabricated valid values. All three are derived and expire after five seconds.

The Java harness first failed with the absent sensor methods. Axis orientation,
inverse orientation, rotational energy/cap and invalid replacement now pass.
226 host tests plus 14 subtests pass. The APK was rebuilt, installed and started
on Pixel 6a; current status shows one observed and eight derived inputs, nine
supplied and 119 missing. Source-bound build and live status receipts are saved
separately. The subset service is stopped after validation. Full native
128-feature extraction, temporal motion-window parity and modality coverage
remain incomplete.

## Native clock and device-state inputs

UTC daily sine/cosine and Monday-based continuous weekly sine now follow the
existing Python normalizer's timestamp formula. They are clock-derived inputs,
not observed biological rhythms or the forecasting kernel's local timezone.
Battery current is read in microamperes from CURRENT_NOW, retaining its sign;
the unsupported MIN_VALUE sentinel is missing. Instantaneous interactive screen
state is explicitly a proxy for screen_on_ratio, not temporal-window parity.
Status and frame provenance separately count observed, derived and proxy
features, while median-imputed dimensions remain missing.

The Java harness first failed with the absent clock method; daily noon and
Monday weekly phase tests pass. APK compilation caught a local-name collision
with velocity calculation, corrected before deployment. 226 host tests plus
14 subtests pass. Pixel 6a reports 14 supplied inputs (two observed, eleven
derived, one proxy) and 114 missing after installation/start. Source-bound build
and live status receipts are saved. The validation service was stopped normally;
complete native coverage, screen-window measurement and extraction parity remain
open.

## Native motion snapshot definition correction

Reading the frozen physical sensor implementation established that its RMS is
spatial across three accelerometer axes, not a temporal-window statistic. The
native adapter previously used linear acceleration without gravity for these
features, unlike the original accelerometer snapshot. It now uses the full
accelerometer norm and spatial RMS with four-decimal rounding, removes the
unneeded linear-acceleration subscription, and combines gravity deviation with
gyroscope magnitude using the existing motion-energy formula.

Both input snapshots must be finite, at most five seconds old and no more than
one second apart. Otherwise motion energy and stationary score are missing;
absent gyroscope data is not silently zero. The stationary score is a heuristic
proxy, not an observed or calibrated probability. The Java regression first
failed with the absent motion-combination method; rest, rotational contribution
and stale pair rejection pass. 226 host tests plus 14 subtests pass.

The rebuilt 176,875-byte APK was installed on Pixel 6a. Live status reports 16
supplied features (two observed, twelve derived, two proxies) and 112 missing.
Build/source and status receipts are retained separately. The subset validation
service stopped normally at eleven frames. Full 128-feature native parity and
coverage remain open; this correction does not create temporal motion windows.

## Native kinematic coverage continuity

The native velocity chain now resets when its available-feature mask changes,
so an imputed-median to actual-reading jump cannot become a measured velocity.
First samples, nonpositive intervals, gaps over 300 seconds and sampling/write
exceptions also reset the chain. The first continuous derivative after a reset
is unsmoothed; subsequent derivatives use the configured exponential smoothing.
Frames record monotonic-derived dt and continuous_from_previous explicitly.

The Java regression first failed with the absent kinematic helper. Imputation
jump rejection, recovery derivative, smoothing and invalid intervals pass.
227 host tests plus 14 subtests pass. The rebuilt APK was installed, started
and stopped normally on Pixel 6a; source-bound build and running status receipts
are saved. These host behavior tests plus the device startup receipt do not
constitute an induced on-device missing-sensor transition test. Wall-clock and
boot provenance, full feature extraction and native lifecycle acceptance remain
open.

## Native session and clock provenance

Frames now include UTC ISO timestamps, elapsed-realtime nanoseconds and a UUID
for each service instance. This session identity is not a device boot ID.
Wall time must advance and agree with the monotonic interval within one second;
otherwise the kinematic chain resets. The Java clock-jump regression first
failed with the absent wall-time interface and now passes. 227 host tests plus
14 subtests pass; the rebuilt signed APK installs successfully on Pixel 6a.
No system-clock change was induced on the phone, and this installation alone
does not verify a live clock-jump event. Complete native lifecycle and feature
coverage remain open.

## Native short background observation

Status now includes the current session, UTC timestamp, monotonic nanoseconds,
dt and continuity flag without raw vectors. After installation/start, HOME
returned the phone to its launcher. The same session advanced from eight to
45 frames over 41.5957 monotonic seconds, with sixteen supplied features and
continuous=true at both observations. Normal stop reports running=false at
50 frames. Live/status and source-bound build receipts are saved.

This establishes short screen-on background operation only, not lock-screen,
Doze, reboot or full native acceptance. The observed 37-frame increment is
about 0.89Hz; post-processing plus one-second delayed scheduling does not meet
strict 1Hz cadence. Scheduling and longer lifecycle verification remain open.

## Native deadline-based cadence

Sampling now targets monotonic one-second deadlines instead of waiting an
additional second after processing. Missed deadlines are skipped and counted;
no synthetic catch-up frames are emitted. The Java regression first failed
with the absent clock helper and now verifies exclusion of processing time,
multiple missed deadlines and equality at a deadline. 228 host tests plus 14
subtests pass. The APK was rebuilt, installed and started on Pixel 6a.

In one screen-on HOME-background session, frames advanced from nine to 51 over
41.999779 monotonic seconds: 42 frames, about 1.000005Hz, with zero skipped ticks
and sixteen supplied features. Source/build and live receipts are saved. This
short test does not establish long-term, lock-screen or Doze cadence; Android
handler scheduling is not a hard realtime guarantee. The subset verification
service stopped normally at 56 frames. Complete native coverage remains open.

## Native powered screen-off observation

The same deployed deadline-scheduled APK was started and the screen closed with
KEYCODE_SLEEP. In one unchanged session, frames advanced from eight to 45 over
37.001370 monotonic seconds: 37 frames, about 0.999963Hz, sixteen supplied
features, continuous=true at both observations and zero skipped ticks. The
terminal power report confirms noninteractive Dozing wakefulness and powered
operation, with both light and full device-idle modes false. The saved receipt
contains timing/status summaries only. Normal service stop was requested and
verified separately.

This is a short powered screen-off observation, not device-idle Doze, unplugged
battery or reboot acceptance. ADB interactions can also affect wakefulness;
no claim about autonomous deep-sleep cadence follows from this run. Those
lifecycle cases and full native feature coverage remain open.

## Native magnetic and pressure-derived inputs

The native adapter now subscribes to calibrated magnetic-field events and
computes the existing norm-relative-to-45uT anomaly. It is explicitly a proxy,
not a spatial magnetic divergence measurement. Pressure retains its direct
source and supports the existing -8.43*dp/dt vertical-velocity formula, clipped
to ±5m/s and rounded to three decimals. Native pressure reference intervals are
at least five and at most 600 seconds; this is not identical to Python frame
sampling cadence. Invalid/nonpositive pressure clears both input and derivative
history. Derived velocity expires after five seconds.

The Java harness first failed with absent methods. Magnetic anomaly, initial
missing derivative, pressure derivative units and invalid-input reset now pass.
228 host tests plus 14 subtests pass. The APK installs and runs on Pixel 6a;
status reports eighteen supplied features (two observed, thirteen derived,
three proxies) and 110 missing. Build/source and live receipts are saved, and
normal stop reports running=false at fourteen frames. Full native extraction
and exact temporal parity remain open.

## Native audio-output and Wi-Fi connectivity inputs

The adapter now enumerates wired/headphone/A2DP output devices for the existing
binary immersion feature. This is labeled a connected-device proxy, not active
playback or attention. It also observes whether any available network exposes
Wi-Fi transport, with the normal ACCESS_NETWORK_STATE permission; the feature
is not an Internet validation claim. No Wi-Fi scan, SSID, device identifier or
microphone capture is introduced. Both values are refreshed from system queries
and have five-second read-age limits.

APK compilation and 228 host tests plus 14 subtests pass. Installation/start
on Pixel 6a reports twenty supplied inputs (three observed, thirteen derived,
four proxies) and 108 missing. Source/build and live status receipts are saved;
normal stop is verified. These current-device checks do not test every headset
or network transition. The existing compute-power proxy still depends on an
unavailable application-switch input and was not populated with a fake complete
measurement. Full native coverage remains open.

## Native motion vector derivatives

Linear acceleration-vector change norm and angular-velocity-vector change norm
now use actual monotonic intervals of 2–300 seconds, four-decimal input/output
rounding and the existing caps of 20 and 25. Native sensor references are held
until at least two seconds elapse; this is not exact Python frame-cadence parity.
Invalid vectors, reversed/repeated times and long gaps reset the references.
Outputs are derived with five-second freshness limits; initial derivatives
remain missing. The legacy rotational_angular_jerk name denotes the existing
angular-velocity derivative formula, not a higher-order derivative.

The Java regression first failed while accessing absent derivative readings;
known linear/angular differences and invalid-vector reset now pass. 228 host
tests plus 14 subtests pass. Pixel 6a installation/start reports twenty-two
supplied features (three observed, fifteen derived, four proxies) and 106
missing. Build/source and live status receipts are saved. Normal stop is
verified afterward. Full native coverage and exact temporal extraction parity
remain open.

### Continuous coordinates connected to sandbox branch statistics

`SandboxSimulator.simulate_continuous_rollouts` now evolves coordinates through
the fitted continuous drift and full covariance, then quantizes each simulated
point for branch occupancy, entropy, first-hit time and energy statistics. It
does not sample the discrete transition matrix. Both white-noise Ito and additive
fractional drivers report their driver and assumed Hurst exponent explicitly.
Initial representation version and coordinate digests must match the fitted
model; a quantizer returning an unknown vocabulary state is rejected.

A streaming observer accounts for every rollout while retaining only five sample
paths. The deterministic 128-dimensional fixture reaches the next token through
coordinate drift even when the discrete matrix contains only self-loops. Seven
rollouts contribute to every occupancy row. A decimal-step regression reproduced
an extra step caused by cumulative floating-point time addition; both drivers now
construct times from integer step indices. The affected continuous, sandbox and
daemon tests pass (35 tests). This is a tested sandbox API connection, not yet
live daemon activation, an identified fractional-memory model, an actuator-effect
model or an Android native performance result.

### APK proof verifier and private replay ledger

The APK now packages the ARM64 public verifier as an extracted native executable
and exposes ProofVerifier.verify through its SDK jar. It executes under the app
UID, with a ten-second timeout, and uses the app-private files/zk_sessions.bin
ledger. A background fixture path avoids blocking the activity thread.

The signed APK was built and installed. The first launch reused an old activity
instance without a receipt; a cold start produced a new receipt at epoch ms
1790842356892: first verification accepted, repeated verification rejected with
exit 1 and 'replayed session'. The public fixture package and fixed test key pin
were staged in the app-private directory. Evidence: zk_apk_private_ledger.json.
No Termux process, private model transfer, foreground collection or actuator was
used in this APK verification. Owner authentication, production setup trust and
two-owner secure computation remain open.

### Owner signature authentication tested in the APK

The SDK now requires two distinct pretrusted public keys. Each SHA256withECDSA
signature approves a domain-separated ordered owner position, verifying-key pin,
all public inputs and the exact proof. Bounded app-private snapshots prevent
replacement between authentication and proof verification. The host Java fixture
rejects replacement owners and changed public statements; private keys remain
in memory.

The signed APK was installed on Pixel. A fresh synthetic signed session is
accepted and its repeat rejected (completed epoch ms 1790842705476). Changing
the last signature byte produces 'owner signature rejected' before session
consumption (1790842758611). The original signature was restored and the private
ledger remains 64 bytes for the two accepted fixture sessions. Evidence:
zk_apk_owner_authentication.json. This authenticates supplied trusted synthetic
keys, not enrollment of real individuals or private computation between owners.

## Native ordered warm trajectory foundation

The APK now writes a thin latest-observation row per UTC 30-second bucket in
warm_trajectory, in the same SQLite transaction as hot frames and occupancy.
Rows retain actual timestamps, model/normalizer identity, session, token and
quantization distance. Contributing-frame counts, intrabucket discontinuity and
missing-bucket markers are preserved. Quantization distance is not inferred
potential energy. Hot frames also retain the canonical velocity vector.
Cleanup removes warm representatives older than 30 days when the running
service performs its periodic cleanup; this is not strict idle expiry.

The new Java bucket test covers first-bucket boundaries, within-bucket breaks,
missing buckets and clock reversal. 229 host tests plus 14 subtests pass. The
APK installs and status confirms warm table writes on Pixel 6a. Source/build
and live receipts are saved; normal service stop is verified. This initial
native table begins at deployment and does not backfill retained hot frames or
reconstruct legacy occupancy. Historical migration, restart/clock edge-case
storage verification, cold statistics and full native storage acceptance remain
open.

## Native retained-hot warm backfill

Native startup now streams retained hot frames in timestamp order into missing
warm buckets once. Buckets already present at migration start are preserved
without recounting; the migration marker and all new rows share one transaction.
Older occupancy summaries are not used. Legacy epoch timestamps can supply UTC
formatting, while missing session/continuity evidence remains discontinuous.
Warm representative time reversal is rejected in the live write transaction.
The startup cursor retains only the current frame, predecessor and bucket state,
not all vectors or a full bucket index in memory.

An independent in-memory Android SQLite fixture verifies damaged-frame rollback,
existing-bucket count preservation and repeated migration idempotence. It passes
on Pixel 6a. 229 host tests plus 14 subtests pass. After installing the rebuilt
180,971-byte APK, the actual database reports 65 warm buckets and the retained-
hot-only migration scope while new sampling continues. Source/build, fixture
and current status receipts are saved; normal stop is verified. Native cold
statistics, complete lifecycle and full feature coverage remain open.

## Native bounded cold layer and pending live startup

The APK now has bounded per-model cold vocabularies and cumulative adjacent-
transition counts, requiring unchanged session/model/normalizer/source and a
positive interval of at most 300 seconds. Foreign states are rejected by SQLite
foreign keys, at most four model namespaces and sixteen states are supported,
and model/normalizer bindings are immutable. Checkpoints retain model prototypes
and normalizer parameters plus counts and row-normalized conditional
probabilities. Gzip export uses a database snapshot, file sync and atomic move;
over 500,000 bytes is rejected before replacing the previous checkpoint.
Directory fsync and worst-case total database-file bounds are not established.
These prototypes are not inferred potentials or bifurcation parameters.

Native independent SQLite tests pass for boundaries, foreign states, namespace
limits, binding and oversized-export preservation. A new regression first
reproduced acceptance of an equally misdeclared normalizer in both input frames;
append now checks the registered namespace binding and the repaired native test
passes. 229 host tests plus fourteen subtests pass. The signed 185,067-byte APK
is installed, with source/build and fixture receipts saved.

Actual service startup was rejected behind the keyguard. Initially this crashed
the activity; startup is now deferred and platform rejection displays a retry
message instead. System policy confirms showing=true, and the user has been
asked to unlock/open the page. The old stopped status is explicitly archived as
stale prior status, not evidence of live cold counting. No live native cold
checkpoint, prior-frame cold backfill, independent long-term memory or complete
native acceptance is claimed. The lock condition blocks this startup test only;
other implementation work can continue.

### Native retained-hot cold backfill

The installed native build now performs one transactional cold migration after
registering the current model. Only retained hot frames belonging to registered
model namespaces can contribute, with the existing explicit session, source,
normalizer, clock-gap and continuity checks. Unknown namespaces break the chain.
Existing transition counts are preserved without replay; the migration marker
and reconstructed counts commit together. This does not reconstruct history
that has already expired or infer missing model bundles.

The Pixel SQLite fixture verifies malformed-input rollback, repeated migration
idempotence and preservation of pre-existing counts, alongside the prior binding,
foreign-state, namespace and atomic size checks. The fixture receipt was read one
second after its device modification time. 229 host tests and fourteen subtests
pass. The signed build is installed. Actual service startup and a live cold
checkpoint remain pending phone unlock; fixture execution does not establish
those outcomes.

### Standalone core memory measurement

A reproducible ARM64 executable runs the same C ABI implementation with all
128D model, CDF, draw and occupancy buffers resident. Five independent Pixel
process runs recorded peak RSS 4,370,432–4,485,120 bytes and 300×120 synthetic
rollout latency 1.356–1.765ms, with mass conserved. Peak RSS includes the native
process runtime and exceeds the measured core allocations, so this workload
satisfies the original core <5MB and <15ms targets. Source and executable
digests bind the receipt. This does not establish continuous SDE, larger-table,
JNI-copy or whole-service memory bounds. Explicit NEON remains unfinished.

### Explicit ARM64 NEON normalization and quantization

The core now uses double-precision ARM64 NEON intrinsics for pairwise robust
normalization and energy-distance reductions, with scalar odd tails and a
non-ARM fallback. Existing decimal rounding and finite checks remain intact.
Compiled normalization and quantization symbols contain vector divide, subtract,
multiply and add instructions. Boundary tests cover odd dimensions, zero speed,
nearest-state ties, degenerate scales and nonfinite centers/tails. 231 host tests
and eighteen subtests pass.

The final installed APK independently verifies eight canonical cases with
maximum error 1.82e-12 and JNI synthetic rollout latency 6.692ms. Five standalone
processes measure peak RSS 4,366,336–4,521,984 bytes and 1.062–3.172ms. Receipts
bind final source, APK and executable digests. No speedup attributable to NEON
is claimed; CDF traversal remains scalar C++. These checks do not replace full
sensor extraction parity or the pending unlocked foreground-service test.

### Additional native magnetic-vector features

The installed adapter derives device-coordinate magnetic inclination and a
50uT reference magnitude anomaly from the existing magnetic vector. The angle
is derived evidence; the fixed-reference anomaly is explicitly a proxy. Both
expire after five seconds. Zero field has no defined inclination; invalid
vectors remove prior values. Formula tests cover vertical and signed inclined
fields, reference baseline, zero-field missingness and invalid replacement.
The affected Java harness passes. The build receipt binds installed sources.
This increases implemented feature capacity by two; live supplied-feature counts
remain unverified while the foreground-service startup awaits phone unlock.

### Native bounded light and pressure sample windows

The installed adapter now computes photometric contrast, lux excess kurtosis
and pressure population variance from at most ten light and six pressure sample
frames. Windows advance once per frame, not per sensor callback. Values older
than five seconds, invalid inputs and frame gaps over five seconds reset the
corresponding history. Warmup remains missing until two contrast/variance samples
or four kurtosis samples exist. The light callback rejects negative/nonfinite
readings rather than fabricating a zero illumination value.

The existing Python formulas and rounding are preserved on populated windows;
1Hz native windows differ in time scale from adaptive Python snapshots. Sources
explicitly describe low-frequency sample statistics, not optical flicker or
fluid-turbulence measurements. The Java harness verifies population formulas,
duplicate-frame suppression, six-frame eviction, stale input and gap resets.
It passes; the signed 189,163-byte APK is installed and the build receipt is
saved. This establishes implementation, not live feature-count coverage or
full extraction parity; continuous collection remains pending phone unlock.

### Native cold vocabulary binding regression

A Pixel SQLite regression first reproduced acceptance of a different token set
under an existing namespace with the same normalizer and bundle. Registration
now compares the actual stored cold-state set with the proposed vocabulary.
Set-preserving reordering remains valid; changed sets are rejected transactionally.
The corrected native fixture passes alongside cold backfill, binding, foreign
state, namespace and atomic export checks. The signed build is installed and
its source receipt saved. This fixture is independent of pending live service
startup and does not establish full-stage completion.

### Independent device feature fixtures and dated receipts

Magnetic inclination/reference anomaly and short-window population statistics
now have an independent APK fixture entry point. It passes on Pixel with a
completed timestamp. Cold and warm fixture receipts now contain completion
timestamps and write passed=false/error on caught exceptions instead of leaving
an old passed report untouched. Combined verification was initially read before
all fixtures completed; separate cold/warm reruns produced current dated passes.
The source/APK-bound fixture receipt records each completion time. The affected
host Java harness passes and the build is installed. Keyguard policy still
shows locked, untrusted and screen off. These are synthetic formula/storage
checks and do not establish live collection behind the keyguard.

### Native successive acceleration derivatives

The installed adapter now supplies snap, crackle and pop magnitudes by successive
vector finite differences at accepted accelerometer intervals of 2–300 seconds.
Raw derivative vectors are retained for the next order; reported magnitudes are
capped at 50 and rounded to four decimals, matching existing Python formulas.
Only three fixed-size historical vectors are retained. Each order stays missing
until its required history exists, and invalid input, reversed time or excessive
gaps reset the derivative chain. Source labels identify finite differences
rather than claiming an independently measured motion derivative.

A quartic acceleration fixture verifies snap 27.5, crackle 7.5 and pop 1.5 for
two-second intervals, with warmup missingness and invalid-input reset. The host
Java harness passes. The source-bound build receipt is saved and the APK is
installed. Live feature coverage remains pending unlocked collection.

### Full retained-stream topology diagnostics for weekly candidates

Guarded calibration now records baseline and candidate mean/p95 energy
quantization distance, occupied states and energy-based nearest-two grid
nonadjacency rate over all retained observations supplied to calibration.
Continuity resets use the existing actual-time scoring contract. Candidate
fitting still sees only the chronological training partition; full-stream
diagnostics are reported after fitting and do not replace held-out acceptance.
A single-node topology has no definable nearest-two adjacency rate. Tests
distinguish adjacent, nonadjacent and single-node cases and preserve the existing
activation/lineage checks. These are reconstruction/topographic diagnostics,
not proof of potential stability or physical causality. Device runtime deployment
of this maintenance-module update remains pending.

### Device topology diagnostics deployment and retained-hot evaluation

The topology module was deployed atomically after saving its prior version.
Device and host SHA-256 match; four native-Termux tests pass, including
activation lineage and grid-adjacency cases. A read-only SQLite snapshot
streamed all 401 retained hot observations through the active baseline locally:
mean energy distance 1875.971, p95 2569.591, seven occupied states and topographic
error 0.09227. Only these aggregates and module/registry bindings were exported.
No candidate was trained or activated, and no raw observation left the phone.
This is an empirical reconstruction diagnostic; without a matched historical
reference it does not establish improvement or a drift verdict.

### Weekly full-retained-stream numeric and chronological validation

Before weekly training, maintenance now streams every retained hot observation
through a timezone-aware timestamp/order and nested numeric-finiteness audit.
Bad timestamps, duplicate/reversed order and NaN/Infinity reject calibration;
gaps over 300 seconds are counted separately and do not by themselves reject
data. The bounded incremental training window also validates its inputs when
calibration is invoked directly. Neither audit claims sensor truth or modality
completeness. Tests cover nested invalid numbers, missing/naive/invalid time,
chronological reversal, legitimate gaps and empty input. Device deployment
of this new gate remains pending.

### Device full-stream validation gate deployment

The audit, storage iterator, maintenance entry point and tests were installed
with saved rollback copies and atomic per-file replacement in dependency order.
All four device digests match the host. Five targeted native-Termux tests and
the maintenance import/CLI check pass. A read-only SQLite snapshot validates all
410 retained hot observations: no invalid timestamps, nonchronological pairs
or nonfinite values; two gaps over 300 seconds are counted. No training or
activation was requested. The receipt binds the deployed sources. This does
not establish sensor completeness, 1Hz acquisition or scheduled Sunday delivery.

### Registered maintenance job dispatch did not establish completion

Android job 11401 still exists in waiting state. A forced JobScheduler dispatch
was accepted and returned to waiting, with onStartJob returned false in history.
The durable maintenance receipt remained at 2026-10-01T04:54:16.541935+00:00;
there is no new script completion evidence. Official Termux-API JobSchedulerAPI
source starts TermuxService with an execution intent and immediately returns
false, so that scheduler stop reason is handoff rather than command success.
Source: https://raw.githubusercontent.com/termux/termux-api/master/app/src/main/java/com/termux/api/apis/JobSchedulerAPI.java

This dispatch must not be counted as successful maintenance or Sunday delivery.
The next diagnostic needs an independent command-start/exit receipt to distinguish
failed launch from a launched script error. No calibration was requested.

### Maintenance dispatch repaired by restoring executable entry point

Inspection found edge_maintenance_job.sh mode 644 on the phone. Repository and
device entry points now have execute permission (device 755). Re-running the
same registered Android job 11401 produced a new actual maintenance receipt at
2026-10-01T06:55:44.123488+00:00, replacing the stale 04:54 receipt. It processed
418 hot rows and exported a 16,337-byte cold checkpoint with 22 cold rows.
No calibration was due or triggered. This verifies forced registered-job
delivery and script completion after the permission repair; it does not prove
Sunday scheduling, reboot delivery or long-term autonomous operation.

### Repeated delivered no-escape outcome suppression

The policy extends context-specific quiet time after consecutive evaluated
non-control interventions with zero observed state-change reward: 15 minutes
at baseline, 30 after one, and at most 60 after two or more. A subsequent
positive intervention outcome resets it. Randomized no-action controls, failed
deliveries and censored trials do not enter this calculation. Decision receipts
record the effective context cooldown and consecutive zero-reward count.
This is repeat-disturbance suppression based on observational outcomes, not a
claim of measured physiological fatigue or causality. The targeted policy test
passes. Device deployment remains pending. Actual pulse verification awaits
explicit disposition of the existing silent preference; production silence
has not been changed.

### Adaptive quiet policy deployed with live shadow reader restart

The new policy source was installed with its prior version saved. The phone
originally lacked the targeted test file; after installing it, all six
closed-loop tests pass in native Termux. Identity/pidfd-bound restart stopped
reader 15666 and launched reader 24186 (start ticks 4918556, same boot).
The new process is alive and the retained latest live_follow frame timestamp
is 2026-10-01T07:02:20.389579Z. The strategy digest matches the deployed source.
The silent flag, disable-haptics flag and shadow mode all remain present.
This establishes deployment and live shadow-reader operation, not real
actuation or an empirical fatigue effect. The user preference question for a
single controlled physical test is still pending.

### Reversible music-volume actuator entry point

MicroActuator now supports explicit volume_duck execution, honoring the same
silence/disable gates as pulse and notification. It validates the Termux volume
query, reduces only the music stream by one step for two seconds, then restores
the original level if the queried level still equals the imposed target.
Intervening external changes are preserved. Conditional restoration is attempted
even after a setter timeout. Receipts distinguish API acknowledgment, restoration
and execution errors; a zero initial level produces no intervention.

API shape was checked against official Termux VolumeAPI source:
https://raw.githubusercontent.com/termux/termux-api/master/app/src/main/java/com/termux/api/apis/VolumeAPI.java
Tests verify restore commands, external-change preservation and timeout recovery.
The policy still selects pulse/notification only; this new explicit action has
not been deployed or physically exercised and is not yet online bandit evidence.

### Configurable closed-loop action vocabulary deployed

The daemon accepts --intervention-actions with pulse, notification and volume_duck.
Defaults remain pulse/notification. Distinct nonempty supported action sets are
validated before opening the trial database. Exploration is distributed over
configured actions while preserving 25% no-action control and 60% exploitation;
decisions record the entire probability map. A three-action shadow fixture
selects volume_duck with its exact 5% exploratory propensity and dispatches nothing.

All 239 host tests plus eighteen subtests and eleven native-Termux closed-loop
tests pass. Three source digests match deployment. Identity-bound reader restart
produced live reader 25859 and a live_follow frame at 07:08:15.197800Z; silence,
disable-haptics and shadow mode remain enabled. No physical volume alteration
was performed. The source-bound receipt preserves this narrower scope.

### Intervention trial retention while the reader is active

Closed-loop observation now performs hourly terminal/shadow trial compaction
using wall-clock time, independently of replay/frame timestamps. Completed,
censored, restart-censored, failed-delivery and shadow trials older than thirty
days are removed. Equality at the cutoff is retained; queued and observing
trials and cumulative policy rewards are preserved. Tests cover these boundaries,
ongoing-trial protection and idempotence. This bounds old trial payload retention
during active observation; policy-context growth, SQLite free-page/file bounds
and idle scheduled cleanup still require separate treatment. The update has
not yet been deployed. No physical actuator was invoked.

### Scheduled intervention trial compaction deployed

Reader-triggered and scheduled cleanup now share compact_trial_rows, preserving
the same terminal-state cutoff and pending-trial protection. Maintenance opens
an independent database connection without constructing the intervention loop
or censoring its active trials. This path can run without observation frames.
Three updated sources were atomically deployed with rollback copies; twelve
native-Termux tests pass. Registered job 11401 completed at
2026-10-01T07:12:59.821059+00:00 and includes intervention_compaction in its
receipt (zero expired trials on this run). The synthetic boundary fixture
proves deletion and pending protection; the real job proves integration and
completion. Policy-context/file-size bounds remain separate open work.

### Exact arithmetic relation for the private alignment proof backend

A bounded integer GW witness validator now checks 1–16-node metric structures,
integer ranges, triangle inequalities, exact transport marginals and the exact
squared-loss numerator/denominator. Permuted zero-loss and uniform 1/2-loss
fixtures pass, alongside invalid range/metric/marginal rejection. The protocol
contract specifies range constraints, no-wrap field bounds and remaining
authentication, private computation, proof and output-leakage requirements.
This is locally evaluated plaintext arithmetic, explicitly not a cryptographic
proof or encrypted exchange. No cohort or model transfer was initiated.

### Source-bound cold potential persistence

Continuous fitting now rejects a mixture of model or normalizer digests even
when model_version is unchanged. A regression fixture first reproduced the
acceptance of mixed coordinate systems and passes after the fix. Model and
potential outputs retain those coordinate digests.

TieredStorage can retain one graph-potential projection per registered cold
lineage, with matching normalizer and source digest, at most 16 anchors of up to
128 dimensions and a 100000-byte uncompressed per-lineage limit. The existing
500000-byte compressed export gate still applies to the combined cold bundle.
The projection survives hot expiry and database reopening in the fixture.
run_continuous_simulation exposes explicit persistence with
--storage-database and --persist-potential-namespace. This path has been verified
on the host; it is not yet deployed or automatically scheduled. These are drift
projection coordinates, not verified attractors, thermodynamic energies or
bifurcation parameters. Twenty-seven affected tests pass.

The five corresponding source/test files were subsequently deployed to Pixel
6a with matching host/device SHA256 and rollback copies. All 27 affected tests
pass in native Termux Python in 3.079 seconds, using isolated temporary fixture
databases. The original reader remains running with disable-haptics, shadow
mode and the silent flag preserved. It was not restarted, so already imported
modules retain their prior code. No potential was written to the live database
and no actuator was invoked. Receipt: cold_potential_device_tests.json.

### Weekly potential maintenance integration

The validated weekly maintenance path now fits a bounded latest continuous
segment (at most 64 retained frames and eight anchors), persists its source-bound
graph projection before candidate calibration, and reports saved/skipped reasons.
It stops at coordinate provenance, stream discontinuity or gaps over 300 seconds;
insufficient or missing coordinates preserve the prior snapshot. Fitting uses
the actual intervals and only the latest registered lineage. A synthetic fixture
proves a prior representation is excluded and a later gap skips replacement.
Thirty affected host tests pass. This new scheduling integration is not yet
deployed or proven by a naturally occurring Sunday run. It does not add inferred
bifurcation parameters or a scientific equilibrium claim.

Weekly integration sources were then deployed with matching host/device SHA256
and rollback copies. The native Termux suite discovers 30 tests: 29 pass and
one host-only tool-interception case is skipped. The direct invocation first
failed because PYTHONTZPATH was omitted; retesting with the production wrapper's
existing timezone path passes in 3.622 seconds. This does not prove a natural
Sunday trigger or live potential persistence. Evidence:
weekly_potential_device_tests.json.

### Memory diagnostics bind fitted coordinates

Regular and irregular residual-memory diagnostics now check every endpoint's
model and normalizer digest against the fitted coordinate provenance and retain
those digests in diagnostic outputs. A same-version, foreign-normalizer fixture
first failed to reject the input and passes after the fix. Thirty-one related
host tests pass; this update has not yet been redeployed. This protects coordinate
comparability, not a claim that fractional memory has been empirically identified.

The updated continuous module and its test file were then atomically deployed
with rollback copies. All 14 continuous tests pass in native Termux Python in
2.040 seconds. Device source SHA256: continuous.py
36ec868d8550efae7090c1f3e8c3909759766e3dfb3678f0492a8fae853e2c72;
test_continuous_dynamics.py
8b3678d2d618cb4a79c0c750baa1dabf4908b0fdfeee478261d298c1a8acfc10.
No live reader restart, new empirical memory estimate or actuation was performed.

### Full-dimensional continuous numerical fixtures

The continuous suite now includes an actual 128-dimensional irregular-interval
fit, exact constant drift and graph-potential recovery, and Euler–Maruyama
terminal propagation. A separate 128-dimensional rank-one correlated diffusion
fixture verifies additive fractional terminal variance against 2*T^(2H), with
T=4 and assumed H=0.25, and checks spatial correlation with numerical tolerance.
Sixteen continuous tests pass on the host. These are synthetic mathematical
fixtures, not empirical subdiffusion evidence, online sandbox integration or
the native core performance workload. The new fixture file is not yet deployed.

### Experimental Groth16 proof generated and verified

The independent Rust backend constrains metric ranges, symmetry, diagonals,
triangle inequalities, mass totals, exact transport marginals and the public GW
numerator. Four Rust tests pass, including real randomized proof generation and
verification for a synthetic two-node zero-objective fixture. Modified public
objectives and proof group elements are rejected, and repeated proofs differ.
Temporary parameters and proof randomness use the operating system random source.
The test suite runs in 1.33 seconds after compilation on the host.

This is not complete encrypted cross-owner alignment: the prover still knows
both metric structures, no owner commitments or session binding exist, and the
temporary local setup is not a production ceremony. Android proof verification
and two-owner secure computation remain open. No device model or cohort data
was used or transferred.

### Model-structure commitments and session binding in the proof

The bound Rust circuit computes SHA256 from the same constrained metric and
mass variables used by the GW objective. Domain, ordered owner position, node
count, scale, public session and private 32-byte nonce are included. The integer
encoding is four-byte little-endian. A generated bound proof verifies; altered
session or commitment public inputs are rejected. Changing private nonce with
the commitment fixed makes the circuit unsatisfied. Public field packing is
checked against the constraint-system input assignment. The targeted bound
proof fixture passes in 5.51 seconds on the host.

Owners are not authenticated yet and no used-session ledger is implemented;
session binding alone is not replay prevention. Tests use synthetic nonces.
The prover still knows both models, so two-owner secure computation and Android
verification remain open. These changes do not enable network model exchange.

### Public-only Groth16 verifier deployed and run on Pixel

An isolated official Rust 1.94.0 toolchain and NDK r27c produce an Android ARM64
release verifier (1011096 bytes). The Homebrew compiler initially rejected the
official Android standard library due to compiler-build incompatibility; the
matched official compiler, host and Android libraries resolve it without changing
the system installation. Official downloads were checked against their SHA256.

Pixel 6a verifies the original synthetic bound package and rejects the modified
public objective with exit status 1. The executable and three public material
files have matching host/device SHA256. No private witnesses, nonces, proving keys
or device models were transferred. Evidence: zk_public_cli_pixel.json. This
completes standalone on-device proof verification, not APK integration, owner
authentication, replay rejection or two-owner secure computation.

### Verifier requires an independently trusted key pin

The verifier now requires a 64-character lowercase SHA256 key pin from its
caller, checks it before proof decoding, and reads package files with bounded
limits. A host test rejects key replacement and malformed pins. The updated
Android executable (1028408 bytes, SHA256
86effa3cdad54bcab25e362d8a72d17e03109f10945c108a774149382c3f9995)
was deployed under a separate path, preserving the earlier verifier. Pixel
accepts the known synthetic fixture key pin and rejects a mismatched pin with
exit 1 and 'untrusted verifying key'. The pin must come from trusted configuration;
copying it from an untrusted package would defeat this check. This adds parameter
identity checking, not setup ceremony trust, model-owner authentication or replay
protection.

### Persistent local replay rejection verified on Pixel

The public verifier now consumes a session only after its proof verifies. Its
locked append-only ledger syncs file and parent directory, rejects duplicate
sessions and malformed records, and fails closed at 4096 entries (131072 bytes).
Four concurrent host submissions have exactly one winner; reopening rejects the
same session. Packed public bytes must also use canonical zero padding.

The initial Android run failed because Rust File::lock is unsupported. No session
was consumed. The corrected Android-only libc flock path was built and deployed
(binary SHA256 77d3642ac6b62d0a92974b98614dc3d06d8ef63dd5d5ee5b81dddf539befa6c1).
Pixel then accepted the first synthetic submission and rejected the next process
with 'replayed session', exit 1. The ledger remains exactly 32 bytes. This fixture
uses /data/local/tmp; production must place the ledger in trusted app-private
persistent storage. Model-owner authentication and two-owner secure computation
remain open.

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

Native periodic-kernel SDK progress: Python probability parity passes across
three IANA zones including DST; the installed APK passes exact-phase
daily/weekly conditioning into 300×120 native rollouts on synthetic records.
All 281 host tests pass. First fixture CDF construction took 90.94ms; no
end-to-end <15ms claim is made. Live native service history integration and
predictive validation remain open. See `native_periodic_kernel_test.json`.

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

### 2026-10-01：周期核的相位范围对照

新增独立差值公式对照，覆盖随机相位、32 rad 边界范围，以及同号 1e16/1e308 有限相位；7 状态、17 步、257 对历史，CDF 误差要求 ≤1e-12，全部通过。不同号极端相位造成差值溢出的拒绝测试仍通过。全套 285 项测试通过（19.226 秒），本轮未改变已部署的设备二进制。证据：`artifacts/native_android_v1/native_periodic_phase_range_test.json`。这项核对不证明月度性能门槛或实时预测有效性。

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

### 2026-10-01：冷层保留连续势场平滑参数

检查确认图势能冷层载荷缺少连续平滑所需的 bandwidth。回归先观察缺失参数错误，以及 NaN/Infinity 带宽未拒绝的失败；现在推断结果包含 bandwidth，势场查询拒绝与当前模型不匹配的带宽。冷层新保存操作要求 bandwidth、mobility 为有限正值，已有归档保持原样。非默认 0.7 带宽在热层过期、关闭重开、gzip 导出后完整保留，来源摘要和坐标绑定检查继续通过。旧归档没有带宽时，无法据此确认原连续平滑定义。

本地 286 项测试通过（19.763 秒）；四个源码/测试文件已原子部署到 Termux，并保留 .pre-cold-bandwidth-20261001 备份。未重启采集进程、未改写历史数据库。真机 32 项隔离合成测试通过（29.543 秒）。证据：`artifacts/native_android_v1/cold_bandwidth_host_test.json`、`cold_bandwidth_source_deployment_pixel.json`、`cold_bandwidth_test_pixel.json`。这补全势场定义的一个必要参数，不等同于已经识别分叉参数或证明数年运行；原生 Android 冷层也尚未接入该连续势场。

### 2026-10-01：仅凭冷层参数重建连续势场

新增 evaluate_graph_potential，使用已归档中心、相对能量、bandwidth 和 mobility 计算连续能量、解析梯度及保守漂移，无需热层轨迹或原经验漂移/扩散模型。现有 potential_at 复用同一计算并继续报告经验残差。入口校验有限值、维度、协议与正参数，缺带宽的旧载荷不能据此重建。热层全部过期、SQLite 重开、gzip 解压后的结果与原模型能量/梯度/保守漂移逐值一致；原解析梯度与数值差分验证仍通过。

本地 286 项测试通过（19.950 秒）；修改源码已原子部署真机并保留 .pre-cold-reconstruction-20261001 备份。真机 32 项合成测试通过（28.896 秒），未重启采集或访问真实历史。证据：`artifacts/native_android_v1/cold_reconstruction_host_test.json`、`cold_reconstruction_source_deployment_pixel.json`、`cold_reconstruction_test_pixel.json`。重建的是平滑图势场，不能恢复经验扩散张量或原漂移残差，也不证明分叉参数识别完成。

### 2026-10-01：归档势场完整曲率与维度边界

新增 graph_potential_curvature：按归档 Gaussian 权重计算完整 Hessian 和 -mobility*Hessian 保守漂移 Jacobian。使用中心相对加权均值的差值计算，保持对称性；与能量/梯度查询共用参数检查和权重，不增加归档体积。1/3/128 维独立梯度差分验证通过，能量零点平移不改变曲率；热层过期、数据库重开、gzip 还原后曲率一致。本地 287 项测试通过（20.254 秒）。源码原子部署真机并保留备份，33 项合成测试通过（28.597 秒），未重启采集。

科学边界：Hessian 是锚点中心差向量外积的加权和，其秩最多为锚点数减一；最多 16 锚点的该平滑势场在 128 维中有未覆盖方向，不能据此确认孤立的完整 128 维稳定平衡点。128 维双锚点合成探针中，正交 127 维梯度和 Hessian 均为零。分叉参数识别仍需控制参数族、平衡点求解、退化与横截条件验证；当前尚未实现这些验收。

证据：`artifacts/native_android_v1/cold_curvature_host_test.json`、`cold_curvature_source_deployment_pixel.json`、`cold_curvature_test_pixel.json`、`cold_curvature_flat_direction_probe.json`。

### 2026-10-01：势场重建的浮点可表示范围

回归先复现有限带宽 1e-300 的平方下溢/除零、1e300 的平方溢出、极端坐标平方溢出，以及非有限梯度/Jacobian 未拒绝。现在构造和冷层新保存拒绝不能形成有限正平方尺度的带宽；归档查询检查缩放距离，并对能量/梯度/保守漂移/Hessian/Jacobian 的非有限结果显式报 ValueError。距离平方改用乘法后检查可表示性，避免未处理的幂运算溢出。一个极值样本在原查询点仍有有限梯度，回归调整到真正梯度溢出的点，未据此否定有限结果。

本地 288 项测试通过（20.270 秒），四个源码/测试文件已原子部署真机并保留 .pre-cold-finite-range-20261001 备份。真机 34 项隔离合成测试通过（29.309 秒），采集未重启。证据：`artifacts/native_android_v1/cold_finite_range_before_host.json`、`cold_finite_range_after_host.json`、`cold_finite_range_source_deployment_pixel.json`、`cold_finite_range_test_pixel.json`。这些检查只覆盖当前列出的边界，不证明所有浮点输入、完整动力学拟合范围或分叉识别验收已完成。

### 2026-10-01：结构发现基础图指标的修复

检查每周候选生成确认其仍只更新固定网格原型，尚未实现结构发现接入。可复用的 transition_graph 存在硬编码 SCC=1、空输入指标构造字段不符、跨片段/非正时间仍累计边的问题。新增回归先观察失败，再修复：按实际经验边用迭代双遍图搜索计算 SCC；只累计正时间且在 max_gap 内、连续片段与会话/模型摘要/归一化摘要匹配的转移。无出边行的自环退回值不计为经验边。空输入返回零节点、零边、零分量。80 个随机小图与独立传递闭包 SCC 计数一致。

本地 292 项测试通过（19.946 秒），匹配文件风格后 7 项受影响测试再次通过；源码原子部署真机并保留 .pre-transition-components-20261001 备份，7 项合成发现测试通过（0.049 秒），未重启采集或读取真实轨迹。证据：`artifacts/native_android_v1/transition_components_host_test.json`、`transition_components_source_deployment_pixel.json`、`transition_components_test_pixel.json`。本轮修复图分析的前置条件，尚未将结构发现接入每周候选，也不证明稳态迭代收敛或自然周日调度已验收。

### 2026-10-01：周期图稳态与收敛证据

回归先复现 A↔{B,C} 周期图在固定 100 次迭代后 A 概率为 0.33333（正确稳态为 0.5）。转移概率现在保留完整计数比值；稳态迭代采用 (I+P)/2 消除周期振荡，并检查原 P 上的 L1 残差，最多 1000 次。指标新增 stationarity_residual、stationary_converged、stationary_iterations，分布保留完整精度。周期图通过；慢混合合成图在 1000 次后显式未收敛，残差 0.0002360873574789868，与独立计算一致。收敛不表示稳态唯一或图具有遍历性。

本地 293 项测试通过（19.957 秒）；源码原子部署真机并保留 .pre-transition-stationary-20261001 备份，8 项发现合成测试通过（0.063 秒），未重启采集或读取真实历史。证据：`artifacts/native_android_v1/transition_stationary_host_test.json`、`transition_stationary_slow_mixing_host.json`、`transition_stationary_source_deployment_pixel.json`、`transition_stationary_test_pixel.json`。每周增量结构发现仍未接入候选生成。

### 2026-10-01：每周候选接入增量转移结构分析

assess_topology 现在将相同量化过程的结果交给已修复的转移图分析，保存矩阵、SCC、稳态收敛信息、有效转移数量，以及模型/归一化/输入摘要。候选验收记录新增 incremental_transition_structure，分别保留训练和留出段的 baseline/candidate 分析，防止训练与留出边界串联。会话、模型摘要或归一化摘要变化时，评分的速度与归一化历史状态也重置。合成测试验证分段摘要、冻结归一化摘要、父版本/标签谱系和有效转移计数。

本地 293 项测试通过（20.533 秒），三个源码/测试文件已原子部署真机并保留 .pre-weekly-structure-20261001 备份。第一次真机维护合成测试因入口未设置生产 PYTHONTZPATH，UTC 不可用；入口复用已有 TZif 路径后 15 项执行通过、1 项主机拦截检查按设计跳过（总 16 项，2.357 秒）。没有安装依赖、强制真实校准、修改真实注册表或重启采集。证据：`artifacts/native_android_v1/weekly_structure_host_test.json`、`weekly_structure_source_deployment_pixel.json`、`weekly_structure_before_pixel.json`、`weekly_structure_test_pixel.json`。每周分析路径已接通，网格结构的自动增量调整尚未实现，当前激活门槛仍是留出距离/覆盖，自然周日执行也未验收。

### 2026-10-01：训练限定的增量网格结构校准

候选生成现在先统计训练段两个最近原型的配对频次，再按严格下降的加权非相邻配对目标，贪心交换候选节点网格位置；保持原型标签、节点数、坐标集合与冷层标签谱系。随后在调整后的邻接结构上执行既有 6 轮 warm-start SOM 拟合。坐标交换记录和训练来源摘要存入 structural_change，布局前/后的配对损失针对布局步骤中尚未重拟合的原型。激活新增留出拓扑误差不增加门槛，仍须满足原有距离改善 5% 与覆盖条件。贪心结果不是全局最优，也没有新增节点。

新增拒绝测试发现旧 ManifoldNode.from_dict 共享 center_x/center_v 列表，候选拟合能污染内存基线。独立回归先观察源字典与参考中心被候选修改，再通过反序列化时复制两数组修复；现在拓扑恶化候选会被拒绝，注册表活跃版本保持不变。不能把旧方法的距离门槛自动解释为独立基线验证。合成布局损失 3→0，标签/中心与冻结基线保持不变，重复布局为无改动。

本地 295 项测试通过（20.691 秒），四个源码/测试文件原子部署真机并保留 .pre-grid-adaptation-20261001 备份。真机 19 项执行通过、1 项主机专用测试跳过（总 20 项，6.200 秒）。未强制真实校准、激活真实候选、重启采集或触发干预。证据：`artifacts/native_android_v1/grid_adaptation_host_test.json`、`grid_adaptation_source_deployment_pixel.json`、`grid_adaptation_test_pixel.json`。原有节点的网格结构调整路径已实现；真实候选收益、自然周日调度、节点增长和完整科学验证未由这些合成测试证明。

### 2026-10-01：训练与评分的连续性边界一致

回归先证明训练在 session_id/model_sha256/normalizer_sha256 变化时未重置归一化和速度历史（3 个子案例均失败）。训练 discontinuities 现在额外绑定这三个字段，与评分/转移结构分析一致；切换点不再用上一片段的速度参与候选拟合。11 项流形测试通过，全套 296 项通过（20.567 秒）。源码原子部署真机，保留 .pre-training-binding-20261001 备份；真机 20 项执行通过、1 项主机专用测试跳过（总 21 项，6.059 秒），不运行真实校准或重启采集。证据：`artifacts/native_android_v1/training_binding_host_test.json`、`training_binding_source_deployment_pixel.json`、`training_binding_test_pixel.json`。这证明列出的训练边界处理，未证明真实候选收益或自然周日执行。

### 2026-10-01：未模拟拟合/评分的结构校准端到端检查

新增 128D、4 节点、100 帧的合成端到端测试，使用实际冻结归一化、训练限定的坐标交换、6 轮 SOM、实际留出评分，以及临时注册表的激活/回滚。训练布局非相邻配对 70→0，合成探针留出平均距离 3254.18921→8.60535，拓扑误差 1→0；候选激活后可回滚到原版本，冻结基线、归一化与标签集合保持不变。未模拟拟合/评分结果，输入并非真实传感轨迹，数值只说明此合成流程可完成。

本地 297 项测试通过（20.900 秒）；测试源码部署真机，相关 21 项执行通过、1 项主机专用检查跳过（总 22 项，6.978 秒）。测试仅使用临时模型注册表，未修改真机当前模型或采集。证据：`artifacts/native_android_v1/structural_end_to_end_host_test.json`、`structural_end_to_end_test_deployment_pixel.json`、`structural_end_to_end_test_pixel.json`。真实数据上的候选收益、自然调度和节点增长仍未由此验收。

### 2026-10-01：四模型满配冷层体积与无损还原

新增满配合成冷层测试：4 个独立 16 节点/128D 模型、完整 16×16 稠密累计转移计数、16 锚点/128D 的势场、训练/留出/全流的 baseline/candidate 结构矩阵，以及模型/归一化/来源摘要。参数使用固定种子的独立随机值，gzip 导出 246684 B；模型束、势场、转移计数和来源绑定逐值无损还原。500000 B 超限保护的已有原子保留测试继续通过。这个样本不构成任意数据的体积上界，也不证明数年运行。

全套本地 298 项测试通过（22.065 秒）；测试文件原子部署真机，在隔离临时数据库中的满配测试通过（0.225 秒），同样导出 246684 B。未读取/修改真实历史或注册表。证据：`artifacts/native_android_v1/cold_capacity_host_test.json`、`cold_capacity_test_deployment_pixel.json`、`cold_capacity_test_pixel.json`。

### 2026-10-01：原生 IMU 自相关衰减输入

原生特征窗口新增 imu_temporal_autocorrelation_decay，最近 25 个新鲜加速度模长采样帧，至少 8 帧后计算绝对 lag-1 ACF 的 -1/log(rho)，rho 限制在 0.01–0.99，结果限制在 0.1–10。来源明确单位为采样帧，不解释为秒或生理记忆。平坦序列、无效输入、未更新的传感器时间戳和采样中断保留缺失或重新预热；同一窗口调用不重复入队。

主机回归覆盖预热、公式、25 帧上限、重复帧、持有旧输入、平坦序列、无效输入与间断；全套 298 项通过（22.385 秒）。APK 已更新安装，Pixel 6a 内置合成验证通过；未启动实际采集。证据：artifacts/native_android_v1/accelerometer_acf_host_test.json、accelerometer_acf_test_pixel.json。此项新增派生输入，不代表完整 128D 原生实测覆盖。

补充时钟回退回归先失败，窗口清空时同步重置最后传感器时间戳后恢复。最终版本原生相关 15 项通过（4.540 秒），再次打包安装后真机合成验证通过；298 项全套运行发生在此单行修复之前。回执和源码/APK 摘要已更新为最终版本。

### 2026-10-01：原生加速度排列熵

原生 permutation_entropy_complexity 改为对实际新鲜加速度模长窗口统计，而非由运动能量估算。定义为最近 25 帧、order=3、delay=1 的三点严格排序模式概率的 Shannon 熵/log(6)，结果四舍五入至三位。排除含相等值的三点组，至少 8 个无相等值的模式才输出；全相等或二值交替序列因无有效三点组保留缺失。沿用 IMU 窗口的缺样、间断及回退重置。短窗口样本估计有偏，排除相等值也是明确的实现选择，不代表生理复杂度。定义参考 Bandt/Pompe 论文 https://doi.org/10.1103/PhysRevLett.88.174102 。

测试覆盖单调序列零熵、预热、持有旧输入、相等值排除，以及六种排序模式的混合序列；混合序列独立 Python 排序计数为 4/4/2/2/1/1，归一化熵 0.920。全套 298 项通过（22.973 秒），包含上一轮时钟回退修复；APK 已安装真机，Pixel 6a 合成公式验证通过。证据：artifacts/native_android_v1/ordinal_entropy_host_test.json、ordinal_entropy_test_pixel.json。没有启动真实采集，未验证模型预测收益或完成 128D 全覆盖。

### 2026-10-01：原生三点 Teager 算子

non_linear_energy_operator_teager 由最近三个新鲜加速度模长的中点平方减两端乘积计算，沿用特征 0–10 非负截断和三位小数；中间帧结果延迟一帧输出。输入包含重力，单位由加速度平方决定，来源明确该离散信号算子不是焦耳能量。缺样、旧输入、间断和时钟回退沿用既有窗口重置，无需新增权限。公式依据 https://academic.oup.com/gji/article/197/1/277/688669 的离散定义；不据此推断身体能耗或预测收益。

回归覆盖三帧预热、正值 7、负值截断 0、96 截断 10、常量零、旧输入与间断重置。全套 298 项通过（21.465 秒），APK 已安装 Pixel 6a，合成验证通过；没有启动实际采集。证据：artifacts/native_android_v1/teager_host_test.json、teager_test_pixel.json。完整阶段验收及仓库推送仍未完成。

### 2026-10-01：闭环结果的来源绑定边界

闭环 observe 原先只检查版本、启动和连续性字段；新增回归先证明 session_id/model_sha256/normalizer_sha256 任一切换仍会被 evaluated 并计入奖励（3 个子案例失败）。现在当前帧与试验起点、上一观察帧的上述来源字段也必须相同，否则 censored，不更新策略奖励。历史缺失字段继续按双方 None 相等处理，不据此声明历史来源已验证。

全套 299 项通过（22.070 秒）；两个源码/测试文件原子部署到 Termux 并保留 .pre-outcome-binding-20261001 备份，Pixel 6a 临时数据库中 13 项闭环测试通过（0.292 秒，无跳过），执行器均禁用或模拟。未重启现有采集，因此已运行进程不会自动重新导入这份修复；它在下次正常启动时生效。证据：artifacts/native_android_v1/outcome_binding_host_test.json、outcome_binding_source_deployment_pixel.json、outcome_binding_test_pixel.json。此修复防止跨来源奖励，不证明真实干预的因果收益；色温执行器等原始阶段要求仍待完成。

### 2026-10-01：音量执行回执验证

回归先复现音量命令成功但实际未下降仍报告 delivered=true。现在必须在命令成功后读到目标音量才计为送达，才能等待两秒并进入结果观察；失败回执明确 target_volume_not_verified。条件恢复继续只在音量仍为目标值时恢复原值，不覆盖期间外部修改；设置超时仍尝试原有条件恢复。

全套 300 项通过（21.430 秒）；源码原子部署并保留 .pre-volume-readback-20261001 备份。真机 14 项隔离测试通过（0.256 秒，无跳过），所有音量调用模拟，没有真实修改音量或重启现有进程；下次启动生效。证据：artifacts/native_android_v1/volume_readback_host_test.json、volume_readback_source_deployment_pixel.json、volume_readback_test_pixel.json。读回只证明目标设置曾被观察到，不证明实际听觉效果或因果收益。

### 2026-10-01：月度时间核优化试验未采用

当前月度基准 86400 对历史的双精度相位精确分组为 57086 组；数学上的 30 秒周周期有 20160 组，但现有浮点生成不全部按位重复。没有引入时间舍入来强行合并，也未修改生产内核。

隔离临时源码尝试 [-8,0] 指数表（间距 1/256，四阶局部展开，范围外 std::exp）。用同一 NDK 和 -O3/-ffp-contract=off 构建、Pixel 6a 交替测量：原内核 83.812947/83.643474ms，试验 107.705892/107.140340ms，均为 128D/16 状态/120 步/300 rollout/86400 对历史，质量守恒。试验更慢，未进入 APK或运行中的服务。标量采样误差记录不等于 CDF parity 或形式误差证明。

证据：artifacts/native_android_v1/periodic_duplicate_phase_probe.json、periodic_exp_table_probe_host.json、periodic_exp_table_probe_pixel.json。月度历史完整核心耗时仍超过 15ms，后续需要计算组织优化；本次未改变既有性能验收范围。

### 2026-10-01：色温执行器的真机能力边界

只读核对 Android 夜间显示实现与真机配置：Pixel 6a config_nightDisplayAvailable=true，设备资源最低/最高色温 686/4082K（不能假定官方示例或其他手机范围）。Termux 请求 WRITE_SECURE_SETTINGS，但本次包状态未观察到已授予项。实际 UID 10216 的 cmd overlay lookup 无法访问 overlay 服务（退出 20）；settings get 默认用户要求 INTERACT_ACROSS_USERS（退出 255），显式 --user 0 仍要求 MANAGE_USERS/CREATE_USERS/QUERY_USERS 之一（退出 255）。没有执行写设置或授予权限，也没有调整屏幕。

此证据排除了直接把 shell 色温命令接入现有 Termux 执行器的方案；下一步为原生 ContentResolver/设备资源的能力和权限接入。它不证明色温执行器完成。官方实现依据 https://source.android.com/docs/core/display/night-light 及 ColorDisplayService 源码 https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/display/color/ColorDisplayService.java 。证据：artifacts/native_android_v1/night_display_capability_pixel.json。生产源码未改，未重复运行无关测试。

### 2026-10-01：原生夜间显示只读 API 接入

新增 NightDisplay.capabilities(Context)，通过 Android Resources 与 Settings.Secure ContentResolver 读取设备范围、启用状态及权限。未设置的值返回 unset/null，被拒绝的读返回 permission_denied；未推断默认值或控制能力。MainActivity 新增仅调试构建的 verifyNightDisplay 入口，保存本机只读回执。未增加权限声明或授予权限。

APK 构建签名验证通过并安装 Pixel 6a。真机原生资源确认色温 686–4082K，夜间显示可用；激活状态读取为 0，auto mode 和色温设置 unset，两项写控制权限均 false。读回路径不需要 Termux 的 shell 权限。settings_writes=0，没有启动传感器或更改显示。证据：artifacts/native_android_v1/night_display_native_build.json、night_display_native_capability_pixel.json。下一步为控制接口和权限/恢复流程；只读能力接通不能计作色温干预完成。

原生相关 15 项测试通过（4.616 秒）；本轮未运行无关的完整 Python 套件。

### 2026-10-01：夜间显示能力的跨进程 SDK

原生 Binder 新增只读事务 3 与 CanonicalClient.nightDisplayCapabilities；沿用签名权限及接口令牌验证，服务销毁时拒绝。真机客户端/服务不同进程，能力与本地资源查询一致，未启动采集或写设置。15 项原生相关测试通过（5.042 秒），APK 已更新安装。原预测事务另做只读真机回归，回执保存为 night_display_sdk_forecast_regression_pixel.json。

证据：artifacts/native_android_v1/night_display_sdk_pixel.json、night_display_sdk_build.json。该接口完成能力接入，不等于色温写入、恢复流程、权限授权或实际干预完成。

### 2026-10-01：原生色温控制与条件恢复逻辑

新增纯 Java ColorTemperatureAdjustment 与 NightDisplay.adjust(Context,target) 包装。目标必须在真实设备范围内，用户 interventions/enabled 首选项默认 false、应用私有 .silent 存在或缺少 WRITE_SECURE_SETTINGS 时均不操作；还要求既有夜间显示已启用且手动 auto mode=0、原色温为有效显式值。不会开启夜间显示或改自动模式。设置后读回目标才 delivered；两秒后仅当色温仍为目标且模式/启用状态未变时恢复原色温，不覆盖外部色温改变。InterruptedException 路径先尝试恢复，再保留线程中断标记；读/写异常可能中止恢复，进程死亡后的持久恢复未实现。

控制边界使用 Settings API 适配器与模拟控制器分别验证，未新增外部依赖。主机覆盖恢复、未生效、外部改变、默认关闭、缺权限、自动模式、夜间显示关闭、越界和中断。全套 301 项通过（27.001 秒），回执字段随后改为 settings_write_attempts（API 尝试次数，不推断实际写入）；最终控制测试再通过（0.414 秒）。APK 已安装，Pixel 6a 模拟恢复与原生无权限守卫通过，真实写尝试为 0。

证据：artifacts/native_android_v1/color_adjustment_build.json、color_adjustment_pixel.json。尚未接入 Binder 写事务、用户启用入口、CSD/沙盒策略与持久恢复；manifest 未申请 WRITE_SECURE_SETTINGS，当前应用不可执行实际色温改变。因此不计为完整色温执行器验收，也没有更改真机显示、权限或静默状态。

### 2026-10-01：恢复失败的回执与奖励边界

主机回归先证明：目标色温读回成功但恢复调用失败时，原因仍只有 target_setting_verified；修复后保留 delivered=true 的已观测事实，增加 restoration_status=failed 和 restoration_failed 原因。正常恢复/外部改变分别区分 restored/external_change_preserved；NightDisplay 回执仅在送达且确认恢复后标记 eligible_for_outcome=true。异常中断/进程死亡的持久恢复仍未完成。

闭环消费回归又先证明 delivered=true 且 eligible_for_outcome=false 会进入 observing。现在记录 censored_delivery，保留原回执，不观察或累计奖励；此终态纳入 30 天试验行清理。没有显式资格字段的既有回执保持原行为，因此不能据此宣称全部历史操作都验证了恢复。

全套 302 项通过（22.648 秒）。Python 两文件原子部署，备份 .pre-restoration-outcome-20261001；真机 15 项隔离闭环测试通过（0.325 秒，无跳过）。APK 更新安装，模拟恢复失败及原生零写尝试守卫通过；没有真实调整屏幕或重启 Python 采集。证据：artifacts/native_android_v1/restoration_outcome_host_test.json、restoration_outcome_source_deployment_pixel.json、restoration_outcome_loop_test_pixel.json、restoration_outcome_color_test_pixel.json。此项不等于实际色温执行器或完整物理闭环验收。

### 2026-10-01：色温调整的持久恢复记录

ColorTemperatureJournal 使用 Android AtomicFile 保存 12 字节版本标识/原色温/目标色温，显式文件 sync，完成后读回核对。已有待恢复记录拒绝覆盖；严格拒绝截断、错误标识和尾随字节。ColorTemperatureAdjustment 在首次设置前调用记录钩子；恢复确认或外部接管后清除，失败/中断读取异常保留。保存失败时不修改设置。同进程 run/recover 用同一类锁串行，但未建立跨进程锁。

新增恢复算法重新核对设备范围、权限、当前温度与手动启用状态；原值已在时无写清理，仍是原目标时恢复，外部改变时不覆盖，失去权限保留 pending。NightDisplay.recover 为包内显式入口，回执永远不标记新干预送达或可计奖励；尚未接入自动启动恢复。新调整在 pending 存在时拒绝进入，不擅自处理。

主机覆盖恢复、已恢复幂等、外部接管、失权、记录保存失败零设置调用及原有中断检查；全套 302 项通过（23.331 秒）。APK 更新安装，Pixel 6a 使用独立私有 cache 文件验证真实 AtomicFile 保存/重新实例化读回、待恢复拒绝覆盖、模拟恢复、清除和损坏记录拒绝。临时文件清理，真实设置写尝试仍 0。证据：artifacts/native_android_v1/color_journal_build.json、color_journal_pixel.json。未杀死真实调整进程或测试断电；自动启动恢复、跨进程互斥、权限与用户入口及策略接入仍未完成。

### 2026-10-01：色温调整与恢复的操作互斥

复用 AlignmentQueryLease 的规范路径身份、进程内准入及 FileChannel 锁，为色温增加独立 color_temperature.lock 入口。保留既有公开对齐入口、文件名和忙碌错误；两类用途不争同一锁。NightDisplay.adjust/recover 从读取记录之前直到设置恢复/记录清除均持色温锁，阻止同时清理、恢复或开始调整。

主机分别对两用途验证同进程重复拒绝、独立用途可进入、另一 JVM 拒绝、持锁 JVM 被 kill 并确认终止后新进程可进入。全套 303 项通过（23.261 秒）。APK 更新安装，Pixel 同进程同时调整与恢复均拒绝、释放后重入及原对齐锁回归通过；模拟记录/恢复继续通过，实际设置写尝试 0。

证据：artifacts/native_android_v1/color_operation_lock_build.json、color_operation_lock_pixel.json、color_operation_lock_alignment_regression_pixel.json。跨进程/被终止持有者的证据是主机 JVM，本轮未做 Android 跨进程色温操作或真实调整；自动恢复入口、用户/权限/策略接入与完整验收仍未完成。

## 2026-10-01：屏幕色温恢复任务

调整前先保存 AtomicFile 待恢复记录，再注册持久化 JobScheduler 任务；注册失败不执行目标设置写入。任务在 `:edge` 进程调用互斥恢复路径，待恢复记录仍存在或发生异常时请求退避重试。停止回调使旧工作线程不能向已停止的任务报告完成。没有待恢复记录时不读写设置、不采集传感器、不计为干预或反馈。新增 RECEIVE_BOOT_COMPLETED 普通权限及 BIND_JOB_SERVICE 保护的服务；未授予设置写入权限。

Pixel 6a 安装成功。任务最初处于 waiting，随后通过系统测试命令强制调度；同一 nonce 的回执由不同进程产生，reason=no_pending_recovery、settings_write_attempts=0、retry=false。303 项测试通过（23.232 秒）。证据：`artifacts/native_android_v1/color_recovery_job_pixel.json`。这里只证明持久化任务注册和强制调度无操作路径；未验证自然调度、重启/断电、真实色温恢复或系统对恢复时限的保证。JobScheduler 为尽力调度，不保证 30 秒内运行。

## 2026-10-01：准备失败与外部设置竞争

复现：beforeAdjustment 保存记录后注册任务失败，同时外部把色温设为目标值；原清理逻辑仍写回原值。回归先失败，再增加本次写入尝试门槛，使准备失败路径不读写设置，保留待恢复记录。已经尝试设置但抛异常的路径仍执行条件恢复。回归通过；303 项测试通过（22.635 秒）。修复 APK 已安装 Pixel 6a，模拟/权限保护回执通过、实际设置写入 0 次；未验证真实设置变更。证据：`artifacts/native_android_v1/color_preparation_failure_pixel.json`。恢复 Job 异常目前仅写日志，失败回执持久化仍待补齐；消费者不得把旧成功回执视为新一次任务结果。

## 2026-10-01：恢复任务异常回执

真机调试注入在设置访问前抛 IOException，先复现新任务仍保留旧成功回执。修复后正常和异常结果均使用 AtomicFile 保存本次 nonce、进程、完成时间及 retry；恢复异常不计为干预/反馈，设置写入次数为未知 null，避免把可能部分执行的恢复误报为零。保存回执失败会请求重试并保留日志。

Pixel 6a 修复 APK 安装成功，强制调度后异常回执 nonce 与注册一致、服务和客户端进程不同、retry=true。303 项测试通过（23.123 秒）。异常注入仅限可调试应用、无待恢复记录检查入口；测试任务已取消，调度器确认找不到该任务。证据：`artifacts/native_android_v1/color_recovery_exception_pixel.json`。尚未验证真实设置异常、磁盘写失败、系统自然退避或重启恢复。读取回执仍必须核对本次任务标识；若持久化本身失败，旧文件可能保留。

## 2026-10-01：整月周期核分块探针未采用

对 64 条历史分块，仅改变遍历顺序；每个输出格子的累加顺序及 cos/exp 公式不变。主机 14 组输入（0/1/63/64/65/129/1000 条、常规/大相位、120 步）输出逐字节一致，未据此推断全输入证明或 ARM 输出一致性。Pixel 6a 86400 条合成历史、16 状态、120 步、300 次模拟，两种顺序下基线 127.910/128.214 ms，探针 130.292/137.150 ms；无收益，不采用，生产核心未修改。各测量质量守恒，独立子进程 RSS 4.16–4.32 MB，均未达 15 ms；不含相位准备、SQLite、ART 或完整应用内存。当前基线较历史 83 ms 更慢，未归因于未经测量的温度或频率变化。证据及可复现源码差异：`artifacts/native_android_v1/periodic_block_probe_pixel.json`、`periodic_block_probe.patch`。下一步需改变计算结构或引入明确误差合同，单纯重排循环不足以解决量级差距。

## 2026-10-01：相位共享计算探针及 SDK 相位基准

保留 --monthly-history 原基准，新增 --canonical-monthly-history：同为 86400 条、30 秒间隔，但按 SDK 的 weekday + local-time fraction 生成相位，保持浮点相位本身不取整、不分箱。报告新增 phase_construction 字段。完全相等相位可共享核权重，临时探针排序索引并按相位分组，排序计入时间；累加顺序变化，需要数值容差检查。主机 16 组重复相位输入（0–10000 条、正常/大相位）最大 CDF 差异 2.831e-15，不能作为全部输入或 ARM 数值证明。

Pixel SDK 构造基准：134.162→62.296 ms，RSS 4.19→5.04 MB；旧构造基准：133.976→120.810 ms，RSS 4.21→5.03 MB。各次质量守恒。探针有条件收益，但仍超过 15 ms 和十进制 5 MB 门槛，尚未进入生产核心；两个基准都保留，避免用改变输入构造掩盖限制。下一步应在历史拟合阶段维护重复相位统计，并单独报告拟合、增量更新、预测及整体成本。证据：`artifacts/native_android_v1/periodic_phase_group_probe_pixel.json`、对应 `.patch`。没有替换真机应用，没有读取真实轨迹。

## 2026-10-01：周期谱拟合快照原型

新增 `traceform/forecasting/periodic_spectrum.py`，尚未选择为生产预测路径。使用 NIST DLMF 10.35.2 的 modified-Bessel Fourier 展开（https://dlmf.nist.gov/10.35.E2），结合日相位等于 7 倍周相位模 2π，将 kappa=2 双周期核展开成 0–144 频率。每个转移边保存正余弦矩统计，预测不再遍历历史。Bessel 系数按 DLMF 10.25.2 正项级数计算（https://dlmf.nist.gov/10.25.E2）。只接收共享本地日历相位，拒绝非法计数/先验；复制先验和时区，不因原 kernel 后续变化改变快照。

截断阶数 M=18。由 I_n(2) ≤ exp(1/(n+1))/n!，尾和用首项及后续比率 ≤1/(M+2) 的几何级数界，单因子尾界 ε=2 exp(-2) exp(1/(M+2))/(M+1)! × (M+2)/(M+1)，双因子权重截断界 2ε+ε²=4.925e-18。N 条历史的归一化行 L1 截断界 ≤2Nδ/(prior_mass+N exp(-8))。这些仅为精确算术截断界，不包括浮点系数计算、矩累加、三角约简、本地日历相位一致性误差或归一化误差，不能作为完整数值认证。

主机整月 86400 条、16 状态、120 步：拟合 1158.718 ms、预测 486.120 ms，抽样行最大 L1 差异 7.841e-16；256 条边 float64 矩载荷 593920 字节，不是 Python/Android 整体内存。307 项测试通过（24.159 秒），包括 UTC/Kathmandu/New York/Lord Howe、分段、周边界、低权重尾部、空历史和无效输入。证据：`artifacts/native_android_v1/periodic_spectrum_host.json`。尚未支持增量增删、原生部署或通过 15 ms 门槛；失效/模型/时区/历史变更需重新拟合。下一步将矩更新移到原生路径并单列拟合及更新耗时。

## 2026-10-01：原生谱矩增删与整月真机预测

新增独立 C++ `PeriodicSpectrum`（1–16 状态、145 频率）、单次合法记录 ±1 矩更新、CDF 及 `native/periodic_spectrum_benchmark.cpp`。仍未接入生产 SDK。删除记录真实性/资格和模型绑定由权威历史窗口负责；摘要只能检查状态、相位、计数非负和最大容量，不能证明指定相位确实曾加入。每条边删除至零清空矩以避免空历史残差；长期非空矩增删的漂移/重拟合周期尚待验证。

主机先追加 2000 条、删除半数、全部删除，逐阶段与精确 CDF 对比 ≤1e-12。308 项测试通过（24.772 秒）。Pixel 6a 整月 86400 条、16 状态、120 步、300 模拟：首次拟合 208.099/242.656 ms（不在预测计时内），1000 组删除再追加 4.271/4.237 ms；预测包含 128D 归一化、量化、CDF 和模拟，为 4.339/4.334 ms。独立子进程 RSS 4952064/5001216 字节，后者超十进制 5 MB，内存验收尚未通过；摘要载荷 596104 字节。不包括 SQLite/ART，预测计时不包括相位准备或首次拟合。

独立 ARM 诊断二进制比较完整整月 CDF：最大差 7.772e-16，相同随机数的 300 模拟占用计数完全相同；诊断额外参考数组使 RSS 5627904 字节，不能替代生产内存测试。证据 `artifacts/native_android_v1/periodic_spectrum_pixel.json` 及 `periodic_spectrum_parity.patch`。编译基准：NDK clang++ -std=c++17 -O3 -ffp-contract=off -static-libstdc++ -I native native/periodic_spectrum.cpp native/traceform_core.cpp native/periodic_spectrum_benchmark.cpp -o BENCH，运行 `BENCH --canonical-monthly-history`。探针已部署 USB 真机，应用未替换。下一步以流式历史拟合降低峰值内存，再接入有模型/时区/窗口绑定和重拟合约束的 SDK 路径。

## 2026-10-01：原生逐条拟合通过独立核心内存与预测门槛

`native/periodic_spectrum_benchmark.cpp` 新增 --streamed-monthly-history，不分配整月 observed/source/target 数组，而将同样的 86400 条合法相位/转移逐条加入摘要。保留 --canonical-monthly-history 对照；相位生成、先验准备、摘要构造及拟合纳入 fit timer，预测仍单独计时。新增诊断 CDF 指纹（非安全摘要），主机两路径指纹相等且质量守恒。309 项测试通过（26.028 秒）。

Pixel 6a 三次流式：预测 4.356/4.327/4.340 ms，RSS 2969600/3018752/3006464 字节，均小于 15 ms 和十进制 5 MB；拟合 204.897/262.812/204.648 ms，1000 组删除再追加 4.209/4.200/4.191 ms。对照保留数组预测 4.503 ms、RSS 4874240 字节；四次 CDF 诊断指纹均 76d14fdf2b7282d4。指纹相等为诊断证据，不声称哈希无碰撞或全输入等价；完整整月 ARM 误差比较沿用上一节独立参考测试。证据 `artifacts/native_android_v1/periodic_spectrum_streamed_pixel.json` 包含各次回执及源/二进制哈希。

通过范围仅独立子进程的已拟合谱核心、128D 归一化、量化、120 步 CDF 及 300 次模拟。初次拟合/增量更新不在预测计时内，但已分别报告；不包含 SQLite、ART、Binder 和真实窗口管理。完整 SDK 尚未选择谱路径；模型/时区绑定、权威过期删除及长期累计漂移重拟合仍待接入验收。探针已部署真机，应用未替换。运行新模式：BENCH --streamed-monthly-history。

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

## 2026-10-01：ARM 双精度向量规约未采用

临时探针使用两条 double lane 累加谱矩点积，保持 vmul+vadd、不使用融合乘加；规约顺序发生变化。ARM 追加 2000 条/删除半数/清空及非法输入测试退出 0；整月精确 CDF 最大差 8.882e-16、共享随机数 300 次占用计数一致。指纹与标量不同，未将容差一致表述为逐位一致。

CPU 0–3 两种顺序复测：标量预测 31.364/31.482 ms，向量规约 32.361/32.233 ms；无收益，不采用，生产谱实现未改。独立默认环境参考诊断预测 3.264 ms，不作为受限环境通过证据；该诊断还包含额外精确参考数组。各次质量守恒，核心门槛在受限 CPU 条件仍未达标。证据 `artifacts/native_android_v1/periodic_spectrum_neon_probe_pixel.json`、对应 `.patch`；探针已运行真机，未替换应用。下一步检查跨预测步复用矩载入的计算结构，不能用默认 CPU 成绩代替应用范围。

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

## 2026-10-02：连续积分时间边界与非恒定漂移收敛

白噪声 rollout 原先接受 NaN 时长（返回未演化轨迹）与无限步长。新增回归先观察两项失败，再将时间参数验证与分数分支已有的有限正数规则一致；NaN/无限时长与步长均拒绝。新增两锚点 Gaussian 漂移的解析对照：dx/dt=-tanh(x)，初值 1，时长 2，解析终值 asinh(sinh(1)*exp(-2))。步长 0.25/0.125/0.0625/0.03125 的绝对误差为 0.032476/0.015859/0.007838/0.003897，逐次误差比例位于 1.8–2.2。这证明该非恒定零扩散夹具的一阶积分收敛；不证明随机强收敛、经验势场正确、128D 真实人体预测或分数记忆。

312 项本地测试通过（25.273 秒）；证据 `artifacts/forecasting_v2/continuous_time_validation.json` 绑定来源与解析误差。此次 Python 修复尚未安装到设备，运行中的进程未重启。真机上层 AGENTS.md 要求源部署前后执行十二项冻结校验；后续部署先核对完整规则并完成校验。既有原生 APK 的部署与上轮测量保持其历史证据范围。整体目标未完成，未提交或推送。

## 2026-10-02：连续时间修复的设备源码部署

完整读取设备上层 AGENTS.md；十二项冻结源摘要脚本部署前后均退出 0 且全部 PASS。初次运行包装器使用旧 PRoot 根目录，校验未启动；通过读取目录布局定位当前 containers/debian/rootfs 后执行成功。未修改校验脚本或冻结文件。独立 Traceform 的 continuous.py 与对应测试各自备份后原子替换、文件及目录 fsync、读回摘要匹配本地。

Termux 原生 Python 上 22 项连续动力学合成测试通过（25.971 秒）。证据 `artifacts/forecasting_v2/continuous_time_validation_pixel.json` 保留新旧源码摘要及前后十二项 PASS。未重启现有运行时，它可能仍持有旧模块；设备测试由新启动解释器使用安装源码。未启动物理采集或执行干预，没有导出原始轨迹。本次证明源部署与合成数值行为，不能充当经验预测、随机强收敛或全阶段验收。整体目标仍未完成，未提交或推送。

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

## 2026-10-02：首次系数构建的频率外循环

局部展开基矩改为按频率连续存储，系数构建每条边先读取一个频率的旋转矩，再更新全部 19 阶；每阶仍按频率 1..144 累加。去除每边临时 even/odd 数组，缓存失效、半径和回退规则保持。CPU 0–3 按旧/新/新/旧顺序：首次预热完整链 8.204/6.861/5.768/8.217 ms，暖完整链 4.002/3.940/3.878/3.971 ms，CDF 指纹一致。313 项测试通过（27.309 秒），APK 已安装。

三轮 APK 首次融合完整链 14.820/15.559/15.932 ms，后两轮超过 15 ms；十五次暖融合链均低于 15 ms（9.785–13.342 ms）。三轮误差上限 8.882e-16，融合/分离占用逐项一致，生命周期和输入保护通过。首次拟合 2.37–2.42 秒，新快照拟合 2.44–2.51 秒单列，历史扫描与相位/随机数准备排除；频率/温度未观测，不归因应用波动。

证据 `artifacts/native_android_v1/periodic_spectrum_coefficient_order_pixel.json` 绑定源码、测试、夹具和 APK，保留全部结果。首次查询门槛仍未通过，历史服务尚未接入融合入口，完整内存和其余阶段仍待完成；未提交或推送。


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


## 2026-10-02：原生每周演化链的源码缺项审计

当前生产 EdgeService 通过 ModelEngine.process 写入 model_sha256，再附 continuous_velocity，process 本身写 continuous_vector。采样持久化调用 ColdTrajectory.append，已登记 bundle 同样含模型摘要，来源字段不是仅由合成夹具提供。此为源码连通性证据，未启动实际采集来证明新部署帧。

原生 manifest 当前仅有 ColorRecoveryJob、VolumeRecoveryJob 两项 JobService；ModelEngine 只从 APK assets 读取模型，没有原生候选注册表/激活入口。源目录不存在原生 weekly job 或校准实现。Python guarded_calibration 与 potential_maintenance 的存在不能证明原生 APK 已摆脱 Termux 的每周演化依赖。证据 artifacts/native_android_v1/native_weekly_gap_audit.json 绑定相关当前源码，明确这些是源码审计而非系统调度观测。

后续主线：原生流式全量审计→拓扑应变及按时间训练/留出评估→固定归一化器的增量候选/网格适配→来源绑定势能重排序→有门槛的原子激活/迁移/回滚→周日当地时间持久调度及幂等周回执。这些缺项需要实现，不能用现有 Termux 任务的成功回执替代。分叉参数族选择仍待答复，原有完整目标保持，未提交或推送。


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

## 2026-10-02 current candidate and convergence evidence

The current complete production APK builds and verifies v2/v3 signing; 334 host tests passed before adding the new convergence case. Current Pixel 6a/API37 monthly synthetic fitted SDK computation meets 15ms (fused five-query range 6.665–7.992ms, first query11.489ms), excluding history scan, phases/draw preparation and fit; independent core RSS2916352B meets5MB only for that process. Evidence: native_current_monthly_compute_android.json and native_current_release_candidate_build.json.

Added a coupled Brownian-path stochastic step refinement test against the exact additive Ornstein–Uhlenbeck solution, including Brownian bridge contributions.256 paths and4/8/16/32 steps show successive RMS error ratios in(1.8,2.3), finest error<0.01. All23 continuous-dynamics tests pass(8.451s), diff check passes. additive_sde_refinement_host.json binds the test source. This is numerical integrator validation for a known1D SDE, not empirical128D drift/diffusion accuracy or fractional-memory identification.

An isolated actual Android JobService now runs the latest complete synthetic weekly fixture, nonce b7964708-7e32-4693-b9b2-f0062350bda9, job34715. Last authoritative state active ready; no completion claim. The production app has not been replaced with the latest candidate, and repository publication remains outstanding.

## 2026-10-02 complete synthetic weekly result and128D moment recovery

The candidate-bound isolated weekly JobService completed with matching nonce b7964708-7e32-4693-b9b2-f0062350bda9, passed=true,741738.030ms. The package was removed; twelve frozen verifiers passed. native_current_weekly_probe_android.json records the bound build, actual receipt and limits. It does not cover the later registry temporary cleanup change or real Sunday calibration.

A new full128D controlled moment test recovers constant drift and all entries of a full-rank non-diagonal covariance using512 signed Hadamard increments at actual .5/1.5s intervals; it checks the noise_covariance=2D convention and PSD factorization. All24 continuous-dynamics tests passed(8.731s). full_128d_covariance_recovery_host.json binds source and scope; controlled second moments do not establish Gaussianity or human empirical accuracy.

### 2026-10-02: uniform 16-node private optimization resource gate

The equal-mass model path now has a separate private uniform-permutation
single-sweep optimizer. The independent four-index integer objective agrees
with all ten recorded-gate and actual separate-party authenticated host cases
at both four and sixteen nodes. The 16-node host runs took 7.80–9.79 seconds;
the full Python suite at this point passed 338 tests in 39.100 seconds.
General-mass 2×3 private optimization retained all eleven passing cases after
the generator branch change. These are synthetic test identities and inputs,
not production enrollment, unrestricted transport optimization or global
optimality. Source-bound receipts are under `artifacts/alignment_v1`.

The attempted Pixel 6a / Mac 16-node execution is **not accepted**. Two
bootstrap problems were reproduced and corrected: background-process stdin
was empty until fd 3 was preserved, and the three-second initial EOF retry
window expired before the large device circuit was ready. The explicit large
circuit retry window is now sixty seconds, bounded by the helper's ninety
second maximum; authentication or post-setup MPC errors remain terminal.
The next attempt entered protocol computation but the device participant
reached 808592 KiB sampled RSS, beyond its 700000 KiB test boundary. The
monitor terminated only that participant. Its timeout, resource limits,
scripts and prior failure attempts are preserved in
`uniform_permutation_16_crossdevice_resource_gate.json`. Participant exit was
confirmed, the dedicated USB forward removed, and the isolated 515 MB test
circuit directory deleted. The installed primary application was unchanged.

Constant folding and dead gate elimination subsequently reduced the circuit
from 15807751 to 14086626 gates. Four-node output checks passed all ten cases;
sixteen-node output and protocol checks for the rewritten circuit are still
pending. This reduction is not yet evidence of practical Android execution.
The latest application source remains uninstalled, and final repository push
remains pending the overall completion gates.

### 2026-10-02: bounded six-bit private search host evidence

Packed uniform commitments use a separate v2 domain and retain complete metric
and uniform-mass validation. The six-bit search still computes and gates the
final score at original precision; it may select a different permutation from
the eight-bit or exact sweep. This is a bounded permutation search, not a global
GW result. Circuit arithmetic narrowing, symmetry-preserving swaps, exact square
construction and Boolean gate rewriting reduce the final circuit to 3790251
gates / 116672749 bytes. All 15 four-node and 14 sixteen-node independent
recorded-gate and actual separate-party authenticated host protocol cases pass.
Host sixteen-node runs take 1.77–1.90s; one separate identical-input measurement
has peak RSS 831766528/941408256 bytes. The full suite passes 349 tests in
35.526s. `lowmemory_packed_permutation_16_private_host.json` binds sources,
rewritten circuit, reference checks, protocol cases and memory measurement.

An initial sandbox-restricted host invocation stalled at local connection
setup and was terminated; it is not acceptance. The permitted local-communication
rerun supplies the accepted protocol evidence. A separate guarded Pixel/Mac
run is in progress and requires its own completed receipt. Neither host
acceptance nor standalone Pixel execution bypasses the SDK's current 64 MiB
snapshot bound or adds v2-approved proof/owner integration. The primary
application remains unchanged; latest-source installation and push are pending.


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

### 2026-10-02: fixed-circuit SDK resource admission

`UniformPrivateAlignment.run` adds an Android entry pinned to the accepted
six-bit uniform sixteen-node circuit. It requires 6224 own-input bits, a full
expected public statement, 960 MiB available memory with no low-memory signal,
and 128 MiB available snapshot space. Its 128 MiB immutable snapshot retains
trusted SHA verification. A running resource callback checks the 192 MiB
system reserve every 250ms; rejection reaps the native child and deletes the
snapshot. The existing public entry still limits snapshots to 64 MiB. Host
regressions cover those independent limits, wrong fixed circuit, input shape,
Android admission conditions via narrow platform substitutes and child cleanup
when resources are withdrawn. All 351 tests pass in 39.057s.

The isolated actual Android JobService terminated with a matching nonce and
`private alignment memory admission rejected` before native computation.
Later `/proc/meminfo` reported 980388 KiB available; this later sample is not an
exact admission-time measurement. The signed build, terminal receipt, host
checks and peer EOF result are recorded in
`uniform_private_sdk16_admission_android.json`. The peer terminated, dedicated
forward was removed, the separate package uninstalled and only created external
test files deleted. The primary application was unchanged. The fixture now
records admission-time memory on its next build. SDK execution remains
unaccepted; resource rejection is not protocol success. Unknown-score approval,
packed-v2 proof integration, real-owner enrollment and final deployment/push
remain outstanding.

### 2026-10-02: first application SDK sixteen-node execution

The diagnostic 960 MiB admission run terminated before computation at
987168768 available bytes, lowMemory=false; the matching failure and source
build are preserved in uniform_private_sdk16_diagnostic_admission_android.json.
The fixed-circuit initial memory estimate was then derived explicitly from the
fourteen accepted standalone Pixel cases: peak sampled628152 KiB *1.20,
rounded upward to737 MiB, plus192 MiB reserve =929 MiB. The 928/929 MiB
boundary has a failing-before/passing-after host regression. Runtime reserve,
snapshot128 MiB quota, fixed digest and exact statement checks remain intact.
This measurement-derived estimate does not certify a worst-case bound.

The new matching nonce4f8fa872-7b99-461a-94db-2a8f3548f1d6 completed the
identical-input sixteen-node protocol inside the isolated application
JobService. Entry memory1001312256 bytes, lowMemory=false, elapsed31332.142ms.
Android and Mac outputs match the independent full-precision reference; the
Mac participant exited0. The job became terminal and app-private snapshot was
absent afterward. uniform_private_sdk16_pixel.json binds the signed build,
circuit, public outputs, peer execution and source digests. The isolated app,
dedicated forward and only created external copies were removed. All351 host
tests pass in38.166s. Full SDK case coverage, unknown-score approval and
packed-v2 proof/owner integration remain open; primary deployment and final
push are not complete.
