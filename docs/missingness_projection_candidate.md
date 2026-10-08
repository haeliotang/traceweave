# Offline missingness and thermal projection candidate v1

This candidate changes five coordinates of a stored, bound 128D frame. The other
123 coordinates are copied exactly. It is not a production normalizer, an
accepted manifold, a scientific result or a registered model. The existing
normalizer and frozen forecast source remain unchanged.

GNSS satellite count, CN0 and PDOP require a finite, in-range direct field or its
existing alias. A reported zero is valid for count/CN0 and takes precedence over
an alias. A boolean, nonfinite value or out-of-range primary field is missing,
not a reason to substitute a favorable alias. Latitude presence does not supply
these measurements. Missing GNSS coordinates are neutral zero in normalized
space, with separate false validity flags; those zeros are not observations.
Valid values retain the baseline normalization. Availability transitions can
still change a valid-to-missing coordinate, so downstream fitting/dynamics must
use the validity flags rather than count that transition as measured dynamics.

Temperature derivatives use finite observed device temperature, timezone-aware
timestamps, a continuous predecessor and intervals between 5 and 300 seconds.
Missing temperature or a discontinuity resets derivative history. The first
valid pair supplies a rate; acceleration requires a preceding valid rate.
There is no screen/charging bias subtraction. The rate is clipped at +/-10 deg C
per hour and divided by 10; rate acceleration is clipped at +/-2 (deg C per hour)
per second and divided by 2. Each coordinate receives weight 1/sqrt(2), so their
combined squared coordinate magnitude cannot exceed one. These ranges reuse the
existing extractor's bounds; they are engineering policy, not empirically
validated thermal or behavioral thresholds. Other thermal/proxy features remain
unchanged and may still duplicate information.

The protocol document includes the complete baseline parameters and policy.
Its digest defines a new coordinate binding, distinct from the baseline.
It cannot be passed directly to the existing normalizer/bundle activation flow.
Any later state comparison requires a newly fitted candidate manifold and
independent annotated data, including stable states and real transitions.
Lower step energy alone cannot justify acceptance.

Run on device-private source files, writing outputs outside the frozen directory:

```sh
python3 scripts/evaluate_missingness_projection.py \
  --sqlite-file PRIVATE_EDGE.sqlite \
  --baseline-bundle-file PRIVATE_BASELINE_BUNDLE.json \
  --diagnostic-window-file PRIVATE_WINDOW.json \
  --frozen-plan-file PRIVATE_FROZEN/plan.json \
  --candidate-file PRIVATE_CANDIDATE.json \
  --output-file PRIVATE_RECEIPT.json
```

The window file supplies `window_start_utc` and `window_end_utc`. The evaluator
requires the whole interval to precede the frozen holdout and verifies every
stored frame's normalizer binding. It does not fit any parameters, access future
holdout rows, compare tokens under the old manifold, score forecasts or modify
the registry. Raw rows and candidate parameters stay private; aggregate receipts
can document engineering behavior without exporting trajectories.
