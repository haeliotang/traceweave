package org.traceform.agentos;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import org.json.JSONObject;

/** Synthetic-only end-to-end broker probe for a test-addressed Edge service. */
public final class ScopedBrokerProbe extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try (AgentStore store = new AgentStore(this)) {
                AgentStore.Task task = store.createTask("SYNTHETIC scoped history");
                CapabilityBroker broker = new CapabilityBroker(this, store);
                if (!broker.canEnableTraceform()) throw new IllegalStateException("isolated service is not trusted");
                broker.setTraceformEnabled(true);
                expectDenied(broker, task.id);
                broker.grant(task.id, CapabilityBroker.TRACEFORM_CAPABILITY, CapabilityBroker.TRACEFORM_SCOPE);
                try {
                    broker.invoke("synthetic_other_agent", task.id, CapabilityBroker.TRACEFORM_CAPABILITY, CapabilityBroker.TRACEFORM_SCOPE);
                    throw new IllegalStateException("another agent inherited the grant");
                } catch (SecurityException expected) { /* Grants remain bound to their agent. */ }
                JSONObject summary = new JSONObject(broker.invoke(task.id, CapabilityBroker.TRACEFORM_CAPABILITY, CapabilityBroker.TRACEFORM_SCOPE));
                if (!summary.getBoolean("available") || summary.getInt("history_records") != 6
                        || summary.getInt("eligible_pairs") != 5 || summary.getBoolean("eligible_for_intervention"))
                    throw new IllegalStateException("unexpected synthetic summary");
                if (!broker.revoke("local_agent", task.id, CapabilityBroker.TRACEFORM_CAPABILITY, CapabilityBroker.TRACEFORM_SCOPE))
                    throw new IllegalStateException("grant was not revoked");
                expectDenied(broker, task.id);
                Log.i("AgentOsScopedBroker", "PASS denied_granted_summary_revoked_denied");
            } catch (Throwable error) {
                Log.e("AgentOsScopedBroker", "FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }, "synthetic-scoped-broker").start();
    }

    private static void expectDenied(CapabilityBroker broker, String taskId) {
        try { broker.invoke(taskId, CapabilityBroker.TRACEFORM_CAPABILITY, CapabilityBroker.TRACEFORM_SCOPE); }
        catch (SecurityException expected) { return; }
        throw new IllegalStateException("ungranted summary was accepted");
    }
}
