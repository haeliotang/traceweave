import math
import unittest

from traceform.representation.normalizer import MultimodalNormalizer
from traceform.representation.missingness_candidate import MissingnessProjectionCandidate


class TestMissingnessProjectionCandidate(unittest.TestCase):
    def setUp(self):
        normalizer = MultimodalNormalizer()
        normalizer.fit_discovery_window([{'place': {'latitude': 1},
                                          'environment': {'device_temp_c': 30}}])
        self.params = normalizer.to_dict()
        self.names = self.params['feature_names']
        self.candidate = MissingnessProjectionCandidate(self.params)
        self.vector = [2.0] * len(self.names)

    def frame(self, seconds, temperature=30, place=None):
        return {'timestamp_utc': f'2026-10-08T00:{seconds // 60:02d}:{seconds % 60:02d}Z',
                'environment': {'device_temp_c': temperature}, 'place': place or {}}

    def test_location_presence_alone_cannot_create_gnss_coordinate_jump(self):
        legacy = MultimodalNormalizer.from_dict(self.params)
        yes = self.frame(0, place={'latitude': 1})
        no = self.frame(30, place={'status': 'NOT_SAMPLED'})
        old_yes = legacy.transform(yes)[0]
        old_no = legacy.transform(no)[0]
        indices = [self.names.index(name) for name in self.candidate.GNSS]
        self.assertNotEqual([old_yes[i] for i in indices], [old_no[i] for i in indices])
        first = self.candidate.project(yes, old_yes, False)
        second = self.candidate.project(no, old_no, True)
        self.assertEqual([first['vector'][i] for i in indices], [0, 0, 0])
        self.assertEqual([second['vector'][i] for i in indices], [0, 0, 0])
        self.assertFalse(any(second['validity'][name] for name in self.candidate.GNSS))

    def test_zero_is_valid_and_precedes_alias_invalid_is_missing(self):
        result = self.candidate.project(self.frame(0, place={'visible_satellites': 0,
            'gnss_sat_count': 12, 'mean_carrier_to_noise_ratio': 0,
            'position_dilution_of_precision': float('nan')}), self.vector, False)
        self.assertTrue(result['validity']['gnss_visible_satellite_density'])
        self.assertTrue(result['validity']['gnss_mean_carrier_to_noise_ratio'])
        self.assertFalse(result['validity']['gnss_position_dilution_of_precision'])
        self.assertEqual(result['vector'][self.names.index('gnss_visible_satellite_density')], -10)
        result = self.candidate.project(self.frame(30, place={'visible_satellites': True,
            'mean_carrier_to_noise_ratio': -1, 'position_dilution_of_precision': 0}), self.vector, True)
        self.assertFalse(any(result['validity'][name] for name in self.candidate.GNSS))

    def test_thermal_noise_has_bounded_shared_energy_and_real_change_survives(self):
        self.candidate.project(self.frame(0), self.vector, False)
        result = self.candidate.project(self.frame(30, temperature=35), self.vector, True)
        indices = [self.names.index(name) for name in self.candidate.THERMAL]
        self.assertGreater(result['vector'][indices[0]], 0)
        self.assertLessEqual(sum(result['vector'][i] ** 2 for i in indices), 1)
        self.assertTrue(result['validity'][self.candidate.THERMAL[0]])
        result = self.candidate.project(self.frame(60, temperature=25), self.vector, True)
        self.assertTrue(all(math.isfinite(value) for value in result['vector']))
        self.assertLessEqual(sum(result['vector'][i] ** 2 for i in indices), 1)

    def test_gap_missing_temperature_and_reset_do_not_make_derivative(self):
        self.candidate.project(self.frame(0), self.vector, False)
        result = self.candidate.project(self.frame(30, temperature=None), self.vector, True)
        self.assertFalse(any(result['validity'][name] for name in self.candidate.THERMAL))
        result = self.candidate.project(self.frame(60, temperature=31), self.vector, True)
        self.assertFalse(any(result['validity'][name] for name in self.candidate.THERMAL))
        result = self.candidate.project(self.frame(90, temperature=32), self.vector, False)
        self.assertFalse(any(result['validity'][name] for name in self.candidate.THERMAL))
        result = self.candidate.project(self.frame(450, temperature=33), self.vector, True)
        self.assertFalse(any(result['validity'][name] for name in self.candidate.THERMAL))

    def test_other_coordinates_and_parameters_are_preserved_and_binding_is_distinct(self):
        from traceform.representation.provenance import content_digest
        result = self.candidate.project(self.frame(0), self.vector, False)
        changed = set(self.candidate.GNSS) | set(self.candidate.THERMAL)
        for index, name in enumerate(self.names):
            if name not in changed:
                self.assertEqual(result['vector'][index], self.vector[index])
        self.assertNotEqual(self.candidate.digest, content_digest(self.params))
        self.assertEqual(self.candidate.to_dict()['baseline_normalizer'], self.params)
        with self.assertRaises(ValueError):
            self.candidate.project(self.frame(30), [0], True)


class TestOfflineProjectionWindow(unittest.TestCase):
    def test_frozen_holdout_and_mismatched_coordinate_binding_are_rejected(self):
        import json
        from pathlib import Path
        import sqlite3
        from contextlib import closing
        import tempfile
        from scripts.evaluate_missingness_projection import evaluate
        from traceform.representation.provenance import content_digest
        normalizer = MultimodalNormalizer()
        params = normalizer.fit_discovery_window([{}]).to_dict()
        bundle = {'normalizer': params}
        plan = {'holdout_start_utc': '2026-10-09T00:00:00+00:00'}
        window = {'window_start_utc': '2026-10-08T00:00:00+00:00',
                  'window_end_utc': '2026-10-08T01:00:00+00:00'}
        with tempfile.TemporaryDirectory() as directory:
            dbpath = Path(directory) / 'source.sqlite'
            with closing(sqlite3.connect(dbpath)) as db:
                db.execute('CREATE TABLE hot(id TEXT,ts REAL,raw TEXT,frame TEXT)')
                for index in range(2):
                    stamp = f'2026-10-08T00:00:{index * 30:02d}+00:00'
                    import datetime
                    instant = datetime.datetime.fromisoformat(stamp).timestamp()
                    raw = {'timestamp_utc': stamp, 'environment': {'device_temp_c': 30 + index}}
                    frame = {'timestamp_utc': stamp, 'normalizer_sha256': content_digest(params),
                             'continuous_vector': [0.0] * 128, 'continuous_from_previous': index > 0}
                    db.execute('INSERT INTO hot VALUES(?,?,?,?)',
                               (str(index), instant, json.dumps(raw), json.dumps(frame)))
                db.commit()
            report, candidate = evaluate(dbpath, bundle, window, plan)
            self.assertEqual(report['frames'], 2)
            self.assertTrue(report['frozen_holdout_excluded'])
            self.assertFalse(report['activated'])
            self.assertEqual(report['unchanged_other_123_dimensions_frames'], 2)
            with self.assertRaisesRegex(ValueError, 'before frozen holdout'):
                evaluate(dbpath, bundle, dict(window, window_end_utc='2026-10-09T00:00:01+00:00'), plan)
            with closing(sqlite3.connect(dbpath)) as db:
                value = json.loads(db.execute('SELECT frame FROM hot LIMIT 1').fetchone()[0])
                value['normalizer_sha256'] = 'wrong'
                db.execute('UPDATE hot SET frame=? WHERE id=?', (json.dumps(value), '0'))
                db.commit()
            with self.assertRaisesRegex(ValueError, 'binding'):
                evaluate(dbpath, bundle, window, plan)
