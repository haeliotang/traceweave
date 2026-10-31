package org.traceform.agentos;

import android.content.Context;

/** Headless sync attempt used by the background job. Writes an audit event either way. */
final class AutoSync {
    private AutoSync() {}

    static String runOnce(Context context) {
        AgentStore store = new AgentStore(context);
        try {
            if (!SyncTransport.available()) return record(store, "skipped: runtime unavailable");
            AgentStore.Pairing target = null;
            for (AgentStore.Pairing pairing : store.listPairings()) {
                if (!"active".equals(pairing.status)) continue;
                if (target == null) target = pairing;
            }
            if (target == null) return record(store, "skipped: no paired device");
            AgentStore.SyncCursor cursor = store.syncCursor(target.deviceId);
            if (cursor == null || cursor.lastAddress.isEmpty())
                return record(store, "skipped: no known address for " + target.deviceId);
            byte[] request = SyncRecords.encode(store.exportSyncRecords());
            byte[] response = SyncTransport.nativeDialOnce(SyncTransport.seed(context), cursor.lastPort,
                target.peerId, request);
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
