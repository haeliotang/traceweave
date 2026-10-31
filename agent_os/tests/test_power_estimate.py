import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("measure_power", ROOT / "agent_os/scripts/measure_power_android.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PowerEstimateTests(unittest.TestCase):
    def test_parse_charging(self):
        parsed = MODULE.parse_battery("Current Battery Service state:\n  AC powered: true\n  level: 80\n  temperature: 305\n")
        self.assertTrue(parsed["powered"])
        self.assertEqual(parsed["level"], 80)
        self.assertAlmostEqual(parsed["temperature_c"], 30.5, places=1)

    def test_estimate_linear_drain(self):
        result = MODULE.estimate([(0.0, 100), (3600.0, 95)], 4000)
        self.assertEqual(result["lost_percent"], 5)
        self.assertAlmostEqual(result["percent_per_hour"], 5.0, places=2)
        self.assertAlmostEqual(result["mah_per_hour"], 200.0, places=1)

    def test_estimate_rejects_short_series(self):
        with self.assertRaises(ValueError):
            MODULE.estimate([(0.0, 100)], 4000)


if __name__ == "__main__":
    unittest.main()
