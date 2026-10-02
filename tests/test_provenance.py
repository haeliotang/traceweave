import unittest

from traceform.forecasting.continuity import same_stream_segment
from traceform.representation.provenance import content_digest, input_inventory


class TestProvenance(unittest.TestCase):
    def test_digest_binds_values_not_dictionary_order(self):
        self.assertEqual(content_digest({"x": 1, "y": 2}), content_digest({"y": 2, "x": 1}))
        self.assertNotEqual(content_digest({"x": 1}), content_digest({"x": 2}))

    def test_missing_and_supplied_do_not_claim_measurement(self):
        inventory = input_inventory({"motion": {"energy": 0.0, "hurst": None, "bad": float("nan")}})
        self.assertEqual(inventory["supplied_paths"], ["motion.energy"])
        self.assertEqual(inventory["missing_paths"], ["motion.bad", "motion.hurst"])

    def test_discontinuities_are_not_transitions(self):
        first = {"model_version": "v1", "continuity_epoch": 1, "source_mode": "live_follow", "boot_id": "a"}
        self.assertTrue(same_stream_segment(first, dict(first)))
        for field, value in [("model_version", "v2"), ("continuity_epoch", 2), ("source_mode", "historical_replay"), ("boot_id", "b"), ("continuous_from_previous", False)]:
            self.assertFalse(same_stream_segment(first, dict(first, **{field: value})))
