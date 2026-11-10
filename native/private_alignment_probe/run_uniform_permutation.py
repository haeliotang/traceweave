#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Run synthetic uniform-permutation MPC cases with separate per-party inputs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

from verify_uniform_permutation import fixtures, encode, approved_cases


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, required=True, help='Runtime or authenticated per-party launcher')
    parser.add_argument('--circuit', type=Path, required=True)
    parser.add_argument('--nodes', type=int, required=True)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--quantized-search', action='store_true')
    parser.add_argument('--packed-commitment', action='store_true')
    parser.add_argument('--approved-bindings', action='store_true')
    parser.add_argument('--search-distance-shift', type=int, choices=(12, 14), default=12)
    args = parser.parse_args()
    if args.nodes not in (1, 2, 4, 5, 8, 10, 16) or not 1 <= args.port <= 65535:
        raise ValueError('exactly uniform supported shape and dedicated test port required')
    per_owner = (args.nodes ** 2 + args.nodes) * 21 + 512 + (768 if args.approved_bindings else 0)
    with args.circuit.open() as source:
        gates, wires = map(int, source.readline().split())
        if list(map(int, source.readline().split())) != [per_owner, per_owner, 849]:
            raise ValueError('uniform circuit shape mismatch')
    receipts = []
    cases = [(label, *encode(witness, label, quantized=args.quantized_search,
                             packed=args.packed_commitment, search_distance_shift=args.search_distance_shift))
             for label, witness in fixtures(args.nodes, quantized=args.quantized_search,
                                            search_distance_shift=args.search_distance_shift)]
    if args.approved_bindings:
        if not args.packed_commitment or args.search_distance_shift != 14:
            raise ValueError('approved bindings require packed six-bit circuit')
        cases = approved_cases(cases, args.nodes)
    for label, bits, expected, valid, objective in cases:
        children, parties = [], []
        error = None
        started = time.monotonic()
        try:
            environment = os.environ.copy()
            environment['TRACEFORM_TEST_SESSION'] = bytes(range(32)).hex()
            for role, own_bits in [('1', bits[per_owner:]), ('2', bits[:per_owner])]:
                child = subprocess.Popen([str(args.binary), role, str(args.port), str(args.circuit)],
                                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                         env=environment)
                children.append(child)
                child.stdin.write((own_bits + '\n').encode())
                child.stdin.close()
                child.stdin = None
                if role == '1':
                    time.sleep(.1)
            for child in children:
                stdout, stderr = child.communicate(timeout=120)
                outputs = [line for line in stdout.decode().splitlines() if line.startswith('PUBLIC_OUTPUT=')]
                parties.append({'exit_code': child.returncode,
                                'output_matches': outputs == ['PUBLIC_OUTPUT=' + expected],
                                'stderr': stderr.decode()})
        except Exception as failure:
            error = str(failure)
        finally:
            for child in children:
                if child.poll() is None:
                    child.kill()
                    child.communicate()
        passed = error is None and len(parties) == 2 and all(p['exit_code'] == 0 and p['output_matches'] for p in parties)
        receipts.append({'case': label, 'passed': passed, 'valid': valid, 'objective_numerator': str(objective),
                         'elapsed_seconds': time.monotonic() - started, 'parties': parties, 'error': error})
        print(json.dumps({'case': label, 'passed': passed, 'elapsed_seconds': receipts[-1]['elapsed_seconds']}), flush=True)
        if not passed:
            break
    passed = len(receipts) == len(cases) and all(r['passed'] for r in receipts)
    report = {'passed': passed, 'nodes': args.nodes, 'gates': gates, 'wires': wires, 'cases': receipts,
              'search': ('eight_bit' if args.search_distance_shift == 12 else 'six_bit') + '_quantized_with_exact_final_loss_gate' if args.quantized_search else 'exact_uniform_permutation',
              'search_distance_shift': args.search_distance_shift if args.quantized_search else None,
              'commitment': 'uniform_packed_v2' if args.packed_commitment else 'full_metric_mass_v1',
              'circuit_sha256': digest(args.circuit), 'launcher_sha256': digest(args.binary),
              'approved_bindings': args.approved_bindings,
              'scope': 'synthetic_separate_party_input_MPC_not_real_owner_enrollment_or_security_audit'}
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
