import copy
import unittest

from scripts.verify_cached_lmk_recovery_usb import verified_system_lmk


class TestCachedLmkEvidence(unittest.TestCase):
    def receipt(self):
        return {'passed': True, 'actual_system_lmk': True, 'old_pid': 42, 'prepared_epoch_ms': 100,
                'exit_records': [{'pid': 42, 'reason': 3, 'timestamp_epoch_ms': 200}]}

    def test_only_bound_system_low_memory_reason_can_pass(self):
        receipt = self.receipt()
        self.assertTrue(verified_system_lmk(receipt, True, True, False))
        for field, value in [('reason', 10), ('reason', 9), ('reason', 14), ('pid', 43),
                             ('timestamp_epoch_ms', 99)]:
            changed = copy.deepcopy(receipt)
            changed['exit_records'][0][field] = value
            self.assertFalse(verified_system_lmk(changed, True, True, False))
        for child_absent, normal_passed, controlled in [(False, True, False), (True, False, False), (True, True, True)]:
            self.assertFalse(verified_system_lmk(receipt, child_absent, normal_passed, controlled))
