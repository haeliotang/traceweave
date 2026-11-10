// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/** Kernel-wide ten-second memory stall fraction; availability depends on procfs policy. */
final class NativeMemoryPressure {
    static double read() { return read("/proc/pressure/memory"); }
    static double read(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("some ")) return parse(line);
            }
        } catch (IOException | SecurityException unavailable) { return Double.NaN; }
        return Double.NaN;
    }
    static double parse(String line) {
        if (line == null) return Double.NaN;
        String[] fields = line.trim().split("\\s+");
        if (fields.length < 2 || !fields[0].equals("some")) return Double.NaN;
        double result = Double.NaN;
        boolean found = false;
        for (int index = 1; index < fields.length; index++) {
            if (!fields[index].startsWith("avg10=")) continue;
            if (found) return Double.NaN;
            found = true;
            try {
                double percent = Double.parseDouble(fields[index].substring(6));
                if (!Double.isFinite(percent) || percent < 0 || percent > 100) return Double.NaN;
                result = percent / 100;
            } catch (NumberFormatException malformed) { return Double.NaN; }
        }
        return result;
    }
}
