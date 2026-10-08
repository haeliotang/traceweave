package org.traceform.edge;

import android.app.ActivityManager;
import android.app.job.JobService;
import android.app.job.JobParameters;
import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import org.json.JSONObject;
import org.json.JSONArray;

/** Real allocation in a disposable child; production admission thresholds stay unchanged. */
public final class SyntheticMemoryPressureJob extends JobService {
    private volatile Process child;
    private volatile Thread worker;
    @Override public boolean onStartJob(JobParameters parameters) {
        if (worker != null) return false;
        worker = new Thread(() -> {
            JSONObject report = new JSONObject();
            try {
                String bits = new String(new char[6224]).replace('\0', '0');
                UniformPrivateAlignment.admit(this, bits);
                report.put("baseline_admitted", true);
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                child = new ProcessBuilder(new File(getApplicationInfo().nativeLibraryDir,
                    "libtraceform_memory_pressure.so").getAbsolutePath()).start();
                JSONArray samples = new JSONArray();
                boolean rejected = false;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream()));
                     OutputStreamWriter writer = new OutputStreamWriter(child.getOutputStream())) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        JSONObject sample = new JSONObject(line);
                        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                        getSystemService(ActivityManager.class).getMemoryInfo(memory);
                        sample.put("available_bytes", memory.availMem).put("threshold_bytes", memory.threshold)
                            .put("low_memory", memory.lowMemory);
                        try { UniformPrivateAlignment.admit(this, bits); }
                        catch (IllegalStateException error) {
                            if (!"private alignment memory admission rejected".equals(error.getMessage())) throw error;
                            rejected = true;
                        }
                        sample.put("admission_rejected", rejected); samples.put(sample);
                        if (rejected) { writer.write("RELEASE\n"); writer.flush(); break; }
                        writer.write("GROW\n"); writer.flush();
                    }
                }
                int exit = child.waitFor(); child = null;
                boolean recovered = false;
                for (int attempt = 0; attempt < 30 && !recovered; ++attempt) {
                    try { UniformPrivateAlignment.admit(this, bits); recovered = true; }
                    catch (IllegalStateException error) { Thread.sleep(1000); }
                }
                report.put("samples", samples).put("pressure_child_exit", exit)
                    .put("real_pressure_admission_rejected", rejected).put("readmitted_after_release", recovered)
                    .put("passed", rejected && recovered && exit == 0)
                    .put("running_low_memory_withdrawal_verified", false)
                    .put("scope", "bounded_real_allocation_admission_and_recovery_not_running_MPC_or_system_LMK");
            } catch (Exception error) {
                try { report.put("passed", false).put("error", error.toString()); } catch (Exception ignored) { }
            } finally {
                if (child != null) { child.destroyForcibly(); child = null; }
            }
            try {
                report.put("completed_epoch_ms", System.currentTimeMillis());
                NativeVerificationJob.write(new File(getFilesDir(), "memory_pressure.json"), report);
            } catch (Exception error) { android.util.Log.e("TraceformProbe", "memory receipt failed", error); }
            new android.os.Handler(getMainLooper()).post(() -> { worker = null; jobFinished(parameters, false); });
        }, "synthetic-memory-pressure");
        worker.start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        if (worker != null) worker.interrupt();
        if (child != null) child.destroyForcibly();
        return false;
    }
}
