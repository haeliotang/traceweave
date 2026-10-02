# Ordered warm observations

The warm layer retains the latest actual observation in each UTC 30-second
bucket for 30 days. It stores the observation's timestamp, token, model and
normalizer provenance, source/boot/continuity identity and `energy_distance`.
That distance is the existing quantization metric; it is not an inferred
physical potential. Raw feature vectors remain in the hot layer.

Each bucket records its contributing frame count. Continuity is conservative:
an input discontinuity, model/boot/source change, invalid time gap or missing
bucket prevents a continuous edge. A break within a bucket survives replacement
by its latest observation. Missing buckets are reported, not interpolated.
The existing warm occupancy table remains available as a separate summary.

Upgrade streams retained hot frames into the ordered table once, in timestamp
order. Older occupancy summaries cannot reconstruct order and are not used for
backfill. Writes to hot data, both warm tables, cold counts and the source
watermark share one transaction. Cleanup deletes representatives older than
the 30-day cutoff, retaining equality at the boundary.

`scripts/compact_edge_storage.py --database PATH --export-trajectory PATH`
exports ordered JSONL after cleanup. `--export-warm` continues to export
occupancy summaries. Cleanup-time retention does not guarantee immediate expiry
while the process and maintenance jobs are idle.

Pixel 6a verification on 2026-10-01: 13 native storage tests and 231 host tests
pass. After migration and reader restart, 202 ordered buckets match the hot
watermark at 04:59:34.981997Z; 48 discontinuous rows and 40 rows following missing
buckets are preserved. Both deployed source digests match local files. Actual
retained history starts at 02:41:19.636853Z; the 30-day policy has synthetic
boundary tests, not 30 days of observed device history. The receipt is
`artifacts/native_android_v1/warm_trajectory_receipt.json`.
