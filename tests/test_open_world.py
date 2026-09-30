"""
Tests for Open-World Continuous Representation Lattice Manager.
"""

import unittest

from traceform.representation.lattice import TopologicalKineticLattice
from traceform.representation.open_world import OpenWorldLatticeManager, OpenWorldStats


class TestOpenWorld(unittest.TestCase):
    def setUp(self):
        self.lattice = TopologicalKineticLattice()
        # Seed 2 core states via quantize
        obs1 = {
            "connectivity": {"wifi_fingerprint": "home"},
            "motion": {"stationary_likelihood": 0.95, "motion_energy": 0.05},
            "device_relation": {"status": "CHARGING", "plugged": "AC"},
            "environment": {"ambient_light_lux": 0.5},
        }
        obs2 = {
            "connectivity": {"wifi_fingerprint": "home"},
            "motion": {"stationary_likelihood": 0.95, "motion_energy": 0.05},
            "device_relation": {"status": "DISCHARGING", "plugged": "UNPLUGGED"},
            "environment": {"ambient_light_lux": 25.0},
        }
        ms1 = self.lattice.quantize(obs1, allow_new_states=True)
        ms2 = self.lattice.quantize(obs2, allow_new_states=True)

        self.core_sig1 = ms1.signature
        self.core_sig2 = ms2.signature

        self.manager = OpenWorldLatticeManager(
            base_lattice=self.lattice,
            tier1_core_states={self.core_sig1, self.core_sig2},
            promotion_threshold=3,
            max_vocab_size=5,
        )

    def test_core_states_fast_path(self):
        obs = {
            "connectivity": {"wifi_fingerprint": "home"},
            "motion": {"stationary_likelihood": 0.95, "motion_energy": 0.05},
            "device_relation": {"status": "CHARGING", "plugged": "AC"},
            "environment": {"ambient_light_lux": 0.5},
        }
        token, sig, is_novel = self.manager.process_observation(obs)
        self.assertEqual(token, "S01")
        self.assertFalse(is_novel)

    def test_candidate_promotion_and_capacity_bound(self):
        # Novel observation in new location
        novel_obs = {
            "connectivity": {"wifi_fingerprint": "cafe_wifi"},
            "motion": {"stationary_likelihood": 0.95, "motion_energy": 0.05},
            "device_relation": {"status": "DISCHARGING", "plugged": "UNPLUGGED"},
            "environment": {"ambient_light_lux": 150.0},
        }

        # 1st time: transient
        tok1, sig1, is_nov1 = self.manager.process_observation(novel_obs)
        self.assertEqual(tok1, "S_TRANSIENT")
        self.assertTrue(is_nov1)

        # 2nd time: transient
        tok2, _, _ = self.manager.process_observation(novel_obs)
        self.assertEqual(tok2, "S_TRANSIENT")

        # 3rd time: exceeds promotion threshold (3), promoted to S03
        tok3, _, _ = self.manager.process_observation(novel_obs)
        self.assertTrue(tok3.startswith("S") and tok3 != "S_TRANSIENT")

        stats = self.manager.get_stats()
        self.assertIsInstance(stats, OpenWorldStats)
        self.assertEqual(stats.tier1_locked_count, 2)
        self.assertEqual(stats.tier2_adaptive_count, 1)
        self.assertLessEqual(stats.total_active_vocabulary, stats.max_capacity_limit)


if __name__ == "__main__":
    unittest.main()
