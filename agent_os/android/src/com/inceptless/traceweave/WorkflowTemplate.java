// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import org.json.JSONObject;
import org.json.JSONArray;
import java.util.Collections;

/** A portable recipe, never an authorization or a previously dispatched action. */
final class WorkflowTemplate {
    static final String FORMAT = "com.inceptless.traceweave.workflow";
    final String name;
    final String goalTemplate;
    final boolean calendarFollowup;
    final boolean shareFollowup;
    final int guardMaxBytes;

    WorkflowTemplate(String name, String goalTemplate) {
        this(name, goalTemplate, false);
    }

    WorkflowTemplate(String name, String goalTemplate, boolean calendarFollowup) {
        this(name, goalTemplate, calendarFollowup, false);
    }

    WorkflowTemplate(String name, String goalTemplate, boolean calendarFollowup, boolean shareFollowup) {
        this(name, goalTemplate, calendarFollowup, shareFollowup, 0);
    }

    WorkflowTemplate(String name, String goalTemplate, boolean calendarFollowup, boolean shareFollowup, int guardMaxBytes) {
        if (calendarFollowup && shareFollowup) throw new IllegalArgumentException("Choose one followup");
        if (guardMaxBytes < 0 || guardMaxBytes > 20000) throw new IllegalArgumentException("Guard limit is 1-20000 bytes");
        this.name = name.trim();
        this.goalTemplate = goalTemplate.trim();
        this.calendarFollowup = calendarFollowup;
        this.shareFollowup = shareFollowup;
        this.guardMaxBytes = guardMaxBytes;
        if (this.name.isEmpty() || this.name.length() > 100 || this.goalTemplate.length() > 1800
                || !this.goalTemplate.contains("{{input}}")
                || this.goalTemplate.replace("{{input}}", "").contains("{{")
                || this.goalTemplate.replace("{{input}}", "").contains("}}"))
            throw new IllegalArgumentException("Use a name (1–100 characters) and a goal containing {{input}} only");
    }

    String encode() throws Exception {
        boolean guarded = shareFollowup && guardMaxBytes > 0;
        JSONObject value = new JSONObject()
            .put("format", FORMAT)
            .put("version", guarded ? 4 : shareFollowup ? 3 : calendarFollowup ? 2 : 1)
            .put("type", guarded ? "markdown_review_save_share_guarded"
                : shareFollowup ? "markdown_review_save_share" : calendarFollowup ? "markdown_review_save_calendar" : "markdown_review_save")
            .put("name", name).put("goalTemplate", goalTemplate);
        if (calendarFollowup || shareFollowup) value.put("steps", new JSONArray().put("verified_document").put(shareFollowup ? "share_draft" : "calendar_draft"));
        if (guarded) value.put("guard", new JSONObject().put("type", "max_bytes").put("value", guardMaxBytes));
        return value.toString();
    }

    static boolean isTemplate(AgentStore.Memory memory) {
        if (!AgentStore.MEMORY_WORKFLOW.equals(memory.kind)) return false;
        try { return FORMAT.equals(new JSONObject(memory.content).optString("format")); }
        catch (Exception error) { return false; }
    }

