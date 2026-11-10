// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Feeds a JSONL observation sequence to a ported feature class and prints its outputs.
 * Each input line is an observation, or {"__reset": true} to call reset(). Output: a "#NAMES"
 * header, then one comma-separated line of full-precision doubles per observation.
 * The class needs a package-visible NAMES array, reset() and extract(Map) returning double[].
 */
public final class ParityHarness {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName(args[1]);
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object instance = constructor.newInstance();
        Field names = type.getDeclaredField("NAMES");
        names.setAccessible(true);
        Method reset = type.getDeclaredMethod("reset");
        reset.setAccessible(true);
        Method extract = type.getDeclaredMethod("extract", Map.class);
        extract.setAccessible(true);
        StringBuilder out = new StringBuilder("#NAMES ").append(String.join(",", (String[]) names.get(null))).append('\n');
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(args[0]), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> observation = (Map<String, Object>) MiniJson.parse(line);
                if (observation.containsKey("__reset")) {
                    reset.invoke(instance);
                    out.append("#RESET\n");
                    continue;
                }
                double[] values;
                try {
                    values = (double[]) extract.invoke(instance, observation);
                } catch (java.lang.reflect.InvocationTargetException failure) {
                    out.append("#ERROR ").append(failure.getCause()).append('\n');
                    reset.invoke(instance);
                    continue;
                }
                for (int i = 0; i < values.length; i++) out.append(i == 0 ? "" : ",").append(Double.toString(values[i]));
                out.append('\n');
            }
        }
        System.out.print(out);
    }
}
