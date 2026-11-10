#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Decide how an unattended relaunch of the edge daemon should run.

Prints ``silent`` or ``active ACTION...``. Active is chosen only when all of these hold: the stop
switch ``.silent`` is absent, ``active_authorization.json`` is readable, it names mode ``active``,
it lists only known actions, and it carries the explicit restart policy
``relaunch_active_after_runtime_failure``. Anything else (missing, malformed, unknown content)
means silent, the safe default, so a damaged file can never switch interventions on.
"""
import json
import sys
from pathlib import Path

POLICY = 'relaunch_active_after_runtime_failure'
KNOWN_ACTIONS = ('pulse', 'notification', 'volume_duck')


def launch_mode(data_dir: Path) -> str:
    if (data_dir / '.silent').exists():
        return 'silent'
    try:
        authorization = json.loads((data_dir / 'active_authorization.json').read_text())
    except (OSError, ValueError):
        return 'silent'
    if not isinstance(authorization, dict):
        return 'silent'
    actions = authorization.get('actions')
    if (authorization.get('mode') != 'active' or authorization.get('restart_policy') != POLICY
            or not isinstance(actions, list) or not actions
            or any(action not in KNOWN_ACTIONS for action in actions)):
        return 'silent'
    return 'active ' + ' '.join(actions)


if __name__ == '__main__':
    print(launch_mode(Path(sys.argv[1])))
