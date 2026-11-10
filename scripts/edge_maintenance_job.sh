#!/data/data/com.termux/files/usr/bin/bash
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

set -euo pipefail

TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="/data/data/com.termux/files/usr/bin:$PATH"
export PYTHONTZPATH="/data/data/com.termux/files/usr/share/zoneinfo"
DEVICE_TIMEZONE="$(getprop persist.sys.timezone)"
exec python3 "$TASK_ROOT/scripts/maintain_edge_runtime.py" \
    --database "$TASK_ROOT/data/edge_storage.sqlite" \
    --registry "$TASK_ROOT/data/model_registry" \
    --physical-ledger-dir /data/data/com.termux/files/home/.pixel_sensorium \
    --snapshot-dir /data/data/com.termux/files/home/traceform_archive \
    --timezone "${TRACEFORM_TIMEZONE:-${DEVICE_TIMEZONE:-UTC}}"
