import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from traceform.representation.observability_registry import build_registry, producer_literals

ROOT = Path(__file__).resolve().parents[1]


def dataflow_entry(index, name, observation=(), history=(), control=()):
    return {'index': index, 'feature': name, 'observation_inputs': list(observation),
            'history_observation_inputs': list(history), 'control_observation_inputs': list(control),
            'input_class': 'observation_derived'}


DATAFLOW = {'source_sha256': 's' * 64, 'features': [
    dataflow_entry(0, 'measured', ['motion.accel']),
    dataflow_entry(1, 'never_produced', ['place.pdop', 'place.pdop_alias']),
    dataflow_entry(2, 'partly_produced', ['motion.accel', 'motion.override']),
    dataflow_entry(3, 'indicator', control=['device_relation.plugged']),
    dataflow_entry(4, 'placeholder', ['audio_context.rms']),
    dataflow_entry(5, 'proxy', ['digital_context.app', 'digital_context.touch']),
]}
LITERALS = {'accel', 'plugged', 'rms', 'app', 'touch', 'timestamp_utc'}


def ledger(tiers, saturated=()):
    return {'frames_analyzed': 10, 'features': [
        {'index': i, 'feature': name, 'claim_tier': tier, 'runtime_class': 'x',
         'saturated_at_clip': name in saturated, 'clip_fraction': 1.0 if name in saturated else 0.0}
        for i, (name, tier) in enumerate(tiers.items())]}


class TestProducerLiterals(unittest.TestCase):
    def test_collects_string_constants_from_python_files_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'a.py').write_text('x = {"alpha": 1}\ny["beta"] = 2\n')
            (root / 'sub').mkdir()
            (root / 'sub/b.py').write_text("z = 'gamma'\n")
            (root / 'c.txt').write_text("'delta'")
            (root / '__pycache__').mkdir()
            (root / '__pycache__/d.py').write_text("w = 'epsilon'")
            self.assertEqual(producer_literals([root]), {'alpha', 'beta', 'gamma'})


class TestStaticRegistry(unittest.TestCase):
    def test_fields_without_any_producer_are_statically_unobservable(self):
        registry = build_registry(DATAFLOW, LITERALS)
        by_name = {item['feature']: item for item in registry['features']}
        self.assertEqual(by_name['never_produced']['status'], 'unobservable_in_current_acquisition')
        self.assertEqual(by_name['never_produced']['basis'], 'static_no_producer')
        self.assertEqual(by_name['never_produced']['unproduced_inputs'], ['place.pdop', 'place.pdop_alias'])

    def test_static_view_does_not_judge_runtime_behaviour(self):
        by_name = {item['feature']: item for item in build_registry(DATAFLOW, LITERALS)['features']}
        self.assertEqual(by_name['measured']['status'], 'not_assessed_at_runtime')
        self.assertEqual(by_name['partly_produced']['status'], 'not_assessed_at_runtime')
        self.assertEqual(by_name['partly_produced']['unproduced_inputs'], ['motion.override'])

    def test_condition_selected_outputs_use_their_tested_fields(self):
        by_name = {item['feature']: item for item in build_registry(DATAFLOW, LITERALS)['features']}
        self.assertEqual(by_name['indicator']['status'], 'not_assessed_at_runtime')


class TestRuntimeRegistry(unittest.TestCase):
    def setUp(self):
        tiers = {'measured': 'supplied_inputs_vary', 'never_produced': 'no_informative_input',
                 'partly_produced': 'proxy_from_supplied_inputs', 'indicator': 'supplied_inputs_vary',
                 'placeholder': 'no_informative_input', 'proxy': 'proxy_from_supplied_inputs'}
        self.registry = build_registry(DATAFLOW, LITERALS, ledger(tiers, saturated={'measured'}))
        self.by_name = {item['feature']: item for item in self.registry['features']}

    def test_static_evidence_takes_precedence_over_runtime(self):
        self.assertEqual(self.by_name['never_produced']['basis'], 'static_no_producer')

    def test_runtime_only_unobservable_is_marked_as_device_derived(self):
        item = self.by_name['placeholder']
        self.assertEqual(item['status'], 'unobservable_in_current_acquisition')
        self.assertEqual(item['basis'], 'runtime_absent_or_constant_device_window')

    def test_other_tiers_map_to_statuses(self):
        self.assertEqual(self.by_name['measured']['status'], 'observed_inputs')
        self.assertEqual(self.by_name['proxy']['status'], 'proxy_from_observed_inputs')
        self.assertEqual(self.by_name['partly_produced']['status'], 'proxy_from_observed_inputs')

    def test_saturation_is_reported_separately_from_observability(self):
        self.assertTrue(self.by_name['measured']['normalization_saturated'])
        self.assertEqual(self.by_name['measured']['status'], 'observed_inputs')

    def test_saturation_is_recorded_even_for_statically_unobservable_outputs(self):
        tiers = {'measured': 'supplied_inputs_vary', 'never_produced': 'no_informative_input',
                 'partly_produced': 'proxy_from_supplied_inputs', 'indicator': 'supplied_inputs_vary',
                 'placeholder': 'no_informative_input', 'proxy': 'proxy_from_supplied_inputs'}
        registry = build_registry(DATAFLOW, LITERALS, ledger(tiers, saturated={'never_produced', 'placeholder'}))
        by_name = {item['feature']: item for item in registry['features']}
        self.assertTrue(by_name['never_produced']['normalization_saturated'])
        self.assertEqual(by_name['never_produced']['basis'], 'static_no_producer')
        self.assertTrue(by_name['placeholder']['normalization_saturated'])
        self.assertEqual(registry['summary']['normalization_saturated_count'], 2)

    def test_summary_counts(self):
        summary = self.registry['summary']
        self.assertEqual(summary['status_counts'], {'observed_inputs': 2, 'proxy_from_observed_inputs': 2,
                                                    'unobservable_in_current_acquisition': 2})
        self.assertEqual(summary['statically_unobservable_count'], 1)
        self.assertEqual(summary['normalization_saturated_count'], 1)

    def test_mismatched_ledger_is_rejected(self):
        with self.assertRaises(ValueError):
            build_registry(DATAFLOW, LITERALS, ledger({'other': 'supplied_inputs_vary'}))

    def test_registry_never_claims_measurement(self):
        text = json.dumps(self.registry)
        self.assertIn('not_measurement_validation', text)


class TestRegistryOnTheRepository(unittest.TestCase):
    def test_cli_static_registry_marks_known_unproduced_gnss_and_cellular_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'registry.json'
            subprocess.run([sys.executable, str(ROOT / 'scripts/build_feature_observability_registry.py'),
                            '--output-file', str(output)], check=True, cwd=ROOT, capture_output=True)
            registry = json.loads(output.read_text())
        by_name = {item['feature']: item for item in registry['features']}
        for name in ('gnss_position_dilution_of_precision', 'cellular_timing_advance_distance',
                     'touch_contact_area_normalized', 'cpu_user_system_mode_ratio'):
            self.assertEqual(by_name[name]['status'], 'unobservable_in_current_acquisition', name)
        self.assertEqual(by_name['motion_accel_magnitude']['status'], 'unobservable_in_current_acquisition')
        self.assertEqual(by_name['motion_accel_magnitude']['basis'], 'static_no_producer')
        self.assertEqual(len(registry['features']), 128)


if __name__ == '__main__':
    unittest.main()
