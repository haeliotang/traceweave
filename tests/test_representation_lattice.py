"""
Unit tests for TopologicalKineticLattice.
"""

import unittest
from traceform.representation.lattice import TopologicalKineticLattice, MicroState


class TestTopologicalKineticLattice(unittest.TestCase):
    def setUp(self):
        self.lattice = TopologicalKineticLattice()
        self.obs1 = {
            "motion": {"stationary_likelihood": 0.95, "motion_energy": 0.01},
            "environment": {"ambient_light_lux": 0.5},
            "device_relation": {"status": "CHARGING", "plugged": "PLUGGED_AC"},
            "connectivity": {"wifi_fingerprint": "8efdbda6f3"},
            "place": {"spatial_cell": "tv58qxy"},
        }
        self.obs2 = {
            "motion": {"stationary_likelihood": 0.10, "motion_energy": 0.8},
            "environment": {"ambient_light_lux": 150.0},
            "device_relation": {"status": "DISCHARGING", "plugged": "UNPLUGGED"},
            "connectivity": {"wifi_fingerprint": "8efdbda6f3"},
            "place": {"spatial_cell": "tv58qxy"},
        }

    def test_signature_computation(self):
        sig1, topo1, kin1, pow1, lit1 = self.lattice.compute_signature(self.obs1)
        self.assertEqual(topo1, "WIFI_8efdbda6f3")
        self.assertEqual(kin1, "K0_STATIONARY")
        self.assertEqual(pow1, "P1_CHARGING")
        self.assertEqual(lit1, "L0_DARK")
        self.assertEqual(sig1, "WIFI_8efdbda6f3|K0_STATIONARY|P1_CHARGING|L0_DARK")

    def test_vocabulary_fit(self):
        vocab = self.lattice.fit_discovery_window([self.obs1, self.obs1, self.obs2])
        self.assertEqual(len(vocab), 2)
        # obs1 appeared twice, so it should be S01
        sig1, _, _, _, _ = self.lattice.compute_signature(self.obs1)
        self.assertEqual(vocab[sig1].state_token, "S01")

        # Quantize
        s = self.lattice.quantize(self.obs1)
        self.assertEqual(s.state_token, "S01")


if __name__ == "__main__":
    unittest.main()
