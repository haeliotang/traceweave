// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Offline, synthetic-only model-to-document experiment; not shipped in production. */
public final class LocalModelDocumentProbe extends Activity {
    private static final String MODEL_SHA = "dcd819ff094852c38faba6873d8ff0c9d51eadb2844539e52042ae5d647bbfdb";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try (AgentStore store = new AgentStore(this)) {
                if ("SEED".equals(getIntent().getAction())) {
                    File model = new File(getFilesDir(), "synthetic-evaluation-model.gguf");
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (FileInputStream input = new FileInputStream(model)) {
                        byte[] buffer = new byte[65536]; int count;
                        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                    }
                    StringBuilder hash = new StringBuilder();
                    for (byte b : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                    if (!MODEL_SHA.equals(hash.toString())) throw new SecurityException("Model digest mismatch");
                    if (!LocalModelRuntime.isAvailable()) throw new IllegalStateException("Local model runtime unavailable");
                    LocalModelRuntime.prepare();
                    byte[] bytes = LocalModelRuntime.generate(model.getPath(),
                        "Write a short Markdown checklist for a synthetic camping trip."
                            .getBytes(StandardCharsets.UTF_8), 96);
                    String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                    DocumentAction.validateContent(text);
                    AgentStore.Task task = store.createDocumentTask("SYNTHETIC local model checklist", text, new JSONArray());
                    if (!AgentStore.WAITING_FOR_USER.equals(task.status)) throw new IllegalStateException("Draft did not await review");
                    getSharedPreferences("model_document_probe", 0).edit().putString("task", task.id).commit();
                    getSharedPreferences("agent_os_prefs", 0).edit().putString("app_lang", "en").commit();
                    Log.i("AgentOsModelDocument", "PASS generated draft awaits review");
                    runOnUiThread(() -> {
                        startActivity(new Intent(this, DocumentTaskActivity.class).putExtra(DocumentTaskActivity.EXTRA_TASK_ID, task.id));
                        finish();
                    });
                } else if ("VERIFY".equals(getIntent().getAction())) {
                    String id = getSharedPreferences("model_document_probe", 0).getString("task", "");
                    AgentStore.Task task = store.getTask(id);
                    JSONObject draft = new JSONObject(task.plan.steps.get(0).observation);
                    if (!AgentStore.WAITING_FOR_USER.equals(task.status) || !draft.optString("attemptId").isEmpty())
                        throw new IllegalStateException("Draft was dispatched before confirmation");
                    boolean blocked = false;
                    try { DocumentAction.writeOnce(getContentResolver(), store, id); }
                    catch (IllegalStateException expected) { blocked = true; }
                    if (!blocked) throw new IllegalStateException("Unapproved write was accepted");
                    String attempt = store.claimExternalAction(id, 0, draft.getString("draftId"), draft);
                    Uri uri = Uri.parse("content://" + getPackageName() + ".documents.fixture/model_document");
                    store.bindDocumentTarget(id, attempt, uri.toString());
                    DocumentAction.writeOnce(getContentResolver(), store, id);
                    if (!DocumentAction.locallyVerified(store.getTask(id))) throw new IllegalStateException("Synthetic file readback failed");
                    Log.i("AgentOsModelDocument", "PASS synthetic approval and file readback");
                } else throw new IllegalArgumentException("Unknown probe action");
            } catch (Throwable error) {
                Log.e("AgentOsModelDocument", "FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }, "synthetic-model-document").start();
    }
}
