"""Parity of ReferenceDigitalProbe.java with the real DigitalSensoriumProbe.

The Python probe is exercised unmodified: ``subprocess.run`` and ``shutil.which`` are patched to return
fabricated ``dumpsys power`` / ``bluetooth_manager`` / ``usagestats`` text built from the same sample that is
handed to Java as already-parsed values (screen state, filtered event list, Wi-Fi AP count, Bluetooth sum).
"""
import random
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import parity_support as parity
from traceform.probes import digital_sensorium

KEYS = ['screen_on_ratio', 'app_switch_rate', 'unlock_burst_count', 'touch_interaction_velocity',
        'ambient_social_ble_density', 'ambient_rf_shannon_entropy', 'touch_interval_jitter',
        'attention_fragmentation_entropy', 'ble_rssi_spatial_variance']
WAKEFULNESS = {'ON': 'Awake', 'DOZING': 'Dozing', 'OFF': 'Asleep'}
EVENT_TYPES = (['ACTIVITY_RESUMED'] * 8 + ['SCREEN_INTERACTIVE'] * 2 + ['KEYGUARD_HIDDEN'] * 2 + [
    'ACTIVITY_PAUSED', 'ACTIVITY_STOPPED', 'SCREEN_NON_INTERACTIVE', 'KEYGUARD_SHOWN', 'USER_INTERACTION',
    'SYSTEM_INTERACTION', 'STANDBY_BUCKET_CHANGED', 'FOREGROUND_SERVICE_START', 'ACTIVITY_RESUMED_EXTRA',
    'SCREEN_INTERACTIVE_X', 'KEYGUARD_HIDDEN2', '3', ''])
UNLOCK_TYPES = ['SCREEN_INTERACTIVE', 'KEYGUARD_HIDDEN']
PACKAGE_ALPHABET = 'abcdefghijklmnopqrstuvwxyz0123456789._-$='
NOISE = ['    time="2026-10-02 10:00:00.000" flags=0x0', '    type=UNRELATED', '    package=com.noise.app',
         '  Events (since boot):', '    some other line without markers']
BLUETOOTH_LABELS = ['Connected count', 'Bonded devices', 'Other devices', 'A2dp Connected count']
BLUETOOTH_TOKENS = ['0', '1', '2', '3', '4', '5', '7', '10', '2.5', '0.1', ' 4 ', '1e1', '-3', '7.', 'abc', '']
SCENARIOS = ['none', 'few', 'window', 'many', 'unlock_only', 'same_package', 'ties', 'distinct', 'no_package',
             'mixed', 'mixed', 'mixed']


def package_pool(rng, size):
    names = set()
    while len(names) < size:
        length = rng.randint(3, 14)
        names.add(''.join(rng.choice(PACKAGE_ALPHABET) for _ in range(length)).strip('=') or 'x')
    return sorted(names)


def build_events(rng, scenario):
    """(type, package-or-None) tuples in chronological order."""
    pool = package_pool(rng, rng.randint(1, 30))
    if scenario == 'none':
        return []
    if scenario == 'unlock_only':
        return [(rng.choice(UNLOCK_TYPES), rng.choice([None, 'android'])) for _ in range(rng.randint(1, 40))]
    if scenario == 'same_package':
        return [('ACTIVITY_RESUMED', pool[0]) for _ in range(rng.randint(1, 60))]
    if scenario == 'ties':
        groups, each = rng.randint(2, 8), rng.randint(1, 15)
        events = [('ACTIVITY_RESUMED', pool[i % len(pool)] + str(i)) for i in range(groups) for _ in range(each)]
        rng.shuffle(events)
        return events
    if scenario == 'distinct':
        return [('ACTIVITY_RESUMED', 'p%d.%s' % (i, rng.choice(pool))) for i in range(rng.randint(2, 70))]
    if scenario == 'no_package':
        return [('ACTIVITY_RESUMED', None) for _ in range(rng.randint(1, 30))]
    weights = [rng.random() ** 2 for _ in pool]
    sizes = {'few': rng.randint(1, 10), 'window': rng.randint(100, 140), 'many': rng.randint(121, 400)}
    total = sizes[scenario] if scenario in sizes else rng.choice(list(sizes.values()))
    events = []
    for _ in range(total):
        event_type = rng.choice(EVENT_TYPES)
        package = rng.choices(pool, weights)[0] if rng.random() < 0.85 else None
        events.append((event_type, package))
    if total > 120 and rng.random() < 0.7:
        # Distinct packages that fall outside the 120-event window must not influence the result.
        events[:total - 120] = [('ACTIVITY_RESUMED', 'outside.%d' % i) for i in range(total - 120)]
    return events


