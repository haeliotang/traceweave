import unittest

from traceform.representation.normalizer import MultimodalNormalizer


class TestNormalizerGnssSources(unittest.TestCase):
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
