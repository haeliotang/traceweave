import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from test_normalizer_candidate import observation, synthetic_database

from traceform.evolution.model_registry import ModelRegistry
from traceform.representation.manifold_lattice import DynamicalEnergyManifold
from traceform.representation.normalizer import MultimodalNormalizer
from traceform.representation.provenance import content_digest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'scripts/build_normalizer_candidate_bundle.py'
LOOSE = ['--coverage-fraction', '0.0', '--topographic-slack', '1.0']


class BundleCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory()
        root = Path(cls._tmp.name)
        cls.database = root / 'edge.sqlite'
        params = synthetic_database(cls.database)
        cls.registry_dir = root / 'registry'
        registry = ModelRegistry(cls.registry_dir)
        frames = [observation(i, pressure=913.0 + (i % 5) * 0.01 if i < 120 else 913.0 + (i - 120) * 0.05) for i in range(240)]
        manifold = DynamicalEnergyManifold(grid_height=4, grid_width=4, kinetic_weight=0.25)
        manifold.fit_discovery_window(frames[:120], MultimodalNormalizer(params), epochs=2, seed=42)
        cls.baseline_version = registry.register(manifold.to_dict(), params.to_dict(), {'accepted': True})
        registry.activate(cls.baseline_version)
        baseline_file = root / 'baseline.json'
        baseline_file.write_text(json.dumps(params.to_dict()))
        cls.report_file = root / 'evaluation.json'
        cls.candidate_file = root / 'candidate.json'
        subprocess.run([sys.executable, str(ROOT / 'scripts/evaluate_normalizer_candidate.py'),
                        '--sqlite-file', str(cls.database), '--baseline-normalizer-file', str(baseline_file),
                        '--output-file', str(cls.report_file), '--candidate-normalizer-file', str(cls.candidate_file)],
                       check=True, cwd=ROOT, capture_output=True)
        # This module tests the bundling step, not the evaluation: synthetic data need not pass every
        # evaluation check, so pin them true while keeping the real baseline/candidate digest bindings.
        report = json.loads(cls.report_file.read_text())
        report['acceptance']['checks'] = {name: True for name in report['acceptance']['checks']}
        cls.report_file.write_text(json.dumps(report))
        cls.root = root

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    def fresh_registry(self):
        """A private copy of the baseline registry so tests cannot affect each other."""
        target = Path(tempfile.mkdtemp(dir=self.root))
        for path in self.registry_dir.glob('*.json'):
            (target / path.name).write_bytes(path.read_bytes())
        return target

    def edited_report(self, mutate):
        report = json.loads(self.report_file.read_text())
        mutate(report)
        path = Path(tempfile.mkdtemp(dir=self.root)) / 'evaluation.json'
        path.write_text(json.dumps(report))
        return path

    def run_script(self, registry, report=None, candidate=None, extra=(), check=False):
        output = Path(tempfile.mkdtemp(dir=self.root)) / 'receipt.json'
        result = subprocess.run(
            [sys.executable, str(SCRIPT), '--sqlite-file', str(self.database), '--registry', str(registry),
             '--evaluation-report', str(report or self.report_file),
             '--candidate-normalizer-file', str(candidate or self.candidate_file),
             '--output-file', str(output), *extra], cwd=ROOT, capture_output=True, text=True)
        if check:
            self.assertEqual(result.returncode, 0, result.stderr)
        return result, output


