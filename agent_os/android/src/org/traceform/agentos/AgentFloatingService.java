package org.traceform.agentos;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
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
    public static final String ACTION_UPDATE_VOICE = "org.traceform.agentos.floating.UPDATE_VOICE";

    public static final String EXTRA_TASK_ID = "task_id";
    public static final String EXTRA_TASK_TITLE = "task_title";
    public static final String EXTRA_STEP_INDEX = "step_index";
    public static final String EXTRA_STEP_TITLE = "step_title";
    public static final String EXTRA_CAPABILITY = "capability";
    public static final String EXTRA_SCOPE = "scope";
    public static final String EXTRA_ENERGY_LEVEL = "energy_level";

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

    // UI Elements
    private LinearLayout rootLayout;
    private LinearLayout pillHeader;
    private android.widget.ImageView statusDotView;
    private TextView statusTextView;
    private android.widget.ImageView micButtonView;
    private LinearLayout expandedPanel;
    private TextView taskDetailView;
    private TextView screenInspectorView;
    private LinearLayout voiceWaveformBar;
    private LinearLayout jitContainer;
    private TextView jitPromptView;
    private Button btnGrant;
    private Button btnDeny;

    private boolean isExpanded = false;
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
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted; overlay running in fallback mode");
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

        floatingParams.gravity = Gravity.TOP | Gravity.END;
        floatingParams.x = 36;
        floatingParams.y = 130;

        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(235, 18, 22, 30));
        bg.setCornerRadius(32);
        bg.setStroke(2, Color.argb(120, 74, 246, 195));
        rootLayout.setBackground(bg);
        rootLayout.setPadding(24, 18, 24, 18);

        // --- 1. Compact Pill Header ---
        pillHeader = new LinearLayout(this);
        pillHeader.setOrientation(LinearLayout.HORIZONTAL);
        pillHeader.setGravity(Gravity.CENTER_VERTICAL);

        statusDotView = IconFactory.createIconView(this, IconFactory.IconType.STATUS_DOT_GREEN, 0xFF00E676, 10);
        pillHeader.addView(statusDotView);

        statusTextView = new TextView(this);
        statusTextView.setText(" Agent Capsule: 离线就绪");
        statusTextView.setTextColor(Color.parseColor("#4AF6C3"));
        statusTextView.setTextSize(13);
        statusTextView.setPadding(8, 0, 12, 0);
        pillHeader.addView(statusTextView);

        micButtonView = IconFactory.createIconView(this, IconFactory.IconType.MIC, Color.parseColor("#4AF6C3"), 18);
        micButtonView.setOnClickListener(v -> toggleVoiceListening());
        pillHeader.addView(micButtonView);

        // Click pill to toggle expansion
        pillHeader.setOnClickListener(v -> toggleExpansion());
        rootLayout.addView(pillHeader);

        // --- 2. Expanded Dashboard Panel ---
        expandedPanel = new LinearLayout(this);
        expandedPanel.setOrientation(LinearLayout.VERTICAL);
        expandedPanel.setVisibility(View.GONE);
        expandedPanel.setPadding(0, 16, 0, 0);

        LinearLayout taskRow = new LinearLayout(this);
        taskRow.setOrientation(LinearLayout.HORIZONTAL);
        taskRow.setGravity(Gravity.CENTER_VERTICAL);
        taskRow.addView(IconFactory.createIconView(this, IconFactory.IconType.TASK, Color.parseColor("#E0E0E0"), 14));
        taskDetailView = new TextView(this);
        taskDetailView.setText(" 待命任务 · Qwen2.5-0.5B 常驻运行");
        taskDetailView.setTextColor(Color.parseColor("#E0E0E0"));
        taskDetailView.setTextSize(12);
        taskRow.addView(taskDetailView);
        expandedPanel.addView(taskRow);

        LinearLayout screenRow = new LinearLayout(this);
        screenRow.setOrientation(LinearLayout.HORIZONTAL);
        screenRow.setGravity(Gravity.CENTER_VERTICAL);
        screenRow.setPadding(0, 8, 0, 0);
        screenRow.addView(IconFactory.createIconView(this, IconFactory.IconType.SCREEN, Color.parseColor("#80D8FF"), 14));
        screenInspectorView = new TextView(this);
        screenInspectorView.setText(" 屏幕感知: 无障碍自动化中枢就绪");
        screenInspectorView.setTextColor(Color.parseColor("#80D8FF"));
        screenInspectorView.setTextSize(11);
        screenRow.addView(screenInspectorView);
        expandedPanel.addView(screenRow);

        // Voice Waveform Visualizer
        voiceWaveformBar = new LinearLayout(this);
        voiceWaveformBar.setOrientation(LinearLayout.HORIZONTAL);
        voiceWaveformBar.setGravity(Gravity.CENTER_VERTICAL);
        voiceWaveformBar.setPadding(0, 8, 0, 0);
        for (int i = 0; i < 5; i++) {
            View bar = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(8, 20);
            lp.setMargins(4, 0, 4, 0);
            bar.setLayoutParams(lp);
            bar.setBackgroundColor(Color.parseColor("#4AF6C3"));
            voiceWaveformBar.addView(bar);
        }
        expandedPanel.addView(voiceWaveformBar);

        // --- 3. Cross-App JIT Approval Container ---
        jitContainer = new LinearLayout(this);
        jitContainer.setOrientation(LinearLayout.VERTICAL);
        jitContainer.setVisibility(View.GONE);
        jitContainer.setPadding(0, 14, 0, 0);

        LinearLayout jitTitleRow = new LinearLayout(this);
        jitTitleRow.setOrientation(LinearLayout.HORIZONTAL);
        jitTitleRow.setGravity(Gravity.CENTER_VERTICAL);
        jitTitleRow.addView(IconFactory.createIconView(this, IconFactory.IconType.ALERT, Color.parseColor("#FFD54F"), 16));
        TextView jitTitle = new TextView(this);
        jitTitle.setText(" 跨应用 JIT 安全门禁");
        jitTitle.setTextColor(Color.parseColor("#FFD54F"));
        jitTitle.setTextSize(12);
        jitTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        jitTitleRow.addView(jitTitle);
        jitContainer.addView(jitTitleRow);

        jitPromptView = new TextView(this);
        jitPromptView.setTextColor(Color.WHITE);
        jitPromptView.setTextSize(12);
        jitPromptView.setPadding(0, 4, 0, 0);
        jitContainer.addView(jitPromptView);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, 10, 0, 0);

        btnGrant = new Button(this);
        btnGrant.setText("授权 5 分钟");
        btnGrant.setBackgroundColor(Color.parseColor("#1B5E20"));
        btnGrant.setTextColor(Color.WHITE);
        btnGrant.setTextSize(11);
        btnGrant.setOnClickListener(v -> handleJitDecision(true));
        btnRow.addView(btnGrant);

        btnDeny = new Button(this);
        btnDeny.setText("拒绝");
        btnDeny.setBackgroundColor(Color.parseColor("#B71C1C"));
        btnDeny.setTextColor(Color.WHITE);
        btnDeny.setTextSize(11);
        btnDeny.setOnClickListener(v -> handleJitDecision(false));
        btnRow.addView(btnDeny);

        jitContainer.addView(btnRow);
        expandedPanel.addView(jitContainer);

        rootLayout.addView(expandedPanel);

        // Draggable touch handler
        rootLayout.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isMoving = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = floatingParams.x;
                        initialY = floatingParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isMoving = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isMoving = true;
                        }
                        floatingParams.x = initialX + dx;
                        floatingParams.y = initialY + dy;
                        try {
                            windowManager.updateViewLayout(floatingView, floatingParams);
                        } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!isMoving) {
                            toggleExpansion();
                        }
                        return true;
                }
                return false;
            }
        });

        floatingView = rootLayout;
        try {
            windowManager.addView(floatingView, floatingParams);
            Log.i(TAG, "Agent floating capsule 2.0 attached successfully");
        } catch (Exception e) {
            Log.w(TAG, "Failed to attach floating overlay: " + e.getMessage());
        }
    }

    private void toggleExpansion() {
        isExpanded = !isExpanded;
        if (expandedPanel != null) {
            expandedPanel.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
        }
        try {
            windowManager.updateViewLayout(floatingView, floatingParams);
        } catch (Exception ignored) {}
    }

    private void toggleVoiceListening() {
        if (statusDotView != null) {
            statusDotView.setImageDrawable(IconFactory.getIcon(this, IconFactory.IconType.STATUS_DOT_BLUE, 0xFF29B6F6, 10));
        }
        if (statusTextView != null) {
            statusTextView.setText(" 语音中枢聆听中 (16kHz PCM)");
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
            if (statusDotView != null) {
                statusDotView.setImageDrawable(IconFactory.getIcon(this, IconFactory.IconType.STATUS_DOT_GREEN, 0xFF00E676, 10));
            }
            if (statusTextView != null) {
                statusTextView.setText(" [" + (step + 1) + "] " + (stepTitle != null ? stepTitle : title));
            }
            if (taskDetailView != null) {
                taskDetailView.setText("当前任务: " + title + "\n当前步骤: " + stepTitle);
            }
        } else if (ACTION_REQUEST_JIT.equals(action)) {
            pendingTaskId = intent.getStringExtra(EXTRA_TASK_ID);
            pendingCap = intent.getStringExtra(EXTRA_CAPABILITY);
            pendingScope = intent.getStringExtra(EXTRA_SCOPE);

            if (statusDotView != null) {
                statusDotView.setImageDrawable(IconFactory.getIcon(this, IconFactory.IconType.STATUS_DOT_AMBER, 0xFFFFB300, 10));
            }
            if (jitContainer != null && jitPromptView != null) {
                jitPromptView.setText("任务: " + pendingTaskId + "\n申请权限: " + pendingCap);
                jitContainer.setVisibility(View.VISIBLE);
                if (expandedPanel != null) expandedPanel.setVisibility(View.VISIBLE);
                if (floatingView != null) floatingView.setVisibility(View.VISIBLE);
            }
        } else if (ACTION_UPDATE_VOICE.equals(action)) {
            float energy = intent.getFloatExtra(EXTRA_ENERGY_LEVEL, 0.0f);
            if (voiceWaveformBar != null) {
                for (int i = 0; i < voiceWaveformBar.getChildCount(); i++) {
                    View bar = voiceWaveformBar.getChildAt(i);
                    int height = Math.min(60, (int) (15 + energy * 300.0f * (i + 1)));
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bar.getLayoutParams();
                    lp.height = height;
                    bar.setLayoutParams(lp);
                }
            }
        }

        return START_NOT_STICKY;
    }

    private void handleJitDecision(boolean granted) {
        if (jitContainer != null) {
            jitContainer.setVisibility(View.GONE);
        }
        if (statusDotView != null) {
            statusDotView.setImageDrawable(IconFactory.getIcon(this,
                    granted ? IconFactory.IconType.STATUS_DOT_GREEN : IconFactory.IconType.STATUS_DOT_AMBER,
                    granted ? 0xFF00E676 : 0xFFFFB300, 10));
        }
        if (statusTextView != null) {
            statusTextView.setText(granted ? " JIT 租约已生效 (5分钟)" : " JIT 授权已拒绝");
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
