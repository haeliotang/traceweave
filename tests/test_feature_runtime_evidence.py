import hashlib
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

from traceform.representation.feature_runtime_evidence import collect

ROOT = Path(__file__).resolve().parents[1]

FEATURES = ['f_direct', 'f_constant_input', 'f_absent_input', 'f_partial', 'f_constant_out', 'f_mix_a', 'f_mix_b']

DATAFLOW = {
    'source_sha256': 'x' * 64,
    'feature_count': len(FEATURES),
    'features': [
        {'index': 0, 'feature': 'f_direct', 'input_class': 'observation_derived',
         'observation_inputs': ['motion.accel'], 'history_observation_inputs': [], 'control_observation_inputs': []},
        {'index': 1, 'feature': 'f_constant_input', 'input_class': 'observation_derived',
         'observation_inputs': ['digital_context.touch'], 'history_observation_inputs': [],
         'control_observation_inputs': []},
        {'index': 2, 'feature': 'f_absent_input', 'input_class': 'observation_derived',
         'observation_inputs': ['place.sats'], 'history_observation_inputs': [], 'control_observation_inputs': []},
        {'index': 3, 'feature': 'f_partial', 'input_class': 'observation_derived',
         'observation_inputs': ['motion.accel', 'motion.override'], 'history_observation_inputs': [],
         'control_observation_inputs': []},
        {'index': 4, 'feature': 'f_constant_out', 'input_class': 'observation_derived',
         'observation_inputs': ['motion.accel'], 'history_observation_inputs': [], 'control_observation_inputs': []},
        {'index': 5, 'feature': 'f_mix_a', 'input_class': 'observation_derived',
         'observation_inputs': ['motion.accel'], 'history_observation_inputs': [], 'control_observation_inputs': []},
        {'index': 6, 'feature': 'f_mix_b', 'input_class': 'observation_derived',
         'observation_inputs': ['motion.accel'], 'history_observation_inputs': [], 'control_observation_inputs': []},
    ],
}


def build_database(path, frames=40, drop_accel_every=None):
    rng = np.random.default_rng(3)
    connection = sqlite3.connect(path)
    connection.execute('create table hot (id text primary key, ts real, raw text, frame text)')
    for i in range(frames):
        accel = float(rng.normal())
        obs = {'timestamp_utc': f'2026-10-02T00:00:{i:02d}.000000Z', 'motion': {'accel': accel},
               'digital_context': {'touch': 0.0}, 'place': {'status': 'UNAVAILABLE'}}
        if drop_accel_every and i % drop_accel_every == 0:
            del obs['motion']['accel']
        vector = [accel, 0.0, 0.0, accel * 2, 1.5, accel * 3, accel * -3]
        frame = {'continuous_vector': vector, 'normalizer_sha256': 'n' * 64, 'model_sha256': 'm' * 64}
        connection.execute('insert into hot values (?,?,?,?)',
                           (f'id{i}', 1000.0 + i, json.dumps(obs), json.dumps(frame)))
    connection.commit()
    connection.close()


