"""Logged contextual policy, actuator receipts and delayed observational outcomes."""

from concurrent.futures import ThreadPoolExecutor
import json
import math
import os
from pathlib import Path
import random
import shutil
import sqlite3
import subprocess
import time
import uuid

from traceform.forecasting.continuity import same_stream_segment
from traceform.forecasting.evaluation import timestamp


def compact_trial_rows(database, now_epoch):
    with database:
        deleted = database.execute("DELETE FROM trials WHERE created < ? AND status IN ('evaluated','censored','censored_restart','censored_delivery','delivery_failed','shadow')", (now_epoch - 30 * 86400,)).rowcount
    return {"trials_deleted": deleted, "retention_days": 30,
            "scope": "terminal_and_shadow_trial_retention_policy_aggregates_preserved"}


class MicroActuator:
    def __init__(self, enabled=True, silent_file=None):
        self.enabled = enabled
        self.silent_file = Path(silent_file) if silent_file else None

    def execute(self, action):
        started = time.time()
        availability = self.availability_snapshot()
        if availability['blocked']:
            return {"delivered": False, "reason": "disabled_or_silent", "started_epoch": started,
                    "availability": availability}
        commands = {
            "pulse": ["termux-vibrate", "-d", "100"],
            "notification": ["termux-notification", "--id", "traceform-micro", "-t", "Traceform", "-c", "A moment to check your current activity."],
            "volume_duck": ["termux-volume"],
        }
        if action not in commands:
            raise ValueError("unknown actuator action")
        command = commands[action]
        if not shutil.which(command[0]):
            return {"delivered": False, "reason": "api_unavailable", "started_epoch": started}
        try:
            if action == "volume_duck":
                return self.duck_volume(started)
            result = subprocess.run(command, capture_output=True, timeout=3)
            return {"delivered": result.returncode == 0, "returncode": result.returncode,
                    "reason": "api_acknowledged" if result.returncode == 0 else "api_failed",
                    "started_epoch": started, "finished_epoch": time.time(),
                    "evidence_scope": "command_acknowledgment_not_human_response"}
        except (OSError, subprocess.TimeoutExpired, ValueError, KeyError, TypeError) as error:
            receipt = {"delivered": False, "reason": type(error).__name__, "started_epoch": started}
            if action == "volume_duck":
                receipt["restoration_status"] = "unknown_after_execution_error"
            return receipt

    def availability_snapshot(self):
        silent = bool(self.silent_file and self.silent_file.exists())
        environment_disabled = os.environ.get("TRACEFORM_DISABLE_HAPTICS", "").lower() in ("1", "true", "yes")
        return {'enabled': self.enabled, 'silent_marker_present': silent, 'environment_disabled': environment_disabled,
                'blocked': not self.enabled or silent or environment_disabled,
                'scope': 'current_flags_not_delivery_acknowledgment'}

    def music_volume(self):
        result = subprocess.run(["termux-volume"], capture_output=True, timeout=3)
        if result.returncode != 0:
            raise ValueError("volume query failed")
        streams = json.loads(result.stdout)
        if not isinstance(streams, list):
            raise ValueError("volume stream array required")
        for stream in streams:
            if not isinstance(stream, dict):
                continue
            if stream.get("stream") == "music":
                value, maximum = stream["volume"], stream["max_volume"]
                if type(value) is int and type(maximum) is int and 0 <= value <= maximum:
                    return value
        raise ValueError("valid music stream required")

    def duck_volume(self, started):
        original = self.music_volume()
        if original == 0:
            return {"delivered": False, "reason": "already_muted", "started_epoch": started}
        target = original - 1
        restored = False
        delivered = False
        try:
            result = subprocess.run(["termux-volume", "music", str(target)], capture_output=True, timeout=3)
            delivered = result.returncode == 0 and self.music_volume() == target
            if delivered:
                time.sleep(2)
        finally:
            # Preserve an intervening user/app volume change instead of overwriting it.
            if self.music_volume() == target:
                restoration = subprocess.run(["termux-volume", "music", str(original)], capture_output=True, timeout=3)
                restored = restoration.returncode == 0 and self.music_volume() == original
        return {"delivered": delivered, "returncode": result.returncode,
                "reason": "target_volume_verified" if delivered else "target_volume_not_verified",
                "original_music_volume": original, "target_music_volume": target, "restored": restored,
                "started_epoch": started, "finished_epoch": time.time(),
                "evidence_scope": "temporary_music_volume_api_execution_not_human_response"}


