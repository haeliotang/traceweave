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
                String text = new String(LocalModelProbe.generate(path, prompt.getBytes(StandardCharsets.UTF_8), 32, null),
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
                String text = new String(LocalModelProbe.generate(path, prompt.getBytes(StandardCharsets.UTF_8), 16, null),
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
                    "Name one color and nothing else.".getBytes(StandardCharsets.UTF_8), 16, null), StandardCharsets.UTF_8).trim();
                LocalModelProbe.prepare();
                String second = new String(LocalModelProbe.generate(path,
                    ("You said: " + first + ". Reply with exactly DONE.").getBytes(StandardCharsets.UTF_8), 16, null),
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
                            ("Reply with exactly DONE. Request " + slot).getBytes(StandardCharsets.UTF_8), 16, null);
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
                        ("Write a long synthetic paragraph about tents. " + longPrompt(120)).getBytes(StandardCharsets.UTF_8), 96, null);
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
                output = new String(LocalModelProbe.generate(model.getPath(), item[1].getBytes(StandardCharsets.UTF_8), 96, null),
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
                .put("output", output.length() > 2000 ? output.substring(0, 2000) : output)
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

    private static String jsonGrammarFor(String keys) {
        StringBuilder root = new StringBuilder("root ::= \"{\" ws ");
        String[] parts = keys.split(",");
        for (int index = 0; index < parts.length; index++) {
            if (index > 0) root.append("ws \",\" ws ");
            root.append("\"\\\"").append(parts[index]).append("\\\"\" ws \":\" ws value ws ");
        }
        root.append("\"}\"\n");
        return root
            + "value ::= array | string | number | \"true\" | \"false\"\n"
            + "array ::= \"[\" ws (value (ws \",\" ws value)*)? ws \"]\"\n"
            + "string ::= \"\\\"\" ( [^\"\\\\] | \"\\\\\" . )* \"\\\"\"\n"
            + "number ::= [0-9]+\n"
            + "ws ::= [ \\t\\n]*\n";
    }

    private void runJsonGrammar(File model) throws Exception {
        JSONArray results = new JSONArray();
        int passed = 0;
        for (String[] item : JSON_CASES) {
            String grammar = jsonGrammarFor(item[2]);
            String prompt = JsonText.schemaPrompt(item[1],
                "an object with the requested keys", "{\"title\":\"camping\",\"steps\":[\"pack\",\"go\"]}");
            boolean ok = false;
            String output = "";
            long startedAt = SystemClock.elapsedRealtime();
            try {
                LocalModelProbe.prepare();
                output = new String(LocalModelProbe.generate(model.getPath(), prompt.getBytes(StandardCharsets.UTF_8), 512, grammar),
                    StandardCharsets.UTF_8);
                org.json.JSONObject parsed = StructuredOutput.parse(JsonText.stripThink(output));
                ok = StructuredOutput.missingKeys(parsed, item[2].split(",")).isEmpty();
            } catch (Exception error) {
                output = "<error: " + error.getMessage() + ">";
            }
            if (ok) passed++;
            results.put(new JSONObject().put("case", item[0]).put("required_keys", item[2]).put("grammar", grammar)
                .put("output", output.length() > 2000 ? output.substring(0, 2000) : output)
                .put("elapsed_ms", SystemClock.elapsedRealtime() - startedAt).put("passed", ok));
        }
        JSONObject summary = new JSONObject().put("passed", passed).put("total", JSON_CASES.length)
            .put("mode", "GBNF grammar + schema prompt").put("criterion", "grammar-valid JSON containing the required keys")
            .put("cases", results);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(getFilesDir(), "json3.json"))) {
            output.write(summary.toString().getBytes(StandardCharsets.UTF_8));
        }
        Log.i("AgentOsModelQuality", "JSON3_DONE " + passed + "/" + JSON_CASES.length);
    }

    /** Content-level checks that the presence-only criterion cannot see. */
    private static java.util.List<String> contentIssues(String testCase, org.json.JSONObject parsed, String runDate) {
        java.util.List<String> issues = new java.util.ArrayList<>();
        try {
            switch (testCase) {
                case "error":
                    if (!(parsed.get("error") instanceof String)) issues.add("error is not a string");
                    if (!(parsed.get("retry") instanceof Boolean)) issues.add("retry is not a boolean");
                    break;
                case "count":
                    if (parsed.get("total") instanceof String) issues.add("total is a string");
                    if (!(parsed.get("items") instanceof org.json.JSONArray)) issues.add("items is not an array");
                    else if (parsed.getJSONArray("items").length() != 3) issues.add("items does not hold three entries");
                    break;
                case "schedule":
                    for (String key : new String[]{"start", "end"}) {
                        String value = parsed.optString(key, "");
                        if (!value.startsWith(runDate.substring(0, 4))) issues.add(key + " is not in " + runDate.substring(0, 4));
                    }
                    break;
                case "summary": {
                    String text = parsed.optString("summary", "");
                    if (text.length() < 20 || text.toLowerCase().contains("brief summary")) issues.add("summary looks like a placeholder");
                    org.json.JSONArray tags = parsed.optJSONArray("tags");
                    if (tags != null && tags.length() == 2 && tags.optString(0).equals(tags.optString(1))) issues.add("tags repeat the same word");
                    break;
                }
                case "empty":
                    if (parsed.optString("note", "").isEmpty()) issues.add("note is empty");
                    break;
                case "plan": {
                    org.json.JSONArray steps = parsed.optJSONArray("steps");
                    if (steps != null && steps.length() == 2 && steps.optString(0).equals("pack")
                        && steps.optString(1).equals("go")) issues.add("steps repeat the prompt example verbatim");
                    break;
                }
                default:
                    break;
            }
        } catch (Exception error) {
            issues.add("content check failed: " + error.getMessage());
        }
        return issues;
    }

    /** Grammar for a small schema spec: s=string, n=number, b=boolean, a=array of 2 strings, {..}=object. */
    private static String grammarFor(String schema) {
        java.util.LinkedHashMap<String, String> rules = new java.util.LinkedHashMap<>();
        emitObject("root", schema, rules);
        String rootRule = rules.remove("root");
        StringBuilder grammar = new StringBuilder();
        grammar.append("root ::= ").append(rootRule).append("\n");
        for (java.util.Map.Entry<String, String> rule : rules.entrySet()) {
            grammar.append(rule.getKey()).append(" ::= ").append(rule.getValue()).append("\n");
        }
        return grammar
            + "array ::= \"[\" ws string ws \",\" ws string ws \"]\"\n"
            + "string ::= \"\\\"\" [a-zA-Z0-9 _]* \"\\\"\"\n"
            + "number ::= [0-9]+\n"
            + "boolean ::= \"true\" | \"false\"\n"
            + "ws ::= [ \\t\\n]*\n";
    }

    private static void emitObject(String ruleName, String schema, java.util.LinkedHashMap<String, String> rules) {
        String body = schema.trim();
        if (body.startsWith("{")) body = body.substring(1, body.length() - 1);
        StringBuilder rhs = new StringBuilder("\"{\" ws ");
        java.util.List<String> parts = splitTopLevel(body);
        for (int index = 0; index < parts.size(); index++) {
            String part = parts.get(index).trim();
            int colon = part.indexOf(':');
            if (colon < 0) continue;
            String key = part.substring(0, colon).trim();
            String type = part.substring(colon + 1).trim();
            if (index > 0) rhs.append("\",\" ws ");
            rhs.append("\"\\\"").append(key).append("\\\"\" ws \":\" ws ");
            if (type.startsWith("{")) {
                String child = "sub" + (char) ('a' + (rules.size() % 26)) + (rules.size() / 26);
                emitObject(child, type, rules);
                rhs.append(child);
            } else {
                rhs.append(valueRule(type));
            }
            rhs.append(" ws ");
        }
        rhs.append("\"}\"");
        rules.put(ruleName, rhs.toString());
    }

    private static java.util.List<String> splitTopLevel(String body) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char character : body.toCharArray()) {
            if (character == '{') depth++;
            if (character == '}') depth--;
            if (character == ',' && depth == 0) { parts.add(current.toString()); current.setLength(0); continue; }
            current.append(character);
        }
        if (current.length() > 0) parts.add(current.toString());
        return parts;
    }

    private static String valueRule(String type) {
        if (type.equals("n")) return "number";
        if (type.equals("b")) return "boolean";
        if (type.equals("a")) return "array";
        return "string";
    }

    private static java.util.List<String> keysOf(String schema) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        String inner = schema.trim();
        if (inner.startsWith("{")) inner = inner.substring(1, inner.length() - 1);
        for (String part : splitTopLevel(inner)) {
            int colon = part.indexOf(':');
            if (colon < 0) continue;
            keys.add(part.substring(0, colon).trim());
        }
        return keys;
    }

    private static final String[][] UNSEEN_CASES = {
        {"nested_plan", "Return JSON with title, steps (two short words) and meta (object with author and count) for a reading plan.", "{title:s,steps:a,meta:{author:s,count:n}}"},
        {"nested_route", "Return JSON with to, reason and options (object with mode and minutes) for going to the library.", "{to:s,reason:s,options:{mode:s,minutes:n}}"},
        {"copy_token", "Return JSON with key code set to the value QX7 and note describing that it was copied.", "{code:s,note:s}"},
        {"flight", "Return JSON with flight, gate and boarding (object with hour and minute) for synthetic flight TF100.", "{flight:s,gate:s,boarding:{hour:n,minute:n}}"},
        {"inventory", "Return JSON with item, quantity and tags (two short words) for synthetic warehouse stock.", "{item:s,quantity:n,tags:a}"},
        {"receipt", "Return JSON with merchant, total and lines (object with count and currency) for a synthetic receipt.", "{merchant:s,total:n,lines:{count:n,currency:s}}"},
        {"meeting", "Return JSON with topic, attendees and room with a single word, for a synthetic standup.", "{topic:s,attendees:n,room:s}"},
        {"backup", "Return JSON with target, encrypted and size for a synthetic backup job.", "{target:s,encrypted:b,size:n}"},
        {"alert", "Return JSON with level, message and source for a synthetic disk alert.", "{level:s,message:s,source:s}"},
        {"profile", "Return JSON with name, verified and prefs (object with theme and font) for a synthetic profile.", "{name:s,verified:b,prefs:{theme:s,font:s}}"},
        {"shipment", "Return JSON with tracking, delivered and attempts for a synthetic parcel.", "{tracking:s,delivered:b,attempts:n}"},
        {"gateway", "Return JSON with host, port and healthy for a synthetic gateway.", "{host:s,port:n,healthy:b}"}
    };

    private void runUnseenJson(File model, boolean withSchemaPrompt) throws Exception {
        JSONArray results = new JSONArray();
        int passed = 0;
        for (String[] item : UNSEEN_CASES) {
            String grammar = grammarFor(item[2]);
            String prompt = withSchemaPrompt
                ? JsonText.schemaPrompt(item[1], "an object with the requested keys", null)
                : item[1];
            boolean ok = false;
            java.util.List<String> issues = new java.util.ArrayList<>();
            String output = "";
            try {
                LocalModelProbe.prepare();
                output = new String(LocalModelProbe.generate(model.getPath(), prompt.getBytes(StandardCharsets.UTF_8), 512, grammar),
                    StandardCharsets.UTF_8);
                org.json.JSONObject parsed = StructuredOutput.parse(JsonText.stripThink(output));
                java.util.List<String> missing = StructuredOutput.missingKeys(parsed, keysOf(item[2]).toArray(new String[0]));
                ok = missing.isEmpty();
                if (!ok) issues.add("missing: " + missing);
            } catch (Exception error) {
                issues.add("<error: " + error.getMessage() + ">");
                issues.add("grammar: " + grammar);
            }
            if (ok) passed++;
            results.put(new JSONObject().put("case", item[0]).put("schema", item[2]).put("passed", ok)
                .put("issues", new JSONArray(issues))
                .put("output", output.length() > 1200 ? output.substring(0, 1200) : output));
        }
        JSONObject summary = new JSONObject().put("passed", passed).put("total", UNSEEN_CASES.length)
            .put("schema_prompt", withSchemaPrompt)
            .put("criterion", "keys present (nested keys checked at the top level), grammar built from the case schema")
            .put("cases", results);
        String file = withSchemaPrompt ? "json5_schema.json" : "json5_plain.json";
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(new File(getFilesDir(), file))) {
            stream.write(summary.toString().getBytes(StandardCharsets.UTF_8));
        }
        Log.i("AgentOsModelQuality", "JSON5_DONE schema_prompt=" + withSchemaPrompt + " " + passed + "/" + UNSEEN_CASES.length);
    }

    private void runJsonContent(File model) throws Exception {
        JSONArray results = new JSONArray();
        int shapeOk = 0;
        int contentOk = 0;
        String runDate = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        for (String[] item : JSON_CASES) {
            String grammar = jsonGrammarFor(item[2]);
            String prompt = JsonText.schemaPrompt(item[1],
                "an object with the requested keys", "{\"title\":\"camping\",\"steps\":[\"pack\",\"go\"]}");
            boolean shape = false;
            java.util.List<String> issues = new java.util.ArrayList<>();
            String output = "";
            long startedAt = SystemClock.elapsedRealtime();
            try {
                LocalModelProbe.prepare();
                output = new String(LocalModelProbe.generate(model.getPath(), prompt.getBytes(StandardCharsets.UTF_8), 512, grammar),
                    StandardCharsets.UTF_8);
                org.json.JSONObject parsed = StructuredOutput.parse(JsonText.stripThink(output));
                shape = StructuredOutput.missingKeys(parsed, item[2].split(",")).isEmpty();
                if (shape) issues.addAll(contentIssues(item[0], parsed, runDate));
            } catch (Exception error) {
                issues.add("<error: " + error.getMessage() + ">");
            }
            if (shape) shapeOk++;
            if (shape && issues.isEmpty()) contentOk++;
            results.put(new JSONObject().put("case", item[0]).put("keys_present", shape)
                .put("content_issues", new JSONArray(issues))
                .put("output", output.length() > 2000 ? output.substring(0, 2000) : output)
                .put("elapsed_ms", SystemClock.elapsedRealtime() - startedAt));
        }
        JSONObject summary = new JSONObject().put("keys_present", shapeOk).put("content_ok", contentOk)
            .put("total", JSON_CASES.length).put("run_date", runDate)
            .put("criterion", "keys present (shape) vs keys present AND case-specific content checks passed")
            .put("cases", results);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(getFilesDir(), "json4.json"))) {
            output.write(summary.toString().getBytes(StandardCharsets.UTF_8));
        }
        Log.i("AgentOsModelQuality", "JSON4_DONE shape=" + shapeOk + "/" + JSON_CASES.length + " content=" + contentOk + "/" + JSON_CASES.length);
    }

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
                    last = new String(LocalModelProbe.generate(model.getPath(), prompt.getBytes(StandardCharsets.UTF_8), 512, null),
                        StandardCharsets.UTF_8);
                    org.json.JSONObject parsed = StructuredOutput.parse(JsonText.stripThink(last));
                    ok = StructuredOutput.missingKeys(parsed, item[2].split(",")).isEmpty();
                } catch (Exception error) {
                    last = "<error: " + error.getMessage() + ">";
                }
                if (!ok) prompt = JsonText.schemaPrompt(item[1] + " Return only the JSON object, nothing else.",
                    "an object with the requested keys", "{\"title\":\"camping\",\"steps\":[\"pack\",\"go\"]}");
            }
            if (ok) passed++;
            results.put(new JSONObject().put("case", item[0]).put("required_keys", item[2]).put("attempts", attempts)
                .put("output", last.length() > 2000 ? last.substring(0, 2000) : last)
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
                    byte[] raw = LocalModelProbe.generate(model.getPath(), item[1].getBytes(StandardCharsets.UTF_8), 96, null);
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
                if ("GRAMMARTEST".equals(getIntent().getAction())) {
                    String base = grammarFor("{title:s,steps:a,meta:{author:s,count:n}}");
                    String[] variants = {
                        base,
                        base.replace("string ::= \"\\\"\" [a-zA-Z0-9 _]* \"\\\"\"",
                                      "string ::= \"\\\"\" ( [^\"\\\\] | \"\\\\\" . )* \"\\\"\""),
                        base.replace("obj_", "sub_"),
                        base.replace("boolean ::= \"true\" | \"false\"\n", "").replace("array ::= \"[\" ws string ws \",\" ws string ws \"]\"\n", ""),
                    };
                    String inlined = "root ::= \"{\" ws \"\\\"title\\\"\" ws \":\" ws string ws \",\" ws \"\\\"meta\\\"\" ws \":\" ws "
                        + "\"{\" ws \"\\\"author\\\"\" ws \":\" ws string ws \"}\" ws \"}\"\n"
                        + "string ::= \"\\\"\" [a-zA-Z0-9 _]* \"\\\"\"\n"
                        + "ws ::= [ \\t\\n]*\n";
                    String alphabetic = base.replace("obj_", "subrule");
                    String[] more = {inlined, alphabetic};
                    for (int i = 0; i < more.length; i++) {
                        String result;
                        try {
                            LocalModelProbe.prepare();
                            LocalModelProbe.generate(model.getPath(), "say hi".getBytes(StandardCharsets.UTF_8), 1, more[i]);
                            result = "ok";
                        } catch (Exception error) {
                            result = "fail: " + error.getMessage();
                        }
                        Log.i("AgentOsModelQuality", "GRAMMARTEST B" + i + " -> " + result);
                    }
                    for (int i = 0; i < variants.length; i++) {
                        String result;
                        try {
                            LocalModelProbe.prepare();
                            LocalModelProbe.generate(model.getPath(), "say hi".getBytes(StandardCharsets.UTF_8), 1, variants[i]);
                            result = "ok";
                        } catch (Exception error) {
                            result = "fail: " + error.getMessage();
                        }
                        Log.i("AgentOsModelQuality", "GRAMMARTEST " + i + " -> " + result);
                    }
                    return;
                }
                if ("GRAMMAR".equals(getIntent().getAction())) {
                    for (String[] item : UNSEEN_CASES) {
                        Log.i("AgentOsModelQuality", "GRAMMAR " + item[0] + " >>>\n" + grammarFor(item[2]));
                    }
                    return;
                }
                if ("JSON5".equals(getIntent().getAction())) {
                    runUnseenJson(model, true);
                    return;
                }
                if ("JSON6".equals(getIntent().getAction())) {
                    runUnseenJson(model, false);
                    return;
                }
                if ("JSON4".equals(getIntent().getAction())) {
                    runJsonContent(model);
                    return;
                }
                if ("JSON3".equals(getIntent().getAction())) {
                    runJsonGrammar(model);
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
                    byte[] raw = LocalModelProbe.generate(model.getPath(), item[1].getBytes(StandardCharsets.UTF_8), 96, null);
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
