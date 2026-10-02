#!/usr/bin/env python3
"""Fold constants, reuse gates and remove dead gates from ordered Bristol.

Private inputs, output order and Boolean semantics remain unchanged. This is a
circuit rewrite, not a protocol or confidentiality audit.
"""
import argparse
from array import array
import hashlib
import json
import os
from pathlib import Path
import tempfile


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def compact(source, target):
    if source.resolve() == target.resolve():
        raise ValueError('distinct input and output paths required')
    left, right, operations = array('i'), array('i'), bytearray()
    common, reused = {}, 0
    with source.open() as stream:
        gates, wires = map(int, stream.readline().split())
        dimensions = list(map(int, stream.readline().split()))
        if (len(dimensions) != 3 or any(value < 1 for value in dimensions)
                or gates < 1 or wires >= 2 ** 31):
            raise ValueError('bounded Bristol dimensions required')
        inputs = sum(dimensions[:2])
        outputs = dimensions[2]
        if wires != inputs + gates or outputs > gates:
            raise ValueError('ordered single-output gate circuit required')
        mapping = array('i', [0]) * wires
        mapping[:inputs] = array('i', range(inputs))

        def append(a, b, operation, deduplicate=True):
            nonlocal reused
            if operation != 2 and a > b:
                a, b = b, a
            key = operation | (a << 2) | (b << 33)
            if deduplicate and key in common:
                reused += 1
                return common[key]
            wire = inputs + len(operations)
            left.append(a); right.append(b); operations.append(operation)
            if deduplicate:
                # A bounded cache changes reuse opportunities, never semantics.
                if len(common) >= 262144:
                    common.clear()
                common[key] = wire
            return wire

        processed = 0
        for line in stream:
            fields = line.split()
            if not fields:
                continue
            arity, destinations = map(int, fields[:2])
            operation = fields[-1]
            if (destinations != 1 or operation not in ('AND', 'XOR', 'INV')
                    or arity != (1 if operation == 'INV' else 2) or len(fields) != arity + 4):
                raise ValueError('unsupported gate shape')
            parents = list(map(int, fields[2:2 + arity]))
            destination = int(fields[2 + arity])
            if (destination != inputs + processed or destination >= wires
                    or any(parent < 0 or parent >= destination for parent in parents)):
                raise ValueError('invalid ordered gate wiring')
            a = mapping[parents[0]]
            b = mapping[parents[1]] if arity == 2 else a
            # Negative identifiers denote public zero (-1) and one (-2).
            if operation == 'INV':
                result = -3 - a if a < 0 else append(a, a, 2)
            elif operation == 'AND':
                result = (-1 if a == -1 or b == -1 else b if a == -2 else
                          a if b == -2 or a == b else append(a, b, 0))
            else:
                if a == b:
                    result = -1
                elif a == -1:
                    result = b
                elif b == -1:
                    result = a
                elif a == -2 or b == -2:
                    other = b if a == -2 else a
                    result = append(other, other, 2)
                else:
                    result = append(a, b, 1)
            mapping[destination] = result
            processed += 1
        if processed != gates:
            raise ValueError('incomplete circuit')
    roots = list(mapping[-outputs:])
    del mapping
    zero = append(0, 0, 1)
    one = append(zero, zero, 2) if -2 in roots else None
    # Explicit copies keep every output in its original position at the end.
    for root in roots:
        value = zero if root == -1 else one if root == -2 else root
        append(value, zero, 1, deduplicate=False)
    alive = bytearray(inputs + len(operations))
    alive[-outputs:] = b'\1' * outputs
    for index in range(len(operations) - 1, -1, -1):
        if alive[inputs + index]:
            alive[left[index]] = 1
            if operations[index] != 2:
                alive[right[index]] = 1
    retained = sum(alive[inputs:])
    renumber = array('i', [0]) * len(alive)
    renumber[:inputs] = array('i', range(inputs))
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile('w', dir=target.parent, delete=False) as result:
            temporary = Path(result.name)
            result.write(f'{retained} {inputs + retained}\n')
            result.write(' '.join(map(str, dimensions)) + '\n\n')
            next_wire = inputs
            for index, operation in enumerate(operations):
                if not alive[inputs + index]:
                    continue
                renumber[inputs + index] = next_wire
                a, b = renumber[left[index]], renumber[right[index]]
                result.write(f'1 1 {a} {next_wire} INV\n' if operation == 2 else
                             f'2 1 {a} {b} {next_wire} {"AND" if operation == 0 else "XOR"}\n')
                next_wire += 1
        os.replace(temporary, target)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return {'input_gates': gates, 'output_gates': retained,
            'common_gates_reused': reused, 'gate_hash_cache_capacity': 262144,
            'input_wires': wires, 'output_wires': inputs + retained,
            'input_sha256': digest(source), 'output_sha256': digest(target),
            'scope': 'constant_folding_bounded_gate_reuse_dead_gate_elimination_not_security_audit'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--target', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    report = compact(args.source, args.target)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
