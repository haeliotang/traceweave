# Continuous feature provenance

The [128-output source inventory](../artifacts/representation_v1/continuous_feature_source_inventory.json)
binds the normalizer source digest and identifies each output expression,
mapping line and assignments to its producer variable. It is a static code
inventory, not complete transitive dataflow or runtime branch evidence.

V2 acquisition records adapter status and field evidence for hardware sensors,
device state, connectivity and audio. Missing gyroscope zeros, audio-route
defaults, failed versus empty Wi-Fi scans and empty PCM defaults are distinct
from reported inputs. These labels survive rolling persistence and edge-frame
generation. They do not establish that all normalized dimensions were measured.

Several normalizer branches require particular care:

- GNSS visible-satellite density, C/N0 and PDOP have fixed fallbacks conditional
  on latitude presence. Latitude alone does not measure these quantities.
- Dual-microphone cross-correlation has a fallback using single-channel spectral
  entropy and silence summaries. This does not establish a dual-microphone measurement.
- Spectral warmth can fall back to time phase and light level, and ultrasonic
  micro-Doppler can fall back to touch and silence summaries.
- Digital interaction metrics include proxy estimates and defaults; modality
  availability is insufficient to certify each field.

Completion requires recording which branch supplied each normalized dimension,
including dependency evidence, missing-input defaults, history warm-up and
proxy limitations, and validating the corresponding acquisition capability.
Existing trajectories must retain their original evidence rather than receive
retrospectively invented source labels. The current-source candidate and final
deployment must also bind this behavior explicitly.

The four GNSS alias lookups now fall back only when the primary value is `None`.
Reported zero values retain priority; PDOP still uses its existing lower bound.
This corrects an `or` lookup that could replace an explicit zero with an alias
or proxy. The source inventory binds the corrected implementation. This is a
normalization semantic change for affected inputs: prior empirical reports and
installed models are not automatically revalidated by the code correction.

New parameter packages explicitly bind `feature_extraction_version=2`, which
preserves reported GNSS zero values. Legacy packages without this field retain
version 1 extraction and their original serialized digest. Loading old parameters
does not silently activate corrected semantics. Unsupported versions are rejected;
activation of a newly fitted version-2 package requires its own model acceptance.

The [version-bound native candidate build](../artifacts/alignment_v1/version_bound_native_candidate_build.json)
rebuilds the native core and packages the existing version-1 normalization assets,
private participant and public verifier. APK signature schemes v2/v3 pass. The
Python source digest in that receipt identifies build-time parameter binding;
Python extraction is not packaged inside the APK. This candidate does not activate
a version-2 model or establish complete sensorium parity, and the primary device
application has not been updated by this build.

## Dataflow and runtime evidence for the 128 outputs

`scripts/audit_feature_dataflow.py` analyzes `extract_raw_features` with an AST
may-dependence pass and writes
[`continuous_feature_dataflow.json`](../artifacts/representation_v1/continuous_feature_dataflow.json).
For each output it lists the observation fields whose values can reach it, the
previous-frame state it reads and the fields that only select between
alternatives. It supersedes the single-assignment view of the source inventory
but remains a static over-approximation: it does not say which branch ran, and a
field named there is not thereby measured. Outputs that take a constant chosen by
a test alone are labelled `condition_selected`; whether the tested field is the
quantity itself or a stand-in is only hinted by `condition_field_named_in_output`.

