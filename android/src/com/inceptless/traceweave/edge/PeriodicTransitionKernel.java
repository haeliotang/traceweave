// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Exact-phase daily/weekly smoothing of same-segment adjacent transitions. */
public final class PeriodicTransitionKernel {
    private final ZoneId timezone;
    private final int states;
    private final double[][] prior;
    private final double[] days, weeks;
    private final int[] sources, targets;
    public final int eligiblePairs;

    public PeriodicTransitionKernel(String timezoneName, int states, long[] epochsMillis,
            int[] tokens, String[] segments, boolean[] continuousFromPrevious, long maxGapMillis) {
        if (states < 1 || maxGapMillis <= 0 || epochsMillis.length != tokens.length
                || segments.length != tokens.length || continuousFromPrevious.length != tokens.length)
            throw new IllegalArgumentException("dimension-matched chronological records required");
        timezone = ZoneId.of(timezoneName);
        this.states = states;
        prior = new double[states][states];
        days = new double[Math.max(0, tokens.length - 1)];
        weeks = new double[days.length];
        sources = new int[days.length]; targets = new int[days.length];
        int pairs = 0;
        for (int index = 1; index < tokens.length; index++) {
            int source = tokens[index - 1], target = tokens[index];
            if (source < 0 || source >= states || target < 0 || target >= states)
                throw new IllegalArgumentException("state outside vocabulary");
            long start = epochsMillis[index - 1], end = epochsMillis[index];
            if (!continuousFromPrevious[index] || segments[index] == null
                    || !segments[index].equals(segments[index - 1]) || end <= start
                    || (double) end - start > maxGapMillis) continue;
            double[] phase = phases(start);
            days[pairs] = phase[0]; weeks[pairs] = phase[1];
            sources[pairs] = source; targets[pairs] = target;
            prior[source][target]++;
            pairs++;
        }
        if (tokens.length == 1 && (tokens[0] < 0 || tokens[0] >= states))
            throw new IllegalArgumentException("state outside vocabulary");
        eligiblePairs = pairs;
        for (int source = 0; source < states; source++) {
            double total = 0;
            for (double count : prior[source]) total += count;
            if (total == 0) prior[source][source] = 1;
            else for (int target = 0; target < states; target++) prior[source][target] /= total;
        }
    }

    public double[] phases(long epochMillis) {
        ZonedDateTime local = Instant.ofEpochMilli(epochMillis).atZone(timezone);
        double fraction = (local.toLocalTime().toSecondOfDay() + local.getNano() / 1e9) / 86400;
        return new double[]{2 * Math.PI * fraction,
            2 * Math.PI * (local.getDayOfWeek().getValue() - 1 + fraction) / 7};
    }

    double[] phaseSequence(long epochMillis, long stepMillis, int horizon) {
        if (stepMillis <= 0 || horizon < 1 || horizon > 2880)
            throw new IllegalArgumentException("bounded positive phase sequence required");
        java.time.zone.ZoneRules rules = timezone.getRules();
        Instant first = Instant.ofEpochMilli(epochMillis);
        int offset = rules.getOffset(first).getTotalSeconds();
        java.time.zone.ZoneOffsetTransition transition = rules.nextTransition(first);
        double[] result = new double[horizon * 2];
        for (int step = 0; step < horizon; step++) {
            long instant = Math.addExact(epochMillis, Math.multiplyExact((long) step, stepMillis));
            long seconds = Math.floorDiv(instant, 1000);
            if (transition != null && seconds >= transition.getInstant().getEpochSecond()) {
                Instant current = Instant.ofEpochMilli(instant);
                offset = rules.getOffset(current).getTotalSeconds();
                transition = rules.nextTransition(current);
            }
            long local = seconds + offset;
            double fraction = (Math.floorMod(local, 86400) + Math.floorMod(instant, 1000) / 1000.0) / 86400;
            long weekday = Math.floorMod(Math.floorDiv(local, 86400) + 3, 7);
            result[step * 2] = 2 * Math.PI * fraction;
            result[step * 2 + 1] = 2 * Math.PI * (weekday + fraction) / 7;
        }
        return result;
    }

