package org.traceform.agentos;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class AndroidRemoteStatusProvider implements CapabilityProvider {
    static final String CAPABILITY = "demo.remote_status.read";
    static final String SCOPE = "synthetic.remote_status";
    static final String PROVIDER_PACKAGE = "org.traceform.agentos.validation.provider";
    static final String AUTHORITY = "org.traceform.agentos.validation.provider";
    static final String ACTION_SELECT = "org.traceform.agentos.validation.provider.SELECT_STATUS";
    static final String MIME_TYPE = "application/vnd.org.traceform.agentos.validation.status";
    static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/status");

    private final Context context;
    private final ContentResolver resolver;
    private static final Map<String, Uri> taskUris = new ConcurrentHashMap<>();

    AndroidRemoteStatusProvider(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
    }

    @Override
    public String capability() {
        return CAPABILITY;
    }

    @Override
    public boolean supportsScope(String scope) {
        return SCOPE.equals(scope);
    }

    @Override
    public boolean isAvailable(String taskId, String scope) {
        Uri uri = taskUris.get(taskId);
        return supportsScope(scope) && uri != null && hasReadPermission(uri);
    }

    boolean hasResource(String taskId) {
        return taskUris.containsKey(taskId);
    }

    boolean acceptUri(String taskId, Uri uri) {
        if (!CONTENT_URI.equals(uri) || !hasReadPermission(uri)) {
            return false;
        }
        taskUris.put(taskId, uri);
        return true;
    }

    @Override
    public String read(String taskId, String scope) {
        Uri uri = taskUris.get(taskId);
        if (!isAvailable(taskId, scope)) {
            throw new SecurityException("The selected synthetic content URI is unavailable");
        }
        try (Cursor cursor = resolver.query(uri, new String[]{"value"}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new IllegalStateException("The synthetic provider returned no status");
            }
            return cursor.getString(0);
        }
    }

    @Override
    public void releaseTask(String taskId) {
        taskUris.remove(taskId);
    }

    static void releaseAllTasks() {
        taskUris.clear();
    }

    private boolean hasReadPermission(Uri uri) {
        return context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

}
