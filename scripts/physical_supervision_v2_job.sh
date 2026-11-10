#!/data/data/com.termux/files/usr/bin/bash
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="/data/data/com.termux/files/usr/bin:$PATH"
exec python3 "$TASK_ROOT/scripts/physical_supervision_v2.py" tick
