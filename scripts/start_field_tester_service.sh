#!/usr/bin/env bash
# Start Traceform Complete Field Tester Runtime on Pixel 6a
# 1. Runs Edge Manifold Daemon in background (live real-time quantization + haptics)
# 2. Runs Field Sync Engine periodically (daily OTA sync to private GitHub repo)

set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export HOME="/data/data/com.termux/files/home"
export PATH="/data/data/com.termux/files/usr/bin:$PATH"

mkdir -p "$DIR/data"

echo "============================================================================"
echo " Starting Traceform Field Tester Service (Pixel 6a)"
echo " Repository: git@github.com:haeliotang/traceform.git"
echo "============================================================================"

# Ensure edge daemon is running
if ! pgrep -f "phone_edge_manifold_daemon.py" > /dev/null; then
    echo "[*] Launching phone_edge_manifold_daemon.py in background..."
    nohup python3 -u "$DIR/scripts/phone_edge_manifold_daemon.py" \
        --follow \
        --raw-ledger "$HOME/.pixel_sensorium/physical_observations.jsonl" \
        --normalizer-file "$DIR/artifacts/representation_v1/representation_spec.json" \
        --model-file "$DIR/artifacts/manifold_dynamics_v1/manifold_lattice_model.json" \
        --output-stream "$DIR/data/realtime_manifold_stream.jsonl" \
        --events-file "$DIR/data/manifold_events.jsonl" > "$DIR/data/daemon.log" 2>&1 &
    echo "[+] Edge daemon started with PID $!."
else
    echo "[*] Edge daemon is already running."
fi

# Run field sync once on start
echo "[*] Triggering initial OTA telemetry sync to GitHub..."
python3 "$DIR/scripts/phone_field_sync.py" --push || true

echo "============================================================================"
echo " [✓] Field Tester Service Ready."
echo "============================================================================"