class TestFeatureRuntimeEvidence(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.db = Path(self._tmp.name) / 'edge.sqlite'

    def collect(self, **kwargs):
        build_database(self.db, **kwargs)
        return collect(self.db, DATAFLOW)

    def feature(self, result, name):
        return next(item for item in result['features'] if item['feature'] == name)

    def test_observation_path_presence_and_variation(self):
        result = self.collect(drop_accel_every=4)
        accel = result['observation_paths']['motion.accel']
        self.assertEqual(accel['present_fraction'], 0.75)
        self.assertTrue(accel['varies'])
        self.assertEqual(result['observation_paths']['digital_context.touch']['present_fraction'], 1.0)
        self.assertFalse(result['observation_paths']['digital_context.touch']['varies'])
        self.assertEqual(result['observation_paths']['place.sats']['present_fraction'], 0.0)

    def test_runtime_classes_separate_measured_placeholder_absent_and_partial(self):
        result = self.collect()
        self.assertEqual(self.feature(result, 'f_direct')['runtime_class'], 'all_listed_inputs_present_and_varying')
        self.assertEqual(self.feature(result, 'f_constant_input')['runtime_class'], 'inputs_absent_or_constant')
        self.assertEqual(self.feature(result, 'f_absent_input')['runtime_class'], 'inputs_absent_or_constant')
        self.assertEqual(self.feature(result, 'f_partial')['runtime_class'], 'partial_inputs')
        self.assertEqual(self.feature(result, 'f_partial')['never_present_inputs'], ['motion.override'])
        self.assertEqual(self.feature(result, 'f_constant_out')['runtime_class'], 'constant_output')

    def test_output_statistics_expose_constant_dimensions(self):
        result = self.collect()
        constant = self.feature(result, 'f_constant_out')['output']
        self.assertEqual(constant['distinct_count'], 1)
        self.assertEqual(constant['modal_fraction'], 1.0)
        varying = self.feature(result, 'f_direct')['output']
        self.assertGreater(varying['std'], 0.1)

    def test_clip_fraction_reports_saturation_at_the_robust_scaling_bound(self):
        build_database(self.db)
        connection = sqlite3.connect(self.db)
        for i, (row_id, frame) in enumerate(connection.execute('select id, frame from hot order by ts').fetchall()):
            payload = json.loads(frame)
            if i % 2 == 0:
                payload['continuous_vector'][0] = 10.0
            elif i % 4 == 1:
                payload['continuous_vector'][0] = -10.0
            connection.execute('update hot set frame=? where id=?', (json.dumps(payload), row_id))
        connection.commit()
        connection.close()
        result = collect(self.db, DATAFLOW)
        self.assertEqual(self.feature(result, 'f_direct')['output']['clip_fraction'], 0.75)
        self.assertEqual(self.feature(result, 'f_absent_input')['output']['clip_fraction'], 0.0)

    def test_effective_dimension_counts_independent_factors(self):
        result = self.collect(frames=400)
        effective = result['effective_dimension']
        # f_direct, f_partial(2x), f_mix_a(3x), f_mix_b(-3x) are one factor; the rest are constant.
        self.assertEqual(effective['constant_dimension_count'], 3)
        self.assertEqual(effective['varying_dimension_count'], 4)
        self.assertEqual(effective['numerical_rank'], 1)
        self.assertAlmostEqual(effective['participation_ratio'], 1.0, places=3)
        self.assertEqual(effective['dimensions_for_variance']['0.99'], 1)

    def test_report_binds_sources_and_contains_no_raw_values(self):
        result = self.collect()
        self.assertEqual(result['frames']['analyzed'], 40)
        self.assertEqual(result['frames']['normalizer_sha256_counts'], {'n' * 64: 40})
        self.assertEqual(result['dataflow_source_sha256'], 'x' * 64)
        text = json.dumps(result)
        self.assertNotIn('2026-10-02T00:00', text.replace(result['frames']['first_timestamp_utc'], '')
                         .replace(result['frames']['last_timestamp_utc'], ''))

    def test_database_is_not_modified(self):
        build_database(self.db)
        before = hashlib.sha256(self.db.read_bytes()).hexdigest()
        collect(self.db, DATAFLOW)
        self.assertEqual(hashlib.sha256(self.db.read_bytes()).hexdigest(), before)

    def test_malformed_rows_are_counted_not_fatal(self):
        build_database(self.db)
        connection = sqlite3.connect(self.db)
        connection.execute("insert into hot values ('bad', 2000.0, 'not json', '{}')")
        connection.execute("insert into hot values ('short', 2001.0, '{}', ?)",
                           (json.dumps({'continuous_vector': [1.0]}),))
        connection.commit()
        connection.close()
        result = collect(self.db, DATAFLOW)
        self.assertEqual(result['frames']['analyzed'], 40)
        self.assertEqual(result['frames']['rejected'], 2)

    def test_cli_writes_report(self):
        build_database(self.db)
        dataflow = Path(self._tmp.name) / 'dataflow.json'
        dataflow.write_text(json.dumps(DATAFLOW))
        output = Path(self._tmp.name) / 'evidence.json'
        subprocess.run([sys.executable, str(ROOT / 'scripts/audit_feature_runtime_evidence.py'),
                        '--sqlite-file', str(self.db), '--dataflow-file', str(dataflow),
                        '--output-file', str(output)], check=True, cwd=ROOT, capture_output=True)
        self.assertEqual(json.loads(output.read_text())['frames']['analyzed'], 40)


if __name__ == '__main__':
    unittest.main()
