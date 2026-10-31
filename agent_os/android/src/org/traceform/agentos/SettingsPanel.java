package org.traceform.agentos;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

/** Settings sheet and service toggles, extracted from MainActivity. */
final class SettingsPanel {
    private final MainActivity host;
    private TextView activityStatus;
    private TextView traceformStatus;
    private Button traceformToggleButton;
    private TextView cloudModelStatus;
    private Button cloudModelConfigButton;

    SettingsPanel(MainActivity host) {
        this.host = host;
    }

    void setRecoveryMessage(String message) {
        if (activityStatus != null) activityStatus.setText(message);
    }

    void show() {
        boolean en = host.english();
        AlertDialog.Builder builder = new AlertDialog.Builder(host);
        ScrollView scroll = new ScrollView(host);
        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(host.dpRef(16), host.dpRef(10), host.dpRef(16), host.dpRef(8));

        LinearLayout titleRow = IconFactory.createHeaderWithIcon(host, IconFactory.IconType.SHIELD,
                en ? "Data Management & Providers" : "数据管理与扩展服务", 0xFF334155, 16);
        root.addView(titleRow);
        root.addView(Ui.section(host, T.s("settings.section_data", en)));

        LinearLayout archiveActions = new LinearLayout(host);
        archiveActions.setOrientation(LinearLayout.HORIZONTAL);
        archiveActions.setPadding(0, host.dpRef(10), 0, host.dpRef(6));
        host.addActionButton(archiveActions, en ? "Export Archive" : "加密导出", view -> host.promptExportPassphrase());
        host.addActionButton(archiveActions, en ? "Import Archive" : "导入档案", view -> host.chooseArchiveToImport());
        root.addView(archiveActions);

        LinearLayout notificationRow = new LinearLayout(host);
        notificationRow.setOrientation(LinearLayout.HORIZONTAL);
        boolean notificationGranted = host.notificationsGranted();
        TextView notificationState = new TextView(host);
        notificationState.setText(en ? (notificationGranted ? "Notifications: enabled" : "Notifications: not enabled")
                                     : (notificationGranted ? "通知权限：已启用" : "通知权限：未启用"));
        notificationState.setTextSize(11);
        notificationState.setTextColor(0xFF64748B);
        notificationState.setPadding(0, host.dpRef(6), host.dpRef(8), host.dpRef(6));
        notificationRow.addView(notificationState);
        if (!notificationGranted) {
            host.addActionButton(notificationRow, en ? "Enable notifications" : "启用通知",
                view -> host.maybeRequestNotificationPermission(true));
        }
        root.addView(notificationRow);
        activityStatus = new TextView(host);
        activityStatus.setText(T.s("settings.note_export", en));
        activityStatus.setTextSize(10);
        activityStatus.setTextColor(0xFF64748B);
        activityStatus.setPadding(0, host.dpRef(2), 0, 0);
        root.addView(activityStatus);

        root.addView(Ui.section(host, T.s("settings.section_sync", en)));


        LinearLayout pairingRow = new LinearLayout(host);
        pairingRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView pairingState = new TextView(host);
        int paired = host.storeRef().listPairings().size();
        pairingState.setText(en ? "Paired devices: " + paired : "已配对设备：" + paired);
        pairingState.setTextSize(11);
        pairingState.setTextColor(0xFF64748B);
        pairingState.setPadding(0, host.dpRef(6), host.dpRef(8), host.dpRef(6));
        pairingRow.addView(pairingState);
        host.addActionButton(pairingRow, en ? "Pair device" : "配对设备", view -> host.showPairingDialog());
        host.addActionButton(pairingRow, en ? "Manage" : "管理设备", view -> host.showPairingList());
        root.addView(pairingRow);

        root.addView(host.syncPanelRef().settingsSection(en));

        root.addView(Ui.section(host, T.s("settings.section_services", en)));

        traceformStatus = new TextView(host);
        traceformStatus.setTextSize(12);
        root.addView(traceformStatus);

        traceformToggleButton = host.createCompactActionButton(en ? "Configure Traceform Provider" : "配置 Traceform Provider", 0xFFF1F5F9, 0xFF334155, view -> toggleTraceformProvider());
        host.persistentButtons.add(traceformToggleButton);
        LinearLayout.LayoutParams tfLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dpRef(34));
        tfLp.setMargins(0, host.dpRef(4), 0, host.dpRef(10));
        root.addView(traceformToggleButton, tfLp);
        refreshTraceform();

        cloudModelStatus = new TextView(host);
        cloudModelStatus.setTextSize(12);
        root.addView(cloudModelStatus);

