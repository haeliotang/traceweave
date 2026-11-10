# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from pixel_physical import device_state as frozen_device
from traceform.streaming import physical_device_state_v2 as device


class TestPhysicalDeviceStateProvenanceV2(unittest.TestCase):
    def sample(self, adapter, battery, audio):
        replies = [SimpleNamespace(returncode=0, stdout=json.dumps(value)) for value in (battery, audio)]
        with patch.object(adapter.Path, 'exists', return_value=True), \
             patch.object(adapter.subprocess, 'run', side_effect=replies):
            return adapter.sample_device_state()

    def test_reported_false_is_distinct_from_defaults_and_numbers_match_v1(self):
        for audio in ({}, {'WIREDHEADSET_IS_CONNECTED': False}):
            with self.subTest(audio=audio):
                battery = {'percentage': 50, 'voltage': 4000}
                values, status = self.sample(device, battery, audio)
                fields = values.pop('_field_provenance')
                self.assertEqual(fields['battery_percentage'], 'reported_by_api')
                self.assertEqual(fields['battery_temp_c'], 'unavailable')
                self.assertEqual(fields['screen_state'], 'unsupported')
                self.assertEqual(fields['wired_headset'],
                                 'reported_api_bool_coercion' if audio else 'default_false')
                self.assertEqual((values, status), self.sample(frozen_device, battery, audio))

    def test_failed_calls_keep_defaults_unverified(self):
        with patch.object(device.Path, 'exists', return_value=True), \
             patch.object(device.subprocess, 'run', side_effect=TimeoutError):
            values, status = device.sample_device_state()
        self.assertEqual(status, 'UNAVAILABLE')
        self.assertEqual(values['_field_provenance']['wired_headset'], 'default_false')
        self.assertEqual(values['_field_provenance']['battery_percentage'], 'unavailable')
