package org.traceform.agentos.sdk;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import org.traceform.agentos.IAgentSubstrate;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Standard Android client SDK interface for interacting with Personal Agent Substrate.
 */
public class SubstrateClient {

    private static final String SUBSTRATE_PACKAGE = "org.traceform.agentos";
    private static final String SUBSTRATE_ACTION = "org.traceform.agentos.action.BIND_SUBSTRATE";

    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;

    private volatile IAgentSubstrate substrateService;
    private volatile boolean isBound = false;
    private volatile String activeSessionToken;
    private volatile String activeTaskId;

    private SubstrateCallback.ConnectionListener connectionListener;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            substrateService = IAgentSubstrate.Stub.asInterface(service);
            isBound = true;
            if (connectionListener != null) {
                mainHandler.post(() -> connectionListener.onConnected());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            substrateService = null;
            isBound = false;
            activeSessionToken = null;
            activeTaskId = null;
            if (connectionListener != null) {
                mainHandler.post(() -> connectionListener.onDisconnected());
            }
        }
    };

    public SubstrateClient(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public synchronized void connect(SubstrateCallback.ConnectionListener listener) {
        this.connectionListener = listener;
        Intent intent = new Intent(SUBSTRATE_ACTION);
        intent.setPackage(SUBSTRATE_PACKAGE);

        try {
            boolean bindSuccess = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
            if (!bindSuccess && listener != null) {
                mainHandler.post(() -> listener.onError(new SubstrateException("Failed to bind to SubstrateService. Ensure AgentOS APK is installed.")));
            }
        } catch (SecurityException e) {
            if (listener != null) {
                mainHandler.post(() -> listener.onError(new SubstrateException("Security exception binding to SubstrateService", e)));
            }
        }
    }

    public synchronized void disconnect() {
        if (isBound) {
            try {
                context.unbindService(serviceConnection);
            } catch (Exception ignored) {
            }
            isBound = false;
            substrateService = null;
            activeSessionToken = null;
            activeTaskId = null;
        }
        executor.shutdown();
    }

    public boolean isConnected() {
        return isBound && substrateService != null;
    }

    public String getActiveSessionToken() {
        return activeSessionToken;
    }

    public String getActiveTaskId() {
        return activeTaskId;
    }

    // =========================================================================
    // Synchronous Methods (Must run off main thread)
    // =========================================================================

    public String registerAgentSync(String pkg, String keyHash, AgentManifest manifest) throws SubstrateException {
        ensureConnected();
        try {
            JSONObject response = new JSONObject(substrateService.registerAgent(pkg, keyHash, manifest.toJsonString()));
            if (!"ok".equals(response.optString("status"))) {
                throw new SubstrateException.AuthenticationException(response.optString("message", "Agent registration failed"));
            }
            String token = response.optString("session_token", "");
            String taskId = response.optString("default_task_id", "");
            if (token.isEmpty() || taskId.isEmpty()) {
                throw new SubstrateException.AuthenticationException("Agent registration response is incomplete");
            }
            this.activeSessionToken = token;
            this.activeTaskId = taskId;
            return token;
        } catch (org.json.JSONException e) {
            throw new SubstrateException.AuthenticationException("Invalid registration response from Substrate");
        } catch (RemoteException e) {
            throw new SubstrateException("IPC failure during registerAgent", e);
        }
    }

    public String listCapabilitiesSync(String taskId) throws SubstrateException {
        ensureConnected();
        try {
            return substrateService.listCapabilities(activeSessionToken, taskId);
        } catch (RemoteException e) {
            throw new SubstrateException("IPC failure during listCapabilities", e);
        }
    }

    public boolean requestCapabilitySync(String taskId, String capability, String scope) throws SubstrateException {
        ensureConnected();
        try {
            return substrateService.requestCapability(activeSessionToken, taskId, capability, scope);
        } catch (RemoteException e) {
            throw new SubstrateException("IPC failure during requestCapability", e);
        }
    }

    public String invokeCapabilitySync(String taskId, String capability, String scope) throws SubstrateException {
        ensureConnected();
        try {
            String result = substrateService.invokeCapability(activeSessionToken, taskId, capability, scope);
            if (result == null) {
                throw new SubstrateException.CapabilityDeniedException(capability, scope);
            }
            return result;
        } catch (RemoteException e) {
            throw new SubstrateException("IPC failure during invokeCapability", e);
        }
    }

    public boolean revokeCapabilitySync(String taskId, String capability, String scope) throws SubstrateException {
        ensureConnected();
        try {
            return substrateService.revokeCapability(activeSessionToken, taskId, capability, scope);
        } catch (RemoteException e) {
            throw new SubstrateException("IPC failure during revokeCapability", e);
        }
    }

    public String queryAuditLogSync(String taskId, int limit) throws SubstrateException {
        ensureConnected();
        try {
            return substrateService.queryAuditLog(activeSessionToken, taskId, limit);
        } catch (RemoteException e) {
            throw new SubstrateException("IPC failure during queryAuditLog", e);
        }
    }

    public String pingSync() throws SubstrateException {
        ensureConnected();
        try {
            return substrateService.ping(activeSessionToken);
        } catch (RemoteException e) {
            throw new SubstrateException("IPC failure during ping", e);
        }
    }

    // =========================================================================
    // Asynchronous Methods with UI Handler dispatch
    // =========================================================================

    public void registerAgent(String pkg, String keyHash, AgentManifest manifest, SubstrateCallback.ActionCallback<String> callback) {
        executor.execute(() -> {
            try {
                String token = registerAgentSync(pkg, keyHash, manifest);
                mainHandler.post(() -> callback.onSuccess(token));
            } catch (Throwable t) {
                mainHandler.post(() -> callback.onError(t));
            }
        });
    }

    public void requestCapability(String taskId, String capability, String scope, SubstrateCallback.ActionCallback<Boolean> callback) {
        executor.execute(() -> {
            try {
                boolean granted = requestCapabilitySync(taskId, capability, scope);
                mainHandler.post(() -> callback.onSuccess(granted));
            } catch (Throwable t) {
                mainHandler.post(() -> callback.onError(t));
            }
        });
    }

    public void invokeCapability(String taskId, String capability, String scope, SubstrateCallback.ActionCallback<String> callback) {
        executor.execute(() -> {
            try {
                String res = invokeCapabilitySync(taskId, capability, scope);
                mainHandler.post(() -> callback.onSuccess(res));
            } catch (Throwable t) {
                mainHandler.post(() -> callback.onError(t));
            }
        });
    }

    public void revokeCapability(String taskId, String capability, String scope, SubstrateCallback.ActionCallback<Boolean> callback) {
        executor.execute(() -> {
            try {
                boolean revoked = revokeCapabilitySync(taskId, capability, scope);
                mainHandler.post(() -> callback.onSuccess(revoked));
            } catch (Throwable t) {
                mainHandler.post(() -> callback.onError(t));
            }
        });
    }

    private void ensureConnected() throws SubstrateException.NotConnectedException {
        if (!isConnected()) {
            throw new SubstrateException.NotConnectedException();
        }
    }
}
