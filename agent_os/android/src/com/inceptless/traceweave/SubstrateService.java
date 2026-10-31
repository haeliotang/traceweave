package com.inceptless.traceweave;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * System-facing bound service providing the Personal Agent Substrate IPC interface.
 * Agent sessions are tied to the caller UID, and capability grants require user approval.
 */
public class SubstrateService extends Service {
    private static final String TAG = "SubstrateService";
    private static final String IDENTITY_SCHEME = "android-package-signer-sha256-v1";
    public static final String ACTION_BIND_SUBSTRATE = "com.inceptless.traceweave.action.BIND_SUBSTRATE";
    static final String ACTION_APPROVE_CAPABILITY = "com.inceptless.traceweave.action.APPROVE_CAPABILITY";
    private static final String APPROVAL_CHANNEL = "agent_capability_approval";
    private static final int APPROVAL_TIMEOUT_SECONDS = 120;
    private static final Map<String, PendingCapabilityApproval> PENDING_APPROVALS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicBoolean APPROVAL_ACTIVE = new AtomicBoolean();

    private AgentStore store;
    private CapabilityBroker broker;
    private final Map<String, AgentSession> sessions = Collections.synchronizedMap(new HashMap<>());

    static final class AgentSession {
        final String token;
        final String packageName;
        final int callerUid;
        final String signerSha256;
        final String agentIdentity;
        final String manifest;
        final String taskId;
        final long createdAt;

        AgentSession(String token, String packageName, int callerUid, String signerSha256, String agentIdentity,
                     String manifest, String taskId, long createdAt) {
            this.token = token;
            this.packageName = packageName;
            this.callerUid = callerUid;
            this.signerSha256 = signerSha256;
            this.agentIdentity = agentIdentity;
            this.manifest = manifest;
            this.taskId = taskId;
            this.createdAt = createdAt;
        }
    }

    static final class PendingCapabilityApproval {
        final String id;
        final String packageName;
        final String signerSha256;
        final String taskTitle;
        final String capability;
        final String scope;
        final CountDownLatch result = new CountDownLatch(1);
        final AtomicBoolean resolved = new AtomicBoolean();
        volatile boolean approved;

        PendingCapabilityApproval(String id, String packageName, String signerSha256, String taskTitle,
                                  String capability, String scope) {
            this.id = id;
            this.packageName = packageName;
            this.signerSha256 = signerSha256;
            this.taskTitle = taskTitle;
            this.capability = capability;
            this.scope = scope;
        }
    }

    private final IAgentSubstrate.Stub binder = new IAgentSubstrate.Stub() {
        @Override
        public String registerAgent(String agentPackage, String legacyAgentPublicKeyHash, String manifestJson) throws RemoteException {
            try {
                int callerUid = Binder.getCallingUid();
                if (agentPackage == null || agentPackage.trim().isEmpty() || agentPackage.length() > 255
                        || !packageBelongsToUid(agentPackage, callerUid)) {
                    throw new SecurityException("Agent package does not belong to the calling application");
                }
                String signerSha256 = currentSignerSha256(agentPackage);
                String agentIdentity = "android-package:" + agentPackage + ":sha256:" + signerSha256;
                if (legacyAgentPublicKeyHash != null && !legacyAgentPublicKeyHash.trim().isEmpty()) {
                    Log.w(TAG, "Ignoring caller-supplied Agent key hash; Android package signing defines MVP identity");
                }
                if (manifestJson == null || manifestJson.length() > 64 * 1024) {
                    throw new IllegalArgumentException("Agent manifest is missing or too large");
                }
                new JSONObject(manifestJson);

                long now = System.currentTimeMillis();
                String token = "sub_sess_" + UUID.randomUUID();
                String taskId = null;
                synchronized (sessions) {
                    java.util.Iterator<Map.Entry<String, AgentSession>> iterator = sessions.entrySet().iterator();
                    while (iterator.hasNext()) {
                        Map.Entry<String, AgentSession> entry = iterator.next();
                        AgentSession existing = entry.getValue();
                        if (existing.callerUid == callerUid && agentPackage.equals(existing.packageName)) {
                            taskId = existing.taskId;
                            iterator.remove();
                        }
                    }
                    if (taskId == null) taskId = store.createTask("Agent task: " + agentPackage).id;
                    sessions.put(token, new AgentSession(token, agentPackage, callerUid, signerSha256,
                        agentIdentity, manifestJson, taskId, now));
                }
                store.appendTaskEvent(taskId, "agent_registered:" + agentIdentity);

                JSONObject response = new JSONObject();
                response.put("status", "ok");
                response.put("session_token", token);
                response.put("agent_package", agentPackage);
                response.put("agent_identity", agentIdentity);
                response.put("identity_scheme", IDENTITY_SCHEME);
                response.put("agent_signer_sha256", signerSha256);
                response.put("default_task_id", taskId);
                response.put("timestamp", now);
                return response.toString();
            } catch (Exception e) {
                Log.e(TAG, "registerAgent failed", e);
                return errorResponse(e);
            }
        }

        @Override
        public String listCapabilities(String sessionToken, String taskId) throws RemoteException {
            AgentSession session = requireSession(sessionToken);
            String effectiveTaskId = requireOwnedTask(session, taskId);
            List<CapabilityDescriptor> list = broker.listAvailableCapabilities(effectiveTaskId);
            JSONArray arr = new JSONArray();
            for (CapabilityDescriptor descriptor : list) {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("capability", descriptor.capability);
                    obj.put("scope", descriptor.scope);
                    obj.put("title", descriptor.title);
                    obj.put("description", descriptor.description);
                    obj.put("available", descriptor.available);
                    arr.put(obj);
                } catch (Exception ignored) {}
            }
            return arr.toString();
        }

