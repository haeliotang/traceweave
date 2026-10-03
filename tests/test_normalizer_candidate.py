import copy
import datetime
import hashlib
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from traceform.evolution.normalizer_candidate import (replay_raw_features, saturation_summary,
                                                      vectors_from_raw)
from traceform.representation.normalizer import MultimodalNormalizer
from traceform.representation.provenance import content_digest

ROOT = Path(__file__).resolve().parents[1]
START = datetime.datetime(2026, 10, 1, 0, 0, 0, tzinfo=datetime.timezone.utc)


def observation(i, *, seconds=30, lat=None, pressure=913.0, temp=30.0):
    stamp = (START + datetime.timedelta(seconds=seconds * i)).isoformat().replace('+00:00', 'Z')
    obs = {'timestamp_utc': stamp,
           'motion': {'accel_magnitude': 9.8 + (i % 7) * 0.02, 'motion_energy': (i % 5) * 0.3,
                      'stationary_likelihood': 0.9 if i % 3 else 0.2, 'accel_raw': [0.1, 0.2, 9.7 + (i % 3) * 0.01]},
           'environment': {'pressure_hpa': pressure, 'device_temp_c': temp + (i % 4) * 0.1,
                           'ambient_light_lux': 50.0 + i},
           'device_relation': {'battery_percentage': 80 - i // 50, 'plugged': 'UNPLUGGED', 'status': 'DISCHARGING',
                               'current_ua': -300000 - (i % 9) * 1000, 'voltage_mv': 3900},
           'connectivity': {'wifi_connected': True, 'wifi_rssi': -60 - i % 5}}
    if lat is not None:
        obs['place'] = {'latitude': lat + i * 1e-4, 'longitude': 85.3}
    return obs


class TestReplay(unittest.TestCase):
    def test_scale_raw_matches_transform(self):
        normalizer = MultimodalNormalizer()
        frames = [observation(i) for i in range(60)]
        params = MultimodalNormalizer().fit_discovery_window(frames, 'fit')
        normalizer.params = params
        reference = MultimodalNormalizer()
        reference.params = params
        for frame in frames[:10]:
            raw, _ = normalizer.extract_raw_features(copy.deepcopy(frame))
            vector, _ = reference.transform(copy.deepcopy(frame))
            self.assertEqual(normalizer.scale_raw(raw), vector)

    def test_replay_returns_one_raw_map_per_frame_without_mutating_input(self):
        frames = [observation(i) for i in range(20)]
        snapshot = copy.deepcopy(frames)
        raws = replay_raw_features(frames)
        self.assertEqual(len(raws), 20)
        self.assertEqual(frames, snapshot)
        self.assertIn('env_pressure_hpa', raws[0])

    def test_long_gap_resets_only_the_geo_window_like_the_daemon(self):
        frames = [observation(i, lat=27.7) for i in range(10)]
        frames += [observation(i, seconds=30, lat=27.7) for i in range(10, 12)]
        gap_start = 400
        frames += [dict(observation(i, lat=27.9), timestamp_utc=(START + datetime.timedelta(
            seconds=30 * 12 + gap_start + 30 * (i - 12))).isoformat().replace('+00:00', 'Z')) for i in range(12, 16)]
        with_reset = replay_raw_features(frames)
        # Without a gap rule the window would still hold the earlier coordinates: the radius would be larger.
        plain = MultimodalNormalizer()
        no_reset = [plain.extract_raw_features(copy.deepcopy(f))[0] for f in frames]
        index = 12  # first frame after the 400 s gap
        self.assertNotEqual(with_reset[index]['spatial_radius_of_gyration'], no_reset[index]['spatial_radius_of_gyration'])

    def test_backwards_time_also_resets_the_geo_window(self):
        frames = [observation(i, lat=27.7) for i in range(6)]
        later = dict(observation(0, lat=27.9), timestamp_utc=(START - datetime.timedelta(seconds=60)).isoformat().replace('+00:00', 'Z'))
        raws = replay_raw_features(frames + [later, dict(later, timestamp_utc=(START - datetime.timedelta(seconds=30)).isoformat().replace('+00:00', 'Z'))])
        self.assertEqual(raws[-1]['spatial_radius_of_gyration'] >= 0.0, True)

    def test_restart_points_reset_all_state_so_memory_features_match_a_restarted_daemon(self):
        frames = [observation(i) for i in range(40)]
        for frame in frames:
            frame['motion']['stationary_likelihood'] = 0.95  # one long residence, so restarts are visible
        restart_at = 25
        # What a daemon restarted at frame 25 would have produced: a fresh normalizer from there on.
        live = [MultimodalNormalizer().extract_raw_features(copy.deepcopy(f))[0] for f in frames[:restart_at]]
        fresh = MultimodalNormalizer()
        live += [fresh.extract_raw_features(copy.deepcopy(f))[0] for f in frames[restart_at:]]
        continuous = replay_raw_features(frames)
        restarted = replay_raw_features(frames, restart_before={restart_at})
        name = 'state_residence_persistence_hours'
        self.assertEqual([r[name] for r in restarted[restart_at:]], [r[name] for r in live[restart_at:]])
        self.assertNotEqual([r[name] for r in continuous[restart_at:]], [r[name] for r in live[restart_at:]])

    def test_vectors_follow_the_requested_parameters(self):
        frames = [observation(i, pressure=913.0 + i * 0.02) for i in range(80)]
        raws = replay_raw_features(frames)
        legacy = MultimodalNormalizer().fit_discovery_window(frames[:20], 'early')
        robust = MultimodalNormalizer().fit_robust_window(frames[:20], 'early')
        index = list(legacy.feature_names).index('env_pressure_hpa')
        old = vectors_from_raw(raws, legacy)
        new = vectors_from_raw(raws, robust)
        self.assertEqual(len(old), len(new))
        self.assertLessEqual(max(abs(row[index]) for row in new), max(abs(row[index]) for row in old))


