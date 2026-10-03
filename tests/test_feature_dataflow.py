import hashlib
import json
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

from traceform.representation.feature_dataflow import analyze_source

ROOT = Path(__file__).resolve().parents[1]
NORMALIZER = ROOT / 'traceform/representation/normalizer.py'

SYNTHETIC = textwrap.dedent('''
    class N:
        def extract_raw_features(self, obs):
            mot = obs.get("motion", {})
            env = obs.get("environment", {})
            plc = obs.get("place", {})
            a = safe_float(mot.get("accel_magnitude"), 9.8)
            p = safe_float(env.get("pressure_hpa"), 915.0)
            if self._last_p is not None:
                v = (p - self._last_p) * 2.0
            else:
                v = 0.0
            self._last_p = p
            self._win.append(a)
            s = sum(self._win) / len(self._win) if len(self._win) >= 2 else 0.0
            stationary_flag = 1.0 if mot.get("stationary") else 0.0
            if plc.get("lat") is not None:
                sats = 12.0
            else:
                sats = 0.0
            lat = plc.get("lat")
            raw = mot.get("accel_raw")
            z = raw[2] if raw and len(raw) >= 3 else 0.0
            stamp = obs.get("timestamp_utc")
            hour = len(str(stamp))
            alias = plc.get("sat_count") or plc.get("gnss_sat_count")
            continuous_map = {
                "a_feat": a,
                "v_feat": v,
                "s_feat": s,
                "stationary_flag": stationary_flag,
                "sats_feat": sats,
                "z_feat": z,
                "hour_feat": hour,
                "const_feat": 1.0,
                "alias_feat": alias,
            }
            return continuous_map, {}
''')


class TestFeatureDataflow(unittest.TestCase):
    def setUp(self):
        self.result = analyze_source(SYNTHETIC)
        self.by_name = {item['feature']: item for item in self.result['features']}

    def test_feature_order_follows_the_output_mapping(self):
        self.assertEqual([item['feature'] for item in self.result['features']],
                         ['a_feat', 'v_feat', 's_feat', 'stationary_flag', 'sats_feat', 'z_feat',
                          'hour_feat', 'const_feat', 'alias_feat'])
        self.assertEqual([item['index'] for item in self.result['features']], list(range(9)))

    def test_direct_observation_inputs_resolve_through_aliases_and_defaults(self):
        self.assertEqual(self.by_name['a_feat']['observation_inputs'], ['motion.accel_magnitude'])
        self.assertEqual(self.by_name['z_feat']['observation_inputs'], ['motion.accel_raw'])

    def test_or_fallback_keeps_both_alias_inputs(self):
        self.assertEqual(self.by_name['alias_feat']['observation_inputs'],
                         ['place.gnss_sat_count', 'place.sat_count'])

    def test_history_state_exposes_previous_frame_inputs(self):
        velocity = self.by_name['v_feat']
        self.assertEqual(velocity['observation_inputs'], ['environment.pressure_hpa'])
        self.assertEqual(velocity['history_state'], ['_last_p'])
        self.assertEqual(velocity['history_observation_inputs'], ['environment.pressure_hpa'])
        self.assertEqual(velocity['uses_history'], True)
        self.assertEqual(self.by_name['a_feat']['uses_history'], False)

    def test_method_mutated_buffers_count_as_history(self):
        window = self.by_name['s_feat']
        self.assertEqual(window['history_state'], ['_win'])
        self.assertEqual(window['history_observation_inputs'], ['motion.accel_magnitude'])

    def test_constant_chosen_by_another_fields_presence_is_condition_selected(self):
        sats = self.by_name['sats_feat']
        self.assertEqual(sats['observation_inputs'], [])
        self.assertEqual(sats['control_observation_inputs'], ['place.lat'])
        self.assertEqual(sats['input_class'], 'condition_selected')
        self.assertFalse(sats['condition_field_named_in_output'])

    def test_indicator_of_a_named_field_is_condition_selected_but_flagged_as_named(self):
        flag = self.by_name['stationary_flag']
        self.assertEqual(flag['observation_inputs'], [])
        self.assertEqual(flag['control_observation_inputs'], ['motion.stationary'])
        self.assertEqual(flag['input_class'], 'condition_selected')
        self.assertTrue(flag['condition_field_named_in_output'])

    def test_pure_constant_has_no_inputs(self):
        self.assertEqual(self.by_name['const_feat']['input_class'], 'no_observation_input')

    def test_timestamp_only_feature_is_time_derived(self):
        self.assertEqual(self.by_name['hour_feat']['input_class'], 'time_only')

    def test_modality_groups_and_class_for_ordinary_inputs(self):
        self.assertEqual(self.by_name['a_feat']['modalities'], ['motion'])
        self.assertEqual(self.by_name['a_feat']['input_class'], 'observation_derived')
        self.assertEqual(self.by_name['s_feat']['input_class'], 'history_derived')
        self.assertEqual(self.by_name['v_feat']['input_class'], 'history_derived')

    def test_output_is_deterministic_and_binds_the_source_digest(self):
        again = analyze_source(SYNTHETIC)
        self.assertEqual(json.dumps(self.result, sort_keys=True), json.dumps(again, sort_keys=True))
        self.assertEqual(self.result['source_sha256'], hashlib.sha256(SYNTHETIC.encode()).hexdigest())

    def test_missing_extraction_function_is_rejected(self):
        with self.assertRaises(ValueError):
            analyze_source('class N:\n    def other(self):\n        return 1\n')


