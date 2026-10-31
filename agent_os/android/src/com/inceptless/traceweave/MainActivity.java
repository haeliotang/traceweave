package com.inceptless.traceweave;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements MemoryHost {
    private static final int CREATE_ARCHIVE_REQUEST = 1;
    private static final int OPEN_ARCHIVE_REQUEST = 2;
    private static final int SELECT_REMOTE_STATUS_REQUEST = 3;
    private static final String STATE_REMOTE_URI_EXPIRES_AT = "remoteUriExpiresAtElapsed";
    private static final long REMOTE_URI_LIFETIME_MILLIS = 5 * 60 * 1000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable taskRefresh = this::refreshTasks;
    private final Runnable sensorRefresh = new Runnable() {
        @Override public void run() {
            updateSensorsCard();
            if (PhysicalSensoriumManager.getInstance(MainActivity.this).isMonitoring()) mainHandler.postDelayed(this, 1000);
        }
    };
    final java.util.ArrayList<Button> persistentButtons = new java.util.ArrayList<>();
    private final java.util.ArrayList<Button> taskButtons = new java.util.ArrayList<>();
    private AgentStore store;
    private CapabilityBroker capabilityBroker;
    private EditText taskInput;
    private ScrollView pageScroll;
    private LinearLayout onboardingCard;
    private String taskFilter = "all";
    private String taskQuery = "";
    private TextView bottomSummary;
    private LinearLayout taskList;
    private TextView floatPermStatus;
    private TextView vadLiveStatus;
    private Button btnToggleVoice;
    private static final int REQ_RECORD_AUDIO = 2001;
    private static final int REQ_NOTIFICATIONS = 7002;
    private static final int SYNC_PORT = 47960;
    private char[] pendingExportPassphrase;
    private boolean busy;
    private boolean destroyed;
    private String pendingRemoteStatusTaskId;
    private long remoteUriExpiresAtElapsed;
    private final Runnable remoteUriExpiry = this::expireRemoteUriGrant;
    private TextView sensInfo;
    private TextView slmInfo;
    private Button sensorToggleButton;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) remoteUriExpiresAtElapsed = state.getLong(STATE_REMOTE_URI_EXPIRES_AT);
        store = new AgentStore(this);
        capabilityBroker = new CapabilityBroker(this, store);
        buildScreen();
        refreshTasks();
        refreshMemories();
        scheduleRemoteUriExpiry();
        updateSensorsCard();
        maybeRequestNotificationPermission(false);
        handleShortcutIntent(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShortcutIntent(intent);
    }

    private void handleShortcutIntent(android.content.Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        // Static shortcuts must target an exported activity; AuditActivity and
        // TaskCenterActivity are not exported, so MainActivity forwards them.
        if ("com.inceptless.traceweave.OPEN_AUDIT".equals(intent.getAction())) {
            intent.setAction(null);
            startActivity(new android.content.Intent(this, AuditActivity.class));
            return;
        }
        if ("com.inceptless.traceweave.OPEN_CENTER".equals(intent.getAction())) {
            intent.setAction(null);
            startActivity(new android.content.Intent(this, TaskCenterActivity.class));
            return;
        }
        if (!"com.inceptless.traceweave.NEW_TASK".equals(intent.getAction())) return;
        intent.setAction(null);
        if (pageScroll != null) pageScroll.post(() -> pageScroll.fullScroll(android.view.View.FOCUS_UP));
        if (taskInput != null) {
            taskInput.post(() -> {
                taskInput.requestFocus();
                android.view.inputmethod.InputMethodManager keyboard =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.showSoftInput(taskInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSensorsCard();
        updateLocalModelStatus();
        updateSensorToggleButton();
        updateFloatPermStatus();
        expireRemoteUriGrant();
        if (syncPanel != null) syncPanel.maybeAutoSync();
        try {
            store.recoverExternalActions();
            int recoveredJobs = AgentScheduler.reconcileActiveTasks(this, store);
            if (recoveredJobs > 0)
                settingsPanel.setRecoveryMessage(T.format("settings.recovered_jobs", isEnglish(), recoveredJobs));
        } catch (Exception error) {
            showError(error);
        }
        // Coming back from the detail page or the vault must show what changed there.
        refreshTasks();
        refreshMemories();
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(taskRefresh);
        mainHandler.removeCallbacks(sensorRefresh);
        PhysicalSensoriumManager.getInstance(this).stopMonitoring();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putLong(STATE_REMOTE_URI_EXPIRES_AT, remoteUriExpiresAtElapsed);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        VoiceInteractionManager.getInstance(this).stopListening();
        PhysicalSensoriumManager.getInstance(this).stopMonitoring();
        mainHandler.removeCallbacks(remoteUriExpiry);
        clearPendingExportPassphrase();
        io.execute(() -> store.close());
        io.shutdown();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, T.s("voice.mic_granted", isEnglish()), Toast.LENGTH_SHORT).show();
                startContinuousVoice();
            } else {
                Toast.makeText(this, T.s("voice.mic_required", isEnglish()), Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_NOTIFICATIONS) {
            boolean granted = grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            showMessage(T.s(granted ? "notify.enabled" : "notify.denied", isEnglish()));
        }
    }

    boolean notificationsGranted() {
        return android.os.Build.VERSION.SDK_INT < 33
            || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    void maybeRequestNotificationPermission(boolean userInitiated) {
        if (notificationsGranted()) {
            if (userInitiated) showMessage(T.s("notify.already", isEnglish()));
            return;
        }
        android.content.SharedPreferences prefs = getSharedPreferences("agent_os_prefs", 0);
        if (!userInitiated && prefs.getBoolean("notification_prompt_shown", false)) return;
        prefs.edit().putBoolean("notification_prompt_shown", true).apply();
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFICATIONS);
    }

    private boolean checkAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void toggleContinuousVoice() {
        VoiceInteractionManager vim = VoiceInteractionManager.getInstance(this);
        if (vim.isListening()) {
            vim.stopListening();
            if (btnToggleVoice != null) {
                btnToggleVoice.setText(T.s("voice.live", isEnglish()));
                btnToggleVoice.setTextColor(0xFF0284C7);
            }
            if (vadLiveStatus != null) {
                vadLiveStatus.setText(T.s("voice.stopped_ready", isEnglish()));
            }
        } else {
            if (!checkAudioPermission()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
                }
            } else {
                startContinuousVoice();
            }
        }
    }

    private void startContinuousVoice() {
        VoiceInteractionManager vim = VoiceInteractionManager.getInstance(this);
        vim.startListening(new VoiceInteractionManager.VoiceListener() {
            @Override
            public void onListeningStateChanged(boolean isListening) {
                if (btnToggleVoice != null) {
                    btnToggleVoice.setText(T.s(isListening ? "voice.stop" : "voice.live", isEnglish()));
                    btnToggleVoice.setTextColor(isListening ? 0xFFE11D48 : 0xFF0284C7);
                }
                if (vadLiveStatus != null) {
                    vadLiveStatus.setText(T.s(isListening ? "voice.listening" : "voice.stopped", isEnglish()));
                }
            }

            @Override
            public void onVoiceMetrics(float energyRms, float zcr, boolean isSpeech, boolean isGated) {
                if (vadLiveStatus != null) {
                    if (isGated) {
                        vadLiveStatus.setText(T.format("voice.rms_pocket", isEnglish(), energyRms));
                    } else if (isSpeech) {
                        vadLiveStatus.setText(T.format("voice.rms_speech", isEnglish(), energyRms, zcr));
                    } else {
                        vadLiveStatus.setText(T.format("voice.rms_noise", isEnglish(), energyRms, zcr));
                    }
                }
            }

            @Override
            public void onSpeechStart() {
                if (vadLiveStatus != null) {
                    vadLiveStatus.setText(T.s("voice.capturing", isEnglish()));
                }
            }

            @Override
            public void onSpeechEnd(short[] speechAudio, String classifiedIntent) {
                if (vadLiveStatus != null) {
                    vadLiveStatus.setText(T.format("voice.segment_done", isEnglish(), speechAudio.length, classifiedIntent));
                }
                Toast.makeText(MainActivity.this, T.format("voice.intent", isEnglish(), classifiedIntent), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                if (vadLiveStatus != null) {
                    vadLiveStatus.setText(T.format("voice.error", isEnglish(), message));
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CREATE_ARCHIVE_REQUEST) {
            char[] passphrase = pendingExportPassphrase;
            pendingExportPassphrase = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                clear(passphrase);
                setBusy(false, T.s("archive.export_cancelled", isEnglish()));
                return;
            }
            writeArchive(data.getData(), passphrase);
        } else if (requestCode == OPEN_ARCHIVE_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                promptImportPassphrase(data.getData());
            } else {
                setBusy(false, T.s("archive.import_cancelled", isEnglish()));
            }
        } else if (requestCode == SELECT_REMOTE_STATUS_REQUEST) {
            String taskId = pendingRemoteStatusTaskId;
            pendingRemoteStatusTaskId = null;
            Uri selectedUri = data == null ? null : data.getData();
            if (resultCode == RESULT_OK && selectedUri != null && taskId != null
                    && (data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
                    && capabilityBroker.acceptRemoteUri(taskId, selectedUri)) {
                if (remoteUriExpiresAtElapsed == 0) {
                    remoteUriExpiresAtElapsed = SystemClock.elapsedRealtime() + REMOTE_URI_LIFETIME_MILLIS;
                    scheduleRemoteUriExpiry();
                }
                showMessage(T.s("cap.received_once", isEnglish()));
            } else {
                if (taskId != null) {
                    capabilityBroker.deny(taskId, AndroidRemoteStatusProvider.CAPABILITY,
                        AndroidRemoteStatusProvider.SCOPE);
                    capabilityBroker.releaseRemoteUri(taskId);
                }
                showMessage(T.s("cap.not_received", isEnglish()));
                if (selectedUri != null) {
                    restartMainActivityAfterRemoteGrantRelease();
                    return;
                }
            }
            refreshTasks();
        }
    }

    private boolean isEnglish() {
        return "en".equals(getSharedPreferences("agent_os_prefs", Context.MODE_PRIVATE).getString("app_lang", "zh"));
    }

    private void toggleLanguage() {
        boolean nextIsEn = !isEnglish();
        getSharedPreferences("agent_os_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("app_lang", nextIsEn ? "en" : "zh")
                .commit();
        Toast.makeText(this, T.s("ui.switched_english", nextIsEn), Toast.LENGTH_SHORT).show();
        try {
            android.util.Log.i("MainActivity", "Sending ACTION_UPDATE_LOCALE nextIsEn=" + nextIsEn);
            Intent langIntent = new Intent(this, AgentFloatingService.class);
            langIntent.setAction(AgentFloatingService.ACTION_UPDATE_LOCALE);
            langIntent.putExtra("is_english", nextIsEn);
            startService(langIntent);
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Failed to send ACTION_UPDATE_LOCALE", e);
        }
        buildScreen();
        refreshTasks();
        refreshMemories();
    }

    private void buildScreen() {
        persistentButtons.clear();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Ui.dark(this) ? 0xFF0B1220 : 0xFFF8FAFC);
        page.setPadding(dp(16), dp(46), dp(16), dp(16));

        boolean en = isEnglish();

        // 1. Top Header Row: Traceweave OS Title + Language Switcher
        LinearLayout topHeaderRow = new LinearLayout(this);
        topHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        topHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams topHeaderLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        topHeaderRow.setLayoutParams(topHeaderLp);

        TextView title = new TextView(this);
        title.setText(T.s("ui.app_title", en));
        title.setTextSize(20);
        title.setTextColor(Ui.text(this));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        title.setLayoutParams(titleLp);
        topHeaderRow.addView(title);

        Button langBtn = new Button(this);
        langBtn.setText(en ? T.s("ui.chinese", en) : T.s("ui.english", en));
        langBtn.setTextSize(12);
        langBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        langBtn.setTextColor(0xFF0284C7);
        langBtn.setAllCaps(false);
        GradientDrawable langBg = new GradientDrawable();
        langBg.setColor(0xFFF0F9FF);
        langBg.setCornerRadius(dp(14));
        langBg.setStroke(dp(1), 0xFFBAE6FD);
        langBtn.setBackground(langBg);
        langBtn.setPadding(dp(12), dp(2), dp(12), dp(2));
        langBtn.setOnClickListener(v -> toggleLanguage());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
        topHeaderRow.addView(langBtn, btnLp);

        page.addView(topHeaderRow);

        // 2. Status badges, collapsed behind a diagnostics toggle: these are
        // implementation states (native fallback, daemon off) and only get in the way.
        Button diagnosticsToggle = createCompactActionButton(T.s("ui.diagnostics_closed", en), Ui.secondaryBg(this), Ui.secondaryFg(this), v -> { });
        diagnosticsToggle.setTextSize(11);
        LinearLayout.LayoutParams diagLp = new LinearLayout.LayoutParams(dp(96), dp(48));
        page.addView(diagnosticsToggle, diagLp);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(4), 0, dp(8));
        statusRow.setVisibility(View.GONE);
        diagnosticsToggle.setOnClickListener(v -> {
            boolean showing = statusRow.getVisibility() == View.VISIBLE;
            statusRow.setVisibility(showing ? View.GONE : View.VISIBLE);
            diagnosticsToggle.setText(T.s(showing ? "ui.diagnostics_closed" : "ui.diagnostics_open", en));
        });

        TextView rustBadge = IconFactory.createBadge(this, NativeAgentCore.isAvailable() ? "RUST NATIVE" : "JAVA FALLBACK",
                NativeAgentCore.isAvailable() ? 0xFFDCFCE7 : 0xFFFEF3C7,
                NativeAgentCore.isAvailable() ? 0xFF166534 : 0xFF92400E);
        statusRow.addView(rustBadge);

        TextView daemonBadge = IconFactory.createBadge(this, "DAEMON DISABLED", 0xFFFEF3C7, 0xFF92400E);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dLp.setMargins(dp(6), 0, 0, 0);
        statusRow.addView(daemonBadge, dLp);

        TextView deviceBadge = IconFactory.createBadge(this, "PIXEL 6A", 0xFFF1F5F9, 0xFF475569);
        LinearLayout.LayoutParams devLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        devLp.setMargins(dp(6), 0, 0, 0);
        statusRow.addView(deviceBadge, devLp);

        // 3. Two everyday cards on the page; the rest live under Diagnostics.
        LinearLayout diagnosticsPanel = new LinearLayout(this);
        diagnosticsPanel.setOrientation(LinearLayout.VERTICAL);
        diagnosticsPanel.setVisibility(View.GONE);
        diagnosticsToggle.setOnClickListener(v -> {
            boolean showing = diagnosticsPanel.getVisibility() == View.VISIBLE;
            diagnosticsPanel.setVisibility(showing ? View.GONE : View.VISIBLE);
            statusRow.setVisibility(showing ? View.GONE : View.VISIBLE);
            diagnosticsToggle.setText(T.s(showing ? "ui.diagnostics_closed" : "ui.diagnostics_open", en));
        });

        page.addView(statusRow);
        page.addView(diagnosticsPanel);

        LinearLayout gridRow1 = createGridRow(diagnosticsPanel);
        LinearLayout hwCard = createGridCard(gridRow1, IconFactory.IconType.CHIP, T.s("ui.hardware", en), Ui.cardAccent(this));
        TextView hwInfo = new TextView(this);
        hwInfo.setText(T.s("ui.tensor_g1_pixel_6a_nsynthetic_profile", en));
        hwInfo.setTextSize(12);
        hwInfo.setLineSpacing(dp(1), 1.0f);
        hwInfo.setTextColor(Ui.body(this));
        hwInfo.setPadding(0, dp(4), 0, 0);
        hwCard.addView(hwInfo);

        hwCard.setOnClickListener(v -> {
            try {
                String resJson = NativeAgentCore.tceExecuteBenchmark(0, 36.0f, 88.0f);
                org.json.JSONObject resObj = new org.json.JSONObject(resJson);
                org.json.JSONObject breakdown = resObj.getJSONObject("placement_breakdown");
                int cpu = breakdown.optInt("CPU_NEON", 0);
                int gpu = breakdown.optInt("GPU_VULKAN", 0);
                int tpu = breakdown.optInt("EDGE_TPU", 0);
                long latencyUs = resObj.optLong("total_latency_us", 0);
                double tflops = resObj.optDouble("effective_tflops", 0.0);

                hwInfo.setText((T.s("ui.scheduler_simulation_n", en))
                    + "GPU(" + gpu + ") TPU(" + tpu + ") CPU(" + cpu + ")\n"
                    + String.format(Locale.US, "%.2f simulated TFLOPS (%dμs)", tflops, latencyUs));
                Toast.makeText(this, (T.s("ui.synthetic_tce_simulation", en))
                    + "GPU:" + gpu + " TPU:" + tpu + " NEON:" + cpu + " (" + latencyUs + "μs)", Toast.LENGTH_SHORT).show();
            } catch (Exception ex) {
                Toast.makeText(this, "TCE: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        LinearLayout primaryRow = createGridRow(page);
        LinearLayout slmCard = createGridCard(primaryRow, IconFactory.IconType.BRAIN, T.s("ui.local_planner", en), Ui.cardAccent(this));
        slmInfo = new TextView(this);
        updateLocalModelStatus();
        slmInfo.setTextSize(12);
        slmInfo.setLineSpacing(dp(1), 1.0f);
        slmInfo.setTextColor(Ui.body(this));
        slmInfo.setPadding(0, dp(4), 0, 0);
        slmCard.addView(slmInfo);

        slmCard.setOnClickListener(v -> startActivity(new Intent(this, LocalModelActivity.class)));

        LinearLayout daemonCard = createGridCard(gridRow1, IconFactory.IconType.SERVER, T.s("ui.daemon", en), Ui.cardAccent(this));
        TextView daemonInfo = new TextView(this);
        daemonInfo.setText(T.s("status.daemon_off", en));
        daemonInfo.setTextSize(12);
        daemonInfo.setLineSpacing(dp(1), 1.0f);
        daemonInfo.setTextColor(Ui.body(this));
        daemonInfo.setPadding(0, dp(4), 0, 0);
        daemonCard.addView(daemonInfo);
        daemonCard.setOnClickListener(v -> showInfoCard(T.s("ui.daemon", isEnglish()),
            T.s("ui.daemon_disabled_body", isEnglish())));

        LinearLayout gridRow2 = createGridRow(diagnosticsPanel);
        LinearLayout syncCard = createGridCard(gridRow2, IconFactory.IconType.SHIELD, T.s("ui.encrypted_archive", en), Ui.cardAccent(this));
        TextView syncInfo = new TextView(this);
        syncInfo.setText(T.s("status.archive_local", en));
        syncInfo.setTextSize(12);
        syncInfo.setLineSpacing(dp(1), 1.0f);
        syncInfo.setTextColor(Ui.body(this));
        syncInfo.setPadding(0, dp(4), 0, 0);
        syncCard.addView(syncInfo);

        syncCard.setOnClickListener(v -> {
            try {
                int memCount = store != null ? store.listMemories().size() : 0;
                syncInfo.setText((T.s("ui.encrypted_memory_vault_n", en))
                        + T.format("count.memories_archive", en, memCount));
                Toast.makeText(this, T.format("archive.ready", en, memCount), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Sync: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        LinearLayout sensCard = createGridCard(primaryRow, IconFactory.IconType.SENSOR, T.s("ui.sensors", en), Ui.cardAccent(this));
        sensInfo = new TextView(this);
        sensInfo.setText(T.s("status.sensors_off", en));
        sensInfo.setTextSize(12);
        sensInfo.setLineSpacing(dp(1), 1.0f);
        sensInfo.setTextColor(Ui.body(this));
        sensInfo.setPadding(0, dp(4), 0, 0);
        sensCard.addView(sensInfo);

        sensCard.setOnClickListener(v -> showSensorsDialog(sensInfo));

        LinearLayout visionCard = createGridCard(gridRow2, IconFactory.IconType.EYE, T.s("ui.vision_ptt", en), Ui.cardAccent(this));
        TextView visionInfo = new TextView(this);
        String bleStatus = HardwarePeripheralService.getInstance().getPeripheralStatus(en);
        visionInfo.setText((T.s("ui.accessibility_ocr_n", en))
                + bleStatus + "\n"
                + (T.s("ui.physical_wakeup_ready", en)));
        visionInfo.setTextSize(12);
        visionInfo.setLineSpacing(dp(1), 1.0f);
        visionInfo.setTextColor(Ui.body(this));
        visionInfo.setPadding(0, dp(4), 0, 0);
        visionCard.addView(visionInfo);

        visionCard.setOnClickListener(v -> {
            try {
                org.json.JSONObject target = VisionAgentManager.getInstance().locateTargetOnScreen(T.s("ui.new_agent_task", en));
                String bleLiveStatus = HardwarePeripheralService.getInstance().getPeripheralStatus(en);
                if (target.optBoolean("matched", false)) {
                    int x = (int) target.optDouble("tap_x");
                    int y = (int) target.optDouble("tap_y");
                    visionInfo.setText((T.s("ui.target", en)) + x + "," + y + ")\n"
                            + (T.s("ui.a11y_node_matched_n", en))
                            + bleLiveStatus);
                    Toast.makeText(this, (T.s("ui.vision_target_located", en)) + x + "," + y + ") · " + bleLiveStatus, Toast.LENGTH_SHORT).show();
                } else {
                    visionInfo.setText((T.s("ui.target_not_found_na11y_node_standby_n", en))
                            + bleLiveStatus);
                    Toast.makeText(this, (T.s("ui.vision_mismatch", en)) + target.optString("reasoning") + " · " + bleLiveStatus, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Vision/PTT: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        // 4. Agent Task Management Section
        TextView taskHeading = new TextView(this);
        taskHeading.setText(T.s("task.section", en));
        taskHeading.setTextSize(14);
        taskHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        taskHeading.setTextColor(Ui.text(this));
        taskHeading.setPadding(0, dp(10), 0, dp(4));
        page.addView(taskHeading);

        LinearLayout taskInputCard = createCard(page, IconFactory.IconType.TASK, T.s("ui.new_agent_task", en), Ui.cardAccent(this));

        taskInput = new EditText(this);
        taskInput.setMinHeight(dp(48));
        taskInput.setHint(T.s("task.hint", en));
        taskInput.setTextSize(12);
        taskInput.setMinLines(1);
        taskInput.setMaxLines(2);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(Ui.dark(this) ? 0xFF0F172A : 0xFFF8FAFC);
        inputBg.setCornerRadius(dp(8));
        inputBg.setStroke(dp(1), Ui.stroke(this));
        taskInput.setBackground(inputBg);
        taskInput.setPadding(dp(8), dp(6), dp(8), dp(6));
        taskInput.setTextColor(Ui.text(this));
        taskInput.setHintTextColor(Ui.muted(this));
        taskInputCard.addView(taskInput);

        Button submitTaskBtn = createCompactActionButton(T.s("task.submit", en), 0xFF0F172A, 0xFFFFFFFF, view -> createTask());
        submitTaskBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        persistentButtons.add(submitTaskBtn);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        subLp.setMargins(0, dp(4), 0, 0);
        taskInputCard.addView(submitTaskBtn, subLp);
        int actionBg = Ui.secondaryBg(this);
        int actionFg = Ui.secondaryFg(this);
        Button calendarBtn = createCompactActionButton(T.s("task.calendar", en), actionBg, actionFg,
            view -> startActivity(new Intent(this, ExternalActionActivity.class)));
        Button fileBtn = createCompactActionButton(T.s("task.file", en), actionBg, actionFg,
            view -> startActivity(new Intent(this, DocumentTaskActivity.class)));
        Button workflowBtn = createCompactActionButton(T.s("task.workflow", en), actionBg, actionFg,
            view -> startActivity(new Intent(this, WorkflowActivity.class)));
        Button centerBtn = createCompactActionButton(T.s("task.center", en), actionBg, actionFg,
            view -> startActivity(new Intent(this, TaskCenterActivity.class)));
        LinearLayout actionGrid = Ui.actionGrid(this, calendarBtn, fileBtn, workflowBtn, centerBtn);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gridLp.setMargins(0, dp(2), 0, 0);
        taskInputCard.addView(actionGrid, gridLp);

        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setPadding(0, dp(4), 0, dp(2));
        String[][] filters = {
            {"all", T.s("task.filter_all", en)},
            {"active", T.s("task.filter_active", en)},
            {"waiting", T.s("task.filter_waiting", en)},
            {"done", T.s("task.filter_done", en)},
        };
        for (String[] entry : filters) {
            Button chip = createCompactActionButton(entry[1], entry[0].equals(taskFilter)
                ? Ui.cardAccent(this) : Ui.secondaryBg(this),
                entry[0].equals(taskFilter) ? 0xFFFFFFFF : Ui.secondaryFg(this),
                view -> { taskFilter = entry[0]; refreshTasks(); });
            chip.setTextSize(11);
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            chipLp.setMargins(0, 0, dp(4), 0);
            filterRow.addView(chip, chipLp);
        }
        page.addView(filterRow);

        EditText taskSearch = new EditText(this);
        taskSearch.setHint(T.s("task.search_hint", en));
        taskSearch.setTextSize(13);
        taskSearch.setMinHeight(dp(48));
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(Ui.dark(this) ? 0xFF0F172A : 0xFFF8FAFC);
        searchBg.setCornerRadius(dp(8));
        searchBg.setStroke(dp(1), Ui.stroke(this));
        taskSearch.setBackground(searchBg);
        taskSearch.setTextColor(Ui.text(this));
        taskSearch.setHintTextColor(Ui.muted(this));
        taskSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                taskQuery = text.toString().trim().toLowerCase(java.util.Locale.ROOT);
                refreshTasks();
            }
            @Override public void afterTextChanged(android.text.Editable text) {}
        });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        searchLp.setMargins(0, dp(2), 0, dp(6));
        page.addView(taskSearch, searchLp);

        taskList = new LinearLayout(this);
        taskList.setOrientation(LinearLayout.VERTICAL);
        page.addView(taskList);
        onboardingCard = createCard(page, IconFactory.IconType.BRAIN, T.s("onboarding.title", en), Ui.cardAccent(this));
        TextView onboardingBody = new TextView(this);
        onboardingBody.setTextSize(12);
        onboardingBody.setTextColor(Ui.body(this));
        onboardingBody.setText(T.s("onboarding.line1", en) + "\n"
            + T.s("onboarding.line2", en) + "\n" + T.s("onboarding.line3", en));
        onboardingCard.addView(onboardingBody);
        onboardingCard.setVisibility(View.GONE);

        // 5. Bottom Functional Navigation Dock (3 Secondary Dialog Triggers)
        LinearLayout dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setPadding(0, dp(8), 0, 0);

        Button btnMemDialog = createCompactActionButton(T.s("nav.memory", en), 0xFF334155, 0xFFFFFFFF, v -> showMemoryDialog());
        btnMemDialog.setTypeface(null, android.graphics.Typeface.BOLD);
        dockRow.addView(btnMemDialog, new LinearLayout.LayoutParams(0, dp(48), 1.0f));

        Button btnSettingsDialog = createCompactActionButton(T.s("nav.settings", en), 0xFF334155, 0xFFFFFFFF, v -> showSettingsDialog());
        btnSettingsDialog.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams setLp = new LinearLayout.LayoutParams(0, dp(48), 1.0f);
        setLp.setMargins(dp(6), 0, dp(6), 0);
        dockRow.addView(btnSettingsDialog, setLp);

        Button btnConsoleDialog = createCompactActionButton(T.s("nav.console", en), 0xFF334155, 0xFFFFFFFF, v -> showConsoleDialog());
        btnConsoleDialog.setTypeface(null, android.graphics.Typeface.BOLD);
        dockRow.addView(btnConsoleDialog, new LinearLayout.LayoutParams(0, dp(48), 1.0f));

        page.addView(dockRow);

        LinearLayout summaryCard = createCard(page, IconFactory.IconType.SHIELD, T.s("ui.status", en), Ui.cardAccent(this));
        bottomSummary = new TextView(this);
        bottomSummary.setTextSize(12);
        bottomSummary.setLineSpacing(dp(2), 1.0f);
        bottomSummary.setTextColor(Ui.muted(this));
        summaryCard.addView(bottomSummary);
        summaryCard.setOnClickListener(v -> showSettingsDialog());

        ScrollView scroller = new ScrollView(this);
        pageScroll = scroller;
        scroller.setBackgroundColor(Ui.dark(this) ? 0xFF0B1220 : 0xFFF8FAFC);
        scroller.setFillViewport(true);
        scroller.addView(page);
        CrashLog.install(this);
        setContentView(scroller);
    }

    private void showMemoryDialog() {
        startActivity(new android.content.Intent(this, MemoryVaultActivity.class));
    }

    void showPairingList() {
        final boolean en = isEnglish();
        try {
            final java.util.List<AgentStore.Pairing> active = store.activePairings();
            if (active.isEmpty()) {
                showMessage(T.s("ui.no_paired_devices", en));
                return;
            }
            final String[] labels = new String[active.size()];
            for (int index = 0; index < active.size(); index++) {
                AgentStore.Pairing pairing = active.get(index);
                AgentStore.SyncCursor cursor = store.syncCursor(pairing.deviceId);
                String last = cursor == null || cursor.lastSyncedAt == 0
                    ? T.s("pair.never_synced", en)
                    : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(new Date(cursor.lastSyncedAt)) + " · " + SyncResultText.describe(cursor.lastResult, en);
                labels[index] = pairing.deviceId + "\n" + pairing.fingerprint.substring(0, 16) + "…\n" + last;
            }
            new AlertDialog.Builder(this)
                .setTitle(T.s("ui.paired_devices", en))
                .setItems(labels, (dialog, which) -> {
                    final AgentStore.Pairing pairing = active.get(which);
                    AlertDialog revokeDialog = new AlertDialog.Builder(this)
                        .setView(Ui.dialogBody(this, T.s("ui.revoke_pairing", en),
                            T.format("pair.revoke_message", en, pairing.deviceId), 13))
                        .setNegativeButton(T.s("ui.cancel", en), null)
                        .setPositiveButton(T.s("ui.revoke", en), (inner, ignored) -> {
                            try {
                                SyncPairing.revoke(store, pairing.deviceId);
                                showMessage(T.s("ui.pairing_revoked", en));
                            } catch (Exception error) {
                                showError(error);
                            }
                        }).create();
                    revokeDialog.show();
                    Ui.themeDialog(revokeDialog, this);
                })
                .setNegativeButton(T.s("ui.close", en), null)
                .show();
        } catch (Exception error) {
            showError(error);
        }
    }

    void showPairingDialog() {
        syncPanel.showPairingDialog();
    }

    private String syncSeed() {
        android.content.SharedPreferences prefs = getSharedPreferences("agent_os_prefs", 0);
        String seed = prefs.getString("sync_seed", "");
        if (seed.isEmpty()) {
            byte[] raw = new byte[32];
            new java.security.SecureRandom().nextBytes(raw);
            StringBuilder builder = new StringBuilder();
            for (byte item : raw) builder.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
            seed = builder.toString();
            prefs.edit().putString("sync_seed", seed).apply();
        }
        return seed;
    }

    private final SyncPanel syncPanel = new SyncPanel(this);
    private final MemoryDetailPanel memoryPanel = new MemoryDetailPanel(this);
    private final SettingsPanel settingsPanel = new SettingsPanel(this);

    public AgentStore storeRef() { return store; }
    SyncPanel syncPanelRef() { return syncPanel; }
    CapabilityBroker capabilityBrokerRef() { return capabilityBroker; }
    java.util.concurrent.Executor ioRef() { return io; }
    android.os.Handler uiRef() { return mainHandler; }
    boolean isDestroyedRef() { return destroyed; }
    public boolean english() { return isEnglish(); }
    public int dpRef(int value) { return dp(value); }
    public void toast(String message) { showMessage(message); }
    public void failWith(Exception error) { showError(error); }
    public void refreshAfterSync() { refreshTasks(); refreshMemories(); }
    String seedValue() { return syncSeed(); }

    private void showSettingsDialog() {
        settingsPanel.show();
    }

    private void showConsoleDialog() {
        boolean en = isEnglish();
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(12));

        LinearLayout titleRow = IconFactory.createHeaderWithIcon(this, IconFactory.IconType.SCREEN,
                T.s("ui.console_diagnostics_hub", en), Ui.cardAccent(this), 16);
        root.addView(titleRow);

        vadLiveStatus = new TextView(this);
        vadLiveStatus.setText(T.s("ui.live_audio_standby_16khz_neon_simd", en));
        vadLiveStatus.setTextSize(11);
        vadLiveStatus.setTextColor(0xFF0284C7);
        vadLiveStatus.setPadding(0, dp(8), 0, dp(6));
        root.addView(vadLiveStatus);

        addPageButton(root, T.s("audit.open", en), view -> startActivity(new android.content.Intent(this, AuditActivity.class)));

        LinearLayout autoBtnRow = new LinearLayout(this);
        autoBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        autoBtnRow.setPadding(0, dp(2), 0, dp(4));

        Button toggleFloatingBtn = createCompactActionButton(T.s("ui.launch_capsule", en), Ui.secondaryBg(this), Ui.secondaryFg(this), v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                new AlertDialog.Builder(this)
                        .setTitle(T.s("ui.grant_floating_capsule_permission", en))
                        .setMessage(T.s("ui.floating_capsule_requires_permission_t", en))
                        .setPositiveButton(T.s("ui.settings", en), (dialog, which) -> {
                            try {
                                Intent reqIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + getPackageName()));
                                startActivity(reqIntent);
                            } catch (Exception e) {
                                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                            }
                        })
                        .setNegativeButton(T.s("ui.cancel", en), null)
                        .show();
                return;
            }

            Intent fIntent = new Intent(this, AgentFloatingService.class);
            fIntent.setAction(AgentFloatingService.ACTION_SHOW);
            startService(fIntent);
            Toast.makeText(this, T.s("ui.floating_capsule_active", en), Toast.LENGTH_SHORT).show();
        });
        autoBtnRow.addView(toggleFloatingBtn, new LinearLayout.LayoutParams(0, dp(34), 1.2f));

        Button hideFloatingBtn = createCompactActionButton(T.s("ui.hide_capsule", en), 0xFFF1F5F9, 0xFF334155, v -> {
            Intent fIntent = new Intent(this, AgentFloatingService.class);
            fIntent.setAction(AgentFloatingService.ACTION_HIDE);
            startService(fIntent);
            Toast.makeText(this, T.s("ui.floating_capsule_hidden", en), Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams hideLp = new LinearLayout.LayoutParams(0, dp(34), 0.9f);
        hideLp.setMargins(dp(4), 0, 0, 0);
        autoBtnRow.addView(hideFloatingBtn, hideLp);

        root.addView(autoBtnRow);

        LinearLayout diagBtnRow = new LinearLayout(this);
        diagBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        diagBtnRow.setPadding(0, dp(4), 0, dp(4));

        btnToggleVoice = createCompactActionButton(T.s("ui.live_audio", en), Ui.secondaryBg(this), Ui.secondaryFg(this), v -> toggleContinuousVoice());
        diagBtnRow.addView(btnToggleVoice, new LinearLayout.LayoutParams(0, dp(32), 1.1f));

        Button testVoiceBtn = createCompactActionButton(T.s("ui.synth_test", en), 0xFFF8FAFC, 0xFF475569, v -> {
            short[] testPcm = new short[640];
            for (int i = 0; i < testPcm.length; i++) {
                testPcm[i] = (short) (Math.sin(i * 0.1) * 8000);
            }
            org.json.JSONObject res = VoiceInteractionManager.processPcmFrame(testPcm);
            Toast.makeText(this, "VAD: " + res.optBoolean("is_speech") + " (RMS: " + String.format(java.util.Locale.US, "%.3f", res.optDouble("energy_rms")) + ")", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams testVoiceLp = new LinearLayout.LayoutParams(0, dp(32), 1.0f);
        testVoiceLp.setMargins(dp(3), 0, 0, 0);
        diagBtnRow.addView(testVoiceBtn, testVoiceLp);

        sensorToggleButton = createCompactActionButton(T.s("ui.enable_sensors", en), 0xFFF8FAFC, 0xFF475569, v -> {
            PhysicalSensoriumManager sens = PhysicalSensoriumManager.getInstance(this);
            if (sens.isMonitoring()) {
                sens.stopMonitoring();
            } else {
                sens.startMonitoring();
            }
            updateSensorToggleButton();
            startSensorRefresh();
            if (!sens.isMonitoring()) {
                Toast.makeText(this, T.s("ui.sensor_monitoring_stopped", en), Toast.LENGTH_SHORT).show();
                return;
            }
            org.json.JSONObject ctx = sens.evaluateCurrentContext();
            Toast.makeText(this, SensorContext.describe(ctx, en), Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams sensLp = new LinearLayout.LayoutParams(0, dp(32), 1.0f);
        sensLp.setMargins(dp(3), 0, 0, 0);
        diagBtnRow.addView(sensorToggleButton, sensLp);

        root.addView(diagBtnRow);

        LinearLayout diagBtnRow2 = new LinearLayout(this);
        diagBtnRow2.setOrientation(LinearLayout.HORIZONTAL);
        diagBtnRow2.setPadding(0, dp(2), 0, dp(4));

        Button testVisionBtn = createCompactActionButton(T.s("ui.vision_ocr", en), 0xFFF8FAFC, 0xFF475569, v -> {
            try {
                String query = T.s("ui.new_agent_task", en);
                org.json.JSONObject target = VisionAgentManager.getInstance().locateTargetOnScreen(query);
                if (!target.optBoolean("matched", false)) {
                    target = VisionAgentManager.getInstance().locateTargetOnScreen(T.s("ui.console", en));
                }
                if (!target.optBoolean("matched", false)) {
                    target = VisionAgentManager.getInstance().locateTargetOnScreen(T.s("ui.vision", en));
                }
                if (target.optBoolean("matched", false)) {
                    int x = (int) target.optDouble("tap_x");
                    int y = (int) target.optDouble("tap_y");
                    VisionAgentManager.getInstance().captureScreenBitmap(new AgentAccessibilityService.ScreenCaptureCallback() {
                        @Override
                        public void onBitmapCaptured(android.graphics.Bitmap bitmap) {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                    (T.s("ui.vision_target", en)) + x + "," + y + (T.s("ui.silent_shot", en)) + bitmap.getWidth() + "x" + bitmap.getHeight(), Toast.LENGTH_SHORT).show());
                        }

                        @Override
                        public void onError(int errorCode) {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                    (T.s("ui.vision_target_2", en)) + x + "," + y + (T.s("ui.capture_code", en)) + errorCode + "]", Toast.LENGTH_SHORT).show());
                        }
                    });
                } else {
                    Toast.makeText(this, (T.s("ui.vision_mismatch_2", en)) + target.optString("reasoning"), Toast.LENGTH_SHORT).show();
                }
            } catch (Exception ex) {
                Toast.makeText(this, (T.s("ui.locate_error", en)) + ex.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        diagBtnRow2.addView(testVisionBtn, new LinearLayout.LayoutParams(0, dp(32), 1.0f));

        Button testPttBtn = createCompactActionButton(T.s("ui.ptt_key", en), 0xFFF8FAFC, 0xFF475569, v -> {
            HardwarePeripheralService hwSvc = HardwarePeripheralService.getInstance();
            boolean nextState = !hwSvc.isPttActive();
            org.json.JSONObject pttRes = hwSvc.triggerPttAction(this, nextState);
            ((Button) v).setText(nextState ? (T.s("ui.release_ptt", en)) : (T.s("ui.ptt_key", en)));
            ((Button) v).setTextColor(nextState ? 0xFFE11D48 : 0xFF475569);
            Toast.makeText(this, "PTT: " + pttRes.optString("action") + " (" + pttRes.optLong("latency_us") + "μs " + (T.s("ui.latency", en)) + ")", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams pttLp = new LinearLayout.LayoutParams(0, dp(32), 1.0f);
        pttLp.setMargins(dp(4), 0, 0, 0);
        diagBtnRow2.addView(testPttBtn, pttLp);

        root.addView(diagBtnRow2);

        LinearLayout diagBtnRow3 = new LinearLayout(this);
        diagBtnRow3.setOrientation(LinearLayout.HORIZONTAL);
        diagBtnRow3.setPadding(0, dp(2), 0, dp(4));

        Button testTceBtn = createCompactActionButton(T.s("ui.tce_graph", en), 0xFFF8FAFC, 0xFF475569, v -> {
            try {
                String resJson = NativeAgentCore.tceExecuteBenchmark(0, 35.0f, 90.0f);
                org.json.JSONObject resObj = new org.json.JSONObject(resJson);
                org.json.JSONObject breakdown = resObj.getJSONObject("placement_breakdown");
                int cpu = breakdown.optInt("CPU_NEON", 0);
                int gpu = breakdown.optInt("GPU_VULKAN", 0);
                int tpu = breakdown.optInt("EDGE_TPU", 0);
                long latencyUs = resObj.optLong("total_latency_us", 0);
                double tflops = resObj.optDouble("effective_tflops", 0.0);
                Toast.makeText(this, (T.s("ui.tce_nominal", en))
                        + "GPU(" + gpu + ") TPU(" + tpu + ") CPU(" + cpu + ") · "
                        + String.format(Locale.US, "%.2f TFLOPS (%dμs)", tflops, latencyUs), Toast.LENGTH_LONG).show();
            } catch (Exception ex) {
                Toast.makeText(this, "TCE error: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        diagBtnRow3.addView(testTceBtn, new LinearLayout.LayoutParams(0, dp(32), 1.0f));

        Button testThermalShedBtn = createCompactActionButton(T.s("ui.thermal_shed", en), 0xFFF8FAFC, 0xFF475569, v -> {
            try {
                String resJson = NativeAgentCore.tceExecuteBenchmark(0, 44.5f, 30.0f);
                org.json.JSONObject resObj = new org.json.JSONObject(resJson);
                org.json.JSONObject breakdown = resObj.getJSONObject("placement_breakdown");
                int cpu = breakdown.optInt("CPU_NEON", 0);
                int gpu = breakdown.optInt("GPU_VULKAN", 0);
                int tpu = breakdown.optInt("EDGE_TPU", 0);
                long delay = resObj.optLong("pacing_delay_ms", 0);
                Toast.makeText(this, T.s("ui.thermal_throttling", en)
                        + T.format("ui.thermal_detail", en, cpu, gpu, tpu, delay), Toast.LENGTH_LONG).show();
            } catch (Exception ex) {
                Toast.makeText(this, "Thermal shed error: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(0, dp(32), 1.0f);
        tsLp.setMargins(dp(4), 0, 0, 0);
        diagBtnRow3.addView(testThermalShedBtn, tsLp);

        root.addView(diagBtnRow3);

        scroll.addView(root);
        builder.setView(scroll);
        builder.setPositiveButton(T.s("ui.close", en), null);
        builder.show();
    }

    private void createTask() {
        if (busy) return;
        final String title = taskInput.getText().toString().trim();
        if (title.isEmpty()) return;
        startActivity(new Intent(this, TaskReviewActivity.class).putExtra(TaskReviewActivity.EXTRA_GOAL, title));
    }



    private void refreshTasks() {
        if (taskList == null || store == null) return;
        try {
            taskButtons.clear();
            taskList.removeAllViews();
            List<AgentStore.Task> tasks = new ArrayList<>();
            for (AgentStore.Task task : store.listTasks()) {
                if (!matchesTaskFilter(task)) continue;
                tasks.add(task);
            }
            refreshSummary();
            refreshFirstRun();
            if (tasks.isEmpty()) {
                boolean filtered = !"all".equals(taskFilter) || (taskQuery != null && !taskQuery.isEmpty());
                if (filtered) addEmptyMessage(taskList, T.s("task.none_matching", isEnglish()));
                else addEmptyTasks(taskList, T.s("task.none_active", isEnglish()));
                mainHandler.removeCallbacks(taskRefresh);
                return;
            }
            boolean hasActiveTask = false;
            AgentStore.Task pendingJitTask = null;
            for (AgentStore.Task task : tasks) {
                boolean externalWaiting = task.plan != null && task.executionStep < task.plan.totalSteps()
                    && task.plan.steps.get(task.executionStep).isExternalAction();
                if (pendingJitTask == null && AgentStore.WAITING_FOR_USER.equals(task.status) && !externalWaiting) {
                    pendingJitTask = task;
                }
                hasActiveTask |= AgentStore.ACTIVE.equals(task.status);
            }
            if (pendingJitTask != null) {
                addJitAlertBanner(taskList, pendingJitTask);
            }
            for (AgentStore.Task task : tasks) {
                addTaskCard(task);
            }
            mainHandler.removeCallbacks(taskRefresh);
            if (hasActiveTask && !destroyed) mainHandler.postDelayed(taskRefresh, 1000);
        } catch (Exception error) {
            showError(error);
        }
    }

    private void addJitAlertBanner(LinearLayout container, AgentStore.Task task) {
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(dp(16), dp(14), dp(16), dp(14));
        banner.setBackgroundColor(0xFFFFF8E1); // Soft amber accent

        LinearLayout titleRow = IconFactory.createHeaderWithIcon(this, IconFactory.IconType.ALERT, T.s("task.jit_title", isEnglish()), 0xFFE65100, 18);
        banner.addView(titleRow);

        boolean en = isEnglish();
        String stepInfo = T.format("task.jit_banner_line", en, task.title, task.executionStep + 1);
        if (task.plan != null && task.executionStep >= 0 && task.executionStep < task.plan.totalSteps()) {
            ExecutionPlan.Step step = task.plan.steps.get(task.executionStep);
            stepInfo += "\n" + T.format("task.requesting_capability", en, CapabilityBroker.friendlyCapabilityName(step.capability, en), step.capability);
        }
        TextView desc = new TextView(this);
        desc.setText(stepInfo);
        desc.setTextSize(14);
        desc.setTextColor(0xFF37474F);
        desc.setPadding(0, dp(4), 0, dp(8));
        banner.addView(desc);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        if (task.plan != null && task.executionStep >= 0 && task.executionStep < task.plan.totalSteps()) {
            ExecutionPlan.Step step = task.plan.steps.get(task.executionStep);
            Button grantBtn = new Button(this);
            grantBtn.setText(T.s("task.jit_grant_all", isEnglish()));
            grantBtn.setOnClickListener(v -> {
                try {
                    capabilityBroker.grant(task.id, step.capability, step.scope);
                    store.transition(task.id, AgentStore.ACTIVE);
                    try {
                        AgentScheduler.scheduleTask(MainActivity.this, store.getTask(task.id));
                    } catch (Exception scheduleError) {
                        try {
                            store.transition(task.id, AgentStore.WAITING_FOR_USER);
                        } finally {
                            capabilityBroker.revoke(task.id, step.capability, step.scope);
                        }
                        throw scheduleError;
                    }
                    refreshTasks();
                } catch (Exception ex) {
                    showError(ex);
                }
            });
            taskButtons.add(grantBtn);
            buttons.addView(grantBtn);
        }

        Button rejectBtn = new Button(this);
        rejectBtn.setText(T.s("task.jit_reject", isEnglish()));
        rejectBtn.setOnClickListener(v -> {
            try {
                store.transition(task.id, AgentStore.CANCELLED);
                refreshTasks();
            } catch (Exception ex) {
                showError(ex);
            }
        });
        taskButtons.add(rejectBtn);
        buttons.addView(rejectBtn);

        banner.addView(buttons);
        container.addView(banner);
        addDivider(container, null);
    }

    /** The guide only makes sense before the first task or memory exists. */
    private void refreshFirstRun() {
        if (onboardingCard == null || store == null) return;
        boolean empty;
        try {
            empty = store.listTasks().isEmpty() && store.listMemories().isEmpty();
        } catch (Exception ignored) {
            empty = false;
        }
        onboardingCard.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void refreshSummary() {
        if (bottomSummary == null || store == null) return;
        boolean en = isEnglish();
        try {
            int memories = store.listMemories().size();
            int tasks = store.listTasks().size();
            String peers = T.format("summary.peers", en, store.listPairings().size());
            bottomSummary.setText(T.format("summary.line", en, memories, tasks) + "\n" + peers);
        } catch (Exception ignored) {}
    }

    private void showInfoCard(String title, String body) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(T.s("common.close", isEnglish()), null)
            .show();
    }

    private void refreshMemories() {
        refreshSummary();
        refreshFirstRun();
    }


    private boolean matchesTaskFilter(AgentStore.Task task) {
        if (taskQuery != null && !taskQuery.isEmpty()
            && !task.title.toLowerCase(java.util.Locale.ROOT).contains(taskQuery)) return false;
        if ("active".equals(taskFilter)) {
            return AgentStore.ACTIVE.equals(task.status) || AgentStore.QUEUED.equals(task.status)
                || AgentStore.PAUSED.equals(task.status);
        }
        if ("waiting".equals(taskFilter)) return AgentStore.WAITING_FOR_USER.equals(task.status);
        if ("done".equals(taskFilter)) {
            return AgentStore.COMPLETED.equals(task.status) || AgentStore.CANCELLED.equals(task.status)
                || AgentStore.OUTCOME_UNKNOWN.equals(task.status) || AgentStore.FAILED.equals(task.status);
        }
        return true;
    }

    private void addTaskCard(AgentStore.Task task) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.card(this));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Ui.stroke(this));
        card.setBackground(bg);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(6));
        card.setLayoutParams(lp);

        // Header: Title + Status Badge
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView description = new TextView(this);
        description.setText(task.title);
        description.setTextSize(15);
        description.setTypeface(null, android.graphics.Typeface.BOLD);
        description.setTextColor(Ui.text(this));
        description.setOnClickListener(view -> startActivity(
            new Intent(this, TaskDetailActivity.class).putExtra(TaskDetailActivity.EXTRA_TASK_ID, task.id)));
        headerRow.addView(description, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView badge = DocumentAction.locallyVerified(task)
            ? IconFactory.createBadge(this, T.s("task.verified", isEnglish()), 0xFFDCFCE7, 0xFF166534)
            : getStatusBadge(task.status);
        headerRow.addView(badge);
        card.addView(headerRow);


        boolean en = isEnglish();
        int planSteps = task.plan == null ? 0 : task.plan.totalSteps();
        int planDone = 0;
        if (task.plan != null) {
            for (ExecutionPlan.Step step : task.plan.steps) {
                if (ExecutionPlan.STEP_DONE.equals(step.status)
                    && (!step.isExternalAction() || ExternalAction.hasVerifiedEffect(step))) planDone++;
            }
        }
        // Everything except the title, badge and one-line status lives behind the
        // details toggle so a long task list stays scannable.
        final LinearLayout detailContainer = new LinearLayout(this);
        detailContainer.setOrientation(LinearLayout.VERTICAL);
        detailContainer.setVisibility(View.GONE);

        TextView metadata = new TextView(this);
        metadata.setText(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(task.updatedAt)));
        metadata.setTextSize(11);
        metadata.setTextColor(0xFF94A3B8);
        metadata.setPadding(0, dp(2), 0, dp(2));
        detailContainer.addView(metadata);

        TextView summary = new TextView(this);
        String nextTitle = "";
        if (task.plan != null && task.executionStep < task.plan.steps.size())
            nextTitle = formatStepTitle(task.plan.steps.get(task.executionStep).title, en);
        summary.setText(planSteps == 0
            ? (T.s("ui.draft_only_no_plan_yet", en))
            : T.format("task.steps_progress", en, planDone, planSteps)
              + (nextTitle.isEmpty() ? "" : (T.s("ui.next", en)) + nextTitle));
        summary.setTextSize(12);
        summary.setTextColor(Ui.muted(this));
        summary.setPadding(0, 0, 0, dp(2));
        card.addView(summary);

        if (planSteps > 0) {
            android.widget.ProgressBar progress = new android.widget.ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(100);
            progress.setProgress(Math.round(planDone * 100f / planSteps));
            progress.setProgressTintList(android.content.res.ColorStateList.valueOf(
                AgentStore.COMPLETED.equals(task.status) ? 0xFF15803D : Ui.cardAccent(this)));
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4));
            barLp.setMargins(0, dp(2), 0, dp(2));
            detailContainer.addView(progress, barLp);
        }

        if (AgentStore.ACTIVE.equals(task.status) && planSteps > 0) {
            TextView running = new TextView(this);
            String stepTitle = task.executionStep < planSteps
                ? formatStepTitle(task.plan.steps.get(task.executionStep).title, en) : nextTitle;
            long elapsedSeconds = Math.max(0, (System.currentTimeMillis() - task.createdAt) / 1000);
            String elapsed = elapsedSeconds < 60
                ? elapsedSeconds + "s"
                : (elapsedSeconds / 60) + "m " + (elapsedSeconds % 60) + "s";
            running.setText(T.format("task.running_step", en, Math.min(task.executionStep + 1, planSteps),
                    planSteps, stepTitle) + " · " + T.format("task.elapsed", en, elapsed));
            running.setTextSize(12);
            running.setTypeface(null, android.graphics.Typeface.BOLD);
            running.setTextColor(Ui.cardAccent(this));
            running.setPadding(0, dp(2), 0, dp(2));
            detailContainer.addView(running);
        }
        boolean isTerminal = AgentStore.COMPLETED.equals(task.status) || AgentStore.CANCELLED.equals(task.status)
            || AgentStore.OUTCOME_UNKNOWN.equals(task.status);
        addMemoryCandidateControls(card, task);
        if (task.plan != null && task.plan.totalSteps() == 1
                && DocumentAction.CAPABILITY.equals(task.plan.steps.get(0).capability)) {
            addPageButton(card, T.s("ui.review_file_and_receipt", en), view ->
                startActivity(new Intent(this, DocumentTaskActivity.class).putExtra(DocumentTaskActivity.EXTRA_TASK_ID, task.id)));
        }
        if (task.plan != null && !task.plan.context.isEmpty()) {
            addPageButton(detailContainer, T.s("ui.review_selected_task_memories", en), view -> {
                StringBuilder context = new StringBuilder(TaskContext.localOnly(task.plan)
                    ? (T.s("ui.task_stays_local_n_n", en))
                    : (T.s("ui.cloud_planning_requires_a_fresh_single", en)));
                for (AgentStore.Memory memory : TaskContext.memories(task.plan))
                    context.append(memory.content).append("\n").append(memory.source).append(" · ").append(memory.id).append("\n\n");
                AlertDialog contextDialog = new AlertDialog.Builder(this)
                    .setView(Ui.dialogBody(this, T.s("ui.selected_memory_snapshots", en), context.toString(), 13))
                    .setPositiveButton(T.s("ui.close", en), null)
                    .create();
                contextDialog.show();
                Ui.themeDialog(contextDialog, this);
            });
        }

        {

            LinearLayout toggleRow = new LinearLayout(this);
            toggleRow.setOrientation(LinearLayout.HORIZONTAL);
            toggleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            toggleRow.setPadding(0, dp(2), 0, dp(4));

            final TextView toggleBtn = new TextView(this);
            int stepCount = (task.plan != null) ? task.plan.totalSteps() : 0;
            String statusText = AgentStore.COMPLETED.equals(task.status)
                    ? (T.s("ui.completed", en))
                    : AgentStore.OUTCOME_UNKNOWN.equals(task.status) ? (T.s("ui.outcome_unknown", en))
                    : AgentStore.CANCELLED.equals(task.status) ? (T.s("ui.cancelled", en))
                    : (T.s("ui.details", en));
            toggleBtn.setText(statusText + T.format("task.expand_steps", en, stepCount));
            toggleBtn.setTextSize(12);
            toggleBtn.setTextColor(0xFF0284C7);
            toggleRow.addView(toggleBtn);

            View.OnClickListener toggleClick = v -> {
                boolean willExpand = detailContainer.getVisibility() != View.VISIBLE;
                detailContainer.setVisibility(willExpand ? View.VISIBLE : View.GONE);
                toggleBtn.setText(willExpand
                    ? statusText + T.format("task.collapse_steps", en, stepCount)
                    : statusText + T.format("task.expand_steps", en, stepCount));
            };
            toggleRow.setOnClickListener(toggleClick);
            toggleBtn.setOnClickListener(toggleClick);

            card.addView(toggleRow);
        }

        if (task.plan != null && !task.plan.steps.isEmpty()) {
            addPlanStepTree(detailContainer, task);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(4), 0, 0);
        detailContainer.addView(actions);

        boolean externalDraft = task.plan != null && task.executionStep < task.plan.totalSteps()
            && task.plan.steps.get(task.executionStep).isExternalAction()
            && ExecutionPlan.STEP_DRAFT_READY.equals(task.plan.steps.get(task.executionStep).status);
        boolean documentPlanning = TaskContext.isDocument(task.plan) && task.plan.steps.isEmpty();
        if (documentPlanning && !isTerminal)
            addCardActionButton(actions, T.s("ui.review_workflow", en), view ->
                startActivity(new Intent(this, TaskReviewActivity.class).putExtra(TaskReviewActivity.EXTRA_TASK_ID, task.id)), taskButtons);
        if (!documentPlanning && !externalDraft && (AgentStore.QUEUED.equals(task.status) || AgentStore.PAUSED.equals(task.status)
                || AgentStore.WAITING_FOR_USER.equals(task.status) || AgentStore.FAILED.equals(task.status))) {
            String label = AgentStore.QUEUED.equals(task.status) ? (T.s("ui.start", en))
                : AgentStore.FAILED.equals(task.status) ? (T.s("ui.retry", en)) : (T.s("ui.resume", en));
            LinearLayout primaryRow = new LinearLayout(this);
            primaryRow.setOrientation(LinearLayout.HORIZONTAL);
            primaryRow.setPadding(0, dp(2), 0, dp(2));
            addTaskButton(primaryRow, label, task, AgentStore.ACTIVE);
            card.addView(primaryRow);
        }
        if (AgentStore.ACTIVE.equals(task.status)) addTaskButton(actions, T.s("ui.pause", en), task, AgentStore.PAUSED);
        if (!isTerminal)
            addTaskButton(actions, T.s("ui.cancel", en), task, AgentStore.CANCELLED);
        addCardActionButton(actions, T.s("ui.delete", en), view -> confirmDeleteTask(task), taskButtons);

        if (!isTerminal && !documentPlanning) {
            // Raw grant/read/revoke rows are diagnostics; a normal task flow only
            // needs Start, review, cancel and delete.
            LinearLayout advanced = new LinearLayout(this);
            advanced.setOrientation(LinearLayout.VERTICAL);
            advanced.setVisibility(View.GONE);
            addCapabilityControls(advanced, task);
            Button advancedToggle = createCompactActionButton(T.s("task.advanced_closed", en),
                Ui.secondaryBg(this), Ui.secondaryFg(this), view -> {
                    boolean showing = advanced.getVisibility() == View.VISIBLE;
                    advanced.setVisibility(showing ? View.GONE : View.VISIBLE);
                    ((Button) view).setText(T.s(showing ? "task.advanced_closed" : "task.advanced_open", en));
                });
            LinearLayout.LayoutParams advancedLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
            advancedLp.setMargins(0, dp(4), 0, 0);
            detailContainer.addView(advancedToggle, advancedLp);
            detailContainer.addView(advanced);
        }

        card.addView(detailContainer);
        taskList.addView(card);
        if (task.id.equals(getIntent().getStringExtra("focusTaskId"))) {
            getIntent().removeExtra("focusTaskId");
            card.post(() -> card.requestRectangleOnScreen(new android.graphics.Rect(0, 0, card.getWidth(), card.getHeight()), true));
        }
    }

    private void addMemoryCandidateControls(LinearLayout card, AgentStore.Task task) {
        if (task.plan == null || !"pending".equals(task.plan.memoryReview)) return;
        List<MemoryConsolidator.ConsolidatedItem> candidates = MemoryConsolidator.extractItems(task, isEnglish());
        if (candidates.isEmpty()) return;
        boolean en = isEnglish();
        LinearLayout actions = new LinearLayout(this);
        addCardActionButton(actions, T.s("ui.edit_and_review_suggestions_individually", en), view ->
            startActivity(new Intent(this, MemoryReviewActivity.class).putExtra(MemoryReviewActivity.EXTRA_TASK_ID, task.id)), taskButtons);
        addCardActionButton(actions, T.format("task.review_suggestions", en, candidates.size()), view -> {
            StringBuilder preview = new StringBuilder(T.s("ui.these_are_suggestions_not_established", en));
            for (MemoryConsolidator.ConsolidatedItem item : candidates)
                preview.append(item.kind).append(": ").append(item.content).append("\n\n");
            preview.append(T.s("ui.source_task", en)).append(task.id);
            AlertDialog suggestionDialog = new AlertDialog.Builder(this)
                .setView(Ui.dialogBody(this, T.s("ui.confirm_long_term_memory", en), preview.toString(), 13))
                .setNegativeButton(T.s("ui.later", en), null)
                .setNeutralButton(T.s("ui.reject_all", en), (dialog, which) -> reviewMemoryCandidates(task.id, false))
                .setPositiveButton(T.s("ui.confirm_and_save_all", en), (dialog, which) -> reviewMemoryCandidates(task.id, true))
                .create();
            suggestionDialog.show();
            Ui.themeDialog(suggestionDialog, this);
        }, taskButtons);
        card.addView(actions);
    }

    private void reviewMemoryCandidates(String taskId, boolean accept) {
        try {
            store.reviewMemoryCandidates(taskId, accept);
            refreshTasks();
            refreshMemories();
        } catch (Exception error) { showError(error); }
    }

    private String formatStepTitle(String title, boolean en) {
        return StepText.title(title, en);
    }

    private String formatStepObservation(String obs, boolean en) {
        return StepText.observation(obs, en);
    }

    private void addPlanStepTree(LinearLayout card, AgentStore.Task task) {
        if (task.plan == null || task.plan.steps.isEmpty()) return;
        boolean en = isEnglish();

        LinearLayout planContainer = new LinearLayout(this);
        planContainer.setOrientation(LinearLayout.VERTICAL);
        planContainer.setPadding(dp(12), dp(10), dp(12), dp(10));
        planContainer.setBackgroundColor(0xFFF3F4F6);

        TextView planHeading = new TextView(this);
        planHeading.setText(en
            ? "Dynamic Execution Plan (" + task.plan.totalSteps() + " steps)"
            : T.format("task.plan_header", false, task.plan.totalSteps()));
        planHeading.setTextSize(15);
        planHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        planHeading.setTextColor(0xFF222222);
        planHeading.setPadding(0, 0, 0, dp(4));
        planContainer.addView(planHeading);

        int doneCount = 0;
        for (ExecutionPlan.Step s : task.plan.steps) {
            if (ExecutionPlan.STEP_DONE.equals(s.status) && (!s.isExternalAction() || ExternalAction.hasVerifiedEffect(s))) doneCount++;
        }
        int progressPercent = (doneCount * 100) / Math.max(1, task.plan.totalSteps());

        TextView progressText = new TextView(this);
        progressText.setText(en
            ? "Progress: " + doneCount + " / " + task.plan.totalSteps() + " steps completed (" + progressPercent + "%)"
            : T.format("task.progress_line", false, doneCount, task.plan.totalSteps(), progressPercent));
        progressText.setTextSize(13);
        progressText.setTextColor(0xFF0D47A1);
        progressText.setPadding(0, 0, 0, dp(6));
        planContainer.addView(progressText);

        for (int i = 0; i < task.plan.steps.size(); i++) {
            ExecutionPlan.Step step = task.plan.steps.get(i);
            LinearLayout stepRow = new LinearLayout(this);
            stepRow.setOrientation(LinearLayout.VERTICAL);
            stepRow.setPadding(0, dp(4), 0, dp(6));

            TextView stepHeader = new TextView(this);
            stepHeader.setTextSize(14);

            boolean isCurrentStep = (task.executionStep == i);
            boolean isStepDone = ExecutionPlan.STEP_DONE.equals(step.status)
                && (!step.isExternalAction() || ExternalAction.hasVerifiedEffect(step));
            boolean isStepWaiting = ExecutionPlan.STEP_AWAITING_GRANT.equals(step.status)
                || (isCurrentStep && AgentStore.WAITING_FOR_USER.equals(task.status));
            boolean isStepRunning = (isCurrentStep && AgentStore.ACTIVE.equals(task.status));

            IconFactory.IconType dotType;
            int textColor;
            String statusTag;

            if (ExecutionPlan.STEP_DRAFT_READY.equals(step.status)) {
                statusTag = T.s("ui.draft_ready_confirm", en);
                textColor = 0xFFB78103;
                dotType = IconFactory.IconType.STATUS_DOT_AMBER;
            } else if (ExecutionPlan.STEP_HANDED_OFF.equals(step.status) || ExecutionPlan.STEP_UNKNOWN.equals(step.status)) {
                statusTag = T.s("ui.handed_off_outcome_unknown", en);
                textColor = 0xFFB78103;
                dotType = IconFactory.IconType.STATUS_DOT_AMBER;
            } else if (ExecutionPlan.STEP_FAILED.equals(step.status) || ExecutionPlan.STEP_CANCELLED.equals(step.status)) {
                statusTag = ExecutionPlan.STEP_FAILED.equals(step.status) ? (T.s("ui.failed", en)) : (T.s("ui.cancelled_2", en));
                textColor = 0xFF991B1B;
                dotType = IconFactory.IconType.STATUS_DOT_GRAY;
            } else if (step.isExternalAction() && ExecutionPlan.STEP_DONE.equals(step.status) && !isStepDone) {
                statusTag = T.s("ui.historical_entry_no_receipt", en);
                textColor = 0xFFB78103;
                dotType = IconFactory.IconType.STATUS_DOT_AMBER;
            } else if (isStepDone) {
                statusTag = T.s("ui.done", en);
                textColor = 0xFF1B5E20;
                dotType = IconFactory.IconType.STATUS_DOT_GREEN;
            } else if (isStepWaiting) {
                statusTag = T.s("ui.awaiting_grant", en);
                textColor = 0xFFB78103;
                dotType = IconFactory.IconType.STATUS_DOT_AMBER;
            } else if (isStepRunning) {
                statusTag = T.s("ui.running", en);
                textColor = 0xFF0D47A1;
                dotType = IconFactory.IconType.STATUS_DOT_BLUE;
            } else {
                statusTag = T.s("ui.pending", en);
                textColor = 0xFF555555;
                dotType = IconFactory.IconType.STATUS_DOT_GRAY;
            }

            LinearLayout headerLine = new LinearLayout(this);
            headerLine.setOrientation(LinearLayout.HORIZONTAL);
            headerLine.setGravity(android.view.Gravity.CENTER_VERTICAL);
            headerLine.addView(IconFactory.createIconView(this, dotType, textColor, 10));

            stepHeader.setText(" " + statusTag + (T.s("ui.step", en)) + (i + 1) + (T.s("ui.text", en)) + formatStepTitle(step.title, en));
            stepHeader.setTextColor(textColor);
            headerLine.addView(stepHeader);
            stepRow.addView(headerLine);

            if (step.isExternalAction()) {
                TextView hint = new TextView(this);
                hint.setText(DocumentAction.CAPABILITY.equals(step.capability)
                    ? (T.s("ui.a_file_is_complete_only_after_readback", en))
                    : T.s("ui.saving_sending_is_handled_by_the_recei", en));
                hint.setTextSize(12);
                stepRow.addView(hint);
                if (isCurrentStep && AgentStore.WAITING_FOR_USER.equals(task.status)
                        && ExecutionPlan.STEP_DRAFT_READY.equals(step.status)) {
                    LinearLayout review = new LinearLayout(this);
                    addCardActionButton(review, T.s("ui.review_action_draft", en), view -> {
                        boolean document = DocumentAction.CAPABILITY.equals(step.capability);
                        startActivity(new Intent(this, document ? DocumentTaskActivity.class : ExternalActionActivity.class)
                            .putExtra(document ? DocumentTaskActivity.EXTRA_TASK_ID : ExternalActionActivity.EXTRA_TASK_ID, task.id));
                    }, taskButtons);
                    stepRow.addView(review);
                }
            } else if (step.isBuiltin()) {
                if (isStepDone && step.observation != null && !step.observation.isEmpty()) {
                    TextView obsView = new TextView(this);
                    obsView.setText(T.s("ui.local_summary_prepared", en));
                    obsView.setTextSize(12);
                    obsView.setTextColor(0xFF1B5E20);
                    obsView.setPadding(dp(16), dp(2), 0, 0);
                    stepRow.addView(obsView);
                } else {
                    TextView hint = new TextView(this);
                    hint.setText(T.s("ui.on_device_verification_no_external_permi", en));
                    hint.setTextSize(12);
                    hint.setTextColor(0xFF777777);
                    hint.setPadding(dp(16), dp(2), 0, 0);
                    stepRow.addView(hint);
                }
            } else {
                String cleanObs = formatStepObservation(step.observation, en);
                if (isStepDone && !cleanObs.isEmpty()) {
                    TextView obsView = new TextView(this);
                    obsView.setText((T.s("ui.observation", en)) + cleanObs);
                    obsView.setTextSize(13);
                    obsView.setTextColor(0xFF1B5E20);
                    obsView.setPadding(dp(16), dp(2), 0, 0);
                    stepRow.addView(obsView);
                } else if (!isStepDone) {
                    long grantRemaining = capabilityBroker.remainingMillis(task.id, step.capability, step.scope);
                    TextView capView = new TextView(this);
                    capView.setText(grantRemaining > 0
                        ? T.format("cap.step_line_remaining", en, step.capability, (grantRemaining + 59_999) / 60_000)
                        : T.format("cap.step_line_none", en, step.capability));
                    capView.setTextSize(12);
                    capView.setTextColor(grantRemaining > 0 ? 0xFF1B5E20 : 0xFF777777);
                    capView.setPadding(dp(16), dp(2), 0, 0);
                    stepRow.addView(capView);

                    if (!AgentStore.COMPLETED.equals(task.status) && !AgentStore.CANCELLED.equals(task.status)) {
                        LinearLayout authRow = new LinearLayout(this);
                        authRow.setOrientation(LinearLayout.HORIZONTAL);
                        authRow.setPadding(dp(16), dp(4), 0, 0);

                        if (grantRemaining <= 0) {
                            String btnLabel = T.s(isStepWaiting ? "task.grant_step" : "task.pregrant_step", en);
                            addCardActionButton(authRow, btnLabel, view ->
                                confirmCapabilityGrant(task, step.title, step.capability, step.scope), taskButtons);
                        } else {
                            addCardActionButton(authRow, T.s("ui.revoke_step_grant", en), view ->
                                revokeCapability(task, step.title, step.capability, step.scope), taskButtons);
                        }
                        stepRow.addView(authRow);
                    }
                }
            }

            if ((AgentStore.ACTIVE.equals(task.status) || AgentStore.WAITING_FOR_USER.equals(task.status))
                    && step.index == task.executionStep) {
                GradientDrawable focus = new GradientDrawable();
                focus.setColor(Ui.dark(this) ? 0xFF1E293B : 0xFFE2E8F0);
                focus.setCornerRadius(dp(6));
                stepRow.setBackground(focus);
                stepRow.setPadding(dp(6), dp(4), dp(6), dp(4));
            }
            planContainer.addView(stepRow);
        }

        card.addView(planContainer);
    }





    /** Marks the search term so a long list is easy to scan. */


    private void addTaskButton(LinearLayout parent, String label, AgentStore.Task task, String status) {
        addCardActionButton(parent, label, view -> {
            if (busy) return;
            try {
                store.transition(task.id, status);
                if (AgentStore.ACTIVE.equals(status)) {
                    try {
                        AgentScheduler.scheduleTask(this, store.getTask(task.id));
                    } catch (Exception error) {
                        store.transition(task.id, task.status);
                        throw error;
                    }
                } else {
                    AgentScheduler.cancelTask(this, task.jobId);
                }
                refreshTasks();
                if (AgentStore.ACTIVE.equals(status)) showMessage(T.s("task.submitted_to_scheduler", isEnglish()));
            } catch (Exception error) {
                showError(error);
            }
        }, taskButtons);
    }

    private void addCapabilityControls(LinearLayout card, AgentStore.Task task) {
        boolean en = isEnglish();
        addProviderControls(card, task, T.s("ui.demo_note", en), CapabilityBroker.DEMO_NOTE_CAPABILITY,
            CapabilityBroker.DEMO_NOTE_SCOPE);
        addProviderControls(card, task, T.s("ui.synthesis_status", en), CapabilityBroker.DEMO_STATUS_CAPABILITY,
            CapabilityBroker.DEMO_STATUS_SCOPE);
        LinearLayout remoteActions = new LinearLayout(this);
        remoteActions.setOrientation(LinearLayout.HORIZONTAL);
        addCardActionButton(remoteActions, T.s("ui.select_cross_app_source", en), view -> selectRemoteStatus(task), taskButtons);
        card.addView(remoteActions);
        if (capabilityBroker.hasRemoteResource(task.id)) {
            addProviderControls(card, task, T.s("ui.cross_app_status", en), AndroidRemoteStatusProvider.CAPABILITY,
                AndroidRemoteStatusProvider.SCOPE);
        }
        if (capabilityBroker.isTraceweaveEnabled()) {
            if (capabilityBroker.canEnableTraceweave()) {
                addProviderControls(card, task, T.s("ui.traceweave_local_history_summary", en),
                    CapabilityBroker.TRACEWEAVE_CAPABILITY, CapabilityBroker.TRACEWEAVE_SCOPE);
            } else {
                TextView unavailable = new TextView(this);
                unavailable.setText(T.s("ui.traceweave_provider_unavailable_trusted_s", en));
                card.addView(unavailable);
            }
        }
    }

    private void selectRemoteStatus(AgentStore.Task task) {
        Intent intent = new Intent(this, RemoteUriPickerActivity.class);
        pendingRemoteStatusTaskId = task.id;
        try {
            startActivityForResult(intent, SELECT_REMOTE_STATUS_REQUEST);
        } catch (ActivityNotFoundException error) {
            pendingRemoteStatusTaskId = null;
            showMessage(T.s("task.no_synthesis_source", isEnglish()));
        }
    }

    private void addProviderControls(LinearLayout card, AgentStore.Task task, String label,
            String capability, String scope) {
        boolean en = isEnglish();
        long grantRemaining = capabilityBroker.remainingMillis(task.id, capability, scope);
        TextView capabilityStatus = new TextView(this);
        capabilityStatus.setText(grantRemaining > 0
            ? T.format("cap.grant_remaining", en, label, (grantRemaining + 59_999) / 60_000)
            : T.format("cap.not_granted", en, label));
        card.addView(capabilityStatus);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        addCardActionButton(actions, T.format("cap.grant", en, label), view -> confirmCapabilityGrant(task, label, capability, scope), taskButtons);
        addCardActionButton(actions, T.format("cap.read", en, label), view -> invokeCapability(task, capability, scope), taskButtons);
        card.addView(actions);

        LinearLayout revokeActions = new LinearLayout(this);
        revokeActions.setOrientation(LinearLayout.HORIZONTAL);
        addCardActionButton(revokeActions, T.format("cap.revoke", en, label),
            view -> revokeCapability(task, label, capability, scope), taskButtons);
        card.addView(revokeActions);
    }

    private void confirmCapabilityGrant(AgentStore.Task task, String label, String capability, String scope) {
        boolean en = isEnglish();
        String details = CapabilityBroker.TRACEWEAVE_CAPABILITY.equals(capability)
            ? (T.s("ui.allow_this_task_to_query_retained_local", en))
            : T.format("cap.allow_details", en, label);
        AlertDialog grantDialog = new AlertDialog.Builder(this)
            .setView(Ui.dialogBody(this, T.format("cap.allow_title", en, label), details, 13))
            .setNegativeButton(T.s("ui.deny", en), (dialog, which) -> {
                try {
                    capabilityBroker.deny(task.id, capability, scope);
                    showMessage(T.format("cap.denied", en, label));
                } catch (Exception error) {
                    showError(error);
                }
            })
            .setPositiveButton(T.s("ui.allow_for_5_minutes", en), (dialog, which) -> {
                try {
                    capabilityBroker.grant(task.id, capability, scope);
                    showMessage(T.format("cap.allowed", en, label));
                } catch (Exception error) {
                    showError(error);
                }
                refreshTasks();
            })
            .create();
        grantDialog.show();
        Ui.themeDialog(grantDialog, this);
    }

    private void invokeCapability(AgentStore.Task task, String capability, String scope) {
        if (busy) return;
        setBusy(true, T.s("cap.reading", isEnglish()));
        io.execute(() -> {
            String value = null;
            Exception failure = null;
            try {
                value = capabilityBroker.invoke(task.id, capability, scope);
            } catch (Exception error) {
                failure = error;
            }
            String result = value;
            Exception invokeFailure = failure;
            mainHandler.post(() -> {
                if (destroyed) return;
                setBusy(false, T.s(invokeFailure == null ? "cap.read_done" : "cap.read_failed", isEnglish()));
                refreshTasks();
                if (invokeFailure != null) {
                    showError(invokeFailure);
                } else if (CapabilityBroker.TRACEWEAVE_CAPABILITY.equals(capability)) {
                    showTraceweaveResult(task, result);
                } else {
                    showMessage(result);
                }
            });
        });
    }

    private void showTraceweaveResult(AgentStore.Task task, String result) {
        TextView content = new TextView(this);
        boolean en = isEnglish();
        content.setText(result + (T.s("ui.n_nthis_simulated_summary_uses_locally", en)));
        content.setTextIsSelectable(true);
        content.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView holder = new ScrollView(this);
        holder.addView(content);
        new AlertDialog.Builder(this)
            .setTitle(T.s("ui.traceweave_local_history_summary_2", en))
            .setView(holder)
            .setNegativeButton(T.s("ui.done_2", en), null)
            .setPositiveButton(T.s("ui.save_as_factual_memory", en), (dialog, which) -> {
                try {
                    String reference = "task=" + task.id + ";provider=traceweave;read="
                        + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date());
                    store.createMemory(AgentStore.MEMORY_FACT, result,
                        AgentStore.MEMORY_SOURCE_TRACEWEAVE, reference);
                    refreshMemories();
                    showMessage(T.s("ui.summary_saved_as_a_traceweave_sourced_fac", en));
                } catch (Exception error) {
                    showError(error);
                }
            })
            .show();
    }

    private void revokeCapability(AgentStore.Task task, String label, String capability, String scope) {
        try {
            boolean revoked = capabilityBroker.revoke(task.id, capability, scope);
            showMessage(isEnglish() ? (revoked ? label + " grant revoked" : "No active " + label + " grant")
                : T.format(revoked ? "cap.revoked" : "cap.none", isEnglish(), label));
        } catch (Exception error) {
            showError(error);
        }
        if (AndroidRemoteStatusProvider.CAPABILITY.equals(capability)) {
            restartMainActivityAfterRemoteGrantRelease();
            return;
        }
        refreshTasks();
    }

    private void restartMainActivityAfterRemoteGrantRelease() {
        mainHandler.removeCallbacks(remoteUriExpiry);
        remoteUriExpiresAtElapsed = 0;
        Intent restart = new Intent(this, MainActivity.class);
        restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(restart);
        finish();
    }

    private void scheduleRemoteUriExpiry() {
        if (remoteUriExpiresAtElapsed == 0 || destroyed) return;
        long delay = remoteUriExpiresAtElapsed - SystemClock.elapsedRealtime();
        if (delay <= 0) {
            expireRemoteUriGrant();
        } else {
            mainHandler.postDelayed(remoteUriExpiry, delay);
        }
    }

    private void expireRemoteUriGrant() {
        if (remoteUriExpiresAtElapsed > 0
                && SystemClock.elapsedRealtime() >= remoteUriExpiresAtElapsed && !destroyed) {
            capabilityBroker.releaseAllRemoteUris();
            showMessage(T.s("cap.remote_expired", isEnglish()));
            finishAndRemoveTask();
        }
    }

    private void confirmDeleteTask(AgentStore.Task task) {
        AlertDialog deleteTaskDialog = new AlertDialog.Builder(this)
            .setView(Ui.dialogBody(this, T.s("task.delete_title", isEnglish()),
                T.s("task.delete_body", isEnglish()), 13))
            .setNegativeButton(T.s("task.delete_keep", isEnglish()), null)
            .setPositiveButton(T.s("task.delete_confirm", isEnglish()), (dialog, which) -> {
                try {
                    capabilityBroker.revoke(task.id, AndroidRemoteStatusProvider.CAPABILITY,
                        AndroidRemoteStatusProvider.SCOPE);
                    store.deleteTask(task.id);
                    AgentScheduler.cancelTask(this, task.jobId);
                    refreshTasks();
                } catch (Exception error) {
                    showError(error);
                }
            })
            .create();
        deleteTaskDialog.show();
        Ui.themeDialog(deleteTaskDialog, this);
    }

    void confirmExportScope() {
        if (busy) return;
        try {
            int memories = store.listMemories().size();
            int tasks = store.listTasks().size();
            int tombstones = store.tombstoneCount();
            String message = T.format("export.scope", isEnglish(), memories, tasks, tombstones)
                + "\n" + T.s("export.scope_note", isEnglish());
            AlertDialog scopeDialog = new AlertDialog.Builder(this)
                .setView(Ui.dialogBody(this, T.s("export.scope_title", isEnglish()), message, 13))
                .setNegativeButton(T.s("common.cancel", isEnglish()), null)
                .setPositiveButton(T.s("export.continue", isEnglish()), (dialog, which) -> promptExportPassphrase())
                .create();
            scopeDialog.show();
            Ui.themeDialog(scopeDialog, this);
        } catch (Exception error) {
            showError(error);
        }
    }

    void promptExportPassphrase() {
        if (busy) return;
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), dp(8), dp(24), 0);
        EditText first = passwordField(T.s("archive.passphrase_new", isEnglish()));
        EditText second = passwordField(T.s("archive.passphrase_again", isEnglish()));
        fields.addView(first);
        fields.addView(second);
        String exportNote = isEnglish()
            ? "Use and retain a passphrase of at least 12 characters. To restore this archive, the destination must run Agent OS 0.6.0 or later."
            : T.s("archive.passphrase_note", false);
        fields.addView(Ui.title(this, T.s("archive.export_title", isEnglish())), 0);
        TextView exportNoteView = new TextView(this);
        exportNoteView.setText(exportNote);
        exportNoteView.setTextSize(12);
        exportNoteView.setTextColor(Ui.muted(this));
        fields.addView(exportNoteView, 1);
        AlertDialog exportDialog = new AlertDialog.Builder(this)
            .setView(fields)
            .setNegativeButton(T.s("common.cancel", isEnglish()), null)
            .setPositiveButton(T.s("archive.choose_location", isEnglish()), (dialog, which) -> {
                char[] passphrase = first.getText().toString().toCharArray();
                char[] confirmation = second.getText().toString().toCharArray();
                first.setText("");
                second.setText("");
                if (!Arrays.equals(passphrase, confirmation)) {
                    clear(passphrase);
                    clear(confirmation);
                    showMessage(T.s("archive.passphrase_mismatch", isEnglish()));
                    return;
                }
                clear(confirmation);
                if (Character.codePointCount(passphrase, 0, passphrase.length) < 12) {
                    clear(passphrase);
                    showMessage(T.s("archive.passphrase_short", isEnglish()));
                    return;
                }
                pendingExportPassphrase = passphrase;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/octet-stream");
                intent.putExtra(Intent.EXTRA_TITLE, "agent-os-memory-" + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()) + ".taos");
                try {
                    setBusy(true, T.s("archive.picking_location", isEnglish()));
                    startActivityForResult(intent, CREATE_ARCHIVE_REQUEST);
                } catch (ActivityNotFoundException error) {
                    clearPendingExportPassphrase();
                    setBusy(false, "");
                    showMessage(T.s("archive.no_save_location", isEnglish()));
                }
            })
            .create();
        exportDialog.show();
        Ui.themeDialog(exportDialog, this);
    }

    void chooseArchiveToImport() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            setBusy(true, T.s("archive.picking_file", isEnglish()));
            startActivityForResult(intent, OPEN_ARCHIVE_REQUEST);
        } catch (ActivityNotFoundException error) {
            setBusy(false, "");
            showMessage(T.s("archive.no_picker", isEnglish()));
        }
    }

    private void promptImportPassphrase(Uri uri) {
        boolean en = isEnglish();
        EditText input = passwordField(T.s("ui.archive_passphrase", en));
        LinearLayout holder = new LinearLayout(this);
        // Default LinearLayout is horizontal; without this the passphrase field is
        // squeezed to zero width and the import dialog cannot be used.
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setPadding(dp(24), dp(8), dp(24), 0);
        holder.addView(input);
        holder.addView(Ui.title(this, T.s("ui.unlock_archive", en)), 0);
        TextView unlockNote = new TextView(this);
        unlockNote.setText(T.s("ui.review_conflicting_memories_before_imp", en));
        unlockNote.setTextSize(12);
        unlockNote.setTextColor(Ui.muted(this));
        holder.addView(unlockNote, 1);
        AlertDialog unlockDialog = new AlertDialog.Builder(this)
            .setView(holder)
            .setNegativeButton(T.s("ui.cancel", en), (dialog, which) -> setBusy(false, T.s("ui.import_cancelled", en)))
            .setPositiveButton(T.s("ui.review", en), (dialog, which) -> {
                char[] passphrase = input.getText().toString().toCharArray();
                input.setText("");
                if (passphrase.length == 0) {
                    setBusy(false, "");
                    showMessage(T.s("ui.enter_the_archive_passphrase", en));
                    return;
                }
                reviewArchive(uri, passphrase);
            })
            .setOnCancelListener(dialog -> setBusy(false, T.s("ui.import_cancelled", en)))
            .create();
        unlockDialog.show();
        Ui.themeDialog(unlockDialog, this);
    }

    private EditText passwordField(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return input;
    }

    private void writeArchive(Uri uri, char[] passphrase) {
        setBusy(true, T.s("archive.encrypting", isEnglish()));
        io.execute(() -> {
            Exception failure = null;
            try {
                byte[] archive = store.exportArchive(passphrase);
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new java.io.IOException(T.s("archive.cannot_open_target", isEnglish()));
                    output.write(archive);
                    output.flush();
                } finally {
                    Arrays.fill(archive, (byte) 0);
                }
            } catch (Exception error) {
                failure = error;
            } finally {
                clear(passphrase);
            }
            Exception result = failure;
            mainHandler.post(() -> {
                if (destroyed) return;
                setBusy(false, T.s(result == null ? "archive.saved" : "archive.export_failed", isEnglish()));
                if (result != null) showError(result);
            });
        });
    }

    private void reviewArchive(Uri uri, char[] passphrase) {
        setBusy(true, T.s("archive.unlocking", isEnglish()));
        io.execute(() -> {
            AgentStore.ArchiveReview review = null;
            Exception failure = null;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new java.io.IOException(T.s("archive.cannot_open_file", isEnglish()));
                review = store.reviewArchive(input, passphrase);
            } catch (Exception error) { failure = error; }
            finally { clear(passphrase); }
            AgentStore.ArchiveReview result = review;
            Exception error = failure;
            mainHandler.post(() -> {
                if (destroyed) return;
                if (error != null) { setBusy(false, ""); showError(error); return; }
                reviewArchiveConflict(result, new java.util.ArrayList<>(result.conflicts.keySet()), 0, new java.util.LinkedHashMap<>());
            });
        });
    }

    private void reviewArchiveConflict(AgentStore.ArchiveReview review, List<String> ids, int index, java.util.Map<String, Boolean> choices) {
        boolean en = isEnglish();
        if (index == ids.size()) {
            AlertDialog importConfirm = new AlertDialog.Builder(this)
                .setView(Ui.dialogBody(this, T.s("ui.confirm_archive_import", en),
                    T.format("archive.import_confirm", en, review.localMemoryDeletions, review.localTaskDeletions), 13))
                .setNegativeButton(T.s("ui.cancel", en), (dialog, which) -> setBusy(false, T.s("ui.import_cancelled", en)))
                .setPositiveButton(T.s("ui.import", en), (dialog, which) -> importReviewedArchive(review, choices))
                .setOnCancelListener(dialog -> setBusy(false, T.s("ui.import_cancelled", en)))
                .create();
            importConfirm.show();
            Ui.themeDialog(importConfirm, this);
            return;
        }
        String id = ids.get(index);
        AgentStore.ArchiveConflict conflict = review.conflicts.get(id);
        AlertDialog conflictDialog = new AlertDialog.Builder(this)
            .setView(Ui.dialogBody(this, (T.s("ui.memory_conflict", en)) + (index + 1) + "/" + ids.size(),
                (T.s("ui.on_this_device_n", en)) + archiveMemoryDescription(conflict.local, en)
                    + (T.s("ui.n_nin_the_archive_n", en)) + archiveMemoryDescription(conflict.incoming, en), 12))
            .setNegativeButton(T.s("ui.cancel", en), (dialog, which) -> setBusy(false, T.s("ui.import_cancelled", en)))
            .setNeutralButton(T.s("ui.use_archive", en), (dialog, which) -> {
                choices.put(id, true); reviewArchiveConflict(review, ids, index + 1, choices);
            })
            .setPositiveButton(T.s("ui.keep_local", en), (dialog, which) -> {
                choices.put(id, false); reviewArchiveConflict(review, ids, index + 1, choices);
            })
            .setOnCancelListener(dialog -> setBusy(false, T.s("ui.import_cancelled", en)))
            .create();
        conflictDialog.show();
        Ui.themeDialog(conflictDialog, this);
    }

    private String archiveMemoryDescription(AgentStore.Memory memory, boolean en) {
        String expires = memory.expiresAt == 0 ? (T.s("ui.none", en)) : DateFormat.getDateTimeInstance().format(new Date(memory.expiresAt));
        return memory.content + (T.s("ui.ntype", en)) + memory.kind
            + (T.s("ui.source", en)) + memory.source
            + (memory.sourceReference.isEmpty() ? "" : " (" + memory.sourceReference + ")")
            + (T.s("ui.nexpires", en)) + expires
            + (T.s("ui.user_edited", en)) + (memory.userEdited ? (T.s("ui.yes", en)) : (T.s("ui.no", en)));
    }

    private void importReviewedArchive(AgentStore.ArchiveReview review, java.util.Map<String, Boolean> choices) {
        setBusy(true, T.s("archive.importing", isEnglish()));
        io.execute(() -> {
            AgentStore.ImportResult result = null;
            Exception failure = null;
            try {
                result = store.importReviewedArchive(review, choices);
            } catch (Exception error) {
                failure = error;
            }
            AgentStore.ImportResult importResult = result;
            Exception importFailure = failure;
            mainHandler.post(() -> {
                if (destroyed) return;
                boolean en = isEnglish();
                setBusy(false, importFailure == null ? (T.s("ui.archive_imported", en)) : (T.s("ui.import_failed", en)));
                if (importFailure != null) {
                    showError(importFailure);
                } else {
                    refreshTasks();
                    refreshMemories();
                    showMessage(T.format("archive.imported", en, importResult.tasksImported, importResult.memoriesImported,
                        importResult.tombstonesApplied, importResult.memoryConflicts, importResult.memoriesReplaced,
                        importResult.recordsSkipped));
                }
            });
        });
    }

    void addActionButton(LinearLayout parent, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTextColor(0xFF334155);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF1F5F9);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFFCBD5E1);
        button.setBackground(bg);
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        button.setOnClickListener(listener);
        button.setEnabled(!busy);
        persistentButtons.add(button);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1);
        lp.setMargins(dp(3), dp(4), dp(3), dp(4));
        parent.addView(button, lp);
    }

    private void startSensorRefresh() {
        mainHandler.removeCallbacks(sensorRefresh);
        mainHandler.post(sensorRefresh);
    }

    private void updateSensorsCard() {
        if (sensInfo == null) return;
        sensInfo.setText(SensorContext.describe(PhysicalSensoriumManager.getInstance(this).evaluateCurrentContext(), isEnglish()));
    }

    private void updateLocalModelStatus() {
        if (slmInfo == null) return;
        slmInfo.setText(LocalModelStatus.describe(isEnglish(), LocalModelRuntime.isAvailable(), LocalModelActivity.modelFile(this).isFile()));
    }

    private void updateSensorToggleButton() {
        if (sensorToggleButton == null) return;
        boolean en = isEnglish();
        boolean monitoring = PhysicalSensoriumManager.getInstance(this).isMonitoring();
        sensorToggleButton.setText(en
                ? (monitoring ? "Stop sensors" : "Enable sensors")
                : T.s(monitoring ? "sensor.stop" : "sensor.start", isEnglish()));
    }

    private void showSensorsDialog(TextView sensInfo) {
        boolean en = isEnglish();
        PhysicalSensoriumManager sens = PhysicalSensoriumManager.getInstance(this);
        boolean monitoring = sens.isMonitoring();
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(T.s("ui.sensors", en))
            .setMessage(monitoring ? sensInfo.getText() : T.s("ui.enable_sensors_to_view_live_context", en))
            .setNegativeButton(T.s("common.close", en), null);
        if (monitoring) {
            builder.setPositiveButton(T.s("sensor.save_as_memory", en), (dialog, which) -> reviewSensorObservation());
            builder.setNeutralButton(T.s("sensor.stop", en), (dialog, which) -> {
                sens.stopMonitoring();
                updateSensorsCard();
                mainHandler.removeCallbacks(sensorRefresh);
                Toast.makeText(this, T.s("ui.sensor_monitoring_stopped", en), Toast.LENGTH_SHORT).show();
            });
        } else {
            builder.setPositiveButton(T.s("sensor.start", en), (dialog, which) -> {
                sens.startMonitoring();
                updateSensorsCard();
                startSensorRefresh();
            });
        }
        builder.show();
    }

    private void reviewSensorObservation() {
        boolean en = isEnglish();
        try {
            PhysicalSensoriumManager sensors = PhysicalSensoriumManager.getInstance(this);
            org.json.JSONObject event = sensors.lastMotionChange();
            if (event == null) throw new IllegalStateException(T.s("ui.enable_sensors_and_wait_for_a_motion_sta", en));
            String from = "LowMotion".equals(event.getString("from")) ? (T.s("ui.low_motion", en)) : (T.s("ui.motion_detected", en));
            String to = "LowMotion".equals(event.getString("to")) ? (T.s("ui.low_motion", en)) : (T.s("ui.motion_detected", en));
            LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(36, 12, 36, 0);
            TextView info = new TextView(this);
            info.setText((T.s("ui.source_accelerometer_gyroscope_3_secon", en))
                + DateFormat.getDateTimeInstance().format(new Date(event.getLong("observedAt"))));
            content.addView(info);
            EditText text = new EditText(this); text.setMinLines(2);
            text.setText((T.s("ui.sensor_estimate_changed_from", en)) + from + (T.s("ui.to", en)) + to);
            content.addView(text);
            Spinner expiry = new Spinner(this); expiry.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{T.s("memory.expire_7", en), T.s("memory.expire_30", en), T.s("memory.no_expiration", en)}));
            content.addView(expiry);
            new AlertDialog.Builder(this).setTitle(T.s("ui.review_before_saving_memory", en))
                .setView(content).setNegativeButton(T.s("ui.discard", en), null)
                .setPositiveButton(T.s("ui.confirm_and_save", en), (dialog, which) -> {
                    try {
                        org.json.JSONObject latest = sensors.lastMotionChange();
                        if (latest == null || !latest.toString().equals(event.toString())) throw new IllegalStateException(T.s("ui.observation_changed_review_it_again", en));
                        int index = expiry.getSelectedItemPosition();
                        long expiresAt = index == 2 ? 0 : System.currentTimeMillis() + (index == 0 ? 7L : 30L) * 24 * 60 * 60 * 1000;
                        store.confirmSensorObservation(event, text.getText().toString(), expiresAt);
                        refreshMemories();
                    } catch (Exception error) { showError(error); }
                }).show();
        } catch (Exception error) { showError(error); }
    }

    private void updateFloatPermStatus() {
        if (floatPermStatus == null) return;
        boolean canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        boolean en = isEnglish();
        if (canDraw) {
            floatPermStatus.setText(T.s("ui.overlay_permission_granted_can_display_o", en));
            floatPermStatus.setTextColor(0xFF00796B);
        } else {
            floatPermStatus.setText(T.s("ui.overlay_permission_not_granted_click_bel", en));
            floatPermStatus.setTextColor(0xFFD32F2F);
        }
    }

    private void addPageButton(LinearLayout parent, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTextColor(0xFFFFFFFF);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0F172A);
        bg.setCornerRadius(dp(8));
        button.setBackground(bg);
        button.setPadding(dp(10), dp(4), dp(10), dp(4));
        button.setOnClickListener(listener);
        button.setEnabled(!busy);
        persistentButtons.add(button);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        lp.setMargins(0, dp(6), 0, dp(6));
        parent.addView(button, lp);
    }

    private void addCardActionButton(LinearLayout parent, String label, View.OnClickListener listener,
            java.util.ArrayList<Button> buttonGroup) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTextColor(0xFF334155);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF1F5F9);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFFCBD5E1);
        button.setBackground(bg);
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        button.setOnClickListener(listener);
        button.setEnabled(!busy);
        buttonGroup.add(button);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1);
        lp.setMargins(dp(3), dp(4), dp(3), dp(4));
        parent.addView(button, lp);
    }

    private void addDivider(LinearLayout parent, LinearLayout child) {
        View divider = new View(this);
        divider.setBackgroundColor(0xFFDDDDDD);
        if (child != null) {
            child.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            parent.addView(child);
        } else if (parent != null) {
            parent.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        }
    }

    private void addEmptyMessage(LinearLayout parent, String text) {
        TextView empty = new TextView(this);
        empty.setText(text);
        empty.setTextColor(Ui.muted(this));
        empty.setPadding(0, 8, 0, 8);
        parent.addView(empty);
    }

    /** Empty state with a way out: focus the composer so the next tap can start a task. */
    private void addEmptyTasks(LinearLayout parent, String text) {
        addEmptyMessage(parent, text);
        Button start = createCompactActionButton(T.s("task.start_first", isEnglish()), 0xFF0F172A, 0xFFFFFFFF,
            view -> {
                if (pageScroll != null) pageScroll.post(() -> pageScroll.fullScroll(View.FOCUS_UP));
                if (taskInput != null) {
                    taskInput.requestFocus();
                    android.view.inputmethod.InputMethodManager keyboard =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (keyboard != null) keyboard.showSoftInput(taskInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                }
            });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.setMargins(0, dp(4), 0, 0);
        parent.addView(start, lp);
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        if (message != null && !message.isEmpty()) settingsPanel.setRecoveryMessage(message);
        for (Button button : persistentButtons) button.setEnabled(!value);
        for (Button button : taskButtons) button.setEnabled(!value);
    }

    private void clearPendingExportPassphrase() {
        clear(pendingExportPassphrase);
        pendingExportPassphrase = null;
    }

    private static void clear(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }

    private void showError(Exception error) {
        String message = error.getMessage() == null ? T.s("common.operation_failed", isEnglish()) : error.getMessage();
        showMessage(message);
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private LinearLayout createCard(LinearLayout parent, IconFactory.IconType iconType, String title, int titleColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.card(this));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Ui.stroke(this));
        card.setBackground(bg);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(6));
        card.setLayoutParams(lp);

        LinearLayout headerRow = IconFactory.createHeaderWithIcon(this, iconType, title, titleColor, 15);
        card.addView(headerRow);

        parent.addView(card);
        return card;
    }

    private LinearLayout createGridCard(LinearLayout rowParent, IconFactory.IconType iconType, String title, int titleColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.card(this));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Ui.stroke(this));
        card.setBackground(bg);
        card.setPadding(dp(7), dp(8), dp(7), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, dp(96), 1.0f);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        card.setLayoutParams(lp);

        LinearLayout headerRow = IconFactory.createHeaderWithIcon(this, iconType, title, titleColor, 12, 11);
        card.addView(headerRow);

        rowParent.addView(card);
        return card;
    }

    private LinearLayout createGridRow(LinearLayout page) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(1), 0, dp(2));
        row.setLayoutParams(lp);
        page.addView(row);
        return row;
    }

    private TextView getStatusBadge(String status) {
        boolean en = isEnglish();
        if (AgentStore.COMPLETED.equals(status)) {
            return IconFactory.createBadge(this, T.s("ui.completed", en), 0xFFDCFCE7, 0xFF166534);
        } else if (AgentStore.OUTCOME_UNKNOWN.equals(status)) {
            return IconFactory.createBadge(this, T.s("ui.outcome_unknown", en), 0xFFFEF3C7, 0xFF92400E);
        } else if (AgentStore.FAILED.equals(status)) {
            return IconFactory.createBadge(this, T.s("ui.failed_2", en), 0xFFFEE2E2, 0xFF991B1B);
        } else if (AgentStore.ACTIVE.equals(status)) {
            return IconFactory.createBadge(this, T.s("ui.scheduled_active", en), 0xFFDBEAFE, 0xFF1E40AF);
        } else if (AgentStore.WAITING_FOR_USER.equals(status)) {
            return IconFactory.createBadge(this, T.s("ui.awaiting_user", en), 0xFFFEF3C7, 0xFF92400E);
        } else if (AgentStore.PAUSED.equals(status)) {
            return IconFactory.createBadge(this, T.s("ui.paused", en), 0xFFF1F5F9, 0xFF475569);
        } else if (AgentStore.CANCELLED.equals(status)) {
            return IconFactory.createBadge(this, T.s("ui.cancelled", en), 0xFFFEE2E2, 0xFF991B1B);
        }
        return IconFactory.createBadge(this, T.s("ui.pending_2", en), 0xFFE0E7FF, 0xFF3730A3);
    }


    Button createCompactActionButton(String text, int bgColor, int textColor, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(11);
        btn.setAllCaps(false);
        btn.setTextColor(textColor);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFFCBD5E1);
        btn.setBackground(bg);
        btn.setPadding(dp(8), dp(2), dp(8), dp(2));
        btn.setOnClickListener(listener);
        return btn;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
