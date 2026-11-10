// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Search rules for the memory list. Kept free of Android and store types so the
 * behaviour can be checked on a plain JVM.
 */
public final class MemorySearch {
    private MemorySearch() {}

    /** Splits a query into terms; Han characters are kept as single-character units. */
    public static List<String> tokens(String text) {
        ArrayList<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        int previousHan = -1;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                if (token.length() > 0) {
                    result.add(token.toString().toLowerCase(Locale.ROOT));
                    token.setLength(0);
                }
                result.add("han:" + codePoint);
                if (previousHan >= 0) result.add("han:" + previousHan + ":" + codePoint);
                previousHan = codePoint;
                continue;
            }
            if (Character.isLetterOrDigit(codePoint)) {
                previousHan = -1;
                token.appendCodePoint(Character.toLowerCase(codePoint));
            } else if (token.length() > 0) {
                result.add(token.toString().toLowerCase(Locale.ROOT));
                token.setLength(0);
                previousHan = -1;
            } else {
                previousHan = -1;
            }
        }
        if (token.length() > 0) result.add(token.toString().toLowerCase(Locale.ROOT));
        return result;
    }
}
