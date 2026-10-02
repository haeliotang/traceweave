#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="/data/data/com.termux/files/usr/bin:$PATH"
export PYTHONTZPATH="/data/data/com.termux/files/usr/share/zoneinfo"
DEVICE_TIMEZONE="$(getprop persist.sys.timezone)"
exec python3 -u "$TASK_ROOT/scripts/phone_edge_manifold_daemon.py" \
    --follow --disable-haptics --intervention-mode shadow --storage-mode tiered \
    --timezone "${TRACEFORM_TIMEZONE:-${DEVICE_TIMEZONE:-UTC}}" \
    --raw-ledger /data/data/com.termux/files/home/.pixel_sensorium/physical_observations.jsonl \
    --normalizer-file "$TASK_ROOT/artifacts/representation_v1/representation_spec.json" \
    --model-file "$TASK_ROOT/artifacts/manifold_dynamics_v1/manifold_lattice_model.json" \
    --output-stream "$TASK_ROOT/data/realtime_manifold_stream.jsonl" \
    --events-file "$TASK_ROOT/data/manifold_events.jsonl"