        @Override
        public boolean requestCapability(String sessionToken, String taskId, String capability, String scope) throws RemoteException {
            AgentSession session = requireSession(sessionToken);
            String effectiveTaskId = requireOwnedTask(session, taskId);
            if (capability == null || scope == null || capability.length() > 128 || scope.length() > 512) {
                broker.deny(session.agentIdentity, effectiveTaskId, capability, scope);
                return false;
            }
            boolean available = false;
            for (CapabilityDescriptor descriptor : broker.listAvailableCapabilities(effectiveTaskId)) {
                if (descriptor.capability.equals(capability) && descriptor.scope.equals(scope) && descriptor.available) {
                    available = true;
                    break;
                }
            }
            if (!available) {
                broker.deny(session.agentIdentity, effectiveTaskId, capability, scope);
                return false;
            }
            AgentStore.Task task;
            try {
                task = store.getTask(effectiveTaskId);
            } catch (Exception e) {
                throw new RemoteException("Unable to load the Agent task: " + e.getMessage());
            }
            if (task == null) throw new RemoteException("Agent task does not exist");

            PendingCapabilityApproval approval = new PendingCapabilityApproval(UUID.randomUUID().toString(),
                session.packageName, session.signerSha256, task.title, capability, scope);
            if (!APPROVAL_ACTIVE.compareAndSet(false, true)) {
                broker.deny(session.agentIdentity, effectiveTaskId, capability, scope);
                return false;
            }
            PENDING_APPROVALS.put(approval.id, approval);
            boolean approved = false;
            try {
                if (!postApprovalNotification(approval)) {
                    broker.deny(session.agentIdentity, effectiveTaskId, capability, scope);
                    return false;
                }
                approved = approval.result.await(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    && approval.approved && approval.resolved.get();
                if (approved) {
                    broker.grant(session.agentIdentity, effectiveTaskId, capability, scope);
                    Log.i(TAG, "User approved capability " + capability + " for " + session.agentIdentity);
                    return true;
                }
                broker.deny(session.agentIdentity, effectiveTaskId, capability, scope);
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                broker.deny(session.agentIdentity, effectiveTaskId, capability, scope);
                return false;
            } catch (Exception e) {
                broker.deny(session.agentIdentity, effectiveTaskId, capability, scope);
                Log.w(TAG, "Capability approval failed: " + e.getMessage());
                return false;
            } finally {
                PENDING_APPROVALS.remove(approval.id, approval);
                APPROVAL_ACTIVE.set(false);
                notificationManager().cancel(approval.id.hashCode());
            }
        }

        @Override
        public String invokeCapability(String sessionToken, String taskId, String capability, String scope) throws RemoteException {
            AgentSession session = requireSession(sessionToken);
            String effectiveTaskId = requireOwnedTask(session, taskId);
            try {
                return broker.invoke(session.agentIdentity, effectiveTaskId, capability, scope);
            } catch (Exception e) {
                Log.e(TAG, "Invoke failed: " + e.getMessage());
                throw new RemoteException(e.getMessage());
            }
        }

        @Override
        public boolean revokeCapability(String sessionToken, String taskId, String capability, String scope) throws RemoteException {
            AgentSession session = requireSession(sessionToken);
            String effectiveTaskId = requireOwnedTask(session, taskId);
            return broker.revoke(session.agentIdentity, effectiveTaskId, capability, scope);
        }

        @Override
        public String queryAuditLog(String sessionToken, String taskId, int limit) throws RemoteException {
            AgentSession session = requireSession(sessionToken);
            String effectiveTaskId = requireOwnedTask(session, taskId);
            try {
                int queryLimit = limit > 0 ? Math.min(limit, 200) : 50;
                List<String> events = store.listAuditEvents(effectiveTaskId, queryLimit);
                JSONObject response = new JSONObject();
                response.put("status", "ok");
                response.put("taskId", effectiveTaskId);
                response.put("agent", session.agentIdentity);
                JSONArray arr = new JSONArray();
                for (String event : events) arr.put(event);
                response.put("events", arr);
                response.put("count", arr.length());
                return response.toString();
            } catch (Exception e) {
                return errorResponse(e);
            }
        }

        @Override
        public String ping(String sessionToken) throws RemoteException {
            AgentSession session = requireSession(sessionToken);
            try {
                JSONObject response = new JSONObject();
                response.put("pong", true);
                response.put("service", "PersonalAgentSubstrate");
                response.put("session_valid", true);
                response.put("agent_package", session.packageName);
                response.put("agent_identity", session.agentIdentity);
                response.put("server_time_ms", System.currentTimeMillis());
                return response.toString();
            } catch (Exception e) {
                return errorResponse(e);
            }
        }
    };

