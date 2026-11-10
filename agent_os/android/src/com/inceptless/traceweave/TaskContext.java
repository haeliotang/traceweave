// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** User-selected, encrypted task snapshot. Contains no permission or cloud grant. */
final class TaskContext {
    private TaskContext() {}

    static boolean isDocument(ExecutionPlan plan) {
        try { return plan != null && "verified_document".equals(new JSONObject(plan.context).optString("intent")); }
        catch (Exception error) { return false; }
    }

    static String selected(List<AgentStore.Memory> memories) throws Exception {
        return selected(memories, true);
    }

    static String selected(List<AgentStore.Memory> memories, boolean localOnly) throws Exception {
        JSONArray selected = new JSONArray();
        for (AgentStore.Memory memory : memories) {
            JSONObject item = new JSONObject().put("id", memory.id).put("kind", memory.kind).put("content", memory.content)
                .put("source", memory.source).put("sourceReference", memory.sourceReference)
                .put("createdAt", memory.createdAt).put("updatedAt", memory.updatedAt);
            if (memory.expiresAt > 0) item.put("expiresAt", memory.expiresAt);
            if (memory.userEdited) item.put("userEdited", true);
            selected.put(item);
        }
        String value = new JSONObject().put("selection", "user").put("localOnly", localOnly).put("memories", selected).toString();
        if (value.length() > 64000) throw new IllegalArgumentException("Selected memory snapshot exceeds 64000 characters");
        return value;
    }

    static boolean localOnly(ExecutionPlan plan) {
        if (plan == null || plan.context.isEmpty()) return true;
        try { return new JSONObject(plan.context).optBoolean("localOnly", true); }
        catch (Exception error) { return true; }
    }

    static List<AgentStore.Memory> memories(ExecutionPlan plan) {
        ArrayList<AgentStore.Memory> result = new ArrayList<>();
        if (plan == null || plan.context.isEmpty()) return result;
        try {
            JSONObject context = new JSONObject(plan.context);
            if (!"user".equals(context.optString("selection"))) return result;
            JSONArray memories = context.getJSONArray("memories");
            for (int index = 0; index < memories.length(); index++) {
                JSONObject memory = memories.getJSONObject(index);
                Object rawExpiry = memory.has("expiresAt") ? memory.get("expiresAt") : 0;
                if (!(rawExpiry instanceof Integer) && !(rawExpiry instanceof Long)) throw new IllegalStateException("Invalid snapshot expiration");
                long expiresAt = ((Number) rawExpiry).longValue();
                if (expiresAt < 0) throw new IllegalStateException("Invalid snapshot expiration");
                Object rawEdited = memory.has("userEdited") ? memory.get("userEdited") : false;
                if (!(rawEdited instanceof Boolean)) throw new IllegalStateException("Invalid snapshot edit marker");
                result.add(new AgentStore.Memory(memory.getString("id"), memory.getString("kind"), memory.getString("content"),
                    memory.getString("source"), memory.getString("sourceReference"), memory.getLong("createdAt"), memory.getLong("updatedAt"), expiresAt, (Boolean) rawEdited));
            }
            return result;
        } catch (Exception error) { throw new IllegalStateException("Invalid selected task memories", error); }
    }

    static String fingerprint(AgentStore.Task task) throws Exception {
        return DocumentAction.sha256((task.id + "\n" + task.title + "\n" + (task.plan == null ? "" : task.plan.context))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String imported(String context) throws Exception {
        if (context.isEmpty()) return context;
        JSONObject value = new JSONObject(context);
        boolean hadLocalOnly = value.has("localOnly");
        boolean hadOrigin = value.has("originLocalOnly");
        if (!hadOrigin) value.put("originLocalOnly", value.optBoolean("localOnly", true));
        value.put("localOnly", true);
        if (value.toString().length() > 64000 && !hadOrigin) value.remove("originLocalOnly");
        // An absent policy already defaults to local-only; preserve a full snapshot without truncating it.
        if (value.toString().length() > 64000 && !hadLocalOnly) value.remove("localOnly");
        return value.toString();
    }
}