class TestRealNormalizerDataflow(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.result = analyze_source(NORMALIZER.read_text())
        cls.by_name = {item['feature']: item for item in cls.result['features']}

    def test_all_128_outputs_are_analyzed_in_inventory_order(self):
        inventory = json.loads((ROOT / 'artifacts/representation_v1/continuous_feature_source_inventory.json').read_text())
        self.assertEqual([item['feature'] for item in self.result['features']],
                         [item['feature'] for item in inventory['features']])
        self.assertEqual(len(self.result['features']), 128)

    def test_known_direct_measurements(self):
        self.assertEqual(self.by_name['motion_accel_magnitude']['observation_inputs'], ['motion.accel_magnitude'])
        self.assertEqual(self.by_name['device_battery_fraction']['observation_inputs'],
                         ['device_relation.battery_percentage'])
        self.assertEqual(self.by_name['conn_wifi_rssi_norm']['observation_inputs'], ['connectivity.wifi_rssi'])

    def test_known_history_dependent_outputs(self):
        vertical = self.by_name['barometric_vertical_velocity']
        self.assertIn('environment.pressure_hpa', vertical['observation_inputs'] + vertical['history_observation_inputs'])
        self.assertTrue(vertical['uses_history'])

    def test_every_feature_has_a_classification(self):
        allowed = {'observation_derived', 'history_derived', 'condition_selected', 'time_only',
                   'no_observation_input'}
        self.assertTrue({item['input_class'] for item in self.result['features']} <= allowed)

    def test_cli_writes_the_same_analysis(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'dataflow.json'
            subprocess.run([sys.executable, str(ROOT / 'scripts/audit_feature_dataflow.py'),
                            '--output-file', str(output)], check=True, cwd=ROOT, capture_output=True)
            written = json.loads(output.read_text())
        self.assertEqual(written['source_sha256'], self.result['source_sha256'])
        self.assertEqual(len(written['features']), 128)


if __name__ == '__main__':
    unittest.main()


class TestBackwardSlice(unittest.TestCase):
    def test_slice_contains_only_statements_that_feed_the_output(self):
        from traceform.representation.feature_dataflow import backward_slice
        lines = backward_slice(SYNTHETIC, 'v_feat')
        text = SYNTHETIC.splitlines()
        shown = [text[number - 1].strip() for number in lines]
        self.assertIn('p = safe_float(env.get("pressure_hpa"), 915.0)', shown)
        self.assertIn('v = (p - self._last_p) * 2.0', shown)
        self.assertIn('self._last_p = p', shown)
        self.assertNotIn('stationary_flag = 1.0 if mot.get("stationary") else 0.0', shown)

    def test_unknown_feature_is_rejected(self):
        from traceform.representation.feature_dataflow import backward_slice
        with self.assertRaises(ValueError):
            backward_slice(SYNTHETIC, 'no_such_feature')

    def test_union_over_features_is_sorted_and_deduplicated(self):
        from traceform.representation.feature_dataflow import backward_slice
        lines = backward_slice(SYNTHETIC, ['a_feat', 'v_feat'])
        self.assertEqual(lines, sorted(set(lines)))
