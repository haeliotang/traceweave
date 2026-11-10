// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Test-only probe: stands up a second libp2p identity inside this process, pairs
 * with it, then runs the real AutoSync path so the dial, merge, cursor and audit
 * can be checked without a second device.
 */
public final class AutoSyncProbe extends Activity {
    private static final String TAG = "AgentOsAutoSyncProbe";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            String result;
            try {
                result = run();
            } catch (Exception error) {
                result = "error: " + error;
            }
            Log.i(TAG, "AUTO_SYNC_PROBE " + result);
        }, "auto-sync-probe").start();
    }

    private String run() throws Exception {
        AgentStore store = new AgentStore(this);
        try {
            if (!SyncTransport.available()) return "sync runtime unavailable";
            String appSeed = SyncTransport.seed(this);
            String appPeer = SyncTransport.nativePeerId(appSeed);
            String serverSeed = sha256("auto-sync-probe-server");
            String serverPeer = SyncTransport.nativePeerId(serverSeed);
            String serverFingerprint = sha256("fingerprint:" + serverSeed);
            String appFingerprint = sha256("fingerprint:" + appSeed);

            for (AgentStore.Pairing existing : store.listPairings())
                SyncPairing.revoke(store, existing.deviceId);
            SyncPairing.confirm(store, appFingerprint, serverFingerprint,
                "probe-" + serverFingerprint.substring(0, 16), serverPeer, "000000", "000000");
            store.recordSyncCursor("probe-" + serverFingerprint.substring(0, 16), 0L, "127.0.0.1", 47960, "seeded");

            // The server needs a record too so the transport accepts the client.
            Thread server = new Thread(() -> {
                try {
                    SyncTransport.nativeServeOnce(serverSeed, 47960, appPeer,
                        request -> SyncRecords.handle(store, request));
                } catch (Throwable ignored) {
                }
            }, "auto-sync-probe-server");
            server.start();
            Thread.sleep(1500);

            String outcome = AutoSync.runOnce(this);
            server.interrupt();
            String cursor = "none";
            AgentStore.SyncCursor row = store.syncCursor("probe-" + serverFingerprint.substring(0, 16));
            if (row != null) cursor = row.lastAddress + ":" + row.lastPort + " " + row.lastResult;
            return "outcome=" + outcome + " cursor=" + cursor + " appPeer=" + appPeer.substring(0, 8)
                + " serverPeer=" + serverPeer.substring(0, 8);
        } finally {
            store.close();
        }
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        for (byte item : digest) builder.append(String.format(Locale.ROOT, "%02x", item & 255));
        return builder.toString();
    }
}
