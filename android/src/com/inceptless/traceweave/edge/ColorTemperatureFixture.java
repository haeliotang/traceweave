// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.content.Context;
import org.json.JSONObject;

final class ColorTemperatureFixture {
    static final class Simulated implements ColorTemperatureAdjustment.Control {
        int value = 3000, writes;
        boolean external, rejectRestore;
        public int temperature() { return value; }
        public boolean active() { return true; }
        public int autoMode() { return 0; }
        public boolean setTemperature(int target) { writes++; if (rejectRestore && writes == 2) return false; value = target; return true; }
        public void pause() { if (external) value = 2800; }
    }
    static JSONObject verify(Context context) throws Exception {
        Simulated normal = new Simulated();
        ColorTemperatureAdjustment.Result result = ColorTemperatureAdjustment.run(normal, 2900, 686, 4082, true, true);
        if (!result.delivered || !result.restored || normal.value != 3000 || normal.writes != 2)
            throw new IllegalStateException("simulated color restoration failed");
        Simulated external = new Simulated(); external.external = true;
        result = ColorTemperatureAdjustment.run(external, 2900, 686, 4082, true, true);
        if (!result.delivered || result.restored || external.value != 2800 || external.writes != 1)
            throw new IllegalStateException("simulated external color change overwritten");
        Simulated disabled = new Simulated();
        result = ColorTemperatureAdjustment.run(disabled, 2900, 686, 4082, false, true);
        if (result.delivered || disabled.writes != 0) throw new IllegalStateException("disabled controller wrote");
        Simulated failedRestore = new Simulated(); failedRestore.rejectRestore = true;
        result = ColorTemperatureAdjustment.run(failedRestore, 2900, 686, 4082, true, true);
        if (!result.delivered || result.restored || !result.reason.equals("restoration_failed") || !result.restorationStatus.equals("failed"))
            throw new IllegalStateException("failed restoration receipt omitted");
        java.io.File journalPath = new java.io.File(context.getCacheDir(), "color-recovery-fixture-" + java.util.UUID.randomUUID() + ".bin");
        ColorTemperatureJournal journal = new ColorTemperatureJournal(journalPath);
        try {
            journal.save(3000, 2900);
            ColorTemperatureJournal.Pending pending = new ColorTemperatureJournal(journalPath).read();
            if (pending == null || pending.original != 3000 || pending.target != 2900)
                throw new IllegalStateException("persistent recovery record mismatch");
            boolean duplicateRejected = false;
            try { journal.save(3000, 2800); } catch (java.io.IOException expected) { duplicateRejected = true; }
            if (!duplicateRejected) throw new IllegalStateException("pending journal overwritten");
            Simulated recovered = new Simulated(); recovered.value = pending.target;
            result = ColorTemperatureAdjustment.recover(recovered, pending.original, pending.target, 686, 4082, true);
            if (result.delivered || !result.restored || recovered.value != 3000 || result.writes != 1)
                throw new IllegalStateException("reopened pending adjustment not restored");
            journal.clear();
            if (journal.read() != null) throw new IllegalStateException("completed recovery retained");
            java.nio.file.Files.write(journalPath.toPath(), new byte[]{1, 2, 3});
            boolean corruptRejected = false;
            try { journal.read(); } catch (java.io.IOException expected) { corruptRejected = true; }
            if (!corruptRejected) throw new IllegalStateException("corrupt recovery journal accepted");
        } finally { journal.clear(); }
        JSONObject capability = NightDisplay.capabilities(context);
        JSONObject blocked = new JSONObject().put("checked", false);
        if (!capability.getBoolean("write_secure_settings_granted") && capability.optBoolean("config_nightDisplayAvailable")
                && !capability.isNull("config_nightDisplayColorTemperatureMin") && !capability.isNull("config_nightDisplayColorTemperatureMax")) {
            int target = capability.getInt("config_nightDisplayColorTemperatureMin");
            blocked = NightDisplay.adjust(context, target).put("checked", true);
            if (blocked.getBoolean("delivered") || blocked.getInt("settings_write_attempts") != 0)
                throw new IllegalStateException("unprivileged controller wrote");
        }
        boolean adjustmentBusy = false, recoveryBusy = false;
        try (AlignmentQueryLease held = AlignmentQueryLease.acquireColorTemperature(context.getFilesDir())) {
            try { NightDisplay.adjust(context, 2900); } catch (SecurityException expected) { adjustmentBusy = true; }
            try { NightDisplay.recover(context); } catch (SecurityException expected) { recoveryBusy = true; }
            try (AlignmentQueryLease independent = AlignmentQueryLease.acquire(context.getFilesDir())) { }
        }
        if (!adjustmentBusy || !recoveryBusy) throw new IllegalStateException("overlapping color operation accepted");
        try (AlignmentQueryLease resumed = AlignmentQueryLease.acquireColorTemperature(context.getFilesDir())) { }
        return new JSONObject().put("passed", true).put("unprivileged_native_guard", blocked).put("overlapping_adjustment_rejected", adjustmentBusy).put("overlapping_recovery_rejected", recoveryBusy)
            .put("scope", "simulated_color_adjustment_journal_reopen_operation_lock_and_native_permission_guard_not_display_change");
    }
}
