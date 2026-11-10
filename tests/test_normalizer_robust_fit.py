# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import datetime
import json
import random
import unittest

from traceform.representation.normalizer import (CONTINUOUS_FEATURE_NAMES, MultimodalNormalizer,
                                                 NormalizationParams)

FIXED_PHASE_FEATURES = ('circadian_sin_phase', 'circadian_cos_phase', 'infradian_weekly_phase')


def observations(count=600, seed=5):
    """Pressure sits within ~0.05 hPa for 85% of the window, then drifts upward: the long stable
    stretch makes the IQR deceptively small for everything that follows."""
    rng = random.Random(seed)
    start = datetime.datetime(2026, 10, 1, 0, 0, 0, tzinfo=datetime.timezone.utc)
    frames = []
    for i in range(count):
        pressure = 913.0 + rng.uniform(-0.05, 0.05) if i < count * 17 // 20 else 913.0 + (i - count * 17 // 20) * 0.05
        frames.append({
            'timestamp_utc': (start + datetime.timedelta(seconds=30 * i)).isoformat().replace('+00:00', 'Z'),
            'motion': {'accel_magnitude': 9.8 + rng.uniform(-0.2, 0.2), 'motion_energy': rng.uniform(0, 2),
                       'stationary_likelihood': rng.uniform(0, 1)},
            'environment': {'pressure_hpa': pressure, 'device_temp_c': 30 + rng.uniform(-2, 2),
                            'ambient_light_lux': rng.uniform(0, 500)},
            'device_relation': {'battery_percentage': 80 - i // 20, 'plugged': 'UNPLUGGED', 'status': 'DISCHARGING',
                                'current_ua': rng.randint(-900000, -100000), 'voltage_mv': 3900},
        })
    return frames


class TestRobustFit(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.frames = observations()
        cls.v1 = MultimodalNormalizer().fit_discovery_window(cls.frames, 'legacy')
        cls.v2 = MultimodalNormalizer().fit_robust_window(cls.frames, 'candidate')

    def test_schema_is_unchanged_so_consumers_need_no_new_fields(self):
        self.assertEqual(set(self.v2.to_dict()), set(self.v1.to_dict()))
        self.assertEqual(NormalizationParams.from_dict(json.loads(json.dumps(self.v2.to_dict()))), self.v2)
        self.assertEqual(self.v2.feature_names, list(CONTINUOUS_FEATURE_NAMES))

    def test_scale_never_shrinks_below_the_legacy_iqr_for_fitted_features(self):
        for name in CONTINUOUS_FEATURE_NAMES:
            if name in FIXED_PHASE_FEATURES:
                continue
            self.assertGreaterEqual(self.v2.iqrs[name] + 1e-6, self.v1.iqrs[name], name)
            self.assertEqual(self.v2.medians[name], self.v1.medians[name], name)

    def test_short_stable_start_no_longer_pins_a_drifting_channel(self):
        later = [MultimodalNormalizer().extract_raw_features(frame)[0]['env_pressure_hpa'] for frame in self.frames[-100:]]
        def pinned(params):
            scaled = [(value - params.medians['env_pressure_hpa']) / params.iqrs['env_pressure_hpa'] for value in later]
            return sum(abs(value) >= 10 for value in scaled) / len(scaled)
        self.assertGreater(pinned(self.v1), 0.5)
        self.assertEqual(pinned(self.v2), 0.0)

    def test_time_phases_use_fixed_analytic_scales(self):
        for name in FIXED_PHASE_FEATURES:
            self.assertEqual(self.v2.medians[name], 0.0, name)
            self.assertAlmostEqual(self.v2.iqrs[name], 2 ** 0.5, places=5, msg=name)

    def test_constant_features_keep_the_unit_scale_fallback(self):
        self.assertEqual(self.v2.iqrs['device_is_plugged'], 1.0)
        self.assertEqual(self.v2.iqrs['audio_rms_loudness'], 1.0)

    def test_description_records_the_policy_and_window_size(self):
        self.assertIn('robust_range_v2', self.v2.fit_window_description)
        self.assertEqual(self.v2.fitted_observation_count, len(self.frames))

    def test_empty_window_is_rejected(self):
        with self.assertRaises(ValueError):
            MultimodalNormalizer().fit_robust_window([], 'empty')

    def test_extraction_version_is_carried_into_the_parameters(self):
        self.assertEqual(self.v2.feature_extraction_version, MultimodalNormalizer().feature_extraction_version)


if __name__ == '__main__':
    unittest.main()
