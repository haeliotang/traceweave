import json
import math
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from traceform.representation.feature_ledger import build_ledger
from traceform.representation.proxy_identities import check_digital_context_identities

ROOT = Path(__file__).resolve().parents[1]


def runtime(feature, klass, varying=(), never=(), constant_out=False, clip=0.0):
    return {'index': 0, 'feature': feature, 'runtime_class': klass, 'varying_present_inputs': list(varying),
            'never_present_inputs': list(never), 'constant_present_inputs': [], 'output_constant': constant_out,
            'input_class': 'observation_derived', 'output': {'clip_fraction': clip}}


def evidence(items):
    for index, item in enumerate(items):
        item['index'] = index
    return {'frames': {'analyzed': 10}, 'features': items, 'effective_dimension': {'participation_ratio': 3.0},
            'observation_paths': {}}


def native(statuses):
    return {'features': [{'index': i, 'feature': name, 'static_status': status}
                         for i, (name, status) in enumerate(statuses)]}


class TestFeatureLedger(unittest.TestCase):
    def build(self):
        items = [
            runtime('a_measured', 'all_listed_inputs_present_and_varying', varying=['motion.x']),
            runtime('b_proxy', 'partial_inputs', varying=['digital_context.app'], never=['digital_context.touch']),
            runtime('c_empty', 'inputs_absent_or_constant', never=['place.sats']),
            runtime('d_flat', 'constant_output', varying=['motion.x'], constant_out=True),
            runtime('e_known', 'all_listed_inputs_present_and_varying', varying=['motion.x']),
            runtime('f_proxy_known', 'partial_inputs', varying=['motion.x'], never=['motion.y']),
        ]
        statuses = [('a_measured', 'unresolved_in_indexed_sources'), ('b_proxy', 'unresolved_in_indexed_sources'),
                    ('c_empty', 'unresolved_in_indexed_sources'), ('d_flat', 'literal_assignment_found'),
                    ('e_known', 'literal_assignment_found'), ('f_proxy_known', 'name_reference_only')]
        return build_ledger(evidence(items), native(statuses))

    def test_tiers_follow_the_runtime_class(self):
        by_name = {item['feature']: item for item in self.build()['features']}
        self.assertEqual(by_name['a_measured']['claim_tier'], 'supplied_inputs_vary')
        self.assertEqual(by_name['b_proxy']['claim_tier'], 'proxy_from_supplied_inputs')
        self.assertEqual(by_name['c_empty']['claim_tier'], 'no_informative_input')
        self.assertEqual(by_name['d_flat']['claim_tier'], 'no_informative_input')

    def test_native_actions_distinguish_portable_proxy_and_uninformative_features(self):
        by_name = {item['feature']: item for item in self.build()['features']}
        self.assertEqual(by_name['a_measured']['native_action'], 'port_formula_and_inputs')
        self.assertEqual(by_name['b_proxy']['native_action'], 'port_proxy_with_proxy_label')
        self.assertEqual(by_name['c_empty']['native_action'], 'do_not_port_as_measurement')
        self.assertEqual(by_name['e_known']['native_action'], 'located_verify_on_hardware')
        self.assertEqual(by_name['f_proxy_known']['native_action'], 'located_verify_on_hardware')
        self.assertEqual(by_name['d_flat']['native_action'], 'do_not_port_as_measurement')

    def test_summary_counts_and_driving_inputs(self):
        ledger = self.build()
        self.assertEqual(ledger['summary']['claim_tier_counts'],
                         {'no_informative_input': 2, 'proxy_from_supplied_inputs': 2, 'supplied_inputs_vary': 2})
        cross = ledger['summary']['native_status_by_tier']
        self.assertEqual(cross['unresolved_in_indexed_sources']['proxy_from_supplied_inputs'], 1)
        self.assertEqual(ledger['summary']['driving_observation_inputs'], ['digital_context.app', 'motion.x'])
        self.assertEqual(ledger['summary']['driving_observation_input_count'], 2)

    def test_saturation_is_flagged_independently_of_the_input_tier(self):
        items = [runtime('a', 'constant_output', varying=['motion.x'], constant_out=True, clip=1.0),
                 runtime('b', 'all_listed_inputs_present_and_varying', varying=['motion.x'], clip=0.49),
                 runtime('c', 'partial_inputs', varying=['motion.x'], never=['motion.y'], clip=0.5)]
        statuses = [(n, 'unresolved_in_indexed_sources') for n in 'abc']
        ledger = build_ledger(evidence(items), native(statuses))
        flags = {e['feature']: e['saturated_at_clip'] for e in ledger['features']}
        self.assertEqual(flags, {'a': True, 'b': False, 'c': True})
        self.assertEqual(ledger['summary']['saturated_dimension_count'], 2)
        by_name = {e['feature']: e for e in ledger['features']}
        self.assertEqual(by_name['a']['claim_tier'], 'no_informative_input')
        self.assertEqual(by_name['b']['clip_fraction'], 0.49)

    def test_feature_sets_must_match(self):
        items = [runtime('a', 'partial_inputs')]
        with self.assertRaises(ValueError):
            build_ledger(evidence(items), native([('different', 'unresolved_in_indexed_sources')]))

    def test_ledger_never_asserts_measurement(self):
        text = json.dumps(self.build())
        self.assertIn('not_measurement_validation', text)
        self.assertNotIn('"measured"', text)


