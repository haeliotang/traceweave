import hashlib
import struct
import unittest

from native.private_alignment_probe.verify_uniform_permutation import encode, fixtures, packed_uniform_message
from traceform.dynamics.alignment_statement import SCALE


class TestPrivatePackedCommitment(unittest.TestCase):
    def test_twenty_bit_upper_triangle_has_explicit_shape_mass_and_context(self):
        session, nonce = bytes(range(32)), bytes([11] * 32)
        message = packed_uniform_message(0, [[0, SCALE], [SCALE, 0]], session, nonce)
        expected = (b'traceform.gw.uniform-packed.v2\0' + bytes([0, 2])
                    + struct.pack('<II', SCALE, SCALE // 2) + session + nonce
                    + SCALE.to_bytes(3, 'little'))
        self.assertEqual(message, expected)

    def test_public_binding_matches_independent_hash_and_changed_nonce(self):
        witness = fixtures(4)[0][1]
        _, output, valid, _ = encode(witness, 'identical', quantized=True, packed=True)
        self.assertTrue(valid)
        session = bytes(range(32))
        expected = b''
        for owner, field, nonce in [(0, 'first', 11), (1, 'second', 13)]:
            expected += hashlib.sha256(packed_uniform_message(owner, witness[field], session, bytes([nonce] * 32))).digest()
        actual = bytes(sum(int(output[337 + byte * 8 + bit]) << bit for bit in range(8)) for byte in range(64))
        self.assertEqual(actual, expected)
        _, changed, _, _ = encode(witness, 'changed_nonce', quantized=True, packed=True)
        self.assertNotEqual(changed[593:], output[593:])

    def test_omitted_half_and_masses_are_still_validated_before_public_acceptance(self):
        for label, witness in fixtures(4):
            if label in ('asymmetric', 'nonuniform_same_total', 'nonzero_diagonal', 'out_of_range_distance'):
                with self.subTest(label=label):
                    _, output, valid, _ = encode(witness, label, quantized=True, packed=True)
                    self.assertFalse(valid)
                    self.assertEqual(output, '0' * 849)
