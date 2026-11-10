# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import unittest
from unittest.mock import patch

import numpy as np
from pixel_physical.audio_features import extract_features_from_pcm
from traceform.streaming import physical_audio_v2 as audio
from traceform.streaming.physical_observation_v2 import acquire_observation_frame


class TestPhysicalAudioProvenanceV2(unittest.TestCase):
    def test_empty_silence_and_signal_preserve_numbers_but_distinguish_sources(self):
        for samples in (np.array([], dtype=np.float32), np.zeros(32, dtype=np.float32),
                        np.sin(np.arange(64, dtype=np.float32)) * 0.1):
            with self.subTest(length=len(samples)):
                features = audio.extract_with_provenance(samples)
                fields = features.pop('_field_provenance')
                self.assertEqual(features, extract_features_from_pcm(samples))
                self.assertEqual(fields['rms_loudness'],
                                 'derived_from_pcm' if len(samples) else 'empty_pcm_default')
                self.assertEqual(fields['speech_likelihood'],
                                 'heuristic_from_pcm' if len(samples) else 'empty_pcm_default')

    def test_audio_field_evidence_moves_to_observation_provenance(self):
        features = audio.extract_with_provenance(np.zeros(32, dtype=np.float32))
        with patch('traceform.streaming.physical_sensors_v2.sample_hardware_sensors',
                   return_value=({}, {}, 'UNAVAILABLE', 'UNAVAILABLE')), \
             patch('traceform.streaming.physical_connectivity_v2.sample_connectivity',
                   return_value=({}, 'UNAVAILABLE')), \
             patch('traceform.streaming.physical_device_state_v2.sample_device_state',
                   return_value=({}, 'UNAVAILABLE')), \
             patch.object(audio, 'sample_audio_context', return_value=(features, 'AVAILABLE')):
            observation = acquire_observation_frame(include_location=False)
        self.assertNotIn('_field_provenance', observation.audio_context)
        self.assertEqual(observation.feature_provenance['audio_context']['fields']['rms_loudness'],
                         'derived_from_pcm')

    def test_missing_microphone_returns_no_fabricated_pcm_evidence(self):
        with patch.object(audio.Path, 'exists', return_value=False):
            features, status = audio.sample_audio_context()
        self.assertEqual(status, 'UNAVAILABLE')
        self.assertNotIn('_field_provenance', features)
