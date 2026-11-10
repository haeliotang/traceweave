# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from traceform.streaming.physical_acquisition_v2 import acquire_observation_frame


class TestPhysicalLocationV2(unittest.TestCase):
    def test_frame_location_uses_server_bounded_updates(self):
        with patch('pixel_physical.location.Path.exists', return_value=True), \
             patch('pixel_physical.location.subprocess.run', return_value=SimpleNamespace(
                 returncode=0, stdout=json.dumps({'latitude': 1, 'longitude': 2, 'elapsedMs': 100, 'provider': 'gps'}))) as run, \
             patch('traceform.streaming.physical_sensors_v2.sample_hardware_sensors', return_value=({}, {}, 'UNAVAILABLE', 'UNAVAILABLE')), \
             patch('traceform.streaming.physical_connectivity_v2.sample_connectivity', return_value=({}, 'UNAVAILABLE')), \
             patch('traceform.streaming.physical_device_state_v2.sample_device_state', return_value=({}, 'UNAVAILABLE')):
            frame = acquire_observation_frame(include_audio=False)
            self.assertEqual(run.call_args.args[0][-2:], ['-r', 'updates'])
            self.assertGreater(run.call_args.kwargs['timeout'], 30)
            self.assertEqual(frame.place['location_age_ms'], 100)

    def test_streamed_replies_choose_latest_and_label_fallback(self):
        from traceform.streaming.physical_location_v2 import sample_location
        first = {'latitude': 1, 'longitude': 2, 'elapsedMs': 10}
        latest = {'latitude': 3, 'longitude': 4, 'elapsedMs': 5}
        with patch('pixel_physical.location.Path.exists', return_value=True), \
             patch('pixel_physical.location.subprocess.run', return_value=SimpleNamespace(
                 returncode=0, stdout=json.dumps(first) + '\n' + json.dumps(latest))):
            place, status = sample_location()
            self.assertEqual(status, 'AVAILABLE')
            self.assertEqual(place['latitude'], 3)
            self.assertEqual(place['location_request'], 'updates')
        with patch('pixel_physical.location.Path.exists', return_value=True), \
             patch('pixel_physical.location.subprocess.run', side_effect=[
                 SimpleNamespace(returncode=0, stdout=''),
                 SimpleNamespace(returncode=0, stdout=json.dumps(first))]):
            place, status = sample_location()
            self.assertEqual(status, 'AVAILABLE')
            self.assertEqual(place['location_request'], 'last')

    def test_stale_invalid_or_unknown_age_is_unavailable(self):
        from traceform.streaming.physical_location_v2 import sample_location
        for payload in [{'latitude': 1, 'longitude': 2, 'elapsedMs': 120001},
                        {'latitude': 1, 'longitude': 2},
                        {'latitude': 91, 'longitude': 2, 'elapsedMs': 0},
                        {'latitude': 1, 'longitude': 2, 'elapsedMs': -1}]:
            with self.subTest(payload=payload), \
                 patch('pixel_physical.location.Path.exists', return_value=True), \
                 patch('pixel_physical.location.subprocess.run', return_value=SimpleNamespace(
                     returncode=0, stdout=json.dumps(payload))):
                place, status = sample_location()
                self.assertEqual(status, 'TEMPORARILY_UNAVAILABLE')
                self.assertNotIn('latitude', place)

    def test_wait_time_counts_toward_freshness_bound(self):
        from traceform.streaming.physical_location_v2 import sample_location
        with patch('pixel_physical.location.Path.exists', return_value=True), \
             patch('traceform.streaming.physical_location_v2.time.monotonic', side_effect=[0, 30, 30, 60]), \
             patch('pixel_physical.location.subprocess.run', return_value=SimpleNamespace(
                 returncode=0, stdout=json.dumps({'latitude': 1, 'longitude': 2, 'elapsedMs': 100000}))):
            place, status = sample_location()
            self.assertEqual(status, 'TEMPORARILY_UNAVAILABLE')
            self.assertNotIn('latitude', place)
