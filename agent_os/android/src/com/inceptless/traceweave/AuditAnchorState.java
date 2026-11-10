// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.content.Context;
import android.content.SharedPreferences;

import javax.crypto.SecretKey;

/**
 * Highest anchored audit index, kept outside the SQLite database and sealed with the
 * device key. Verification without this cannot tell "a row was never anchored" from
 * "an attacker deleted the anchor", which is exactly how a rewritten chain used to be
 * re-signed for free.
 */
final class AuditAnchorState {
    private static final String PREFS = "audit_anchor_state";
    private static final String COUNT = "count";
    private static final String HEAD = "head";
    private static final String AT = "at";
    private static final String MAC = "mac";

    private AuditAnchorState() {}

    static final class Snapshot {
        final boolean present;
        final boolean valid;
        final int count;
        final String head;
        final long at;

        Snapshot(boolean present, boolean valid, int count, String head, long at) {
            this.present = present;
            this.valid = valid;
            this.count = count;
            this.head = head == null ? "" : head;
            this.at = at;
        }
    }

    static void save(Context context, int count, String head, long anchoredAt) {
        SecretKey key = AnchorKey.get();
        if (key == null || count <= 0 || head == null || head.isEmpty()) return;
        String mac = AnchorMac.compute(key, count - 1, head, anchoredAt);
        context.getSharedPreferences(PREFS, 0).edit()
            .putInt(COUNT, count).putString(HEAD, head).putLong(AT, anchoredAt).putString(MAC, mac)
            .commit();
    }

    static Snapshot read(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        if (!prefs.contains(COUNT)) return new Snapshot(false, false, 0, "", 0);
        int count = prefs.getInt(COUNT, -1);
        String head = prefs.getString(HEAD, "");
        long at = prefs.getLong(AT, 0);
        String mac = prefs.getString(MAC, "");
        SecretKey key = AnchorKey.get();
        if (key == null || count <= 0 || mac == null || mac.isEmpty())
            return new Snapshot(true, false, count, head, at);
        String expected = AnchorMac.compute(key, count - 1, head, at);
        return new Snapshot(true, expected.equals(mac), count, head, at);
    }

    /** One-time baseline for installs that predate the state file. */
    static void adopt(Context context, int count, String head, long anchoredAt) {
        save(context, count, head, anchoredAt);
    }
}
