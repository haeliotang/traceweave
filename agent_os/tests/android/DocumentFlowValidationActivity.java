package org.traceform.agentos;

import android.app.Activity;
import android.os.Bundle;
import android.net.Uri;
import android.util.Log;
import android.widget.TextView;

public final class DocumentFlowValidationActivity extends Activity {
    private int checks;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView output = new TextView(this);
        setContentView(output);
        new Thread(() -> {
            String result;
            try (AgentStore store = new AgentStore(this)) {
                ExecutionPlan.Step file = new ExecutionPlan.Step(0, "SYNTHETIC document", "system.document.write_verified",
                    "user_selected_document", "", ExecutionPlan.STEP_PENDING, "", 0);
                check(file.isExternalAction(), "document writes require external-action confirmation");
                check(!new ExecutionPlan("synthetic", "", java.util.Collections.singletonList(file), 1, 0).isComplete(),
                    "unverified document cannot complete a plan");
                if ("BASELINE_CLOUD".equals(getIntent().getAction())) baselineCloud(store);
                else if ("VERIFY_PRIVACY".equals(getIntent().getAction())) verifyPrivacy(store);
                else if ("VERIFY_MEMORY_REVIEW".equals(getIntent().getAction())) verifyMemoryReview(store);
                else if ("SEED_DOCUMENT_UI".equals(getIntent().getAction())) seedDocumentUi(store);
                else if ("EXPORT_MIGRATION".equals(getIntent().getAction())) exportMigration(store);
                else if ("IMPORT_MIGRATION".equals(getIntent().getAction()) || "IMPORT_MIGRATION_WITH_CLOUD_FIXTURE".equals(getIntent().getAction())) importMigration(store);
                else if ("REVIEW_ARCHIVE_CONFLICTS".equals(getIntent().getAction())) reviewArchiveConflicts(store);
                else if ("SEED_ARCHIVE_REVIEW_UI".equals(getIntent().getAction())) seedArchiveReviewUi(store);
                else if ("VERIFY_ARCHIVE_REVIEW_UI".equals(getIntent().getAction())) verifyArchiveReviewUi(store);
                else if ("VERIFY_DOCUMENT_UI".equals(getIntent().getAction())) verifyDocumentUi(store);
                else if ("DUPLICATE_RECEIPT".equals(getIntent().getAction())) duplicateReceipt(store);
                else if ("SEED_CLOUD_UI".equals(getIntent().getAction())) {
                    check(new CloudModelConfig(true, "http://127.0.0.1:58999/chat/completions", "", "synthetic-fixture", 15000, 0.1).save(this), "synthetic-only UI cloud config saved");
                }
                else if ("VERIFY_CLOUD_UI".equals(getIntent().getAction())) verifyCloudUi(store);
                else if ("VERIFY_IMPORTED_READ_ONLY".equals(getIntent().getAction())) verifyImportedReadOnly(store);
                else if ("VERIFY_REVOKED_READ".equals(getIntent().getAction())) verifyRevokedRead(store);
                else if ("VERIFY_REVIEW_UI".equals(getIntent().getAction())) verifyReviewUi(store);
                else if ("INVALID_DRAFT".equals(getIntent().getAction())) invalidDraft(store);
                else if ("MEMORY_BOUNDARY".equals(getIntent().getAction())) memoryBoundary(store);
                else if ("SNAPSHOT_BOUNDARY".equals(getIntent().getAction())) snapshotBoundary(store);
                else if ("MODEL_DOCUMENT".equals(getIntent().getAction())) modelDocument(store);
                else if ("MODEL_DOCUMENT_FAILURES".equals(getIntent().getAction())) modelDocumentFailures(store);
                else if ("MODEL_CANCEL_RACE".equals(getIntent().getAction())) modelCancelRace(store);
                else if ("SEED_REVIEW_UI".equals(getIntent().getAction())) {
                    AgentStore.Task task = reviewableTask(store);
                    getSharedPreferences("document_validation", 0).edit().putString("ui_review", task.id).commit();
                }
                else if ("OPEN_IMPORTED_DOCUMENT".equals(getIntent().getAction())) {
                    String id = getSharedPreferences("document_validation", 0).getString("migratedUiFile", "");
                    check(!id.isEmpty() && store.getTask(id) != null, "migrated UI file fixture is present");
                }
                else if ("OPEN_REVIEW_UI".equals(getIntent().getAction())) {
                    check(store.getTask(getSharedPreferences("document_validation", 0).getString("ui_review", "")) != null, "UI review fixture exists");
                }
                else if ("OPEN_SOURCE_DOCUMENT".equals(getIntent().getAction())) {
                    for (AgentStore.Task task : store.listTasks()) if ("SYNTHETIC verified Markdown acceptance".equals(task.title))
                        getSharedPreferences("document_validation", 0).edit().putString("ui_document", task.id).commit();
                    check(!getSharedPreferences("document_validation", 0).getString("ui_document", "").isEmpty(), "source UI document exists");
                }
                else if ("VERIFY_DOCUMENT_RESTART".equals(getIntent().getAction())) verifyRestart(store);
                else if ("STOP_TRACKING".equals(getIntent().getAction())) stopTracking(store);
                else if ("ARM_DOCUMENT_CRASH".equals(getIntent().getAction())) armCrash(store);
                else validateDocuments(store);
                result = "PASS document_flow checks=" + checks + " mode=" + getIntent().getAction();
            } catch (Exception error) {
                result = "FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage();
                Log.e("AgentOsDocumentsValidation", result, error);
            }
            String done = result;
            Log.i("AgentOsDocumentsValidation", done);
            runOnUiThread(() -> {
                output.setText(done);
                if (done.startsWith("PASS") && "OPEN_IMPORTED_DOCUMENT".equals(getIntent().getAction())) {
                    startActivity(new android.content.Intent(this, DocumentTaskActivity.class).putExtra(DocumentTaskActivity.EXTRA_TASK_ID,
                        getSharedPreferences("document_validation", 0).getString("migratedUiFile", "")));
                    finish();
                }
                if (done.startsWith("PASS") && "OPEN_REVIEW_UI".equals(getIntent().getAction())) {
                    startActivity(new android.content.Intent(this, MemoryReviewActivity.class).putExtra(MemoryReviewActivity.EXTRA_TASK_ID,
                        getSharedPreferences("document_validation", 0).getString("ui_review", "")));
                    finish();
                }
                if (done.startsWith("PASS") && "OPEN_SOURCE_DOCUMENT".equals(getIntent().getAction())) {
                    startActivity(new android.content.Intent(this, DocumentTaskActivity.class).putExtra(DocumentTaskActivity.EXTRA_TASK_ID,
                        getSharedPreferences("document_validation", 0).getString("ui_document", "")));
                    finish();
                }
            });
        }, "document-flow-validation").start();
    }

    private void check(boolean value, String message) {
        checks++;
        if (!value) throw new IllegalStateException("assertion: " + message);
    }

    private AgentStore.Task draft(AgentStore store) throws Exception {
        return store.createDocumentTask("SYNTHETIC verified document task", "# SYNTHETIC approved Markdown\n\nOnly selected content.\n",
            new org.json.JSONArray());
    }

    private void duplicateReceipt(AgentStore store) throws Exception {
        AgentStore.Task task = draft(store);
        Uri uri = target("duplicate_receipt");
        claim(store, task, uri);
        DocumentAction.writeOnce(getContentResolver(), store, task.id);
        org.json.JSONObject receipt = new org.json.JSONObject(store.getTask(task.id).plan.steps.get(0).observation);
        int audits = store.listAuditEvents(task.id, 100).size();
        store.completeDocumentVerification(task.id, receipt.getString("attemptId"), receipt.getString("verificationId"), uri.toString(), receipt.getString("sha256"), receipt.getInt("byteCount"));
        check(AgentStore.COMPLETED.equals(store.getTask(task.id).status), "duplicate receipt preserves verified outcome");
        check(store.listAuditEvents(task.id, 100).size() == audits, "duplicate receipt does not duplicate success audit");
        store.selectDocumentForVerification(task.id, uri.toString());
        String oldRead = new org.json.JSONObject(store.getTask(task.id).plan.steps.get(0).observation).getString("verificationId");
        store.selectDocumentForVerification(task.id, uri.toString());
        DocumentAction.verify(getContentResolver(), store, task.id, receipt.getString("attemptId"), uri);
        store.documentOutcomeUnknown(task.id, receipt.getString("attemptId"), oldRead, "SYNTHETIC late read failure");
        check(AgentStore.COMPLETED.equals(store.getTask(task.id).status), "stale read failure cannot overwrite a newer verification");
    }

    private void invalidDraft(AgentStore store) throws Exception {
        int before = store.listTasks().size();
        StringBuilder oversized = new StringBuilder();
        for (int index = 0; index < 13000; index++) oversized.append('x');
        rejects(() -> store.createDocumentTask("SYNTHETIC oversized sources", "# SYNTHETIC content", new org.json.JSONArray().put(oversized.toString())),
            "oversized draft rejected");
        check(store.listTasks().size() == before, "rejected draft leaves no orphan task or active job");
    }

    private void clearFixture(AgentStore store) {
        for (String table : java.util.Arrays.asList("audit", "tasks", "memories", "tombstones")) store.getWritableDatabase().delete(table, null, null);
    }

    private void memoryBoundary(AgentStore store) throws Exception {
        clearFixture(store);
        AgentStore.Task task = reviewableTask(store);
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 10000; index++) text.append('"');
        check(store.reviewMemoryCandidate(task.id, 0, true, text.toString()) == 1, "first maximum-length edited memory accepted");
        rejects(() -> store.reviewMemoryCandidate(task.id, 1, true, text.toString()), "oversized combined review journal rejected atomically");
        check(store.getTask(task.id) != null && store.listMemories().size() == 1, "rejected review leaves task readable and no extra memory");
        store.reviewMemoryCandidate(task.id, 1, false, "");
        check("accepted".equals(store.getTask(task.id).plan.memoryReview), "remaining candidate can still be rejected after size failure");
    }

    private void snapshotBoundary(AgentStore store) throws Exception {
        clearFixture(store);
        org.json.JSONObject context = new org.json.JSONObject(TaskContext.selected(java.util.Collections.emptyList(), false));
        context.put("padding", "");
        StringBuilder padding = new StringBuilder();
        for (int index = context.toString().length(); index < 64000; index++) padding.append('x');
        context.put("padding", padding.toString());
        check(context.toString().length() == 64000, "maximum supported snapshot fixture");
        AgentStore.Task task = store.createTask("SYNTHETIC maximum selected snapshot");
        store.savePlan(task.id, new ExecutionPlan(task.id, task.title, java.util.Collections.emptyList(), 1, 0).withContext(context.toString()));
        byte[] archive = store.exportArchive(ARCHIVE_PASSWORD.toCharArray());
        clearFixture(store);
        store.importArchive(new java.io.ByteArrayInputStream(archive), ARCHIVE_PASSWORD.toCharArray());
        AgentStore.Task imported = store.getTask(task.id);
        check(imported != null && TaskContext.localOnly(imported.plan), "maximum snapshot imports readably with local-only policy");
        check(padding.toString().equals(new org.json.JSONObject(imported.plan.context).getString("padding")), "maximum snapshot import preserves content without truncation");
    }

    private org.json.JSONObject proposedDocument() throws Exception {
        return new org.json.JSONObject().put("capability", DocumentAction.CAPABILITY).put("scope", DocumentAction.SCOPE)
            .put("markdown", "# SYNTHETIC model proposal\n\n- Review selected information.\n");
    }

    private String envelope(org.json.JSONObject value) throws Exception {
        return new org.json.JSONObject().put("choices", new org.json.JSONArray().put(new org.json.JSONObject()
            .put("finish_reason", "stop").put("message", new org.json.JSONObject().put("content", value.toString())))).toString();
    }

    private void modelDocument(AgentStore store) throws Exception {
        clearFixture(store);
        getSharedPreferences("document_fixture", 0).edit().clear().commit();
        AgentStore.Memory memory = store.createMemory(AgentStore.MEMORY_PREFERENCE, "SYNTHETIC selected document context");
        AgentStore.Memory excluded = store.createMemory(AgentStore.MEMORY_FACT, "SYNTHETIC not-selected-document-sentinel");
        try (CloudCapture server = new CloudCapture()) {
            server.responseContent = proposedDocument().toString();
            CloudModelClient client = new CloudModelClient();
            CloudModelConfig config = new CloudModelConfig(true, server.endpoint(), "", "synthetic-document", 15000, 0.1);
            AgentStore.Task task = selectedTask(store, java.util.Collections.singletonList(memory), false);
            CloudRequest request = client.prepareDocument(task, config);
            CloudRequest bounded = client.prepareDocument(task, config, 256);
            check(new org.json.JSONObject(bounded.body).getInt("max_tokens") == 256
                && !bounded.sha256.equals(request.sha256), "output budget is part of the exact approved request");
            rejects(() -> client.prepareDocument(task, config, 999), "unsupported token limit rejected");
            check(!CloudFailure.describe(new IllegalStateException("secret endpoint api-key"), true).contains("secret"), "failure display does not expose exception details");
            check(CloudFailure.describe(new IllegalStateException("Cloud model endpoint returned HTTP 429"), true).contains("quota"), "quota errors are classified locally");
            check(CloudFailure.describe(new java.net.SocketTimeoutException(), true).contains("timed out"), "timeout describes uncertain provider outcome");
            check(request.documentDraft && request.body.contains(memory.content) && !request.body.contains(excluded.content), "document preview includes only selected context");
            rejects(() -> client.requestApprovedDocument(request, store), "document preview cannot send without approval");
            check(server.count.get() == 0 && writes() == 0, "preview has no network or file effect");
            request.approveOnce();
            rejects(() -> client.requestApprovedPlan(request, store), "document approval cannot authorize general planning");
            AgentStore.Task draft = client.requestApprovedDocument(request, store);
            org.json.JSONObject value = new org.json.JSONObject(draft.plan.steps.get(0).observation);
            check(draft.id.equals(task.id) && AgentStore.WAITING_FOR_USER.equals(draft.status), "model output waits for separate file confirmation on same task");
            check("model".equals(value.getString("draftOrigin")) && request.sha256.equals(value.getString("requestSha256")), "proposal provenance binds exact approved request");
            check(value.getJSONArray("memorySources").getJSONObject(0).getString("id").equals(memory.id), "draft retains selected source identity");
            check(!value.has("uri") && writes() == 0 && !draft.plan.isComplete(), "model grants no file access and cannot claim completion");
            check(server.count.get() == 1 && server.body.equals(request.body), "exact preview sent once");
            rejects(() -> client.requestApprovedDocument(request, store), "document approval cannot replay");
            rejects(() -> DocumentAction.writeOnce(getContentResolver(), store, draft.id), "model proposal is not file authorization");
            String edited = "# SYNTHETIC user edited model draft\n\nApproved after review.\n";
            value.put("content", edited);
            String attempt = store.claimExternalAction(task.id, 0, value.getString("draftId"), value);
            Uri uri = target("model_verified");
            store.bindDocumentTarget(task.id, attempt, uri.toString());
            DocumentAction.writeOnce(getContentResolver(), store, task.id);
            AgentStore.Task done = store.getTask(task.id);
            org.json.JSONObject receipt = new org.json.JSONObject(done.plan.steps.get(0).observation);
            check(DocumentAction.locallyVerified(done) && done.plan.isComplete(), "edited model task completes only after readback");
            check(DocumentAction.sha256(edited.getBytes("UTF-8")).equals(receipt.getString("sha256")), "receipt verifies the user's edited text");
            check(writes() == 1 && server.count.get() == 1, "one file write and no hidden follow-up cloud call");
            org.json.JSONObject bad = proposedDocument().put("uri", "content://unapproved/file");
            rejects(() -> CloudModelClient.parseDocumentResponse(envelope(bad)), "model-selected URI rejected");
            rejects(() -> CloudModelClient.parseDocumentResponse(envelope(proposedDocument().put("scope", "all_files"))), "invented scope rejected");
            rejects(() -> CloudModelClient.parseDocumentResponse(envelope(proposedDocument().put("capability", "system.shell"))), "invented execution capability rejected");
            rejects(() -> CloudModelClient.parseDocumentResponse(envelope(proposedDocument().put("markdown", 12))), "non-text content rejected");
            rejects(() -> CloudModelClient.parseDocumentResponse(envelope(proposedDocument().put("markdown", ""))), "empty model content rejected");
            StringBuilder large = new StringBuilder(); for (int i = 0; i < 8001; i++) large.append('x');
            rejects(() -> CloudModelClient.parseDocumentResponse(envelope(proposedDocument().put("markdown", large.toString()))), "oversized model content rejected");
            org.json.JSONObject truncated = new org.json.JSONObject(envelope(proposedDocument()));
            truncated.getJSONArray("choices").getJSONObject(0).put("finish_reason", "length");
            rejects(() -> CloudModelClient.parseDocumentResponse(truncated.toString()), "truncated completion rejected");
            org.json.JSONObject tools = new org.json.JSONObject(envelope(proposedDocument()));
            tools.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("tool_calls", new org.json.JSONArray());
            rejects(() -> CloudModelClient.parseDocumentResponse(tools.toString()), "tool execution proposal rejected");
        }
    }

    private void modelDocumentFailures(AgentStore store) throws Exception {
        clearFixture(store);
        getSharedPreferences("document_fixture", 0).edit().clear().commit();
        try (CloudCapture server = new CloudCapture()) {
            CloudModelClient client = new CloudModelClient();
            CloudModelConfig config = new CloudModelConfig(true, server.endpoint(), "", "synthetic-document", 15000, 0.1);
            AgentStore.Task invalid = selectedTask(store, java.util.Collections.emptyList(), false);
            CloudRequest request = client.prepareDocument(invalid, config); request.approveOnce();
            server.responseContent = proposedDocument().put("uri", "content://unapproved/file").toString();
            rejects(() -> client.requestApprovedDocument(request, store), "illegal wire response rejected");
            check(AgentStore.QUEUED.equals(store.getTask(invalid.id).status) && writes() == 0, "illegal response leaves task retryable without file effects");
            rejects(() -> client.requestApprovedDocument(request, store), "failed request cannot silently retry");
            server.responseContent = proposedDocument().toString(); server.delayMillis = 700;
            AgentStore.Task cancelled = selectedTask(store, java.util.Collections.emptyList(), false);
            CloudRequest late = client.prepareDocument(cancelled, config); late.approveOnce();
            int before = server.count.get();
            java.util.concurrent.atomic.AtomicReference<Exception> failure = new java.util.concurrent.atomic.AtomicReference<>();
            Thread worker = new Thread(() -> { try { client.requestApprovedDocument(late, store); } catch (Exception error) { failure.set(error); } });
            worker.start();
            long deadline = System.currentTimeMillis() + 5000;
            while (server.count.get() == before && System.currentTimeMillis() < deadline) Thread.sleep(10);
            check(server.count.get() == before + 1, "cancellation fixture received one request");
            store.transition(cancelled.id, AgentStore.CANCELLED); late.revoke(); worker.join(5000);
            check(!worker.isAlive() && failure.get() instanceof SecurityException, "late response rejected after cancellation");
            check(AgentStore.CANCELLED.equals(store.getTask(cancelled.id).status) && writes() == 0, "cancelled task remains terminal and cannot write");
            AgentStore.Task stale = selectedTask(store, java.util.Collections.emptyList(), false);
            String fingerprint = TaskContext.fingerprint(stale);
            store.savePlan(stale.id, stale.plan.withContext(TaskContext.selected(java.util.Collections.emptyList(), true)));
            rejects(() -> store.prepareReviewedDocument(stale.id, "# SYNTHETIC stale", fingerprint, ""), "changed context rejects stale model result atomically");
            server.responseStatus = 503; server.delayMillis = 0;
            AgentStore.Task unavailable = selectedTask(store, java.util.Collections.emptyList(), false);
            CloudRequest error = client.prepareDocument(unavailable, config); error.approveOnce();
            rejects(() -> client.requestApprovedDocument(error, store), "HTTP service failure has no draft");
            check(AgentStore.QUEUED.equals(store.getTask(unavailable.id).status), "service failure remains recoverable");
            server.responseStatus = 200; server.delayMillis = 17000;
            AgentStore.Task timeout = selectedTask(store, java.util.Collections.emptyList(), false);
            CloudRequest slow = client.prepareDocument(timeout, config); slow.approveOnce();
            rejects(() -> client.requestApprovedDocument(slow, store), "read timeout has no draft");
            check(AgentStore.QUEUED.equals(store.getTask(timeout.id).status) && writes() == 0, "timeout cannot report completion or write");
        }
        AgentStore.Task offline = selectedTask(store, java.util.Collections.emptyList(), false);
        java.net.ServerSocket closed = new java.net.ServerSocket(0); int port = closed.getLocalPort(); closed.close();
        CloudModelConfig disconnected = new CloudModelConfig(true, "http://127.0.0.1:" + port, "", "synthetic", 15000, 0.1);
        CloudModelClient client = new CloudModelClient(); CloudRequest request = client.prepareDocument(offline, disconnected); request.approveOnce();
        rejects(() -> client.requestApprovedDocument(request, store), "unreachable endpoint cannot create document");
        check(AgentStore.QUEUED.equals(store.getTask(offline.id).status) && writes() == 0, "unreachable endpoint remains local and unfinished");
    }

    private void modelCancelRace(AgentStore store) throws Exception {
        clearFixture(store);
        getSharedPreferences("document_fixture", 0).edit().clear().commit();
        try (CloudCapture server = new CloudCapture()) {
            server.responseContent = proposedDocument().toString();
            CloudModelConfig config = new CloudModelConfig(true, server.endpoint(), "", "synthetic-document", 15000, 0.1);
            AgentStore.Task task = selectedTask(store, java.util.Collections.emptyList(), false);
            CloudModelClient client = new CloudModelClient();
            CloudRequest request = client.prepareDocument(task, config); request.approveOnce();
            AgentStore.Task draft = client.requestApprovedDocument(request, store);
            check(AgentStore.WAITING_FOR_USER.equals(draft.status), "response committed before cancel tap fixture");
            TaskReviewActivity.cancelRequest(store, request);
            check(AgentStore.CANCELLED.equals(store.getTask(task.id).status), "cancel tap also cancels a just-committed model draft");
            rejects(() -> claim(store, draft, target("cancel_race")), "cancelled model draft cannot launch a file write");
            check(writes() == 0 && server.count.get() == 1, "cancel race has no file write or retry");
            AgentStore.Task changed = selectedTask(store, java.util.Collections.emptyList(), false);
            CloudRequest stale = client.prepareDocument(changed, config);
            store.savePlan(changed.id, changed.plan.withContext(TaskContext.selected(java.util.Collections.emptyList(), true)));
            TaskReviewActivity.cancelRequest(store, stale);
            check(AgentStore.QUEUED.equals(store.getTask(changed.id).status), "stale preview cannot cancel a newly changed task context");
        }
    }

    private Uri target(String id) { return Uri.parse("content://" + getPackageName() + ".documents.fixture/" + id); }

    private String claim(AgentStore store, AgentStore.Task task, Uri uri) throws Exception {
        org.json.JSONObject value = new org.json.JSONObject(task.plan.steps.get(0).observation);
        String attempt = store.claimExternalAction(task.id, 0, value.getString("draftId"), value);
        store.bindDocumentTarget(task.id, attempt, uri.toString());
        return attempt;
    }

    private void stopTracking(AgentStore store) throws Exception {
        AgentStore.Task task = draft(store);
        claim(store, task, target("stopped"));
        org.json.JSONObject writing = store.claimDocumentWrite(task.id);
        store.transition(task.id, AgentStore.CANCELLED);
        rejects(() -> store.completeDocumentVerification(task.id, writing.getString("attemptId"), writing.getString("verificationId"), writing.getString("uri"),
            writing.getString("sha256"), writing.getString("content").getBytes("UTF-8").length),
            "stopping tracking cannot be overwritten by late write completion");
    }

    private void validateDocuments(AgentStore store) throws Exception {
        getSharedPreferences("document_fixture", 0).edit().clear().commit();
        AgentStore.Task task = draft(store);
        check(AgentStore.WAITING_FOR_USER.equals(task.status), "document draft waits for confirmation");
        rejects(() -> DocumentAction.writeOnce(getContentResolver(), store, task.id), "unconfirmed write rejected");
        org.json.JSONObject payload = new org.json.JSONObject(task.plan.steps.get(0).observation);
        android.content.Intent intent = ExternalAction.intent(DocumentAction.CAPABILITY, payload);
        check(android.content.Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction()), "system file picker used");
        Uri uri = target("verified");
        String attempt = claim(store, task, uri);
        rejects(() -> store.bindDocumentTarget(task.id, attempt, target("stale").toString()), "duplicate file-selection callback rejected");
        DocumentAction.writeOnce(getContentResolver(), store, task.id);
        AgentStore.Task completed = store.getTask(task.id);
        check(AgentStore.COMPLETED.equals(completed.status) && completed.executionStep == 1, "readback completes document task");
        check(completed.plan.isComplete(), "verified plan is complete");
        org.json.JSONObject receipt = new org.json.JSONObject(completed.plan.steps.get(0).observation);
        check(DocumentAction.hasReceipt(receipt) && receipt.getString("attemptId").equals(attempt)
            && receipt.getString("taskId").equals(task.id), "receipt binds task and attempt");
        int writes = writes();
        rejects(() -> DocumentAction.writeOnce(getContentResolver(), store, task.id), "completed write cannot repeat");
        check(writes() == writes, "duplicate write rejected before opening output");
        store.selectDocumentForVerification(task.id, uri.toString());
        DocumentAction.verify(getContentResolver(), store, task.id, attempt, uri);
        check(writes() == writes && AgentStore.COMPLETED.equals(store.getTask(task.id).status), "explicit recheck is read-only");
        try (java.io.OutputStream output = getContentResolver().openOutputStream(uri, "wt")) { output.write("SYNTHETIC changed".getBytes("UTF-8")); }
        store.selectDocumentForVerification(task.id, uri.toString());
        rejects(() -> DocumentAction.verify(getContentResolver(), store, task.id, attempt, uri), "changed file cannot verify");
        check(AgentStore.OUTCOME_UNKNOWN.equals(store.getTask(task.id).status), "changed file leaves unknown outcome");

        AgentStore.Task denied = draft(store);
        String deniedAttempt = claim(store, denied, target("denied"));
        getSharedPreferences("document_fixture", 0).edit().putBoolean("deny", true).commit();
        rejects(() -> DocumentAction.writeOnce(getContentResolver(), store, denied.id), "provider access revocation rejected");
        check(AgentStore.OUTCOME_UNKNOWN.equals(store.getTask(denied.id).status), "revocation does not become success");
        getSharedPreferences("document_fixture", 0).edit().putBoolean("deny", false).commit();

        AgentStore.Task cancelled = draft(store);
        org.json.JSONObject choice = new org.json.JSONObject(cancelled.plan.steps.get(0).observation);
        String pick = store.claimExternalAction(cancelled.id, 0, choice.getString("draftId"), choice);
        store.cancelDocumentPicker(cancelled.id, pick);
        check(AgentStore.CANCELLED.equals(store.getTask(cancelled.id).status), "file-picker cancellation is known");
        rejects(() -> store.bindDocumentTarget(cancelled.id, pick, target("cancelled").toString()), "cancelled selection cannot dispatch");

        AgentStore.Task interrupted = draft(store);
        Uri recovery = target("restart");
        String interruptedAttempt = claim(store, interrupted, recovery);
        DocumentAction.afterWriteForValidation = () -> { throw new IllegalStateException("SYNTHETIC process interruption after write"); };
        try { rejects(() -> DocumentAction.writeOnce(getContentResolver(), store, interrupted.id), "interruption after write recorded"); }
        finally { DocumentAction.afterWriteForValidation = null; }
        check(AgentStore.OUTCOME_UNKNOWN.equals(store.getTask(interrupted.id).status), "uncommitted receipt remains unknown");
        getSharedPreferences("document_validation", 0).edit().putString("restart", interrupted.id)
            .putString("attempt", interruptedAttempt).putString("uri", recovery.toString()).putInt("writes", writes()).commit();
    }

    private void verifyRestart(AgentStore store) throws Exception {
        store.recoverExternalActions();
        android.content.SharedPreferences prefs = getSharedPreferences("document_validation", 0);
        String id = prefs.getString("restart", "");
        check(AgentStore.OUTCOME_UNKNOWN.equals(store.getTask(id).status), "unknown receipt survives actual process stop");
        rejects(() -> DocumentAction.writeOnce(getContentResolver(), store, id), "restart cannot write again");
        store.selectDocumentForVerification(id, prefs.getString("uri", ""));
        DocumentAction.verify(getContentResolver(), store, id, prefs.getString("attempt", ""), Uri.parse(prefs.getString("uri", "")));
        check(AgentStore.COMPLETED.equals(store.getTask(id).status), "read-only recovery completes existing file");
        check(writes() == prefs.getInt("writes", -1), "recovery performed no output open");
        getContentResolver().delete(Uri.parse(prefs.getString("uri", "")), null, null);
        store.selectDocumentForVerification(id, prefs.getString("uri", ""));
        rejects(() -> DocumentAction.verify(getContentResolver(), store, id, prefs.getString("attempt", ""), Uri.parse(prefs.getString("uri", ""))),
            "deleted file cannot verify");
        check(AgentStore.OUTCOME_UNKNOWN.equals(store.getTask(id).status), "deleted file is not still locally verified");
    }

    private void armCrash(AgentStore store) throws Exception {
        AgentStore.Task task = draft(store);
        Uri uri = target("actual_process_crash");
        String attempt = claim(store, task, uri);
        getSharedPreferences("document_validation", 0).edit().putString("restart", task.id).putString("attempt", attempt)
            .putString("uri", uri.toString()).putInt("writes", writes() + 1).commit();
        DocumentAction.afterWriteForValidation = () -> android.os.Process.killProcess(android.os.Process.myPid());
        DocumentAction.writeOnce(getContentResolver(), store, task.id);
        throw new IllegalStateException("Crash hook did not terminate the process");
    }

    private int writes() { return getSharedPreferences("document_fixture", 0).getInt("writes", 0); }

    private void baselineCloud(AgentStore store) throws Exception {
        try (CloudCapture server = new CloudCapture()) {
            CloudModelConfig config = new CloudModelConfig(true, server.endpoint(), "", "synthetic-fixture", 15000, 0.1);
            AgentStore.Task task = store.createTask("SYNTHETIC no implicit cloud request");
            new HybridReasoningEngine(this, store, config).plan(task.id, task.title,
                java.util.Collections.emptyList(), java.util.Collections.emptyList());
            check(server.count.get() == 0, "global cloud configuration is not per-request user consent");
        }
    }

    private AgentStore.Task selectedTask(AgentStore store, java.util.List<AgentStore.Memory> selected, boolean local) throws Exception {
        AgentStore.Task task = store.createTask("SYNTHETIC goal phone 13988776655");
        store.savePlan(task.id, new ExecutionPlan(task.id, task.title, java.util.Collections.emptyList(), 1, 0)
            .withContext(TaskContext.selected(selected, local)));
        return store.getTask(task.id);
    }

    private void verifyPrivacy(AgentStore store) throws Exception {
        AgentStore.Memory selected = store.createMemory(AgentStore.MEMORY_PREFERENCE, "SYNTHETIC selected context 13988776655");
        AgentStore.Memory excluded = store.createMemory(AgentStore.MEMORY_FACT, "SYNTHETIC excluded-private-context-sentinel");
        try (CloudCapture server = new CloudCapture()) {
            CloudModelConfig config = new CloudModelConfig(true, server.endpoint(), "", "synthetic-fixture", 15000, 0.1);
            CloudModelClient client = new CloudModelClient();
            AgentStore.Task local = selectedTask(store, java.util.Collections.singletonList(selected), true);
            check(TaskContext.memories(local.plan).size() == 1 && TaskContext.memories(local.plan).get(0).id.equals(selected.id),
                "only explicitly selected memory snapshots enter a task");
            rejects(() -> client.preparePlan(local, java.util.Collections.emptyList(), config), "local-only task cannot prepare cloud request");
            HybridReasoningEngine engine = new HybridReasoningEngine(this, store, config);
            engine.plan(local.id, local.title, java.util.Collections.emptyList(), TaskContext.memories(local.plan));
            engine.synthesize(local.id, local.title, java.util.Collections.singletonList(new ExecutionPlan.Step(0,
                "SYNTHETIC private observation", "demo.note.read", "synthetic.welcome", "", ExecutionPlan.STEP_DONE,
                "SYNTHETIC observation-not-approved-for-cloud", 1)));
            rejects(() -> client.requestPlan(local.id, local.title, java.util.Collections.emptyList(), java.util.Collections.singletonList(selected), config),
                "legacy cloud planning entry fails closed");
            rejects(() -> client.requestSynthesis(local.id, local.title, java.util.Collections.emptyList(), config), "unapproved synthesis fails closed");
            check(server.count.get() == 0, "no packet without request-level consent, including synthesis");
            AgentStore.Task task = selectedTask(store, java.util.Collections.singletonList(selected), false);
            CloudRequest request = client.preparePlan(task, java.util.Collections.emptyList(), config);
            check(request.body.contains("SYNTHETIC selected context") && !request.body.contains(excluded.content), "preview excludes unselected private memory");
            check(!request.body.contains("13988776655") && request.body.contains("139****6655"), "preview shows the actual redacted payload");
            rejects(() -> client.requestApprovedPlan(request, store), "preview alone is not permission to send");
            check(server.count.get() == 0, "unapproved preview causes no HTTP request");
            request.approveOnce();
            ExecutionPlan plan = client.requestApprovedPlan(request, store);
            check(plan.totalSteps() == 1 && server.count.get() == 1, "one approved planning request reaches fixture");
            check(server.body.equals(request.body), "wire body is byte-for-byte the previewed request");
            check(plan.context.equals(task.plan.context), "cloud plan preserves selected context snapshot");
            rejects(() -> client.requestApprovedPlan(request, store), "single-use cloud approval cannot replay");
            check(server.count.get() == 1, "replay denied before a second request");
            CloudRequest revoked = client.preparePlan(task, java.util.Collections.emptyList(), config);
            revoked.approveOnce(); revoked.revoke();
            rejects(() -> client.requestApprovedPlan(revoked, store), "revoked cloud approval rejected");
            CloudRequest stale = client.preparePlan(task, java.util.Collections.emptyList(), config);
            stale.approveOnce();
            store.savePlan(task.id, task.plan.withContext(TaskContext.selected(java.util.Collections.emptyList(), false)));
            rejects(() -> client.requestApprovedPlan(stale, store), "changed selection invalidates pending approval");
            AgentStore.Task changed = store.getTask(task.id);
            CloudRequest expired = client.preparePlan(changed, java.util.Collections.emptyList(), config);
            expired.approveOnce();
            java.lang.reflect.Field deadline = CloudRequest.class.getDeclaredField("expiresAt");
            deadline.setAccessible(true); deadline.setLong(expired, 1);
            rejects(() -> client.requestApprovedPlan(expired, store), "expired approval rejected");
            check(server.count.get() == 1, "revocation, stale selection and expiry cause no request");
        }
    }

    private AgentStore.Task reviewableTask(AgentStore store) throws Exception {
        AgentStore.Task task = store.createTask("I prefer SYNTHETIC detailed task notes");
        java.util.List<ExecutionPlan.Step> steps = java.util.Arrays.asList(
            new ExecutionPlan.Step(0, "note", CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE, "", ExecutionPlan.STEP_PENDING, "", 0),
            new ExecutionPlan.Step(1, "status", CapabilityBroker.DEMO_STATUS_CAPABILITY, CapabilityBroker.DEMO_STATUS_SCOPE, "", ExecutionPlan.STEP_PENDING, "", 0),
            new ExecutionPlan.Step(2, "summary", "builtin.synthesis", "local_inference", "", ExecutionPlan.STEP_PENDING, "", 0));
        store.savePlan(task.id, new ExecutionPlan(task.id, task.title, steps, 1, 0));
        CapabilityBroker broker = new CapabilityBroker(this, store);
        broker.grant(task.id, CapabilityBroker.DEMO_NOTE_CAPABILITY, CapabilityBroker.DEMO_NOTE_SCOPE);
        broker.grant(task.id, CapabilityBroker.DEMO_STATUS_CAPABILITY, CapabilityBroker.DEMO_STATUS_SCOPE);
        store.transition(task.id, AgentStore.ACTIVE);
        new AgentExecutor(store, broker).execute(task.id, new java.util.concurrent.atomic.AtomicBoolean());
        return store.getTask(task.id);
    }

    private void verifyMemoryReview(AgentStore store) throws Exception {
        AgentStore.Task task = reviewableTask(store);
        check(MemoryConsolidator.extractItems(store.getTask(task.id)).size() == 2, "two independently reviewable candidates");
        int before = store.listMemories().size();
        rejects(() -> store.reviewMemoryCandidate(task.id, 0, true, ""), "empty edited candidate rejected");
        check(store.listMemories().size() == before, "invalid candidate leaves no inserted memory");
        check(store.reviewMemoryCandidate(task.id, 0, true, "SYNTHETIC user-edited preference") == 1, "edited candidate accepted independently");
        check("pending".equals(store.getTask(task.id).plan.memoryReview), "other candidate stays pending");
        check(store.reviewMemoryCandidate(task.id, 0, true, "SYNTHETIC duplicate") == 0, "accepted candidate cannot insert twice");
        store.reviewMemoryCandidate(task.id, 1, false, "");
        org.json.JSONObject decisions = new org.json.JSONObject(store.getTask(task.id).plan.memoryDecisions);
        check(decisions.length() == 2 && "accepted".equals(decisions.getJSONObject("0").getString("decision"))
            && "dismissed".equals(decisions.getJSONObject("1").getString("decision")), "mixed review decisions persisted");
        check(store.listMemories().size() == before + 1, "only the accepted item was saved");
        getSharedPreferences("document_validation", 0).edit().putString("reviewed", task.id).commit();
    }

    private void seedDocumentUi(AgentStore store) throws Exception {
        store.createMemory(AgentStore.MEMORY_PREFERENCE, "SYNTHETIC selected memory: prepare a concise local plan.");
        store.createMemory(AgentStore.MEMORY_FACT, "SYNTHETIC excluded private memory sentinel");
        AgentStore.Task candidate = reviewableTask(store);
        getSharedPreferences("document_validation", 0).edit().putString("ui_review", candidate.id).commit();
        check(true, "synthetic UI fixtures prepared");
    }

    private static final String ARCHIVE_PASSWORD = "synthetic-documents-migration-only";

    private void seedArchiveReviewUi(AgentStore store) throws Exception {
        for (String table : java.util.Arrays.asList("audit", "tasks", "memories", "tombstones")) store.getWritableDatabase().delete(table, null, null);
        AgentStore.Memory first = store.createMemory(AgentStore.MEMORY_FACT, "SYNTHETIC UI local first");
        AgentStore.Memory second = store.createMemory(AgentStore.MEMORY_FACT, "SYNTHETIC UI local second");
        MemoryArchive.Data data = new MemoryArchive.Data();
        data.memories.add(new AgentStore.Memory(first.id, first.kind, "SYNTHETIC UI archive first", first.createdAt, first.updatedAt));
        data.memories.add(new AgentStore.Memory(second.id, second.kind, "SYNTHETIC UI archive second", second.createdAt, second.updatedAt));
        byte[] archive = MemoryArchive.encrypt(data, ARCHIVE_PASSWORD.toCharArray());
        try (java.io.OutputStream output = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "synthetic-review.taos"))) { output.write(archive); }
        getSharedPreferences("document_validation", 0).edit().putString("review_first", first.id).putString("review_second", second.id).commit();
        check(true, "synthetic review fixture created");
    }

    private void verifyArchiveReviewUi(AgentStore store) throws Exception {
        String first = getSharedPreferences("document_validation", 0).getString("review_first", "");
        String second = getSharedPreferences("document_validation", 0).getString("review_second", "");
        java.util.Map<String, String> values = new java.util.HashMap<>();
        for (AgentStore.Memory memory : store.listMemories()) values.put(memory.id, memory.content);
        check("SYNTHETIC UI archive first".equals(values.get(first)) && "SYNTHETIC UI local second".equals(values.get(second)),
            "UI imported the separately selected versions");
    }

    private void reviewArchiveConflicts(AgentStore store) throws Exception {
        for (String table : java.util.Arrays.asList("audit", "tasks", "memories", "tombstones")) store.getWritableDatabase().delete(table, null, null);
        AgentStore.Memory first = store.createMemory(AgentStore.MEMORY_FACT, "SYNTHETIC local first");
        AgentStore.Memory second = store.createMemory(AgentStore.MEMORY_FACT, "SYNTHETIC local second");
        MemoryArchive.Data data = new MemoryArchive.Data();
        data.memories.add(new AgentStore.Memory(first.id, first.kind, "SYNTHETIC archive first", first.createdAt, first.updatedAt));
        data.memories.add(new AgentStore.Memory(second.id, second.kind, "SYNTHETIC archive second", second.createdAt, second.updatedAt));
        byte[] archive = MemoryArchive.encrypt(data, ARCHIVE_PASSWORD.toCharArray());
        AgentStore.ArchiveReview review = store.reviewArchive(new java.io.ByteArrayInputStream(archive), ARCHIVE_PASSWORD.toCharArray());
        check(review.conflicts.size() == 2, "two conflicts are reviewable");
        java.util.Map<String, Boolean> choices = new java.util.HashMap<>();
        choices.put(first.id, true); choices.put(second.id, false);
        store.importReviewedArchive(review, choices);
        java.util.Map<String, String> values = new java.util.HashMap<>();
        for (AgentStore.Memory memory : store.listMemories()) values.put(memory.id, memory.content);
        check("SYNTHETIC archive first".equals(values.get(first.id)) && "SYNTHETIC local second".equals(values.get(second.id)), "individual choices applied");
        AgentStore.ArchiveReview stale = store.reviewArchive(new java.io.ByteArrayInputStream(archive), ARCHIVE_PASSWORD.toCharArray());
        AgentStore.Memory current = null;
        for (AgentStore.Memory memory : store.listMemories()) if (second.id.equals(memory.id)) current = memory;
        store.reviseMemory(current, "SYNTHETIC changed during review", 0);
        rejects(() -> store.importReviewedArchive(stale, choices), "changed conflict invalidates review");
        values.clear();
        for (AgentStore.Memory memory : store.listMemories()) values.put(memory.id, memory.content);
        check("SYNTHETIC changed during review".equals(values.get(second.id)), "stale import leaves new edit intact");
        choices.remove(second.id);
        rejects(() -> store.importReviewedArchive(stale, choices), "missing decision is rejected");
        choices.put(second.id, false);
        AgentStore.ArchiveReview beforeDeletion = store.reviewArchive(new java.io.ByteArrayInputStream(archive), ARCHIVE_PASSWORD.toCharArray());
        store.deleteMemory(first.id);
        rejects(() -> store.importReviewedArchive(beforeDeletion, choices), "deletion invalidates reviewed conflict set");
        check(store.listMemories().size() == 1, "stale reviewed import cannot restore deleted memory");
    }

    private void exportMigration(AgentStore store) throws Exception {
        AgentStore.Memory selected = store.createMemory(AgentStore.MEMORY_PREFERENCE, "SYNTHETIC migration selected memory");
        AgentStore.Memory deleted = store.createMemory(AgentStore.MEMORY_FACT, "SYNTHETIC deleted migration memory");
        store.deleteMemory(deleted.id);
        AgentStore.Task file = draft(store);
        claim(store, file, target("migration_verified"));
        DocumentAction.writeOnce(getContentResolver(), store, file.id);
        AgentStore.Task dispatched = draft(store);
        org.json.JSONObject picking = new org.json.JSONObject(dispatched.plan.steps.get(0).observation);
        store.claimExternalAction(dispatched.id, 0, picking.getString("draftId"), picking);
        AgentStore.Task policy = selectedTask(store, java.util.Collections.singletonList(selected), false);
        verifyMemoryReview(store);
        String reviewed = getSharedPreferences("document_validation", 0).getString("reviewed", "");
        org.json.JSONObject ids = new org.json.JSONObject().put("file", file.id).put("dispatched", dispatched.id)
            .put("policy", policy.id).put("reviewed", reviewed).put("selected", selected.id).put("deleted", deleted.id)
            .put("fileUri", target("migration_verified").toString());
        for (AgentStore.Task task : store.listTasks()) {
            if ("SYNTHETIC verified Markdown acceptance".equals(task.title) && AgentStore.COMPLETED.equals(task.status))
                ids.put("uiFile", task.id);
        }
        byte[] archive = store.exportArchive(ARCHIVE_PASSWORD.toCharArray());
        MemoryArchive.Data decoded = MemoryArchive.decrypt(new java.io.ByteArrayInputStream(archive), ARCHIVE_PASSWORD.toCharArray());
        for (AgentStore.Task task : decoded.tasks) {
            if (task.id.equals(file.id)) {
                org.json.JSONObject receipt = new org.json.JSONObject(task.plan.steps.get(0).observation);
                check(!receipt.has("uri") && !receipt.getBoolean("localAccess") && receipt.has("originUri"), "export carries a historical identifier, not local access");
            }
        }
        try (java.io.OutputStream output = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "synthetic-migration.taos"))) { output.write(archive); }
        try (java.io.OutputStream output = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "migration-ids.json"))) { output.write(ids.toString().getBytes("UTF-8")); }
        check(archive[4] == 3, "migration keeps encryption envelope version 3");
    }

    private void importMigration(AgentStore store) throws Exception {
        CloudModelConfig cloudBefore = CloudModelConfig.load(this);
        if ("IMPORT_MIGRATION_WITH_CLOUD_FIXTURE".equals(getIntent().getAction()))
            check(cloudBefore.enabled && "synthetic-target-credential".equals(cloudBefore.apiKey), "target synthetic cloud credential is present before import");
        for (String table : java.util.Arrays.asList("audit", "tasks", "memories", "tombstones")) store.getWritableDatabase().delete(table, null, null);
        byte[] archive = read(new java.io.File(getFilesDir(), "synthetic-migration.taos"));
        org.json.JSONObject ids = new org.json.JSONObject(new String(read(new java.io.File(getFilesDir(), "migration-ids.json")), "UTF-8"));
        MemoryArchive.Data original = MemoryArchive.decrypt(new java.io.ByteArrayInputStream(archive), ARCHIVE_PASSWORD.toCharArray());
        AgentStore.Memory conflict = null;
        for (AgentStore.Memory memory : original.memories) if (memory.id.equals(ids.getString("selected"))) conflict = memory;
        check(conflict != null, "source selection was exported");
        MemoryArchive.Data existing = new MemoryArchive.Data();
        existing.memories.add(new AgentStore.Memory(conflict.id, conflict.kind, "SYNTHETIC target local conflict", conflict.createdAt, conflict.updatedAt));
        existing.memories.add(new AgentStore.Memory(ids.getString("deleted"), AgentStore.MEMORY_FACT, "SYNTHETIC old deleted content", 1, 1));
        store.importArchive(new java.io.ByteArrayInputStream(MemoryArchive.encrypt(existing, ARCHIVE_PASSWORD.toCharArray())), ARCHIVE_PASSWORD.toCharArray());
        store.importArchive(new java.io.ByteArrayInputStream(archive), ARCHIVE_PASSWORD.toCharArray(), false);
        boolean kept = false;
        for (AgentStore.Memory memory : store.listMemories()) if (memory.id.equals(conflict.id)) kept = "SYNTHETIC target local conflict".equals(memory.content);
        check(kept, "cross-install keep-local conflict choice honored");
        store.importArchive(new java.io.ByteArrayInputStream(archive), ARCHIVE_PASSWORD.toCharArray(), true);
        boolean replaced = false;
        for (AgentStore.Memory memory : store.listMemories()) {
            if (memory.id.equals(conflict.id)) replaced = conflict.content.equals(memory.content);
            check(!memory.id.equals(ids.getString("deleted")), "migrated tombstone wins over target old content");
        }
        check(replaced, "cross-install prefer-archive conflict choice honored");
        AgentStore.Task file = store.getTask(ids.getString("file"));
        org.json.JSONObject receipt = new org.json.JSONObject(file.plan.steps.get(0).observation);
        check(AgentStore.COMPLETED.equals(file.status) && DocumentAction.hasReceipt(receipt), "historical verified receipt and completed state preserved");
        check(!receipt.has("uri") && !receipt.getBoolean("localAccess"), "target has no inherited file authorization");
        check(ids.getString("file").equals(receipt.getString("taskId")) && receipt.getString("sha256").length() == 64, "receipt identity and hash preserved");
        rejects(() -> getContentResolver().openInputStream(Uri.parse(ids.getString("fileUri"))), "separate target UID cannot open source private provider");
        rejects(() -> DocumentAction.writeOnce(getContentResolver(), store, file.id), "imported completed receipt cannot replay write");
        AgentStore.Task dispatched = store.getTask(ids.getString("dispatched"));
        check(AgentStore.OUTCOME_UNKNOWN.equals(dispatched.status), "imported dispatched file remains unknown");
        rejects(() -> DocumentAction.writeOnce(getContentResolver(), store, dispatched.id), "imported dispatch cannot gain write permission");
        AgentStore.Task policy = store.getTask(ids.getString("policy"));
        check(TaskContext.localOnly(policy.plan) && TaskContext.memories(policy.plan).get(0).id.equals(conflict.id), "selected memory identity preserved; imported execution is local-only");
        CloudModelConfig config = new CloudModelConfig(true, "http://127.0.0.1:58999/chat/completions", "", "synthetic-fixture", 15000, 0.1);
        rejects(() -> new CloudModelClient().preparePlan(policy, java.util.Collections.emptyList(), config), "archive does not confer cloud consent");
        CloudModelConfig cloudAfter = CloudModelConfig.load(this);
        check(cloudAfter.enabled == cloudBefore.enabled && cloudAfter.endpointUrl.equals(cloudBefore.endpointUrl)
            && cloudAfter.apiKey.equals(cloudBefore.apiKey) && cloudAfter.modelName.equals(cloudBefore.modelName),
            "archive does not overwrite target cloud configuration or migrate source credentials");
        AgentStore.Task reviewed = store.getTask(ids.getString("reviewed"));
        org.json.JSONObject choices = new org.json.JSONObject(reviewed.plan.memoryDecisions);
        check(choices.length() == 2 && "SYNTHETIC user-edited preference".equals(choices.getJSONObject("0").getString("content"))
            && "dismissed".equals(choices.getJSONObject("1").getString("decision")), "mixed confirmation/rejection and edited content migrated");
        int memories = store.listMemories().size();
        check(store.reviewMemoryCandidates(reviewed.id, true) == 0 && store.listMemories().size() == memories, "migrated approval cannot insert memory again");
        getSharedPreferences("document_validation", 0).edit().putString("migratedUiFile", ids.optString("uiFile", "")).commit();
    }

    private byte[] read(java.io.File file) throws Exception {
        try (java.io.InputStream input = new java.io.FileInputStream(file); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            return output.toByteArray();
        }
    }

    private void verifyDocumentUi(AgentStore store) throws Exception {
        boolean found = false;
        for (AgentStore.Task task : store.listTasks()) {
            if ("SYNTHETIC verified Markdown acceptance".equals(task.title)) {
                found = true;
                org.json.JSONObject receipt = new org.json.JSONObject(task.plan.steps.get(0).observation);
                check(AgentStore.COMPLETED.equals(task.status) && DocumentAction.hasReceipt(receipt), "real UI document has a verified receipt");
                check(receipt.getString("content").contains("SYNTHETIC selected memory:")
                    && !receipt.getString("content").contains("excluded private memory sentinel"), "real UI output contains only selected memory");
                check(receipt.getBoolean("localAccess") && receipt.has("uri"), "file access was selected on this installation");
                check(TaskContext.memories(task.plan).size() == 1, "real UI persisted exactly one selected memory");
                int writes = 0;
                for (String event : store.listAuditEvents(task.id, 100)) if (event.contains("document_write_claimed:")) writes++;
                check(writes == 1, "real UI write was claimed once");
            }
        }
        check(found, "real UI document task exists");
    }

    private void verifyImportedReadOnly(AgentStore store) throws Exception {
        String id = getSharedPreferences("document_validation", 0).getString("migratedUiFile", "");
        AgentStore.Task task = store.getTask(id);
        org.json.JSONObject receipt = new org.json.JSONObject(task.plan.steps.get(0).observation);
        check(AgentStore.COMPLETED.equals(task.status) && receipt.getBoolean("localAccess"), "imported file explicitly reauthorized and verified here");
        check("verified_existing".equals(receipt.getString("status")), "read-only rebind does not claim a new write");
        Uri uri = Uri.parse(receipt.getString("uri"));
        boolean readOnlyGrant = false;
        for (android.content.UriPermission permission : getContentResolver().getPersistedUriPermissions())
            if (uri.equals(permission.getUri())) readOnlyGrant = permission.isReadPermission() && !permission.isWritePermission();
        check(readOnlyGrant, "target persisted only the newly selected read permission");
        DocumentAction.verify(getContentResolver(), store, task.id, receipt.getString("attemptId"), uri);
        check(AgentStore.COMPLETED.equals(store.getTask(task.id).status), "new read grant survives process stop and still verifies the file");
        rejects(() -> getContentResolver().openOutputStream(uri, "wa"), "restarted target holds no persistent write permission");
        int writes = 0;
        for (String event : store.listAuditEvents(task.id, 100)) if (event.contains("document_write_claimed:")) writes++;
        check(writes == 1, "only the original installation's write claim exists");
    }

    private void verifyCloudUi(AgentStore store) throws Exception {
        boolean approved = false;
        boolean local = false;
        for (AgentStore.Task task : store.listTasks()) {
            if ("SYNTHETIC cloud consent UI".equals(task.title)) {
                approved = true;
                check(AgentStore.COMPLETED.equals(task.status), "approved cloud UI plan executed locally to completion");
                check(TaskContext.memories(task.plan).size() == 1, "approved UI sent only one selected memory");
                int grants = 0;
                for (String event : store.listAuditEvents(task.id, 100)) if (event.contains("cloud_request_authorized:planning:")) grants++;
                check(grants == 1, "UI authorized one planning request only");
            }
            if ("SYNTHETIC keep local UI".equals(task.title)) {
                local = true;
                check(TaskContext.localOnly(task.plan), "keep-local UI decision persisted");
                for (String event : store.listAuditEvents(task.id, 100)) check(!event.contains("cloud_request_authorized:"), "keep-local UI granted no request");
            }
        }
        check(approved && local, "both cloud UI choices exercised");
    }

    private void verifyRevokedRead(AgentStore store) throws Exception {
        String id = getSharedPreferences("document_validation", 0).getString("migratedUiFile", "");
        org.json.JSONObject receipt = new org.json.JSONObject(store.getTask(id).plan.steps.get(0).observation);
        Uri uri = Uri.parse(receipt.getString("uri"));
        getContentResolver().releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        revokeUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        store.selectDocumentForVerification(id, uri.toString());
        rejects(() -> DocumentAction.verify(getContentResolver(), store, id, receipt.getString("attemptId"), uri), "actual revoked document read grant is rejected");
        check(AgentStore.OUTCOME_UNKNOWN.equals(store.getTask(id).status), "revoked real file access leaves unknown outcome");
    }

    private void verifyReviewUi(AgentStore store) throws Exception {
        String id = getSharedPreferences("document_validation", 0).getString("ui_review", "");
        org.json.JSONObject choices = new org.json.JSONObject(store.getTask(id).plan.memoryDecisions);
        check(choices.length() == 2, "UI reviewed both suggestions independently");
        check("SYNTHETIC UI edited preference".equals(choices.getJSONObject("0").getString("content")), "UI saved edited text exactly");
        check("dismissed".equals(choices.getJSONObject("1").getString("decision")), "UI rejection persisted independently");
        String memoryId = choices.getJSONObject("0").getString("memoryId");
        boolean found = false;
        for (AgentStore.Memory memory : store.listMemories()) if (memory.id.equals(memoryId)) found = "confirmed:".concat(id).equals(memory.sourceReference);
        check(found, "UI edited memory retains source task reference");
    }

    private static final class CloudCapture implements AutoCloseable {
        final java.net.ServerSocket server = new java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        final Thread worker;
        volatile String body = "";
        volatile boolean closed;
        volatile String responseContent;
        volatile int responseStatus = 200;
        volatile long delayMillis;
        CloudCapture() throws Exception {
            worker = new Thread(() -> {
                while (!closed) {
                    try (java.net.Socket client = server.accept()) {
                        client.setSoTimeout(5000);
                        java.io.InputStream input = client.getInputStream();
                        java.io.ByteArrayOutputStream headers = new java.io.ByteArrayOutputStream();
                        int value;
                        while ((value = input.read()) != -1) {
                            headers.write(value);
                            if (headers.size() > 16000) throw new java.io.IOException("Fixture header too large");
                            if (headers.toString("UTF-8").endsWith("\r\n\r\n")) break;
                        }
                        int length = 0;
                        for (String line : headers.toString("UTF-8").split("\r\n"))
                            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                        if (length < 0 || length > 2000000) throw new java.io.IOException("Fixture body too large");
                        byte[] bytes = new byte[length];
                        int offset = 0;
                        while (offset < length) {
                            int read = input.read(bytes, offset, length - offset);
                            if (read < 0) throw new java.io.IOException("Incomplete fixture body");
                            offset += read;
                        }
                        body = new String(bytes, "UTF-8");
                        count.incrementAndGet();
                        if (delayMillis > 0) Thread.sleep(delayMillis);
                        String plan = responseContent == null ? "{\"steps\":[{\"capability\":\"builtin.synthesis\",\"scope\":\"local_inference\",\"title\":\"SYNTHETIC summary\"}]}" : responseContent;
                        org.json.JSONObject response = new org.json.JSONObject().put("choices", new org.json.JSONArray().put(
                            new org.json.JSONObject().put("message", new org.json.JSONObject().put("content", plan))));
                        byte[] responseBytes = response.toString().getBytes("UTF-8");
                        java.io.OutputStream output = client.getOutputStream();
                        output.write(("HTTP/1.1 " + responseStatus + " Fixture\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: "
                            + responseBytes.length + "\r\n\r\n").getBytes("UTF-8"));
                        output.write(responseBytes);
                        output.flush();
                    } catch (Exception error) { if (!closed) android.util.Log.e("AgentOsDocumentsValidation", "Synthetic server failed", error); }
                }
            }, "synthetic-cloud-capture");
            worker.start();
        }
        String endpoint() { return "http://127.0.0.1:" + server.getLocalPort() + "/chat/completions"; }
        @Override public void close() throws Exception { closed = true; server.close(); worker.join(1000); }
    }
    private interface Checked { void run() throws Exception; }
    private void rejects(Checked action, String message) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException | SecurityException | java.io.IOException | org.json.JSONException expected) { rejected = true; }
        check(rejected, message);
    }
}
