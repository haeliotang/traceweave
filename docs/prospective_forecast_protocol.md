# Prospective forecast protocol v1

Freeze the latest 2,048 complete, bound live-follow frames in one read transaction
before inspecting any test result. Keep the training snapshot, frame digest,
coordinate bindings, code archive and plan on the device. No candidate normalizer
or manifold is selected on this holdout. This is a single-participant,
next-observation evaluation; irregular acquisition does not define fixed-time
hazards or prove causal, fractional-memory or conservative-potential claims.

The holdout starts at the next UTC midnight after freezing and ends four UTC days
later. Require at least 2,300 retained frames on each predeclared UTC day. Missing
coverage or changed coordinate bindings blocks the evaluation. Report the failure;
do not substitute favorable days, reproject new coordinates or retrain on test
records. Production calibration continues independently. If it changes the
coordinate binding during this interval, this experiment is blocked; freezing an
evaluation does not authorize disabling production evolution.

The fixed continuous estimator uses bandwidth 1, 128 coordinates and at most 16
anchors. Its primary diagnostic is paired increment MSE reduction against
persistence; report all interval strata and full residual/predicted covariance
rates separately. Neither proxies nor imputed dimensions constitute independent
physical measurements. Keep missing/proxy provenance in the source audit.

The fixed discrete comparison uses a training-only vocabulary, persistence,
first-order, dwell and daily/weekly periodic models in Asia/Kathmandu. Report
accuracy, log loss, Brier score, 10-bin calibration error and unknown-state
coverage. Exclude cross-segment or greater-than-300-second transition pairs in
both comparisons. Fit no model on the holdout.

Use the existing 1,000-draw, seed-42 day bootstrap and report its descriptive
scope. Continuous blocks use UTC days; the discrete evaluator currently uses
local calendar days, which must be labeled separately. Four days do not certify
independence or provide a general scientific acceptance threshold. Report each
model even if it loses to persistence. No automatic activation follows the
result. Numerical execution checks remain separate from predictive validity.

`scripts/freeze_prospective_forecast.py` writes an exclusive new device-private
folder containing `training.sqlite`, `code.tar.gz` and `plan.json`. Preserve daily
source snapshots through the planned closing time. Evaluate only after that time,
using the archived code, verified training digest, exact declared holdout window
and unchanged bindings. A future run must verify readiness before computing
scores; an unavailable or blocked window is a terminal recorded result for v1.