# Events at which the live daemon may consider an intervention. A tipping alert almost never coincides with a
# long dwell (offline replay: 1 of 607 matched alerts), so a prolonged dwell is an event of its own: it fires
# once, when the dwell count reaches the threshold.
TRIGGER_EVENT_TYPES = ("CSD_TIPPING", "PROLONGED_DWELL")


def should_consider_intervention(event_type, dwell_steps, dwell_threshold, mode):
    """Whether this event, on a newly stored frame, goes on to the counterfactual check and the policy."""
    return mode != "off" and event_type in TRIGGER_EVENT_TYPES and dwell_steps >= dwell_threshold


class ClosedLoopIntervention:
    def __init__(self, path, actuator, mode="shadow", seed=None, actions=("pulse", "notification")):
        if mode not in ("off", "shadow", "active"):
            raise ValueError("invalid intervention mode")
        if not actions or len(set(actions)) != len(actions) or any(action not in ("pulse", "notification", "volume_duck") for action in actions):
            raise ValueError("distinct supported intervention actions required")
        self.actions = tuple(actions)
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path)
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS trials (id TEXT PRIMARY KEY, context TEXT, action TEXT,
                propensity REAL, created REAL, frame TEXT, status TEXT, receipt TEXT, outcome TEXT);
            CREATE TABLE IF NOT EXISTS policy (context TEXT, action TEXT, n INTEGER, reward REAL,
                PRIMARY KEY(context,action));
        """)
        self.actuator = actuator
        self.mode = mode
        self.rng = random.Random(seed)
        self.executor = ThreadPoolExecutor(max_workers=1)
        self.futures = {}
        self.previous = None
        self.last_compaction_epoch = None
        self.last_proposal_block = None
        # An interrupted actuator has unknown delivery/outcome; do not credit it.
        with self.db:
            self.db.execute("UPDATE trials SET status='censored_restart' WHERE status IN ('queued','observing')")

    def close(self):
        self.executor.shutdown(wait=True)
        self.drain_receipts()
        self.db.close()

    def compact(self, now_epoch):
        report = compact_trial_rows(self.db, now_epoch)
        self.last_compaction_epoch = now_epoch
        return report

    def propose(self, frame, event_type, comparison, local_phase):
        self.last_proposal_block = None
        if self.mode == "off":
            self.last_proposal_block = {'reason': 'mode_off'}
            return None
        if comparison.recommended_path != "RECOMMEND_INTERVENTION":
            self.last_proposal_block = {'reason': 'counterfactual_not_recommended'}
            return None
        instant = timestamp(frame).timestamp()
        last = self.db.execute("SELECT MAX(created) FROM trials").fetchone()[0]
        if last is not None and instant - last < 900:
            self.last_proposal_block = {'reason': 'global_cooldown', 'elapsed_seconds': instant - last, 'required_seconds': 900}
            return None
        context = json.dumps([frame["model_version"], frame["state_token"], event_type, local_phase])
        evaluated = self.db.execute("SELECT created,outcome FROM trials WHERE context=? AND action!='none' AND status='evaluated' ORDER BY created DESC LIMIT 3", (context,)).fetchall()
        failed_outcomes = 0
        for _, outcome in evaluated:
            if json.loads(outcome)["reward"] != 0:
                break
            failed_outcomes += 1
        context_cooldown = 900 * (2 ** min(2, failed_outcomes))
        if evaluated and instant - evaluated[0][0] < context_cooldown:
            self.last_proposal_block = {'reason': 'context_cooldown', 'elapsed_seconds': instant - evaluated[0][0],
                                       'required_seconds': context_cooldown, 'consecutive_no_escape_outcomes': failed_outcomes}
            return None
        rewards = {action: (reward + 1) / (count + 2) for action, count, reward in
                   self.db.execute("SELECT action,n,reward FROM policy WHERE context=?", (context,))}
        best = max(self.actions, key=lambda action: rewards.get(action, 0.5))
        probabilities = {"none": 0.25, **{action: 0.15 / len(self.actions) for action in self.actions}}
        probabilities[best] += 0.6
        draw, cumulative = self.rng.random(), 0.0
        action = "none"
        for candidate, probability in probabilities.items():
            cumulative += probability
            if draw < cumulative:
                action = candidate
                break
        identifier = uuid.uuid4().hex
        active = self.mode == "active" and frame.get("source_mode") == "live_follow"
        status = "observing" if active and action == "none" else "queued" if active else "shadow"
        receipt = {"delivered": True, "reason": "randomized_no_action"} if active and action == "none" else {}
        with self.db:
            self.db.execute("INSERT INTO trials VALUES (?,?,?,?,?,?,?,?,NULL)",
                            (identifier, context, action, probabilities[action], instant, json.dumps(frame), status, json.dumps(receipt)))
        if status == "queued":
            self.futures[identifier] = self.executor.submit(self.actuator.execute, action)
        return {"trial_id": identifier, "action": action, "propensity": probabilities[action], "status": status,
                "context_cooldown_seconds": context_cooldown, "consecutive_no_escape_outcomes": failed_outcomes,
                "action_probabilities": probabilities,
                "comparison": comparison.to_dict(), "evidence_scope": "model_scenario_and_observational_policy"}

    def drain_receipts(self):
        for identifier, future in list(self.futures.items()):
            if not future.done():
                continue
            try:
                receipt = future.result()
            except Exception as error:
                receipt = {"delivered": False, "reason": type(error).__name__}
            status = "observing" if receipt["delivered"] else "delivery_failed"
            if receipt["delivered"] and receipt.get("eligible_for_outcome", True) is not True:
                status = "censored_delivery"
            with self.db:
                self.db.execute("UPDATE trials SET status=?, receipt=? WHERE id=?",
                                (status, json.dumps(receipt), identifier))
            del self.futures[identifier]

    def observe(self, frame):
        self.drain_receipts()
        now_epoch = time.time()
        if self.last_compaction_epoch is None or now_epoch - self.last_compaction_epoch >= 3600:
            self.compact(now_epoch)
        instant = timestamp(frame).timestamp()
        outputs = []
        for identifier, context, action, created, original_text, receipt_text in self.db.execute("SELECT id,context,action,created,frame,receipt FROM trials WHERE status='observing'").fetchall():
            original = json.loads(original_text)
            receipt = json.loads(receipt_text)
            feedback_started = created if action == "none" else receipt.get("finished_epoch")
            valid_clock = type(feedback_started) in (int, float) and math.isfinite(feedback_started) and feedback_started >= created
            discontinuity = not same_stream_segment(original, frame) or any(
                original.get(field) != frame.get(field)
                for field in ("session_id", "model_sha256", "normalizer_sha256"))
            if self.previous:
                gap = instant - timestamp(self.previous).timestamp()
                discontinuity = discontinuity or not same_stream_segment(self.previous, frame) or not 0 < gap <= 300 or any(
                    self.previous.get(field) != frame.get(field)
                    for field in ("session_id", "model_sha256", "normalizer_sha256"))
            if not valid_clock:
                outcome = {"status": "censored", "reason": "missing_or_invalid_delivery_clock", "timestamp_utc": frame["timestamp_utc"]}
            elif discontinuity or instant - feedback_started > 900:
                outcome = {"status": "censored", "reason": "continuity_or_timeout", "timestamp_utc": frame["timestamp_utc"]}
            elif instant - feedback_started >= 600:
                reward = int(frame["state_token"] != original["state_token"])
                outcome = {"status": "evaluated", "reward": reward, "timestamp_utc": frame["timestamp_utc"],
                           "evidence_scope": "state_change_association_not_individual_causal_effect",
                           "feedback_clock": "randomized_control_decision" if action == "none" else "delivery_acknowledgment",
                           "feedback_started_epoch": feedback_started, "post_delivery_seconds": instant - feedback_started}
            else:
                continue
            with self.db:
                if outcome["status"] == "evaluated":
                    self.db.execute("""INSERT INTO policy VALUES (?,?,1,?) ON CONFLICT(context,action)
                        DO UPDATE SET n=n+1,reward=reward+excluded.reward""", (context, action, outcome["reward"]))
                self.db.execute("UPDATE trials SET status=?,outcome=? WHERE id=?", (outcome["status"], json.dumps(outcome), identifier))
            outputs.append(dict(outcome, trial_id=identifier, action=action))
        self.previous = frame
        return outputs
