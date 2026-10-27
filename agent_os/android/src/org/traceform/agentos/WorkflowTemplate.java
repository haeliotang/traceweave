package org.traceform.agentos;

import org.json.JSONObject;
import org.json.JSONArray;
import java.util.Collections;

/** A portable recipe, never an authorization or a previously dispatched action. */
final class WorkflowTemplate {
    static final String FORMAT = "org.traceform.agentos.workflow";
    final String name;
    final String goalTemplate;
    final boolean calendarFollowup;

    WorkflowTemplate(String name, String goalTemplate) {
        this(name, goalTemplate, false);
    }

    WorkflowTemplate(String name, String goalTemplate, boolean calendarFollowup) {
        this.name = name.trim();
        this.goalTemplate = goalTemplate.trim();
        this.calendarFollowup = calendarFollowup;
        if (this.name.isEmpty() || this.name.length() > 100 || this.goalTemplate.length() > 1800
                || !this.goalTemplate.contains("{{input}}")
                || this.goalTemplate.replace("{{input}}", "").contains("{{")
                || this.goalTemplate.replace("{{input}}", "").contains("}}"))
            throw new IllegalArgumentException("Use a name (1–100 characters) and a goal containing {{input}} only");
    }

    String encode() throws Exception {
        JSONObject value = new JSONObject().put("format", FORMAT).put("version", calendarFollowup ? 2 : 1)
            .put("type", calendarFollowup ? "markdown_review_save_calendar" : "markdown_review_save")
            .put("name", name).put("goalTemplate", goalTemplate);
        if (calendarFollowup) value.put("steps", new JSONArray().put("verified_document").put("calendar_draft"));
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
        if ((!legacy && !chained) || !(value.get("name") instanceof String) || !(value.get("goalTemplate") instanceof String))
            throw new IllegalArgumentException("Unsupported workflow recipe; permissions and file targets cannot be imported");
        return new WorkflowTemplate(value.getString("name"), value.getString("goalTemplate"), chained);
    }

    String render(String input) {
        String value = input.trim();
        String goal = goalTemplate.replace("{{input}}", value);
        if (value.isEmpty() || value.length() > 1000 || goal.length() > 2000)
            throw new IllegalArgumentException("Enter 1–1000 characters; the rendered goal must fit 2000 characters");
        return goal;
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
            if (recipe.calendarFollowup) workflow.put("steps", new JSONArray().put("verified_document").put("calendar_draft"));
            context.put("intent", "verified_document").put("workflow", workflow);
            store.savePlan(task.id, new ExecutionPlan(task.id, task.title, Collections.emptyList(), System.currentTimeMillis(), 0)
                .withContext(context.toString()));
            store.appendTaskEvent(task.id, "workflow_run_created:" + memory.id);
            database.setTransactionSuccessful();
            return store.getTask(task.id);
        } finally { database.endTransaction(); }
    }
}
