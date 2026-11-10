// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;


final class LocalCalendarProvider implements CapabilityProvider {
    static final String CAPABILITY = "system.calendar.create";
    static final String SCOPE = "system.calendar_event";
    static final String INTENT_ACTION = "android.intent.action.INSERT";
    static final String MIME_TYPE = "vnd.android.cursor.dir/event";
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

        try {
            return ExternalAction.calendarDraft("", 0, 0)
                .put("intent_action", INTENT_ACTION).put("intent_uri", CALENDAR_URI).toString();
        } catch (Exception ex) {
            throw new RuntimeException("Failed to generate calendar event: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void releaseTask(String taskId) {}
}
