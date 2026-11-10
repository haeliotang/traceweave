#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Check recorded commitment gates against independent SHA-256 byte encoding."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--generator', type=Path, required=True)
    parser.add_argument('--sha-circuit', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    expected_sha = 'bd0a91bb7e97bb60c1468fe8caecc546af3f832bd4152d9c8c4e7527412dd11d'
    if hashlib.sha256(args.sha_circuit.read_bytes()).hexdigest() != expected_sha:
        raise ValueError('unexpected SHA compression circuit')
    receipts = []
    with tempfile.TemporaryDirectory(prefix='traceform-commitment-') as directory:
        for owner, nodes in [(0, 1), (1, 1), (0, 2), (1, 3), (0, 4)]:
            circuit = Path(directory) / 'commitment.txt'
            subprocess.run([str(args.generator), str(owner), str(nodes),
                            str(args.sha_circuit), str(circuit)], check=True, timeout=60)
            lines = [line.split() for line in circuit.read_text().splitlines() if line.strip()]
            gate_count, wire_count = map(int, lines[0])
            first_count, second_count, output_count = map(int, lines[1])
            assert (first_count, second_count, output_count) == (21 * (nodes * nodes + nodes) + 256, 256, 256)
            assert len(lines[2:]) == gate_count
            for case in ('zero', 'varied', 'changed_session', 'changed_nonce', 'changed_model'):
                values = [0] * (nodes * nodes + nodes) if case == 'zero' else [
                    (i * 32769 + 1235) % 1000001 for i in range(nodes * nodes + nodes)]
                session = bytes(range(32))
                nonce = bytes(range(255, 223, -1))
                if case == 'changed_session': session = bytes([session[0] ^ 1]) + session[1:]
                if case == 'changed_nonce': nonce = bytes([nonce[0] ^ 1]) + nonce[1:]
                if case == 'changed_model': values[0] ^= 1
                bits = ''.join(format(v, '021b')[::-1] for v in values)
                bits += ''.join(format(v, '08b')[::-1] for v in nonce + session)
                wires = [None] * wire_count
                wires[:first_count + second_count] = map(int, bits)
                for gate in lines[2:]:
                    count = int(gate[0])
                    assert int(gate[1]) == 1
                    operands = [wires[int(v)] for v in gate[2:2 + count]]
                    target = int(gate[2 + count])
                    assert all(v is not None for v in operands) and wires[target] is None
                    operation = gate[-1]
                    if operation == 'AND': result = operands[0] & operands[1]
                    elif operation == 'XOR': result = operands[0] ^ operands[1]
                    elif operation == 'INV': result = 1 - operands[0]
                    else: raise ValueError('unexpected gate')
                    wires[target] = result
                out = wires[-256:]
                actual = bytes(sum(out[i + j] << j for j in range(8)) for i in range(0, 256, 8))
                message = b'traceform.gw.commit.v1\0' + bytes([owner, nodes]) + struct.pack('<I', 1000000)
                message += session + nonce + b''.join(struct.pack('<I', v) for v in values)
                expected = hashlib.sha256(message).digest()
                assert actual == expected, (owner, nodes, case, actual.hex(), expected.hex())
                receipts.append({'owner': owner, 'nodes': nodes, 'case': case,
                                 'preimage_bytes': len(message), 'gates': gate_count,
                                 'passed': True, 'circuit_sha256': hashlib.sha256(circuit.read_bytes()).hexdigest()})
                print(json.dumps({'owner': owner, 'nodes': nodes, 'case': case, 'passed': True}), flush=True)
    args.output.write_text(json.dumps({'passed': True, 'cases': receipts,
        'sha_compression_circuit_sha256': expected_sha,
        'generator_sha256': hashlib.sha256(args.generator.read_bytes()).hexdigest(),
        'scope': 'independent_commitment_gate_evaluation_not_gw_integration_or_protocol_execution'}, indent=2) + '\n')


if __name__ == '__main__':
    main()
