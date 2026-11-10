#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Check recorded uniform-permutation gates against independent integer witnesses.

This evaluates plaintext synthetic gates, not a private protocol execution.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import struct
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from traceform.dynamics.alignment_statement import SCALE
from traceform.dynamics.integer_transport import optimize_uniform_permutation, optimize_quantized_uniform_permutation


def fixtures(nodes, quantized=False, search_distance_shift=12):
    points = [index * index * 4000 for index in range(nodes)]
    metric = [[abs(a - b) for b in points] for a in points]
    base = {'first': metric, 'second': copy.deepcopy(metric),
            'first_mass': [SCALE // nodes] * nodes, 'second_mass': [SCALE // nodes] * nodes}
    cases = [('identical', base)]
    diagonal = copy.deepcopy(base)
    diagonal['first'][0][0] = 1
    cases.append(('nonzero_diagonal', diagonal))
    if nodes > 1:
        transposition = list(range(nodes))
        transposition[0], transposition[1] = transposition[1], transposition[0]
        for label, permutation in [('transposition', transposition), ('reversal', list(reversed(range(nodes))))]:
            changed = copy.deepcopy(base)
            changed['second'] = [[metric[i][j] for j in permutation] for i in permutation]
            cases.append((label, changed))
        asymmetric = copy.deepcopy(base)
        asymmetric['first'][0][1] += 1
        cases.append(('asymmetric', asymmetric))
        nonuniform = copy.deepcopy(base)
        nonuniform['first_mass'][0] += 1
        nonuniform['first_mass'][1] -= 1
        cases.append(('nonuniform_same_total', nonuniform))
        for label, value in [('negative_distance', -1), ('out_of_range_distance', SCALE + 1)]:
            changed = copy.deepcopy(base)
            changed['first'][0][1] = changed['first'][1][0] = value
            cases.append((label, changed))
    if nodes > 2:
        triangle = copy.deepcopy(base)
        triangle['first'][0][1] = triangle['first'][1][0] = metric[0][2] + metric[2][1] + 1
        cases.append(('triangle_violation', triangle))
    scaled = copy.deepcopy(base)
    scaled['second'] = [[value // 2 for value in row] for row in metric]
    cases.append(('different_scale', scaled))
    maximum = copy.deepcopy(base)
    maximum['first'] = [[0 if i == j else SCALE for j in range(nodes)] for i in range(nodes)]
    maximum['second'] = [[0] * nodes for _ in range(nodes)]
    cases.append(('large_80bit_objective', maximum))
    wrong_total = copy.deepcopy(base)
    wrong_total['second_mass'][0] -= 1
    cases.extend([('wrong_total', wrong_total), ('session_mismatch', copy.deepcopy(base)),
                  ('changed_nonce', copy.deepcopy(base))])
    if quantized and nodes == 4:
        fallback = copy.deepcopy(base)
        for field, points in [('first', [1127, 6, 9398, 6318]), ('second', [5906, 1203, 9552, 7846])]:
            points = [value * (1 << (search_distance_shift - 12)) for value in points]
            fallback[field] = [[abs(a - b) for b in points] for a in points]
        cases.append(('full_precision_rejection', fallback))
    return cases


def packed_uniform_message(owner, metric, session, nonce):
    nodes = len(metric)
    if not 1 <= nodes <= 16 or SCALE % nodes:
        raise ValueError('packed uniform shape must divide the mass scale')
    message = (b'traceform.gw.uniform-packed.v2\0' + bytes([owner, nodes])
               + struct.pack('<II', SCALE, SCALE // nodes) + session + nonce)
    payload = ''.join(format(metric[i][j] % (1 << 20), '020b')[::-1]
                      for i in range(nodes) for j in range(i + 1, nodes))
    payload += '0' * (-len(payload) % 8)
    return message + bytes(int(payload[index:index + 8][::-1], 2) for index in range(0, len(payload), 8))


def encode(witness, label, quantized=False, packed=False, search_distance_shift=12, session=None):
    if packed and not quantized:
        raise ValueError('packed v2 commitments require the dedicated quantized-search circuit')
    if search_distance_shift == 14 and not packed:
        raise ValueError('six-bit search requires the dedicated packed low-memory circuit')
    session = bytes(range(32)) if session is None else session
    if not isinstance(session, bytes) or len(session) != 32:
        raise ValueError('exact synthetic session required')
    sessions = [session, session]
    if label == 'session_mismatch':
        sessions[1] = bytes([session[0] ^ 1]) + session[1:]
    nonces = [bytes([11] * 32), bytes([12 if label == 'changed_nonce' else 13] * 32)]
    bit_inputs, commitments = [], []
    for owner, field, masses in [(0, 'first', 'first_mass'), (1, 'second', 'second_mass')]:
        values = [value for row in witness[field] for value in row] + witness[masses]
        bits = ''.join(format(value % (1 << 21), '021b')[::-1] for value in values)
        bits += ''.join(format(value, '08b')[::-1] for value in sessions[owner] + nonces[owner])
        bit_inputs.append(bits)
        message = b'traceform.gw.commit.v1\0' + bytes([owner, len(witness[field])]) + struct.pack('<I', SCALE)
        message += sessions[owner] + nonces[owner] + b''.join(struct.pack('<I', value % (1 << 21)) for value in values)
        if packed:
            message = packed_uniform_message(owner, witness[field], sessions[owner], nonces[owner])
        commitments.append(hashlib.sha256(message).digest())
    try:
        optimizer = optimize_quantized_uniform_permutation if quantized else optimize_uniform_permutation
        result = optimizer(**witness, search_distance_shift=search_distance_shift) if quantized else optimizer(**witness)
        valid = label != 'session_mismatch'
        objective = result['objective_numerator'] if valid else 0
    except ValueError:
        valid, objective = False, 0
    expected = str(int(valid)) + format(objective, '080b')[::-1]
    binding = session + b''.join(commitments)
    expected += ''.join(format(value, '08b')[::-1] for value in binding) if valid else '0' * 768
    return ''.join(bit_inputs), expected, valid, objective


def approved_cases(cases, nodes):
    original = cases
    cases = []
    owner_width = (nodes ** 2 + nodes) * 21 + 512
    for label, bits, expected, valid, objective in original:
        approval = expected[81:]
        cases.append((label, bits[:owner_width] + approval + bits[owner_width:] + approval,
                      expected, valid, objective))
    base = next(case for case in original if case[0] == 'different_scale')
    label, bits, expected, valid, objective = base
    approved = expected[81:]
    changed = copy.deepcopy(next(w for name, w in fixtures(nodes) if name == label))
    changed['first'] = copy.deepcopy(changed['second'])
    changed_bits, changed_output, _, _ = encode(changed, label, quantized=True, packed=True,
                                               search_distance_shift=14)
    cases.append(('changed_valid_model', changed_bits[:owner_width] + approved
                  + changed_bits[owner_width:] + approved, '0' * 849, False, 0))
    cases.append(('changed_model_and_one_approval', changed_bits[:owner_width] + changed_output[81:]
                  + changed_bits[owner_width:] + approved, '0' * 849, False, 0))
    for index in (0, 255, 256, 511, 512, 767):
        altered = approved[:index] + str(1 - int(approved[index])) + approved[index + 1:]
        cases.append(('changed_approval_' + str(index), bits[:owner_width] + altered
                      + bits[owner_width:] + altered, '0' * 849, False, 0))
    return cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--circuit', type=Path, required=True)
    parser.add_argument('--nodes', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--quantized-search', action='store_true')
    parser.add_argument('--packed-commitment', action='store_true')
    parser.add_argument('--approved-bindings', action='store_true')
    parser.add_argument('--search-distance-shift', type=int, choices=(12, 14), default=12)
    args = parser.parse_args()
    if not 1 <= args.nodes <= 16 or SCALE % args.nodes:
        raise ValueError('1..16 nodes dividing integer scale required')
    cases = [(label, *encode(witness, label, quantized=args.quantized_search, packed=args.packed_commitment,
                            search_distance_shift=args.search_distance_shift))
             for label, witness in fixtures(args.nodes, quantized=args.quantized_search,
                                            search_distance_shift=args.search_distance_shift)]
    if args.approved_bindings:
        if not args.packed_commitment or args.search_distance_shift != 14:
            raise ValueError('approved bindings require packed six-bit circuit')
        cases = approved_cases(cases, args.nodes)
    with args.circuit.open() as source:
        gates, count = map(int, source.readline().split())
        dimensions = list(map(int, source.readline().split()))
        per_owner = (args.nodes ** 2 + args.nodes) * 21 + 512 + (768 if args.approved_bindings else 0)
        if dimensions != [per_owner, per_owner, 849]:
            raise ValueError('uniform circuit input/output shape mismatch')
        wires = [bytearray(count) for _ in cases]
        for state, (_, bits, _, _, _) in zip(wires, cases):
            state[:len(bits)] = bytes(map(int, bits))
        processed = 0
        for line in source:
            fields = line.split()
            if not fields:
                continue
            operands, outputs = map(int, fields[:2])
            parents = list(map(int, fields[2:2 + operands]))
            destination = int(fields[2 + operands])
            operation = fields[-1]
            if (outputs != 1 or destination != 2 * per_owner + processed or destination >= count
                    or any(parent < 0 or parent >= destination for parent in parents)):
                raise ValueError('invalid ordered gate wiring')
            for state in wires:
                if operation == 'AND' and operands == 2:
                    state[destination] = state[parents[0]] & state[parents[1]]
                elif operation == 'XOR' and operands == 2:
                    state[destination] = state[parents[0]] ^ state[parents[1]]
                elif operation == 'INV' and operands == 1:
                    state[destination] = 1 - state[parents[0]]
                else:
                    raise ValueError('unknown gate')
            processed += 1
    if processed != gates or count != 2 * per_owner + gates:
        raise ValueError('incomplete circuit')
    receipts = []
    for state, (label, _, expected, valid, objective) in zip(wires, cases):
        actual = ''.join(map(str, state[-849:]))
        if actual != expected:
            raise ValueError('uniform circuit mismatch: ' + label)
        receipts.append({'case': label, 'valid': valid, 'objective_numerator': str(objective), 'passed': True})
    report = {'passed': True, 'nodes': args.nodes, 'gates': gates, 'inputs_per_owner': per_owner,
              'search': ('eight_bit' if args.search_distance_shift == 12 else 'six_bit') + '_quantized_with_exact_final_loss_gate' if args.quantized_search else 'exact_uniform_permutation',
              'search_distance_shift': args.search_distance_shift if args.quantized_search else None,
              'commitment': 'uniform_packed_v2' if args.packed_commitment else 'full_metric_mass_v1',
              'circuit_sha256': hashlib.sha256(args.circuit.read_bytes()).hexdigest(), 'cases': receipts,
              'approved_bindings': args.approved_bindings,
              'scope': 'plaintext_synthetic_recorded_gate_validation_not_private_execution_or_security_audit'}
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'passed': True, 'nodes': args.nodes, 'gates': gates, 'cases': len(receipts)}))


if __name__ == '__main__':
    main()
