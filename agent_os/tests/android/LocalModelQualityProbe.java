package org.traceform.agentos;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed synthetic cases; scores are observations, never action authorization. */
public final class LocalModelQualityProbe extends Activity {
    private static final String SMALL_SHA = "7671c0c304e6ce5a7fc577bcb12aba01e2c155cc2efd29b2213c95b18edaf6ed";
    private static final String LARGE_SHA = "dcd819ff094852c38faba6873d8ff0c9d51eadb2844539e52042ae5d647bbfdb";
    private static final String MINICPM_SHA = "ec2d5801640099e97d8d7e8003ad4d81f336e757811f03a26173dddf386602fd";
    private static final String[][] CASES = {
        {"english_exact", "Reply with exactly READY and no other words."},
        {"chinese_exact", "只回复两个汉字：完成。不要回复其他内容。"},
        {"arithmetic_exact", "Reply with the number only: 17 + 25 = ?"},
        {"strict_json", "Reply with JSON only, no markdown: {\"status\":\"ready\"}"},
        {"list_three", "List exactly these three words, one per line: water, tent, map."}
    };

    private static final String[][] QUALITY_CASES = {
        {"english_exact", "Reply with exactly READY and no other words.", "exact", "READY"},
        {"english_exact", "Reply with exactly OK and no other words.", "exact", "OK"},
        {"english_exact", "Reply with exactly DONE and no other words.", "exact", "DONE"},
        {"english_exact", "Reply with exactly HELLO and no other words.", "exact", "HELLO"},
        {"english_exact", "Reply with exactly STOP and no other words.", "exact", "STOP"},
        {"english_exact", "Reply with exactly YES and no other words.", "exact", "YES"},
        {"english_exact", "Reply with exactly NO and no other words.", "exact", "NO"},
        {"english_exact", "Reply with exactly GREEN and no other words.", "exact", "GREEN"},
        {"english_exact", "Reply with exactly NORTH and no other words.", "exact", "NORTH"},
        {"english_exact", "Reply with exactly END and no other words.", "exact", "END"},
        {"chinese_exact", "只回复两个汉字：完成。不要回复其他内容。", "exact", "完成"},
        {"chinese_exact", "只回复两个汉字：确定。不要回复其他内容。", "exact", "确定"},
        {"chinese_exact", "只回复两个汉字：停止。不要回复其他内容。", "exact", "停止"},
        {"chinese_exact", "只回复两个汉字：开始。不要回复其他内容。", "exact", "开始"},
        {"chinese_exact", "只回复两个汉字：通过。不要回复其他内容。", "exact", "通过"},
        {"chinese_exact", "只回复两个汉字：失败。不要回复其他内容。", "exact", "失败"},
        {"chinese_exact", "只回复两个汉字：安全。不要回复其他内容。", "exact", "安全"},
        {"chinese_exact", "只回复两个汉字：继续。不要回复其他内容。", "exact", "继续"},
        {"chinese_exact", "只回复两个汉字：正确。不要回复其他内容。", "exact", "正确"},
        {"chinese_exact", "只回复两个汉字：结束。不要回复其他内容。", "exact", "结束"},
        {"arithmetic_exact", "Reply with the number only: 17 + 25 = ?", "exact", "42"},
        {"arithmetic_exact", "Reply with the number only: 13 + 29 = ?", "exact", "42"},
        {"arithmetic_exact", "Reply with the number only: 48 + 17 = ?", "exact", "65"},
        {"arithmetic_exact", "Reply with the number only: 36 + 27 = ?", "exact", "63"},
        {"arithmetic_exact", "Reply with the number only: 54 + 19 = ?", "exact", "73"},
        {"arithmetic_exact", "Reply with the number only: 28 + 44 = ?", "exact", "72"},
        {"arithmetic_exact", "Reply with the number only: 63 + 18 = ?", "exact", "81"},
        {"arithmetic_exact", "Reply with the number only: 75 + 26 = ?", "exact", "101"},
        {"arithmetic_exact", "Reply with the number only: 39 + 41 = ?", "exact", "80"},
        {"arithmetic_exact", "Reply with the number only: 88 + 12 = ?", "exact", "100"},
        {"strict_json", "Reply with JSON only, no markdown: {\"status\":\"ready\"}", "json", "status", "ready"},
        {"strict_json", "Reply with JSON only, no markdown: {\"mode\":\"local\"}", "json", "mode", "local"},
        {"strict_json", "Reply with JSON only, no markdown: {\"ok\":\"true\"}", "json", "ok", "true"},
        {"strict_json", "Reply with JSON only, no markdown: {\"count\":\"3\"}", "json", "count", "3"},
        {"strict_json", "Reply with JSON only, no markdown: {\"name\":\"tent\"}", "json", "name", "tent"},
        {"strict_json", "Reply with JSON only, no markdown: {\"level\":\"2\"}", "json", "level", "2"},
        {"strict_json", "Reply with JSON only, no markdown: {\"color\":\"green\"}", "json", "color", "green"},
        {"strict_json", "Reply with JSON only, no markdown: {\"state\":\"idle\"}", "json", "state", "idle"},
        {"strict_json", "Reply with JSON only, no markdown: {\"unit\":\"meter\"}", "json", "unit", "meter"},
        {"strict_json", "Reply with JSON only, no markdown: {\"flag\":\"off\"}", "json", "flag", "off"},
        {"list_three", "List exactly these three words, one per line: water, tent, map.", "lines", "water,tent,map"},
        {"list_three", "List exactly these three words, one per line: red, green, blue.", "lines", "red,green,blue"},
        {"list_three", "List exactly these three words, one per line: one, two, three.", "lines", "one,two,three"},
        {"list_three", "List exactly these three words, one per line: north, south, east.", "lines", "north,south,east"},
        {"list_three", "List exactly these three words, one per line: cat, dog, bird.", "lines", "cat,dog,bird"},
        {"list_three", "List exactly these three words, one per line: sun, moon, star.", "lines", "sun,moon,star"},
        {"list_three", "List exactly these three words, one per line: apple, pear, plum.", "lines", "apple,pear,plum"},
        {"list_three", "List exactly these three words, one per line: pen, book, bag.", "lines", "pen,book,bag"},
        {"list_three", "List exactly these three words, one per line: rain, snow, wind.", "lines", "rain,snow,wind"},
        {"list_three", "List exactly these three words, one per line: fork, knife, spoon.", "lines", "fork,knife,spoon"},
    };

