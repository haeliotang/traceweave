# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Experimental fitted spectrum; not selected by the production forecast path.

The local daily phase equals seven times the weekly phase modulo 2*pi.
DLMF 10.35.2 gives a finite Fourier approximation of the existing product
of two kappa=2 von Mises kernels. No timestamp bins or phase rounding are used.
"""

import math
from traceform.forecasting.periodic_kernel import PeriodicTransitionKernel


_ORDER = 18


def _bessel_integer(order):
    # I_n(2) = sum_{j>=0} 1 / (j! (n+j)!), DLMF 10.25.2.
    term = 1 / math.factorial(order)
    terms = [term]
    for index in range(1, 40):
        term /= index * (order + index)
        terms.append(term)
    return math.fsum(terms)


def _coefficients():
    bessel = [_bessel_integer(index) for index in range(_ORDER + 1)]
    terms = [[] for _ in range(8 * _ORDER + 1)]
    for day in range(-_ORDER, _ORDER + 1):
        for week in range(-_ORDER, _ORDER + 1):
            frequency = 7 * day + week
            if frequency >= 0:
                terms[frequency].append(math.exp(-4) * bessel[abs(day)] * bessel[abs(week)])
    return tuple(math.fsum(values) for values in terms)


_COEFFICIENTS = _coefficients()
# Bound in exact arithmetic only; excludes coefficient evaluation, moment
# summation, trigonometric reduction, and floating point normalization errors.
_ONE_DIMENSION_TAIL = (2 * math.exp(-2) * math.exp(1 / (_ORDER + 2))
                       / math.factorial(_ORDER + 1) * (_ORDER + 2) / (_ORDER + 1))
TRUNCATION_WEIGHT_BOUND = 2 * _ONE_DIMENSION_TAIL + _ONE_DIMENSION_TAIL ** 2


class PeriodicSpectrum:
    """Immutable fitted snapshot of an eligible PeriodicTransitionKernel.

    Construction processes the history; row() uses only per-edge moments.
    Expiration or model/timezone changes require fitting a new snapshot.
    """

    def __init__(self, kernel):
        self.timezone_name = kernel.timezone_name
        self._phases = PeriodicTransitionKernel(kernel.timezone_name).phases
        self.prior_strength = kernel.prior_strength
        self.prior = {source: dict(row) for source, row in kernel.global_trans.items()}
        if not math.isfinite(self.prior_strength) or self.prior_strength <= 0:
            raise ValueError("finite positive prior strength required")
        for row in self.prior.values():
            if any(not math.isfinite(value) or value < 0 for value in row.values()) or abs(math.fsum(row.values()) - 1) > 1e-12:
                raise ValueError("normalized finite nonnegative prior rows required")
        self.moments = {}
        self.counts = {}
        for source, phase_counts in kernel.counts.items():
            for (day, week), counts in phase_counts.items():
                if not math.isfinite(day) or not math.isfinite(week):
                    raise ValueError("finite canonical phases required")
                if abs(math.remainder(day - 7 * week, 2 * math.pi)) > 1e-12:
                    raise ValueError("daily and weekly phases must share local calendar time")
                cosines = [math.cos(frequency * week) for frequency in range(len(_COEFFICIENTS))]
                sines = [math.sin(frequency * week) for frequency in range(len(_COEFFICIENTS))]
                for target, count in counts.items():
                    if type(count) is not int or count <= 0 or source not in self.prior or target not in self.prior:
                        raise ValueError("eligible positive integer transition counts required")
                    key = source, target
                    if key not in self.moments:
                        self.moments[key] = ([0.0] * len(_COEFFICIENTS), [0.0] * len(_COEFFICIENTS))
                    cosine, sine = self.moments[key]
                    for frequency in range(len(_COEFFICIENTS)):
                        cosine[frequency] += count * cosines[frequency]
                        sine[frequency] += count * sines[frequency]
                    self.counts[source] = self.counts.get(source, 0) + count

    def row(self, source, timestamp):
        _, week = self._phases(timestamp)
        cosine = [math.cos(frequency * week) for frequency in range(len(_COEFFICIENTS))]
        sine = [math.sin(frequency * week) for frequency in range(len(_COEFFICIENTS))]
        result = {target: self.prior_strength * value
                  for target, value in self.prior.get(source, {source: 1.0}).items()}
        for (origin, target), (real, imaginary) in self.moments.items():
            if origin != source:
                continue
            weight = _COEFFICIENTS[0] * real[0] + math.fsum(
                2 * _COEFFICIENTS[frequency]
                * (cosine[frequency] * real[frequency] + sine[frequency] * imaginary[frequency])
                for frequency in range(1, len(_COEFFICIENTS)))
            if not math.isfinite(weight) or weight < 0:
                raise ValueError("invalid spectral transition mass")
            result[target] = result.get(target, 0.0) + weight
        total = math.fsum(result.values())
        if not math.isfinite(total) or total <= 0:
            raise ValueError("invalid spectral row mass")
        return {target: value / total for target, value in result.items()}

    def truncation_row_l1_bound(self, source):
        """Exact-arithmetic truncation bound; not an IEEE-754 error guarantee."""
        count = self.counts.get(source, 0)
        prior_mass = self.prior_strength * math.fsum(self.prior.get(source, {source: 1.0}).values())
        minimum_mass = prior_mass + count * math.exp(-8)
        return 2 * count * TRUNCATION_WEIGHT_BOUND / minimum_mass
