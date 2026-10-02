import unittest

from traceform.dynamics.alignment_statement import SCALE
from traceform.dynamics.private_alignment_output import verify_private_alignment_output


class TestPrivateAlignmentOutput(unittest.TestCase):
    def setUp(self):
        self.binding = bytes(range(96))
        self.numerator = SCALE ** 4
        self.bits = '1' + format(self.numerator, '080b')[::-1]
        self.bits += ''.join(format(value, '08b')[::-1] for value in self.binding)
        self.statement = self.numerator.to_bytes(32, 'little')
        for offset in range(0, 96, 32):
            value = self.binding[offset:offset + 32]
            self.statement += value[:31] + b'\0' + value[31:] + b'\0' * 31

    def test_matches_full_unsigned_objective_and_ordered_bindings(self):
        report = verify_private_alignment_output(self.bits, self.statement)
        self.assertEqual(report['objective_numerator'], SCALE ** 4)
        self.assertEqual(report['session_hex'], self.binding[:32].hex())
        self.assertEqual(report['commitments_hex'], [self.binding[32:64].hex(), self.binding[64:].hex()])

    def test_rejects_changed_objective_session_or_either_owner(self):
        for offset in (1, 81, 337, 593):
            with self.subTest(offset=offset):
                changed = self.bits[:offset] + str(1 - int(self.bits[offset])) + self.bits[offset + 1:]
                with self.assertRaises(ValueError):
                    verify_private_alignment_output(changed, self.statement)
        swapped = self.statement[:96] + self.statement[160:] + self.statement[96:160]
        with self.assertRaises(ValueError):
            verify_private_alignment_output(self.bits, swapped)

    def test_rejects_noncanonical_statement_padding(self):
        for offset in (10, 63, 65, 127, 129, 191, 193):
            with self.subTest(offset=offset):
                changed = bytearray(self.statement)
                changed[offset] = 1
                with self.assertRaises(ValueError):
                    verify_private_alignment_output(self.bits, bytes(changed))

    def test_rejects_invalid_output_and_objective_bound(self):
        for bits in (None, self.bits[:-1], self.bits + '0', 'x' + self.bits[1:],
                     '0' + self.bits[1:], '1' + format(SCALE ** 4 + 1, '080b')[::-1] + self.bits[81:]):
            with self.assertRaises(ValueError):
                verify_private_alignment_output(bits, self.statement)
        for statement in (None, bytearray(self.statement), self.statement[:-1], self.statement + b'\0'):
            with self.assertRaises(ValueError):
                verify_private_alignment_output(self.bits, statement)