def event_line(rng, index, event_type, package):
    line = '    time="2026-10-02 10:%02d:%02d.%03d" type=%s' % (index // 60 % 60, index % 60, rng.randrange(1000),
                                                                event_type)
    if package is not None:
        line += ' package=%s' % package
    return line + ' class=com.example.Main flags=0x0 instanceId=1'


def usagestats_text(rng, events, marker):
    lines = ['UsageStatsService:', '  Usage stats version 1', '  user=0']
    for index, (event_type, package) in enumerate(events):
        if rng.random() < 0.15:
            lines.append(rng.choice(NOISE))
        lines.append(event_line(rng, index, event_type, package))
    if marker:
        lines.append(rng.choice(['  In-memory daily stats', 'In-memory daily stats:']))
        for index in range(rng.randint(0, 40)):  # later events must be ignored
            lines.append(event_line(rng, index, rng.choice(['ACTIVITY_RESUMED', 'SCREEN_INTERACTIVE']), 'after.marker'))
    return '\n'.join(lines) + '\n'


def bluetooth_sum(parts):
    total = 0.0
    for _, token in parts:
        try:
            total += float(token.strip())
        except ValueError:
            pass
    return total


def build_sample(rng):
    state = rng.choice(['ON', 'ON', 'DOZING', 'OFF', 'UNKNOWN'])
    sample = {'has_dumpsys': rng.random() < 0.9, 'state': state, 'wifi': rng.choice([0, rng.randint(1, 60),
                                                                                    rng.randint(-5, 0)])}
    # dumpsys power
    if state in WAKEFULNESS:
        text = 'Power Manager State:\n  mWakefulnessChanging=false\n'
        if rng.random() < 0.2:
            text += '  mWakefulness=Transitioning\n'  # a mWakefulness= line without a recognised value is skipped
        text += '  mWakefulness=%s\n  mWakeLockSummary=0x0\n' % WAKEFULNESS[state]
        sample['power'] = ('ok', 0, text)
    else:
        sample['power'] = rng.choice([('ok', 1, '  mWakefulness=Awake\n'), ('ok', 0, 'Power Manager State:\n'),
                                      ('ok', 0, '  mWakefulness=Other\n'), ('raise',)])
    # dumpsys bluetooth_manager
    available = rng.random() < 0.8
    parts = [(rng.choice(BLUETOOTH_LABELS), rng.choice(BLUETOOTH_TOKENS)) for _ in range(rng.randint(0, 5))] \
        if available else []
    if available and rng.random() < 0.05:
        parts.append(('Other devices', rng.choice(['nan', 'inf', '-inf'])))
    sample['bt_parts'] = parts
    sample['bt'] = ('ok', 0, 'Bluetooth Status\n  enabled: true\n  address: AA:BB:CC:DD:EE:FF\n' + ''.join(
        '  %s: %s\n' % part for part in parts)) if available else rng.choice(
        [('ok', 1, '  Connected count: 9\n'), ('ok', 0, ''), ('raise',)])
    # dumpsys usagestats
    if rng.random() < 0.85:
        sample['events'] = build_events(rng, rng.choice(SCENARIOS))
        sample['usage'] = ('ok', 0, usagestats_text(rng, sample['events'], rng.random() < 0.8))
    else:
        sample['events'] = None
        sample['usage'] = rng.choice([('ok', 1, '  time=1 type=ACTIVITY_RESUMED package=a\n'), ('ok', 0, ''), ('raise',)])
    return sample


def java_inputs(sample):
    """What the native caller supplies: the values Python extracts from the fabricated dumps."""
    if not sample['has_dumpsys']:
        return 'UNKNOWN', None, sample['wifi'], False, 0.0
    bt_ok = sample['bt'][0] == 'ok' and sample['bt'][1] == 0 and bool(sample['bt'][2])
    return (sample['state'], sample['events'], sample['wifi'], bt_ok, bluetooth_sum(sample['bt_parts']) if bt_ok else 0.0)


def run_python_probe(sample):
    def fake_run(args, **kwargs):
        assert sample['has_dumpsys'], 'subprocess.run must not be reached without dumpsys'
        behaviour = sample[{'power': 'power', 'bluetooth_manager': 'bt', 'usagestats': 'usage'}[args[1]]]
        if behaviour[0] == 'raise':
            raise subprocess.TimeoutExpired(args, 1.5)
        return subprocess.CompletedProcess(args, behaviour[1], stdout=behaviour[2])

    with mock.patch.object(digital_sensorium.shutil, 'which',
                           side_effect=lambda name: '/system/bin/dumpsys' if sample['has_dumpsys'] else None):
        with mock.patch.object(digital_sensorium.subprocess, 'run', side_effect=fake_run):
            probe = digital_sensorium.DigitalSensoriumProbe()
            return probe.sample_digital_interaction_metrics(fallback_wifi_ap_count=sample['wifi'])


