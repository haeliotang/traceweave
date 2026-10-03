import copy
import datetime
import math
import random
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import parity_support as parity
from traceform.representation.normalizer import MultimodalNormalizer, NormalizationParams

NAMES = [
    'physio_ballisto_tremor_energy', 'rf_spectral_penetration_ratio', 'neuromotor_tremor_spectral_ratio',
    'wifi_rssi_distribution_skewness', 'phase_space_correlation_dimension', 'wifi_rssi_distribution_kurtosis',
    'photometric_spectral_warmth_index', 'device_structural_resonance_energy',
    'ultrasonic_proximity_micro_doppler', 'gnss_doppler_velocity_drift', 'thermal_soc_to_skin_gradient',
    'thermal_entropy_production_rate', 'fractal_hurst_exponent', 'multiscale_approximate_entropy',
    'topological_betti_number_proxy',
]

# 10 ** -digits of the outermost Python round() of each output. The direct-override branches of
# physio_ballisto_tremor_energy and neuromotor_tremor_spectral_ratio are not rounded, but their computed
# branches are, so one rounding unit is the allowance for both.
QUANTUM = {
    'physio_ballisto_tremor_energy': 10 ** -4,
    'rf_spectral_penetration_ratio': 10 ** -3,
    'neuromotor_tremor_spectral_ratio': 10 ** -3,
    'wifi_rssi_distribution_skewness': 10 ** -3,
    'phase_space_correlation_dimension': 10 ** -3,
    'wifi_rssi_distribution_kurtosis': 10 ** -3,
    'photometric_spectral_warmth_index': 10 ** -3,
    'device_structural_resonance_energy': 10 ** -3,
    'ultrasonic_proximity_micro_doppler': 10 ** -3,
    'gnss_doppler_velocity_drift': 10 ** -2,
    'thermal_soc_to_skin_gradient': 10 ** -2,
    'thermal_entropy_production_rate': 10 ** -3,
    'fractal_hurst_exponent': 10 ** -3,
    'multiscale_approximate_entropy': 10 ** -3,
    'topological_betti_number_proxy': 10 ** -2,
}

JAVA_FILES = ['ReferenceSignalFeatures.java']
ORIGINAL_GENERATE = parity.generate_sequence

# Runs the legacy version-1 extractor (falsy direct values fall through) against a no-argument Java wrapper.
V1_WRAPPER = '''package org.traceform.edge;

import java.util.Map;

final class ReferenceSignalFeaturesV1 {
    static final String[] NAMES = ReferenceSignalFeatures.NAMES;
    private final ReferenceSignalFeatures inner = new ReferenceSignalFeatures(1);
    void reset() { inner.reset(); }
    double[] extract(Map<String, Object> obs) { return inner.extract(obs); }
}
'''

# Coordinate pairs (accel, temp, battery fraction, log lux, stationary) whose squared distance sits on a
# rounding boundary of the radius-3 recurrence test: CPython's compensated sum() and a plain left-to-right
# sum classify them differently, so only a faithful port of builtin sum() matches.
SUM_WITNESSES = [
    ([9.18, 24.2, 0.5, 4.71, 0.55], [6.38, 24.2, 0.9, 3.71, 0.55]),
    ([9.83, 31.5, 0.55, 3.14, 0.26], [9.83, 33.5, 0.55, 5.34, 0.66]),
    ([9.72, 37.0, 0.6, 5.59, 0.86], [11.72, 37.0, 0.2, 3.39, 0.86]),
    ([9.95, 25.6, 0.78, 2.66, 0.29], [10.95, 25.6, 0.78, 5.46, 0.69]),
    ([9.02, 24.2, 0.89, 0.33, 0.38], [7.38, 26.2, 0.89, 1.85, 0.38]),
    ([10.11, 30.3, 0.68, 7.09, 0.07], [10.51, 30.3, 0.68, 4.17, 0.63]),
    ([9.25, 38.6, 0.76, 3.07, 0.33], [8.85, 38.6, 0.76, 0.15, 0.89]),
]


def _version1_normalizer():
    params = NormalizationParams(feature_names=[], medians={}, iqrs={}, fitted_observation_count=0,
                                 fit_window_description='parity', feature_extraction_version=1)
    return MultimodalNormalizer(params)


