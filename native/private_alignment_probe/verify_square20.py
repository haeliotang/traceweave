#!/usr/bin/env python3
"""Check a recorded unsigned square primitive against independent Python ints."""
import argparse
import hashlib
import json
from pathlib import Path
import random


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--circuit', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    rng = random.Random(20040)
    values = sorted(set(range(256)) | {1000000, (1 << 20) - 1}
                    | {1 << bit for bit in range(20)}
                    | {(1 << bit) - 1 for bit in range(21)}
                    | {(1 << 20) - 1 - (1 << bit) for bit in range(20)}
                    | {rng.randrange(1 << 20) for _ in range(512)})
    with args.circuit.open() as stream:
        gates, count = map(int, stream.readline().split())
        if list(map(int, stream.readline().split())) != [20,1,40] or count != 21 + gates:
            raise ValueError('square primitive shape mismatch')
        states = [bytearray(count) for _ in values]
        for value, state in zip(values, states):
            state[:20] = bytes((value >> bit) & 1 for bit in range(20))
        processed = 0
        for line in stream:
            fields = line.split()
            if not fields:
                continue
            arity, outputs = map(int, fields[:2])
            parents = list(map(int, fields[2:2 + arity]))
            destination = int(fields[2 + arity])
            operation = fields[-1]
            if (outputs != 1 or destination != 21 + processed or destination >= count
                    or any(parent < 0 or parent >= destination for parent in parents)):
                raise ValueError('invalid square gate wiring')
            for state in states:
                if operation == 'AND' and arity == 2:
                    state[destination] = state[parents[0]] & state[parents[1]]
                elif operation == 'XOR' and arity == 2:
                    state[destination] = state[parents[0]] ^ state[parents[1]]
                elif operation == 'INV' and arity == 1:
                    state[destination] = 1 - state[parents[0]]
                else:
                    raise ValueError('unknown square gate')
            processed += 1
    if processed != gates:
        raise ValueError('incomplete square circuit')
    for value, state in zip(values, states):
        actual = sum(bit << index for index, bit in enumerate(state[-40:]))
        if actual != value * value:
            raise ValueError('unsigned square mismatch: ' + str(value))
    report = {'passed': True, 'gates': gates, 'cases': len(values),
              'circuit_sha256': hashlib.sha256(args.circuit.read_bytes()).hexdigest(),
              'scope': 'plaintext_synthetic_square_primitive_not_private_protocol_or_security_audit'}
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
