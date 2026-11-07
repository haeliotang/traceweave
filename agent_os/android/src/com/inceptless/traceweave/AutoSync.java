package com.inceptless.traceweave;

import android.content.Context;

/** Headless sync attempt used by the background job. Writes an audit event either way. */
final class AutoSync {
    private AutoSync() {}

    static String runOnce(Context context) {
        AgentStore store = new AgentStore(context);
        try {
            if (!SyncTransport.available()) return record(store, "skipped: runtime unavailable");
            java.util.List<AgentStore.Pairing> active = new java.util.ArrayList<>();
            java.util.List<Long> lastSeen = new java.util.ArrayList<>();
            for (AgentStore.Pairing pairing : store.listPairings()) {
                if (!"active".equals(pairing.status)) continue;
                AgentStore.SyncCursor known = store.syncCursor(pairing.deviceId);
                active.add(pairing);
                lastSeen.add(known == null ? 0L : known.lastSyncedAt);
            }
            if (active.isEmpty()) return record(store, "skipped: no paired device");
            long[] stamps = new long[lastSeen.size()];
            for (int index = 0; index < stamps.length; index++) stamps[index] = lastSeen.get(index);
            AgentStore.Pairing target = active.get(AutoSyncPolicy.pickTarget(active.size(), stamps));
            AgentStore.SyncCursor cursor = store.syncCursor(target.deviceId);
            if (cursor == null || cursor.lastAddress.isEmpty())
                return record(store, "skipped: no known address for " + target.deviceId);
            String localFingerprint = SyncTransport.localFingerprint();
            if (!target.p256Bound())
                return record(store, "skipped: pairing has no P-256 binding; re-pair by fingerprint");
            SyncTransport.Prepared request = SyncTransport.prepareRequest(localFingerprint,
                target.p256Fingerprint, SyncTransport.decodeChallenge(store.getNextChallenge(target.deviceId)),
                SyncRecords.encode(store.exportSyncRecords()));
            byte[] envelope = cursor.lastAddress.startsWith("/ip4/")
                ? SyncTransport.nativeDialAddressOnce(SyncTransport.seed(context), cursor.lastAddress,
                    target.peerId, request.envelope)
                : SyncTransport.nativeDialOnce(SyncTransport.seed(context), cursor.lastPort, target.peerId,
                    request.envelope);
            byte[] response = null;
            if (envelope != null) {
                try {
                    TransportBinding.Opened opened = SyncTransport.verifyResponse(envelope, localFingerprint,
                        target.p256Fingerprint, request.nonce);
                    store.setNextChallenge(target.deviceId, SyncTransport.encodeChallenge(opened.challenge));
                    response = opened.body;
                } catch (IllegalStateException rejected) {
                    store.appendSystemAudit("sync_binding_rejected:" + rejected.getMessage());
                    return record(store, "failed: peer binding rejected (" + rejected.getMessage() + ")");
                }
            }
            boolean ok = response != null;
            int applied = ok ? SyncRecords.merge(store, response) : 0;
            store.recordSyncCursor(target.deviceId, System.currentTimeMillis(), cursor.lastAddress, cursor.lastPort,
                ok ? "ok:" + applied : "no response");
            return record(store, ok ? "completed with " + target.deviceId + " applied=" + applied : "failed: no response");
        } catch (Exception error) {
            return record(store, "error: " + error.getMessage());
        } finally {
            store.close();
        }
    }

    private static String record(AgentStore store, String message) {
        try {
            store.appendSystemAudit("sync_auto_" + message);
        } catch (Exception ignored) {
        }
        return message;
    }
}