def _repeating_timestamps(generate):
    """Wrap a generator so that about one frame in six repeats the previous frame's timestamp.

    The generated sequences never repeat a timestamp, which is the one situation the stateful outputs
    treat specially: the buffers are updated or replaced in place instead of advancing. A frame whose
    predecessor lacks a timestamp repeats the absence.
    """

    def wrapper(seed, frames=300, profile='full', paths=None):
        sequence = generate(seed, frames, profile, paths)
        rng = random.Random(seed * 7919 + len(sequence))
        previous = None
        for frame in sequence:
            if '__reset' in frame:
                previous = None
                continue
            if previous is not None and rng.random() < 1 / 6:
                if 'timestamp_utc' in previous:
                    frame['timestamp_utc'] = previous['timestamp_utc']
                else:
                    frame.pop('timestamp_utc', None)
            previous = frame
        return sequence

    return wrapper


def _resetting_after_python_errors(make_normalizer, generate):
    """Wrap a generator so that the sequence mirrors the Python reference's own recovery.

    When extract_raw_features raises (another output's code, e.g. haversine on an infinite latitude), the
    reference calls reset_state() and yields no row. The stateful Java class does not raise there, so it
    must be reset at the same point; a reset marker after the failing frame does exactly that and leaves
    the Python run unchanged.
    """

    def wrapper(seed, frames=300, profile='full', paths=None):
        sequence = generate(seed, frames, profile, paths)
        normalizer = make_normalizer()
        patched = []
        for frame in sequence:
            patched.append(frame)
            if '__reset' in frame:
                normalizer.reset_state()
                continue
            try:
                normalizer.extract_raw_features(copy.deepcopy(frame))
            except Exception:
                normalizer.reset_state()
                patched.append({'__reset': True})
        return patched

    return wrapper


def _stamp(step):
    moment = datetime.datetime(2026, 10, 2, 6, 0, 0, tzinfo=datetime.timezone.utc) + datetime.timedelta(
        seconds=30 * step)
    return moment.isoformat(timespec='microseconds').replace('+00:00', 'Z')


def _frame(step, accel=9.8, temp=30.0, battery=50, log_lux=0.0, stat=0.5, **sections):
    """Directed frame 30 s * step into the day; the five keywords are the recurrence-buffer coordinates."""
    frame = {'timestamp_utc': _stamp(step),
             'motion': {'accel_magnitude': accel, 'stationary_likelihood': stat},
             'environment': {'device_temp_c': temp, 'ambient_light_lux': math.expm1(log_lux)},
             'device_relation': {'battery_percentage': battery}}
    for name, values in sections.items():
        frame.setdefault(name, {}).update(values)
    return frame


def _without_timestamp(frame):
    del frame['timestamp_utc']
    return frame


def _plain_sum(values):
    total = 0.0
    for value in values:
        total = total + value
    return total


def _witness_radii(first, second, summing):
    squares = [(a - b) ** 2 for a, b in zip(first, second)]
    distance = math.sqrt(summing(squares))
    return distance < 1.0, distance < 3.0


