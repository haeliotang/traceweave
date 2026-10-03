import copy
import datetime
import random
import unittest
from unittest import mock

import parity_support as parity
from traceform.representation.normalizer import MultimodalNormalizer

# Group P: outputs that share one state core in extract_raw_features (thermal conduction, state
# residence, phase-space recurrence/RQA, kinematic curvature and torsion, Lyapunov dynamics, coherence).
NAMES = [
    'thermal_conduction_rate_dt', 'state_residence_persistence_hours', 'thermal_flux_acceleration_dt2',
    'phase_space_recurrence_density', 'kinematic_trajectory_curvature', 'phase_space_kinetic_velocity',
    'neuromotor_tap_recoil_intensity', 'kinematic_trajectory_torsion', 'thermodynamic_dissipation_ratio',
    'neuromotor_tremor_lyapunov_exponent', 'phase_space_information_flow_rate',
    'touch_downforce_pressure_derivative', 'rqa_recurrence_determinism_det',
    'rqa_laminarity_vertical_trapping', 'lempel_ziv_algorithmic_complexity', 'fisher_information_measure',
    'phase_space_trapping_time_seconds', 'bifurcation_stability_margin', 'phase_space_angular_momentum',
    'dissipative_lyapunov_dimension_ky', 'holo_cosmic_coherence_index',
]

# 10 ** -digits of the outermost round(..., digits) applied to each output in normalizer.py.
QUANTUM = {
    'thermal_conduction_rate_dt': 1e-3,
    'state_residence_persistence_hours': 1e-3,
    'thermal_flux_acceleration_dt2': 1e-5,
    'phase_space_recurrence_density': 1e-3,
    'kinematic_trajectory_curvature': 1e-4,
    'phase_space_kinetic_velocity': 1e-4,
    'neuromotor_tap_recoil_intensity': 1e-3,
    'kinematic_trajectory_torsion': 1e-4,
    'thermodynamic_dissipation_ratio': 1e-4,
    'neuromotor_tremor_lyapunov_exponent': 1e-4,
    'phase_space_information_flow_rate': 1e-4,
    'touch_downforce_pressure_derivative': 1e-3,
    'rqa_recurrence_determinism_det': 1e-3,
    'rqa_laminarity_vertical_trapping': 1e-3,
    'lempel_ziv_algorithmic_complexity': 1e-3,
    'fisher_information_measure': 1e-3,
    'phase_space_trapping_time_seconds': 1e-1,
    'bifurcation_stability_margin': 1e-3,
    'phase_space_angular_momentum': 1e-3,
    'dissipative_lyapunov_dimension_ky': 1e-3,
    'holo_cosmic_coherence_index': 1e-3,
}


_GENERATE_SEQUENCE = parity.generate_sequence


def _without_foreign_failures(frames):
    """Turn every frame on which the Python reference raises into a reset frame.

    In the hostile profile an infinite place.latitude/longitude makes haversine_km raise for the whole
    extract_raw_features call (the radius-of-gyration section, which no group-P output reads). The
    reference harness then resets the Python state, but the Java harness only resets after a Java
    exception, and this class has no reason to model another section's failure. Replacing exactly those
    frames by resets gives both sides the same state evolution; Python's result for them is None either way.
    """
    normalizer = MultimodalNormalizer()
    result = []
    for frame in frames:
        if '__reset' in frame:
            normalizer.reset_state()
            result.append(frame)
            continue
        try:
            normalizer.extract_raw_features(copy.deepcopy(frame))
            result.append(frame)
        except Exception:
            normalizer.reset_state()
            result.append({'__reset': True})
    return result


def generate_without_foreign_failures(*args, **kwargs):
    return _without_foreign_failures(_GENERATE_SEQUENCE(*args, **kwargs))


# Gaps (seconds) between valid timestamps in the boundary stress sequences. Every timestamp is a whole
# second, so a gap of exactly 2, 5, 300 or 3600 gives a float difference of exactly that value; 0 repeats
# a timestamp (the "same instant" paths); negative gaps move time backwards; 100000 exceeds 24 hours.
STRESS_GAPS = [0.0, 0.0, 1.0, 2.0, 2.0, 3.0, 4.0, 5.0, 5.0, 6.0, 30.0, 30.0, 30.0, 299.0, 300.0, 300.0, 301.0,
               3599.0, 3600.0, 3600.0, 3601.0, 7200.0, 100000.0, -1.0, -4000.0]
STRESS_SCALES = [1.0, 1.0, 1.0, 0.02, 0.1, 3.0, 10.0, 50.0]


def _scaled(vector, factor):
    if isinstance(vector, list) and len(vector) == 3 and all(isinstance(v, float) for v in vector):
        return [v * factor for v in vector]
    return vector


