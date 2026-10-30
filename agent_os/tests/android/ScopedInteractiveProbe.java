package org.traceform.agentos;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import org.json.JSONObject;

/** Isolated synthetic Edge fixture only. The grant is made through the real task dialog. */
public final class ScopedInteractiveProbe extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try (AgentStore store = new AgentStore(this)) {
                CapabilityBroker broker = new CapabilityBroker(this, store);
                if ("SEED".equals(getIntent().getAction())) {
                    if (!broker.canEnableTraceform()) throw new IllegalStateException("Synthetic service unavailable");
                    broker.setTraceformEnabled(true);
                    AgentStore.Task task = store.createTask("SYNTHETIC interactive Traceform approval");
                    try {
                        broker.invoke(task.id, CapabilityBroker.TRACEFORM_CAPABILITY, CapabilityBroker.TRACEFORM_SCOPE);
                        throw new IllegalStateException("Ungranted read unexpectedly succeeded");
                    } catch (SecurityException expected) { }
                    getSharedPreferences("interactive_probe", 0).edit().putString("task", task.id).commit();
                    getSharedPreferences("agent_os_prefs", 0).edit().putString("app_lang", "en").commit();
                    Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
                        public void onActivityCreated(Activity a, Bundle b) {}
                        public void onActivityStarted(Activity a) {}
                        public void onActivityResumed(Activity a) {
                            if (!(a instanceof MainActivity)) return;
                            getApplication().unregisterActivityLifecycleCallbacks(this);
                            a.getWindow().getDecorView().post(() -> {
                                try {
                                    java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod("confirmCapabilityGrant", AgentStore.Task.class, String.class, String.class, String.class);
                                    method.setAccessible(true);
                                    method.invoke(a, task, "Traceform Local History Summary", CapabilityBroker.TRACEFORM_CAPABILITY, CapabilityBroker.TRACEFORM_SCOPE);
                                } catch (Exception e) { Log.e("ScopedInteractive", "FAIL dialog", e); }
                            });
                        }
                        public void onActivityPaused(Activity a) {}
                        public void onActivityStopped(Activity a) {}
                        public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                        public void onActivityDestroyed(Activity a) {}
                    };
                    getApplication().registerActivityLifecycleCallbacks(callbacks);
                    startActivity(new Intent(this, MainActivity.class));
                    Log.i("ScopedInteractive", "PASS seed ungranted read rejected");
                } else if ("VERIFY".equals(getIntent().getAction())) {
                    String id = getSharedPreferences("interactive_probe", 0).getString("task", "");
                    JSONObject summary = new JSONObject(broker.invoke(id, CapabilityBroker.TRACEFORM_CAPABILITY, CapabilityBroker.TRACEFORM_SCOPE));
                    if (!summary.getBoolean("available") || summary.getInt("history_records") != 6 || summary.getInt("eligible_pairs") != 5)
                        throw new IllegalStateException("Unexpected synthetic summary");
                    if (!broker.revoke("local_agent", id, CapabilityBroker.TRACEFORM_CAPABILITY, CapabilityBroker.TRACEFORM_SCOPE))
                        throw new IllegalStateException("Grant unavailable for revocation");
                    try {
                        broker.invoke(id, CapabilityBroker.TRACEFORM_CAPABILITY, CapabilityBroker.TRACEFORM_SCOPE);
                        throw new IllegalStateException("Revoked read unexpectedly succeeded");
                    } catch (SecurityException expected) { }
                    Log.i("ScopedInteractive", "PASS interactive grant synthetic summary revoked denied");
                } else throw new IllegalArgumentException("Unsupported test action");
            } catch (Exception error) { Log.e("ScopedInteractive", "FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage(), error); }
        }, "synthetic-interactive-probe").start();
    }
}
