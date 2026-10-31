package com.inceptless.traceweave;

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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class AgentFloatingService extends Service {
    private static final String TAG = "AgentFloatingService";
    private final android.os.Handler sensorHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable sensorRefresh = new Runnable() {
        @Override public void run() {
            if (!isExpanded || floatingView == null) return;
            updateSensorText(isEnglish());
            sensorHandler.postDelayed(this, 2000);
        }
    };
    private void updateSensorText(boolean en) {
        if (sensoriumDetailView != null) sensoriumDetailView.setText(SensorContext.describe(
            PhysicalSensoriumManager.getInstance(this).evaluateCurrentContext(), en));
    }

    public static final String ACTION_SHOW = "com.inceptless.traceweave.floating.SHOW";
    public static final String ACTION_HIDE = "com.inceptless.traceweave.floating.HIDE";
    public static final String ACTION_TOGGLE = "com.inceptless.traceweave.floating.TOGGLE";
    public static final String ACTION_UPDATE_STEP = "com.inceptless.traceweave.floating.UPDATE_STEP";
    public static final String ACTION_REQUEST_JIT = "com.inceptless.traceweave.floating.REQUEST_JIT";
    public static final String ACTION_UPDATE_VOICE = "com.inceptless.traceweave.floating.UPDATE_VOICE";
    public static final String ACTION_UPDATE_LOCALE = "com.inceptless.traceweave.floating.UPDATE_LOCALE";

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

    // UI Structure
    private FrameLayout rootContainer;
    private FrameLayout ballView;
    private ImageView ballDotView;
    private LinearLayout expandedCard;

    // Elements inside expanded card
    private ImageView statusDotView;
    private TextView statusTextView;
    private TextView collapseBtn;
    private TextView jitTitle;
    private TextView taskDetailView;
    private TextView sensoriumDetailView;
    private LinearLayout voiceWaveformBar;
    private LinearLayout jitContainer;
    private TextView jitPromptView;
    private Button btnGrant;
    private Button btnDeny;

    // Action buttons inside expanded card
    private Button btnVoice;
    private Button btnVision;
    private Button btnPtt;
    private Button btnOpenApp;

    private boolean isExpanded = false;
    private String pendingTaskId;
    private String pendingCap;
    private String pendingScope;

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private boolean isEnglish() {
        return "en".equals(getSharedPreferences("agent_os_prefs", Context.MODE_PRIVATE).getString("app_lang", "zh"));
    }

    private void updateLocaleTexts(boolean en) {
        if (statusTextView != null) {
            statusTextView.setText(T.s("agentfloatingservice.traceweave_capsule", en));
        }
        if (collapseBtn != null) {
            collapseBtn.setText(T.s("agentfloatingservice.collapse", en));
        }
        if (jitTitle != null) {
            jitTitle.setText(T.s("agentfloatingservice.cross_app_jit_gate_request", en));
        }
        if (btnGrant != null) {
            btnGrant.setText(T.s("agentfloatingservice.grant_5_min", en));
        }
        if (btnDeny != null) {
            btnDeny.setText(T.s("agentfloatingservice.deny", en));
        }
        if (taskDetailView != null && (pendingTaskId == null || pendingTaskId.isEmpty())) {
            taskDetailView.setText(T.s("agentfloatingservice.core_local_rule_planner_ready", en));
        }
        if (sensoriumDetailView != null) {
            updateSensorText(en);
        }
        if (btnVoice != null) {
            boolean listening = VoiceInteractionManager.getInstance(this).isListening();
            btnVoice.setText(T.s(listening ? "overlay.stop_audio" : "overlay.live_audio", en));
        }
        if (btnVision != null) {
            btnVision.setText(T.s("agentfloatingservice.vision_ocr", en));
        }
        if (btnPtt != null) {
            btnPtt.setText(T.s("agentfloatingservice.ptt_key", en));
        }
        if (btnOpenApp != null) {
            btnOpenApp.setText(T.s("agentfloatingservice.traceweave_os", en));
        }
        if (floatingView != null) {
            floatingView.postInvalidate();
            floatingView.requestLayout();
            try {
                if (windowManager != null && floatingParams != null) {
                    windowManager.updateViewLayout(floatingView, floatingParams);
                }
            } catch (Exception ignored) {}
        }
    }

    private void updateLocaleTexts() {
        updateLocaleTexts(isEnglish());
    }

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

    private synchronized void initFloatingLayout() {
        if (floatingView != null) {
            floatingView.setVisibility(View.VISIBLE);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted; overlay running in fallback mode");
            return;
        }

        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
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
        floatingParams.x = dp(16);
        floatingParams.y = dp(110);

        rootContainer = new FrameLayout(this);

        // ==========================================
        // 1. COLLAPSED VIEW: CIRCULAR FLOATING BALL
        // ==========================================
        ballView = new FrameLayout(this);
        int ballSize = dp(48);
        FrameLayout.LayoutParams ballLp = new FrameLayout.LayoutParams(ballSize, ballSize);
        ballView.setLayoutParams(ballLp);

        GradientDrawable ballBg = new GradientDrawable();
        ballBg.setShape(GradientDrawable.OVAL);
        ballBg.setColor(Color.argb(240, 15, 23, 42)); // Deep slate navy
        ballBg.setStroke(dp(2), Color.argb(220, 74, 246, 195)); // Glowing cyan
        ballView.setBackground(ballBg);

        ImageView ballIcon = IconFactory.createIconView(this, IconFactory.IconType.BRAIN, 0xFF4AF6C3, 22);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        ballView.addView(ballIcon, iconLp);

        ballDotView = IconFactory.createIconView(this, IconFactory.IconType.STATUS_DOT_GREEN, 0xFF00E676, 8);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        dotLp.setMargins(0, dp(4), dp(4), 0);
        ballView.addView(ballDotView, dotLp);

        attachDragAndClickListener(ballView, this::toggleExpansion);
        rootContainer.addView(ballView);

        // ==========================================
        // 2. EXPANDED VIEW: TRACEWEAVE CAPSULE CARD
        // ==========================================
        expandedCard = new LinearLayout(this);
        expandedCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.argb(245, 15, 23, 42));
        cardBg.setCornerRadius(dp(16));
        cardBg.setStroke(dp(1), Color.argb(160, 74, 246, 195));
        expandedCard.setBackground(cardBg);
        expandedCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        expandedCard.setLayoutParams(new FrameLayout.LayoutParams(dp(290), FrameLayout.LayoutParams.WRAP_CONTENT));

        // --- Header Row ---
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        statusDotView = IconFactory.createIconView(this, IconFactory.IconType.STATUS_DOT_GREEN, 0xFF00E676, 10);
        headerRow.addView(statusDotView);

        statusTextView = new TextView(this);
        statusTextView.setText(" Traceweave Capsule");
        statusTextView.setTextColor(Color.parseColor("#4AF6C3"));
        statusTextView.setTextSize(13);
        statusTextView.setTypeface(null, android.graphics.Typeface.BOLD);
        statusTextView.setPadding(dp(6), 0, 0, 0);
        headerRow.addView(statusTextView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        collapseBtn = new TextView(this);
        collapseBtn.setText(T.s("overlay.collapse", isEnglish()));
        collapseBtn.setTextSize(11);
        collapseBtn.setTextColor(Color.parseColor("#94A3B8"));
        collapseBtn.setPadding(dp(8), dp(4), dp(4), dp(4));
        collapseBtn.setOnClickListener(v -> toggleExpansion());
        headerRow.addView(collapseBtn);

        attachDragAndClickListener(headerRow, null);
        expandedCard.addView(headerRow);

        // --- Section: Essential Alerts ---
        // Alert 1: JIT Security Gate (hidden by default)
        jitContainer = new LinearLayout(this);
        jitContainer.setOrientation(LinearLayout.VERTICAL);
        jitContainer.setVisibility(View.GONE);
        jitContainer.setPadding(0, dp(8), 0, dp(4));

        LinearLayout jitTitleRow = new LinearLayout(this);
        jitTitleRow.setOrientation(LinearLayout.HORIZONTAL);
        jitTitleRow.setGravity(Gravity.CENTER_VERTICAL);
        jitTitleRow.addView(IconFactory.createIconView(this, IconFactory.IconType.ALERT, Color.parseColor("#FFD54F"), 14));
        jitTitle = new TextView(this);
        jitTitle.setText(" " + T.s("overlay.jit_title", isEnglish()));
        jitTitle.setTextColor(Color.parseColor("#FFD54F"));
        jitTitle.setTextSize(12);
        jitTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        jitTitleRow.addView(jitTitle);
        jitContainer.addView(jitTitleRow);

        jitPromptView = new TextView(this);
        jitPromptView.setTextColor(Color.WHITE);
        jitPromptView.setTextSize(11);
        jitPromptView.setPadding(0, dp(2), 0, dp(4));
        jitContainer.addView(jitPromptView);

        LinearLayout jitBtnRow = new LinearLayout(this);
        jitBtnRow.setOrientation(LinearLayout.HORIZONTAL);

        btnGrant = createSmallButton(T.s("overlay.grant_five_minutes", isEnglish()), Color.parseColor("#1B5E20"), Color.WHITE, v -> handleJitDecision(true));
        jitBtnRow.addView(btnGrant, new LinearLayout.LayoutParams(0, dp(28), 1.0f));

        btnDeny = createSmallButton(T.s("overlay.deny", isEnglish()), Color.parseColor("#B71C1C"), Color.WHITE, v -> handleJitDecision(false));
        LinearLayout.LayoutParams denyLp = new LinearLayout.LayoutParams(0, dp(28), 1.0f);
        denyLp.setMargins(dp(6), 0, 0, 0);
        jitBtnRow.addView(btnDeny, denyLp);

        jitContainer.addView(jitBtnRow);
        expandedCard.addView(jitContainer);

        // Alert 2: Active Task Status
        LinearLayout taskRow = new LinearLayout(this);
        taskRow.setOrientation(LinearLayout.HORIZONTAL);
        taskRow.setGravity(Gravity.CENTER_VERTICAL);
        taskRow.setPadding(0, dp(6), 0, 0);
        taskRow.addView(IconFactory.createIconView(this, IconFactory.IconType.TASK, Color.parseColor("#94A3B8"), 13));
        taskDetailView = new TextView(this);
        taskDetailView.setText(" " + T.s("overlay.core_ready", isEnglish()));
        taskDetailView.setTextColor(Color.parseColor("#E2E8F0"));
        taskDetailView.setTextSize(11);
        taskRow.addView(taskDetailView);
        expandedCard.addView(taskRow);

        // Alert 3: Physical Sensorium Context
        LinearLayout sensRow = new LinearLayout(this);
        sensRow.setOrientation(LinearLayout.HORIZONTAL);
        sensRow.setGravity(Gravity.CENTER_VERTICAL);
        sensRow.setPadding(0, dp(4), 0, 0);
        sensRow.addView(IconFactory.createIconView(this, IconFactory.IconType.SENSOR, Color.parseColor("#38BDF8"), 13));
        sensoriumDetailView = new TextView(this);
        updateSensorText(isEnglish());
        sensoriumDetailView.setTextColor(Color.parseColor("#38BDF8"));
        sensoriumDetailView.setTextSize(11);
        sensRow.addView(sensoriumDetailView);
        expandedCard.addView(sensRow);

        // Alert 4: Live Audio Waveform
        voiceWaveformBar = new LinearLayout(this);
        voiceWaveformBar.setOrientation(LinearLayout.HORIZONTAL);
        voiceWaveformBar.setGravity(Gravity.CENTER_VERTICAL);
        voiceWaveformBar.setPadding(0, dp(6), 0, dp(4));
        for (int i = 0; i < 6; i++) {
            View bar = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(4), dp(10));
            lp.setMargins(dp(2), 0, dp(2), 0);
            bar.setLayoutParams(lp);
            bar.setBackgroundColor(Color.parseColor("#4AF6C3"));
            voiceWaveformBar.addView(bar);
        }
        expandedCard.addView(voiceWaveformBar);

        // --- Section: Essential Function Buttons Row ---
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, dp(6), 0, 0);

        btnVoice = createSmallButton(T.s("overlay.live_audio", isEnglish()), Color.argb(220, 2, 132, 199), Color.WHITE, v -> toggleVoiceListening());
        actionRow.addView(btnVoice, new LinearLayout.LayoutParams(0, dp(30), 1.0f));

        btnVision = createSmallButton(T.s("overlay.vision", isEnglish()), Color.argb(200, 30, 41, 59), Color.parseColor("#E2E8F0"), v -> triggerVisionInspection());
        LinearLayout.LayoutParams visLp = new LinearLayout.LayoutParams(0, dp(30), 1.0f);
        visLp.setMargins(dp(4), 0, 0, 0);
        actionRow.addView(btnVision, visLp);

        btnPtt = createSmallButton(T.s("overlay.ptt", isEnglish()), Color.argb(200, 30, 41, 59), Color.parseColor("#E2E8F0"), v -> triggerPttAction());
        LinearLayout.LayoutParams pttLp = new LinearLayout.LayoutParams(0, dp(30), 1.0f);
        pttLp.setMargins(dp(4), 0, 0, 0);
        actionRow.addView(btnPtt, pttLp);

        btnOpenApp = createSmallButton(T.s("overlay.open_app", isEnglish()), Color.argb(200, 30, 41, 59), Color.parseColor("#E2E8F0"), v -> launchMainActivity());
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(0, dp(30), 0.9f);
        openLp.setMargins(dp(4), 0, 0, 0);
        actionRow.addView(btnOpenApp, openLp);

        expandedCard.addView(actionRow);

        // Initially collapsed into the circular ball
        expandedCard.setVisibility(View.GONE);
        ballView.setVisibility(View.VISIBLE);
        rootContainer.addView(expandedCard);
        updateLocaleTexts();

        floatingView = rootContainer;
        try {
            windowManager.addView(floatingView, floatingParams);
            Log.i(TAG, "Traceweave floating capsule attached successfully");
        } catch (Exception e) {
            Log.w(TAG, "Failed to attach floating overlay: " + e.getMessage());
            floatingView = null;
        }
    }

    private Button createSmallButton(String text, int bgColor, int textColor, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(10);
        btn.setTextColor(textColor);
        btn.setAllCaps(false);
        btn.setPadding(0, 0, 0, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(8));
        btn.setBackground(bg);
        btn.setOnClickListener(listener);
        return btn;
    }

    private void attachDragAndClickListener(View target, Runnable clickAction) {
        target.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isMoving = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        if (floatingParams != null) {
                            initialX = floatingParams.x;
                            initialY = floatingParams.y;
                        }
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
                        if (floatingParams != null && floatingView != null && windowManager != null) {
                            floatingParams.x = Math.max(0, initialX - dx);
                            floatingParams.y = Math.max(0, initialY + dy);
                            try {
                                windowManager.updateViewLayout(floatingView, floatingParams);
                            } catch (Exception ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!isMoving && clickAction != null) {
                            clickAction.run();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void toggleExpansion() {
        isExpanded = !isExpanded;
        sensorHandler.removeCallbacks(sensorRefresh);
        if (isExpanded) sensorHandler.post(sensorRefresh);
        if (isExpanded) {
            updateLocaleTexts();
        }
        if (ballView != null && expandedCard != null) {
            ballView.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
            expandedCard.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
        }
        try {
            if (floatingView != null && floatingParams != null && windowManager != null) {
                windowManager.updateViewLayout(floatingView, floatingParams);
            }
        } catch (Exception ignored) {}
    }

    private void toggleVoiceListening() {
        VoiceInteractionManager vim = VoiceInteractionManager.getInstance(this);
        vim.toggleListening(new VoiceInteractionManager.VoiceListener() {
            @Override
            public void onListeningStateChanged(boolean isListening) {
                boolean en = isEnglish();
                int dotColor = isListening ? 0xFF29B6F6 : 0xFF00E676;
                IconFactory.IconType iconType = isListening ? IconFactory.IconType.STATUS_DOT_BLUE : IconFactory.IconType.STATUS_DOT_GREEN;
                if (statusDotView != null) statusDotView.setImageDrawable(IconFactory.getIcon(AgentFloatingService.this, iconType, dotColor, 10));
                if (ballDotView != null) ballDotView.setImageDrawable(IconFactory.getIcon(AgentFloatingService.this, iconType, dotColor, 8));
                if (btnVoice != null) btnVoice.setText(T.s(isListening ? "overlay.stop_audio" : "overlay.live_audio", en));
                if (statusTextView != null) statusTextView.setText(isListening
                        ? (T.s("agentfloatingservice.listening_16_khz", en))
                        : (T.s("agentfloatingservice.traceweave_capsule", en)));
            }

            @Override
            public void onVoiceMetrics(float energyRms, float zcr, boolean isSpeech, boolean isGated) {
                if (voiceWaveformBar != null && isSpeech && !isGated) {
                    for (int i = 0; i < voiceWaveformBar.getChildCount(); i++) {
                        View bar = voiceWaveformBar.getChildAt(i);
                        int height = Math.min(dp(22), (int) (dp(6) + energyRms * 200.0f * (i + 1)));
                        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bar.getLayoutParams();
                        lp.height = height;
                        bar.setLayoutParams(lp);
                    }
                }
            }

            @Override
            public void onSpeechStart() {
                boolean en = isEnglish();
                if (statusDotView != null) statusDotView.setImageDrawable(IconFactory.getIcon(AgentFloatingService.this, IconFactory.IconType.STATUS_DOT_AMBER, 0xFFFFB300, 10));
                if (ballDotView != null) ballDotView.setImageDrawable(IconFactory.getIcon(AgentFloatingService.this, IconFactory.IconType.STATUS_DOT_AMBER, 0xFFFFB300, 8));
                if (statusTextView != null) statusTextView.setText(T.s("agentfloatingservice.capturing_speech", en));
            }

            @Override
            public void onSpeechEnd(short[] speechAudio, String classifiedIntent) {
                boolean en = isEnglish();
                if (statusDotView != null) statusDotView.setImageDrawable(IconFactory.getIcon(AgentFloatingService.this, IconFactory.IconType.STATUS_DOT_GREEN, 0xFF00E676, 10));
                if (ballDotView != null) ballDotView.setImageDrawable(IconFactory.getIcon(AgentFloatingService.this, IconFactory.IconType.STATUS_DOT_GREEN, 0xFF00E676, 8));
                if (statusTextView != null) statusTextView.setText(en
                        ? " Intent: " + englishVoiceIntent(classifiedIntent)
                        : " " + T.format("overlay.intent", false, classifiedIntent));
            }

            @Override
            public void onError(String message) {
                if (statusTextView != null) statusTextView.setText(englishVoiceError(message));
            }
        });
    }

    private String englishVoiceIntent(String intent) {
        if (intent == null || intent.trim().isEmpty() || "speech_burst".equals(intent)
                || intent.matches(".*[\\u4e00-\\u9fff].*")) {
            return "Speech detected";
        }
        return intent;
    }

    private String englishVoiceError(String message) {
        if (!isEnglish()) return " " + T.format("overlay.audio_error", false, message);
        if ("RECORD_AUDIO 运行时权限未授予".equals(message)) return " " + T.s("overlay.mic_denied", true);
        if (message != null && message.startsWith("录音启动失败:")) return " " + T.s("overlay.audio_start_failed", true);
        if (message != null && message.startsWith("音频硬件读取故障:")) return " " + T.s("overlay.audio_hardware_failed", true);
        if (message == null || message.matches(".*[\\u4e00-\\u9fff].*")) return " Audio capture failed";
        return " Audio error: " + message;
    }

    private void triggerVisionInspection() {
        try {
            boolean en = isEnglish();
            String query = T.s("agentfloatingservice.new_agent_task", en);
            org.json.JSONObject target = VisionAgentManager.getInstance().locateTargetOnScreen(query);
            Toast.makeText(this, (T.s("agentfloatingservice.vision_sense", en)) + (target.optBoolean("matched", false) ? (T.s("agentfloatingservice.target_located", en)) : (T.s("agentfloatingservice.scanning_screen", en))), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, T.format("overlay.vision_error", isEnglish(), e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void triggerPttAction() {
        HardwarePeripheralService hwSvc = HardwarePeripheralService.getInstance();
        boolean nextState = !hwSvc.isPttActive();
        org.json.JSONObject pttRes = hwSvc.triggerPttAction(this, nextState);
        Toast.makeText(this, "PTT: " + pttRes.optString("action") + " (" + pttRes.optLong("latency_us") + "μs)", Toast.LENGTH_SHORT).show();
    }

    private void launchMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        Log.i(TAG, "onStartCommand action=" + action);

        if (ACTION_UPDATE_LOCALE.equals(action)) {
            boolean en = intent.getBooleanExtra("is_english", isEnglish());
            Log.i(TAG, "ACTION_UPDATE_LOCALE en=" + en);
            updateLocaleTexts(en);
            return START_STICKY;
        }

        if (ACTION_SHOW.equals(action) || ACTION_TOGGLE.equals(action)) {
            if (floatingView == null) {
                initFloatingLayout();
            } else if (ACTION_TOGGLE.equals(action)) {
                int nextVis = (floatingView.getVisibility() == View.VISIBLE) ? View.GONE : View.VISIBLE;
                floatingView.setVisibility(nextVis);
            } else {
                floatingView.setVisibility(View.VISIBLE);
            }
        } else if (ACTION_HIDE.equals(action)) {
            if (floatingView != null) {
                floatingView.setVisibility(View.GONE);
            }
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
                taskDetailView.setText(T.format("overlay.task_detail", isEnglish(), title, stepTitle));
            }
        } else if (ACTION_REQUEST_JIT.equals(action)) {
            pendingTaskId = intent.getStringExtra(EXTRA_TASK_ID);
            pendingCap = intent.getStringExtra(EXTRA_CAPABILITY);
            pendingScope = intent.getStringExtra(EXTRA_SCOPE);

            if (statusDotView != null) {
                statusDotView.setImageDrawable(IconFactory.getIcon(this, IconFactory.IconType.STATUS_DOT_AMBER, 0xFFFFB300, 10));
            }
            if (ballDotView != null) {
                ballDotView.setImageDrawable(IconFactory.getIcon(this, IconFactory.IconType.STATUS_DOT_AMBER, 0xFFFFB300, 8));
            }
            if (jitContainer != null && jitPromptView != null) {
                jitPromptView.setText(T.format("overlay.jit_prompt", isEnglish(), pendingTaskId, pendingCap));
                jitContainer.setVisibility(View.VISIBLE);
                // Auto-expand on security JIT alert so user never misses it
                if (!isExpanded) toggleExpansion();
                if (floatingView != null) floatingView.setVisibility(View.VISIBLE);
            }
        } else if (ACTION_UPDATE_VOICE.equals(action)) {
            float energy = intent.getFloatExtra(EXTRA_ENERGY_LEVEL, 0.0f);
            if (voiceWaveformBar != null) {
                for (int i = 0; i < voiceWaveformBar.getChildCount(); i++) {
                    View bar = voiceWaveformBar.getChildAt(i);
                    int height = Math.min(dp(22), (int) (dp(6) + energy * 200.0f * (i + 1)));
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
        if (ballDotView != null) {
            ballDotView.setImageDrawable(IconFactory.getIcon(this,
                    granted ? IconFactory.IconType.STATUS_DOT_GREEN : IconFactory.IconType.STATUS_DOT_AMBER,
                    granted ? 0xFF00E676 : 0xFFFFB300, 8));
        }
        if (statusTextView != null) {
            statusTextView.setText(" " + T.s(granted ? "overlay.jit_granted" : "overlay.jit_denied", isEnglish()));
        }
        if (sJitListener != null) {
            sJitListener.onDecision(pendingTaskId, pendingCap, pendingScope, granted);
        }
    }

    @Override
    public void onDestroy() {
        sensorHandler.removeCallbacks(sensorRefresh);
        super.onDestroy();
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception ignored) {}
            floatingView = null;
        }
    }
}
