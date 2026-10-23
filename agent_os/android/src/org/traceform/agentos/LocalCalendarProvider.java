package org.traceform.agentos;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class LocalCalendarProvider implements CapabilityProvider {
    static final String CAPABILITY = "system.calendar.create";
    static final String SCOPE = "system.calendar_event";
    static final String INTENT_ACTION = "android.intent.action.INSERT";
    static final String CALENDAR_URI = "content://com.android.calendar/events";

    @Override
    public String capability() {
        return CAPABILITY;
    }

    @Override
    public boolean supportsScope(String scope) {
        return SCOPE.equals(scope);
    }

    @Override
    public boolean isAvailable(String taskId, String scope) {
        return supportsScope(scope);
    }

    @Override
    public String read(String taskId, String scope) {
        if (!isAvailable(taskId, scope)) {
            throw new IllegalStateException("Calendar provider does not support scope: " + scope);
        }

        long now = System.currentTimeMillis();
        // Schedule event 1 hour from now for 30 minutes
        long startTime = now + 3600_000L;
        long endTime = startTime + 1800_000L;
        String formattedDate = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(startTime));

        try {
            JSONObject event = new JSONObject();
            event.put("provider", "system.calendar");
            event.put("title", "Agent OS 规划日程待办");
            event.put("description", "由 Agent OS 自动规划创建的本地提醒日程事项 (taskId=" + taskId + ")");
            event.put("startTime", startTime);
            event.put("endTime", endTime);
            event.put("formattedStart", formattedDate);
            event.put("durationMinutes", 30);
            event.put("intent_action", INTENT_ACTION);
            event.put("intent_uri", CALENDAR_URI);
            event.put("status", "prepared");
            return event.toString();
        } catch (Exception ex) {
            throw new RuntimeException("Failed to generate calendar event: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void releaseTask(String taskId) {}
}