    private boolean checkQualityCase(String[] item, String output) {
        String trimmed = output.trim();
        if ("exact".equals(item[2])) return trimmed.equals(item[3]);
        if ("json".equals(item[2])) {
            try {
                org.json.JSONObject value = new org.json.JSONObject(trimmed);
                return item[4].equals(value.optString(item[3]));
            } catch (Exception error) {
                return false;
            }
        }
        if ("lines".equals(item[2])) {
            String[] expected = item[3].split(",");
            String[] actual = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s*\\n\\s*");
            if (actual.length != expected.length) return false;
            for (int index = 0; index < expected.length; index++) if (!expected[index].equals(actual[index].trim())) return false;
            return true;
        }
        return false;
    }

    private void writeStress3(String phase, JSONObject value) {
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(getFilesDir(), "d3-" + phase + ".json"))) {
            output.write(value.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private String longPrompt(int sentences) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < sentences; index++) builder.append("Synthetic context sentence ").append(index).append(". ");
        return builder.append("Reply with exactly DONE.").toString();
    }

    private void runStress3(File model, String phase) throws Exception {
        String path = model.getPath();
        JSONObject report = new JSONObject().put("phase", phase);
        if ("long_context".equals(phase)) {
            String prompt = longPrompt(getIntent().getIntExtra("sentences", 180));
            report.put("prompt_chars", prompt.length());
            long started = SystemClock.elapsedRealtime();
            try {
                LocalModelProbe.prepare();
                String text = new String(LocalModelProbe.generate(path, prompt.getBytes(StandardCharsets.UTF_8), 32),
                    StandardCharsets.UTF_8);
                report.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                    .put("output_chars", text.length()).put("exact_done", "DONE".equals(text.trim()))
                    .put("output", text.length() > 120 ? text.substring(0, 120) : text);
            } catch (Exception error) {
                report.put("error", String.valueOf(error.getMessage())).put("prompt_chars", prompt.length());
            }
        } else if ("boundary".equals(phase)) {
            String prompt = longPrompt(300);
            report.put("prompt_chars", prompt.length());
            try {
                LocalModelProbe.prepare();
                String text = new String(LocalModelProbe.generate(path, prompt.getBytes(StandardCharsets.UTF_8), 16),
                    StandardCharsets.UTF_8);
                report.put("accepted", true).put("output_chars", text.length());
            } catch (Exception error) {
                report.put("accepted", false).put("error", String.valueOf(error.getMessage()));
            }
        } else if ("multi_turn".equals(phase)) {
            long started = SystemClock.elapsedRealtime();
            try {
                LocalModelProbe.prepare();
                String first = new String(LocalModelProbe.generate(path,
                    "Name one color and nothing else.".getBytes(StandardCharsets.UTF_8), 16), StandardCharsets.UTF_8).trim();
                LocalModelProbe.prepare();
                String second = new String(LocalModelProbe.generate(path,
                    ("You said: " + first + ". Reply with exactly DONE.").getBytes(StandardCharsets.UTF_8), 16),
                    StandardCharsets.UTF_8).trim();
                report.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                    .put("first", first.length() > 60 ? first.substring(0, 60) : first)
                    .put("second", second.length() > 60 ? second.substring(0, 60) : second);
            } catch (Exception error) {
                report.put("error", String.valueOf(error.getMessage()));
            }
        } else if ("concurrent".equals(phase)) {
            final String[] results = new String[2];
            final long[] elapsed = new long[2];
            Thread[] workers = new Thread[2];
            for (int index = 0; index < 2; index++) {
                final int slot = index;
                workers[index] = new Thread(() -> {
                    long begin = SystemClock.elapsedRealtime();
                    try {
                        LocalModelProbe.prepare();
                        byte[] raw = LocalModelProbe.generate(path,
                            ("Reply with exactly DONE. Request " + slot).getBytes(StandardCharsets.UTF_8), 16);
                        results[slot] = new String(raw, StandardCharsets.UTF_8).trim();
                    } catch (Exception error) {
                        results[slot] = "<error: " + error.getMessage() + ">";
                    }
                    elapsed[slot] = SystemClock.elapsedRealtime() - begin;
                }, "d3-concurrent-" + index);
            }
            for (Thread worker : workers) worker.start();
            for (Thread worker : workers) worker.join(60000);
            boolean finished = true;
            for (Thread worker : workers) if (worker.isAlive()) finished = false;
            report.put("both_finished", finished)
                .put("first_output", results[0] == null ? "" : results[0])
                .put("second_output", results[1] == null ? "" : results[1])
                .put("first_ms", elapsed[0]).put("second_ms", elapsed[1]);
        } else if ("cancel".equals(phase)) {
            final String[] cancelled = new String[1];
            final long[] cancelElapsed = new long[1];
            Thread worker = new Thread(() -> {
                long begin = SystemClock.elapsedRealtime();
                try {
                    LocalModelProbe.prepare();
                    byte[] raw = LocalModelProbe.generate(path,
                        ("Write a long synthetic paragraph about tents. " + longPrompt(120)).getBytes(StandardCharsets.UTF_8), 96);
                    cancelled[0] = new String(raw, StandardCharsets.UTF_8);
                } catch (Exception error) {
                    cancelled[0] = "<error: " + error.getMessage() + ">";
                }
                cancelElapsed[0] = SystemClock.elapsedRealtime() - begin;
            }, "d3-cancel");
            worker.start();
            Thread.sleep(500);
            LocalModelProbe.cancel();
            worker.join(30000);
            report.put("returned_after_cancel", !worker.isAlive())
                .put("elapsed_ms", cancelElapsed[0])
                .put("output_chars", cancelled[0] == null ? -1 : cancelled[0].length())
                .put("output", cancelled[0] == null ? "" : (cancelled[0].length() > 80 ? cancelled[0].substring(0, 80) : cancelled[0]));
        } else {
            throw new IllegalArgumentException("unknown stress phase");
        }
        writeStress3(phase, report);
        Log.i("AgentOsModelQuality", "D3_DONE " + phase);
    }

    private void runQuality50(File model) throws Exception {
        JSONArray results = new JSONArray();
        java.util.LinkedHashMap<String, int[]> byCategory = new java.util.LinkedHashMap<>();
        int passed = 0;
        for (String[] item : QUALITY_CASES) {
            String category = item[0];
            String output;
            long startedAt = SystemClock.elapsedRealtime();
            try {
                LocalModelProbe.prepare();
                output = new String(LocalModelProbe.generate(model.getPath(), item[1].getBytes(StandardCharsets.UTF_8), 96),
                    StandardCharsets.UTF_8);
            } catch (Exception error) {
                output = "<error: " + error.getMessage() + ">";
            }
            long elapsedMs = SystemClock.elapsedRealtime() - startedAt;
            boolean ok = checkQualityCase(item, JsonText.stripThink(output));
            if (ok) passed++;
            int[] counts = byCategory.computeIfAbsent(category, ignored -> new int[2]);
            counts[1]++;
            if (ok) counts[0]++;
            results.put(new JSONObject().put("category", category).put("prompt", item[1])
                .put("expected", item.length > 4 ? item[3] + "=" + item[4] : item[3])
                .put("output", output.length() > 200 ? output.substring(0, 200) : output)
                .put("elapsed_ms", elapsedMs).put("passed", ok));
        }
        JSONObject categories = new JSONObject();
        for (java.util.Map.Entry<String, int[]> entry : byCategory.entrySet())
            categories.put(entry.getKey(), entry.getValue()[0] + "/" + entry.getValue()[1]);
        JSONObject summary = new JSONObject().put("passed", passed).put("total", QUALITY_CASES.length)
            .put("by_category", categories).put("cases", results);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(getFilesDir(), "quality50.json"))) {
            output.write(summary.toString().getBytes(StandardCharsets.UTF_8));
        }
        Log.i("AgentOsModelQuality", "QUALITY50_DONE " + passed + "/" + QUALITY_CASES.length + " " + categories);
    }

    private static final String[][] JSON_CASES = {
        {"plan", "Make a JSON object with keys title and steps (array of 2 strings) for a camping trip.", "title,steps"},
        {"memory", "Return JSON with keys kind and content for a preference about concise checklists.", "kind,content"},
        {"error", "Return JSON with keys error and retry (boolean) for a failed calendar write.", "error,retry"},
        {"count", "Return JSON with keys total (number) and items (array of three short words).", "total,items"},
        {"schedule", "Return JSON with keys start and end as ISO dates for tomorrow 9-10am.", "start,end"},
        {"summary", "Return JSON with keys summary (one sentence) and tags (array of two words).", "summary,tags"},
        {"device", "Return JSON with keys device and available (boolean) for this phone.", "device,available"},
        {"route", "Return JSON with keys to (string) and reason (string) for opening the calendar.", "to,reason"},
        {"conflict", "Return JSON with keys conflict (boolean) and choices (array of two strings).", "conflict,choices"},
        {"empty", "Return JSON with keys ok (boolean) and note (string) when nothing needs doing.", "ok,note"}
    };

    private void runJson2(File model) throws Exception {
        JSONArray results = new JSONArray();
        int passed = 0;
        for (String[] item : JSON_CASES) {
            String prompt = JsonText.schemaPrompt(item[1],
                "an object with the requested keys", "{\"title\":\"camping\",\"steps\":[\"pack\",\"go\"]}");
            String output;
            boolean ok = false;
            int attempts = 0;
            long startedAt = SystemClock.elapsedRealtime();
            String last = "";
            while (attempts < 2 && !ok) {
                attempts++;
                try {
                    LocalModelProbe.prepare();
                    last = new String(LocalModelProbe.generate(model.getPath(), prompt.getBytes(StandardCharsets.UTF_8), 128),
                        StandardCharsets.UTF_8);
                    org.json.JSONObject parsed = StructuredOutput.parse(last);
                    ok = StructuredOutput.missingKeys(parsed, item[2].split(",")).isEmpty();
                } catch (Exception error) {
                    last = "<error: " + error.getMessage() + ">";
                }
                if (!ok) prompt = JsonText.schemaPrompt(item[1] + " Return only the JSON object, nothing else.",
                    "an object with the requested keys", "{\"title\":\"camping\",\"steps\":[\"pack\",\"go\"]}");
            }
            if (ok) passed++;
            results.put(new JSONObject().put("case", item[0]).put("required_keys", item[2]).put("attempts", attempts)
                .put("output", last.length() > 200 ? last.substring(0, 200) : last)
                .put("elapsed_ms", SystemClock.elapsedRealtime() - startedAt).put("passed", ok));
        }
        JSONObject summary = new JSONObject().put("passed", passed).put("total", JSON_CASES.length)
            .put("mode", "schema prompt + parse + one retry").put("criterion", "valid JSON object containing the required keys")
            .put("cases", results);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(getFilesDir(), "json2.json"))) {
            output.write(summary.toString().getBytes(StandardCharsets.UTF_8));
        }
        Log.i("AgentOsModelQuality", "JSON2_DONE " + passed + "/" + JSON_CASES.length);
    }

    private JSONObject telemetry() throws Exception {
        android.content.Intent battery = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        return new JSONObject().put("battery_temperature_tenths_c", battery == null ? -1 : battery.getIntExtra("temperature", -1))
            .put("battery_level_percent", battery == null ? -1 : battery.getIntExtra("level", -1))
            .put("plugged", battery == null ? -1 : battery.getIntExtra("plugged", -1))
            .put("thermal_status", power == null ? -1 : power.getCurrentThermalStatus())
            .put("process_pss_kib", android.os.Debug.getPss());
    }

    private void runStress(File model) throws Exception {
        JSONArray results = new JSONArray();
        JSONObject before = telemetry();
        String stopReason = "completed";
        java.util.concurrent.atomic.AtomicBoolean sampling = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicLong peakPss = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicInteger samples = new java.util.concurrent.atomic.AtomicInteger();
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                long pss = android.os.Debug.getPss();
                if (pss > 0) { peakPss.accumulateAndGet(pss, Math::max); samples.incrementAndGet(); }
                try { Thread.sleep(500); } catch (InterruptedException ignored) { break; }
            }
        }, "synthetic-model-pss-sampler");
        sampler.start();
        try {
            for (int round = 0; round < 5; round++) {
                for (String[] item : CASES) {
                    JSONObject current = telemetry();
                    if (current.optInt("thermal_status", -1) >= android.os.PowerManager.THERMAL_STATUS_SEVERE
                            || current.optInt("battery_temperature_tenths_c", -1) >= 420) {
                        stopReason = "thermal_guard";
                        break;
                    }
                    LocalModelProbe.prepare();
                    long start = SystemClock.elapsedRealtime();
                    byte[] raw = LocalModelProbe.generate(model.getPath(), item[1].getBytes(StandardCharsets.UTF_8), 96);
                    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw));
                    results.put(new JSONObject().put("round", round + 1).put("case", item[0])
                        .put("elapsed_ms", SystemClock.elapsedRealtime() - start).put("output_bytes", raw.length)
                        .put("after", telemetry()));
                }
                if (!"completed".equals(stopReason)) break;
            }
        } finally {
            sampling.set(false);
            sampler.interrupt();
            sampler.join();
        }
        JSONObject result = new JSONObject().put("before", before).put("results", results)
            .put("stop_reason", stopReason).put("after", telemetry())
            .put("sampled_peak_pss_kib", peakPss.get()).put("pss_sample_count", samples.get())
            .put("pss_sampling_interval_ms", 500);
        try (java.io.FileOutputStream output = openFileOutput("synthetic-stress-result.json", 0)) {
            output.write(result.toString().getBytes(StandardCharsets.UTF_8));
        }
        Log.i("AgentOsModelQuality", "STRESS_RESULT_READY");
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try {
                File model = new File(getFilesDir(), "synthetic-evaluation-model.gguf");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (FileInputStream input = new FileInputStream(model)) {
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                }
                StringBuilder hash = new StringBuilder();
                for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
                String requested = getIntent().getStringExtra("model");
                String expected = "qwen15".equals(requested) ? LARGE_SHA
                    : "minicpm".equals(requested) ? MINICPM_SHA : SMALL_SHA;
                if (!expected.equals(hash.toString())) throw new SecurityException("Model digest mismatch");
                if ("STRESS3".equals(getIntent().getAction())) {
                    runStress3(model, getIntent().getStringExtra("phase"));
                    return;
                }
                if ("JSON2".equals(getIntent().getAction())) {
                    runJson2(model);
                    return;
                }
                if ("QUALITY50".equals(getIntent().getAction())) {
                    runQuality50(model);
                    return;
                }
                if ("STRESS".equals(getIntent().getAction())) {
                    runStress(model);
                    return;
                }
                JSONArray results = new JSONArray();
                for (String[] item : CASES) {
                    LocalModelProbe.prepare();
                    long start = SystemClock.elapsedRealtime();
                    byte[] raw = LocalModelProbe.generate(model.getPath(), item[1].getBytes(StandardCharsets.UTF_8), 96);
                    String response = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString().trim();
                    boolean passed;
                    switch (item[0]) {
                        case "english_exact": passed = response.equals("READY"); break;
                        case "chinese_exact": passed = response.equals("完成"); break;
                        case "arithmetic_exact": passed = response.equals("42"); break;
                        case "strict_json":
                            passed = response.equals("{\"status\":\"ready\"}");
                            break;
                        default:
                            String[] lines = response.toLowerCase(Locale.ROOT).split("\\r?\\n");
                            passed = lines.length == 3 && response.toLowerCase(Locale.ROOT).contains("water")
                                && response.toLowerCase(Locale.ROOT).contains("tent") && response.toLowerCase(Locale.ROOT).contains("map");
                    }
                    results.put(new JSONObject().put("case", item[0]).put("passed", passed)
                        .put("elapsed_ms", SystemClock.elapsedRealtime() - start).put("output_bytes", raw.length));
                }
                Log.i("AgentOsModelQuality", "RESULT " + results);
            } catch (Throwable error) {
                Log.e("AgentOsModelQuality", "FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }, "synthetic-model-quality").start();
    }
}
