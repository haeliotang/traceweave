#!/usr/bin/env python3
"""Verify bounded synthetic private GW circuits against exact integer arithmetic."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import struct

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from traceform.dynamics.alignment_statement import SCALE, validate_alignment_witness
from traceform.dynamics.private_alignment_output import verify_private_alignment_output
from traceform.dynamics.integer_transport import optimize_integer_transport


def encode(values):
    return ''.join(format(value % (1 << 21), '021b')[::-1] for value in values)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--circuit', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--second-nodes', type=int, choices=(2, 3), default=2)
    parser.add_argument('--bound-commitments', action='store_true')
    parser.add_argument('--optimized-transport', action='store_true')
    parser.add_argument('--bound-transport-session', action='store_true',
                        help='Pass the synthetic model session to the TLS launcher via TRACEFORM_TEST_SESSION')
    parser.add_argument('--port', type=int, default=23687, help='Dedicated loopback port for this test run')
    args = parser.parse_args()
    if args.bound_transport_session and not args.bound_commitments:
        raise ValueError('transport session requires bound commitment circuit')
    if not 1 <= args.port <= 65535: raise ValueError('valid loopback test port required')
    lines = [line.split() for line in args.circuit.read_text().splitlines() if line.strip()]
    gate_count, wire_count = map(int, lines[0])
    dimensions = [210, 126, 81] if args.second_nodes == 2 else [252, 252, 81]
    if args.bound_commitments:
        dimensions = [dimensions[0] + 512, dimensions[1] + 512, 849]
    if args.optimized_transport:
        if not args.bound_commitments: raise ValueError('optimized circuit requires commitment binding')
        dimensions[0] -= 2 * args.second_nodes * 21
    assert list(map(int, lines[1])) == dimensions
    assert len(lines) - 2 == gate_count
    baseline = {'first': [[0, SCALE], [SCALE, 0]], 'second': [[0, SCALE], [SCALE, 0]],
                'first_mass': [SCALE // 2] * 2, 'second_mass': [SCALE // 2] * 2,
                'coupling': [[SCALE // 2, 0], [0, SCALE // 2]]}
    if args.second_nodes == 3:
        baseline['second'] = [[0, 400000, 800000], [400000, 0, 400000], [800000, 400000, 0]]
        baseline['second_mass'] = [333334, 333333, 333333]
        baseline['coupling'] = [[333334, 166666, 0], [0, 166667, 333333]]
    fixtures = [('diagonal' if args.second_nodes == 2 else 'feasible_rectangular', baseline)]
    uniform = copy.deepcopy(baseline)
    uniform['coupling'] = ([[SCALE // 4] * 2 for _ in range(2)] if args.second_nodes == 2
                           else [[166667, 166666, 166667], [166667, 166667, 166666]])
    fixtures.append(('uniform_nonzero', uniform))
    odd_distance = copy.deepcopy(uniform)
    odd_distance['first'] = [[0, 32769], [32769, 0]]
    fixtures.append(('odd_distance', odd_distance))
    if args.second_nodes == 2:
        mixed_transport = copy.deepcopy(baseline)
        mixed_transport['coupling'] = [[333333, 166667], [166667, 333333]]
        mixed_transport['second'] = [[0, SCALE // 2], [SCALE // 2, 0]]
        fixtures.append(('mixed_transport', mixed_transport))
    else:
        triangle = copy.deepcopy(baseline)
        triangle['second'][0][2] = triangle['second'][2][0] = 900000
        fixtures.append(('triangle_violation', triangle))
    for label, field, row, column, value in [
        ('asymmetric', 'first', 0, 1, SCALE // 2),
        ('diagonal_nonzero', 'second', 0, 0, 1),
        ('marginal_mismatch', 'coupling', 0, 0, SCALE // 2 - 1),
        ('negative_transport', 'coupling', 0, 1, -1),
    ]:
        witness = copy.deepcopy(baseline)
        witness[field][row][column] = value
        fixtures.append((label, witness))
    bad_mass = copy.deepcopy(baseline)
    bad_mass['first_mass'][0] = SCALE + 1
    fixtures.append(('mass_out_of_range', bad_mass))
    if args.bound_commitments:
        for label in ('changed_session', 'changed_nonce', 'session_mismatch'):
            fixtures.append((label, copy.deepcopy(baseline)))
    if args.optimized_transport:
        fixtures = [(label, witness) for label, witness in fixtures
                    if label not in ('uniform_nonzero', 'marginal_mismatch', 'negative_transport')]
        fixtures = [('different_second_metric' if label == 'mixed_transport' else label, witness)
                    for label, witness in fixtures]
        bad_total = copy.deepcopy(baseline)
        bad_total['first_mass'][0] -= 1
        fixtures.append(('wrong_mass_total', bad_total))
        zero_mass = copy.deepcopy(baseline)
        zero_mass['first_mass'] = [0, SCALE]
        fixtures.append(('zero_mass_row', zero_mass))
        if args.second_nodes == 2:
            swapped_mass = copy.deepcopy(baseline)
            swapped_mass['first_mass'] = [400000, 600000]
            swapped_mass['second_mass'] = [600000, 400000]
            fixtures.append(('private_swapped_mass_optimization', swapped_mass))
    receipts = []
    for label, witness in fixtures:
        try:
            if args.optimized_transport:
                optimized = optimize_integer_transport(witness['first'], witness['second'],
                    witness['first_mass'], witness['second_mass'])
                witness['coupling'] = optimized['coupling']
            expected_value = validate_alignment_witness(**witness)['objective_numerator']
            valid = True
        except ValueError:
            valid, expected_value = False, 0
        expected = str(int(valid)) + format(expected_value, '080b')[::-1]
        first = encode([value for row in witness['first'] for value in row] + witness['first_mass']
                       + ([] if args.optimized_transport else [value for row in witness['coupling'] for value in row]))
        second = encode([value for row in witness['second'] for value in row] + witness['second_mass'])
        if args.bound_commitments:
            session = bytes(range(32))
            nonces = [bytes([11] * 32), bytes([13] * 32)]
            if label == 'changed_session': session = bytes([session[0] ^ 1]) + session[1:]
            if label == 'changed_nonce': nonces[1] = bytes([12]) + nonces[1][1:]
            second_session = bytes([session[0] ^ 1]) + session[1:] if label == 'session_mismatch' else session
            def byte_bits(data):
                return ''.join(format(value, '08b')[::-1] for value in data)
            first += byte_bits(session + nonces[0])
            second += byte_bits(second_session + nonces[1])
            if label == 'session_mismatch':
                valid, expected_value = False, 0
                expected = '0' * 81
            binding = session
            for owner, field, mass_field in [(0, 'first', 'first_mass'), (1, 'second', 'second_mass')]:
                nodes = len(witness[field])
                values = [value for row in witness[field] for value in row] + witness[mass_field]
                message = b'traceform.gw.commit.v1\0' + bytes([owner, nodes]) + struct.pack('<I', SCALE)
                message += session + nonces[owner] + b''.join(struct.pack('<I', v % (1 << 21)) for v in values)
                binding += hashlib.sha256(message).digest()
            expected += byte_bits(binding) if valid else '0' * 768
            public_inputs = expected_value.to_bytes(32, 'little')
            for offset in range(0, 96, 32):
                value = binding[offset:offset + 32]
                public_inputs += value[:31] + b'\0' + value[31:] + b'\0' * 31
        wires = [None] * wire_count
        wires[:sum(dimensions[:2])] = [int(bit) for bit in first + second]
        for gate in lines[2:]:
            count = int(gate[0]); assert int(gate[1]) == 1
            operands = [wires[int(value)] for value in gate[2:2 + count]]
            assert all(value is not None for value in operands)
            destination = int(gate[2 + count]); assert wires[destination] is None
            if gate[-1] == 'AND': value = operands[0] & operands[1]
            elif gate[-1] == 'XOR': value = operands[0] ^ operands[1]
            elif gate[-1] == 'INV': value = 1 - operands[0]
            else: raise ValueError('unknown gate')
            wires[destination] = value
        assert ''.join(map(str, wires[-dimensions[2]:])) == expected, label
        children = []
        try:
            environment = os.environ.copy()
            if args.bound_transport_session:
                environment['TRACEFORM_TEST_SESSION'] = session.hex()
            for role, bits in [('1', second), ('2', first)]:
                children.append(subprocess.Popen([str(args.binary), role, str(args.port), str(args.circuit)],
                                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                                  env=environment))
                children[-1].stdin.write((bits + '\n').encode())
                children[-1].stdin.close()
                children[-1].stdin = None
                if role == '1': time.sleep(.1)
            parties = []
            for child in children:
                stdout, stderr = child.communicate(timeout=60)
                if args.bound_commitments and valid:
                    outputs = [line[len('PUBLIC_OUTPUT='):] for line in stdout.decode().splitlines()
                               if line.startswith('PUBLIC_OUTPUT=')]
                    assert len(outputs) == 1, 'exactly one public output required'
                    verify_private_alignment_output(outputs[0], public_inputs)
                parties.append({'exit_code': child.returncode,
                                'output_matches': 'PUBLIC_OUTPUT=' + expected in stdout.decode().splitlines(),
                                'stderr': stderr.decode()})
            receipts.append({'case': label, 'valid': valid, 'expected_numerator': str(expected_value), 'parties': parties})
            print(json.dumps({'case': label, 'passed': all(p['exit_code'] == 0 and p['output_matches'] for p in parties)}), flush=True)
        finally:
            for child in children:
                if child.poll() is None: child.kill(); child.communicate()
    passed = all(p['exit_code'] == 0 and p['output_matches'] for receipt in receipts for p in receipt['parties'])
    args.output.write_text(json.dumps({'passed': passed, 'gates': gate_count, 'cases': receipts,
        'circuit_sha256': hashlib.sha256(args.circuit.read_bytes()).hexdigest(),
        'binary_sha256': hashlib.sha256(args.binary.read_bytes()).hexdigest(),
        'first_nodes': 2, 'second_nodes': args.second_nodes,
        'optimized_transport': args.optimized_transport,
        'scope': ('synthetic_private_single_sweep_transport_descent_not_global_optimum_or_security_audit'
                  if args.optimized_transport else 'synthetic_private_feasible_gw_same_input_commitments_not_optimizer_or_security_audit'
                  if args.bound_commitments else 'synthetic_private_feasible_gw_relation_not_optimizer_commitment_binding_or_security_audit')}, indent=2) + '\n')
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