    public double[] row(int source, long epochMillis) {
        if (source < 0 || source >= states) throw new IllegalArgumentException("unknown source");
        double[] phase = phases(epochMillis), result = new double[states];
        for (int target = 0; target < states; target++) result[target] = 5 * prior[source][target];
        for (int pair = 0; pair < eligiblePairs; pair++) {
            if (sources[pair] != source) continue;
            double weight = Math.exp(2 * (Math.cos(phase[0] - days[pair]) - 1)
                + 2 * (Math.cos(phase[1] - weeks[pair]) - 1));
            result[targets[pair]] += weight;
        }
        double total = 0;
        for (double value : result) total += value;
        for (int target = 0; target < states; target++) result[target] /= total;
        return result;
    }

    double[] priorArray() {
        double[] result = new double[states * states];
        for (int source = 0; source < states; source++)
            System.arraycopy(prior[source], 0, result, source * states, states);
        return result;
    }
    FittedPeriodicSpectrum fitSpectrum() {
        double[] observed = new double[eligiblePairs * 2];
        for (int pair = 0; pair < eligiblePairs; pair++) {
            observed[pair * 2] = days[pair]; observed[pair * 2 + 1] = weeks[pair];
        }
        return new FittedPeriodicSpectrum(states, observed,
            java.util.Arrays.copyOf(sources, eligiblePairs), java.util.Arrays.copyOf(targets, eligiblePairs));
    }
    /** CDF layout consumed directly by NativeCore.rollouts. */
    public double[] nativeCdf(long epochMillis, long stepMillis, int horizon) {
        if (stepMillis <= 0 || horizon < 1 || horizon > 2880
                || (long) horizon * states * states > 1000000 || eligiblePairs > 1000000)
            throw new IllegalArgumentException("bounded positive periodic workload required");
        double[] queries = phaseSequence(epochMillis, stepMillis, horizon), observed = new double[eligiblePairs * 2];
        double[] flattenedPrior = new double[states * states];
        for (int source = 0; source < states; source++)
            System.arraycopy(prior[source], 0, flattenedPrior, source * states, states);
        for (int pair = 0; pair < eligiblePairs; pair++) {
            observed[pair * 2] = days[pair]; observed[pair * 2 + 1] = weeks[pair];
        }
        return NativeCore.periodicCdf(flattenedPrior, queries, observed,
            java.util.Arrays.copyOf(sources, eligiblePairs), java.util.Arrays.copyOf(targets, eligiblePairs), states);
    }

    /** Portable reference path with identical probability semantics. */
    public double[] cdf(long epochMillis, long stepMillis, int horizon) {
        if (stepMillis <= 0 || horizon < 1) throw new IllegalArgumentException("positive step and horizon required");
        double[] result = new double[Math.multiplyExact(horizon, Math.multiplyExact(states, states))];
        for (int step = 0; step < horizon; step++) {
            long instant = Math.addExact(epochMillis, Math.multiplyExact((long) step, stepMillis));
            double[] phase = phases(instant);
            int offset = step * states * states;
            for (int source = 0; source < states; source++)
                for (int target = 0; target < states; target++)
                    result[offset + source * states + target] = 5 * prior[source][target];
            for (int pair = 0; pair < eligiblePairs; pair++) {
                double weight = Math.exp(2 * (Math.cos(phase[0] - days[pair]) - 1)
                    + 2 * (Math.cos(phase[1] - weeks[pair]) - 1));
                result[offset + sources[pair] * states + targets[pair]] += weight;
            }
            for (int source = 0; source < states; source++) {
                int row = offset + source * states;
                double total = 0;
                for (int target = 0; target < states; target++) total += result[row + target];
                double cumulative = 0;
                for (int target = 0; target < states; target++) {
                    cumulative += result[row + target] / total;
                    result[row + target] = target == states - 1 ? 1 : Math.min(1, cumulative);
                }
            }
        }
        return result;
    }
}
