package org.traceform.agentos;

import android.app.Activity;
import android.content.Intent;
import android.content.ContentValues;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.util.Log;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ActionFlowValidationActivity extends Activity {
    private int checks;
    private static final String PASSPHRASE = "synthetic-action-validation-only";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView output = new TextView(this);
        setContentView(output);
        new Thread(() -> {
            String result;
            try (AgentStore store = new AgentStore(this)) {
                if ("VERIFY_RESTART".equals(getIntent().getAction())) verifyRestart(store);
                else if ("SEED_UI".equals(getIntent().getAction())) seedUi(store);
                else if ("VERIFY_UI".equals(getIntent().getAction())) verifyUi(store);
                else if ("VERIFY_UPGRADE".equals(getIntent().getAction())) verifyUpgrade(store);
                else validate(store);
                result = "PASS action_flow checks=" + checks + " mode=" + getIntent().getAction();
            } catch (Exception error) {
                result = "FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage();
                Log.e("AgentOsActionsValidation", result, error);
            }
            String completed = result;
            Log.i("AgentOsActionsValidation", completed);
            runOnUiThread(() -> {
                output.setText(completed);
                if (completed.startsWith("PASS") && "SEED_UI".equals(getIntent().getAction())) {
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                }
            });
        }, "action-flow-validation").start();
    }

    private void validate(AgentStore store) throws Exception {
        clear(store);
        CapabilityBroker broker = new CapabilityBroker(this, store);
        AgentExecutor executor = new AgentExecutor(store, broker);
        AgentStore.Task calendar = draft(store, executor, LocalCalendarProvider.CAPABILITY, LocalCalendarProvider.SCOPE);
        check(!AgentStore.COMPLETED.equals(calendar.status), "calendar draft must not complete a task");
        check(AgentStore.WAITING_FOR_USER.equals(calendar.status), "draft waits for explicit user confirmation");
        check(ExecutionPlan.STEP_DRAFT_READY.equals(calendar.plan.steps.get(0).status), "draft-ready checkpoint");
        check(calendar.executionStep == 0, "draft does not advance step");
        rejects(() -> store.transition(calendar.id, AgentStore.ACTIVE), "resume cannot dispatch a prepared action");
        rejects(() -> store.completeExecution(calendar.id, "SYNTHETIC false success"), "incomplete task cannot succeed");
        JSONObject payload = event();
        Intent eventIntent = ExternalAction.intent(LocalCalendarProvider.CAPABILITY, payload);
        check("vnd.android.cursor.dir/event".equals(eventIntent.getType()), "calendar intent has explicit event MIME type");
        check("SYNTHETIC chosen event".equals(eventIntent.getStringExtra(CalendarContract.Events.TITLE)), "chosen title reaches calendar intent");
        check(eventIntent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0) == payload.getLong("startTime"), "chosen time reaches calendar intent");
        rejects(() -> ExternalAction.intent(LocalCalendarProvider.CAPABILITY, ExternalAction.calendarDraft("test", 0, 0)), "missing times are not invented");
        rejects(() -> ExternalAction.calendarDraft("test", 100, 50), "invalid interval rejected");
        String draftId = new JSONObject(calendar.plan.steps.get(0).observation).getString("draftId");
        rejects(() -> store.claimExternalAction(calendar.id, 0, "stale-draft", event()), "stale preview rejected");
        String attempt = store.claimExternalAction(calendar.id, 0, draftId, payload);
        rejects(() -> store.claimExternalAction(calendar.id, 0, draftId, event()), "double tap cannot dispatch twice");
        check(store.recordExternalOutcome(calendar.id, 0, attempt, false, "activity_result_-1"), "callback accepted once");
        check(!store.recordExternalOutcome(calendar.id, 0, attempt, false, "duplicate_callback"), "duplicate callback ignored");
        AgentStore.Task unknown = store.getTask(calendar.id);
        check(AgentStore.OUTCOME_UNKNOWN.equals(unknown.status), "RESULT_OK is not verified external success");
        check(unknown.executionStep == 0, "unknown effect does not advance pipeline");
        check(MemoryConsolidator.extractItems(unknown).isEmpty(), "unknown effect creates no fact");
        rejects(() -> store.transition(calendar.id, AgentStore.ACTIVE), "unknown effect cannot replay by resume");

        AgentStore.Task cancelled = draft(store, executor, LocalCalendarProvider.CAPABILITY, LocalCalendarProvider.SCOPE);
        store.transition(cancelled.id, AgentStore.CANCELLED);
        check(ExecutionPlan.STEP_CANCELLED.equals(store.getTask(cancelled.id).plan.steps.get(0).status), "cancel before handoff persisted");
        rejects(() -> store.claimExternalAction(cancelled.id, 0,
            new JSONObject(cancelled.plan.steps.get(0).observation).getString("draftId"), event()), "cancelled preview cannot dispatch");

        AgentStore.Task afterDispatch = draft(store, executor, LocalCalendarProvider.CAPABILITY, LocalCalendarProvider.SCOPE);
        claim(store, afterDispatch);
        store.transition(afterDispatch.id, AgentStore.CANCELLED);
        check(AgentStore.OUTCOME_UNKNOWN.equals(store.getTask(afterDispatch.id).status), "stopping tracking cannot undo external effect");

        AgentStore.Task failed = draft(store, executor, LocalCalendarProvider.CAPABILITY, LocalCalendarProvider.SCOPE);
        String failedAttempt = claim(store, failed);
        store.recordExternalOutcome(failed.id, 0, failedAttempt, true, "no_handler");
        check(AgentStore.FAILED.equals(store.getTask(failed.id).status), "known launch failure is failed");
        store.transition(failed.id, AgentStore.ACTIVE);
        executor.execute(failed.id, new AtomicBoolean());
        check(ExecutionPlan.STEP_DRAFT_READY.equals(store.getTask(failed.id).plan.steps.get(0).status), "explicit retry returns to preview");
        check(!new JSONObject(store.getTask(failed.id).plan.steps.get(0).observation).has("attemptId"), "retry requires fresh dispatch claim");

        AgentStore.Task share = draft(store, executor, LocalShareProvider.CAPABILITY, LocalShareProvider.SCOPE);
        check(AgentStore.WAITING_FOR_USER.equals(share.status), "share draft cannot complete or send itself");
        JSONObject shared = new JSONObject(share.plan.steps.get(0).observation);
        check(shared.getString("content").contains(share.title), "share uses task input instead of canned success report");
        Intent shareIntent = ExternalAction.intent(LocalShareProvider.CAPABILITY, shared);
        check(Intent.ACTION_SEND.equals(shareIntent.getAction()), "share uses Android chooser contract");

        check(!new LocalNotificationProvider(this).isAvailable("synthetic", LocalNotificationProvider.SCOPE), "notification permission denial fails closed");
        AgentStore.Task preference = store.createTask("我偏好 SYNTHETIC memory confirmation");
        store.savePlan(preference.id, plan(preference, "builtin.synthesis", "builtin.summary"));
        store.transition(preference.id, AgentStore.ACTIVE);
        int before = store.listMemories().size();
        executor.execute(preference.id, new AtomicBoolean());
        check(store.listMemories().size() == before, "candidate not saved automatically");
        AgentStore.Task completed = store.getTask(preference.id);
        check(completed.executionStep == completed.plan.totalSteps(), "completion has no phantom extra step");
        check(!MemoryConsolidator.extractItems(completed).isEmpty(), "preference available for user review");
        check(store.reviewMemoryCandidates(preference.id, true) == 1, "explicit confirmation saves candidate");
        check(store.reviewMemoryCandidates(preference.id, true) == 0, "repeated confirmation is idempotent");
        check(store.listMemories().size() == before + 1, "one confirmed candidate inserted");
        check(store.listPlanningMemories().size() == 1, "confirmed suggestion available to planning");
        AgentStore.Memory legacy = store.createMemory(AgentStore.MEMORY_FACT, "SYNTHETIC legacy unreviewed inference", AgentStore.MEMORY_SOURCE_CONSOLIDATION, preference.id);
        check(store.listPlanningMemories().size() == 1, "legacy unreviewed inference excluded from planning");
        check(store.reviewLegacyMemory(legacy.id, legacy.updatedAt), "legacy inference can be explicitly confirmed");
        check(!store.reviewLegacyMemory(legacy.id, legacy.updatedAt), "legacy confirmation is idempotent");
        check(store.listPlanningMemories().size() == 2, "only reviewed legacy entry enters planning");
        AgentStore.Task rejected = store.createTask("我喜欢 SYNTHETIC rejected suggestion");
        store.savePlan(rejected.id, plan(rejected, "builtin.synthesis", "builtin.summary"));
        store.transition(rejected.id, AgentStore.ACTIVE);
        executor.execute(rejected.id, new AtomicBoolean());
        int count = store.listMemories().size();
        store.reviewMemoryCandidates(rejected.id, false);
        check(store.reviewMemoryCandidates(rejected.id, true) == 0 && store.listMemories().size() == count, "dismissed suggestions do not reappear or save");
        ExecutionPlan.Step unverified = new ExecutionPlan.Step(0, "calendar", LocalCalendarProvider.CAPABILITY, LocalCalendarProvider.SCOPE,
            "", ExecutionPlan.STEP_DONE, new JSONObject().put("status", "created").toString(), 1);
        AgentStore.Task falseFact = new AgentStore.Task(calendar.id, "我偏好 SYNTHETIC fabricated effect", AgentStore.COMPLETED, 1, 1, 1, 2, 1, "", "SYNTHETIC", new ExecutionPlan(calendar.id, "", Collections.singletonList(unverified), 1, 1));
        check(MemoryConsolidator.extractItems(falseFact).isEmpty(), "created string without readback is not a fact");

        AgentStore.Memory sentinel = store.createMemory(AgentStore.MEMORY_FACT, "SYNTHETIC-private-memory-sentinel");
        AgentStore.Task noteTask = store.createTask("SYNTHETIC demo boundary");
        broker.grant(noteTask.id, CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE);
        String note = broker.invoke(noteTask.id, CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE);
        check(!note.contains(sentinel.content), "demo note must not read private user memory");
        store.deleteMemory(sentinel.id);

        AgentStore.Task importing = draft(store, executor, LocalCalendarProvider.CAPABILITY, LocalCalendarProvider.SCOPE);
        claim(store, importing);
        byte[] archive = store.exportArchive(PASSPHRASE.toCharArray());
        check(archive[4] == 3, "data schema upgrade preserves encryption envelope version 3");
        clear(store);
        store.importArchive(new ByteArrayInputStream(archive), PASSPHRASE.toCharArray());
        check(AgentStore.OUTCOME_UNKNOWN.equals(store.getTask(importing.id).status), "imported dispatched action cannot replay");
        check("accepted".equals(store.getTask(preference.id).plan.memoryReview), "archive preserves review choice");
        check(store.reviewMemoryCandidates(preference.id, true) == 0, "archive does not resave approved suggestion");
        check(store.listMemories().size() == count, "archive preserves confirmed and legacy records");

        AgentStore.Task restart = draft(store, executor, LocalCalendarProvider.CAPABILITY, LocalCalendarProvider.SCOPE);
        claim(store, restart);
        getSharedPreferences("action_validation", MODE_PRIVATE).edit().putString("restart", restart.id)
            .putString("confirmed", preference.id).putInt("memories", store.listMemories().size()).commit();
    }

    private void verifyRestart(AgentStore store) throws Exception {
        String id = getSharedPreferences("action_validation", MODE_PRIVATE).getString("restart", "");
        check(store.recoverExternalActions() == 1, "interrupted handoff recovered exactly once");
        check(AgentStore.OUTCOME_UNKNOWN.equals(store.getTask(id).status), "process restart retains unknown effect");
        check(store.recoverExternalActions() == 0, "recovery is idempotent");
        rejects(() -> store.transition(id, AgentStore.ACTIVE), "restart cannot replay effect");
        String confirmed = getSharedPreferences("action_validation", MODE_PRIVATE).getString("confirmed", "");
        check(store.reviewMemoryCandidates(confirmed, true) == 0, "review choice survives process death");
        check(store.listMemories().size() == getSharedPreferences("action_validation", MODE_PRIVATE).getInt("memories", -1), "no duplicate memory after restart");
    }

    private void seedUi(AgentStore store) throws Exception {
        clear(store);
        AgentExecutor executor = new AgentExecutor(store, new CapabilityBroker(this, store));
        for (String name : Arrays.asList("accept", "reject")) {
            AgentStore.Task task = store.createTask("I prefer SYNTHETIC " + name + " task previews");
            store.savePlan(task.id, plan(task, "builtin.synthesis", "local_inference"));
            store.transition(task.id, AgentStore.ACTIVE);
            executor.execute(task.id, new AtomicBoolean());
            getSharedPreferences("action_validation", MODE_PRIVATE).edit().putString(name, task.id).commit();
        }
        AgentStore.Task cancel = store.createTask("SYNTHETIC cancel before handoff");
        JSONObject input = ExternalAction.calendarDraft(cancel.title, System.currentTimeMillis() + 86400000L,
            System.currentTimeMillis() + 88200000L);
        ExecutionPlan.Step action = new ExecutionPlan.Step(0, "SYNTHETIC calendar cancellation", LocalCalendarProvider.CAPABILITY,
            LocalCalendarProvider.SCOPE, input.toString(), ExecutionPlan.STEP_PENDING, "", 0);
        store.savePlan(cancel.id, new ExecutionPlan(cancel.id, cancel.title, Collections.singletonList(action), System.currentTimeMillis(), 0));
        store.transition(cancel.id, AgentStore.ACTIVE);
        executor.execute(cancel.id, new AtomicBoolean());
        check(store.listMemories().isEmpty(), "UI fixtures are unconfirmed suggestions");
    }

    private void verifyUi(AgentStore store) throws Exception {
        store.recoverExternalActions();
        String accepted = getSharedPreferences("action_validation", MODE_PRIVATE).getString("accept", "");
        String rejected = getSharedPreferences("action_validation", MODE_PRIVATE).getString("reject", "");
        check("accepted".equals(store.getTask(accepted).plan.memoryReview), "UI confirmed suggestion persisted");
        check("dismissed".equals(store.getTask(rejected).plan.memoryReview), "UI rejection persisted");
        check(store.listMemories().size() == 1, "UI saved only the approved suggestion");
        check(store.listMemories().get(0).sourceReference.equals("confirmed:" + accepted), "UI suggestion provenance retained");
        boolean handoff = false;
        boolean cancelled = false;
        for (AgentStore.Task task : store.listTasks()) {
            if ("SYNTHETIC Agent OS calendar acceptance".equals(task.title)) {
                handoff = true;
                check(AgentStore.OUTCOME_UNKNOWN.equals(task.status), "real calendar return is unknown, not succeeded");
                check(ExecutionPlan.STEP_UNKNOWN.equals(task.plan.steps.get(0).status), "real calendar step state persisted");
                JSONObject result = new JSONObject(task.plan.steps.get(0).observation);
                check(task.title.equals(result.getString("title")) && result.getLong("endTime") > result.getLong("startTime"), "real calendar preview retained chosen inputs");
                Log.i("AgentOsActionsValidation", "SYNTHETIC calendar start=" + result.getLong("startTime") + " end=" + result.getLong("endTime"));
                int launches = 0;
                for (String event : store.listAuditEvents(task.id, 100)) if (event.contains("action_launch_accepted:")) launches++;
                check(launches == 1, "calendar handoff occurred exactly once");
                check(MemoryConsolidator.extractItems(task).isEmpty(), "calendar unknown result produced no fact");
            }
            if ("SYNTHETIC cancel before handoff".equals(task.title)) {
                cancelled = true;
                check(AgentStore.CANCELLED.equals(task.status), "UI preview cancellation persisted");
                for (String event : store.listAuditEvents(task.id, 100)) check(!event.contains("action_launch_accepted:"), "cancel did not launch calendar");
            }
        }
        check(handoff && cancelled, "both real UI action paths were exercised");
    }

    private void verifyUpgrade(AgentStore store) throws Exception {
        check(MemoryArchive.encrypt(new MemoryArchive.Data(), PASSPHRASE.toCharArray())[4] == 3,
            "data schema upgrade preserves encryption envelope version 3");
        AgentStore.Task legacy = store.createTask("SYNTHETIC historical unverified calendar");
        ExecutionPlan.Step step = new ExecutionPlan.Step(0, "SYNTHETIC historical draft", LocalCalendarProvider.CAPABILITY,
            LocalCalendarProvider.SCOPE, "", ExecutionPlan.STEP_DONE,
            new JSONObject().put("status", "prepared").put("title", "SYNTHETIC preserved draft").toString(), 100);
        ExecutionPlan plan = new ExecutionPlan(legacy.id, legacy.title, Collections.singletonList(step), 100, 1);
        check(!plan.isComplete(), "historical unverified external action cannot satisfy completion");
        JSONObject payload = new JSONObject().put("title", legacy.title).put("checkpointNote", "SYNTHETIC checkpoint")
            .put("result", "SYNTHETIC old result retained").put("plan", plan.toJson());
        java.lang.reflect.Method seal = AgentStore.class.getDeclaredMethod("seal", String.class, byte[].class);
        seal.setAccessible(true);
        ContentValues row = new ContentValues();
        row.put("status", AgentStore.COMPLETED);
        row.put("execution_step", 2);
        row.put("sealed_payload", (byte[]) seal.invoke(store, legacy.id, payload.toString().getBytes("UTF-8")));
        store.getWritableDatabase().update("tasks", row, "id=?", new String[]{legacy.id});
        int memories = store.listMemories().size();
        store.getWritableDatabase().setVersion(4);
        store.close();
        try (AgentStore upgraded = new AgentStore(this)) {
            AgentStore.Task task = upgraded.getTask(legacy.id);
            check(upgraded.getReadableDatabase().getVersion() == 6, "database upgraded to version 6");
            check(AgentStore.OUTCOME_UNKNOWN.equals(task.status) && task.executionStep == 0, "historical false success normalized");
            check(ExecutionPlan.STEP_UNKNOWN.equals(task.plan.steps.get(0).status), "historical external step normalized");
            JSONObject observation = new JSONObject(task.plan.steps.get(0).observation);
            check("prepared".equals(observation.getString("previousStatus"))
                && "SYNTHETIC preserved draft".equals(observation.getString("title")), "migration preserves draft content and prior status");
            check("SYNTHETIC old result retained".equals(task.result) && "SYNTHETIC checkpoint".equals(task.checkpointNote), "migration preserves encrypted results");
            check(upgraded.listMemories().size() == memories, "migration neither deletes nor inserts memories");
            int audits = upgraded.listAuditEvents(task.id, 100).size();
            upgraded.close();
            check(upgraded.listAuditEvents(task.id, 100).size() == audits, "reopen does not duplicate migration audit");
            upgraded.deleteTask(task.id);

            MemoryArchive.Data oldArchive = new MemoryArchive.Data();
            String id = java.util.UUID.randomUUID().toString();
            oldArchive.tasks.add(new AgentStore.Task(id, "SYNTHETIC imported false success", AgentStore.COMPLETED,
                100, 100, 1, 2, 2, "SYNTHETIC checkpoint", "SYNTHETIC imported result",
                new ExecutionPlan(id, "", Collections.singletonList(step), 100, 1)));
            upgraded.importArchive(new ByteArrayInputStream(MemoryArchive.encrypt(oldArchive, PASSPHRASE.toCharArray())), PASSPHRASE.toCharArray());
            AgentStore.Task imported = upgraded.getTask(id);
            check(AgentStore.OUTCOME_UNKNOWN.equals(imported.status) && imported.executionStep == 0, "import normalizes historical external false success");
            check("SYNTHETIC imported result".equals(imported.result), "import retains historical result text");
            rejects(() -> upgraded.transition(id, AgentStore.ACTIVE), "imported historical action cannot replay");
            check(MemoryConsolidator.extractItems(imported).isEmpty(), "historical archive creates no fact");
            upgraded.deleteTask(id);
        }
    }

    private static AgentStore.Task draft(AgentStore store, AgentExecutor executor, String capability, String scope) throws Exception {
        AgentStore.Task task = store.createTask("SYNTHETIC chosen action input");
        store.savePlan(task.id, plan(task, capability, scope));
        store.transition(task.id, AgentStore.ACTIVE);
        executor.execute(task.id, new AtomicBoolean());
        return store.getTask(task.id);
    }

    private static String claim(AgentStore store, AgentStore.Task task) throws Exception {
        return store.claimExternalAction(task.id, 0, new JSONObject(task.plan.steps.get(0).observation).getString("draftId"), event());
    }

    private static JSONObject event() throws Exception {
        long start = System.currentTimeMillis() + 86400000L;
        return ExternalAction.calendarDraft("SYNTHETIC chosen event", start, start + 1800000L);
    }

    private static ExecutionPlan plan(AgentStore.Task task, String capability, String scope) {
        ExecutionPlan.Step step = new ExecutionPlan.Step(0, "SYNTHETIC step", capability, scope, "", ExecutionPlan.STEP_PENDING, "", 0);
        return new ExecutionPlan(task.id, task.title, Collections.singletonList(step), System.currentTimeMillis(), 0);
    }

    private static void clear(AgentStore store) {
        for (String table : Arrays.asList("audit", "tasks", "memories", "tombstones")) store.getWritableDatabase().delete(table, null, null);
    }

    private void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new IllegalStateException("assertion: " + message);
    }

    private interface Checked { void run() throws Exception; }
    private void rejects(Checked operation, String message) throws Exception {
        boolean rejected = false;
        try { operation.run(); } catch (IllegalStateException | IllegalArgumentException expected) { rejected = true; }
        check(rejected, message);
    }
}
