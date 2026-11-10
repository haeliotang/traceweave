// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

/** One-step temporary music duck with conditional restoration of the owned value. */
final class VolumeAdjustment {
    interface Control {
        int volume() throws Exception;
        boolean setVolume(int value) throws Exception;
        void pause() throws Exception;
        default void beforeAdjustment(int original, int target) throws Exception {}
        default void afterAdjustment(Result result) throws Exception {}
    }
    static final class Result {
        boolean delivered, restored;
        String reason, restorationStatus = "not_needed";
        int writes;
    }
    static Result run(Control control, int maximum, boolean enabled) throws Exception {
        if (maximum < 0) throw new IllegalArgumentException("valid stream range required");
        Result result = new Result();
        if (!enabled) { result.reason = "disabled"; return result; }
        int original = control.volume(), target = original - 1;
        if (original < 0 || original > maximum) throw new IllegalArgumentException("invalid original music volume");
        if (original == 0) { result.reason = "already_muted"; return result; }
        boolean interrupted = false;
        try {
            control.beforeAdjustment(original,target);
            result.writes++;
            result.delivered = control.setVolume(target) && control.volume() == target;
            result.reason = result.delivered ? "target_volume_verified" : "target_volume_not_verified";
            if (result.delivered) control.pause();
        } catch (InterruptedException error) {
            interrupted = true; throw error;
        } finally {
            try {
                int current = control.volume();
                if (current == original) { result.restored = true; result.restorationStatus = "restored"; }
                else if (result.writes > 0 && current == target) {
                    result.writes++;
                    result.restored = control.setVolume(original) && control.volume() == original;
                    result.restorationStatus = result.restored ? "restored" : "failed";
                    if (!result.restored) result.reason = "restoration_failed";
                } else result.restorationStatus = "external_change_preserved";
                control.afterAdjustment(result);
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        return result;
    }
    static Result recover(Control control, int original, int target, int maximum) throws Exception {
        if (original < 1 || original > maximum || target != original - 1)
            throw new IllegalArgumentException("invalid pending volume range");
        Result result = new Result();
        int current = control.volume();
        if (current == original) { result.restored = true; result.restorationStatus = "restored"; }
        else if (current == target) {
            result.writes++;
            result.restored = control.setVolume(original) && control.volume() == original;
            result.restorationStatus = result.restored ? "restored" : "failed";
        } else result.restorationStatus = "external_change_preserved";
        result.reason = result.restorationStatus;
        return result;
    }
}
