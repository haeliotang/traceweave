// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import java.util.Locale;

/**
 * Builds the "Source" link required by section 13 of the AGPL.
 *
 * <p>The link points at the source of the running build: a commit when the build knows one,
 * otherwise the release tag for the version in the manifest, otherwise the repository root.
 * The repository can be overridden in settings for forks or self-hosted mirrors.
 */
public final class SourceInfo {
    public static final String DEFAULT_REPOSITORY = "https://github.com/haeliotang/traceweave";
    public static final String LICENSE = "AGPL-3.0-or-later";

    private SourceInfo() {}

    /** Repo root with any trailing slash removed; empty means the default. */
    public static String repository(String configured) {
        String value = configured == null ? "" : configured.trim();
        if (value.isEmpty()) return DEFAULT_REPOSITORY;
        if (!value.matches("https://[A-Za-z0-9._~:/?#@!$&'()*+,;=%-]+")) return DEFAULT_REPOSITORY;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    /** @param commit a git commit hash when the build knows it, otherwise null/empty */
    public static String url(String repository, String versionName, String commit) {
        String base = repository(repository);
        String hash = commit == null ? "" : commit.trim().toLowerCase(Locale.ROOT);
        if (hash.matches("[0-9a-f]{7,40}")) return base + "/tree/" + hash;
        String version = versionName == null ? "" : versionName.trim();
        if (version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) return base + "/releases/tag/v" + version;
        return base;
    }

    /** Human-readable line for the settings screen. */
    public static String describe(String repository, String versionName, String commit) {
        return versionName + " · " + LICENSE + " · " + url(repository, versionName, commit);
    }
}
