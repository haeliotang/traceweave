// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Helpers reproducing the Python semantics that the reference feature extractor relies on.
 * Ported code uses these instead of Java's own operations wherever the two differ, so that a
 * statement-by-statement translation keeps Python's results (NaN handling, rounding, truthiness).
 */
final class PyRuntime {
    private PyRuntime() {}

    static final Map<String, Object> EMPTY = Collections.emptyMap();
    private static final Pattern PLAIN_FLOAT = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    /** Python safe_float: None, non-numeric and non-finite values fall back to the default. */
    static double safeFloat(Object value, double fallback) {
        if (value == null) return fallback;
        double parsed;
        if (value instanceof Boolean) parsed = ((Boolean) value) ? 1.0 : 0.0;
        else if (value instanceof Number) parsed = ((Number) value).doubleValue();
        else if (value instanceof String) {
            String text = ((String) value).trim();
            if (!PLAIN_FLOAT.matcher(text).matches()) return fallback;
            parsed = Double.parseDouble(text);
        } else return fallback;
        return Double.isNaN(parsed) || Double.isInfinite(parsed) ? fallback : parsed;
    }

    /** Python round(x, digits): the exact binary value rounded half-to-even in decimal. */
    static double round(double value, int digits) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return value;
        double rounded = new BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
        return rounded == 0.0 && (value < 0 || 1.0 / value < 0) ? -0.0 : rounded;
    }

    /** Python min(a, b): the first argument unless the second is strictly smaller (NaN-safe as Python is). */
    static double min(double first, double second) { return second < first ? second : first; }

    /** Python max(a, b). */
    static double max(double first, double second) { return second > first ? second : first; }

    /** Python bool(value) for the JSON-like values observations contain. */
    static boolean truthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).doubleValue() != 0.0;
        if (value instanceof CharSequence) return ((CharSequence) value).length() > 0;
        if (value instanceof Collection) return !((Collection<?>) value).isEmpty();
        if (value instanceof Map) return !((Map<?, ?>) value).isEmpty();
        return true;
    }

    /** obs.get(key, {}) used as a nested container; anything that is not a mapping behaves as empty. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> dict(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        return value instanceof Map ? (Map<String, Object>) value : EMPTY;
    }

    /** container.get(key, fallback): the fallback applies only when the key is absent. */
    static Object getOr(Map<String, Object> container, String key, Object fallback) {
        return container.containsKey(key) ? container.get(key) : fallback;
    }

    static final class Stamp {
        final boolean valid;
        final double secondsInDay;
        final double epochSeconds;
        final int weekday;
        private Stamp(boolean valid, double secondsInDay, double epochSeconds, int weekday) {
            this.valid = valid; this.secondsInDay = secondsInDay; this.epochSeconds = epochSeconds; this.weekday = weekday;
        }
        static final Stamp INVALID = new Stamp(false, 0, 0, 0);
    }

    /**
     * Mirrors datetime.fromisoformat(text.replace("Z", "+00:00")) for UTC timestamps; anything
     * unparsable (or without an offset) is invalid, as Python's try/except leaves the phase unset.
     */
    static Stamp parseTimestamp(Object text) {
        if (!(text instanceof String) || ((String) text).isEmpty()) return Stamp.INVALID;
        try {
            OffsetDateTime moment = OffsetDateTime.parse(((String) text).replace("Z", "+00:00"));
            OffsetDateTime utc = moment.withOffsetSameInstant(ZoneOffset.UTC);
            long micros = utc.toEpochSecond() * 1000000L + utc.getNano() / 1000;
            double inDay = (double) (moment.getHour() * 3600 + moment.getMinute() * 60 + moment.getSecond())
                + (moment.getNano() / 1000) / 1e6;
            return new Stamp(true, inDay, micros / 1e6, moment.getDayOfWeek().getValue() - 1);
        } catch (RuntimeException invalid) {
            return Stamp.INVALID;
        }
    }
}