    static WorkflowTemplate decode(AgentStore.Memory memory) throws Exception {
        if (!isTemplate(memory)) throw new IllegalArgumentException("This memory is a note, not an executable workflow");
        JSONObject value = new JSONObject(memory.content);
        boolean legacy = value.length() == 5 && Integer.valueOf(1).equals(value.opt("version"))
            && "markdown_review_save".equals(value.optString("type"));
        JSONArray steps = value.optJSONArray("steps");
        boolean chained = value.length() == 6 && Integer.valueOf(2).equals(value.opt("version"))
            && "markdown_review_save_calendar".equals(value.optString("type")) && steps != null
            && steps.length() == 2 && "verified_document".equals(steps.optString(0)) && "calendar_draft".equals(steps.optString(1));
        boolean guarded = value.length() == 7 && Integer.valueOf(4).equals(value.opt("version"))
            && "markdown_review_save_share_guarded".equals(value.optString("type")) && steps != null
            && steps.length() == 2 && "verified_document".equals(steps.optString(0)) && "share_draft".equals(steps.optString(1))
            && value.optJSONObject("guard") != null && "max_bytes".equals(value.optJSONObject("guard").optString("type"));
        int guardMaxBytes = 0;
        if (guarded) {
            Object raw = value.getJSONObject("guard").opt("value");
            if (!(raw instanceof Integer) && !(raw instanceof Long)) throw new IllegalArgumentException("Unsupported workflow guard");
            guardMaxBytes = ((Number) raw).intValue();
            if (guardMaxBytes < 1 || guardMaxBytes > 20000) throw new IllegalArgumentException("Unsupported workflow guard");
        }
        boolean shared = value.length() == 6 && Integer.valueOf(3).equals(value.opt("version"))
            && "markdown_review_save_share".equals(value.optString("type")) && steps != null
            && steps.length() == 2 && "verified_document".equals(steps.optString(0)) && "share_draft".equals(steps.optString(1));
        if ((!legacy && !chained && !shared && !guarded) || !(value.get("name") instanceof String) || !(value.get("goalTemplate") instanceof String))
            throw new IllegalArgumentException("Unsupported workflow recipe; permissions and file targets cannot be imported");
        return new WorkflowTemplate(value.getString("name"), value.getString("goalTemplate"), chained, shared || guarded, guardMaxBytes);
    }

    String render(String input) {
        String value = input.trim();
        String goal = goalTemplate.replace("{{input}}", value);
        if (value.isEmpty() || value.length() > 1000 || goal.length() > 2000)
            throw new IllegalArgumentException("Enter 1–1000 characters; the rendered goal must fit 2000 characters");
        return goal;
    }

    /** Whitelisted guard: only these conditions may stop a run. Unknown types are rejected. */
    static boolean guardAllows(JSONObject guard, int contentBytes) {
        if (guard == null || guard.length() == 0) return true;
        if (!"max_bytes".equals(guard.optString("type"))) throw new IllegalArgumentException("Unsupported workflow guard");
        int limit = guard.optInt("value", 0);
        if (limit < 1 || limit > 20000) throw new IllegalArgumentException("Unsupported workflow guard");
        return contentBytes <= limit;
    }

    static AgentStore.Task createRun(AgentStore store, String memoryId, String input) throws Exception {
        android.database.sqlite.SQLiteDatabase database = store.getWritableDatabase();
        database.beginTransaction();
        try {
            AgentStore.Memory memory = null;
            for (AgentStore.Memory item : store.listMemories()) if (memoryId.equals(item.id)) memory = item;
            if (memory == null) throw new IllegalArgumentException("Workflow no longer exists");
            if (memory.isExpired(System.currentTimeMillis())) throw new IllegalStateException("Workflow expired; renew it in the memory vault before running");
            WorkflowTemplate recipe = decode(memory);
            AgentStore.Task task = store.createTask(recipe.render(input));
            JSONObject context = new JSONObject(TaskContext.selected(Collections.emptyList(), true));
            JSONObject workflow = new JSONObject().put("id", memory.id)
                .put("updatedAt", memory.updatedAt).put("name", recipe.name).put("goalTemplate", recipe.goalTemplate)
                .put("input", input.trim());
            if (recipe.calendarFollowup || recipe.shareFollowup) workflow.put("steps", new JSONArray().put("verified_document").put(recipe.shareFollowup ? "share_draft" : "calendar_draft"));
            if (recipe.guardMaxBytes > 0) workflow.put("guard", new JSONObject().put("type", "max_bytes").put("value", recipe.guardMaxBytes));
            context.put("intent", "verified_document").put("workflow", workflow);
            store.savePlan(task.id, new ExecutionPlan(task.id, task.title, Collections.emptyList(), System.currentTimeMillis(), 0)
                .withContext(context.toString()));
            store.appendTaskEvent(task.id, "workflow_run_created:" + memory.id);
            database.setTransactionSuccessful();
            return store.getTask(task.id);
        } finally { database.endTransaction(); }
    }
}