def _directed_sequence():
    """Hand-built frames on the thresholds the random sequences hit only by luck, or never."""
    blocks = []
    # Recurrence radii: a pair 0.5 apart, a pair exactly 1.0 apart (inside radius 3 only) and a pair
    # exactly 3.0 apart (inside neither); the groups are far from each other.
    blocks.append([_frame(step, accel=accel) for step, accel in enumerate([10.0, 10.5, 30.0, 31.0, 50.0, 53.0])])
    # Stationary likelihood exactly on and just under the 0.8 threshold, identical points.
    blocks.append([_frame(step, stat=0.8, motion={'motion_energy': 0.5}) for step in range(8)])
    blocks.append([_frame(step, stat=0.79, motion={'motion_energy': 0.5}) for step in range(8)])
    # Negative motion energy drives the tremor ratio denominator onto its floor.
    blocks.append([_frame(step, stat=0.9, motion={'motion_energy': -7.5}) for step in range(2)])
    # Wi-Fi band edge.
    blocks.append([_frame(step, connectivity={'wifi_connected': True, 'wifi_rssi': -60.0,
                                              'wifi_frequency_mhz': mhz})
                   for step, mhz in enumerate([4899.0, 4900.0, 4901.0])])
    # Battery impedance from successive current/voltage readings: a current step of exactly 40000 uA
    # (both signs), one microampere under it, and unclamped results.
    readings = [(1000000.0, 4000.0), (1040000.0, 4100.0), (1079999.0, 4150.0), (-1000000.0, 4000.0),
                (-960000.0, 3990.0), (100000.0, 4000.0), (200000.0, 4020.0)]
    blocks.append([_frame(step, device_relation={'current_ua': current, 'voltage_mv': voltage})
                   for step, (current, voltage) in enumerate(readings)])
    # Joule heat on its 5 W cap at a temperature where the entropy rate cap does not hide it.
    blocks.append([_frame(0, temp=300.0, device_relation={'current_ua': 1e7, 'voltage_mv': 4000.0})])
    # Repeated timestamps: in-place replacement of the buffers, state not advanced.
    steps = [0, 0, 1, 1, 2, 2, 3, 4, 5, 5, 6, 7]
    blocks.append([_frame(step, accel=10.0 + 0.3 * k,
                          device_relation={'current_ua': 1000000.0 + 55000.0 * k * (-1) ** k,
                                           'voltage_mv': 4000.0 + 17.0 * k})
                   for k, step in enumerate(steps)])
    # The same timestamp on both sides of a reset: nothing may survive the reset.
    blocks.append([_frame(5, device_relation={'current_ua': 1000000.0, 'voltage_mv': 4000.0}),
                   {'__reset': True},
                   _frame(5, device_relation={'current_ua': 1200000.0, 'voltage_mv': 4100.0}),
                   _frame(6, device_relation={'current_ua': 1500000.0, 'voltage_mv': 4050.0})])
    # Missing timestamps share the buffer slot of time 0.0, which a 1970-01-01 stamp also maps to.
    epoch = _frame(0, accel=10.3)
    epoch['timestamp_utc'] = '1970-01-01T00:00:00Z'
    blocks.append([_without_timestamp(_frame(0, accel=10.0)), epoch, _without_timestamp(_frame(0, accel=10.6)),
                   _without_timestamp(_frame(0, accel=10.9)), _frame(1, accel=11.2), _frame(2, accel=11.5),
                   _frame(3, accel=11.8), _without_timestamp(_frame(0, accel=12.1))])
    # Differences that overflow to infinity: float ** 2 of an infinite operand is infinity, not an error.
    blocks.append([_frame(step, accel=1e308 if step % 2 == 0 else -1e308) for step in range(7)])
    # Compensated-sum witnesses: the pair under test, a pair 0.5 apart and a pair 2.0 apart, all far from
    # one another, so the last frame's dimension reads off both radius counts.
    witness_blocks = []
    for first, second in SUM_WITNESSES:
        coordinates = [first, second] + [[accel, 30.0, 0.5, 0.0, 0.5] for accel in (30.0, 30.5, 50.0, 52.0)]
        witness_blocks.append([_frame(step, accel=c[0], temp=c[1], battery=round(c[2] * 100), log_lux=c[3], stat=c[4])
                               for step, c in enumerate(coordinates)])
    sequence = []
    for block in blocks:
        sequence.append({'__reset': True})
        sequence.extend(block)
    witness_positions = []
    for block in witness_blocks:
        sequence.append({'__reset': True})
        sequence.extend(block)
        witness_positions.append(len(sequence) - 1)
    return sequence, witness_positions


