package org.traceform.agentos;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * System-facing bound service providing the Personal Agent Substrate IPC interface.
 * Allows independent third-party Agent applications to securely register, request JIT capabilities,
 * execute tools, and inspect audit trails under strict user ownership.
 */
public class SubstrateService extends Service {
    private static final String TAG = "SubstrateService";
    public static final String ACTION_BIND_SUBSTRATE = "org.traceform.agentos.action.BIND_SUBSTRATE";

    private AgentStore store;
    private CapabilityBroker broker;
    private final Map<String, AgentSession> sessions = Collections.synchronizedMap(new HashMap<>());

    static final class AgentSession {
        final String token;
        final String packageName;
        final String keyHash;
        final String manifest;
        final long createdAt;

        AgentSession(String token, String packageName, String keyHash, String manifest, long createdAt) {
            this.token = token;
            this.packageName = packageName;
            this.keyHash = keyHash;
            this.manifest = manifest;
            this.createdAt = createdAt;
        }
    }

    private final IAgentSubstrate.Stub binder = new IAgentSubstrate.Stub() {
        @Override
        public String registerAgent(String agentPackage, String agentPublicKeyHash, String manifestJson) throws RemoteException {
            try {
                String token = "sub_sess_" + UUID.randomUUID().toString();
                AgentSession session = new AgentSession(token, agentPackage, agentPublicKeyHash, manifestJson, System.currentTimeMillis());
                sessions.put(token, session);

                // Record audit log of external agent registration
                if (store != null) {
                    store.appendTaskEvent(getOrEnsureSubstrateTask(), "agent_registered:" + agentPackage);
                }
                Log.i(TAG, "Registered external agent: " + agentPackage + ", session: " + token);

                JSONObject res = new JSONObject();
                res.put("status", "ok");
                res.put("session_token", token);
                res.put("agent_package", agentPackage);
                res.put("timestamp", System.currentTimeMillis());
                return res.toString();
            } catch (Exception e) {
                Log.e(TAG, "registerAgent failed", e);
                return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
            }
        }

        @Override
        public String listCapabilities(String sessionToken, String taskId) throws RemoteException {
            AgentSession session = requireSession(sessionToken);
            String effectiveTaskId = (taskId == null || taskId.isEmpty()) ? getOrEnsureSubstrateTask() : taskId;
            List<CapabilityDescriptor> list = broker.listAvailableCapabilities(effectiveTaskId);
            JSONArray arr = new JSONArray();
            for (CapabilityDescriptor d : list) {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("capability", d.capability);
                    obj.put("scope", d.scope);
                    obj.put("title", d.title);
                    obj.put("description", d.description);
                    obj.put("available", d.available);
                    arr.put(obj);
                } catch (Exception ignored) {}
            }
            return arr.toString();
        }

        @Override
        public boolean requestCapability(String sessionToken, String taskId, String capability, String scope) throws RemoteException {
            AgentSession session = requireSession(sessionToken);
            String effectiveTaskId = (taskId == null || taskId.isEmpty()) ? getOrEnsureSubstrateTask() : taskId;
            try {
                broker.grant(session.packageName, effectiveTaskId, capability, scope);
                Log.i(TAG, "Granted capability " + capability + " to agent " + session.packageName);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "Grant rejected: " + e.getMessage());
                return false;
            }
        }

        @Override
        public String invokeCapability(String sessionToken, String taskId, String capability, String scope) throws RemoteException {
            AgentSession session = requireSession(sessionToken);
            String effectiveTaskId = (taskId == null || taskId.isEmpty()) ? getOrEnsureSubstrateTask() : taskId;
            try {
                return broker.invoke(session.packageName, effectiveTaskId, capability, scope);
            } catch (Exception e) {
                Log.e(TAG, "Invoke failed: " + e.getMessage());
                throw new RemoteException(e.getMessage());
            }
        }

        @Override
        public boolean revokeCapability(String sessionToken, String taskId, String capability, String scope) throws RemoteException {
            AgentSession session = requireSession(sessionToken);
            String effectiveTaskId = (taskId == null || taskId.isEmpty()) ? getOrEnsureSubstrateTask() : taskId;
            return broker.revoke(session.packageName, effectiveTaskId, capability, scope);
        }

        @Override
        public String queryAuditLog(String sessionToken, String taskId, int limit) throws RemoteException {
            AgentSession session = requireSession(sessionToken);
            String effectiveTaskId = (taskId == null || taskId.isEmpty()) ? getOrEnsureSubstrateTask() : taskId;
            try {
                int queryLimit = limit > 0 ? limit : 50;
                List<String> events = store.listAuditEvents(effectiveTaskId, queryLimit);
                JSONObject obj = new JSONObject();
                obj.put("status", "ok");
                obj.put("taskId", effectiveTaskId);
                obj.put("agent", session.packageName);
                JSONArray arr = new JSONArray();
                for (String ev : events) {
                    arr.put(ev);
                }
                obj.put("events", arr);
                obj.put("count", arr.length());
                return obj.toString();
            } catch (Exception e) {
                return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
            }
        }

        @Override
        public String ping(String sessionToken) throws RemoteException {
            boolean valid = false;
            if (sessionToken != null && sessions.containsKey(sessionToken)) {
                AgentSession sess = sessions.get(sessionToken);
                valid = sess != null && (System.currentTimeMillis() - sess.createdAt <= SESSION_EXPIRY_MILLIS);
            }
            try {
                JSONObject obj = new JSONObject();
                obj.put("pong", true);
                obj.put("service", "PersonalAgentSubstrate");
                obj.put("session_valid", valid);
                obj.put("active_agents", sessions.size());
                obj.put("server_time_ms", System.currentTimeMillis());
                return obj.toString();
            } catch (Exception e) {
                return "{\"pong\":true}";
            }
        }
    };

    private static final long SESSION_EXPIRY_MILLIS = 24L * 60L * 60L * 1000L;

    private AgentSession requireSession(String token) throws RemoteException {
        if (token == null || !sessions.containsKey(token)) {
            throw new RemoteException("Invalid substrate session token");
        }
        AgentSession session = sessions.get(token);
        if (session == null || System.currentTimeMillis() - session.createdAt > SESSION_EXPIRY_MILLIS) {
            sessions.remove(token);
            throw new RemoteException("Substrate session token has expired");
        }
        return session;
    }

    private synchronized String getOrEnsureSubstrateTask() {
        try {
            List<AgentStore.Task> tasks = store.listTasks();
            if (!tasks.isEmpty()) {
                return tasks.get(0).id;
            }
            AgentStore.Task newTask = store.createTask("Substrate Host Task Context");
            return newTask.id;
        } catch (Exception e) {
            return "substrate-default-task";
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store = new AgentStore(this);
        broker = new CapabilityBroker(this, store);
        Log.i(TAG, "SubstrateService initialized and ready for Agent IPC");
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "Client binding to SubstrateService: " + (intent != null ? intent.getAction() : "null"));
        return binder;
    }
}
