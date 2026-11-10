# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import datetime
import math
import random
import tempfile
import unittest
from pathlib import Path

import parity_support as parity

NAMES = [
    'touch_interaction_velocity', 'touch_interval_jitter', 'attention_fragmentation_entropy',
    'compute_power_dissipation_watts', 'touch_engagement_decay_factor',
    'neuromotor_microslip_angular_velocity', 'circadian_phase_disruption_index',
    'touch_contact_ellipse_eccentricity', 'neuromotor_fitts_law_throughput',
    'touch_biomotor_cadence_decay_slope', 'touch_path_tortuosity_ratio',
    'cpu_frequency_scaling_entropy', 'kernel_context_switch_velocity',
    'display_frame_pacing_jank_variance', 'io_wait_latency_proxy',
]

# 10 ** -digits of the outermost Python round() on each output. Outputs without a round() on every path
# (a direct safe_float passthrough or a constant) still have the rounded fallback as outermost round,
# and the unrounded paths are compared at 1e-9 whenever a rounding unit is not involved.
QUANTUM = {
    'touch_interaction_velocity': 1e-3,
    'touch_interval_jitter': 1e-3,
    'attention_fragmentation_entropy': 1e-3,
    'compute_power_dissipation_watts': 1e-3,
    'touch_engagement_decay_factor': 1e-3,
    'neuromotor_microslip_angular_velocity': 1e-3,
    'circadian_phase_disruption_index': 1e-3,
    'touch_contact_ellipse_eccentricity': 1e-3,
    'neuromotor_fitts_law_throughput': 1e-2,
    'touch_biomotor_cadence_decay_slope': 1e-3,
    'touch_path_tortuosity_ratio': 1e-3,
    'cpu_frequency_scaling_entropy': 1e-3,
    'kernel_context_switch_velocity': 1e-2,
    'display_frame_pacing_jank_variance': 1e-3,
    'io_wait_latency_proxy': 1e-3,
}


ABSENT = object()
UTC = datetime.timezone.utc
NEPAL = datetime.timezone(datetime.timedelta(hours=5, minutes=45))
NASTY = [None, 0, 0.0, -0.0, '', False, True, [], [0], {}, 'abc', '2.5', ' 7 ', float('nan'), float('inf'),
         -7.5, 1e12, 0.3, 1, 2.5, 5, 12]
DIGITAL_DIRECT = [
    'touch_interval_jitter', 'attention_fragmentation_entropy', 'neuromotor_microslip_angular_velocity',
    'circadian_phase_disruption_index', 'chrono_entropy', 'touch_contact_ellipse_eccentricity',
    'touch_eccentricity', 'neuromotor_fitts_law_throughput', 'fitts_throughput',
    'touch_biomotor_cadence_decay_slope', 'touch_path_tortuosity_ratio', 'cpu_frequency_scaling_entropy',
    'kernel_context_switch_velocity', 'display_frame_pacing_jank_variance', 'frame_jank_variance',
    'io_wait_latency_proxy',
]
COUNT_VALUES = [0, 0, 0, 1, 2, 0.33, 7.7, 16, 17.5, 60, -7.5, -40, 1e12, 'abc', '3.5', True, None, float('nan')]
RATIO_VALUES = [0, 0.05, 0.0500001, 0.2, 0.5, 0.5000001, 1, 'x', '0.7', True, None, -1, 1e12]
GYRO_VALUES = [[], [1], [1, 2], [1, 2, 3], [1, 2, 3, 4], [9, 0, 0], [0, 'x', None], [100, 100, 100], 'abc',
               [0, 0, 0], [0.5, 0.5, 0.5], [None, None, None], [1, 2, None], None, 0, {}]
LUX_VALUES = [0, 0, -0.5, 0.05, 0.3, 0.10517091807564749, 0.10517091807564771, 0.5, 10, 3000, 1e12, 'x', None]
STATIONARY_VALUES = [0.5, 0.79, 0.8, 0.89, 0.9, 0.91, 1.0, 1e12, -1, 'x', None]
FLAG_VALUES = [True, False, 0, 1, '', 'abc', [], [0], {}, 1.5, None, float('nan')]


def _stamp(moment, zone=UTC):
    text = moment.astimezone(zone).isoformat(timespec='microseconds')
    return text.replace('+00:00', 'Z')