class TestReferenceSignalFeatures(unittest.TestCase):
    def run_group(self, generate, **options):
        if not parity.java_available():
            self.skipTest('Java compiler/runtime unavailable')
        with mock.patch.object(parity, 'generate_sequence', generate):
            return parity.check_group('ReferenceSignalFeatures', JAVA_FILES, NAMES, quantum=QUANTUM, **options)

    def assert_parity(self, report, minimum=None):
        self.assertEqual(report['problems'], [])
        self.assertEqual(report['java_only_errors'], 0)
        if minimum is not None:
            self.assertGreater(min(report['compared'].values()), minimum)

    def test_matches_python_reference_on_generated_sequences(self):
        report = self.run_group(_resetting_after_python_errors(MultimodalNormalizer, ORIGINAL_GENERATE))
        self.assert_parity(report, 1500)

    def test_matches_python_reference_with_repeated_timestamps(self):
        report = self.run_group(_resetting_after_python_errors(
            MultimodalNormalizer, _repeating_timestamps(ORIGINAL_GENERATE)))
        self.assert_parity(report, 1500)

    def test_matches_python_reference_on_directed_boundary_sequences(self):
        sequence, _ = _directed_sequence()
        report = self.run_group(lambda seed, frames=300, profile='full', paths=None: sequence,
                                seeds=(1,), profiles=('full',))
        self.assert_parity(report, 50)

    def test_sum_witnesses_separate_compensated_from_plain_summation(self):
        for first, second in SUM_WITNESSES:
            self.assertNotEqual(_witness_radii(first, second, sum), _witness_radii(first, second, _plain_sum))
        # The last frame of a witness block reads off both radius counts: the pair under test, a pair 0.5
        # apart (inside radius 1) and a pair 2.0 apart (inside radius 3 only).
        sequence, positions = _directed_sequence()
        rows = parity.run_python(sequence, NAMES)
        index = NAMES.index('phase_space_correlation_dimension')
        expected = []
        for first, second in SUM_WITNESSES:
            inside_one, inside_three = _witness_radii(first, second, sum)
            n1 = 1 + inside_one
            n2 = 2 + inside_three
            expected.append(round(math.log(float(n2) / float(n1)) / math.log(3.0), 3))
        self.assertEqual([rows[position][index] for position in positions], expected)

    def test_overflow_raises_like_python(self):
        if not parity.java_available():
            self.skipTest('Java compiler/runtime unavailable')
        sequences = [
            [{'__reset': True}, _frame(0), _frame(1, device_relation={'current_ua': 1e200}), _frame(2)],
            [{'__reset': True}] + [_frame(step, accel=10.0 + step) for step in range(5)]
            + [_frame(5, accel=1e200), _frame(6)],
        ]
        with tempfile.TemporaryDirectory() as directory:
            for number, sequence in enumerate(sequences):
                path = Path(directory) / f'overflow-{number}.jsonl'
                parity.write_sequence(sequence, path)
                expected = parity.run_python(sequence, NAMES)
                names, actual = parity.run_java('ReferenceSignalFeatures', JAVA_FILES, path, Path(directory))
                self.assertEqual(names, NAMES)
                failing = [i for i, (frame, row) in enumerate(zip(sequence, expected))
                           if row is None and '__reset' not in frame]
                self.assertEqual(len(failing), 1)
                # Python raises OverflowError on float ** 2 overflow; Java must raise as well, not return infinity.
                self.assertEqual(actual[failing[0]], 'error')
                result = parity.compare(NAMES, sequence, expected, actual, QUANTUM)
                self.assertEqual(result['problems'], [])

    def test_matches_version1_python_reference(self):
        if not parity.java_available():
            self.skipTest('Java compiler/runtime unavailable')
        seeds = range(1, 9)
        generate = _resetting_after_python_errors(_version1_normalizer, ORIGINAL_GENERATE)
        with tempfile.TemporaryDirectory() as directory:
            wrapper = Path(directory) / 'ReferenceSignalFeaturesV1.java'
            wrapper.write_text(V1_WRAPPER)
            with mock.patch.object(parity, 'MultimodalNormalizer', _version1_normalizer), \
                    mock.patch.object(parity, 'generate_sequence', generate):
                report = parity.check_group('ReferenceSignalFeaturesV1', JAVA_FILES + [str(wrapper)], NAMES,
                                            seeds=seeds, profiles=('hostile',), frames=600, quantum=QUANTUM)
                sequences = [parity.generate_sequence(seed, 600, 'hostile') for seed in seeds]
                version1 = [parity.run_python(sequence, NAMES) for sequence in sequences]
            version2 = [parity.run_python(sequence, NAMES) for sequence in sequences]
        self.assert_parity(report)
        index = NAMES.index('gnss_doppler_velocity_drift')
        # The sequences must reach the version-1-only branch, otherwise the comparison proves nothing.
        differing = sum(1 for one, two in zip(version1, version2) for a, b in zip(one, two)
                        if a is not None and b is not None and a[index] != b[index])
        self.assertGreater(differing, 5)

    def test_every_output_takes_several_values(self):
        distinct = parity.exercise(NAMES)
        for name in NAMES:
            self.assertGreater(distinct[name], 3, name)


if __name__ == '__main__':
    unittest.main()
