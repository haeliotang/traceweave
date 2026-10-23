package org.traceform.agentos;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class AgentAccessibilityService extends AccessibilityService {
    private static final String TAG = "AgentAccessibility";
    private static AgentAccessibilityService sInstance;

    public static AgentAccessibilityService getInstance() {
        return sInstance;
    }

    public static boolean isServiceRunning() {
        return sInstance != null;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.i(TAG, "AgentAccessibilityService connected and ready for cross-app UI automation");

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Passive observation of active window changes
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "AgentAccessibilityService interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sInstance = null;
    }

    /**
     * Dumps the current on-screen view hierarchy across all active interactive windows as a JSON array of ScreenNode objects.
     */
    public String dumpScreenHierarchyJson() {
        List<AccessibilityNodeInfo> queue = new ArrayList<>();
        try {
            List<android.view.accessibility.AccessibilityWindowInfo> windows = getWindows();
            if (windows != null && !windows.isEmpty()) {
                for (android.view.accessibility.AccessibilityWindowInfo w : windows) {
                    AccessibilityNodeInfo wRoot = w.getRoot();
                    if (wRoot != null) {
                        queue.add(wRoot);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to query getWindows: " + e.getMessage());
        }

        if (queue.isEmpty()) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                queue.add(root);
            }
        }

        if (queue.isEmpty()) {
            Log.w(TAG, "getRootInActiveWindow() and getWindows() both returned null");
            return "[]";
        }

        JSONArray array = new JSONArray();
        int count = 0;
        Rect bounds = new Rect();

        while (!queue.isEmpty() && count < 200) {
            AccessibilityNodeInfo node = queue.remove(0);
            count++;

            try {
                JSONObject json = new JSONObject();
                String resId = node.getViewIdResourceName();
                json.put("id", resId != null ? resId : "node_" + count);
                json.put("class_name", node.getClassName() != null ? node.getClassName().toString() : "");
                json.put("text", node.getText() != null ? node.getText().toString() : JSONObject.NULL);
                json.put("content_description", node.getContentDescription() != null ? node.getContentDescription().toString() : JSONObject.NULL);

                node.getBoundsInScreen(bounds);
                JSONArray boundsArr = new JSONArray();
                boundsArr.put(bounds.left);
                boundsArr.put(bounds.top);
                boundsArr.put(bounds.right);
                boundsArr.put(bounds.bottom);
                json.put("bounds", boundsArr);

                json.put("is_clickable", node.isClickable());
                json.put("is_editable", node.isEditable());
                json.put("is_scrollable", node.isScrollable());
                json.put("is_visible", node.isVisibleToUser());

                array.put(json);

                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.add(child);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error serializing accessibility node: " + e.getMessage());
            } finally {
                node.recycle();
            }
        }

        // Recycle any remaining queued nodes
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo leftover = queue.remove(0);
            if (leftover != null) {
                leftover.recycle();
            }
        }

        return array.toString();
    }

    /**
     * Programmatically dispatches a simulated touch tap gesture at coordinates (x, y).
     */
    public boolean performClickGesture(int x, int y) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            return false;
        }

        Path clickPath = new Path();
        clickPath.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(clickPath, 0, 100);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);

        return dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.i(TAG, "Gesture tap completed at (" + x + ", " + y + ")");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.w(TAG, "Gesture tap cancelled at (" + x + ", " + y + ")");
            }
        }, null);
    }

    public interface ScreenCaptureCallback {
        void onBitmapCaptured(android.graphics.Bitmap bitmap);
        void onError(int errorCode);
    }

    /**
     * Programmatically captures the current active screen frame without prompt dialogs.
     */
    public void takeScreenBitmap(ScreenCaptureCallback callback) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult result) {
                        try {
                            android.hardware.HardwareBuffer hb = result.getHardwareBuffer();
                            android.graphics.Bitmap hwBitmap = android.graphics.Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                            android.graphics.Bitmap copy = hwBitmap != null ? hwBitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false) : null;
                            if (hwBitmap != null) {
                                hwBitmap.recycle();
                            }
                            hb.close();
                            if (callback != null) callback.onBitmapCaptured(copy);
                        } catch (Exception e) {
                            Log.e(TAG, "HardwareBuffer conversion error: " + e.getMessage());
                            if (callback != null) callback.onError(-1);
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        Log.w(TAG, "Accessibility takeScreenshot failed with code: " + errorCode);
                        if (callback != null) callback.onError(errorCode);
                    }
                }
            );
        } else {
            if (callback != null) callback.onError(-2);
        }
    }
}
