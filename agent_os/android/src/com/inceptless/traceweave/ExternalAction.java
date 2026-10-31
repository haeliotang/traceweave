package com.inceptless.traceweave;

import android.content.Intent;
import android.provider.CalendarContract;

import org.json.JSONObject;

import java.util.TimeZone;
import java.util.UUID;

final class ExternalAction {
    private ExternalAction() {}

    static boolean hasVerifiedEffect(ExecutionPlan.Step step) {
        if (!ExecutionPlan.STEP_DONE.equals(step.status)) return false;
        try {
            JSONObject result = new JSONObject(step.observation);
            if (DocumentAction.CAPABILITY.equals(step.capability)) return DocumentAction.hasReceipt(result);
            return "created".equals(result.optString("status"))
                && "provider_readback".equals(result.optString("verification"));
        } catch (Exception error) { return false; }
    }

    static JSONObject calendarDraft(String title, long start, long end) throws Exception {
        String normalized = title == null ? "" : title.trim();
        if (normalized.length() > 2000) throw new IllegalArgumentException("Calendar title is too long");
        if ((start != 0 || end != 0) && (start <= 0 || end <= start))
            throw new IllegalArgumentException("Calendar end must be after start");
        return new JSONObject().put("provider", "system.calendar").put("status", "prepared")
            .put("title", normalized).put("startTime", start).put("endTime", end)
            .put("timeZone", TimeZone.getDefault().getID());
    }

    static JSONObject prepare(AgentStore.Task task, ExecutionPlan.Step step) throws Exception {
        JSONObject draft;
        if (ExecutionPlan.STEP_FAILED.equals(step.status) && !step.observation.isEmpty()) {
            draft = new JSONObject(step.observation);
            draft.remove("attemptId");
            draft.remove("verification");
            return draft.put("status", "prepared").put("draftId", UUID.randomUUID().toString());
        }
        if (DocumentAction.CAPABILITY.equals(step.capability)) {
            draft = DocumentAction.draft(DocumentAction.markdown(task.title, java.util.Collections.emptyList()),
                new org.json.JSONArray());
        } else if (LocalCalendarProvider.CAPABILITY.equals(step.capability)) {
            JSONObject input = null;
            try { input = new JSONObject(step.prompt); } catch (Exception ignored) {}
            draft = calendarDraft(input == null ? task.title : input.optString("title", task.title),
                input == null ? 0 : input.optLong("startTime", 0), input == null ? 0 : input.optLong("endTime", 0));
        } else if (LocalShareProvider.CAPABILITY.equals(step.capability)) {
            StringBuilder content = new StringBuilder(task.title);
            for (ExecutionPlan.Step previous : task.plan.steps) {
                if (previous.index >= step.index) break;
                if (ExecutionPlan.STEP_DONE.equals(previous.status) && !previous.isBuiltin())
                    content.append("\n").append(previous.title).append(": ").append(previous.observation);
            }
            String text = content.toString();
            draft = new JSONObject().put("provider", "system.share").put("status", "ready_for_sharing")
                .put("subject", task.title).put("content", text.substring(0, Math.min(8000, text.length())));
        } else {
            throw new IllegalArgumentException("Unsupported external action");
        }
        return draft.put("draftId", UUID.randomUUID().toString());
    }

    static Intent intent(String capability, JSONObject draft) throws Exception {
        if (DocumentAction.CAPABILITY.equals(capability)) {
            DocumentAction.validateContent(draft.getString("content"));
            return new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/markdown").putExtra(Intent.EXTRA_TITLE, draft.getString("fileName"));
        }
        if (LocalCalendarProvider.CAPABILITY.equals(capability)) {
            JSONObject checked = calendarDraft(draft.getString("title"), draft.getLong("startTime"), draft.getLong("endTime"));
            if (checked.getString("title").isEmpty() || checked.getLong("startTime") <= 0)
                throw new IllegalArgumentException("Choose a title, start and end time");
            return new Intent(Intent.ACTION_INSERT)
                .setDataAndType(CalendarContract.Events.CONTENT_URI, LocalCalendarProvider.MIME_TYPE)
                .putExtra(CalendarContract.Events.TITLE, checked.getString("title"))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, checked.getLong("startTime"))
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, checked.getLong("endTime"));
        }
        if (LocalShareProvider.CAPABILITY.equals(capability)) {
            String text = draft.getString("content").trim();
            if (text.isEmpty() || text.length() > 8000) throw new IllegalArgumentException("Choose share text (up to 8000 characters)");
            return new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, draft.optString("subject", ""))
                .putExtra(Intent.EXTRA_TEXT, text);
        }
        throw new IllegalArgumentException("Unsupported external action");
    }
}
