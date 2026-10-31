import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("ci_backfill", ROOT / "agent_os/scripts/ci_backfill_github.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CiBackfillTests(unittest.TestCase):
    def test_selects_failed_runs_since_date(self):
        runs = [
            {"databaseId": 1, "conclusion": "failure", "createdAt": "2026-10-29T00:00:00Z"},
            {"databaseId": 2, "conclusion": "failure", "createdAt": "2026-10-31T00:00:00Z"},
            {"databaseId": 3, "conclusion": "success", "createdAt": "2026-10-31T01:00:00Z"},
            {"databaseId": 4, "conclusion": "failure", "createdAt": "2026-10-30T12:00:00Z"},
        ]
        chosen = MODULE.select_runs(runs, "2026-10-30", 10)
        self.assertEqual([run["databaseId"] for run in chosen], [2, 4])

    def test_limit_and_order(self):
        runs = [{"databaseId": index, "conclusion": "failure",
                 "createdAt": f"2026-10-30T0{index}:00:00Z"} for index in range(1, 6)]
        chosen = MODULE.select_runs(runs, "2026-10-30", 3)
        self.assertEqual([run["databaseId"] for run in chosen], [5, 4, 3])


if __name__ == "__main__":
    unittest.main()
