# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import unittest

from traceform.representation.normalizer import MultimodalNormalizer


class TestNormalizerGnssSources(unittest.TestCase):
    def test_legacy_and_corrected_semantics_have_distinct_bindings(self):
        from traceform.representation.provenance import content_digest
        normalizer = MultimodalNormalizer()
        normalizer.fit_discovery_window([{'place': {'latitude': 1}}])
        current = normalizer.to_dict()
        self.assertEqual(current['feature_extraction_version'], 2)
        legacy = dict(current)
        del legacy['feature_extraction_version']
        restored = MultimodalNormalizer.from_dict(legacy)
        self.assertEqual(restored.to_dict(), legacy)
        observation = {'place': {'latitude': 1, 'visible_satellites': 0, 'gnss_sat_count': 12}}
        self.assertEqual(restored.extract_raw_features(observation)[0]['gnss_visible_satellite_density'], 12)
        self.assertEqual(MultimodalNormalizer.from_dict(current).extract_raw_features(observation)[0]
                         ['gnss_visible_satellite_density'], 0)
        self.assertNotEqual(content_digest(current), content_digest(legacy))
        current['feature_extraction_version'] = 3
        with self.assertRaisesRegex(ValueError, 'extraction version'):
            MultimodalNormalizer.from_dict(current)

    def test_reported_zero_precedes_alias_and_proxy(self):
        observation = {'place': {'latitude': 1, 'visible_satellites': 0, 'gnss_sat_count': 12,
                                'mean_carrier_to_noise_ratio': 0, 'gnss_cn0_dbhz': 32,
                                'position_dilution_of_precision': 0, 'pdop': 3,
                                'doppler_velocity_drift': 0, 'gnss_speed_mps': 5},
                       'motion': {'stationary_likelihood': 0, 'motion_energy': 1}}
        values, _ = MultimodalNormalizer().extract_raw_features(observation)
        self.assertEqual(values['gnss_visible_satellite_density'], 0)
        self.assertEqual(values['gnss_mean_carrier_to_noise_ratio'], 0)
        self.assertEqual(values['gnss_position_dilution_of_precision'], 0.5)
        self.assertEqual(values['gnss_doppler_velocity_drift'], 0)

    def test_absent_primary_retains_alias_and_missing_proxy_behavior(self):
        values, _ = MultimodalNormalizer().extract_raw_features({'place': {
            'visible_satellites': None, 'gnss_sat_count': 7,
            'mean_carrier_to_noise_ratio': None, 'gnss_cn0_dbhz': 20,
            'position_dilution_of_precision': None, 'pdop': 4,
            'doppler_velocity_drift': None, 'gnss_speed_mps': 2}})
        self.assertEqual([values[key] for key in ('gnss_visible_satellite_density',
                         'gnss_mean_carrier_to_noise_ratio', 'gnss_position_dilution_of_precision',
                         'gnss_doppler_velocity_drift')], [7, 20, 4, 2])
        values, _ = MultimodalNormalizer().extract_raw_features({'place': {'latitude': 1}})
        self.assertEqual(values['gnss_visible_satellite_density'], 16)
        self.assertEqual(values['gnss_mean_carrier_to_noise_ratio'], 34)
