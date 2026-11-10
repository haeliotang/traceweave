# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Chronological, paired forecasting evaluation without test-set fitting."""

from collections import Counter, defaultdict
import datetime
import math
import random

from traceform.forecasting.periodic_kernel import PeriodicTransitionKernel
from traceform.forecasting.continuity import same_stream_segment


def timestamp(record):
    value = datetime.datetime.fromisoformat(record["timestamp_utc"].replace("Z", "+00:00"))
    if value.tzinfo is None:
        raise ValueError("timezone-aware timestamps required")
    return value


def adjacent_examples(records, max_gap_seconds=1800.0):
    dwell = 1
    for first, second in zip(records, records[1:]):
        gap = (timestamp(second) - timestamp(first)).total_seconds()
        if not 0 < gap <= max_gap_seconds or not same_stream_segment(first, second):
            dwell = 1
            continue
        yield first, second, dwell
        dwell = dwell + 1 if first["state_token"] == second["state_token"] else 1


def evaluate_forecasts(records, split_time, timezone_name="UTC", max_gap_seconds=1800.0):
    if split_time.tzinfo is None:
        raise ValueError("timezone-aware split required")
    times = [timestamp(record) for record in records]
    if any(second < first for first, second in zip(times, times[1:])):
        raise ValueError("records must be in chronological order")
    train = [record for record, instant in zip(records, times) if instant < split_time]
    test = [record for record, instant in zip(records, times) if instant >= split_time]
    if len(train) < 2 or len(test) < 2:
        raise ValueError("both partitions need at least two records")
    vocab = sorted({record["state_token"] for record in train})
    counts = defaultdict(Counter)
    dwell_counts = defaultdict(Counter)
    for first, second, dwell in adjacent_examples(train, max_gap_seconds):
        source, target = first["state_token"], second["state_token"]
        counts[source][target] += 1
        dwell_counts[(source, min(dwell, 60))][target] += 1

    def smooth(row, prior=None):
        weights = {state: row.get(state, 0) + (5 * prior[state] if prior else 0.5) for state in vocab}
        total = sum(weights.values())
        return {state: value / total for state, value in weights.items()}

    global_trans = {state: smooth(counts[state]) for state in vocab}
    kernel = PeriodicTransitionKernel(timezone_name).fit(train, global_trans, max_gap_seconds)
    scores = {name: [] for name in ["persist", "first_order", "dwell", "periodic"]}
    unknown = 0
    for first, second, dwell in adjacent_examples(test, max_gap_seconds):
        source, target = first["state_token"], second["state_token"]
        if source not in vocab or target not in vocab:
            unknown += 1
            continue
        prior = global_trans[source]
        distributions = {
            "persist": {state: (1.0 if state == source else 0.0) for state in vocab},
            "first_order": prior,
            "dwell": smooth(dwell_counts[(source, min(dwell, 60))], prior),
            "periodic": kernel.row(source, timestamp(first)),
        }
        day = timestamp(first).astimezone(kernel.timezone).date().isoformat()
        for name, row in distributions.items():
            predicted = max(vocab, key=lambda state: row[state])
            scores[name].append({
                "day": day,
                "correct": int(predicted == target),
                "confidence": row[predicted],
                "log_loss": -math.log(max(row[target], 1e-12)),
                "brier": sum((row[state] - int(state == target)) ** 2 for state in vocab),
            })
    if not scores["persist"]:
        raise ValueError("no evaluable holdout transitions")
    metrics = {}
    for name, rows in scores.items():
        bins = defaultdict(list)
        for row in rows:
            bins[min(9, int(row["confidence"] * 10))].append(row)
        ece = sum(abs(sum(row["confidence"] - row["correct"] for row in group)) for group in bins.values()) / len(rows)
        metrics[name] = {field: sum(row[field] for row in rows) / len(rows) for field in ["correct", "log_loss", "brier"]}
        metrics[name]["top1_accuracy"] = metrics[name].pop("correct")
        metrics[name]["ece_10_bins"] = ece
    days = sorted({row["day"] for row in scores["persist"]})
    test_states = {record["state_token"] for record in test}
    acceptance_blockers = []
    if len(vocab) < 2 or len(test_states) < 2:
        acceptance_blockers.append("single-state data cannot distinguish forecasting models")
    if len(days) < 2:
        acceptance_blockers.append("fewer than two holdout days")
    if any(not record.get("model_version") for record in records):
        acceptance_blockers.append("missing representation model provenance")
    if unknown:
        acceptance_blockers.append("holdout contains unknown states")
    comparisons = {}
    for name in ["first_order", "dwell", "periodic"]:
        daily = defaultdict(list)
        for row, baseline in zip(scores[name], scores["persist"]):
            daily[row["day"]].append(row["correct"] - baseline["correct"])
        rng = random.Random(42)
        samples = []
        if len(days) >= 2:
            for _ in range(1000):
                selected = [rng.choice(days) for _ in days]
                samples.append(sum(sum(daily[day]) for day in selected) / sum(len(daily[day]) for day in selected))
            samples.sort()
        comparisons[name] = {
            "accuracy_delta_over_persist": metrics[name]["top1_accuracy"] - metrics["persist"]["top1_accuracy"],
            "paired_day_bootstrap_95_interval": [samples[25], samples[974]] if samples else None,
        }
    return {
        "protocol": "traceform.predictive-holdout.v2",
        "split_time": split_time.isoformat(), "timezone": timezone_name,
        "train_records": len(train), "test_records": len(test),
        "vocabulary": vocab, "unknown_holdout_transitions": unknown,
        "evaluated_transitions": len(scores["persist"]), "test_days": len(days),
        "metrics": metrics, "paired_comparisons": comparisons,
        "acceptance_blockers": acceptance_blockers,
        "eligible_for_model_selection": not acceptance_blockers,
        "limitations": ["Representation training provenance must be checked separately.",
                         "Persistence log loss uses a 1e-12 scoring floor.",
                         "Day bootstrap is unavailable with fewer than two test days.",
                         "Irregular intervals estimate next-observation probabilities, not fixed-time hazards."],
    }
