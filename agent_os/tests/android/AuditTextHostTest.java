// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

import com.inceptless.traceweave.AuditText;
import com.inceptless.traceweave.T;

/** Host check for audit wording, run by test_audit_text.py. */
public final class AuditTextHostTest {
    private static int checks = 0;

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("FAIL " + message);
        checks++;
    }

    public static void main(String[] args) {
        check(AuditText.describe("task_created", true).equals("Task created"), "known event in english");
        check(AuditText.describe("task_created", false).equals("任务已创建"), "known event in chinese");

        String planned = AuditText.describe("plan_generated:2", true);
        check(planned.equals("Plan generated (2 steps)"), "argument is rendered, got: " + planned);

        String waiting = AuditText.describe("task_waiting_for_user:step_1", false);
        check(waiting.contains("step_1"), "waiting event keeps the step, got: " + waiting);

        // Background sync events keep their reason and never show a leading space.
        String skipped = AuditText.describe("sync_auto_skipped: no paired device", true);
        check(skipped.equals("Background sync skipped: no paired device"), "auto-sync reason, got: " + skipped);
        check(!skipped.contains(":  "), "no double space after trimming");

        // Unknown events stay readable instead of disappearing.
        String unknown = AuditText.describe("sync_cursor_moved", true);
        check(unknown.contains("sync_cursor_moved"), "unknown sync event names itself, got: " + unknown);
        String unknownTask = AuditText.describe("executor_step_3_started", true);
        check(unknownTask.equals("Step 2 started") || unknownTask.contains("executor_step_3_started"),
            "unknown executor event falls back to its name, got: " + unknownTask);

        check(AuditText.describe("", true).isEmpty(), "empty event renders empty");
        check(AuditText.describe(null, true).isEmpty(), "null event renders empty");
        check(AuditText.describe("weird_event_without_colon", true).equals("weird_event_without_colon"),
            "event without an argument is returned as is");
        check(T.has("audit.event.task_created"), "catalog carries the event keys");
        System.out.println("PASS audit_text checks=" + checks);
    }
}
