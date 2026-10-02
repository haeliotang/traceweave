import io
import unittest
from unittest.mock import patch
from subprocess import CompletedProcess

from native.private_alignment_probe import run_usb_client


class TestPrivateUsbClient(unittest.TestCase):
    def run_client(self, results, clocks, bootstrap_seconds=3):
        output, errors = io.BytesIO(), io.BytesIO()
        with patch.object(run_usb_client.sys, 'argv', ['adapter', 'participant']), \
             patch.object(run_usb_client.sys, 'stdin', io.TextIOWrapper(io.BytesIO(b'0011\n'))), \
             patch.object(run_usb_client.sys, 'stdout', io.TextIOWrapper(output)), \
             patch.object(run_usb_client.sys, 'stderr', io.TextIOWrapper(errors)), \
             patch.object(run_usb_client.time, 'monotonic', side_effect=clocks), \
             patch.object(run_usb_client.subprocess, 'run', side_effect=results) as execute:
            code = run_usb_client.main(bootstrap_seconds=bootstrap_seconds)
            calls = execute.call_args_list
        return code, calls

    def test_retries_only_initial_eof_and_preserves_party_input(self):
        transient = CompletedProcess([], 1, b'connected\n', b'TLS handshake rejected: unexpected eof while reading')
        success = CompletedProcess([], 0, b'PUBLIC_OUTPUT=1\n', b'')
        code, calls = self.run_client([transient, success], [0, 0])
        self.assertEqual(code, 0)
        self.assertEqual(len(calls), 2)
        self.assertTrue(all(call.kwargs['input'] == b'0011\n' for call in calls))

    def test_authentication_and_mpc_failures_are_terminal(self):
        for output, error in [(b'connected\n', b'TLS peer certificate pin rejected'),
                              (b'connected\n722 638 849\n', b'TLS handshake rejected: unexpected eof while reading')]:
            with self.subTest(error=error):
                code, calls = self.run_client([CompletedProcess([], 1, output, error)], [0])
                self.assertEqual(code, 1)
                self.assertEqual(len(calls), 1)

    def test_bootstrap_retry_deadline_is_bounded(self):
        failure = CompletedProcess([], 1, b'connected\n', b'TLS handshake rejected: unexpected eof while reading')
        code, calls = self.run_client([failure], [0, 4])
        self.assertEqual(code, 1)
        self.assertEqual(len(calls), 1)

    def test_large_circuit_bootstrap_extends_only_bounded_initial_retry(self):
        failure = CompletedProcess([], 1, b'connected\n', b'TLS handshake rejected: unexpected eof while reading')
        code, calls = self.run_client([failure, failure], [0, 4, 61], bootstrap_seconds=60)
        self.assertEqual(code, 1)
        self.assertEqual(len(calls), 2)
