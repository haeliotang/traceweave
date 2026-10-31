package com.inceptless.traceweave.edge;

/** Terminal-risk and trajectory divergence for a modeled CLAMP_DWELL scenario. */
final class CounterfactualDecision {
    static final class Result {
        final double meanJsd, peakJsd, terminalRiskChange;
        final int peakStep;
        final String recommendation;
        Result(double meanJsd, double peakJsd, int peakStep, double terminalRiskChange) {
            this.meanJsd = meanJsd; this.peakJsd = peakJsd; this.peakStep = peakStep;
            this.terminalRiskChange = terminalRiskChange;
            recommendation = terminalRiskChange < -0.05 ? "RECOMMEND_INTERVENTION"
                : meanJsd < 0.02 ? "NEUTRAL" : "MAINTAIN_STATUS_QUO";
        }
    }
    static Result compare(long[] counts, int states, int horizon, int runs, boolean[] risk) {
        if (counts == null || risk == null || states < 1 || states > 128 || horizon < 1 || horizon > 2880
                || runs < 1 || runs > 10000 || risk.length != states || counts.length != 2L * states * horizon)
            throw new IllegalArgumentException("bounded matching comparison required");
        int branchSize = states * horizon, peakStep = 0;
        double totalJsd = 0, peakJsd = 0;
        long riskDifference = 0;
        for (int step = 0; step < horizon; step++) {
            long massA = 0, massB = 0;
            double jsd = 0;
            for (int state = 0; state < states; state++) {
                long a = counts[step * states + state], b = counts[branchSize + step * states + state];
                if (a < 0 || b < 0 || a > runs || b > runs) throw new IllegalArgumentException("invalid occupancy count");
                massA += a; massB += b;
                double p = a / (double) runs, q = b / (double) runs, middle = (p + q) / 2;
                if (p > 0) jsd += 0.5 * p * Math.log(p / middle) / Math.log(2);
                if (q > 0) jsd += 0.5 * q * Math.log(q / middle) / Math.log(2);
                if (step == horizon - 1 && risk[state]) riskDifference += b - a;
            }
            if (massA != runs || massB != runs) throw new IllegalArgumentException("comparison mass mismatch");
            totalJsd += jsd;
            if (jsd > peakJsd) { peakJsd = jsd; peakStep = step; }
        }
        return new Result(totalJsd / horizon, peakJsd, peakStep, riskDifference / (double) runs);
    }
}
