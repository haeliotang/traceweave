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
     * Dumps the current active window view hierarchy as a JSON array of ScreenNode objects.
     */
    public String dumpScreenHierarchyJson() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return "[]";
        }

        JSONArray array = new JSONArray();
        List<AccessibilityNodeInfo> queue = new ArrayList<>();
        queue.add(root);

        int count = 0;
        Rect bounds = new Rect();

        while (!queue.isEmpty() && count < 100) {
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
}
