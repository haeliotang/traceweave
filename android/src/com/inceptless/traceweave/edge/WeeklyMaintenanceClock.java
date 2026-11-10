// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/** Local Sunday window, resolved through zone rules rather than fixed week seconds. */
final class WeeklyMaintenanceClock {
    static final class Window {
        final long startMillis, endMillis;
        final String key;
        Window(LocalDate sunday, ZoneId zone) {
            startMillis = sunday.atTime(LocalTime.of(2, 0)).atZone(zone).toInstant().toEpochMilli();
            endMillis = sunday.atTime(LocalTime.of(6, 0)).atZone(zone).toInstant().toEpochMilli();
            key = sunday.toString();
        }
        boolean contains(long nowMillis) { return nowMillis >= startMillis && nowMillis < endMillis; }
    }
    static Window next(long nowMillis, ZoneId zone, String completedWindow) {
        ZonedDateTime local = Instant.ofEpochMilli(nowMillis).atZone(zone);
        LocalDate sunday = local.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        Window window = new Window(sunday, zone);
        if (nowMillis >= window.endMillis || window.key.equals(completedWindow)) window = new Window(sunday.plusWeeks(1), zone);
        return window;
    }
}
