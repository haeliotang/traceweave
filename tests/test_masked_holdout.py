import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.validate_continuous_holdout import validate
from traceform.representation.observability_registry import select_indices

ROOT = Path(__file__).resolve().parents[1]


def rows(start, count=6):
    return [{'timestamp_utc': f'2026-10-02T00:00:{i:02d}Z',
             'continuous_vector': [2.0 * i, 0.5, float(((i * 7) % 5) - 2)],
             'state_token': 'A', 'model_version': 'v1', 'model_sha256': '1' * 64,
             'normalizer_sha256': '2' * 64, 'boot_id': 'b', 'continuity_epoch': 1,
             'source_mode': 'synthetic'} for i in range(start, start + count)]


class TestMaskedHoldout(unittest.TestCase):
    def test_increment_only_diagnostics_match_full_and_retain_binding_guards(self):
        train, holdout = rows(0), rows(6, count=12)
        for index, record in enumerate(holdout):
            record['timestamp_utc'] = f'2026-10-{3 + index // 6:02d}T00:00:{index % 6:02d}Z'
        for keep in (None, [0, 2]):
            full = validate(train, holdout, dimension=3, keep_indices=keep)
            lean = validate(train, holdout, dimension=3, keep_indices=keep,
                            include_covariance=False)
            for key in ('drift_increment_mse', 'persistence_increment_mse',
                        'training_pair_count', 'holdout_pair_count', 'interval_strata',
                        'paired_day_bootstrap_95_interval', 'coordinate_bindings'):
                self.assertEqual(lean[key], full[key], key)
            self.assertIsNone(lean['predicted_covariance_rate'])
            self.assertIsNone(lean['residual_covariance_rate'])
            self.assertIsNotNone(lean['paired_day_bootstrap_95_interval'])
        wrong = copy.deepcopy(holdout)
        for record in wrong:
            record['model_sha256'] = '3' * 64
        with self.assertRaisesRegex(ValueError, 'bindings must match'):
            validate(train, wrong, dimension=3, include_covariance=False)

    def test_subset_equals_validating_pre_masked_records(self):
        train, holdout = rows(0), rows(6)
        masked = validate(train, holdout, dimension=3, keep_indices=[0])
        manual_train, manual_holdout = copy.deepcopy(train), copy.deepcopy(holdout)
        for record in manual_train + manual_holdout:
            record['continuous_vector'] = record['continuous_vector'][:1]
        manual = validate(manual_train, manual_holdout, dimension=1)
        for key in ('drift_increment_mse', 'persistence_increment_mse', 'training_pair_count', 'holdout_pair_count'):
            self.assertEqual(masked[key], manual[key], key)
        self.assertEqual(masked['dimension'], 1)
        self.assertEqual(masked['dimension_subset'], {'kept': 1, 'of': 3, 'indices': [0]})

    def test_dropping_noise_dimensions_changes_the_skill_measure(self):
        train, holdout = rows(0), rows(6)
        full = validate(train, holdout, dimension=3)
        trend_only = validate(train, holdout, dimension=3, keep_indices=[0])
        self.assertLess(trend_only['drift_increment_mse'] / trend_only['persistence_increment_mse'],
                        full['drift_increment_mse'] / full['persistence_increment_mse'])

    def test_default_behaviour_is_unchanged_and_has_no_subset_field(self):
        result = validate(rows(0), rows(6), dimension=3)
        self.assertNotIn('dimension_subset', result)
        self.assertEqual(result['dimension'], 3)

    def test_invalid_subsets_are_rejected(self):
        for bad in ([], [3], [-1], [0, 0]):
            with self.assertRaises(ValueError):
                validate(rows(0), rows(6), dimension=3, keep_indices=bad)


REGISTRY = {'features': [
    {'index': 0, 'feature': 'a', 'status': 'observed_inputs', 'normalization_saturated': False},
    {'index': 1, 'feature': 'b', 'status': 'proxy_from_observed_inputs', 'normalization_saturated': True},
    {'index': 2, 'feature': 'c', 'status': 'unobservable_in_current_acquisition', 'normalization_saturated': False},
    {'index': 3, 'feature': 'd', 'status': 'observed_inputs'}]}


class TestSelectIndices(unittest.TestCase):
    def test_selects_by_status(self):
        self.assertEqual(select_indices(REGISTRY, {'observed_inputs'}), [0, 3])
        self.assertEqual(select_indices(REGISTRY, {'observed_inputs', 'proxy_from_observed_inputs'}), [0, 1, 3])

    def test_can_exclude_saturated_dimensions(self):
        self.assertEqual(select_indices(REGISTRY, {'observed_inputs', 'proxy_from_observed_inputs'},
                                        exclude_saturated=True), [0, 3])

    def test_unknown_status_is_rejected(self):
        with self.assertRaises(ValueError):
            select_indices(REGISTRY, {'nonsense'})

    def test_empty_selection_is_rejected(self):
        with self.assertRaises(ValueError):
            select_indices({'features': []}, {'observed_inputs'})


if __name__ == '__main__':
    unittest.main()
