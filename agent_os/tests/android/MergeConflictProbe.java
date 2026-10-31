package org.traceform.agentos;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Locale;
import java.util.TreeMap;

/** Test-only probe: two concurrent edits of one memory must keep both versions. */
public final class MergeConflictProbe extends Activity {
    private static final String TAG = "AgentOsMergeProbe";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            String result;
            try {
                result = run();
            } catch (Exception error) {
                result = "error: " + error;
            }
            Log.i(TAG, "MERGE_CONFLICT " + result);
        }, "merge-probe").start();
    }

    private String run() throws Exception {
        AgentStore store = new AgentStore(this);
        try {
            AgentStore.Memory memory = store.createMemory(AgentStore.MEMORY_FACT, "conflict base value");
            int before = store.listMemoryVersions(memory.id).size();
            TreeMap<String, Long> remoteClock = new TreeMap<>();
            remoteClock.put("remote-actor", System.currentTimeMillis() + 1);
            String remoteContent = "conflict remote value";
            AgentStore.SyncRecord record = new AgentStore.SyncRecord(memory.id, AgentStore.MEMORY_FACT,
                remoteContent, AgentStore.MEMORY_SOURCE_USER, "", 0, remoteClock, sha256(remoteContent), false);
            int applied = store.mergeSyncRecords(Collections.singletonList(record));
            int after = store.listMemoryVersions(memory.id).size();
            boolean candidate = store.hasMemoryCandidate(memory.id);
            String stored = null;
            for (AgentStore.Memory item : store.listMemories()) if (item.id.equals(memory.id)) stored = item.content;
            return "before=" + before + " applied=" + applied + " after=" + after
                + " candidate=" + candidate + " stored=\"" + stored + "\"";
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
