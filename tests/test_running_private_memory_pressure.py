# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import copy
import unittest

from scripts.verify_running_private_memory_usb import verified_withdrawal


class TestRunningPrivateMemoryPressure(unittest.TestCase):
    def receipt(self):
        return {'passed': True, 'lifecycle': {'passed': True, 'mode': 'pressure',
            'withdrawal_reason': 'private alignment memory reserve exhausted',
            'child_reaped': True, 'snapshot_removed': True, 'replay_rejected': True,
            'pressure_child_reaped': True,
            'memory_samples': [{'allocated_mib': 16, 'available_bytes': 215000000, 'low_memory': True}],
            'withdrawal_memory': {'available_bytes': 215000000, 'low_memory': True}}}

    def test_requires_actual_pressure_and_production_guard(self):
        receipt = self.receipt()
        self.assertTrue(verified_withdrawal(receipt))
        for path, replacement in [(('lifecycle', 'mode'), 'withdraw'),
            (('lifecycle', 'withdrawal_reason'), 'synthetic resource permission withdrawn'),
            (('lifecycle', 'pressure_child_reaped'), False),
            (('lifecycle', 'memory_samples'), []),
            (('lifecycle', 'withdrawal_memory'), None),
            (('lifecycle', 'withdrawal_memory'), {'available_bytes': 500000000, 'low_memory': False})]:
            changed = copy.deepcopy(receipt)
            changed[path[0]][path[1]] = replacement
            self.assertFalse(verified_withdrawal(changed))
