#!/usr/bin/env python3
"""Check one loss-contribution circuit against independent arbitrary-precision arithmetic."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--circuit', type=Path, required=True)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    lines = [line.split() for line in args.circuit.read_text().splitlines() if line.strip()]
    gate_count, wire_count = map(int, lines[0])
    assert list(map(int, lines[1])) == [21, 21, 81]
    assert len(lines[2:]) == gate_count
    cases = []
    for first, second in [(0, 0), (1000000, 0), (0, 1000000), (1000000, 1000000),
                          (32768, 1234), (1235, 0), (1000001, 0), ((1 << 21) - 1, 0)]:
        first_bits = format(first, '021b')[::-1]
        second_bits = format(second, '021b')[::-1]
        valid = first <= 1000000 and second <= 1000000
        numerator = (first - second) ** 2 * 1000000 ** 2 if valid else 0
        expected = str(int(valid)) + format(numerator, '080b')[::-1]
        wires = [None] * wire_count
        wires[:42] = [int(bit) for bit in first_bits + second_bits]
        for gate in lines[2:]:
            count = int(gate[0])
            assert int(gate[1]) == 1
            inputs = [wires[int(value)] for value in gate[2:2 + count]]
            assert all(value is not None for value in inputs)
            output = int(gate[2 + count])
            assert wires[output] is None
            operation = gate[-1]
            if operation == 'AND': value = inputs[0] & inputs[1]
            elif operation == 'XOR': value = inputs[0] ^ inputs[1]
            elif operation == 'INV': value = 1 - inputs[0]
            else: raise ValueError('unknown gate')
            wires[output] = value
        actual = ''.join(map(str, wires[-81:]))
        assert actual == expected, (first, second, expected[0], numerator,
                                   actual[0], int(actual[1:][::-1], 2))
        children = []
        try:
            for role, bits in [('1', first_bits), ('2', second_bits)]:
                children.append(subprocess.Popen([str(args.binary), role, '23685', str(args.circuit)],
                                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE))
                children[-1].stdin.write((bits + '\n').encode())
                children[-1].stdin.close()
                children[-1].stdin = None
                if role == '1': time.sleep(.1)
            parties = []
            for child in children:
                stdout, stderr = child.communicate(timeout=30)
                parties.append({'exit_code': child.returncode,
                                'output_matches': 'PUBLIC_OUTPUT=' + expected in stdout.decode().splitlines(),
                                'stderr': stderr.decode()})
            cases.append({'valid': valid, 'expected_numerator': str(numerator), 'parties': parties})
        finally:
            for child in children:
                if child.poll() is None: child.kill(); child.communicate()
    passed = all(p['exit_code'] == 0 and p['output_matches'] for case in cases for p in case['parties'])
    args.output.write_text(json.dumps({'passed': passed, 'cases': cases, 'gates': gate_count,
        'circuit_sha256': hashlib.sha256(args.circuit.read_bytes()).hexdigest(),
        'binary_sha256': hashlib.sha256(args.binary.read_bytes()).hexdigest(),
        'scope': 'bounded_single_loss_contribution_not_complete_gw_or_commitment_binding'}, indent=2) + '\n')
    print(json.dumps({'passed': passed, 'cases': len(cases), 'gates': gate_count}))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