class TestCandidateBundle(BundleCase):
    def test_registers_without_activating_unless_asked(self):
        registry = self.fresh_registry()
        result, output = self.run_script(registry, extra=LOOSE, check=True)
        receipt = json.loads(output.read_text())
        self.assertFalse(receipt['activated'])
        self.assertEqual(ModelRegistry(registry).active()['version'], self.baseline_version)
        bundle = ModelRegistry(registry).bundle(receipt['candidate_version'])
        self.assertTrue(bundle['validation']['accepted'])
        self.assertEqual(bundle['validation']['parent_registry_version'], self.baseline_version)
        self.assertEqual(bundle['validation']['calibration_mode'], 'normalizer_refit_robust_range_v2')
        self.assertEqual(bundle['normalizer']['fit_window_description'].split(':')[0], 'robust_range_v2')
        expected_namespace = content_digest({'manifold': content_digest(bundle['manifold']),
                                             'normalizer': content_digest(bundle['normalizer'])})
        self.assertEqual(bundle['validation']['cold_state_namespace'], expected_namespace)

    def test_activation_moves_the_pointer_and_keeps_a_rollback_target(self):
        registry = self.fresh_registry()
        result, output = self.run_script(registry, extra=LOOSE + ['--activate'], check=True)
        receipt = json.loads(output.read_text())
        self.assertTrue(receipt['activated'])
        pointer = json.loads((registry / 'current.json').read_text())
        self.assertEqual(pointer['version'], receipt['candidate_version'])
        self.assertEqual(pointer['previous'], self.baseline_version)
        self.assertEqual(ModelRegistry(registry).rollback(), self.baseline_version)

    def test_rerun_is_idempotent_and_never_overwrites_the_rollback_target(self):
        registry = self.fresh_registry()
        self.run_script(registry, extra=LOOSE + ['--activate'], check=True)
        result, output = self.run_script(registry, extra=LOOSE + ['--activate'], check=True)
        receipt = json.loads(output.read_text())
        self.assertEqual(receipt['status'], 'already_active')
        pointer = json.loads((registry / 'current.json').read_text())
        self.assertEqual(pointer['previous'], self.baseline_version)

    def test_a_failed_manifold_gate_registers_but_never_activates(self):
        registry = self.fresh_registry()
        result, output = self.run_script(registry, extra=['--coverage-fraction', '2.0', '--activate'], check=True)
        receipt = json.loads(output.read_text())
        self.assertFalse(receipt['activated'])
        self.assertFalse(receipt['gates']['coverage']['passed'])
        self.assertEqual(ModelRegistry(registry).active()['version'], self.baseline_version)
        self.assertFalse(ModelRegistry(registry).bundle(receipt['candidate_version'])['validation']['accepted'])

    def test_unaccepted_evaluation_is_refused_before_anything_is_registered(self):
        registry = self.fresh_registry()
        before = sorted(p.name for p in registry.glob('*.json'))
        def fail(report):
            report['acceptance']['checks']['forecast_not_worse'] = False
        result, _ = self.run_script(registry, report=self.edited_report(fail), extra=LOOSE + ['--activate'])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('evaluation did not pass', result.stderr)
        self.assertEqual(sorted(p.name for p in registry.glob('*.json')), before)

    def test_evaluation_against_a_different_baseline_is_refused(self):
        def other(report):
            report['baseline_normalizer_sha256'] = '0' * 64
        result, _ = self.run_script(self.fresh_registry(), report=self.edited_report(other), extra=LOOSE)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('baseline', result.stderr)

    def test_a_candidate_file_that_differs_from_the_evaluated_one_is_refused(self):
        tampered = json.loads(self.candidate_file.read_text())
        tampered['iqrs']['env_pressure_hpa'] = tampered['iqrs']['env_pressure_hpa'] * 2
        path = Path(tempfile.mkdtemp(dir=self.root)) / 'candidate.json'
        path.write_text(json.dumps(tampered))
        result, _ = self.run_script(self.fresh_registry(), candidate=path, extra=LOOSE)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('candidate', result.stderr)

    def test_receipt_records_the_declared_gates_and_contains_no_observation_values(self):
        result, output = self.run_script(self.fresh_registry(), extra=LOOSE, check=True)
        receipt = json.loads(output.read_text())
        self.assertEqual(set(receipt['gates']), {'coverage', 'topography', 'finite_distance'})
        self.assertEqual(receipt['gate_parameters'], {'coverage_fraction': 0.0, 'topographic_slack': 1.0, 'epochs': 6})
        self.assertNotIn('latitude', json.dumps(receipt))


if __name__ == '__main__':
    unittest.main()
