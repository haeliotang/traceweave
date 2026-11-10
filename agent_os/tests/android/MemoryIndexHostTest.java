// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

import com.inceptless.traceweave.MemorySearch;
import java.util.List;

/** Host check for the memory search rules, run by test_memory_index.py. */
public final class MemoryIndexHostTest {
    private static int checks = 0;

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("FAIL " + message);
        checks++;
    }

    private static boolean matches(String haystack, String query) {
        List<String> terms = MemorySearch.tokens(query);
        String lowered = haystack.toLowerCase(java.util.Locale.ROOT);
        for (String term : terms) {
            if (term.startsWith("han:")) {
                if (!containsHan(haystack, query)) return false;
            } else if (!lowered.contains(term)) {
                return false;
            }
        }
        return !terms.isEmpty();
    }

    private static boolean containsHan(String haystack, String query) {
        for (int offset = 0; offset < query.length();) {
            int codePoint = query.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
                && haystack.indexOf(new String(Character.toChars(codePoint))) < 0) return false;
        }
        return true;
    }

    public static void main(String[] args) {
        String preference = "preference GroupingCheckPreference user";
        String fact = "fact GroupingCheckFact user";
        String chinese = "fact 露营清单检查 user";
        check(matches(preference, "check"), "substring search finds the preference row");
        check(matches(fact, "CHECK"), "search is case insensitive");
        check(matches(fact, "fact"), "kind is searchable");
        check(matches(chinese, "露营"), "han characters match");
        check(!matches(fact, "missing"), "unknown term does not match");
        check(MemorySearch.tokens("").isEmpty(), "empty query has no terms");
        check(MemorySearch.tokens("check").size() == 1, "one latin term");
        System.out.println("PASS memory_index checks=" + checks);
    }
}
