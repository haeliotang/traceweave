package org.traceform.agentos;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class AgentFloatingService extends Service {
    private static final String TAG = "AgentFloatingService";

    public static final String ACTION_SHOW = "org.traceform.agentos.floating.SHOW";
    public static final String ACTION_HIDE = "org.traceform.agentos.floating.HIDE";
    public static final String ACTION_UPDATE_STEP = "org.traceform.agentos.floating.UPDATE_STEP";
    public static final String ACTION_REQUEST_JIT = "org.traceform.agentos.floating.REQUEST_JIT";

    public static final String EXTRA_TASK_ID = "task_id";
    public static final String EXTRA_TASK_TITLE = "task_title";
    public static final String EXTRA_STEP_INDEX = "step_index";
    public static final String EXTRA_STEP_TITLE = "step_title";
    public static final String EXTRA_CAPABILITY = "capability";
    public static final String EXTRA_SCOPE = "scope";

    public interface JitDecisionListener {
        void onDecision(String taskId, String capability, String scope, boolean granted);
    }

    private static JitDecisionListener sJitListener;

    public static void setJitDecisionListener(JitDecisionListener listener) {
        sJitListener = listener;
    }

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams floatingParams;

    private TextView statusTextView;
    private LinearLayout jitContainer;
    private TextView jitPromptView;
    private Button btnGrant;
    private Button btnDeny;

    private String pendingTaskId;
    private String pendingCap;
    private String pendingScope;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        initFloatingLayout();
    }

    private void initFloatingLayout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted; overlay will operate in in-app mock mode");
            return;
        }

        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        floatingParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        floatingParams.gravity = Gravity.TOP | Gravity.START;
        floatingParams.x = 60;
        floatingParams.y = 200;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.argb(220, 24, 28, 36));
        root.setPadding(28, 20, 28, 20);

        // Header Capsule
        statusTextView = new TextView(this);
        statusTextView.setText("● Agent OS: 离线就绪");
        statusTextView.setTextColor(Color.parseColor("#4AF6C3"));
        statusTextView.setTextSize(14);
        root.addView(statusTextView);

        // Cross-App JIT Gating Container
        jitContainer = new LinearLayout(this);
        jitContainer.setOrientation(LinearLayout.VERTICAL);
        jitContainer.setVisibility(View.GONE);
        jitContainer.setPadding(0, 16, 0, 0);

        jitPromptView = new TextView(this);
        jitPromptView.setTextColor(Color.WHITE);
        jitPromptView.setTextSize(13);
        jitContainer.addView(jitPromptView);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, 12, 0, 0);

        btnGrant = new Button(this);
        btnGrant.setText("授权 5 分钟");
        btnGrant.setBackgroundColor(Color.parseColor("#1B5E20"));
        btnGrant.setTextColor(Color.WHITE);
        btnGrant.setTextSize(12);
        btnGrant.setOnClickListener(v -> handleJitDecision(true));
        btnRow.addView(btnGrant);

        btnDeny = new Button(this);
        btnDeny.setText("拒绝");
        btnDeny.setBackgroundColor(Color.parseColor("#B71C1C"));
        btnDeny.setTextColor(Color.WHITE);
        btnDeny.setTextSize(12);
        btnDeny.setOnClickListener(v -> handleJitDecision(false));
        btnRow.addView(btnDeny);

        jitContainer.addView(btnRow);
        root.addView(jitContainer);

        // Draggable touch handler
        root.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = floatingParams.x;
                        initialY = floatingParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        floatingParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                        floatingParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                        try {
                            windowManager.updateViewLayout(floatingView, floatingParams);
                        } catch (Exception ignored) {}
                        return true;
                }
                return false;
            }
        });

        floatingView = root;
        try {
            windowManager.addView(floatingView, floatingParams);
            Log.i(TAG, "Agent floating capsule attached successfully");
        } catch (Exception e) {
            Log.w(TAG, "Failed to attach floating overlay: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();

        if (ACTION_SHOW.equals(action)) {
            if (floatingView != null) floatingView.setVisibility(View.VISIBLE);
        } else if (ACTION_HIDE.equals(action)) {
            if (floatingView != null) floatingView.setVisibility(View.GONE);
        } else if (ACTION_UPDATE_STEP.equals(action)) {
            String title = intent.getStringExtra(EXTRA_TASK_TITLE);
            int step = intent.getIntExtra(EXTRA_STEP_INDEX, 0);
            String stepTitle = intent.getStringExtra(EXTRA_STEP_TITLE);
            if (statusTextView != null) {
                statusTextView.setText("● [" + (step + 1) + "] " + (stepTitle != null ? stepTitle : title));
            }
        } else if (ACTION_REQUEST_JIT.equals(action)) {
            pendingTaskId = intent.getStringExtra(EXTRA_TASK_ID);
            pendingCap = intent.getStringExtra(EXTRA_CAPABILITY);
            pendingScope = intent.getStringExtra(EXTRA_SCOPE);

            if (jitContainer != null && jitPromptView != null) {
                jitPromptView.setText("【跨应用 JIT 阻断申请】\n任务: " + pendingTaskId + "\n申请权限: " + pendingCap);
                jitContainer.setVisibility(View.VISIBLE);
                if (floatingView != null) floatingView.setVisibility(View.VISIBLE);
            }
        }

        return START_NOT_STICKY;
    }

    private void handleJitDecision(boolean granted) {
        if (jitContainer != null) {
            jitContainer.setVisibility(View.GONE);
        }
        if (statusTextView != null) {
            statusTextView.setText(granted ? "● JIT 租约已生效 (5分钟)" : "● JIT 授权已拒绝");
        }
        if (sJitListener != null) {
            sJitListener.onDecision(pendingTaskId, pendingCap, pendingScope, granted);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception ignored) {}
        }
    }
}
