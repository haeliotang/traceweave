import importlib.util
from pathlib import Path
import unittest
import tempfile
import json


spec = importlib.util.spec_from_file_location("background_measurement", Path(__file__).resolve().parents[1] / "scripts/measure_background_android.py")
measurement = importlib.util.module_from_spec(spec)
spec.loader.exec_module(measurement)


class MeasurementTests(unittest.TestCase):
    def samples(self):
        return [{"phase": "baseline_start", "elapsedMs": 1000, "chargeCounter_uAh": 1000000,
                 "plugged": 0, "interactive": False, "pid": 10, "processCpuMs": 20},
                {"phase": "baseline_tick", "elapsedMs": 301000, "chargeCounter_uAh": 999500,
                 "plugged": 0, "interactive": False, "pid": 10, "processCpuMs": 25},
                {"phase": "baseline_end", "elapsedMs": 601000, "chargeCounter_uAh": 999000,
                 "plugged": 0, "interactive": False, "pid": 10, "processCpuMs": 30}]

    def test_physical_interval_and_cpu_are_measured_separately(self):
        result = measurement.compare(self.samples(), "baseline")
        self.assertEqual(result["elapsed_seconds"], 600)
        self.assertEqual(result["whole_device_charge_drop_uAh"], 1000)
        self.assertEqual(result["process_cpu_delta_ms"], 10)

    def test_plugging_or_waking_mid_interval_invalidates_conditions(self):
        samples = self.samples(); samples[1]["plugged"] = 2; samples[1]["interactive"] = True
        result = measurement.compare(samples, "baseline")
        self.assertFalse(result["all_samples_unplugged"])
        self.assertFalse(result["all_samples_screen_off"])

    def test_missing_gauge_and_process_restart_are_not_zero_energy_or_negative_cpu(self):
        samples = self.samples(); samples[0]["chargeCounter_uAh"] = None; samples[-1]["pid"] = 11; samples[-1]["processCpuMs"] = 1
        result = measurement.compare(samples, "baseline")
        self.assertIsNone(result["whole_device_charge_drop_uAh"])
        self.assertIsNone(result["process_cpu_delta_ms"])

    def test_incomplete_interval_is_not_fabricated(self):
        with self.assertRaises(StopIteration):
            measurement.compare(self.samples()[:-1], "baseline")

    def test_delayed_sparse_sampling_is_not_a_controlled_comparison(self):
        baseline = {"elapsed_seconds": 3684.855, "sample_count": 4, "all_samples_unplugged": True, "all_samples_screen_off": True}
        work = {"elapsed_seconds": 2066.558, "sample_count": 2, "all_samples_unplugged": True, "all_samples_screen_off": True}
        self.assertFalse(measurement.controlled_comparison_valid(baseline, work, 1))

    def test_regular_controlled_sampling_requires_the_planned_task_dose(self):
        phase = {"elapsed_seconds": 600.1, "sample_count": 11, "max_sample_gap_seconds": 60,
                 "all_samples_unplugged": True, "all_samples_screen_off": True}
        self.assertTrue(measurement.controlled_comparison_valid(phase, phase, 10))
        self.assertFalse(measurement.controlled_comparison_valid(phase, phase, 1))

    def test_clustered_samples_do_not_satisfy_equal_frequency(self):
        phase = {"elapsed_seconds": 600.1, "sample_count": 11, "max_sample_gap_seconds": 500,
                 "all_samples_unplugged": True, "all_samples_screen_off": True}
        self.assertFalse(measurement.controlled_comparison_valid(phase, phase, 10))

    def test_screen_wake_does_not_imply_scheduler_deferral(self):
        phase = {"elapsed_seconds": 600.1, "sample_count": 11, "max_sample_gap_seconds": 60,
                 "all_samples_unplugged": True, "all_samples_screen_off": False}
        self.assertTrue(measurement.timing_and_dose_valid(phase, phase, 10))
        self.assertFalse(measurement.controlled_comparison_valid(phase, phase, 10))

    def test_incomplete_reread_cannot_replace_completed_raw_record(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            complete = {"status": "complete", "samples": [{"phase": "work_end"}]}
            measurement.save_probe_record(output, complete)
            measurement.save_probe_record(output, {"status": "await_unplug", "samples": []})
            self.assertEqual(json.loads((output / "physical_background_raw.json").read_text()), complete)
            self.assertEqual(json.loads((output / "incomplete_background_raw.json").read_text())["status"], "await_unplug")

    def test_screen_wake_between_samples_invalidates_controlled_comparison(self):
        phase = {"elapsed_seconds": 600.1, "sample_count": 11, "max_sample_gap_seconds": 60,
                 "all_samples_unplugged": True, "all_samples_screen_off": True}
        self.assertFalse(measurement.controlled_comparison_valid(phase, phase, 10, 1))


if __name__ == "__main__":
    unittest.main()
