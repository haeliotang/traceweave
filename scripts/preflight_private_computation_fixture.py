#!/usr/bin/env python3
"""Check isolated synthetic unknown-score fixtures before USB dispatch.

Fixture DER keys are test identities, not production owner enrollment. This
check does not compute the objective, reserve a session or contact a device.
"""
import argparse
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from native.private_alignment_probe.verify_uniform_permutation import packed_uniform_message


APPROVED_CIRCUIT = 'ba00463c74f7899a069305ab7edf8041234dea76ae6fb5a430182191c924dca2'


def approval_bits(bindings):
    if len(bindings) != 192:
        raise ValueError('exact computation bindings required')
    for block in range(3):
        if any(bindings[block * 64 + offset] for offset in range(31, 64) if offset != 32):
            raise ValueError('noncanonical computation bindings')
    return ''.join(str((bindings[block * 64 + (32 if index == 31 else index)] >> bit) & 1)
                   for block in range(3) for index in range(32) for bit in range(8))


def verify_private_inputs(own, peer, bindings):
    approvals = approval_bits(bindings)
    session = bindings[:31] + bindings[32:33]
    for owner, bits in ((0, peer), (1, own)):
        if len(bits) != 6224 or set(bits) - {'0', '1'}:
            raise ValueError('exact sixteen-node private bits required')
        values = [int(bits[index:index + 21][::-1], 2) for index in range(0, 5712, 21)]
        context = bytes(int(bits[index:index + 8][::-1], 2) for index in range(5712, 6224, 8))
        if context[:32] != session:
            raise ValueError('private input session differs from approval')
        metric = [values[index:index + 16] for index in range(0, 256, 16)]
        commitment = hashlib.sha256(packed_uniform_message(owner, metric, session, context[32:])).digest()
        block = (owner + 1) * 64
        approved = bindings[block:block + 31] + bindings[block + 32:block + 33]
        if commitment != approved:
            raise ValueError('private input commitment differs from approval')
    return approvals


def verify_fixture(directory, circuit):
    directory, circuit = Path(directory), Path(circuit)
    digest = hashlib.sha256()
    with circuit.open('rb') as source:
        for chunk in iter(lambda: source.read(65536), b''):
            digest.update(chunk)
    if digest.hexdigest() != APPROVED_CIRCUIT:
        raise ValueError('approved circuit digest mismatch')
    bindings = (directory / 'bindings.bin').read_bytes()
    approvals = verify_private_inputs((directory / 'own.bits').read_text(),
                                     (directory / 'peer.bits').read_text(), bindings)
    root = Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory() as temporary:
        harness = Path(temporary, 'FixtureApproval.java')
        harness.write_text('''package com.inceptless.traceweave.edge;
import java.io.File;import java.security.*;import java.security.spec.X509EncodedKeySpec;
public final class FixtureApproval {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);PublicKey[] owners = new PublicKey[2];
        for (int owner=0;owner<2;owner++) owners[owner] = KeyFactory.getInstance("EC").generatePublic(
            new X509EncodedKeySpec(OwnerAuthentication.readBounded(new File(root,"owner"+owner+".der"),256)));
        OwnerAuthentication.verifyComputation(root,args[1],owners);
    }
}''')
        subprocess.run(['javac', '--release', '8', '-d', temporary,
                        str(root / 'android/src/com/inceptless/traceweave/edge/OwnerAuthentication.java'), str(harness)],
                       check=True, capture_output=True, timeout=30)
        subprocess.run(['java', '-cp', temporary, 'com.inceptless.traceweave.edge.FixtureApproval',
                        str(directory.resolve()), APPROVED_CIRCUIT], check=True, capture_output=True, timeout=10)
    return approvals


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--circuit', type=Path, required=True)
    args = parser.parse_args()
    try:
        verify_fixture(args.fixture, args.circuit)
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.stderr.decode().strip()) from error
    print('SYNTHETIC_APPROVAL_PREFLIGHT=PASS')


if __name__ == '__main__':
    main()
