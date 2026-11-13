// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.app.Activity;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.TextView;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Track B read-only exporter: builds a canonical v2 portable state from the frozen app database,
 * signs it with the production device identity (Android Keystore), and writes a TWPH1 handoff to
 * Downloads. No database schema or wire protocol changes; this build is not the 0.1.5 release.
 */
public final class PortableExportActivity extends Activity {
    private static final byte[] DOMAIN = "traceweave-portable-handoff-v1".getBytes(StandardCharsets.UTF_8);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView output = new TextView(this);
        setContentView(output);
        new Thread(() -> {
            String result;
            try {
                DeviceIdentity.create();
                long signedAt = System.currentTimeMillis();
                byte[] canonical;
                int memoryCount;
                int taskCount;
                try (AgentStore store = new AgentStore(this)) {
                    MemoryArchive.Data data = store.exportData();
                    TreeSet<String> memoryTombstones = new TreeSet<>();
                    TreeSet<String> taskTombstones = new TreeSet<>();
                    for (AgentStore.Tombstone tombstone : data.tombstones) {
                        if ("memory".equals(tombstone.entityType)) memoryTombstones.add(tombstone.entityId);
                        else if ("task".equals(tombstone.entityType)) taskTombstones.add(tombstone.entityId);
                    }
                    TreeMap<String, AgentStore.Memory> memories = new TreeMap<>();
                    for (AgentStore.Memory memory : data.memories) {
                        if (!memoryTombstones.contains(memory.id)) memories.put(memory.id, memory);
                    }
                    TreeMap<String, AgentStore.Task> tasks = new TreeMap<>();
                    for (AgentStore.Task task : data.tasks) {
                        if (!taskTombstones.contains(task.id)) tasks.put(task.id, task);
                    }
                    memoryCount = memories.size();
                    taskCount = tasks.size();
                    canonical = canonicalState(memories, tasks);
                }
                byte[] message = ByteBuffer.allocate(DOMAIN.length + 8 + canonical.length)
                    .put(DOMAIN)
                    .putLong(signedAt)
                    .put(canonical)
                    .array();
                byte[] signature = DeviceIdentity.sign(message);
                byte[] spki = DeviceIdentity.publicKey();
                byte[] envelope = ByteBuffer.allocate(5 + 8 + 4 + spki.length + 4 + canonical.length + 4 + signature.length)
                    .put("TWPH1".getBytes(StandardCharsets.UTF_8))
                    .putLong(signedAt)
                    .putInt(spki.length).put(spki)
                    .putInt(canonical.length).put(canonical)
                    .putInt(signature.length).put(signature)
                    .array();
                String name = "traceweave-portable-" + signedAt + ".twph";
                if (Build.VERSION.SDK_INT < 29) throw new IllegalStateException("Downloads export requires API 29");
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("could not create the Downloads item");
                try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
                    stream.write(envelope);
                }
                result = "PASS portable_export file=" + name
                    + " bytes=" + envelope.length
                    + " memories=" + memoryCount
                    + " tasks=" + taskCount
                    + " state_sha256=" + sha256(canonical)
                    + " signed_at=" + signedAt
                    + " fingerprint=" + DeviceIdentity.fingerprint();
                Log.i("AgentOsPortableExport", result);
            } catch (Throwable error) {
                result = "FAIL portable_export " + error;
                Log.e("AgentOsPortableExport", result);
            }
            String text = result;
            runOnUiThread(() -> output.setText(text));
        }).start();
    }

    private static byte[] canonicalState(TreeMap<String, AgentStore.Memory> memories,
            TreeMap<String, AgentStore.Task> tasks) {
        StringBuilder json = new StringBuilder(4096);
        json.append("{\"version\":2,\"memories\":{");
        boolean first = true;
        for (AgentStore.Memory memory : memories.values()) {
            if (!first) json.append(',');
            first = false;
            json.append('"').append(escape(memory.id)).append("\":[{\"clock\":{\"android\":1},\"value\":\"")
                .append(escape(memory.content == null ? "" : memory.content)).append("\"}]");
        }
        json.append("},\"tasks\":{");
        first = true;
        for (AgentStore.Task task : tasks.values()) {
            if (!first) json.append(',');
            first = false;
            json.append('"').append(escape(task.id)).append("\":{\"task_id\":\"").append(escape(task.id))
                .append("\",\"goal\":\"").append(escape(task.title == null ? "" : task.title))
                .append("\",\"status\":\"").append(portableStatus(task.status))
                .append("\",\"steps\":[],\"effects\":{},\"updated_at\":").append(task.updatedAt)
                .append(",\"needs_authorization\":true}");
        }
        json.append("},\"grants\":[],\"audit\":{\"entries\":[]}}");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String portableStatus(String status) {
        if ("completed".equals(status)) return "completed";
        if ("cancelled".equals(status)) return "cancelled";
        if ("failed".equals(status)) return "failed";
        return "waiting_for_user";
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char item = value.charAt(index);
            switch (item) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (item < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) item));
                    else out.append(item);
            }
        }
        return out.toString();
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder();
        for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 255));
        return result.toString();
    }
}
