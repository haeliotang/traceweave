#!/data/data/com.termux/files/usr/bin/bash
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

# Start Traceform Complete Field Tester Runtime on Pixel 6a
# 1. Runs Edge Manifold Daemon in background (live real-time quantization + haptics)
# 2. Generates local field telemetry without remote synchronization

set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export HOME="/data/data/com.termux/files/home"
export PATH="/data/data/com.termux/files/usr/bin:$PATH"

mkdir -p "$DIR/data"

echo "============================================================================"
echo " Starting Traceform Field Tester Service (Pixel 6a)"
echo " Repository: git@github.com:haeliotang/traceform.git"
echo "============================================================================"

# Ensure edge daemon is running in silent mode
daemon_query_status=0
# Liveness comes from the daemon's own flock, not the process table: a daemon
# started under another SELinux domain (adb run-as) is invisible to pgrep here.
python3 "$DIR/scripts/daemon_lock_probe.py" "$DIR/data/edge_manifold_daemon.lock" || daemon_query_status=$?
if [ "$daemon_query_status" -gt 1 ]; then
    echo "[!] Unable to determine edge daemon state; startup aborted." >&2
    exit "$daemon_query_status"
fi
if [ "$daemon_query_status" -eq 1 ]; then
    # Relaunch through edge_runtime.sh so the daemon gets the same environment (time zone data),
    # storage mode and arguments as a planned start. Interventions stay off unless the authorization
    # record explicitly asks for active relaunch and the stop switch is absent.
    launch_mode="$(python3 "$DIR/scripts/runtime_launch_mode.py" "$DIR/data")" || launch_mode=silent
    case "$launch_mode" in
        "active "*)
            export TRACEFORM_INTERVENTION_MODE=active
            export TRACEFORM_INTERVENTION_ACTIONS="${launch_mode#active }"
            echo "[*] Relaunching edge daemon in authorized active mode (${TRACEFORM_INTERVENTION_ACTIONS})..."
            ;;
        *)
            # A new unattended launch defaults to silent; preserve an existing runtime.
            touch "$DIR/data/.silent"
            echo "[*] Launching edge daemon (Silent Mode) in background..."
            ;;
    esac
    nohup bash "$DIR/scripts/edge_runtime.sh" > "$DIR/data/daemon.log" 2>&1 &
    echo "[+] Edge daemon started with PID $!."
else
    echo "[*] Edge daemon is already running."
fi

# Generate local field telemetry once on start
echo "[*] Generating local field telemetry..."
python3 "$DIR/scripts/phone_field_sync.py" || true

echo "============================================================================"
echo " [✓] Field Tester Service Ready."
echo "============================================================================"
