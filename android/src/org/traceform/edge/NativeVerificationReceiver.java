package org.traceform.edge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import org.json.JSONObject;
import java.io.File;

/** DUMP-permission protected, synthetic-only debug verification entry. */
public final class NativeVerificationReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        if ("org.traceform.edge.VERIFY_LINEAGE_CAPACITY".equals(intent.getAction())) {
            try {
                JSONObject report;
                try { report = ColdLineage.verifyCapacity(); }
                catch (Exception error) { report = new JSONObject().put("passed", false).put("error", error.toString()); }
                NativeVerificationJob.write(new File(context.getFilesDir(), "lineage_capacity_fixture.json"),
                    report.put("completed_epoch_ms", System.currentTimeMillis()));
            } catch (Exception error) { android.util.Log.e("Traceform", "debug lineage capacity failed", error); }
            return;
        }
        if ("org.traceform.edge.VERIFY_LINEAGE_RETENTION".equals(intent.getAction())) {
            try {
                JSONObject report;
                try { report = ColdLineage.verifyRetention(new ModelEngine(context)); }
                catch (Exception error) { report = new JSONObject().put("passed", false).put("error", error.toString()); }
                NativeVerificationJob.write(new File(context.getFilesDir(), "lineage_retention_fixture.json"),
                    report.put("completed_epoch_ms", System.currentTimeMillis()));
            } catch (Exception error) { android.util.Log.e("Traceform", "debug lineage retention failed", error); }
            return;
        }
        if ("org.traceform.edge.VERIFY_LINEAGE_ADMISSION".equals(intent.getAction())) {
            try {
                JSONObject report;
                try { report = ColdLineage.verifyAdmission(new ModelEngine(context)); }
                catch (Exception error) { report = new JSONObject().put("passed", false).put("error", error.toString()); }
                NativeVerificationJob.write(new File(context.getFilesDir(), "lineage_admission_fixture.json"),
                    report.put("completed_epoch_ms", System.currentTimeMillis()));
            } catch (Exception error) { android.util.Log.e("Traceform", "debug lineage admission failed", error); }
            return;
        }
        if ("org.traceform.edge.VERIFY_COLD_LINEAGE".equals(intent.getAction())) {
            String nonce = java.util.UUID.randomUUID().toString();
            try {
                NativeVerificationJob.scheduleLineage(context, nonce);
                NativeVerificationJob.write(new File(context.getFilesDir(), "cold_lineage_dispatch.json"),
                    new JSONObject().put("nonce", nonce).put("job_id", NativeVerificationJob.JOB_ID)
                        .put("scheduled_epoch_ms", System.currentTimeMillis()));
            } catch (Exception error) { android.util.Log.e("Traceform", "debug lineage dispatch failed", error); }
            return;
        }
        if ("org.traceform.edge.VERIFY_REGISTRY".equals(intent.getAction())) {
            try {
                JSONObject report;
                try { report = NativeRegistryFixture.verify(new ModelEngine(context), context.getCacheDir()); }
                catch (Exception error) { report = new JSONObject().put("passed", false).put("error", error.toString()); }
                NativeVerificationJob.write(new File(context.getFilesDir(), "registry_fixture.json"),
                    report.put("completed_epoch_ms", System.currentTimeMillis()));
            } catch (Exception error) { android.util.Log.e("Traceform", "debug registry fixture failed", error); }
            return;
        }
        if ("org.traceform.edge.VERIFY_SNAPSHOT".equals(intent.getAction())) {
            try {
                JSONObject report;
                try { report = WeeklySnapshotFixture.verify(new ModelEngine(context), context.getCacheDir()); }
                catch (Exception error) { report = new JSONObject().put("passed", false).put("error", error.toString()); }
                NativeVerificationJob.write(new File(context.getFilesDir(), "snapshot_fixture.json"),
                    report.put("completed_epoch_ms", System.currentTimeMillis()));
            } catch (Exception error) { android.util.Log.e("Traceform", "debug snapshot fixture failed", error); }
            return;
        }
        if ("org.traceform.edge.VERIFY_COLD_AUDIT".equals(intent.getAction())) {
            try {
                JSONObject report;
                try { report = NativeColdAudit.verify(new ModelEngine(context)); }
                catch (Exception error) { report = new JSONObject().put("passed", false).put("error", error.toString()); }
                NativeVerificationJob.write(new File(context.getFilesDir(), "cold_audit_fixture.json"),
                    report.put("completed_epoch_ms", System.currentTimeMillis()));
            } catch (Exception error) { android.util.Log.e("Traceform", "debug cold audit fixture failed", error); }
            return;
        }
        if ("org.traceform.edge.VERIFY_WARM_AUDIT".equals(intent.getAction())) {
            try {
                JSONObject report;
                try { report = NativeWarmAudit.verify(new ModelEngine(context)); }
                catch (Exception error) { report = new JSONObject().put("passed", false).put("error", error.toString()); }
                NativeVerificationJob.write(new File(context.getFilesDir(), "warm_audit_fixture.json"),
                    report.put("completed_epoch_ms", System.currentTimeMillis()));
            } catch (Exception error) { android.util.Log.e("Traceform", "debug warm audit fixture failed", error); }
            return;
        }
        if ("org.traceform.edge.VERIFY_MODEL_JSON".equals(intent.getAction())) {
            try {
                JSONObject report;
                try { report = ModelJsonFixture.verify(new ModelEngine(context)); }
                catch (Exception error) { report = new JSONObject().put("passed", false).put("error", error.toString()); }
                NativeVerificationJob.write(new File(context.getFilesDir(), "model_json_fixture.json"),
                    report.put("completed_epoch_ms", System.currentTimeMillis()));
            } catch (Exception error) { android.util.Log.e("Traceform", "debug model JSON fixture failed", error); }
            return;
        }
        if (!"org.traceform.edge.VERIFY_WEEKLY".equals(intent.getAction())) return;
        String nonce = java.util.UUID.randomUUID().toString();
        try {
            NativeVerificationJob.schedule(context, nonce);
            NativeVerificationJob.write(new File(context.getFilesDir(), "weekly_verification_dispatch.json"),
                new JSONObject().put("nonce", nonce).put("job_id", NativeVerificationJob.JOB_ID)
                    .put("scheduled_epoch_ms", System.currentTimeMillis()));
        } catch (Exception error) { android.util.Log.e("Traceform", "debug weekly dispatch failed", error); }
    }
}
