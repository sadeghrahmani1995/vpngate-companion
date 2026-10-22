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
 * Mirrors the Windows keeper.cjs: connect best-first, watch the engine state,
 * and jump to the next server on failure/timeout.
 *
 * v2.1 hardening:
 *  - ignores stale DISCONNECTED events right after starting a new attempt
 *    (they belonged to the previous server we just stopped)
 *  - "failingOver" guard prevents double-advance (two servers consumed at once)
 *  - stop fallback timers so pressing «قطع» can never leave the UI stuck
 *  - AUTH_FAILED / WAITING_FOR_USER_INPUT handled like upstream failovers
 *  - shorter watchdog (20s) so a full sweep cannot "hang" for 15+ minutes
 */
public final class VpnKeeper implements VpnStatus.StateListener, VpnStatus.LogListener {

    public interface Ui {
        void onPhase(String msg);
        void onEngineLog(String line);
        void onConnected(String server);
        void onIdle(String msg);
    }

    public static class Candidate {
        public String label;     // display name, e.g. "JP · vg-host (120ms)"
        public String config;    // decoded .ovpn text
        public boolean verified; // TCP probe succeeded before connecting
        public long latency = -1;
    }

    /** how long one connection attempt may take before we fail over */
    private static final long CONNECT_TIMEOUT = 20_000L;
    /** how long a RECONNECTING state of a previously-connected session may last */
    private static final long RECONNECT_GRACE = 25_000L;
    /** if stopVPN does not finish within this time, advance anyway */
    private static final long STOP_FALLBACK = 6_000L;
    /** engine events that arrive this early after an attempt started are leftovers */
    private static final long START_GRACE = 4_000L;
    /** absolute ceiling for requestStop: UI is never stuck longer than this */
    private static final long STOP_HARD_FALLBACK = 8_000L;

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
        final int gen = ++k.generation;
        k.disconnect(() -> k.h.post(() -> {
            k.teardown();
            release(k);
            if (done != null) done.run();
        }), gen, true);
        // absolute fallback — never leave the button stuck on «قطع»
        k.h.postDelayed(() -> {
            k.teardown();
            release(k);
            if (done != null) done.run();
        }, STOP_HARD_FALLBACK);
    }

    private static void release(VpnKeeper k) {
        synchronized (VpnKeeper.class) {
            if (active == k) active = null;
        }
    }

    private final Activity act;
    private final List<Candidate> cands;
    private final Ui ui;
    private final Handler h = new Handler(Looper.getMainLooper());

    private int idx = -1;
    private boolean stopping = false;
    private boolean connectedOnce = false;
    private boolean hadConnectedOnce = false; // برای این کاندید: آیا روزی وصل شد؟
    private int sameRetry = 0;                // چند بار به همان سرور تلاش مجدد شده
    private static final int MAX_SAME_RETRY = 2;
    private boolean failingOver = false;
    private boolean sawEngineState = false;
    private long attemptStart = 0L;
    private int generation = 0;
    private String currentLabel = "";

    private VpnKeeper(Activity act, List<Candidate> cands, Ui ui) {
        this.act = act;
        this.cands = cands;
        this.ui = ui;
    }

    private void teardown() {
        VpnStatus.removeStateListener(this);
        VpnStatus.removeLogListener(this);
    }

    // ---------- sequence ----------

    private void advance() {
        if (stopping) return;
        idx++;
        failingOver = false;
        sawEngineState = false;
        connectedOnce = false;
        hadConnectedOnce = false;
        sameRetry = 0;
        if (idx >= cands.size()) {
            teardown();
            ui.onIdle("all_failed");
            return;
        }
        final int gen = ++generation;
        attemptStart = System.currentTimeMillis();
        currentLabel = cands.get(idx).label;
        ui.onPhase(String.format(java.util.Locale.US, "%d/%d%s",
                idx + 1, cands.size(), cands.get(idx).verified ? "" : " (تست نشده)"));
        startAttempt(cands.get(idx), gen);
    }

    /** قطع‌شدن یک سروری که قبلاً وصل بود: اول ۲ بار به همان سرور، بعد سرور بعدی */
    private void reconnectSame() {
        if (stopping) return;
        sameRetry++;
        failingOver = false;
        sawEngineState = false;
        connectedOnce = false;
        final int gen = ++generation;
        attemptStart = System.currentTimeMillis();
        ui.onPhase("تلاش مجدد " + currentLabel);
        startAttempt(cands.get(idx), gen);
    }

    private void startAttempt(Candidate c, final int gen) {
        try {
            ConfigParser cp = new ConfigParser();
            cp.parseConfig(new StringReader(c.config));
            final VpnProfile p = cp.convertProfile();
            p.mName = "VPG " + c.label;

            ProfileManager.setTemporaryProfile(act.getApplicationContext(), p);
            VPNLaunchHelper.startOpenVpn(p, act.getApplicationContext(), "companion-auto", true);

            h.postDelayed(() -> {
                if (stopping || gen != generation || connectedOnce) return;
                engineLog("-- timeout (CONNECTED نشد در " + (CONNECT_TIMEOUT / 1000) + " ثانیه)");
                failover();
            }, CONNECT_TIMEOUT);
        } catch (Throwable t) {
            engineLog("-- parse/start error: " + t.getMessage());
            h.post(this::advance);
        }
    }

    private void failover() {
        if (stopping || failingOver) return;
        failingOver = true;
        final int gen = ++generation; // invalidate watchdog + grace timers
        disconnect(this::advance, gen, false);
        // if stopVPN never completes, move to the next candidate anyway
        h.postDelayed(() -> {
            if (!stopping && failingOver && generation == gen) {
                engineLog("-- stop fallback");
                advance();
            }
        }, STOP_FALLBACK);
    }

    // ---------- engine state ----------

    @Override
    public void updateState(String state, String logmessage, int localizedResId,
                            ConnectionStatus level, Intent intent) {
        h.post(() -> {
            if (stopping || failingOver) return; // ignore engine noise during transitions
            final long elapsed = System.currentTimeMillis() - attemptStart;

            switch (state) {
                case "CONNECTING":
                case "TCP_CONNECT":
                case "RESOLVE":
                case "WAIT":
                case "AUTH":
                case "GET_CONFIG":
                case "ASSIGN_IP":
                case "ADD_ROUTES":
                    sawEngineState = true;
                    break;
                default:
                    break;
            }

            if (level == ConnectionStatus.LEVEL_CONNECTED) {
                connectedOnce = true;
                hadConnectedOnce = true;
                sameRetry = 0; // اتصال موفق: شمارندهٔ تلاش مجدد صفر شود
                generation++; // cancel watchdog / grace timers
                ui.onConnected(currentLabel);
            } else if (level == ConnectionStatus.LEVEL_AUTH_FAILED
                    || level == ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT) {
                engineLog("-- " + state);
                failover();
            } else if ("DISCONNECTED".equals(state) || "EXITING".equals(state)) {
                // a DISCONNECTED arriving instantly after we started a new attempt
                // is the farewell of the previous server — ignore it
                if (!sawEngineState && elapsed < START_GRACE) return;
                // سرور خوبی داشتیم و پروسه کشته/قطع شد؟ اول به همان سرور تلاش کن
                if (hadConnectedOnce && sameRetry < MAX_SAME_RETRY) {
                    engineLog("-- قطع شد؛ تلاش دوباره به همان سرور (" + (sameRetry + 1) + "/" + MAX_SAME_RETRY + ")");
                    reconnectSame();
                    return;
                }
                engineLog("-- " + state);
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

    /**
     * @param done       runs only if this stop-request is still current (generation unchanged)
     * @param alwaysStop stop the engine even if the generation moved on (user-pressed stop)
     */
    private void disconnect(final Runnable done, final int gen, final boolean alwaysStop) {
        try {
            Intent si = new Intent(act, OpenVPNService.class);
            si.setAction(OpenVPNService.START_SERVICE);
            ServiceConnection sc = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    boolean current = alwaysStop || generation == gen;
                    if (current) {
                        try {
                            IOpenVPNServiceInternal svc =
                                    IOpenVPNServiceInternal.Stub.asInterface(service);
                            if (svc != null) svc.stopVPN(false);
                        } catch (Throwable ignored) {
                        }
                    }
                    try {
                        act.unbindService(this);
                    } catch (Throwable ignored) {
                    }
                    if (done != null && current) done.run();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                }
            };
            boolean ok = act.bindService(si, sc, Context.BIND_AUTO_CREATE);
            if (!ok) {
                act.unbindService(sc);
                if (done != null && (alwaysStop || generation == gen)) done.run();
            }
        } catch (Throwable t) {
            engineLog("-- stop error: " + t.getMessage());
            if (done != null && (alwaysStop || generation == gen)) done.run();
        }
    }

    private void engineLog(String s) {
        ui.onEngineLog(s);
    }
}
