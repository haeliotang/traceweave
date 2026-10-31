package com.inceptless.traceweave;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** JSON encoding for synthetic sync exchanges; test-only. */
final class SyncExchange {
    private SyncExchange() {}

    static byte[] encode(List<AgentStore.SyncRecord> records) throws Exception {
        JSONArray array = new JSONArray();
        for (AgentStore.SyncRecord record : records) {
            JSONObject clock = new JSONObject();
            for (java.util.Map.Entry<String, Long> entry : record.clock.entrySet())
                clock.put(entry.getKey(), entry.getValue());
            JSONObject value = new JSONObject()
                .put("id", record.id)
                .put("kind", record.kind)
                .put("source", record.source)
                .put("reference", record.reference)
                .put("expiresAt", record.expiresAt)
                .put("clock", clock)
                .put("tombstone", record.tombstone);
            if (record.content != null) value.put("content", record.content);
            if (record.contentHash != null) value.put("hash", record.contentHash);
            array.put(value);
        }
        return new JSONObject().put("records", array).toString().getBytes(StandardCharsets.UTF_8);
    }

    static List<AgentStore.SyncRecord> decode(byte[] payload) throws Exception {
        JSONObject root = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        JSONArray array = root.getJSONArray("records");
        if (array.length() > 500) throw new IllegalArgumentException("Too many records");
        ArrayList<AgentStore.SyncRecord> records = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject value = array.getJSONObject(index);
            JSONObject clock = value.getJSONObject("clock");
            TreeMap<String, Long> parsed = new TreeMap<>();
            for (java.util.Iterator<String> keys = clock.keys(); keys.hasNext(); ) {
                String actor = keys.next();
                parsed.put(actor, clock.getLong(actor));
            }
            boolean tombstone = value.getBoolean("tombstone");
            records.add(new AgentStore.SyncRecord(value.getString("id"), value.optString("kind", AgentStore.MEMORY_FACT),
                value.has("content") ? value.getString("content") : null,
                value.optString("source", AgentStore.MEMORY_SOURCE_USER), value.optString("reference", ""),
                value.optLong("expiresAt", 0), parsed,
                value.has("hash") ? value.getString("hash") : null, tombstone));
        }
        return records;
    }

    /** Merge the peer's records into the local store, then return the local records. */
    static byte[] handle(AgentStore store, byte[] request) {
        try {
            store.mergeSyncRecords(decode(request));
            return encode(store.exportSyncRecords());
        } catch (Exception error) {
            throw new IllegalStateException("sync exchange failed: " + error.getMessage(), error);
        }
    }

    static void mergeResponse(AgentStore store, byte[] response) throws Exception {
        store.mergeSyncRecords(decode(response));
    }
}
