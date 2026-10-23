"""Continuous daily/weekly smoothing of observed adjacent transitions."""

from collections import Counter
import datetime
import math
from traceform.timezones import resolve_zone
from traceform.forecasting.continuity import same_stream_segment


class PeriodicTransitionKernel:
    def __init__(self, timezone_name="UTC", prior_strength=5.0):
        self.timezone_name = timezone_name
        self.timezone = resolve_zone(timezone_name)
        if prior_strength <= 0:
            raise ValueError("prior_strength must be positive")
        self.prior_strength = prior_strength
        self.counts = {}
        self.global_trans = {}

    def phases(self, timestamp):
        if timestamp.tzinfo is None:
            raise ValueError("timezone-aware timestamp required")
        local = timestamp.astimezone(self.timezone)
        hours = local.hour + local.minute / 60 + local.second / 3600 + local.microsecond / 3.6e9
        return 2 * math.pi * hours / 24, 2 * math.pi * (local.weekday() + hours / 24) / 7

    def fit(self, records, global_trans, max_gap_seconds=1800.0):
        self.counts = {}
        self.global_trans = global_trans
        for first, second in zip(records, records[1:]):
            try:
                start = datetime.datetime.fromisoformat(first["timestamp_utc"].replace("Z", "+00:00"))
                end = datetime.datetime.fromisoformat(second["timestamp_utc"].replace("Z", "+00:00"))
                gap = (end - start).total_seconds()
                phases = self.phases(start)
            except (KeyError, ValueError, TypeError):
                continue
            if not 0 < gap <= max_gap_seconds:
                continue
            if not same_stream_segment(first, second):
                continue
            source, target = first.get("state_token"), second.get("state_token")
            if source not in global_trans or target not in global_trans:
                continue
            # Exact phase aggregation preserves continuity without query-time binning.
            self.counts.setdefault(source, {}).setdefault(phases, Counter())[target] += 1
        return self

    def row(self, source, timestamp):
        day, week = self.phases(timestamp)
        prior = self.global_trans.get(source, {source: 1.0})
        result = Counter({target: self.prior_strength * probability for target, probability in prior.items()})
        for (observed_day, observed_week), counts in self.counts.get(source, {}).items():
            weight = math.exp(2 * (math.cos(day - observed_day) - 1) + 2 * (math.cos(week - observed_week) - 1))
            for target, count in counts.items():
                result[target] += weight * count
        total = sum(result.values())
        return {target: count / total for target, count in result.items()}
