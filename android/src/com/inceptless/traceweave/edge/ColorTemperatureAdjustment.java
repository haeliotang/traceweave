// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

final class ColorTemperatureAdjustment {
    interface Control {
        int temperature() throws Exception;
        boolean active() throws Exception;
        int autoMode() throws Exception;
        boolean setTemperature(int value) throws Exception;
        void pause() throws Exception;
        default void beforeAdjustment(int original, int target) throws Exception {}
        default void afterAdjustment(Result result) throws Exception {}
    }
    static final class Result {
        boolean delivered, restored;
        String reason, restorationStatus = "not_needed";
        int writes;
    }
    static synchronized Result run(Control control, int target, int minimum, int maximum, boolean enabled, boolean writable) throws Exception {
        if (minimum <= 0 || maximum < minimum || target < minimum || target > maximum)
            throw new IllegalArgumentException("target outside device color temperature range");
        Result result = new Result();
        if (!enabled || !writable) { result.reason = "disabled_or_permission_denied"; return result; }
        if (!control.active() || control.autoMode() != 0) { result.reason = "manual_night_display_required"; return result; }
        int original = control.temperature();
        if (original < minimum || original > maximum) { result.reason = "original_temperature_unavailable"; return result; }
        if (original == target) { result.reason = "already_at_target"; return result; }
        boolean interrupted = false;
        try {
            control.beforeAdjustment(original, target);
            result.writes++;
            result.delivered = control.setTemperature(target) && control.temperature() == target;
            result.reason = result.delivered ? "target_setting_verified" : "target_setting_not_verified";
            if (result.delivered) control.pause();
        } catch (InterruptedException error) {
            interrupted = true;
            throw error;
        } finally {
            try {
                if (result.writes > 0 && control.temperature() == target && control.active() && control.autoMode() == 0) {
                    result.writes++;
                    result.restored = control.setTemperature(original) && control.temperature() == original;
                    result.restorationStatus = result.restored ? "restored" : "failed";
                    if (!result.restored) result.reason = "restoration_failed";
                } else if (result.delivered) result.restorationStatus = "external_change_preserved";
                control.afterAdjustment(result);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        return result;
    }
    static synchronized Result recover(Control control, int original, int target, int minimum, int maximum, boolean writable) throws Exception {
        if (minimum <= 0 || maximum < minimum || original < minimum || original > maximum || target < minimum || target > maximum || original == target)
            throw new IllegalArgumentException("invalid pending color temperature range");
        Result result = new Result();
        if (!writable) { result.reason = "recovery_permission_denied"; result.restorationStatus = "pending"; return result; }
        int current = control.temperature();
        if (current == original) {
            result.restored = true; result.restorationStatus = "restored"; result.reason = "original_already_present";
        } else if (current == target && control.active() && control.autoMode() == 0) {
            result.writes++;
            result.restored = control.setTemperature(original) && control.temperature() == original;
            result.restorationStatus = result.restored ? "restored" : "failed";
            result.reason = result.restored ? "pending_adjustment_restored" : "restoration_failed";
        } else {
            result.restorationStatus = "external_change_preserved"; result.reason = "pending_adjustment_superseded";
        }
        return result;
    }
}
