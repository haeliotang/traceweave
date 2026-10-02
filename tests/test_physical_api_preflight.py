import subprocess
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from scripts.physical_supervision_v2 import api_preflight


class TestPhysicalApiPreflight(unittest.TestCase):
    def test_requires_successful_reply_with_boolean_presence(self):
        for code, payload, ready in [(0, '{"present": true}', True),
                                     (0, '{"present": false}', True),
                                     (1, '{"present": true}', False),
                                     (0, '{}', False), (0, '[]', False),
                                     (0, '{"present": 1}', False)]:
            with self.subTest(code=code, payload=payload):
                with patch('scripts.physical_supervision_v2.subprocess.run',
                           side_effect=[SimpleNamespace(returncode=code, stdout=payload),
                                        SimpleNamespace(returncode=0, stdout='{"LSM6DSR Accelerometer": {"values": [0, 0, 9.8]}, "TMD3719 Ambient Light": {"values": [10]}}'),
                                        SimpleNamespace(returncode=0, stdout='')]):
                    self.assertEqual(api_preflight()['ready'], ready)

    def test_invalid_json_and_timeout_fail_closed(self):
        with patch('scripts.physical_supervision_v2.subprocess.run',
                   return_value=SimpleNamespace(returncode=0, stdout='invalid')):
            self.assertFalse(api_preflight()['ready'])
        with patch('scripts.physical_supervision_v2.subprocess.run',
                   side_effect=subprocess.TimeoutExpired('battery', 5)):
            self.assertFalse(api_preflight()['ready'])

    def test_battery_success_does_not_accept_missing_sensor_events(self):
        with patch('scripts.physical_supervision_v2.subprocess.run', side_effect=[
            SimpleNamespace(returncode=0, stdout='{"present": true}'),
            SimpleNamespace(returncode=0, stdout='{}'),
            SimpleNamespace(returncode=0, stdout='')]):
            self.assertFalse(api_preflight()['ready'])

    def test_invalid_sensor_values_and_timeouts_are_rejected_and_cleaned_up(self):
        for payload in ['[]', '{"LSM6DSR Accelerometer": {"values": [0, 0]}}',
                        '{"LSM6DSR Accelerometer": {"values": [0, 0, NaN]}, "TMD3719 Ambient Light": {"values": [1]}}',
                        '{"LSM6DSR Accelerometer": {"values": [0, 0, 9.8]}, "TMD3719 Ambient Light": {"values": [true]}}']:
            with self.subTest(payload=payload), patch('scripts.physical_supervision_v2.subprocess.run', side_effect=[
                SimpleNamespace(returncode=0, stdout='{"present": true}'),
                SimpleNamespace(returncode=0, stdout=payload),
                SimpleNamespace(returncode=0, stdout='')]) as run:
                self.assertFalse(api_preflight()['ready'])
                self.assertEqual(run.call_args.args[0][-1], '-c')
        with patch('scripts.physical_supervision_v2.subprocess.run', side_effect=[
            SimpleNamespace(returncode=0, stdout='{"present": true}'),
            subprocess.TimeoutExpired('sensor', 5), SimpleNamespace(returncode=0, stdout='')]) as run:
            self.assertFalse(api_preflight()['ready'])
            self.assertEqual(run.call_args.args[0][-1], '-c')
