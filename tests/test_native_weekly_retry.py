from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeWeeklyRetry(unittest.TestCase):
    def test_transient_lock_does_not_complete_week(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            # Platform stand-ins let the real JobService control flow run on a host.
            # This checks retry/receipt decisions, not Android scheduler behavior.
            sources = {
                'android/content/Context.java': '''package android.content; import java.io.*; public class Context { public static File root; public File getFilesDir(){return root;} public File getCacheDir(){return root;} public File getDatabasePath(String name){return new File(root,name);} public <T>T getSystemService(Class<T> type){return type.cast(new android.app.job.JobScheduler());} }''',
                'android/content/ComponentName.java': 'package android.content; public class ComponentName { public ComponentName(Context c,Class<?> type){} }',
                'android/os/Looper.java': 'package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }',
                'android/os/Handler.java': 'package android.os; public class Handler { public Handler(Looper l){} public void post(Runnable r){r.run();} }',
                'android/os/PersistableBundle.java': 'package android.os; public class PersistableBundle { public void putString(String k,String v){} public String getString(String k){return null;} }',
                'android/app/job/JobParameters.java': 'package android.app.job; public class JobParameters {}',
                'android/app/job/JobService.java': '''package android.app.job; public class JobService extends android.content.Context { public boolean retry; public final java.util.concurrent.CountDownLatch finished=new java.util.concurrent.CountDownLatch(1); public boolean onStartJob(JobParameters p){return false;} public boolean onStopJob(JobParameters p){return false;} public void onDestroy(){} public void jobFinished(JobParameters p,boolean retry){this.retry=retry;finished.countDown();} }''',
                'android/app/job/JobScheduler.java': 'package android.app.job; public class JobScheduler { public static final int RESULT_SUCCESS=1; public JobInfo getPendingJob(int id){return null;} public int schedule(JobInfo job){return 1;} }',
                'android/app/job/JobInfo.java': '''package android.app.job; public class JobInfo { public android.os.PersistableBundle getExtras(){return new android.os.PersistableBundle();} public static class Builder { public Builder(int id,android.content.ComponentName c){} public Builder setMinimumLatency(long n){return this;} public Builder setPersisted(boolean b){return this;} public Builder setExtras(android.os.PersistableBundle b){return this;} public JobInfo build(){return new JobInfo();} } }''',
                'android/database/sqlite/SQLiteDatabase.java': '''package android.database.sqlite; public class SQLiteDatabase implements AutoCloseable { public static final int OPEN_READONLY=1, OPEN_READWRITE=0; public static SQLiteDatabase openDatabase(String p,Object f,int flags){return new SQLiteDatabase();} public void close(){} }''',
                'android/database/sqlite/SQLiteDatabaseLockedException.java': 'package android.database.sqlite; public class SQLiteDatabaseLockedException extends RuntimeException {}',
                'android/util/Log.java': 'package android.util; public class Log { public static int e(String t,String m,Throwable e){return 0;} }',
                'android/util/AtomicFile.java': '''package android.util; public class AtomicFile { private java.io.File file; public AtomicFile(java.io.File f){file=f;} public java.io.File getBaseFile(){return file;} public java.io.FileInputStream openRead()throws Exception{return new java.io.FileInputStream(file);} }''',
                'org/json/JSONObject.java': '''package org.json; public class JSONObject { public JSONObject(){} public JSONObject(String s){} private final java.util.Map<String,Object> values=new java.util.HashMap<>(); public JSONObject put(String k,Object v){values.put(k,v);return this;} public Object remove(String k){return values.remove(k);} public String optString(String k,String d){return values.containsKey(k)?String.valueOf(values.get(k)):d;} public boolean has(String k){return values.containsKey(k);} public boolean getBoolean(String k){return Boolean.TRUE.equals(values.get(k));} }''',
                'com/inceptless/traceweave/edge/RetryTest.java': '''package com.inceptless.traceweave.edge;
import java.io.*; import java.util.concurrent.*;
public class RetryTest {
    static RuntimeException failure, activationFailure;
    static int writes;
    static boolean recover;
    static org.json.JSONObject last;
    public static void main(String[] args)throws Exception {
        android.content.Context.root=new File(args[0]);
        new File(args[0],"edge.sqlite").createNewFile();
        for(RuntimeException error:new RuntimeException[]{new SecurityException("model registry busy"),new android.database.sqlite.SQLiteDatabaseLockedException()}) {
            failure=error; writes=0;
            WeeklyMaintenanceJob job=new WeeklyMaintenanceJob();
            job.onStartJob(new android.app.job.JobParameters());
            if(!job.finished.await(5,TimeUnit.SECONDS))throw new AssertionError("job did not finish");
            if(!job.retry || writes!=0)throw new AssertionError("transient lock completed the week");
            job.onDestroy();
        }
        failure=null; writes=0;
        WeeklyMaintenanceJob job=new WeeklyMaintenanceJob();
        job.onStartJob(new android.app.job.JobParameters());
        if(!job.finished.await(5,TimeUnit.SECONDS))throw new AssertionError("activation job did not finish");
        if(job.retry || writes!=1 || !last.getBoolean("candidate_activated"))
            throw new AssertionError("accepted weekly candidate was not activated or receipt overwrote activation");
        job.onDestroy();
        for(RuntimeException error:new RuntimeException[]{new IllegalArgumentException("capacity full"),new SecurityException("registry busy"),new android.database.sqlite.SQLiteDatabaseLockedException()}) {
            activationFailure=error; writes=0;
            job=new WeeklyMaintenanceJob(); job.onStartJob(new android.app.job.JobParameters());
            if(!job.finished.await(5,TimeUnit.SECONDS))throw new AssertionError("admission failure job did not finish");
            boolean transientFailure=!(error instanceof IllegalArgumentException);
            if(job.retry!=transientFailure || writes!=(transientFailure?0:1))throw new AssertionError("admission failure retry or receipt mismatch");
            if(!transientFailure && (!last.getBoolean("accepted") || last.getBoolean("candidate_activated")
                    || !last.optString("activation_pending","").equals("lifecycle_admission_failed")))
                throw new AssertionError("capacity refusal marked activated or discarded accepted candidate");
            job.onDestroy();
        }
        recover=true; failure=new IllegalArgumentException("fit must not run on publication recovery"); writes=0;
        job=new WeeklyMaintenanceJob(); job.onStartJob(new android.app.job.JobParameters());
        if(!job.finished.await(5,TimeUnit.SECONDS))throw new AssertionError("recovery job did not finish");
        if(job.retry || writes!=1 || !last.getBoolean("candidate_activated") || !last.getBoolean("activation_recovered"))
            throw new AssertionError("published window recovery ran fitting or lost activation");
        job.onDestroy();
    }
}
class WeeklyMaintenanceClock {
    static class Window { String key="fixture-sunday"; long startMillis=0; boolean contains(long now){return true;} }
    static Window next(long now,java.time.ZoneId zone,String done){return new Window();}
}
class ModelEngine { String modelVersion="fixture"; ModelEngine(android.content.Context c){} }
class NativeModelRegistry { NativeModelRegistry(File f,ModelEngine m){} ModelEngine active(){return new ModelEngine(null);} org.json.JSONObject completedWeeklyActivation(String window){return RetryTest.recover?new org.json.JSONObject().put("accepted",true).put("candidate_activated",true).put("activation_recovered",true):null;} String activateWeeklyWithHistory(File f,String digest,String expected,android.database.sqlite.SQLiteDatabase db,String window)throws Exception{if(RetryTest.activationFailure!=null)throw RetryTest.activationFailure;return "updated";} }
class WeeklySnapshot implements AutoCloseable {
    boolean warmIncluded=true;
    android.database.sqlite.SQLiteDatabase database=new android.database.sqlite.SQLiteDatabase();
    static WeeklySnapshot copy(android.database.sqlite.SQLiteDatabase s,File f,long t){return new WeeklySnapshot();}
    public void close(){}
}
class NativeCandidateCalibration { static org.json.JSONObject run(android.database.sqlite.SQLiteDatabase d,ModelEngine m,File f){if(RetryTest.failure!=null)throw RetryTest.failure; return new org.json.JSONObject().put("accepted",true);} }
class NativeColdAudit { static org.json.JSONObject run(android.database.sqlite.SQLiteDatabase d){return new org.json.JSONObject();} }
class NativeWarmAudit { static org.json.JSONObject run(android.database.sqlite.SQLiteDatabase d){if(RetryTest.failure!=null)throw RetryTest.failure; return new org.json.JSONObject().put("valid",true);} }
class NativeCandidateStore { static String save(File f,ModelEngine m,org.json.JSONObject r){return "candidate";} }
class ColdLineage { static void migrateOnce(android.database.sqlite.SQLiteDatabase d){} }
class NativeVerificationJob { static void write(File f,org.json.JSONObject r){RetryTest.writes++; RetryTest.last=r;} }
''',
            }
            paths = []
            for name, source in sources.items():
                path = base / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source)
                paths.append(str(path))
            paths += [str(root / 'android/src/com/inceptless/traceweave/edge' / name) for name in ['WeeklyMaintenanceJob.java', 'AlignmentQueryLease.java']]
            compile_result = subprocess.run(['javac', '-d', directory, *paths], capture_output=True, text=True)
            self.assertEqual(compile_result.returncode, 0, compile_result.stderr)
            result = subprocess.run(['java', '-cp', directory, 'com.inceptless.traceweave.edge.RetryTest', directory], capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr)
