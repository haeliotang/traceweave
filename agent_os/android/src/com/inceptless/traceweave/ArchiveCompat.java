// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

/**
 * Compatibility rules for archives written before the Traceweave rename.
 *
 * <p>The rename changed the archive format id, the memory source for provider-sourced
 * memories and the capability ids that appear inside stored execution plans. Archives
 * exported by 0.6.x must still import, so every legacy spelling is mapped here instead
 * of being scattered through the reader.
 */
public final class ArchiveCompat {
    public static final String FORMAT = "com.inceptless.traceweave.memory";
    public static final String LEGACY_FORMAT = "org.traceform.agentos.memory";

    static final String SOURCE_TRACEWEAVE = "traceweave";
    static final String LEGACY_SOURCE_TRACEWEAVE = "traceform";

    private static final String LEGACY_CAPABILITY_PREFIX = "traceform.";
    private static final String CAPABILITY_PREFIX = "traceweave.";

    private ArchiveCompat() {}

    /** Accepts the current format id and the pre-rename one. */
    public static boolean isFormatAccepted(String format) {
        return FORMAT.equals(format) || LEGACY_FORMAT.equals(format);
    }

    /** Maps the legacy provider source onto the current one; other sources pass through. */
    public static String normalizeSource(String source) {
        return LEGACY_SOURCE_TRACEWEAVE.equals(source) ? SOURCE_TRACEWEAVE : source;
    }

    /** Rewrites capability and scope ids stored by 0.6.x so imported plans still resolve. */
    public static String normalizeCapabilityId(String value) {
        if (value == null) return null;
        return value.startsWith(LEGACY_CAPABILITY_PREFIX)
            ? CAPABILITY_PREFIX + value.substring(LEGACY_CAPABILITY_PREFIX.length())
            : value;
    }

    /** Rewrites every legacy capability id inside a serialized plan. */
    public static String normalizePlanJson(String json) {
        if (json == null) return null;
        return json.replace("\"traceform.", "\"traceweave.");
    }
}
