package org.traceform.agentos;

import android.app.AlertDialog;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Sync and pairing UI, extracted from MainActivity. Behaviour is unchanged. */
final class SyncPanel {
    private static final int SYNC_PORT = 47960;
    private static final int PAIR_PORT = 47961;

    private final MainActivity host;
    private boolean autoSyncAttempted;

    SyncPanel(MainActivity host) {
        this.host = host;
    }

    LinearLayout settingsSection(boolean en) {
        LinearLayout syncRow = new LinearLayout(host);
        syncRow.setOrientation(LinearLayout.VERTICAL);
        TextView syncState = new TextView(host);
        int pairedNow = host.storeRef().activePairings().size();
        String mySyncId = localSyncId();
        syncState.setText((en ? "Sync: " : "同步：") + (SyncTransport.available()
            ? (en ? pairedNow + " paired device(s)" : pairedNow + " 台已配对设备")
            : (en ? "runtime unavailable in this build" : "此构建不包含同步运行库"))
            + (mySyncId.isEmpty() ? "" : (en ? " · this device " : " · 本机 ") + mySyncId.substring(0, Math.min(8, mySyncId.length()))));
        syncState.setTextSize(11);
        syncState.setTextColor(0xFF64748B);
        syncState.setPadding(0, host.dpRef(6), 0, host.dpRef(2));
        syncRow.addView(syncState);

        LinearLayout syncButtons = new LinearLayout(host);
        syncButtons.setOrientation(LinearLayout.HORIZONTAL);
        host.addActionButton(syncButtons, T.s("sync.wait_peer", en), view -> startSyncServe(en));
        host.addActionButton(syncButtons, T.s("sync.now", en), view -> startSyncDial(en));
        syncRow.addView(syncButtons);

        android.widget.CheckBox autoSync = new android.widget.CheckBox(host);
        autoSync.setText(T.s("sync.auto", en));
        autoSync.setTextSize(11);
        autoSync.setTextColor(0xFF64748B);
        autoSync.setChecked(host.storeRef().syncAutoEnabled());
        autoSync.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                new AlertDialog.Builder(host)
                    .setTitle(T.s("sync.enable_title", en))
                    .setMessage(T.s("sync.enable_message", en))
                    .setNegativeButton(T.s("common.cancel", en), (dialog, which) -> button.setChecked(false))
                    .setPositiveButton(T.s("common.enable", en), (dialog, which) -> host.storeRef().setSyncAutoEnabled(true))
                    .setOnCancelListener(dialog -> button.setChecked(false))
                    .show();
            } else {
                host.storeRef().setSyncAutoEnabled(false);
            }
        });
        syncRow.addView(autoSync);
        return syncRow;
    }

    void showPairingDialog() {
        boolean en = host.english();
        try {
            DeviceIdentity.create();
            String local = DeviceIdentity.fingerprint();
            EditText remote = new EditText(host);
            remote.setHint(T.s("pair.remote_hint", en));
            TextView localView = new TextView(host);
            localView.setText((en ? "This device: " : "本机：") + local);
            localView.setTextSize(11);
            TextView syncView = new TextView(host);
            String localSyncId = localSyncId();
            syncView.setText((en ? "Sync ID: " : "同步 ID：") + (localSyncId.isEmpty() ? "—" : localSyncId));
            syncView.setTextSize(11);
            EditText remoteSync = new EditText(host);
            remoteSync.setHint(T.s("pair.sync_hint", en));
            EditText remoteAddress = new EditText(host);
            remoteAddress.setHint(T.s("pair.address_hint", en));

            LinearLayout holder = new LinearLayout(host);
            holder.setOrientation(LinearLayout.VERTICAL);
            holder.setPadding(host.dpRef(24), host.dpRef(8), host.dpRef(24), 0);
            holder.addView(localView);
            holder.addView(syncView);
            holder.addView(remote);
            holder.addView(remoteSync);
            holder.addView(remoteAddress);

            Button discoverButton = compact(T.s("pair.find", en), view -> startPairDiscovery(en));
            Button waitButton = compact(T.s("pair.wait", en), view -> startPairServe(en));
            Button connectButton = compact(T.s("pair.connect", en),
                view -> startPairDialFromText(remoteAddress.getText().toString(), en));
            holder.addView(discoverButton);
            holder.addView(waitButton);
            holder.addView(connectButton);

            new AlertDialog.Builder(host)
                .setTitle(T.s("pair.title", en))
                .setView(holder)
                .setNegativeButton(T.s("common.cancel", en), null)
                .setPositiveButton(en ? "Pair by fingerprint" : "用指纹配对", (dialog, which) -> {
                    String remoteValue = remote.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
                    if (!SyncPairing.validFingerprint(remoteValue) || remoteValue.equals(local)) {
                        host.toast(en ? "Enter the other device's fingerprint" : "请输入对端设备的指纹");
                        return;
                    }
                    String peerId = remoteSync.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
                    try {
                        byte[] nonce = SyncPairing.pairingNonce(local, remoteValue);
                        String code = SyncPairing.code(local, remoteValue, nonce);
                        String deviceId = "peer-" + remoteValue.substring(0, 16);
                        new AlertDialog.Builder(host)
                            .setTitle(en ? "Compare this code" : "核对确认码")
                            .setMessage((en ? "Code: " : "确认码：") + code + "\n"
                                + (T.s("pair.same_code", en)))
                            .setNegativeButton(T.s("common.cancel", en), null)
                            .setPositiveButton(T.s("pair.confirm", en), (inner, ignored) -> {
                                try {
                                    SyncPairing.confirm(host.storeRef(), local, remoteValue, deviceId, peerId, code, code);
                                    host.toast(T.s("pair.done", en));
                                    host.refreshAfterSync();
                                } catch (Exception error) {
                                    host.failWith(error);
                                }
                            }).show();
                    } catch (Exception error) {
                        host.failWith(error);
                    }
                })
                .show();
        } catch (Exception error) {
            host.failWith(error);
        }
    }

    void maybeAutoSync() {
        if (autoSyncAttempted || host.storeRef() == null) return;
        try {
            if (!host.storeRef().syncAutoEnabled() || !SyncTransport.available()) return;
            if (syncTarget() == null) return;
            autoSyncAttempted = true;
            startSyncDial(host.english());
        } catch (Throwable ignored) {
        }
    }

    private Button compact(String label, android.view.View.OnClickListener listener) {
        Button button = new Button(host);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, host.dpRef(40));
        params.setMargins(0, host.dpRef(4), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private String syncSeed() {
        return host.seedValue();
    }

    private String localSyncId() {
        if (!SyncTransport.available()) return "";
        try {
            String value = SyncTransport.nativePeerId(syncSeed());
            return value == null ? "" : value;
        } catch (Throwable error) {
            return "";
        }
    }

    private AgentStore.Pairing syncTarget() throws Exception {
        for (AgentStore.Pairing pairing : host.storeRef().activePairings())
            if (pairing.peerId != null && !pairing.peerId.isEmpty()) return pairing;
        return null;
    }

    private android.net.wifi.WifiManager.MulticastLock acquireMulticastLock() {
        try {
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager)
                host.getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
            if (wifi == null) return null;
            android.net.wifi.WifiManager.MulticastLock lock = wifi.createMulticastLock("traceform-mdns");
            lock.setReferenceCounted(false);
            lock.acquire();
            return lock;
        } catch (Throwable error) {
            return null;
        }
    }

    private void startPairDiscovery(boolean en) {
        if (!SyncTransport.available()) { host.toast(en ? "Sync runtime is not in this build" : "此构建不包含同步运行库"); return; }
        host.toast(en ? "Searching the local network…" : "正在搜索局域网…");
        final android.net.wifi.WifiManager.MulticastLock multicastLock = acquireMulticastLock();
        host.ioRef().execute(() -> {
            String payload;
            try {
                payload = SyncTransport.nativeDiscover(syncSeed(), 6000L);
            } catch (Throwable error) {
                payload = null;
            } finally {
                if (multicastLock != null) multicastLock.release();
            }
            final String json = payload;
            host.uiRef().post(() -> {
                if (host.isDestroyedRef()) return;
                java.util.List<String> labels = SyncActions.labels(json);
                java.util.List<String> addresses = SyncActions.addresses(json);
                if (labels.isEmpty()) {
                    host.toast(en ? "No device found on this network" : "局域网内没有发现设备");
                    return;
                }
                new AlertDialog.Builder(host)
                    .setTitle(en ? "Pick a device" : "选择设备")
                    .setItems(labels.toArray(new String[0]), (dialog, which) -> startPairDial(addresses.get(which), en))
                    .setNegativeButton(T.s("common.cancel", en), null)
                    .show();
            });
        });
    }

    private void startPairDialFromText(String text, boolean en) {
        String address = SyncActions.address(text);
        if (address == null) {
            host.toast(en ? "Enter host:port" : "请输入 主机:端口");
            return;
        }
        startPairDial(address, en);
    }

    private void startPairDial(String address, boolean en) {
        host.toast(en ? "Pairing…" : "正在配对…");
        host.ioRef().execute(() -> {
            String payload;
            try {
                payload = SyncTransport.nativePairDial(syncSeed(), address, DeviceIdentity.fingerprint(), 20000L);
            } catch (Throwable error) {
                payload = null;
            }
            final String json = payload;
            host.uiRef().post(() -> { if (!host.isDestroyedRef()) showPairResult(json, en); });
        });
    }

    private void startPairServe(boolean en) {
        host.toast(en ? "Waiting for the other device…" : "正在等待对方设备…");
        host.ioRef().execute(() -> {
            String payload;
            try {
                payload = SyncTransport.nativePairServe(syncSeed(), PAIR_PORT, DeviceIdentity.fingerprint(), 60000L);
            } catch (Throwable error) {
                payload = null;
            }
            final String json = payload;
            host.uiRef().post(() -> { if (!host.isDestroyedRef()) showPairResult(json, en); });
        });
    }

    private void showPairResult(String json, boolean en) {
        SyncActions.Session session = SyncActions.session(json);
        if (session == null) {
            String detail = SyncActions.error(json);
            host.toast((T.s("pair.failed", en)) + (detail.isEmpty() ? "" : ": " + detail));
            return;
        }
        final String sas = session.sas;
        final String remoteFingerprint = session.fingerprint;
        final String peerId = session.peerId;
        new AlertDialog.Builder(host)
            .setTitle(T.s("pair.compare", en))
            .setMessage((en ? "Code: " : "确认码：") + sas + "\n"
                + (T.s("pair.same_code", en)))
            .setNegativeButton(T.s("common.cancel", en), null)
            .setPositiveButton(T.s("pair.confirm", en), (dialog, which) -> {
                try {
                    SyncPairing.confirm(host.storeRef(), DeviceIdentity.fingerprint(), remoteFingerprint,
                        "peer-" + remoteFingerprint.substring(0, 16), peerId, sas, sas);
                    host.refreshAfterSync();
                    host.toast(T.s("pair.done", en));
                } catch (Exception error) { host.failWith(error); }
            })
            .show();
    }

    private void startSyncServe(boolean en) {
        if (!SyncTransport.available()) { host.toast(en ? "Sync runtime is not in this build" : "此构建不包含同步运行库"); return; }
        try {
            final AgentStore.Pairing target = syncTarget();
            if (target == null) { host.toast(en ? "Pair a device and add its sync ID first" : "请先配对并填写对端同步 ID"); return; }
            host.toast(en ? "Waiting for the peer…" : "正在等待对端…");
            host.ioRef().execute(() -> {
                String outcome;
                try {
                    String result = SyncTransport.nativeServeOnce(syncSeed(), SYNC_PORT, target.peerId,
                        request -> SyncRecords.handle(host.storeRef(), request));
                    outcome = result != null && result.startsWith("OK")
                        ? (en ? "Peer synced" : "对端已完成同步")
                        : (en ? "Wait failed: " + result : "等待失败：" + result);
                } catch (Throwable error) {
                    outcome = (en ? "Wait failed: " : "等待失败：") + error.getMessage();
                }
                final String message = outcome;
                host.uiRef().post(() -> { if (!host.isDestroyedRef()) host.toast(message); });
            });
        } catch (Exception error) {
            host.failWith(error);
        }
    }

    private void startSyncDial(boolean en) {
        if (!SyncTransport.available()) { host.toast(en ? "Sync runtime is not in this build" : "此构建不包含同步运行库"); return; }
        try {
            final AgentStore.Pairing target = syncTarget();
            if (target == null) { host.toast(en ? "Pair a device and add its sync ID first" : "请先配对并填写对端同步 ID"); return; }
            host.ioRef().execute(() -> {
                String outcome;
                try {
                    byte[] payload = SyncRecords.encode(host.storeRef().exportSyncRecords());
                    byte[] response = SyncTransport.nativeDialOnce(syncSeed(), SYNC_PORT, target.peerId, payload);
                    if (response == null) {
                        outcome = en ? "Sync failed: no response from the peer" : "同步失败：对端没有响应";
                    } else {
                        int applied = SyncRecords.merge(host.storeRef(), response);
                        outcome = en ? "Synced; " + applied + " change(s) applied" : "同步完成；应用 " + applied + " 条变更";
                    }
                } catch (Throwable error) {
                    outcome = (en ? "Sync failed: " : "同步失败：") + error.getMessage();
                }
                final String message = outcome;
                host.uiRef().post(() -> {
                    if (host.isDestroyedRef()) return;
                    host.toast(message);
                    host.refreshAfterSync();
                });
            });
        } catch (Exception error) {
            host.failWith(error);
        }
    }
}