def number(value):
    if value != value:
        return 'NaN'
    if value in (float('inf'), float('-inf')):
        return 'Infinity' if value > 0 else '-Infinity'
    return repr(float(value))


def sample_file_text(samples):
    lines = []
    for sample in samples:
        screen, events, wifi, bt_ok, bt_sum = java_inputs(sample)
        lines.append('\t'.join(['H', screen, str(wifi), '1' if bt_ok else '0', number(bt_sum),
                                '-1' if events is None else str(len(events))]))
        for event_type, package in events or []:
            lines.append('\t'.join(['E', event_type, 'N' if package is None else 'S' + package]))
    return '\n'.join(lines) + '\n'


def run_java_probe(samples):
    with tempfile.TemporaryDirectory() as directory:
        workdir = Path(directory)
        classes = workdir / 'classes'
        classes.mkdir()
        sources = [parity.JAVA_SOURCE / 'PyRuntime.java', parity.JAVA_SOURCE / 'ReferenceDigitalProbe.java',
                   parity.PARITY_JAVA / 'DigitalProbeRunner.java']
        compiled = subprocess.run(['javac', '-d', str(classes)] + [str(s) for s in sources],
                                  capture_output=True, text=True)
        if compiled.returncode != 0:
            raise RuntimeError('javac failed:\n' + compiled.stderr)
        sample_path = workdir / 'samples.tsv'
        sample_path.write_text(sample_file_text(samples), encoding='utf-8')
        result = subprocess.run(['java', '-cp', str(classes), 'org.traceform.edge.DigitalProbeRunner',
                                 str(sample_path)], capture_output=True, text=True, timeout=300)
        if result.returncode != 0:
            raise RuntimeError('java failed:\n' + result.stderr)
    rows = []
    for line in result.stdout.splitlines():
        if line.startswith('#ERROR'):
            rows.append(line)
        else:
            rows.append({key: float(value) for key, value in (field.split('=', 1) for field in line.split('\t'))})
    return rows


def compare(samples):
    expected = [run_python_probe(sample) for sample in samples]
    actual = run_java_probe(samples)
    problems = []
    if len(expected) != len(actual):
        return expected, ['row count differs: python %d java %d' % (len(expected), len(actual))]
    for index, (want, got) in enumerate(zip(expected, actual)):
        if got == want and isinstance(got, dict) and list(got) == list(want) == KEYS:
            continue
        if len(problems) < 15:
            screen, events, wifi, bt_ok, bt_sum = java_inputs(samples[index])
            problems.append('sample %d (screen %s, %s events, wifi %d, bluetooth %s %r): python %r java %r' % (
                index, screen, 'no' if events is None else len(events), wifi, bt_ok, bt_sum, want, got))
    return expected, problems


def distinct_values(expected):
    return {key: len({row[key] for row in expected}) for key in KEYS}


