package com.inceptless.traceweave;

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
        syncState.setText((T.s("sync.sync", en)) + (SyncTransport.available()
            ? T.format("sync.paired_devices", en, pairedNow)
            : (T.s("sync.runtime_unavailable_in_this_build", en)))
            + (mySyncId.isEmpty() ? "" : (T.s("sync.this_device", en)) + mySyncId.substring(0, Math.min(8, mySyncId.length()))));
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
        Button intervalButton = compact(T.format("sync.interval_button", en, intervalLabel(host.storeRef().syncAutoIntervalMs())), view -> {
            long[] options = {15 * 60 * 1000L, 60 * 60 * 1000L, 6 * 60 * 60 * 1000L};
            long current = host.storeRef().syncAutoIntervalMs();
            int next = 0;
            for (int index = 0; index < options.length; index++) if (options[index] == current) next = (index + 1) % options.length;
            host.storeRef().setSyncAutoIntervalMs(options[next]);
            AutoSyncScheduler.apply(host, host.storeRef().syncAutoEnabled(), options[next]);
            ((Button) view).setText(T.format("sync.interval_button", en, intervalLabel(options[next])));
        });
        TextView lastAuto = new TextView(host);
        String lastEvent = host.storeRef().lastAutoSyncEvent();
        lastAuto.setText(lastEvent.isEmpty()
            ? T.s("sync.auto_never", en)
            : T.format("sync.auto_last", en, lastEvent.substring(0, lastEvent.indexOf('\t')).replace("sync_auto_", "")));
        lastAuto.setTextSize(11);
        lastAuto.setTextColor(Ui.muted(host));
        lastAuto.setPadding(0, host.dpRef(4), 0, 0);
        syncRow.addView(lastAuto);

        autoSync.setText(T.s("sync.auto", en));
        autoSync.setTextSize(11);
        autoSync.setTextColor(0xFF64748B);
        autoSync.setChecked(host.storeRef().syncAutoEnabled());
        autoSync.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                AlertDialog enableDialog = new AlertDialog.Builder(host)
                    .setView(Ui.dialogBody(host, T.s("sync.enable_title", en), T.s("sync.enable_message", en), 13))
                    .setNegativeButton(T.s("common.cancel", en), (dialog, which) -> button.setChecked(false))
                    .setPositiveButton(T.s("common.enable", en), (dialog, which) -> {
                        host.storeRef().setSyncAutoEnabled(true);
                        AutoSyncScheduler.apply(host, true, AutoSyncScheduler.DEFAULT_INTERVAL_MS);
                    })
                    .setOnCancelListener(dialog -> button.setChecked(false))
                    .create();
                enableDialog.show();
                Ui.themeDialog(enableDialog, host);
            } else {
                host.storeRef().setSyncAutoEnabled(false);
                AutoSyncScheduler.apply(host, false, AutoSyncScheduler.DEFAULT_INTERVAL_MS);
            }
        });
        syncRow.addView(autoSync);
        LinearLayout.LayoutParams intervalLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, host.dpRef(40));
        intervalLp.setMargins(host.dpRef(6), 0, 0, 0);
        syncRow.addView(intervalButton, intervalLp);
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
            localView.setText((T.s("sync.this_device_2", en)) + local);
            localView.setTextSize(11);
            TextView syncView = new TextView(host);
            String localSyncId = localSyncId();
            syncView.setText((T.s("sync.sync_id", en)) + (localSyncId.isEmpty() ? "—" : localSyncId));
            syncView.setTextSize(11);
            EditText remoteSync = new EditText(host);
            remoteSync.setHint(T.s("pair.sync_hint", en));
            EditText remoteAddress = new EditText(host);
            remoteAddress.setHint(T.s("pair.address_hint", en));

            LinearLayout holder = new LinearLayout(host);
            holder.setOrientation(LinearLayout.VERTICAL);
            holder.setPadding(host.dpRef(24), host.dpRef(8), host.dpRef(24), 0);
            TextView heading = Ui.title(host, T.s("pair.title", en));
            heading.setTextSize(14);
            holder.addView(heading);
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

            AlertDialog pairDialog = new AlertDialog.Builder(host)
                .setView(holder)
                .setNegativeButton(T.s("common.cancel", en), null)
                .setPositiveButton(T.s("sync.pair_by_fingerprint", en), (dialog, which) -> {
                    String remoteValue = remote.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
                    if (!SyncPairing.validFingerprint(remoteValue) || remoteValue.equals(local)) {
                        host.toast(T.s("sync.enter_the_other_device_s_fingerprint", en));
                        return;
                    }
                    String peerId = remoteSync.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
                    try {
                        byte[] nonce = SyncPairing.pairingNonce(local, remoteValue);
                        String code = SyncPairing.code(local, remoteValue, nonce);
                        String deviceId = "peer-" + remoteValue.substring(0, 16);
                        android.widget.EditText typed = Ui.input(host, T.s("pair.type_code", en));
                        typed.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                        LinearLayout compareBody = new LinearLayout(host);
                        compareBody.setOrientation(LinearLayout.VERTICAL);
                        compareBody.setPadding(host.dpRef(24), host.dpRef(8), host.dpRef(24), 0);
                        compareBody.addView(Ui.dialogBody(host, T.s("sync.compare_this_code", en),
                            T.s("sync.code", en) + code + "\n" + T.s("pair.same_code", en), 13));
                        compareBody.addView(typed);
                        AlertDialog compareDialog = new AlertDialog.Builder(host)
                            .setView(compareBody)
                            .setNegativeButton(T.s("common.cancel", en), null)
                            .setPositiveButton(T.s("pair.confirm", en), (inner, ignored) -> {
                                char[] typedCode = typed.getText().toString().toCharArray();
                                typed.setText("");
                                try {
                                    SyncPairing.confirm(host.storeRef(), local, remoteValue, deviceId, peerId,
                                        code, new String(typedCode).trim());
                                    java.util.Arrays.fill(typedCode, '\0');
                                    host.toast(T.s("pair.done", en));
                                    host.refreshAfterSync();
                                } catch (Exception error) {
                                    java.util.Arrays.fill(typedCode, '\0');
                                    host.failWith(error);
                                }
                            }).create();
                        compareDialog.show();
                        Ui.themeDialog(compareDialog, host);
                    } catch (Exception error) {
                        host.failWith(error);
                    }
                })
                .create();
            pairDialog.show();
            Ui.themeDialog(pairDialog, host);
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
            android.net.wifi.WifiManager.MulticastLock lock = wifi.createMulticastLock("traceweave-mdns");
            lock.setReferenceCounted(false);
            lock.acquire();
            return lock;
        } catch (Throwable error) {
            return null;
        }
    }

    private void startPairDiscovery(boolean en) {
        if (!SyncTransport.available()) { host.toast(T.s("sync.sync_runtime_is_not_in_this_build", en)); return; }
        host.toast(T.s("sync.searching_the_local_network", en));
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
                    host.toast(T.s("sync.no_device_found_on_this_network", en));
                    return;
                }
                new AlertDialog.Builder(host)
                    .setTitle(T.s("sync.pick_a_device", en))
                    .setItems(labels.toArray(new String[0]), (dialog, which) -> startPairDial(addresses.get(which), en))
                    .setNegativeButton(T.s("common.cancel", en), null)
                    .show();
            });
        });
    }

    private void startPairDialFromText(String text, boolean en) {
        String address = SyncActions.address(text);
        if (address == null) {
            host.toast(T.s("sync.enter_host_port", en));
            return;
        }
        startPairDial(address, en);
    }

    private void startPairDial(String address, boolean en) {
        host.toast(T.s("sync.pairing", en));
        android.util.Log.i("AgentOsPairing", "pair_dial start address=" + address);
        host.ioRef().execute(() -> {
            String payload;
            java.util.concurrent.ExecutorService call = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<String> pending = call.submit(
                    () -> SyncTransport.nativePairDial(syncSeed(), address, DeviceIdentity.fingerprint(), 20000L));
                payload = pending.get(25, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                payload = null;
                android.util.Log.w("AgentOsPairing", "pair_dial timed out after 25s");
            } catch (Throwable error) {
                payload = null;
                android.util.Log.w("AgentOsPairing", "pair_dial threw " + error);
            } finally {
                call.shutdownNow();
            }
            android.util.Log.i("AgentOsPairing", "pair_dial finished ok=" + (payload != null));
            final String json = payload;
            host.uiRef().post(() -> {
                android.util.Log.i("AgentOsPairing", "pair_dial show destroyed=" + host.isDestroyedRef());
                if (!host.isDestroyedRef()) showPairResult(json, en, address);
            });
        });
    }

    private void startPairServe(boolean en) {
        host.toast(T.s("sync.waiting_for_the_other_device", en));
        host.ioRef().execute(() -> {
            String payload;
            try {
                payload = SyncTransport.nativePairServe(syncSeed(), PAIR_PORT, DeviceIdentity.fingerprint(), pairWindowMs());
            } catch (Throwable error) {
                payload = null;
            }
            final String json = payload;
            host.uiRef().post(() -> { if (!host.isDestroyedRef()) showPairResult(json, en, ""); });
        });
    }

    /** Validation builds keep the pairing server open long enough for scripted checks. */
    private long pairWindowMs() {
        return host.getPackageName().endsWith("validation") ? 300_000L : 60_000L;
    }

    private String intervalLabel(long intervalMs) {
        long minutes = Math.max(1, intervalMs / 60_000L);
        if (minutes < 60) return minutes + " min";
        return (minutes / 60) + " h";
    }

    /** Remembers where a peer was reachable so the background job can dial it later. */
    private void rememberPeerAddress(String deviceId, String pairAddress) {
        try {
            String multiaddr = pairAddress.startsWith("/") ? pairAddress : SyncActions.address(pairAddress);
            if (multiaddr == null || !multiaddr.contains("/ip4/")) return;
            String target = multiaddr.substring(0, multiaddr.lastIndexOf("/tcp/")) + "/tcp/" + SYNC_PORT;
            host.storeRef().recordSyncCursor(deviceId, System.currentTimeMillis(), target, SYNC_PORT, "paired");
        } catch (Exception ignored) {
        }
    }

    private void showPairResult(String json, boolean en, String address) {
        SyncActions.Session session = SyncActions.session(json);
        android.util.Log.i("AgentOsPairing", "show_pair_result ok=" + (session != null));
        if (session == null) {
            String detail = SyncActions.error(json);
            host.toast((T.s("pair.failed", en)) + (detail.isEmpty() ? "" : ": " + detail));
            return;
        }
        final String sas = session.sas;
        final String remoteFingerprint = session.fingerprint;
        final String peerId = session.peerId;
        LinearLayout body = new LinearLayout(host);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(host.dpRef(16), host.dpRef(8), host.dpRef(16), 0);
        TextView heading = Ui.title(host, T.s("pair.compare", en));
        heading.setTextSize(14);
        body.addView(heading);
        TextView message = new TextView(host);
        message.setText(T.s("sync.code", en) + sas + "\n" + T.s("pair.same_code", en));
        message.setTextSize(12);
        message.setTextColor(Ui.text(host));
        message.setPadding(0, host.dpRef(4), 0, 0);
        body.addView(message);
        android.widget.EditText typed = Ui.input(host, T.s("pair.type_code", en));
        typed.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        body.addView(typed);
        AlertDialog result = new AlertDialog.Builder(host)
            .setView(body)
            .setNegativeButton(T.s("common.cancel", en), null)
            .setPositiveButton(T.s("pair.confirm", en), (dialog, which) -> {
                char[] typedCode = typed.getText().toString().toCharArray();
                typed.setText("");
                try {
                    String deviceId = "peer-" + remoteFingerprint.substring(0, 16);
                    SyncPairing.confirm(host.storeRef(), DeviceIdentity.fingerprint(), remoteFingerprint,
                        deviceId, peerId, sas, new String(typedCode).trim());
                    java.util.Arrays.fill(typedCode, '\0');
                    rememberPeerAddress(deviceId, address);
                    host.refreshAfterSync();
                    host.toast(T.s("pair.done", en));
                } catch (Exception error) {
                    java.util.Arrays.fill(typedCode, '\0');
                    host.failWith(error);
                }
            })
            .create();
        result.show();
        Ui.themeDialog(result, host);
    }

    private void startSyncServe(boolean en) {
        if (!SyncTransport.available()) { host.toast(T.s("sync.sync_runtime_is_not_in_this_build", en)); return; }
        try {
            final AgentStore.Pairing target = syncTarget();
            if (target == null) { host.toast(T.s("sync.pair_a_device_and_add_its_sync_id_firs", en)); return; }
            host.toast(T.s("sync.waiting_for_the_peer", en));
            host.ioRef().execute(() -> {
                String outcome;
                try {
                    String result = SyncTransport.nativeServeOnce(syncSeed(), SYNC_PORT, target.peerId,
                        request -> SyncRecords.handle(host.storeRef(), request));
                    outcome = result != null && result.startsWith("OK")
                        ? (T.s("sync.peer_synced", en))
                        : T.format("sync.wait_failed", en, result);
                } catch (Throwable error) {
                    outcome = (T.s("sync.wait_failed", en)) + error.getMessage();
                }
                final String message = outcome;
                host.uiRef().post(() -> { if (!host.isDestroyedRef()) host.toast(message); });
            });
        } catch (Exception error) {
            host.failWith(error);
        }
    }

    private void startSyncDial(boolean en) {
        if (!SyncTransport.available()) { host.toast(T.s("sync.sync_runtime_is_not_in_this_build", en)); return; }
        try {
            final AgentStore.Pairing target = syncTarget();
            if (target == null) { host.toast(T.s("sync.pair_a_device_and_add_its_sync_id_firs", en)); return; }
            host.ioRef().execute(() -> {
                String outcome;
                try {
                    byte[] payload = SyncRecords.encode(host.storeRef().exportSyncRecords());
                    byte[] response = SyncTransport.nativeDialOnce(syncSeed(), SYNC_PORT, target.peerId, payload);
                    if (response == null) {
                        outcome = T.s("sync.sync_failed_no_response_from_the_peer", en);
                    } else {
                        int applied = SyncRecords.merge(host.storeRef(), response);
                        host.storeRef().recordSyncCursor(target.deviceId, System.currentTimeMillis(),
                            "127.0.0.1", SYNC_PORT, "ok:" + applied);
                        outcome = T.format("sync.changes_applied", en, applied);
                    }
                } catch (Throwable error) {
                    outcome = (T.s("sync.sync_failed", en)) + error.getMessage();
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
