import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from traceform.streaming.physical_observation_v2 import PhysicalObservation, acquire_observation_frame
from traceform.streaming.physical_ledger_v2 import append_observation, read_recent_observations


class TestPhysicalObservationProvenanceV2(unittest.TestCase):
    def test_legacy_frames_remain_without_provenance(self):
        frame = PhysicalObservation.from_dict({'observation_id': 'legacy'})
        self.assertEqual(frame.feature_provenance, {})

    def test_adapter_evidence_survives_rolling_roundtrip_without_claiming_measurement(self):
        with patch('traceform.streaming.physical_sensors_v2.sample_hardware_sensors', return_value=(
                {'gyro_raw': [0, 0, 0]}, {}, 'AVAILABLE', 'UNAVAILABLE')), \
             patch('traceform.streaming.physical_connectivity_v2.sample_connectivity', return_value=({}, 'UNAVAILABLE')), \
             patch('traceform.streaming.physical_device_state_v2.sample_device_state', return_value=(
                 {'wired_headset': False}, 'AVAILABLE')):
            frame = acquire_observation_frame(include_audio=False, include_location=False)
        evidence = frame.to_dict()['feature_provenance']
        self.assertEqual(evidence['motion']['status'], 'AVAILABLE')
        self.assertEqual(evidence['place']['status'], 'NOT_SAMPLED')
        self.assertEqual(evidence['audio_context']['status'], 'NOT_SAMPLED')
        self.assertEqual(evidence['motion']['measurement_scope'], 'adapter_return_not_per_field_direct_measurement')
        self.assertIn('missing_gyroscope_may_be_zero_filled', evidence['motion']['limitations'])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'observations.jsonl'
            append_observation(frame, path)
            restored = read_recent_observations(1, path)[0]
            self.assertEqual(restored.to_dict()['feature_provenance'], evidence)