def synthetic_digital_db(path, corrupt=False):
    connection = sqlite3.connect(path)
    connection.execute('create table hot (id text, ts real, raw text, frame text)')
    cases = [(0, 0.0, 0.0, 0.0), (6, 1.0, 3.0, 1.0), (30, 1.0, 4.0, 0.0), (9, 0.2, 0.0, 0.0)]
    for i, (aps, screen, app, unlock) in enumerate(cases * 5):
        density = round(min(30.0, aps / 3.0), 2) if aps > 0 else 0.0
        digital = {
            'screen_on_ratio': screen, 'app_switch_rate': app, 'unlock_burst_count': unlock,
            'ambient_social_ble_density': density,
            'ble_rssi_spatial_variance': round(min(25.0, density * 1.6), 2) if density > 1.0 else 0.0,
            'ambient_rf_shannon_entropy': round(min(5.0, math.log2(aps + 1.0) * 0.85), 3) if aps > 0 else 0.0,
        }
        velocity = round(min(10.0, 1.0 + app * 0.4 + unlock * 0.3), 3) if screen == 1.0 else 0.0
        digital['touch_interaction_velocity'] = velocity
        digital['touch_interval_jitter'] = round(
            min(2.0, (app * 0.25 + unlock * 0.2) / (1.0 + velocity * 0.2)), 3) if screen == 1.0 else 0.0
        if corrupt and i == 3:
            digital['ambient_rf_shannon_entropy'] += 0.5
        raw = {'digital_context': digital, 'connectivity': {'wifi_ap_count': aps}}
        connection.execute('insert into hot values (?,?,?,?)', (f'i{i}', float(i), json.dumps(raw), '{}'))
    connection.commit()
    connection.close()


class TestProxyIdentities(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.db = Path(self._tmp.name) / 'edge.sqlite'

    def test_exact_formula_relationships_hold_on_consistent_frames(self):
        synthetic_digital_db(self.db)
        result = check_digital_context_identities(self.db)
        self.assertEqual(result['frames_with_digital_context'], 20)
        for name, check in result['identities'].items():
            self.assertEqual(check['matches'], check['checked'], name)
            self.assertGreater(check['checked'], 0, name)
            self.assertEqual(check['max_abs_error'], 0.0, name)

    def test_violations_are_counted(self):
        synthetic_digital_db(self.db, corrupt=True)
        result = check_digital_context_identities(self.db)
        entropy = result['identities']['rf_entropy_from_wifi_ap_count']
        self.assertEqual(entropy['matches'], entropy['checked'] - 1)
        self.assertGreaterEqual(entropy['max_abs_error'], 0.5)

    def test_cli_runs_read_only(self):
        synthetic_digital_db(self.db)
        output = Path(self._tmp.name) / 'identities.json'
        subprocess.run([sys.executable, str(ROOT / 'scripts/audit_digital_context_proxies.py'),
                        '--sqlite-file', str(self.db), '--output-file', str(output)],
                       check=True, cwd=ROOT, capture_output=True)
        self.assertEqual(json.loads(output.read_text())['frames_with_digital_context'], 20)


if __name__ == '__main__':
    unittest.main()