class TestSaturation(unittest.TestCase):
    def test_summary_flags_pinned_and_constant_dimensions(self):
        names = ['pinned', 'constant', 'live']
        rows = [[10.0, 0.5, 0.1 * i] for i in range(10)]
        rows += [[-10.0, 0.5, 0.2] for _ in range(10)]
        summary = saturation_summary(rows, names)
        self.assertEqual(summary['saturated_dimensions'], ['pinned'])
        self.assertEqual(summary['constant_dimensions'], ['constant'])
        self.assertEqual(summary['clip_fraction']['pinned'], 1.0)
        self.assertEqual(summary['clip_fraction']['live'], 0.0)

    def test_threshold_is_inclusive_at_half(self):
        summary = saturation_summary([[10.0], [0.0]] * 5, ['half'])
        self.assertEqual(summary['saturated_dimensions'], ['half'])


def synthetic_database(path, count=240, restart_at=None):
    connection = sqlite3.connect(path)
    connection.execute('create table hot (id text primary key, ts real, raw text, frame text)')
    normalizer = MultimodalNormalizer()
    frames = []
    for i in range(count):
        # steady pressure for the first half (narrow IQR), then drift: the case the robust fit addresses
        pressure = 913.0 + (i % 5) * 0.01 if i < count // 2 else 913.0 + (i - count // 2) * 0.05
        frames.append(observation(i, pressure=pressure))
    params = MultimodalNormalizer().fit_discovery_window(frames[:120], 'early window')
    normalizer.params = params
    digest = content_digest(params.to_dict())
    for i, obs in enumerate(frames):
        if i == restart_at:
            normalizer.reset_state()  # a daemon restart: the live normalizer starts from empty state
        vector, _ = normalizer.transform(copy.deepcopy(obs))
        frame = {'continuous_vector': vector, 'timestamp_utc': obs['timestamp_utc'], 'model_version': 'v1',
                 'model_sha256': 'a' * 64, 'normalizer_sha256': digest, 'boot_id': 'b', 'continuity_epoch': 1,
                 'source_mode': 'live_follow', 'state_token': 'M01', 'continuous_from_previous': i > 0 and i != restart_at,
                 'observation_id': f'obs{i}'}
        connection.execute('insert into hot values (?,?,?,?)', (f'id{i}', START.timestamp() + 30 * i,
                                                                json.dumps(obs), json.dumps(frame)))
    connection.commit()
    connection.close()
    return params


