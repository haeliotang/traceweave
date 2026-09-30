"""
Unit tests for Macroscope 2.0 Dashboard Generator.
"""

import unittest

from traceform.ui.macroscope_v2_generator import MacroscopeV2Generator


class TestMacroscopeV2(unittest.TestCase):
    def setUp(self):
        self.mock_dynamics_spec = {
            "protocol": "pixel-sensorium.manifold-dynamics-spec.v1",
            "potential_surfaces": {
                "w1_potential_matrix": [[2.5, 3.0], [3.0, 1.8]],
                "w2_potential_matrix": [[3.2, 1.9], [2.8, 2.2]],
            },
            "w1_attractor_basins": [
                {"basin_id": "B01", "grid_coord": [1, 1], "state_token": "M04", "potential_depth": 1.2, "stationary_probability": 0.25}
            ],
            "w2_attractor_basins": [
                {"basin_id": "B01", "grid_coord": [0, 1], "state_token": "M02", "potential_depth": 1.4, "stationary_probability": 0.30}
            ],
            "attractor_drift": {
                "primary_attractor_drift_distance": 1.0,
                "mean_grid_drift_distance": 0.5,
                "basin_drift_details": [
                    {
                        "w1_basin": {"basin_id": "B01", "grid_coord": [1, 1]},
                        "matched_w2_basin": {"basin_id": "B01", "grid_coord": [0, 1]},
                        "grid_drift_distance": 1.0,
                    }
                ],
            },
            "critical_slowing_down": {
                "total_alerts": 2,
                "alerts": [
                    {
                        "window_index": 5,
                        "timestamp_utc": "2026-09-04T19:26:00Z",
                        "lag1_autocorrelation": 0.764,
                        "spatial_variance": 3.89,
                        "csd_composite_score": 5.76,
                        "warning_level": "CRITICAL",
                    }
                ],
            },
        }

        self.mock_multiscale_spec = {
            "multiscale_metrics": {
                "diffusion_regime": {
                    "hurst_exponent": 0.0541,
                    "r_squared": 0.8153,
                    "regime_type": "SUB_DIFFUSIVE",
                    "msd_by_lag": {1: 0.5, 2: 0.6, 4: 0.7, 8: 0.8},
                }
            }
        }

    def test_html_generation(self):
        generator = MacroscopeV2Generator(self.mock_dynamics_spec, self.mock_multiscale_spec)
        html = generator.generate_html()

        self.assertIsInstance(html, str)
        self.assertIn("Traceform Personal Macroscope 2.0", html)
        self.assertIn("id=\"potCanvas\"", html)
        self.assertIn("id=\"msdCanvas\"", html)
        self.assertIn("id=\"csdCanvas\"", html)
        self.assertIn("Sub-Diffusive Trapping", html)
        self.assertIn("w1_potential_matrix", html)


if __name__ == "__main__":
    unittest.main()