    private static final long SESSION_EXPIRY_MILLIS = 24L * 60L * 60L * 1000L;

    private boolean packageBelongsToUid(String packageName, int uid) {
        String[] packages = getPackageManager().getPackagesForUid(uid);
        return packages != null && packages.length == 1 && packageName.equals(packages[0]);
    }

    private String currentSignerSha256(String packageName)
            throws PackageManager.NameNotFoundException, NoSuchAlgorithmException {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo packageInfo = getPackageManager().getPackageInfo(packageName, flags);
        Signature[] signers;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signers = packageInfo.signingInfo == null ? null : packageInfo.signingInfo.getApkContentsSigners();
        } else {
            signers = packageInfo.signatures;
        }
        if (signers == null || signers.length != 1) {
            throw new SecurityException("MVP agents must have exactly one current APK signing certificate");
        }

        byte[] digest = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray());
        StringBuilder fingerprint = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            int unsigned = value & 0xff;
            fingerprint.append("0123456789abcdef".charAt(unsigned >>> 4));
            fingerprint.append("0123456789abcdef".charAt(unsigned & 0x0f));
        }
        return fingerprint.toString();
    }

    private AgentSession requireSession(String token) throws RemoteException {
        int callerUid = Binder.getCallingUid();
        AgentSession session = token == null ? null : sessions.get(token);
        if (session == null || session.callerUid != callerUid) {
            throw new RemoteException("Invalid substrate session token for caller");
        }
        if (!packageBelongsToUid(session.packageName, callerUid)) {
            sessions.remove(token);
            throw new RemoteException("Agent package no longer belongs to the Binder caller UID");
        }
        try {
            if (!session.signerSha256.equals(currentSignerSha256(session.packageName))) {
                sessions.remove(token);
                throw new RemoteException("Agent package signing identity changed; register again");
            }
        } catch (RemoteException error) {
            throw error;
        } catch (Exception error) {
            sessions.remove(token);
            throw new RemoteException("Unable to verify Agent package signing identity");
        }
        if (System.currentTimeMillis() - session.createdAt > SESSION_EXPIRY_MILLIS) {
            sessions.remove(token);
            throw new RemoteException("Substrate session token has expired");
        }
        return session;
    }

    private String requireOwnedTask(AgentSession session, String taskId) throws RemoteException {
        String effectiveTaskId = taskId == null || taskId.isEmpty() ? session.taskId : taskId;
        if (!session.taskId.equals(effectiveTaskId)) {
            throw new RemoteException("Agent session cannot access another task");
        }
        return effectiveTaskId;
    }

    private boolean postApprovalNotification(PendingCapabilityApproval approval) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager = notificationManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(APPROVAL_CHANNEL,
                "Agent capability requests", NotificationManager.IMPORTANCE_HIGH));
        }
        Intent intent = new Intent(this, CapabilityApprovalActivity.class);
        intent.setAction(ACTION_APPROVE_CAPABILITY);
        intent.putExtra("request_id", approval.id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, approval.id.hashCode(), intent, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, APPROVAL_CHANNEL) : new Notification.Builder(this);
        Notification notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Agent OS capability request")
            .setContentText("Tap to review an Agent's access request")
            .setContentIntent(pendingIntent)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build();
        manager.notify(approval.id.hashCode(), notification);
        return true;
    }

    private NotificationManager notificationManager() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    static PendingCapabilityApproval getPendingApproval(String id) {
        return PENDING_APPROVALS.get(id);
    }

    static void resolvePendingApproval(String id, boolean approved) {
        PendingCapabilityApproval pending = PENDING_APPROVALS.get(id);
        if (pending != null && pending.resolved.compareAndSet(false, true)) {
            pending.approved = approved;
            pending.result.countDown();
        }
    }

    private static String errorResponse(Exception error) {
        try {
            return new JSONObject().put("status", "error")
                .put("message", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())
                .toString();
        } catch (Exception ignored) {
            return "{\"status\":\"error\"}";
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
        if (intent == null || !ACTION_BIND_SUBSTRATE.equals(intent.getAction())) return null;
        return binder;
    }
}
