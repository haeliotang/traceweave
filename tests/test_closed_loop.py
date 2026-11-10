# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import datetime
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from traceform.intervention.closed_loop import ClosedLoopIntervention, MicroActuator


class TestClosedLoop(unittest.TestCase):
    def test_real_delivery_requires_full_post_acknowledgment_window(self):
        with tempfile.TemporaryDirectory() as directory:
            created = datetime.datetime.fromisoformat(self.frame(0)['timestamp_utc']).timestamp()
            actuator = SimpleNamespace(execute=lambda action: {'delivered': True,
                'started_epoch': created + 30, 'finished_epoch': created + 40})
            loop = ClosedLoopIntervention(Path(directory) / 'trials.sqlite', actuator, 'active', seed=2)
            self.addCleanup(loop.close)
            decision = loop.propose(self.frame(0), 'CSD_TIPPING', self.comparison(), 'morning')
            loop.futures[decision['trial_id']].result(timeout=1)
            for seconds in (200, 400, 600, 639):
                self.assertEqual(loop.observe(self.frame(seconds, 'B')), [])
            outcome = loop.observe(self.frame(640, 'B'))[0]
            self.assertEqual(outcome['status'], 'evaluated')
            self.assertEqual(outcome['post_delivery_seconds'], 600)
            self.assertEqual(outcome['feedback_clock'], 'delivery_acknowledgment')

    def test_missing_or_invalid_delivery_clock_cannot_credit_policy(self):
        for finished in (None, float('nan'), float('inf'), True, 0):
            with self.subTest(finished=finished), tempfile.TemporaryDirectory() as directory:
                actuator = SimpleNamespace(execute=lambda action: {'delivered': True, 'finished_epoch': finished})
                loop = ClosedLoopIntervention(Path(directory) / 'trials.sqlite', actuator, 'active', seed=2)
                try:
                    decision = loop.propose(self.frame(0), 'CSD_TIPPING', self.comparison(), 'morning')
                    loop.futures[decision['trial_id']].result(timeout=1)
                    outcome = loop.observe(self.frame(200))[0]
                    self.assertEqual(outcome['status'], 'censored')
                    self.assertEqual(outcome['reason'], 'missing_or_invalid_delivery_clock')
                    self.assertEqual(loop.db.execute('SELECT count(*) FROM policy').fetchone()[0], 0)
                finally:
                    loop.close()

    def test_rejected_proposal_reports_cooldown_without_consuming_randomness(self):
        with tempfile.TemporaryDirectory() as directory:
            loop = ClosedLoopIntervention(Path(directory) / 'trials.sqlite', MicroActuator(False), 'shadow', seed=2)
            self.addCleanup(loop.close)
            decision = loop.propose(self.frame(0), 'CSD_TIPPING', self.comparison(), 'morning')
            before = loop.rng.getstate()
            self.assertIsNone(loop.propose(self.frame(1), 'CSD_TIPPING', self.comparison(), 'morning'))
            self.assertEqual(loop.last_proposal_block['reason'], 'global_cooldown')
            self.assertEqual(loop.last_proposal_block['required_seconds'], 900)
            self.assertEqual(loop.rng.getstate(), before)
            self.assertEqual(loop.db.execute('SELECT count(*) FROM trials').fetchone()[0], 1)
            self.assertTrue(decision)

    def test_compaction_preserves_pending_trials_boundary_and_policy_aggregates(self):
        with tempfile.TemporaryDirectory() as directory:
            loop = ClosedLoopIntervention(Path(directory) / "trials.sqlite", MicroActuator(False))
            now = 40 * 86400
            cutoff = now - 30 * 86400
            for identifier, created, status in [("old", cutoff - 1, "evaluated"), ("edge", cutoff, "shadow"), ("pending", cutoff - 1, "observing"), ("queued", cutoff - 1, "queued")]:
                loop.db.execute("INSERT INTO trials VALUES (?,?,?,?,?,?,?,?,?)", (identifier, "context", "pulse", 0.5, created, "{}", status, "{}", "{}"))
            loop.db.execute("INSERT INTO policy VALUES ('context','pulse',3,2)")
            loop.db.commit()
            self.assertEqual(loop.compact(now)["trials_deleted"], 1)
            self.assertEqual({row[0] for row in loop.db.execute("SELECT id FROM trials")}, {"edge", "pending", "queued"})
            self.assertEqual(loop.db.execute("SELECT n,reward FROM policy").fetchone(), (3, 2))
            self.assertEqual(loop.compact(now)["trials_deleted"], 0)
            loop.close()

    def test_configured_volume_action_keeps_randomized_control_and_logged_propensities(self):
        with tempfile.TemporaryDirectory() as directory:
            loop = ClosedLoopIntervention(Path(directory) / "trials.sqlite", MicroActuator(False), "shadow", seed=2,
                                          actions=("pulse", "notification", "volume_duck"))
            decision = loop.propose(self.frame(0), "CSD_TIPPING", self.comparison(), "morning")
            self.assertEqual(decision["action"], "volume_duck")
            self.assertAlmostEqual(decision["propensity"], 0.05)
            self.assertEqual(decision["action_probabilities"]["none"], 0.25)
            self.assertAlmostEqual(sum(decision["action_probabilities"].values()), 1)
            self.assertEqual(loop.futures, {})
            loop.close()

    def test_invalid_action_sets_fail_before_opening_trial_database(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "trials.sqlite"
            for actions in ((), ("pulse", "pulse"), ("unknown",)):
                with self.assertRaises(ValueError):
                    ClosedLoopIntervention(target, MicroActuator(False), actions=actions)
                self.assertFalse(target.exists())

    def frame(self, seconds, token="A", mode="live_follow"):
        instant = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc) + datetime.timedelta(seconds=seconds)
        return {"timestamp_utc": instant.isoformat(), "state_token": token, "model_version": "v1",
                "source_mode": mode, "continuous_from_previous": True, "boot_id": "boot", "continuity_epoch": 1}

    def comparison(self):
        return SimpleNamespace(recommended_path="RECOMMEND_INTERVENTION", to_dict=lambda: {})

    def test_silence_and_failed_api_are_not_delivery(self):
        with tempfile.TemporaryDirectory() as directory:
            silent = Path(directory) / ".silent"
            silent.touch()
            with patch("subprocess.run") as run:
                self.assertFalse(MicroActuator(silent_file=silent).execute("pulse")["delivered"])
                run.assert_not_called()
            with patch("shutil.which", return_value="api"), patch("subprocess.run", return_value=SimpleNamespace(returncode=1)):
                self.assertFalse(MicroActuator().execute("pulse")["delivered"])

    def test_randomized_control_delayed_outcome_and_cooldown(self):
        with tempfile.TemporaryDirectory() as directory:
            loop = ClosedLoopIntervention(Path(directory) / "trials.sqlite", MicroActuator(False), "active", seed=1)
            decision = loop.propose(self.frame(0), "CSD_TIPPING", self.comparison(), "morning")
            self.assertEqual(decision["action"], "none")
            self.assertEqual(decision["propensity"], 0.25)
            self.assertIsNone(loop.propose(self.frame(1), "CSD_TIPPING", self.comparison(), "morning"))
            self.assertEqual(loop.observe(self.frame(200)), [])
            self.assertEqual(loop.observe(self.frame(400, "B")), [])
            outcomes = loop.observe(self.frame(600, "B"))
            self.assertEqual(outcomes[0]["reward"], 1)
            self.assertEqual(loop.db.execute("SELECT n FROM policy").fetchone()[0], 1)
            loop.close()

    def test_volume_duck_restores_original_after_temporary_reduction(self):
        import json
        def reading(value):
            return SimpleNamespace(returncode=0, stdout=json.dumps([{"stream": "music", "volume": value, "max_volume": 15}]))
        acknowledged = SimpleNamespace(returncode=0)
        with patch("shutil.which", return_value="api"), patch("time.sleep"), \
                patch("subprocess.run", side_effect=[reading(5), acknowledged, reading(4), reading(4), acknowledged, reading(5)]) as run:
            report = MicroActuator().execute("volume_duck")
        self.assertTrue(report["delivered"])
        self.assertTrue(report["restored"])
        self.assertEqual(run.call_args_list[1].args[0], ["termux-volume", "music", "4"])
        self.assertEqual(run.call_args_list[4].args[0], ["termux-volume", "music", "5"])

    def test_volume_duck_preserves_intervening_external_change(self):
        import json
        responses = [SimpleNamespace(returncode=0, stdout=json.dumps([{"stream": "music", "volume": 5, "max_volume": 15}])),
                     SimpleNamespace(returncode=0),
                     SimpleNamespace(returncode=0, stdout=json.dumps([{"stream": "music", "volume": 4, "max_volume": 15}])),
                     SimpleNamespace(returncode=0, stdout=json.dumps([{"stream": "music", "volume": 3, "max_volume": 15}]))]
        with patch("shutil.which", return_value="api"), patch("time.sleep"), patch("subprocess.run", side_effect=responses) as run:
            report = MicroActuator().execute("volume_duck")
        self.assertFalse(report["restored"])
        self.assertEqual(run.call_count, 4)

    def test_acknowledged_volume_change_without_readback_is_not_delivery(self):
        import json
        reading = SimpleNamespace(returncode=0, stdout=json.dumps([{"stream": "music", "volume": 5, "max_volume": 15}]))
        with patch("shutil.which", return_value="api"), patch("time.sleep") as sleep, \
                patch("subprocess.run", side_effect=[reading, SimpleNamespace(returncode=0), reading, reading]):
            report = MicroActuator().execute("volume_duck")
        self.assertFalse(report["delivered"])
        sleep.assert_not_called()

    def test_volume_set_timeout_still_attempts_conditional_restoration(self):
        import json
        import subprocess
        def reading(value):
            return SimpleNamespace(returncode=0, stdout=json.dumps([{"stream": "music", "volume": value, "max_volume": 15}]))
        responses = [reading(5), subprocess.TimeoutExpired(["termux-volume"], 3), reading(4), SimpleNamespace(returncode=0), reading(5)]
        with patch("shutil.which", return_value="api"), patch("subprocess.run", side_effect=responses) as run:
            report = MicroActuator().execute("volume_duck")
        self.assertFalse(report["delivered"])
        self.assertEqual(run.call_args_list[3].args[0], ["termux-volume", "music", "5"])

    def test_gap_censors_and_replay_never_dispatches(self):
        with tempfile.TemporaryDirectory() as directory:
            loop = ClosedLoopIntervention(Path(directory) / "trials.sqlite", MicroActuator(False), "active", seed=1)
            loop.propose(self.frame(0), "CSD_TIPPING", self.comparison(), "morning")
            loop.observe(self.frame(1))
            self.assertEqual(loop.observe(self.frame(700))[0]["status"], "censored")
            self.assertEqual(loop.db.execute("SELECT COUNT(*) FROM policy").fetchone()[0], 0)
            replay = loop.propose(self.frame(1000, mode="historical_replay"), "CSD_TIPPING", self.comparison(), "morning")
            self.assertEqual(replay["status"], "shadow")
            self.assertEqual(loop.futures, {})
            loop.close()

    def test_failed_async_delivery_is_not_rewarded(self):
        with tempfile.TemporaryDirectory() as directory:
            actuator = SimpleNamespace(execute=lambda action: {"delivered": False, "reason": "test_failure"})
            loop = ClosedLoopIntervention(Path(directory) / "trials.sqlite", actuator, "active", seed=2)
            decision = loop.propose(self.frame(0), "CSD_TIPPING", self.comparison(), "morning")
            self.assertEqual(decision["status"], "queued")
            loop.futures[decision["trial_id"]].result(timeout=1)
            loop.drain_receipts()
            self.assertEqual(loop.db.execute("SELECT status FROM trials").fetchone()[0], "delivery_failed")
            loop.observe(self.frame(600, "B"))
            self.assertEqual(loop.db.execute("SELECT COUNT(*) FROM policy").fetchone()[0], 0)
            loop.close()

    def test_delivered_but_ineligible_receipt_is_censored_without_reward(self):
        with tempfile.TemporaryDirectory() as directory:
            actuator = SimpleNamespace(execute=lambda action: {"delivered": True, "eligible_for_outcome": False, "restoration_status": "failed"})
            loop = ClosedLoopIntervention(Path(directory) / "trials.sqlite", actuator, "active", seed=2)
            self.addCleanup(loop.close)
            decision = loop.propose(self.frame(0), "CSD_TIPPING", self.comparison(), "morning")
            loop.futures[decision["trial_id"]].result(timeout=1)
            loop.drain_receipts()
            self.assertEqual(loop.db.execute("SELECT status FROM trials").fetchone()[0], "censored_delivery")
            loop.observe(self.frame(200))
            loop.observe(self.frame(400))
            self.assertEqual(loop.observe(self.frame(600, "B")), [])
            self.assertEqual(loop.db.execute("SELECT COUNT(*) FROM policy").fetchone()[0], 0)
            now = datetime.datetime.fromisoformat(self.frame(31 * 86400)["timestamp_utc"]).timestamp()
            self.assertEqual(loop.compact(now)["trials_deleted"], 1)

    def test_source_binding_changes_censor_outcomes_without_policy_credit(self):
        for field in ("session_id", "model_sha256", "normalizer_sha256"):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                loop = ClosedLoopIntervention(Path(directory) / "trials.sqlite", MicroActuator(False), "active", seed=1)
                self.addCleanup(loop.close)
                original = dict(self.frame(0), **{field: "original"})
                loop.propose(original, "CSD_TIPPING", self.comparison(), "morning")
                loop.observe(dict(self.frame(200), **{field: "original"}))
                loop.observe(dict(self.frame(400), **{field: "original"}))
                outcomes = loop.observe(dict(self.frame(600, "B"), **{field: "changed"}))
                self.assertEqual(outcomes[0]["status"], "censored")
                self.assertEqual(loop.db.execute("SELECT COUNT(*) FROM policy").fetchone()[0], 0)

    def test_restart_censors_pending_trial(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trials.sqlite"
            loop = ClosedLoopIntervention(path, MicroActuator(False), "active", seed=1)
            loop.propose(self.frame(0), "CSD_TIPPING", self.comparison(), "morning")
            loop.close()
            loop = ClosedLoopIntervention(path, MicroActuator(False), "active")
            self.assertEqual(loop.db.execute("SELECT status FROM trials").fetchone()[0], "censored_restart")
            self.assertEqual(loop.observe(self.frame(600, "B")), [])
            loop.close()

    def test_repeated_delivered_no_escape_extends_quiet_time_and_success_resets_it(self):
        import json
        with tempfile.TemporaryDirectory() as directory:
            loop = ClosedLoopIntervention(Path(directory) / "trials.sqlite", MicroActuator(False), "active", seed=1)
            context = json.dumps(["v1", "A", "CSD_TIPPING", "morning"])
            for index in range(3):
                frame = self.frame(index * 900)
                created = datetime.datetime.fromisoformat(frame["timestamp_utc"]).timestamp()
                loop.db.execute("INSERT INTO trials VALUES (?,?,?,?,?,?,?,?,?)", (str(index), context, "pulse", 0.5, created, json.dumps(frame), "evaluated", json.dumps({"delivered": True}), json.dumps({"reward": 0})))
            loop.db.commit()
            self.assertIsNone(loop.propose(self.frame(3000), "CSD_TIPPING", self.comparison(), "morning"))
            loop.db.execute("UPDATE trials SET outcome=? WHERE id='2'", (json.dumps({"reward": 1}),))
            loop.db.commit()
            decision = loop.propose(self.frame(3000), "CSD_TIPPING", self.comparison(), "morning")
            self.assertEqual(decision["context_cooldown_seconds"], 900)
            self.assertEqual(decision["consecutive_no_escape_outcomes"], 0)
            loop.close()


class TestTriggerEvents(unittest.TestCase):
    def test_tipping_and_prolonged_dwell_may_trigger_when_the_dwell_is_long_enough(self):
        from traceform.intervention.closed_loop import should_consider_intervention as consider
        self.assertTrue(consider("CSD_TIPPING", 60, 60, "active"))
        self.assertTrue(consider("PROLONGED_DWELL", 60, 60, "active"))
        self.assertTrue(consider("PROLONGED_DWELL", 61, 60, "shadow"))

    def test_short_dwell_other_events_and_off_mode_never_trigger(self):
        from traceform.intervention.closed_loop import should_consider_intervention as consider
        self.assertFalse(consider("CSD_TIPPING", 59, 60, "active"))
        self.assertFalse(consider("PROLONGED_DWELL", 59, 60, "active"))
        self.assertFalse(consider("HIGH_VELOCITY_BURST", 90, 60, "active"))
        self.assertFalse(consider("PROLONGED_DWELL", 90, 60, "off"))
        self.assertFalse(consider("CSD_TIPPING", 90, 60, "off"))

    def test_the_daemon_uses_this_decision_and_no_longer_hard_codes_tipping(self):
        source = (Path(__file__).resolve().parents[1] / "scripts/phone_edge_manifold_daemon.py").read_text()
        self.assertIn("should_consider_intervention(ev.event_type", source)
        self.assertNotIn("ev.event_type == \"CSD_TIPPING\" and payload", source)