def adversarial_frames(seed, count=400):
    """Frames built from small alphabets of boundary values: null-valued keys, repeated and backwards
    timestamps, epoch-zero and pre-epoch stamps, cos-phase threshold instants, resets, quiet frames."""
    rng = random.Random(seed)
    day = datetime.datetime(2026, 10, 2, tzinfo=UTC)
    boundary = math.acos(0.6) / (2 * math.pi) * 86400.0
    special = [day + datetime.timedelta(seconds=offset) for offset in (
        boundary - 0.2, boundary - 0.01, boundary + 0.01, boundary + 0.2, 28800, 28800.000001, 57600,
        57599.999999, 0, 86399.999999)]
    clock = day + datetime.timedelta(hours=rng.randint(0, 23))
    frames, previous = [{'__reset': True}], None
    for _ in range(count):
        if rng.random() < 0.04:
            frames.append({'__reset': True})  # the next frame may repeat the pre-reset timestamp
            if previous is not None and rng.random() < 0.6:
                # A touch frame repeating the pre-reset timestamp, then a quiet frame a minute later: the
                # decay depends on whether reset() cleared both _last_ts_seconds and _last_touch_ts.
                frames.append({'timestamp_utc': previous, 'digital_context': {'app_switch_rate': 2}})
                clock = max(clock, datetime.datetime.fromisoformat(previous.replace('Z', '+00:00')))
                clock += datetime.timedelta(seconds=60)
                frames.append({'timestamp_utc': _stamp(clock), 'digital_context': {
                    'app_switch_rate': 0, 'unlock_burst_count': 0, 'touch_interaction_velocity': 0,
                    'screen_on_ratio': 0}})
        frame, dig, dev, mot, env = {}, {}, {}, {}, {}
        roll = rng.random()
        if roll < 0.10 and previous is not None:
            stamp = previous  # a repeated timestamp
        elif roll < 0.15:
            stamp = rng.choice(['1970-01-01T00:00:00Z', '1969-12-31T23:59:00Z', '1969-12-31T23:59:30Z'])
        elif roll < 0.30:
            stamp = _stamp(rng.choice(special))
        elif roll < 0.38:
            stamp = rng.choice([ABSENT, 'x', '', None, 123, True, [], 'garbage-Z'])
        elif roll < 0.46:
            clock += datetime.timedelta(seconds=rng.choice([0, 30, 599, 601, 3600]))
            stamp = _stamp(clock, NEPAL)
        else:
            clock += datetime.timedelta(seconds=rng.choice([-30, 0, 0.000001, 1, 30, 30, 120, 599, 601, 3600, 86400]))
            stamp = _stamp(clock)
        if stamp is not ABSENT:
            frame['timestamp_utc'] = stamp
        previous = stamp if isinstance(stamp, str) and stamp.endswith('Z') else previous
        quiet = rng.random() < 0.4
        for key in ('app_switch_rate', 'unlock_burst_count'):
            if rng.random() < 0.5:
                dig[key] = 0 if quiet and rng.random() < 0.8 else rng.choice(COUNT_VALUES)
        if rng.random() < 0.4:
            dig['touch_interaction_velocity'] = rng.choice(NASTY + [0.5, 3, 0])
        if rng.random() < 0.55:
            dig['screen_on_ratio'] = rng.choice(RATIO_VALUES)
        for key in DIGITAL_DIRECT:
            if rng.random() < 0.3:
                dig[key] = rng.choice(NASTY)
        if rng.random() < 0.6:
            dev['screen_state'] = rng.choice(['ON', 'DOZING', 'OFF', 'UNKNOWN', 5, None, 'on'])
        for key in ('wired_headset', 'bluetooth_a2dp'):
            if rng.random() < 0.5:
                dev[key] = rng.choice(FLAG_VALUES)
        if rng.random() < 0.35:
            dev['compute_power_w'] = rng.choice(NASTY)
        for key, values in (('stationary_likelihood', STATIONARY_VALUES), ('motion_energy', [0, 0.1, 0.4, 0.5, 2, -1, 'x']),
                            ('gyro_raw', GYRO_VALUES), ('microslip_ang_vel', NASTY)):
            if rng.random() < 0.5:
                mot[key] = rng.choice(values)
        if rng.random() < 0.6:
            env['ambient_light_lux'] = rng.choice(LUX_VALUES)
        for name, part in (('digital_context', dig), ('device_relation', dev), ('motion', mot), ('environment', env)):
            if part or rng.random() < 0.5:
                frame[name] = part
        frames.append(frame)
    return frames


class TestReferenceUsageFeatures(unittest.TestCase):
    def test_matches_python_reference_on_generated_sequences(self):
        if not parity.java_available():
            self.skipTest('Java compiler/runtime unavailable')
        report = parity.check_group('ReferenceUsageFeatures', ['ReferenceUsageFeatures.java'], NAMES,
                                    quantum=QUANTUM)
        self.assertEqual(report['problems'], [])
        self.assertEqual(report['java_only_errors'], 0)
        self.assertGreater(min(report['compared'].values()), 1500)

    def test_matches_python_reference_on_adversarial_sequences(self):
        """Boundary values, null-valued keys, repeated/backwards/epoch timestamps and resets."""
        if not parity.java_available():
            self.skipTest('Java compiler/runtime unavailable')
        seen = {name: set() for name in NAMES}
        with tempfile.TemporaryDirectory() as directory:
            workdir = Path(directory)
            for seed in range(8):
                frames = adversarial_frames(seed)
                path = workdir / f'adversarial-{seed}.jsonl'
                parity.write_sequence(frames, path)
                names, actual = parity.run_java('ReferenceUsageFeatures', ['ReferenceUsageFeatures.java'], path,
                                                workdir)
                self.assertEqual(list(names), NAMES)
                expected = parity.run_python(frames, NAMES)
                for frame, row in zip(frames, expected):
                    if '__reset' not in frame:
                        # Python must not raise: a silent Python-side reset_state() would desynchronise Java.
                        self.assertIsNotNone(row, f'Python raised on {frame!r}')
                        for name, value in zip(NAMES, row):
                            seen[name].add(value)
                report = parity.compare(NAMES, frames, expected, actual, QUANTUM)
                self.assertEqual(report['problems'], [], f'seed {seed}')
                self.assertEqual(report['java_only_errors'], 0)
        for name in NAMES:
            self.assertGreater(len(seen[name]), 5, name)

    def test_every_output_takes_several_values_on_the_sequences(self):
        distinct = parity.exercise(NAMES)
        for name in NAMES:
            self.assertGreater(distinct[name], 3, f'{name} is nearly constant on the generated sequences')


if __name__ == '__main__':
    unittest.main()
