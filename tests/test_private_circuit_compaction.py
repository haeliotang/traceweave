import itertools
from pathlib import Path
import tempfile
import unittest

from native.private_alignment_probe.compact_circuit import compact


def evaluate(path, bits):
    lines = path.read_text().splitlines()
    _, count = map(int, lines[0].split())
    outputs = int(lines[1].split()[-1])
    wires = list(bits) + [0] * (count - len(bits))
    for line in lines[2:]:
        fields = line.split()
        if not fields:
            continue
        inputs = int(fields[0])
        parents = [wires[int(value)] for value in fields[2:2 + inputs]]
        destination = int(fields[2 + inputs])
        wires[destination] = (parents[0] & parents[1] if fields[-1] == 'AND' else
                              parents[0] ^ parents[1] if fields[-1] == 'XOR' else 1 - parents[0])
    return wires[-outputs:]


class TestPrivateCircuitCompaction(unittest.TestCase):
    def test_reuses_commutative_gates_without_collapsing_output_positions(self):
        with tempfile.TemporaryDirectory() as root:
            source, target = Path(root) / 'source.txt', Path(root) / 'target.txt'
            source.write_text('4 6\n1 1 2\n2 1 0 1 2 AND\n2 1 1 0 3 AND\n'
                              '2 1 2 3 4 XOR\n2 1 0 1 5 XOR\n')
            compact(source, target)
            self.assertNotIn('AND', target.read_text())
            for bits in itertools.product((0, 1), repeat=2):
                self.assertEqual(evaluate(source, bits), evaluate(target, bits))

    def test_preserves_constant_outputs_including_one(self):
        with tempfile.TemporaryDirectory() as root:
            source, target = Path(root) / 'source.txt', Path(root) / 'target.txt'
            source.write_text('2 4\n1 1 2\n2 1 0 0 2 XOR\n1 1 2 3 INV\n')
            compact(source, target)
            for bits in itertools.product((0, 1), repeat=2):
                self.assertEqual(evaluate(target, bits), [0, 1])

    def test_preserves_all_inputs_outputs_and_removes_dead_constant_work(self):
        circuit = ('9 11\n1 1 3\n\n2 1 0 0 2 XOR\n1 1 2 3 INV\n'
                   '2 1 0 3 4 AND\n2 1 1 2 5 XOR\n2 1 4 5 6 AND\n'
                   '2 1 0 1 7 XOR\n2 1 4 2 8 XOR\n2 1 5 2 9 XOR\n1 1 6 10 INV\n')
        with tempfile.TemporaryDirectory() as root:
            source, target = Path(root) / 'source.txt', Path(root) / 'target.txt'
            source.write_text(circuit)
            report = compact(source, target)
            self.assertLess(report['output_gates'], report['input_gates'])
            self.assertEqual(target.read_text().splitlines()[1], '1 1 3')
            for bits in itertools.product((0, 1), repeat=2):
                self.assertEqual(evaluate(source, bits), evaluate(target, bits))

    def test_rejects_forward_or_incomplete_gate_wiring(self):
        for circuit in ('1 3\n1 1 1\n2 1 0 2 2 AND\n', '2 4\n1 1 1\n2 1 0 1 2 XOR\n'):
            with tempfile.TemporaryDirectory() as root:
                source, target = Path(root) / 'source.txt', Path(root) / 'target.txt'
                source.write_text(circuit)
                with self.assertRaises(ValueError):
                    compact(source, target)
