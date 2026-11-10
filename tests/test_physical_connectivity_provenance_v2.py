# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from traceform.streaming import physical_connectivity_v2 as connectivity


class TestPhysicalConnectivityProvenanceV2(unittest.TestCase):
    def sample(self, replies):
        with patch.object(connectivity, 'get_device_salt', return_value=b'synthetic'), \
             patch.object(connectivity, 'hash_identifier', return_value='synthetic_hmac'), \
             patch.object(connectivity, 'hash_environment_set', return_value=None), \
             patch.object(connectivity.subprocess, 'run', side_effect=replies):
            return connectivity.sample_connectivity()

    def test_empty_scan_is_distinct_from_failed_scan(self):
        reply = lambda value: SimpleNamespace(returncode=0, stdout=json.dumps(value))
        values, _ = self.sample([reply({'bssid': 'synthetic', 'rssi': -40}), reply([]), reply([])])
        fields = values['_field_provenance']
        self.assertEqual(fields['wifi_rssi'], 'reported_by_api')
        self.assertEqual(fields['wifi_fingerprint'], 'derived_hmac')
        self.assertEqual(fields['wifi_ap_count'], 'derived_from_api_scan')
        self.assertEqual(fields['cellular_dbm'], 'unavailable')
        values, _ = self.sample([TimeoutError(), TimeoutError(), TimeoutError()])
        self.assertEqual(values['_field_provenance']['wifi_ap_count'], 'default_zero')
        self.assertEqual(values['_field_provenance']['wifi_connected'], 'default_false')

    def test_salt_error_keeps_fields_unverified(self):
        with patch.object(connectivity, 'get_device_salt', side_effect=RuntimeError('synthetic error')), \
             patch.object(connectivity.subprocess, 'run') as run:
            values, status = connectivity.sample_connectivity()
        self.assertEqual(status, 'DEGRADED_FINGERPRINT_KEY_ERROR')
        self.assertEqual(values['_field_provenance']['wifi_fingerprint'], 'unavailable')
        run.assert_not_called()
