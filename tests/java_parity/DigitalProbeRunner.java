package org.traceform.edge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs ReferenceDigitalProbe.synthesize on samples from a tab-separated file and prints one line per
 * sample: "key=value" fields (full-precision doubles) in map order, or "#ERROR ..." on a failure.
 *
 * Input, per sample:
 *   H, screenState, wifiApCount, bluetoothDumpAvailable (1/0), bluetoothDeviceCount, eventCount
 *   followed by eventCount lines  E, type, "N" (no package) or "S" + package
 * eventCount -1 means the usagestats dump is unavailable (null event list).
 */
public final class DigitalProbeRunner {
    public static void main(String[] args) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < lines.size()) {
            String[] head = lines.get(index++).split("\t", -1);
            int count = Integer.parseInt(head[5]);
            List<String[]> events = null;
            if (count >= 0) {
                events = new ArrayList<String[]>();
                for (int k = 0; k < count; k++) {
                    String[] event = lines.get(index++).split("\t", -1);
                    events.add(new String[]{event[1], "N".equals(event[2]) ? null : event[2].substring(1)});
                }
            }
            try {
                Map<String, Object> result = ReferenceDigitalProbe.synthesize(head[1], events,
                    Integer.parseInt(head[2]), "1".equals(head[3]), Double.parseDouble(head[4]));
                boolean first = true;
                for (Map.Entry<String, Object> entry : result.entrySet()) {
                    if (!(entry.getValue() instanceof Double)) throw new IllegalStateException("not a Double: " + entry.getKey());
                    out.append(first ? "" : "\t").append(entry.getKey()).append('=').append(entry.getValue());
                    first = false;
                }
                out.append('\n');
            } catch (RuntimeException failure) {
                out.append("#ERROR ").append(failure).append('\n');
            }
        }
        System.out.print(out);
    }
}
