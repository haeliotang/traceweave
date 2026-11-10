#!/data/data/com.termux/files/usr/bin/bash
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

set -euo pipefail

TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="/data/data/com.termux/files/usr/bin:$PATH"
export PYTHONTZPATH="/data/data/com.termux/files/usr/share/zoneinfo"
DEVICE_TIMEZONE="$(getprop persist.sys.timezone)"

# Interventions stay off (shadow, haptics disabled) unless the caller explicitly selects active.
# scripts/start_field_tester_service.sh selects it from the authorization record; see
# scripts/runtime_launch_mode.py.
MODE_ARGUMENTS=(--disable-haptics --intervention-mode shadow)
if [ "${TRACEFORM_INTERVENTION_MODE:-shadow}" = "active" ]; then
    read -r -a ACTIONS <<< "${TRACEFORM_INTERVENTION_ACTIONS:-pulse notification}"
    MODE_ARGUMENTS=(--intervention-mode active --intervention-actions "${ACTIONS[@]}" --csd-input-space continuous)
fi
exec python3 -u "$TASK_ROOT/scripts/phone_edge_manifold_daemon.py" \
    --follow "${MODE_ARGUMENTS[@]}" --storage-mode tiered \
    --timezone "${TRACEFORM_TIMEZONE:-${DEVICE_TIMEZONE:-UTC}}" \
    --raw-ledger /data/data/com.termux/files/home/.pixel_sensorium/physical_observations.jsonl \
    --normalizer-file "$TASK_ROOT/artifacts/representation_v1/representation_spec.json" \
    --model-file "$TASK_ROOT/artifacts/manifold_dynamics_v1/manifold_lattice_model.json" \
    --output-stream "$TASK_ROOT/data/realtime_manifold_stream.jsonl" \
    --events-file "$TASK_ROOT/data/manifold_events.jsonl"
