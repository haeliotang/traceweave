import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    'android/app/AppOpsManager.java': 'package android.app; public class AppOpsManager {public static final String OPSTR_GET_USAGE_STATS="usage"; public static final int MODE_ALLOWED=0; public int checkOpNoThrow(String op,int uid,String pkg){return 0;}}',
    'android/os/Process.java': 'package android.os; public class Process {public static int myUid(){return 1;}}',
    'android/content/Context.java': 'package android.content; public class Context {public <T> T getSystemService(Class<T> type){return null;} public String getPackageName(){return "fixture";}}',
    'android/app/usage/UsageStatsManager.java': 'package android.app.usage; public class UsageStatsManager {public UsageEvents queryEvents(long b,long e){return new UsageEvents();}}',
    'android/app/usage/UsageEvents.java': 'package android.app.usage; public class UsageEvents {public boolean hasNextEvent(){return false;} public boolean getNextEvent(Event e){return false;} public static class Event {public static final int ACTIVITY_RESUMED=1,SCREEN_INTERACTIVE=15,KEYGUARD_HIDDEN=18; public long getTimeStamp(){return 0;} public int getEventType(){return 0;} public String getPackageName(){return null;}}}',
    'com/inceptless/traceweave/edge/UsageDigitalTest.java': '''package com.inceptless.traceweave.edge;
import java.util.Map;
public class UsageDigitalTest {
  static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
  public static void main(String[] args) {
    NativeUsageSource.Window window = new NativeUsageSource.Window();
    window.add(1, "pkg.a"); window.add(1, "pkg.b"); window.add(15, null); window.add(1, "pkg.a"); window.add(7, "pkg.c");
    NativeUsageSource.Counts counts = window.countsWithDigital();
    check(counts.resumes == 3.0 && counts.interactiveChecks == 1.0, "aggregate counts");
    Map<String, Object> on = counts.digital("ON");
    check((Double) on.get("app_switch_rate") == 3.0, "app switches");
    check((Double) on.get("unlock_burst_count") == 1.0, "unlocks");
    check((Double) on.get("screen_on_ratio") == 1.0, "screen on");
    check(Math.abs((Double) on.get("touch_interaction_velocity") - 2.5) < 1e-12, "touch velocity 1+3*0.4+1*0.3");
    check(Math.abs((Double) on.get("attention_fragmentation_entropy") - 0.918) < 1e-12, "entropy of 2:1 package split");
    Map<String, Object> off = counts.digital("OFF");
    check((Double) off.get("touch_interaction_velocity") == 0.0 && (Double) off.get("screen_on_ratio") == 0.0, "screen off");
    check((Double) counts.digital("DOZING").get("screen_on_ratio") == 0.2, "dozing");
    check(counts.digital("no such state") == counts.digital("UNKNOWN"), "unknown screen state falls back");
    check(new NativeUsageSource.Counts(Double.NaN, Double.NaN).digital("ON") == null, "no digital without usage stats");
    check(counts.at(99).digital("ON") == on && counts.at(99).timestampNanos == 99, "rebased counts keep digital");
    boolean immutable = false;
    try { on.put("x", 1.0); } catch (UnsupportedOperationException expected) { immutable = true; }
    check(immutable, "cached maps are read-only");
    for (int i = 0; i < 300; i++) window.add(1, "pkg." + (i % 7));
    check(window.countsWithDigital().resumes == 50.0, "window of 120 events, resumes capped at 50");
  }
}''',
}


class TestNativeUsageDigital(unittest.TestCase):
    def test_digital_context_is_derived_transiently_from_the_event_window(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for name, content in STUBS.items():
                path = Path(directory) / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
                paths.append(str(path))
            edge = ROOT / 'android/src/com/inceptless/traceweave/edge'
            compiled = subprocess.run(['javac', '-d', directory, str(edge / 'NativeUsageSource.java'),
                                       str(edge / 'ReferenceDigitalProbe.java'), str(edge / 'PyRuntime.java'), *paths],
                                      capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(['java', '-cp', directory, 'com.inceptless.traceweave.edge.UsageDigitalTest'],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