        cloudModelConfigButton = host.createCompactActionButton(en ? "Configure Hybrid Cloud Inference" : "配置端云协同推理", 0xFFF1F5F9, 0xFF334155, view -> promptCloudModelConfig());
        host.persistentButtons.add(cloudModelConfigButton);
        LinearLayout.LayoutParams cmLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dpRef(34));
        cmLp.setMargins(0, host.dpRef(4), 0, host.dpRef(4));
        root.addView(cloudModelConfigButton, cmLp);
        refreshCloud();

        scroll.addView(root);
        builder.setView(scroll);
        builder.setPositiveButton(en ? "Close" : "关闭", null);
        builder.show();
    }

    void toggleTraceformProvider() {
        boolean en = host.english();
        if (host.capabilityBrokerRef().isTraceformEnabled()) {
            new AlertDialog.Builder(host)
                .setTitle(en ? "Disable Traceform Provider?" : "停用 Traceform Provider？")
                .setMessage(en ? "Disabling revokes Traceform task grants in this process. You remain in control of saved Traceform-sourced memories." : "停用后会撤销当前进程中的 Traceform 任务授权。已保存的 Traceform 来源记忆仍由你管理。")
                .setNegativeButton(en ? "Keep enabled" : "继续启用", null)
                .setPositiveButton(en ? "Disable" : "停用", (dialog, which) -> {
                    try {
                        host.capabilityBrokerRef().setTraceformEnabled(false);
                        refreshTraceform();
                        host.refreshAfterSync();
                    } catch (Exception error) {
                        host.failWith(error);
                    }
                })
                .show();
            return;
        }
        if (!host.capabilityBrokerRef().canEnableTraceform()) {
            host.toast(en ? "Trusted Traceform summary service is unavailable or unauthorized; check the Edge version and signer." : "Traceform 可信摘要服务未就绪或未获授权；请检查 Edge 版本及签名");
            return;
        }
        new AlertDialog.Builder(host)
            .setTitle(en ? "Enable optional Traceform Provider?" : "启用可选 Traceform Provider？")
            .setMessage(en ? "Enabling adds Traceform to task capabilities. Each read still requires separate authorization for that task. Edge reads its own history; Agent OS does not open the file, start sensor collection, or access the network. Saving a result as memory requires another confirmation." : "启用后，Traceform 才会出现在任务的能力列表中。每次读取仍需为具体任务单独授权；Edge 服务会只读查询自己的历史库，Agent OS 不直接打开该文件，不启动传感器采集或访问网络。保存结果为记忆还需再次确认。")
            .setNegativeButton(en ? "Cancel" : "取消", null)
            .setPositiveButton(en ? "Enable" : "启用", (dialog, which) -> {
                try {
                    host.capabilityBrokerRef().setTraceformEnabled(true);
                    refreshTraceform();
                    host.refreshAfterSync();
                } catch (Exception error) {
                    host.failWith(error);
                }
            })
            .show();
    }

    void refreshTraceform() {
        if (traceformStatus == null || traceformToggleButton == null || host.capabilityBrokerRef() == null) return;
        boolean en = host.english();
        boolean enabled = host.capabilityBrokerRef().isTraceformEnabled();
        boolean trustedService = host.capabilityBrokerRef().canEnableTraceform();
        traceformToggleButton.setText(enabled ? (en ? "Disable Traceform Provider" : "停用 Traceform Provider") : (en ? "Enable Traceform Provider" : "启用 Traceform Provider"));
        traceformStatus.setText(enabled
            ? trustedService ? (en ? "Enabled; each read requires per-task auth, zero passive sensor capture." : "已启用；读取仍需逐任务授权，且不会启动传感器采集。")
                : (en ? "Enabled in settings, but the trusted summary service is unavailable." : "设置已启用，但可信摘要服务不可用。")
            : (en ? "Disabled by default. Reads/saves always require explicit user confirmation." : "默认关闭。启用本身不会读取数据；每次读取和保存为记忆都需单独确认。"));
    }

    void refreshCloud() {
        if (cloudModelStatus == null || cloudModelConfigButton == null) return;
        boolean en = host.english();
        CloudModelConfig config = CloudModelConfig.load(host);
        cloudModelConfigButton.setText(config.enabled
            ? (en ? "Configure Cloud Planning" : "设置云端规划参数")
            : (en ? "Configure Optional Cloud Planning" : "配置可选云端规划"));
        cloudModelStatus.setText(config.enabled
            ? (en ? "Configured (Model: " + config.modelName + ") · Each planning request needs its own preview and approval; execution observations stay local."
                  : "已配置（模型：" + config.modelName + "） · 每次规划请求仍需预览并单独授权；执行观察保持本地。")
            : (en ? "Cloud planning is disabled. Task planning and synthesis run locally."
                  : "云端规划已关闭，任务规划与综合分析在本地运行。"));
    }

    void promptCloudModelConfig() {
        boolean en = host.english();
        CloudModelConfig current = CloudModelConfig.load(host);
        LinearLayout fields = new LinearLayout(host);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(host.dpRef(24), host.dpRef(8), host.dpRef(24), 0);

        final android.widget.CheckBox enabledCheck = new android.widget.CheckBox(host);
        enabledCheck.setText(en ? "Allow cloud planning previews (approval required for each request)" : "允许云端规划预览（每次请求仍需单独授权）");
        enabledCheck.setChecked(current.enabled);
        fields.addView(enabledCheck);

        final EditText urlInput = new EditText(host);
        urlInput.setHint(en ? "Endpoint URL, e.g. https://api.openai.com/v1/chat/completions" : "端点 URL，例如 https://api.openai.com/v1/chat/completions");
        urlInput.setText(current.endpointUrl);
        fields.addView(urlInput);

        final EditText keyInput = new EditText(host);
        keyInput.setHint(en ? "API Key (optional)" : "API Key（可选）");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setText(current.apiKey);
        fields.addView(keyInput);

        final boolean[] keyVisible = {false};
        final Button toggleKeyVisBtn = new Button(host);
        toggleKeyVisBtn.setText(en ? "Show API Key" : "显示 API Key");
        toggleKeyVisBtn.setTextSize(11);
        toggleKeyVisBtn.setTextColor(0xFF0284C7);
        toggleKeyVisBtn.setBackgroundColor(0x00000000);
        toggleKeyVisBtn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggleKeyVisBtn.setPadding(0, host.dpRef(2), 0, host.dpRef(4));
        toggleKeyVisBtn.setOnClickListener(v -> {
            keyVisible[0] = !keyVisible[0];
            if (keyVisible[0]) {
                keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                toggleKeyVisBtn.setText(en ? "Hide API Key" : "隐藏 API Key");
            } else {
                keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                toggleKeyVisBtn.setText(en ? "Show API Key" : "显示 API Key");
            }
            keyInput.setSelection(keyInput.getText().length());
        });
        fields.addView(toggleKeyVisBtn);

        final EditText modelInput = new EditText(host);
        modelInput.setHint(en ? "Model name, e.g. gpt-4o-mini or qwen2.5:7b" : "模型名称，例如 gpt-4o-mini 或 qwen2.5:7b");
        modelInput.setText(current.modelName);
        fields.addView(modelInput);

        new AlertDialog.Builder(host)
            .setTitle(en ? "Configure Hybrid Cloud Inference" : "配置端云协同推理")
            .setMessage(en ? "Saving this configuration sends no task. For each task, select memories, preview the exact endpoint and payload, then approve one planning request or keep the task local. Pattern redaction can miss sensitive information. Failed requests are not resent automatically; later execution observations stay local."
                           : "保存配置不会发送任务。每次任务需选择记忆、预览实际端点与请求内容，再决定仅允许本次规划或保持本地。规则脱敏可能遗漏敏感信息；请求失败不自动重发，后续执行观察保持本地。")
            .setView(fields)
            .setNegativeButton(en ? "Cancel" : "取消", null)
            .setPositiveButton(en ? "Save Config" : "保存配置", (dialog, which) -> {
                String endpointUrl = urlInput.getText().toString();
                if (enabledCheck.isChecked() && !CloudModelClient.isAllowedEndpointUrl(endpointUrl)) {
                    host.toast(en ? "Use HTTPS for cloud endpoints; HTTP is allowed only on this device."
                        : "云端端点必须使用 HTTPS；仅允许本机回环地址使用 HTTP。");
                    return;
                }
                CloudModelConfig updated = new CloudModelConfig(
                    enabledCheck.isChecked(),
                    endpointUrl,
                    keyInput.getText().toString(),
                    modelInput.getText().toString(),
                    CloudModelConfig.DEFAULT_TIMEOUT_MILLIS,
                    CloudModelConfig.DEFAULT_TEMPERATURE
                );
                if (!updated.save(host)) {
                    host.toast(en ? "Could not protect the API key with Android Keystore; configuration was not saved."
                        : "无法使用 Android Keyhost.storeRef() 加密 API Key，配置未保存。");
                    return;
                }
                refreshCloud();
                host.toast(en ? "Hybrid cloud inference configuration updated" : "端云协同配置已更新");
            })
            .show();
    }

}
