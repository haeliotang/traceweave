"""Offline five-coordinate candidate; never used by the production normalizer.

Validities remain separate from the state vector. Missing direct GNSS observations
are neutral, not synthetic satellite measurements. A new manifold and independent
annotated validation are required before considering deployment.
"""

import copy
import datetime
import math

from traceform.representation.normalizer import MultimodalNormalizer
from traceform.representation.provenance import content_digest


class MissingnessProjectionCandidate:
    GNSS = {
        'gnss_visible_satellite_density': (('visible_satellites', 'gnss_sat_count'), 0, 40),
        'gnss_mean_carrier_to_noise_ratio': (('mean_carrier_to_noise_ratio', 'gnss_cn0_dbhz'), 0, 50),
        'gnss_position_dilution_of_precision': (('position_dilution_of_precision', 'pdop'), 0.5, 20),
    }
    THERMAL = ('thermal_conduction_rate_dt', 'thermal_flux_acceleration_dt2')

    def __init__(self, baseline_normalizer):
        self.baseline = copy.deepcopy(baseline_normalizer)
        self.normalizer = MultimodalNormalizer.from_dict(self.baseline)
        self.names = self.baseline['feature_names']
        if len(self.names) != 128 or not set(self.GNSS).union(self.THERMAL) <= set(self.names):
            raise ValueError('128D baseline with candidate coordinates required')
        self.reset_state()

    @property
    def digest(self):
        return content_digest(self.to_dict())

    def to_dict(self):
        return {'protocol': 'traceform.offline-missingness-projection.v1',
                'baseline_normalizer': copy.deepcopy(self.baseline),
                'gnss_policy': 'direct_finite_in_range_only_missing_neutral_separate_validity',
                'thermal_policy': {'rate_bound_degC_per_hour': 10,
                                   'acceleration_bound_degC_per_hour_per_second': 2,
                                   'coordinate_weight': 1 / math.sqrt(2),
                                   'maximum_gap_seconds': 300,
                                   'minimum_gap_seconds': 5,
                                   'device_bias_subtraction': False},
                'scope': 'offline_projection_requires_new_manifold_and_independent_validation'}

    def reset_state(self):
        self.previous = None
        self.previous_rate = None

    @staticmethod
    def finite_number(value):
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
            return None
        return float(value)

    def project(self, observation, baseline_vector, continuous_from_previous):
        if (len(baseline_vector) != len(self.names)
                or any(self.finite_number(value) is None for value in baseline_vector)):
            raise ValueError('finite dimension-matched baseline vector required')
        vector = list(baseline_vector)
        validity = {}
        place = observation.get('place', {})
        for name, (keys, low, high) in self.GNSS.items():
            supplied = next((place[key] for key in keys if place.get(key) is not None), None)
            value = self.finite_number(supplied)
            valid = value is not None and low <= value <= high
            validity[name] = valid
            if valid:
                scaled = self.normalizer.scale_raw({name: value})[self.names.index(name)]
            else:
                scaled = 0.0
            vector[self.names.index(name)] = scaled

        stamp = datetime.datetime.fromisoformat(observation['timestamp_utc'].replace('Z', '+00:00'))
        if stamp.tzinfo is None:
            raise ValueError('timezone-aware observation timestamp required')
        instant = stamp.timestamp()
        temperature = self.finite_number(observation.get('environment', {}).get('device_temp_c'))
        rate = acceleration = None
        if continuous_from_previous and temperature is not None and self.previous is not None:
            before, previous_temperature = self.previous
            interval = instant - before
            if 5 <= interval <= 300:
                rate = max(-10.0, min(10.0, (temperature - previous_temperature) * 3600 / interval))
                if self.previous_rate is not None:
                    acceleration = max(-2.0, min(2.0, (rate - self.previous_rate) / interval))
        for name, value, bound in zip(self.THERMAL, (rate, acceleration), (10, 2)):
            validity[name] = value is not None
            vector[self.names.index(name)] = value / bound / math.sqrt(2) if value is not None else 0.0
        self.previous = (instant, temperature) if temperature is not None else None
        self.previous_rate = rate
        return {'vector': vector, 'validity': validity}
