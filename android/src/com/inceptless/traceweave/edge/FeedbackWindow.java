package com.inceptless.traceweave.edge;

/** Ten-minute state-change association, censored on source or clock discontinuity. */
final class FeedbackWindow {
    static final class Frame {
        final long epochMillis, elapsedMillis;
        final String identity, token;
        final boolean continuous;
        Frame(long epochMillis, long elapsedMillis, String identity, String token, boolean continuous) {
            if (elapsedMillis < 0 || identity == null || identity.isEmpty() || token == null || token.isEmpty())
                throw new IllegalArgumentException("source-bound monotonic frame required");
            this.epochMillis = epochMillis; this.elapsedMillis = elapsedMillis;
            this.identity = identity; this.token = token; this.continuous = continuous;
        }
    }
    static final class Outcome {
        final String status, reason;
        final int reward;
        Outcome(String status, String reason, int reward) { this.status = status; this.reason = reason; this.reward = reward; }
    }
    static Outcome observe(Frame original, Frame previous, Frame current) {
        long duration = current.elapsedMillis - original.elapsedMillis;
        long gap = current.elapsedMillis - previous.elapsedMillis;
        double wallDuration = (double) current.epochMillis - original.epochMillis;
        double wallGap = (double) current.epochMillis - previous.epochMillis;
        if (!original.identity.equals(current.identity) || !previous.identity.equals(current.identity)
                || !current.continuous || gap <= 0 || gap > 300000 || wallGap <= 0
                || Math.abs(wallDuration - duration) > 1000 || duration < 0)
            return new Outcome("censored", "source_or_clock_discontinuity", -1);
        if (duration > 900000) return new Outcome("censored", "feedback_timeout", -1);
        if (duration >= 600000) return new Outcome("evaluated", "state_change_association_not_causal_effect",
            current.token.equals(original.token) ? 0 : 1);
        return new Outcome("observing", "awaiting_ten_minute_window", -1);
    }
}
