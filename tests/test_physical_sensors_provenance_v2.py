# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from pixel_physical import sensors as frozen_sensors
from traceform.streaming import physical_sensors_v2 as sensors


class TestPhysicalSensorsProvenanceV2(unittest.TestCase):
    def sample(self, adapter, gyro):
        raw = {'LSM6DSR Accelerometer': {'values': [0, 0, 9.8]},
               'TMD3719 Ambient Light': {'values': [20]}}
        if gyro is not None:
            raw['LSM6DSR Gyroscope'] = {'values': gyro}
        with patch.object(adapter.Path, 'exists', return_value=True), \
             patch.object(adapter.subprocess, 'run', return_value=SimpleNamespace(
                 returncode=0, stdout=json.dumps(raw))):
            return adapter.sample_hardware_sensors()

    def test_measured_zero_differs_from_missing_and_preserves_v1_numbers(self):
        for gyro, status in ((None, 'imputed_zero'), ([0, 0, 0], 'observed')):
            with self.subTest(gyro=gyro):
                motion, environment, ma, ea = self.sample(sensors, gyro)
                fields = motion.pop('_field_provenance')
                env_fields = environment.pop('_field_provenance')
                self.assertEqual(fields['gyro_raw'], status)
                self.assertEqual(fields['accel_raw'], 'observed_rounded')
                self.assertEqual(fields['orientation_degrees'], 'unavailable')
                self.assertEqual(fields['motion_energy'],
                                 'derived_with_imputed_input' if gyro is None else 'derived')
                self.assertEqual(env_fields['pressure_hpa'], 'unavailable')
                self.assertEqual(env_fields['ambient_light_lux'], 'observed_rounded')
                self.assertEqual((motion, environment, ma, ea), self.sample(frozen_sensors, gyro))

    def test_missing_adapter_does_not_fabricate_field_evidence(self):
        with patch.object(sensors.Path, 'exists', return_value=False):
            motion, environment, ma, ea = sensors.sample_hardware_sensors()
        self.assertEqual((ma, ea), ('UNAVAILABLE', 'UNAVAILABLE'))
        self.assertNotIn('_field_provenance', motion)
        self.assertNotIn('_field_provenance', environment)
