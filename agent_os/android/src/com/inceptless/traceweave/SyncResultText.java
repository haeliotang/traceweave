// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

/** Pure mapping from a stored sync result to readable text. */
final class SyncResultText {
    private SyncResultText() {}

    /** Turns a stored sync result into a short sentence a user can read. */
    static String describe(String result, boolean en) {
        if (result == null || result.isEmpty()) return T.s("pair.never_synced", en);
        if (result.startsWith("ok:")) {
            String applied = result.substring(3).trim();
            return T.format("pair.sync_done", en, applied.isEmpty() ? "0" : applied);
        }
        if ("paired".equals(result)) return T.s("pair.paired_not_synced", en);
        if (result.startsWith("no response")) return T.s("pair.peer_offline", en);
        if (result.startsWith("skipped")) return T.s("pair.sync_skipped", en);
        if (result.startsWith("error")) return T.s("pair.sync_error", en);
        return result;
    }

}
