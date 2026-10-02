#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="/data/data/com.termux/files/usr/bin:$PATH"
exec python3 "$TASK_ROOT/scripts/physical_supervision_v2.py" tick
