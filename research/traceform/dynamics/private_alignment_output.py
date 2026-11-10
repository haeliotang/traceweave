# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Compare circuit output with an externally authenticated public statement.

Matching bytes do not authenticate owners or verify a cryptographic proof.
"""

import hmac

from traceform.dynamics.alignment_statement import SCALE


def verify_private_alignment_output(bits, public_inputs):
    """Match the 849-bit output to the existing seven-field public encoding."""
    if not isinstance(bits, str) or len(bits) != 849 or any(bit not in '01' for bit in bits):
        raise ValueError('exact binary private alignment output required')
    if bits[0] != '1':
        raise ValueError('private alignment witness rejected')
    numerator = int(bits[1:81][::-1], 2)
    if numerator > SCALE ** 4:
        raise ValueError('private alignment objective exceeds bound')
    if not isinstance(public_inputs, bytes) or len(public_inputs) != 224:
        raise ValueError('exact public statement encoding required')
    binding = bytes(int(bits[i:i + 8][::-1], 2) for i in range(81, 849, 8))
    encoded = numerator.to_bytes(32, 'little')
    for offset in range(0, 96, 32):
        value = binding[offset:offset + 32]
        encoded += value[:31] + b'\0' + value[31:] + b'\0' * 31
    if not hmac.compare_digest(encoded, public_inputs):
        raise ValueError('private alignment output does not match public statement')
    return {'objective_numerator': numerator, 'session_hex': binding[:32].hex(),
            'commitments_hex': [binding[32:64].hex(), binding[64:96].hex()],
            'scope': 'public_statement_byte_match_not_authentication_or_proof_verification'}