def stress_frames(frames, seed, profile):
    """Deterministically rewrite generated frames to reach what the shared generator cannot.

    The shared generator never repeats a timestamp, never produces a gap between 2 and 3 seconds or exactly
    on a threshold, keeps accelerations and rotations too small to reach the jerk/curvature/torsion clips,
    leaves list elements numeric, and never puts stationary_likelihood exactly on 0.8 or 0.9. This keeps
    the generator untouched and applies those rewrites in a derived sequence.
    """
    rng = random.Random(f'stress-{seed}-{profile}')
    clock = datetime.datetime(2026, 10, 2, 6, 0, 0, tzinfo=datetime.timezone.utc) + datetime.timedelta(
        seconds=rng.randint(0, 6 * 86400))
    result = []
    previous = None
    for original in frames:
        if '__reset' in original:
            result.append(original)
            previous = None
            continue
        frame = copy.deepcopy(original)
        stamp = frame.get('timestamp_utc')
        if isinstance(stamp, str) and stamp != 'not-a-time':
            clock += datetime.timedelta(seconds=rng.choice(STRESS_GAPS))
            frame['timestamp_utc'] = clock.isoformat(timespec='microseconds').replace('+00:00', 'Z')
        motion = frame.get('motion')
        if isinstance(motion, dict):
            if 'accel_raw' in motion:
                motion['accel_raw'] = _scaled(motion['accel_raw'], rng.choice(STRESS_SCALES))
                roll = rng.random()
                if roll < 0.03 and isinstance(motion['accel_raw'], list):
                    motion['accel_raw'] = motion['accel_raw'][:2]
                elif roll < 0.06 and isinstance(motion['accel_raw'], list) and len(motion['accel_raw']) == 3:
                    motion['accel_raw'][2] = None
            if 'gyro_raw' in motion:
                motion['gyro_raw'] = _scaled(motion['gyro_raw'], rng.choice([1.0, 1.0, 10.0, 50.0, 200.0]))
                if rng.random() < 0.03 and isinstance(motion['gyro_raw'], list) and len(motion['gyro_raw']) == 3:
                    motion['gyro_raw'][1] = 'abc'
            roll = rng.random()
            if roll < 0.06:
                motion['stationary_likelihood'] = 0.8
            elif roll < 0.09:
                motion['stationary_likelihood'] = 0.9
            if rng.random() < 0.25:
                motion['gyro_energy'] = 0.0
        digital = frame.get('digital_context')
        if isinstance(digital, dict) and rng.random() < 0.1:
            digital['screen_on_ratio'] = 0.0
        # Twin of the previous frame two degrees warmer: the two phase-space points are exactly 2.0 apart
        # (squared distance exactly 4.0), the recurrence-radius boundary.
        if previous is not None and rng.random() < 0.05:
            twin = copy.deepcopy(previous)
            twin['timestamp_utc'] = frame.get('timestamp_utc', twin.get('timestamp_utc'))
            if isinstance(previous.get('environment'), dict) and isinstance(twin.get('environment'), dict):
                previous['environment']['device_temp_c'] = 30.0
                twin['environment']['device_temp_c'] = 32.0
                frame = twin
        result.append(frame)
        previous = frame
    return result


def generate_boundary_stress(seed, frames=300, profile='full', paths=None):
    base = _GENERATE_SEQUENCE(seed, frames, profile, paths)
    return _without_foreign_failures(stress_frames(base, seed, profile))


class TestReferenceDynamicsFeatures(unittest.TestCase):
    def test_every_output_is_exercised_by_the_generated_sequences(self):
        # A port that matches only because an output stays at its default proves nothing.
        with mock.patch.object(parity, 'generate_sequence', generate_without_foreign_failures):
            distinct = parity.exercise(NAMES)
        for name in NAMES:
            self.assertGreater(distinct[name], 3, f'{name} takes only {distinct[name]} distinct values')

    def test_matches_python_reference_on_generated_sequences(self):
        if not parity.java_available():
            self.skipTest('Java compiler/runtime unavailable')
        with mock.patch.object(parity, 'generate_sequence', generate_without_foreign_failures):
            report = parity.check_group('ReferenceDynamicsFeatures', ['ReferenceDynamicsFeatures.java'], NAMES,
                                        quantum=QUANTUM)
        self.assertEqual(report['problems'], [])
        self.assertEqual(report['java_only_errors'], 0)
        self.assertGreater(min(report['compared'].values()), 1500)

    def test_matches_python_reference_at_thresholds_and_clips(self):
        if not parity.java_available():
            self.skipTest('Java compiler/runtime unavailable')
        with mock.patch.object(parity, 'generate_sequence', generate_boundary_stress):
            report = parity.check_group('ReferenceDynamicsFeatures', ['ReferenceDynamicsFeatures.java'], NAMES,
                                        quantum=QUANTUM)
            distinct = parity.exercise(NAMES)
        self.assertEqual(report['problems'], [])
        self.assertEqual(report['java_only_errors'], 0)
        self.assertGreater(min(report['compared'].values()), 1500)
        for name in NAMES:
            self.assertGreater(distinct[name], 3, f'{name} takes only {distinct[name]} distinct values')


if __name__ == '__main__':
    unittest.main()