@unittest.skipUnless(parity.java_available(), 'Java compiler/runtime unavailable')
class TestReferenceDigitalProbe(unittest.TestCase):
    def test_random_samples_match_python_probe(self):
        rng = random.Random(20261003)
        samples = [build_sample(rng) for _ in range(3000)]
        expected, problems = compare(samples)
        self.assertEqual(problems, [])
        # The samples must exercise every branch rather than leave the outputs at their defaults.
        self.assertGreater(sum(1 for s in samples if s['events'] is not None and len(s['events']) > 120), 100)
        self.assertGreater(sum(1 for s in samples if s['events'] == []), 20)
        self.assertGreater(sum(1 for s in samples if s['events'] is None), 100)
        self.assertGreater(sum(1 for s in samples if not s['has_dumpsys']), 100)
        counts = distinct_values(expected)
        self.assertEqual(counts['screen_on_ratio'], 3)
        for key in KEYS[1:]:
            self.assertGreater(counts[key], 8, key)

    def test_wifi_counts_and_bluetooth_sums_exhaustively(self):
        rng = random.Random(7)
        samples = []
        for state in ('ON', 'DOZING', 'OFF', 'UNKNOWN'):
            for wifi in range(-2, 301):
                sample = build_sample(rng)
                sample.update(has_dumpsys=True, state=state, wifi=wifi, events=None, bt_parts=[],
                              power=('ok', 0, 'mWakefulness=%s\n' % WAKEFULNESS.get(state, 'Other')),
                              bt=('ok', 1, ''), usage=('ok', 1, ''))
                samples.append(sample)
        for total in range(0, 61):
            for wifi in (0, 5, 60):
                for split in range(3):
                    parts = [('Connected count', str(total))] if split == 0 else (
                        [('Connected count', str(total // 2)), ('Bonded devices', str(total - total // 2))]
                        if split == 1 else [('Other devices', str(total)), ('Bonded devices', '0'),
                                            ('Connected count', '0')])
                    sample = build_sample(rng)
                    sample.update(has_dumpsys=True, state='ON', wifi=wifi, events=[], bt_parts=parts,
                                  bt=('ok', 0, ''.join('%s: %s\n' % part for part in parts) + 'x\n'),
                                  usage=('ok', 0, 'header only\n'),
                                  power=('ok', 0, 'mWakefulness=Awake\n'))
                    samples.append(sample)
        expected, problems = compare(samples)
        self.assertEqual(problems, [])
        self.assertGreater(len({row['ambient_rf_shannon_entropy'] for row in expected}), 50)
        self.assertGreater(len({row['ambient_social_ble_density'] for row in expected}), 40)

    def test_named_edge_cases(self):
        def sample(state, events, wifi=0, bt=None):
            rng = random.Random(1)
            base = build_sample(rng)
            parts = [('Connected count', str(bt))] if bt else []
            usage = ('ok', 0, usagestats_text(rng, events, True)) if events is not None else ('ok', 1, '')
            base.update(has_dumpsys=True, state=state, wifi=wifi, events=events, bt_parts=parts, usage=usage,
                        bt=('ok', 0, ''.join('%s: %s\n' % part for part in parts) + 'x\n') if bt is not None
                        else ('ok', 1, ''),
                        power=('ok', 0, 'mWakefulness=%s\n' % WAKEFULNESS.get(state, 'Other')))
            return base

        resumed = lambda pkg: ('ACTIVITY_RESUMED', pkg)
        cases = {
            'no_events_screen_on': sample('ON', []),
            'no_events_screen_off': sample('OFF', []),
            'unavailable_screen_on': sample('ON', None, wifi=9, bt=4),
            'only_unlock': sample('ON', [('SCREEN_INTERACTIVE', None), ('KEYGUARD_HIDDEN', 'android')] * 6),
            'ties': sample('ON', [resumed('a'), resumed('b'), resumed('c'), resumed('d')] * 3),
            'two_way_tie': sample('DOZING', [resumed('a'), resumed('b')]),
            'single_package': sample('ON', [resumed('a')] * 25),
            'over_120': sample('ON', [resumed('old.%d' % i) for i in range(50)] + [resumed('a')] * 60 + [
                ('SCREEN_INTERACTIVE', None)] * 60),
            'capped_counts': sample('ON', [resumed('p%d' % i) for i in range(120)]),
            'unlock_cap': sample('ON', [('KEYGUARD_HIDDEN', None)] * 120),
            'rf_and_ble': sample('OFF', [resumed('a')], wifi=45, bt=0),
        }
        names = list(cases)
        expected, problems = compare([cases[name] for name in names])
        self.assertEqual(problems, [])
        by_name = dict(zip(names, expected))
        self.assertEqual(by_name['no_events_screen_on']['touch_interaction_velocity'], 1.0)
        self.assertEqual(by_name['no_events_screen_off']['touch_interaction_velocity'], 0.0)
        self.assertEqual(by_name['unavailable_screen_on']['touch_interaction_velocity'], 0.0)
        self.assertEqual(by_name['two_way_tie']['attention_fragmentation_entropy'], 1.0)
        self.assertEqual(by_name['ties']['attention_fragmentation_entropy'], 2.0)
        self.assertEqual(by_name['single_package']['attention_fragmentation_entropy'], 0.0)
        self.assertEqual(by_name['capped_counts']['app_switch_rate'], 50.0)
        self.assertEqual(by_name['capped_counts']['attention_fragmentation_entropy'], 4.0)
        self.assertEqual(by_name['unlock_cap']['unlock_burst_count'], 20.0)
        self.assertEqual(by_name['over_120']['app_switch_rate'], 50.0)
        self.assertEqual(by_name['over_120']['unlock_burst_count'], 20.0)
        self.assertEqual(by_name['over_120']['attention_fragmentation_entropy'], 0.0)


if __name__ == '__main__':
    unittest.main()