def corrupt_dimension(path, index, every=4):
    """Make the stored vector disagree with a faithful replay on one dimension."""
    connection = sqlite3.connect(path)
    for position, (row_id, frame) in enumerate(connection.execute('select id, frame from hot order by ts').fetchall()):
        if position % every == 0:
            payload = json.loads(frame)
            payload['continuous_vector'][index] += 0.5
            connection.execute('update hot set frame=? where id=?', (json.dumps(payload), row_id))
    connection.commit()
    connection.close()


class TestEvaluationScript(unittest.TestCase):
    def test_warmup_frames_are_excluded_from_the_faithfulness_check_but_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / 'edge.sqlite'
            params = synthetic_database(database)
            connection = sqlite3.connect(database)
            for row_id, frame in connection.execute('select id, frame from hot order by ts limit 10').fetchall():
                payload = json.loads(frame)
                payload['continuous_vector'][0] += 0.25  # state the live daemon had before the window began
                connection.execute('update hot set frame=? where id=?', (json.dumps(payload), row_id))
            connection.commit()
            connection.close()
            baseline = Path(directory) / 'baseline.json'
            baseline.write_text(json.dumps(params.to_dict()))
            output = Path(directory) / 'report.json'
            subprocess.run([sys.executable, str(ROOT / 'scripts/evaluate_normalizer_candidate.py'),
                            '--sqlite-file', str(database), '--baseline-normalizer-file', str(baseline),
                            '--output-file', str(output)], check=True, cwd=ROOT, capture_output=True)
            report = json.loads(output.read_text())
            fidelity = report['replay_fidelity']
            self.assertAlmostEqual(fidelity['baseline_matches_stored_fraction'], 230 / 240, places=4)
            self.assertEqual(fidelity['fidelity_after_warmup'], 1.0)
            self.assertEqual(fidelity['fidelity_by_warmup_frames']['0'], fidelity['baseline_matches_stored_fraction'])
            self.assertTrue(report['acceptance']['checks']['replay_faithful'])

    def run_cli(self, database, params):
        baseline = Path(database).parent / 'baseline.json'
        baseline.write_text(json.dumps(params.to_dict()))
        output = Path(database).parent / 'report.json'
        subprocess.run([sys.executable, str(ROOT / 'scripts/evaluate_normalizer_candidate.py'),
                        '--sqlite-file', str(database), '--baseline-normalizer-file', str(baseline),
                        '--output-file', str(output)], check=True, cwd=ROOT, capture_output=True)
        return json.loads(output.read_text())

    def corrupt_range(self, database, index, start, stop, delta=0.5):
        connection = sqlite3.connect(database)
        for position, (row_id, frame) in enumerate(connection.execute('select id, frame from hot order by ts').fetchall()):
            if start <= position < stop:
                payload = json.loads(frame)
                payload['continuous_vector'][index] += delta
                connection.execute('update hot set frame=? where id=?', (json.dumps(payload), row_id))
        connection.commit()
        connection.close()

    def test_long_memory_dimensions_are_not_judged_before_the_first_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / 'edge.sqlite'
            params = synthetic_database(database, restart_at=150)
            residence = list(params.feature_names).index('state_residence_persistence_hours')
            self.corrupt_range(database, residence, 0, 150)  # live state existed before the window began
            report = self.run_cli(database, params)
            fidelity = report['replay_fidelity']
            self.assertEqual(fidelity['first_restart_index'], 150)
            self.assertEqual(fidelity['fidelity_after_first_restart'], 1.0)
            self.assertEqual(fidelity['pre_restart_short_memory_fidelity'], 1.0)
            self.assertLess(fidelity['baseline_matches_stored_fraction'], 0.5)  # visible in the all-frames figure
            self.assertTrue(report['acceptance']['checks']['replay_faithful'])

    def test_a_short_memory_mismatch_before_the_restart_is_still_a_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / 'edge.sqlite'
            params = synthetic_database(database, restart_at=150)
            temp = list(params.feature_names).index('env_device_temp_c')
            self.corrupt_range(database, temp, 110, 140)
            report = self.run_cli(database, params)
            self.assertLess(report['replay_fidelity']['pre_restart_short_memory_fidelity'], 0.99)
            self.assertFalse(report['acceptance']['checks']['replay_faithful'])

    def test_a_mismatch_after_the_restart_is_a_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / 'edge.sqlite'
            params = synthetic_database(database, restart_at=150)
            temp = list(params.feature_names).index('env_device_temp_c')
            self.corrupt_range(database, temp, 160, 200)
            report = self.run_cli(database, params)
            self.assertLess(report['replay_fidelity']['fidelity_after_first_restart'], 0.99)
            self.assertFalse(report['acceptance']['checks']['replay_faithful'])

    def test_replay_mismatch_is_reported_per_dimension_without_values(self):
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / 'edge.sqlite'
            params = synthetic_database(database)
            index = list(params.feature_names).index('env_device_temp_c')
            corrupt_dimension(database, index, every=4)
            baseline = Path(directory) / 'baseline.json'
            baseline.write_text(json.dumps(params.to_dict()))
            output = Path(directory) / 'report.json'
            subprocess.run([sys.executable, str(ROOT / 'scripts/evaluate_normalizer_candidate.py'),
                            '--sqlite-file', str(database), '--baseline-normalizer-file', str(baseline),
                            '--output-file', str(output)], check=True, cwd=ROOT, capture_output=True)
            fidelity = json.loads(output.read_text())['replay_fidelity']
            mismatch = fidelity['mismatched_dimensions']
            self.assertEqual(list(mismatch), ['env_device_temp_c'])
            self.assertAlmostEqual(mismatch['env_device_temp_c']['fraction'], 0.25, places=2)
            self.assertAlmostEqual(mismatch['env_device_temp_c']['max_abs_difference'], 0.5, places=4)
            self.assertAlmostEqual(fidelity['baseline_matches_stored_fraction'], 0.75, places=2)
            self.assertIn(fidelity['replay_variant'], ('gap_rule_only', 'restart_at_discontinuities'))
            self.assertEqual(set(fidelity['variant_fidelity']), {'gap_rule_only', 'restart_at_discontinuities'})
            self.assertFalse(json.loads(output.read_text())['acceptance']['checks']['replay_faithful'])


    def test_cli_reports_fidelity_saturation_forecast_and_acceptance(self):
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / 'edge.sqlite'
            params = synthetic_database(database)
            baseline = Path(directory) / 'baseline.json'
            baseline.write_text(json.dumps(params.to_dict()))
            output = Path(directory) / 'report.json'
            candidate = Path(directory) / 'candidate.json'
            subprocess.run([sys.executable, str(ROOT / 'scripts/evaluate_normalizer_candidate.py'),
                            '--sqlite-file', str(database), '--baseline-normalizer-file', str(baseline),
                            '--output-file', str(output), '--candidate-normalizer-file', str(candidate)],
                           check=True, cwd=ROOT, capture_output=True)
            report = json.loads(output.read_text())
            self.assertEqual(report['replay_fidelity']['baseline_matches_stored_fraction'], 1.0)
            self.assertIn('env_pressure_hpa', report['saturation']['baseline']['saturated_dimensions'])
            self.assertNotIn('env_pressure_hpa', report['saturation']['candidate']['saturated_dimensions'])
            for key in ('saturation_reduced', 'no_new_constant_dimensions', 'forecast_not_worse', 'replay_faithful'):
                self.assertIn(key, report['acceptance']['checks'])
            self.assertEqual(report['acceptance']['accepted'], all(report['acceptance']['checks'].values()))
            self.assertEqual(report['split']['train_records'] + report['split']['holdout_records'], 240)
            self.assertTrue(json.loads(candidate.read_text())['fit_window_description'].startswith('robust_range_v2'))
            self.assertTrue(report['baseline_digest_matches_stored_frames'])
            self.assertEqual(report['stored_frames_normalizer_sha256_counts'], {report['baseline_normalizer_sha256']: 240})
            self.assertNotIn('latitude', json.dumps(report))


if __name__ == '__main__':
    unittest.main()
