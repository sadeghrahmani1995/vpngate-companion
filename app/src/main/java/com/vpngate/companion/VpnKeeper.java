package com.vpngate.companion;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import java.io.StringReader;
import java.util.List;

import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ConfigParser;
import de.blinkt.openvpn.core.ConnectionStatus;
import de.blinkt.openvpn.core.IOpenVPNServiceInternal;
import de.blinkt.openvpn.core.OpenVPNService;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VPNLaunchHelper;
import de.blinkt.openvpn.core.VpnStatus;

/**
 * VpnKeeper — automatic connect + failover driver for the embedded ics-openvpn engine.
 * Mirrors the Windows keeper.cjs: probe candidates, connect best-first, watch the
 * engine state, and jump to the next server on failure/timeout.
 */
public final class VpnKeeper implements VpnStatus.StateListener, VpnStatus.LogListener {

    public interface Ui {
        void onPhase(String msg);
        void onEngineLog(String line);
        void onConnected(String server);
        void onIdle(String msg);
    }

    public static class Candidate {
        public String label;   // display name, e.g. "JP · vg-host"
        public String config;  // decoded .ovpn text
    }

    private static final long CONNECT_TIMEOUT = 30_000L;
    private static final long RECONNECT_GRACE = 45_000L;

    private static VpnKeeper active;

    public static synchronized boolean isActive() {
        return active != null;
    }

    public static synchronized void begin(Activity act, List<Candidate> cands, Ui ui) {
        if (active != null || cands == null || cands.isEmpty()) return;
        active = new VpnKeeper(act, cands, ui);
        VpnStatus.addStateListener(active);
        VpnStatus.addLogListener(active);
        active.h.post(active::advance);
    }

    public static synchronized void requestStop(final Runnable done) {
        final VpnKeeper k = active;
        if (k == null) {
            if (done != null) done.run();
            return;
        }
        k.stopping = true;
        k.h.removeCallbacksAndMessages(null);
        k.disconnect(() -> k.h.post(() -> {
            VpnStatus.removeStateListener(k);
            VpnStatus.removeLogListener(k);
            active = null;
            if (done != null) done.run();
        }));
    }

    private final Activity act;
    private final List<Candidate> cands;
    private final Ui ui;
    private final Handler h = new Handler(Looper.getMainLooper());

    private int idx = -1;
    private boolean stopping = false;
    private boolean connectedOnce = false;
    private int generation = 0;
    private String currentLabel = "";

    private VpnKeeper(Activity act, List<Candidate> cands, Ui ui) {
        this.act = act;
        this.cands = cands;
        this.ui = ui;
    }

    // ---------- sequence ----------

    private void advance() {
        if (stopping) return;
        idx++;
        if (idx >= cands.size()) {
            cleanup();
            ui.onIdle("all_failed");
            return;
        }
        final int gen = ++generation;
        connectedOnce = false;
        Candidate c = cands.get(idx);
        currentLabel = c.label;
        ui.onPhase(String.format(java.util.Locale.US, "%d/%d", idx + 1, cands.size()));

        try {
            ConfigParser cp = new ConfigParser();
            cp.parseConfig(new StringReader(c.config));
            final VpnProfile p = cp.convertProfile();
            p.mName = "VPG " + c.label;

            ProfileManager.setTemporaryProfile(act.getApplicationContext(), p);
            VPNLaunchHelper.startOpenVpn(p, act.getApplicationContext(), "companion-auto", true);

            h.postDelayed(() -> {
                if (stopping || gen != generation || connectedOnce) return;
                engineLog("-- timeout");
                failover();
            }, CONNECT_TIMEOUT);
        } catch (Throwable t) {
            engineLog("-- parse/start error: " + t.getMessage());
            h.post(this::advance);
        }
    }

    private void failover() {
        if (stopping) return;
        generation++;
        disconnect(this::advance);
    }

    // ---------- engine state ----------

    @Override
    public void updateState(String state, String logmessage, int localizedResId,
                            ConnectionStatus level, Intent intent) {
        h.post(() -> {
            if (stopping) return;
            if (level == ConnectionStatus.LEVEL_CONNECTED) {
                connectedOnce = true;
                generation++; // cancel watchdog
                ui.onConnected(currentLabel);
            } else if (level == ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT) {
                // VPN Gate profiles need no auth; stuck here means a bad profile
                engineLog("-- waiting for user input (bad profile)");
                failover();
            } else if ("DISCONNECTED".equals(state)) {
                engineLog("-- disconnected");
                failover();
            } else if ("RECONNECTING".equals(state)) {
                if (!connectedOnce) return; // watchdog still armed
                final int gen = ++generation;
                h.postDelayed(() -> {
                    if (stopping || gen != generation) return;
                    engineLog("-- reconnecting too long");
                    failover();
                }, RECONNECT_GRACE);
            }
        });
    }

    @Override
    public void setConnectedVPN(String uuid) {
    }

    @Override
    public void newLog(de.blinkt.openvpn.core.LogItem logItem) {
        try {
            final String s = logItem.getString(act.getApplicationContext());
            h.post(() -> ui.onEngineLog(s));
        } catch (Throwable ignored) {
        }
    }

    // ---------- low level ----------

    private void disconnect(final Runnable done) {
        try {
            Intent si = new Intent(act, OpenVPNService.class);
            si.setAction(OpenVPNService.START_SERVICE);
            ServiceConnection sc = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    try {
                        IOpenVPNServiceInternal svc = IOpenVPNServiceInternal.Stub.asInterface(service);
                        if (svc != null) svc.stopVPN(false);
                    } catch (RemoteException ignored) {
                    }
                    try {
                        act.unbindService(this);
                    } catch (Throwable ignored) {
                    }
                    if (done != null) done.run();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                }
            };
            boolean ok = act.bindService(si, sc, Context.BIND_AUTO_CREATE);
            if (!ok && done != null) done.run();
        } catch (Throwable t) {
            engineLog("-- stop error: " + t.getMessage());
            if (done != null) done.run();
        }
    }

    private void cleanup() {
        VpnStatus.removeStateListener(this);
        VpnStatus.removeLogListener(this);
    }

    private void engineLog(String s) {
        ui.onEngineLog(s);
    }
}
