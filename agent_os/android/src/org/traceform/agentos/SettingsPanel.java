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
        root.setBackgroundColor(Ui.background(host));

        LinearLayout titleRow = IconFactory.createHeaderWithIcon(host, IconFactory.IconType.SHIELD,
                T.s("settingspanel.1", en), 0xFF334155, 16);
        root.addView(titleRow);
        root.addView(Ui.section(host, T.s("settings.section_data", en)));

        LinearLayout archiveActions = new LinearLayout(host);
        archiveActions.setOrientation(LinearLayout.HORIZONTAL);
        archiveActions.setPadding(0, host.dpRef(10), 0, host.dpRef(6));
        host.addActionButton(archiveActions, T.s("settingspanel.2", en), view -> host.promptExportPassphrase());
        host.addActionButton(archiveActions, T.s("settingspanel.3", en), view -> host.chooseArchiveToImport());
        root.addView(archiveActions);

        LinearLayout notificationRow = new LinearLayout(host);
        notificationRow.setOrientation(LinearLayout.HORIZONTAL);
        boolean notificationGranted = host.notificationsGranted();
        TextView notificationState = new TextView(host);
        notificationState.setText(T.s(notificationGranted ? "settings.notify_on" : "settings.notify_off", en));
        notificationState.setTextSize(11);
        notificationState.setTextColor(0xFF64748B);
        notificationState.setPadding(0, host.dpRef(6), host.dpRef(8), host.dpRef(6));
        notificationRow.addView(notificationState);
        if (!notificationGranted) {
            host.addActionButton(notificationRow, T.s("settingspanel.4", en),
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
        pairingState.setText(T.format("settings.paired_count", en, paired));
        pairingState.setTextSize(11);
        pairingState.setTextColor(0xFF64748B);
        pairingState.setPadding(0, host.dpRef(6), host.dpRef(8), host.dpRef(6));
        pairingRow.addView(pairingState);
        host.addActionButton(pairingRow, T.s("settingspanel.5", en), view -> host.showPairingDialog());
        host.addActionButton(pairingRow, T.s("settingspanel.6", en), view -> host.showPairingList());
        root.addView(pairingRow);

        root.addView(host.syncPanelRef().settingsSection(en));

        root.addView(Ui.section(host, T.s("settings.section_services", en)));

        traceformStatus = new TextView(host);
        traceformStatus.setTextSize(12);
        root.addView(traceformStatus);

        traceformToggleButton = host.createCompactActionButton(T.s("settingspanel.7", en), 0xFFF1F5F9, 0xFF334155, view -> toggleTraceformProvider());
        host.persistentButtons.add(traceformToggleButton);
        LinearLayout.LayoutParams tfLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dpRef(34));
        tfLp.setMargins(0, host.dpRef(4), 0, host.dpRef(10));
        root.addView(traceformToggleButton, tfLp);
        refreshTraceform();

        cloudModelStatus = new TextView(host);
        cloudModelStatus.setTextSize(12);
        root.addView(cloudModelStatus);

        cloudModelConfigButton = host.createCompactActionButton(T.s("settingspanel.8", en), 0xFFF1F5F9, 0xFF334155, view -> promptCloudModelConfig());
        host.persistentButtons.add(cloudModelConfigButton);
        LinearLayout.LayoutParams cmLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dpRef(34));
        cmLp.setMargins(0, host.dpRef(4), 0, host.dpRef(4));
        root.addView(cloudModelConfigButton, cmLp);
        refreshCloud();

        scroll.addView(root);
        builder.setView(scroll);
        builder.setPositiveButton(T.s("settingspanel.9", en), null);
        AlertDialog dialog = builder.create();
        dialog.show();
        if (Ui.dark(host) && dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.background(host)));
            if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFF7DD3FC);
        }
    }

    void toggleTraceformProvider() {
        boolean en = host.english();
        if (host.capabilityBrokerRef().isTraceformEnabled()) {
            new AlertDialog.Builder(host)
                .setTitle(T.s("settingspanel.10", en))
                .setMessage(T.s("settingspanel.11", en))
                .setNegativeButton(T.s("settingspanel.12", en), null)
                .setPositiveButton(T.s("settingspanel.13", en), (dialog, which) -> {
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
            host.toast(T.s("settingspanel.14", en));
            return;
        }
        new AlertDialog.Builder(host)
            .setTitle(T.s("settingspanel.15", en))
            .setMessage(T.s("settingspanel.16", en))
            .setNegativeButton(T.s("settingspanel.17", en), null)
            .setPositiveButton(T.s("settingspanel.18", en), (dialog, which) -> {
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
        traceformToggleButton.setText(enabled ? (T.s("settingspanel.19", en)) : (T.s("settingspanel.20", en)));
        traceformStatus.setText(enabled
            ? trustedService ? (T.s("settingspanel.21", en))
                : (T.s("settingspanel.22", en))
            : (T.s("settingspanel.23", en)));
    }

    void refreshCloud() {
        if (cloudModelStatus == null || cloudModelConfigButton == null) return;
        boolean en = host.english();
        CloudModelConfig config = CloudModelConfig.load(host);
        cloudModelConfigButton.setText(config.enabled
            ? (T.s("settingspanel.24", en))
            : (T.s("settingspanel.25", en)));
        cloudModelStatus.setText(config.enabled
            ? T.format("settings.cloud_configured", en, config.modelName)
            : (T.s("settings.cloud_planning_is_disabled_task_planni", en)));
    }

    void promptCloudModelConfig() {
        boolean en = host.english();
        CloudModelConfig current = CloudModelConfig.load(host);
        LinearLayout fields = new LinearLayout(host);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(host.dpRef(24), host.dpRef(8), host.dpRef(24), 0);

        final android.widget.CheckBox enabledCheck = new android.widget.CheckBox(host);
        enabledCheck.setText(T.s("settingspanel.26", en));
        enabledCheck.setChecked(current.enabled);
        fields.addView(enabledCheck);

        final EditText urlInput = new EditText(host);
        urlInput.setHint(T.s("settingspanel.27", en));
        urlInput.setText(current.endpointUrl);
        fields.addView(urlInput);

        final EditText keyInput = new EditText(host);
        keyInput.setHint(T.s("settingspanel.28", en));
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setText(current.apiKey);
        fields.addView(keyInput);

        final boolean[] keyVisible = {false};
        final Button toggleKeyVisBtn = new Button(host);
        toggleKeyVisBtn.setText(T.s("settingspanel.29", en));
        toggleKeyVisBtn.setTextSize(11);
        toggleKeyVisBtn.setTextColor(0xFF0284C7);
        toggleKeyVisBtn.setBackgroundColor(0x00000000);
        toggleKeyVisBtn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggleKeyVisBtn.setPadding(0, host.dpRef(2), 0, host.dpRef(4));
        toggleKeyVisBtn.setOnClickListener(v -> {
            keyVisible[0] = !keyVisible[0];
            if (keyVisible[0]) {
                keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                toggleKeyVisBtn.setText(T.s("settingspanel.30", en));
            } else {
                keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                toggleKeyVisBtn.setText(T.s("settingspanel.31", en));
            }
            keyInput.setSelection(keyInput.getText().length());
        });
        fields.addView(toggleKeyVisBtn);

        final EditText modelInput = new EditText(host);
        modelInput.setHint(T.s("settingspanel.32", en));
        modelInput.setText(current.modelName);
        fields.addView(modelInput);

        new AlertDialog.Builder(host)
            .setTitle(T.s("settingspanel.33", en))
            .setMessage(T.s("settings.saving_this_configuration_sends_no_tas", en))
            .setView(fields)
            .setNegativeButton(T.s("settingspanel.34", en), null)
            .setPositiveButton(T.s("settingspanel.35", en), (dialog, which) -> {
                String endpointUrl = urlInput.getText().toString();
                if (enabledCheck.isChecked() && !CloudModelClient.isAllowedEndpointUrl(endpointUrl)) {
                    host.toast(T.s("settings.use_https_for_cloud_endpoints_http_is", en));
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
                    host.toast(T.s("settings.could_not_protect_the_api_key_with_and", en));
                    return;
                }
                refreshCloud();
                host.toast(T.s("settingspanel.36", en));
            })
            .show();
    }

}