`scripts/audit_feature_runtime_evidence.py` reads one SQLite hot-tier snapshot
read-only and reports, per observation field, how often it was present and
whether its value ever changed, and per dimension the output spread, the share of
frames pinned at the ±10 robust-scaling bound, and the eigen-spectrum of the
stored vectors. `scripts/audit_digital_context_proxies.py` tests whether the
`digital_context` fields equal the probe's fixed formulas. `scripts/
build_feature_provenance_ledger.py` joins these with the native source index into
a per-dimension tier and native-port action. All of them emit aggregates only;
results from a device database are device-derived data and stay local until their
publication is separately authorized
(`artifacts/forecasting_v2/device_128d_feature_*.json`,
`device_128d_digital_context_proxy_identities.json`).

Findings that follow from the code alone:

- `digital_context` is not a sensor channel. The edge daemon fills it from
  `dumpsys` text and fixed formulas in `traceform/probes/digital_sensorium.py`:
  touch velocity and jitter are functions of application-switch and unlock
  counts and screen state; the BLE density falls back to `wifi_ap_count / 3`
  whenever the Bluetooth dump yields nothing; BLE variance is `1.6 x` that
  density and RF entropy is `0.85 x log2(wifi_ap_count + 1)`. These dimensions
  cannot carry information beyond those few inputs even when they vary.
- The frozen parameters bound every scaled value to [-10, 10]. In
  `normalizer_v1.json` the pressure median is 913.1 hPa with an IQR of 0.1 hPa,
  so any pressure more than about 1 hPa from the median pins that dimension at
  the bound regardless of how the input varies. A dimension whose stored values
  stop following its input is a normalization defect, separate from provenance.
- Acquisition v2 supplies no direct fields for most of the "override" inputs the
  normalizer looks up first (for example touch contact geometry, GNSS counts,
  cellular quality, CPU mode ratios). For those outputs the fallback formulas
  are what run.

Each ledger tier is an input-evidence statement: `supplied_inputs_vary`,
`proxy_from_supplied_inputs` or `no_informative_input`. None of them asserts that
the named physical quantity was measured, and one window of one device does not
show behavior across days or subjects. The tooling does not change the
normalizer, the installed model or any stored trajectory.

## Observability registry, normalizer evaluation and native parity ports

**Observability registry.** `scripts/build_feature_observability_registry.py` marks each of the 128
dimensions `observed_inputs`, `proxy_from_observed_inputs`, `unobservable_in_current_acquisition` or
`not_assessed_at_runtime`, and records the basis. A dimension is statically unobservable when no
producer code in `pixel_physical`, `traceform/streaming` or `traceform/probes` contains any of its
input field names as a string literal; that is a fact about the code, not about one device
(`artifacts/representation_v1/feature_observability_static.json`). Runtime-based statuses need a
device-derived ledger and stay local. `validate_continuous_holdout.py --observability-registry`
restricts the drift-versus-persistence comparison to chosen dimensions.

**Normalizer candidate.** `MultimodalNormalizer.fit_robust_window` scales by
`max(IQR, (q95 - q05) / 2.44)` and gives the clock phases fixed analytic scales. It exists because
the deployed parameters were fitted on 1,200 frames and pin dimensions at the +/-10 bound once the
inputs drift. `scripts/evaluate_normalizer_candidate.py` replays stored observations (including
the daemon's gap rule and its restarts), scales them under both parameter sets and applies four
checks that are declared in the script before results are seen. It also reports where replay
cannot equal the live state: the live normalizer held history before the first stored frame, so
long-memory dimensions (hours of residence, the geo window) are not judged before the first
restart. Two refinements of the faithfulness check were added after device runs, each with direct
evidence of the mechanism; the 99% level was never changed. `scripts/build_normalizer_candidate_bundle.py`
registers a manifold fitted on an evaluated candidate and activates it only with `--activate`, only
after every check and manifold gate passes, and only for the exact baseline and candidate that were
evaluated. A candidate that fails the forecast check is neither registered nor activated, and the
drift-versus-persistence ratio changed sign between two windows, so one window does not settle it.

**Native parity ports.** `ReferenceUsageFeatures`, `ReferenceSignalFeatures`,
`ReferenceDynamicsFeatures` and `ReferenceDigitalProbe` are statement-by-statement ports of 51
extractor outputs and of the digital-context formula layer. `tests/parity_support.py` feeds the
same deterministic sequences (absent fields, direct overrides, odd timestamps, gaps, resets,
hostile values) to Python and Java and compares frame by frame; mutation checks confirm the
tests fail when the ports are altered. `ReferenceFeatureSupplier` builds the observation from native
sources and records the results as `derived` or `proxy` readings
(`ReferenceFeatureKinds`, generated by `scripts/generate_reference_feature_kinds.py`).
`tests/test_native_reference_supply.py` replays native-shaped samples through the supplier and
recomputes every value in Python from the observation Java handed to the extractor. The ports are
exact only given identical base inputs; 12 outputs need inputs the service cannot obtain without
new permissions (Wi-Fi scan results, cell identity, location) and stay missing, recorded with the
reason `unobservable_no_producer_in_acquisition` or `not_supplied_this_sample`. The ports add no
information: they are formulas over a few base inputs.
