from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


class TestNativeUsageSource(unittest.TestCase):
    def test_recent_event_window_caps_and_rollover(self):
        jar = Path(os.environ.get('TRACEFORM_ANDROID_JAR', '/private/tmp/traceform-android-tools/platform/android-35/android.jar'))
        if not jar.is_file() or not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Android API jar and Java compiler/runtime required')
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory) / 'UsageTest.java'
            harness.write_text('''package org.traceform.edge;
public class UsageTest {
    public static void main(String[] args) {
        NativeUsageSource.Window w=new NativeUsageSource.Window();
        if(w.counts().resumes!=0 || w.counts().interactiveChecks!=0)throw new AssertionError("available empty window");
        for(int i=0;i<80;i++)w.add(1);
        if(w.counts().resumes!=50)throw new AssertionError("resume cap");
        for(int i=0;i<40;i++)w.add(i%2==0?15:18);
        if(w.counts().interactiveChecks!=20)throw new AssertionError("interactive cap");
        for(int i=0;i<100;i++)w.add(2);
        if(w.counts().resumes!=0 || w.counts().interactiveChecks!=20)throw new AssertionError("rolling tail");
        for(int i=0;i<20;i++)w.add(2);
        if(w.counts().resumes!=0 || w.counts().interactiveChecks!=0)throw new AssertionError("all prior events expired");
        w.add(1);w.add(15);w.add(18);
        if(w.counts().resumes!=1 || w.counts().interactiveChecks!=2)throw new AssertionError("event meanings");
    }
}''')
            subprocess.run(['javac', '-cp', str(jar), '-d', directory, str(root / 'android/src/org/traceform/edge/NativeUsageSource.java'), str(harness)], check=True, capture_output=True)
            subprocess.run(['java', '-cp', directory + os.pathsep + str(jar), 'org.traceform.edge.UsageTest'], check=True, capture_output=True)

    def test_cache_permission_withdrawal_reset_and_clock_change(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        root = Path(__file__).resolve().parents[1]
        sources = {
            'android/app/AppOpsManager.java': 'package android.app; public class AppOpsManager {public static final String OPSTR_GET_USAGE_STATS="usage"; public static final int MODE_ALLOWED=0; public int mode=0; public int checkOpNoThrow(String op,int uid,String pkg){return mode;}}',
            'android/os/Process.java': 'package android.os; public class Process {public static int myUid(){return 1;}}',
            'android/content/Context.java': 'package android.content; public class Context {public final android.app.AppOpsManager ops=new android.app.AppOpsManager(); public final android.app.usage.UsageStatsManager usage=new android.app.usage.UsageStatsManager(); public <T> T getSystemService(Class<T> type){return type.cast(type==android.app.AppOpsManager.class?ops:usage);} public String getPackageName(){return "fixture";}}',
            'android/app/usage/UsageStatsManager.java': 'package android.app.usage; public class UsageStatsManager {public int calls; public UsageEvents queryEvents(long begin,long end){calls++;return new UsageEvents();}}',
            'android/app/usage/UsageEvents.java': 'package android.app.usage; public class UsageEvents {public boolean hasNextEvent(){return false;} public boolean getNextEvent(Event e){return false;} public static class Event {public static final int ACTIVITY_RESUMED=1,SCREEN_INTERACTIVE=15,KEYGUARD_HIDDEN=18; public long getTimeStamp(){return 0;} public int getEventType(){return 0;}}}',
            'org/traceform/edge/UsageCacheTest.java': '''package org.traceform.edge;
public class UsageCacheTest {
 public static void main(String[] args) {
  android.content.Context c=new android.content.Context();NativeUsageSource s=new NativeUsageSource();
  long wall=100000000L,nanos=1000000000L;
  NativeUsageSource.Counts first=s.sample(c,wall,nanos);
  if(first.resumes!=0 || c.usage.calls!=1)throw new AssertionError("initial available source");
  if(s.sample(c,wall+100,nanos+100000000L).timestampNanos!=nanos || c.usage.calls!=1)throw new AssertionError("cache timestamp");
  c.ops.mode=1;
  if(!Double.isNaN(s.sample(c,wall+200,nanos+200000000L).resumes))throw new AssertionError("permission withdrawal returned cached data");
  c.ops.mode=0;s.sample(c,wall+300,nanos+300000000L);
  if(c.usage.calls!=2)throw new AssertionError("regrant fresh source");
  s.reset();s.sample(c,wall+400,nanos+400000000L);
  if(c.usage.calls!=3)throw new AssertionError("collection reset");
  s.sample(c,wall+100000,nanos+500000000L);
  if(c.usage.calls!=4)throw new AssertionError("wall jump retained old cache");
  s.sample(c,wall+100100,nanos-1);
  if(c.usage.calls!=5)throw new AssertionError("monotonic rollback retained cache");
 }
}''',
        }
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for name, content in sources.items():
                path = Path(directory) / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
                paths.append(str(path))
            subprocess.run(['javac', '-d', directory, str(root / 'android/src/org/traceform/edge/NativeUsageSource.java'), *paths], check=True, capture_output=True)
            result = subprocess.run(['java', '-cp', directory, 'org.traceform.edge.UsageCacheTest'], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
