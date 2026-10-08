import unittest
from unittest.mock import patch
import subprocess

from scripts.preflight_private_computation_fixture import verify_private_inputs
from scripts.verify_guarded_private_sdk_usb import prepare_case, synthetic_witness, wait_terminal
from scripts.verify_uniform_private_sdk_usb import statement_bytes


class TestGuardedPrivateSdkCases(unittest.TestCase):
    def test_android_missing_job_exit_23_is_terminal(self):
        def query(command, **options):
            result = subprocess.CompletedProcess(command, 23, b'', b'Could not find job 34716\nunknown(u0a281/jid34716)\n')
            if options.get('check'):
                raise subprocess.CalledProcessError(23, command, result.stdout, result.stderr)
            return result
        with patch('scripts.verify_guarded_private_sdk_usb.subprocess.run', side_effect=query):
            wait_terminal(['adb'], 34716)

    def test_fresh_sessions_and_independent_valid_bindings(self):
        sessions = set()
        for index, label in enumerate(('identical', 'transposition', 'reversal', 'different_scale', 'large_80bit_objective')):
            case = prepare_case(label, index)
            self.assertTrue(case['valid'])
            self.assertNotIn(case['session'], sessions)
            sessions.add(case['session'])
            approvals = verify_private_inputs(case['own'], case['peer'], case['bindings'])
            self.assertEqual(len(approvals), 768)
            self.assertEqual(statement_bytes(case['expected'], True)[32:], case['bindings'])
        self.assertGreater(case['objective'], 2**64)

    def test_deliberate_adversarial_bindings_do_not_pass_honest_preflight(self):
        for index, label in enumerate(('changed_nonce', 'changed_valid_model', 'session_mismatch',
                                       'changed_approval_0', 'changed_approval_1', 'changed_approval_2')):
            with self.subTest(case=label):
                case = prepare_case(label, index)
                self.assertFalse(case['valid'])
                self.assertEqual(case['expected'], '0' * 849)
                with self.assertRaises(ValueError):
                    verify_private_inputs(case['own'], case['peer'], case['bindings'])

    def test_prover_input_requires_actual_discovered_statement(self):
        case = prepare_case('different_scale', 20)
        discovered = statement_bytes(case['expected'], True)
        witness = synthetic_witness(case, discovered)
        self.assertEqual(len(witness), 3400)
        self.assertEqual(witness[-224:], discovered)
        for index in (0, 32, 96, 160):
            changed = bytearray(discovered); changed[index] ^= 1
            with self.assertRaises(ValueError):
                synthetic_witness(case, bytes(changed))


if __name__ == '__main__':
    unittest.main()
