# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import tempfile
from pathlib import Path
import unittest

from native.private_alignment_probe.verify_uniform_permutation import encode, fixtures
from scripts.preflight_private_computation_fixture import approval_bits, verify_fixture, verify_private_inputs
from scripts.verify_uniform_private_sdk_usb import statement_bytes


class TestPrivateComputationFixture(unittest.TestCase):
    def setUp(self):
        witness = next(witness for label, witness in fixtures(16) if label == 'different_scale')
        self.bits, self.output, valid, _ = encode(witness, 'different_scale', quantized=True,
                                                packed=True, search_distance_shift=14)
        self.assertTrue(valid)
        self.bindings = statement_bytes(self.output, True)[32:]

    def test_unknown_score_input_bindings_and_party_order(self):
        own, peer = self.bits[6224:], self.bits[:6224]
        approvals = verify_private_inputs(own, peer, self.bindings)
        self.assertEqual(approvals, self.output[81:])
        self.assertEqual(len(own + approvals), 6992)
        with self.assertRaisesRegex(ValueError, 'commitment'):
            verify_private_inputs(peer, own, self.bindings)

    def test_changed_model_session_and_nonce_rejected_before_dispatch(self):
        own, peer = self.bits[6224:], self.bits[:6224]
        for index in (21, 5712, 5968):
            changed = own[:index] + str(1 - int(own[index])) + own[index + 1:]
            with self.subTest(index=index), self.assertRaises(ValueError):
                verify_private_inputs(changed, peer, self.bindings)
        for invalid in (own[:-1], own + '0', 'x' + own[1:]):
            with self.assertRaisesRegex(ValueError, 'private bits'):
                verify_private_inputs(invalid, peer, self.bindings)

    def test_canonical_approval_and_circuit_pin(self):
        with self.assertRaises(ValueError):
            approval_bits(self.bindings[:-1])
        for index in (31, 33, 63, 95, 127, 159, 191):
            changed = bytearray(self.bindings)
            changed[index] = 1
            with self.subTest(index=index), self.assertRaises(ValueError):
                approval_bits(changed)
        with tempfile.TemporaryDirectory() as directory:
            circuit = Path(directory, 'circuit.txt')
            circuit.write_text('wrong-circuit')
            with self.assertRaisesRegex(ValueError, 'circuit digest mismatch'):
                verify_fixture(directory, circuit)
