// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

import com.inceptless.traceweave.SourceInfo;

/** Host check for the AGPL section 13 source link, run by test_source_info.py. */
public final class SourceInfoHostTest {
    private static int checks = 0;

    private static void check(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("FAIL " + label);
        checks++;
    }

    public static void main(String[] args) {
        check(SourceInfo.DEFAULT_REPOSITORY.equals("https://github.com/haeliotang/traceweave"),
            "default repository");
        check(SourceInfo.repository("").equals(SourceInfo.DEFAULT_REPOSITORY), "empty repository falls back");
        check(SourceInfo.repository("https://example.com/team/traceweave/").equals("https://example.com/team/traceweave"),
            "trailing slash removed");
        check(SourceInfo.repository("javascript:alert(1)").equals(SourceInfo.DEFAULT_REPOSITORY),
            "non-https override rejected");
        check(SourceInfo.repository("http://example.com/x").equals(SourceInfo.DEFAULT_REPOSITORY),
            "http override rejected");

        String base = SourceInfo.DEFAULT_REPOSITORY;
        check(SourceInfo.url(base, "0.1.5", "0123456789abcdef0123456789abcdef01234567")
            .equals(base + "/tree/0123456789abcdef0123456789abcdef01234567"), "commit link");
        check(SourceInfo.url(base, "0.1.4", "").equals(base + "/releases/tag/v0.1.4"), "release tag link");
        check(SourceInfo.url(base, "0.1.5", "dev").equals(base + "/releases/tag/v0.1.5"),
            "non-hash commit falls back to the version tag");
        check(SourceInfo.url(base, "unknown", "").equals(base), "unknown version falls back to the repository");
        check(SourceInfo.describe(base, "0.1.4", "").contains("AGPL-3.0-or-later"), "licence name shown");
        check(SourceInfo.describe(base, "0.1.4", "").contains("/releases/tag/v0.1.4"), "url shown");
        System.out.println("PASS source_info checks=" + checks);
    }
}
