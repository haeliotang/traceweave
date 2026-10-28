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
    private static final String SHA = "7671c0c304e6ce5a7fc577bcb12aba01e2c155cc2efd29b2213c95b18edaf6ed";
    private static final String[][] CASES = {
        {"english_exact", "Reply with exactly READY and no other words."},
        {"chinese_exact", "只回复两个汉字：完成。不要回复其他内容。"},
        {"arithmetic_exact", "Reply with the number only: 17 + 25 = ?"},
        {"strict_json", "Reply with JSON only, no markdown: {\"status\":\"ready\"}"},
        {"list_three", "List exactly these three words, one per line: water, tent, map."}
    };

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
                if (!SHA.equals(hash.toString())) throw new SecurityException("Model digest mismatch");
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
