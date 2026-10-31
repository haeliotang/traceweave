package com.inceptless.traceweave;

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
                if ("SEED_UI".equals(getIntent().getAction())) {
                    if (!broker.canEnableTraceweave()) throw new IllegalStateException("Synthetic service unavailable");
                    AgentStore.Task task = store.createTask("SYNTHETIC interactive Traceweave approval");
                    getSharedPreferences("interactive_probe", 0).edit().putString("task", task.id).commit();
                    getSharedPreferences("agent_os_prefs", 0).edit().putString("app_lang", "en").commit();
                    startActivity(new Intent(this, MainActivity.class).putExtra("focusTaskId", task.id));
                    Log.i("ScopedInteractive", "PASS task UI seeded");
                } else if ("VERIFY_UI".equals(getIntent().getAction())) {
                    String id = getSharedPreferences("interactive_probe", 0).getString("task", "");
                    if (broker.remainingMillis(id, CapabilityBroker.TRACEWEAVE_CAPABILITY, CapabilityBroker.TRACEWEAVE_SCOPE) != 0)
                        throw new IllegalStateException("UI did not revoke grant");
                    try {
                        broker.invoke(id, CapabilityBroker.TRACEWEAVE_CAPABILITY, CapabilityBroker.TRACEWEAVE_SCOPE);
                        throw new IllegalStateException("Revoked read unexpectedly succeeded");
                    } catch (SecurityException expected) { }
                    Log.i("ScopedInteractive", "PASS UI revoked grant denied");
                } else if ("SEED".equals(getIntent().getAction())) {
                    if (!broker.canEnableTraceweave()) throw new IllegalStateException("Synthetic service unavailable");
                    broker.setTraceweaveEnabled(true);
                    AgentStore.Task task = store.createTask("SYNTHETIC interactive Traceweave approval");
                    try {
                        broker.invoke(task.id, CapabilityBroker.TRACEWEAVE_CAPABILITY, CapabilityBroker.TRACEWEAVE_SCOPE);
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
                                    method.invoke(a, task, "Traceweave Local History Summary", CapabilityBroker.TRACEWEAVE_CAPABILITY, CapabilityBroker.TRACEWEAVE_SCOPE);
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
                    JSONObject summary = new JSONObject(broker.invoke(id, CapabilityBroker.TRACEWEAVE_CAPABILITY, CapabilityBroker.TRACEWEAVE_SCOPE));
                    if (!summary.getBoolean("available") || summary.getInt("history_records") != 6 || summary.getInt("eligible_pairs") != 5)
                        throw new IllegalStateException("Unexpected synthetic summary");
                    if (!broker.revoke("local_agent", id, CapabilityBroker.TRACEWEAVE_CAPABILITY, CapabilityBroker.TRACEWEAVE_SCOPE))
                        throw new IllegalStateException("Grant unavailable for revocation");
                    try {
                        broker.invoke(id, CapabilityBroker.TRACEWEAVE_CAPABILITY, CapabilityBroker.TRACEWEAVE_SCOPE);
                        throw new IllegalStateException("Revoked read unexpectedly succeeded");
                    } catch (SecurityException expected) { }
                    Log.i("ScopedInteractive", "PASS interactive grant synthetic summary revoked denied");
                } else throw new IllegalArgumentException("Unsupported test action");
            } catch (Exception error) { Log.e("ScopedInteractive", "FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage(), error); }
        }, "synthetic-interactive-probe").start();
    }
}
