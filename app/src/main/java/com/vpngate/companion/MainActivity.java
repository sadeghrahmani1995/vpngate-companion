package com.vpngate.companion;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.blinkt.openvpn.R;

public class MainActivity extends Activity implements VpnKeeper.Ui {

    private static final int REQ_IMPORT = 41;
    private static final int REQ_VPN = 47;
    private static final int REQ_NOTIF = 48;
    private static final int PROBE_THREADS = 16;
    private static final int PROBE_POOL_CAP = 250;  // حداکثر سرور برای تست TCP (قبلاً 40 بود)
    private static final int AUTO_QUEUE = 30;       // حداکثر کاندید در صف failover
    private static final int PROBE_TIMEOUT_MS = 2500;

    private static final Pattern REMOTE_RE =
            Pattern.compile("(?im)^\\s*remote\\s+([^\\s]+)\\s+(\\d+)");
    private static final Pattern PROTO_RE =
            Pattern.compile("(?im)^\\s*proto\\s+(tcp|udp)");

    private static final String[] DEFAULT_SOURCES = {
            "https://www.vpngate.net/api/iphone/",
            "http://www.vpngate.net/api/iphone/",
            "https://raw.githubusercontent.com/GeorgeXie2333/vpngate-list-mirror/main/data/vpngate.csv",
            "https://cdn.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@main/data/vpngate.csv"
    };

    private TextView statusView, logView;
    private ProgressBar progress;
    private Spinner countrySpin;
    private ListView listView;
    private TextView autoBtn;

    private final List<Server> all = new ArrayList<>();
    private final List<Server> shown = new ArrayList<>();
    private ServerAdapter adapter;
    private String countryFilter = "";
    private boolean fetching = false;
    private volatile boolean probing = false;
    private volatile boolean probeCancel = false;
    private Runnable pendingAfterVpnGrant;

    private static class Server {
        String host, ip, countryLong, countryShort, b64;
        long score, speed, ping;
        int sessions;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.statusView);
        logView = findViewById(R.id.logView);
        progress = findViewById(R.id.progress);
        countrySpin = findViewById(R.id.countrySpin);
        listView = findViewById(R.id.listView);
        autoBtn = findViewById(R.id.autoBtn);

        adapter = new ServerAdapter();
        listView.setAdapter(adapter);

        findViewById(R.id.fetchBtn).setOnClickListener(v -> fetchList());
        findViewById(R.id.importBtn).setOnClickListener(v -> pickFile());
        autoBtn.setOnClickListener(v -> {
            if (VpnKeeper.isActive()) {
                VpnKeeper.requestStop(() -> runOnUiThread(() -> {
                    setAutoBtn(false);
                    statusView.setText(R.string.vpg_stopped);
                }));
            } else if (probing) {
                probeCancel = true; // لغوی تست‌ها؛ دکمه بعداً به حالت عادی برمی‌گردد
            } else {
                startAuto();
            }
        });

        listView.setOnItemClickListener((parent, view, pos, id) -> {
            if (pos >= 0 && pos < shown.size()) connectSingle(shown.get(pos));
        });
        listView.setOnItemLongClickListener((parent, view, pos, id) -> {
            if (pos >= 0 && pos < shown.size()) shareConfig(shown.get(pos));
            return true;
        });
    }

    // ---------- اتصال ----------

    private void setAutoBtn(boolean running) {
        autoBtn.setText(running ? R.string.vpg_disconnect : R.string.vpg_auto_connect);
    }

    /**
     * v2.1: کل استخر (تا ۲۵۰ سرور، با احترام به فیلتر کشور) تست می‌شود — نه فقط ۴۰ سرورِ برتر.
     * فقط کانفیگ‌های TCP به‌طور معناداری قابل تست‌اند؛ کانفیگ‌های UDP «تست‌نشده» ولی آخر صف
     * می‌روند (در ایران OpenVPN/UDP تقریباً همیشه مسدود است). تست با IP به‌جای hostname انجام
     * می‌شود تا DNS-hang رخ ندهد و در هر لحظه قابل لغو است.
     */
    private List<VpnKeeper.Candidate> probeServers(List<Server> servers) {
        List<Server> pool = new ArrayList<>(servers);
        if (pool.size() > PROBE_POOL_CAP) pool = pool.subList(0, PROBE_POOL_CAP);

        List<Probe> results = Collections.synchronizedList(new ArrayList<>());
        ExecutorService ex = Executors.newFixedThreadPool(PROBE_THREADS);
        AtomicInteger done = new AtomicInteger();
        int total = pool.size();

        for (Server s : pool) {
            ex.execute(() -> {
                Remote r = probeCancel ? null : parseRemote(s);
                long lat = -1;
                if (r != null && r.isTcp) lat = tcpProbe(r.host, r.port, PROBE_TIMEOUT_MS);
                results.add(new Probe(s, r == null ? "?" : r.host + ":" + r.port, lat,
                        r != null && r.isTcp));
                int d = done.incrementAndGet();
                runOnUiThread(() -> statusView.setText(getString(R.string.vpg_probing, d, total)));
            });
        }
        ex.shutdown();
        try {
            while (!ex.awaitTermination(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (probeCancel) {
                    ex.shutdownNow();
                    break;
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            ex.shutdownNow();
        }

        List<Probe> tcpAlive = new ArrayList<>();
        List<Probe> udpOnly = new ArrayList<>();
        for (Probe p : results) {
            if (p.isTcp && p.latency >= 0) tcpAlive.add(p);
            else if (!p.isTcp) udpOnly.add(p);
        }
        Collections.sort(tcpAlive, (a, b) -> Long.compare(a.latency, b.latency));

        List<VpnKeeper.Candidate> out = new ArrayList<>();
        for (Probe p : tcpAlive) {
            VpnKeeper.Candidate c = buildCandidate(p.s, p.latency, true);
            if (c != null) out.add(c);
            if (out.size() >= AUTO_QUEUE) return out;
        }
        for (Probe p : udpOnly) { // آخر صف: تأییدنشده
            VpnKeeper.Candidate c = buildCandidate(p.s, -1, false);
            if (c != null) out.add(c);
            if (out.size() >= AUTO_QUEUE) return out;
        }
        return out;
    }

    private VpnKeeper.Candidate buildCandidate(Server s, long latency, boolean verified) {
        try {
            String cfg = new String(Base64.decode(s.b64, Base64.DEFAULT), "UTF-8");
            VpnKeeper.Candidate c = new VpnKeeper.Candidate();
            c.label = s.countryShort + " · " + s.host
                    + (latency >= 0 ? " (" + latency + "ms)" : "");
            c.config = cfg;
            c.verified = verified;
            c.latency = latency;
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private static class Probe {
        final Server s;
        final String ep;
        final long latency;
        final boolean isTcp;

        Probe(Server s, String ep, long latency, boolean isTcp) {
            this.s = s;
            this.ep = ep;
            this.latency = latency;
            this.isTcp = isTcp;
        }
    }

    private static class Remote {
        String host;
        int port;
        boolean isTcp;
    }

    private static Remote parseRemote(Server s) {
        try {
            String cfg = new String(Base64.decode(s.b64, Base64.DEFAULT), "UTF-8");
            boolean isTcp = false;
            Matcher pm = PROTO_RE.matcher(cfg);
            if (pm.find()) isTcp = "tcp".equalsIgnoreCase(pm.group(1));
            Matcher m = REMOTE_RE.matcher(cfg);
            if (m.find()) {
                Remote r = new Remote();
                // تست با IP از CSV — بدون DNS تا هرگز گیر نکند
                r.host = (s.ip != null && !s.ip.trim().isEmpty()) ? s.ip.trim() : m.group(1);
                r.port = Integer.parseInt(m.group(2));
                r.isTcp = isTcp;
                return r;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static long tcpProbe(String host, int port, int timeoutMs) {
        long t0 = System.currentTimeMillis();
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(host, port), timeoutMs);
            return System.currentTimeMillis() - t0;
        } catch (Exception e) {
            return -1;
        }
    }

    private void ensureVpnPermissionAnd(Runnable action) {
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            pendingAfterVpnGrant = action;
            startActivityForResult(prep, REQ_VPN);
            return;
        }
        action.run();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    private void startAuto() {
        if (probing || VpnKeeper.isActive()) return;
        if (all.isEmpty()) {
            fetchList();
            return;
        }
        requestNotificationPermissionIfNeeded();
        // اول اجازهٔ VPN — تا دیالوگ وسط تست ظاهر نشود
        ensureVpnPermissionAnd(() -> runOnUiThread(this::runAutoProbe));
    }

    private void runAutoProbe() {
        setAutoBtn(true); // حالا دکمه نقش توقف/لغو دارد
        progress.setVisibility(View.VISIBLE);
        probing = true;
        probeCancel = false;
        statusView.setText(R.string.vpg_probing_start);
        final List<Server> pool = countryFilter.isEmpty() ? all : new ArrayList<>(shown);
        new Thread(() -> {
            List<VpnKeeper.Candidate> cands = probeServers(pool);
            probing = false;
            runOnUiThread(() -> progress.setVisibility(View.GONE));
            if (probeCancel) {
                runOnUiThread(() -> {
                    setAutoBtn(false);
                    statusView.setText(R.string.vpg_stopped);
                });
                return;
            }
            if (cands.isEmpty()) {
                runOnUiThread(() -> {
                    setAutoBtn(false);
                    statusView.setText(R.string.vpg_no_alive);
                });
                return;
            }
            runOnUiThread(() -> VpnKeeper.begin(MainActivity.this, cands, MainActivity.this));
        }, "prober").start();
    }

    private void connectSingle(Server s) {
        if (probing || VpnKeeper.isActive()) return;
        requestNotificationPermissionIfNeeded();
        statusView.setText(getString(R.string.vpg_testing_one, s.countryShort + " · " + s.host));
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            Remote r = parseRemote(s);
            long lat = -1;
            if (r != null && r.isTcp) lat = tcpProbe(r.host, r.port, PROBE_TIMEOUT_MS);
            final long fLat = lat;
            // کاندید همیشه ساخته می‌شود — حتی اگر تست TCP رد شد (موتور خودش گزارش می‌دهد)
            final VpnKeeper.Candidate c = buildCandidate(s, fLat, r != null && r.isTcp && fLat >= 0);
            runOnUiThread(() -> progress.setVisibility(View.GONE));
            runOnUiThread(() -> ensureVpnPermissionAnd(() -> runOnUiThread(() -> {
                setAutoBtn(true);
                VpnKeeper.begin(this, Collections.singletonList(c), this);
            })));
        }, "prober1").start();
    }

    // ---------- VpnKeeper.Ui ----------

    @Override
    public void onPhase(String msg) {
        statusView.setText(getString(R.string.vpg_connecting_phase, msg));
    }

    @Override
    public void onEngineLog(String line) {
        logView.append(line + "\n");
        if (logView.getLineCount() > 200) logView.setText("");
    }

    @Override
    public void onConnected(String server) {
        statusView.setText(getString(R.string.vpg_connected, server));
    }

    @Override
    public void onIdle(String code) {
        setAutoBtn(false);
        if ("all_failed".equals(code)) {
            statusView.setText(R.string.vpg_all_failed);
        } else {
            statusView.setText(R.string.vpg_stopped);
        }
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            Runnable r = pendingAfterVpnGrant;
            pendingAfterVpnGrant = null;
            if (resultCode == RESULT_OK && r != null) r.run();
            else statusView.setText(R.string.vpg_vpn_denied);
        } else if (requestCode == REQ_IMPORT && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            try {
                InputStream in = getContentResolver().openInputStream(data.getData());
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                final String cfg = bo.toString("UTF-8");
                final String label = "فایل واردشده";
                statusView.setText(getString(R.string.vpg_testing_one, label));
                progress.setVisibility(View.VISIBLE);
                new Thread(() -> {
                    long lat = -1;
                    Matcher m = REMOTE_RE.matcher(cfg);
                    if (m.find()) lat = tcpProbe(m.group(1), Integer.parseInt(m.group(2)), PROBE_TIMEOUT_MS);
                    final long fLat = lat;
                    runOnUiThread(() -> progress.setVisibility(View.GONE));
                    VpnKeeper.Candidate c = new VpnKeeper.Candidate();
                    c.label = label;
                    c.config = cfg;
                    runOnUiThread(() -> ensureVpnPermissionAnd(() -> runOnUiThread(() -> {
                        setAutoBtn(true);
                        VpnKeeper.begin(this, Collections.singletonList(c), this);
                    })));
                }, "import-connect").start();
            } catch (Exception e) {
                statusView.setText("خطا: " + e.getMessage());
            }
        }
    }

    // ---------- اشتراک‌گذاری کانفیگ (fallback) ----------

    private void shareConfig(Server s) {
        try {
            byte[] data = Base64.decode(s.b64, Base64.DEFAULT);
            String safe = (s.host == null || s.host.isEmpty()) ? "server" : s.host.replaceAll("[^A-Za-z0-9._-]", "_");
            File f = new File(getCacheDir(), "vg_" + safe + ".ovpn");
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(data);
            fo.close();
            androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent sh = new Intent(Intent.ACTION_SEND);
            sh.setType("application/octet-stream");
            sh.putExtra(Intent.EXTRA_STREAM,
                    androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f));
            sh.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(sh, getString(R.string.vpg_share)));
        } catch (Exception e) {
            statusView.setText("خطا: " + e.getMessage());
        }
    }

    // ---------- دریافت لیست ----------

    private void fetchList() {
        if (fetching) return;
        String custom = ((EditText) findViewById(R.id.urlEdit)).getText().toString().trim();
        List<String> urls = new ArrayList<>();
        if (!custom.isEmpty()) {
            urls.add(custom);
        } else {
            Collections.addAll(urls, DEFAULT_SOURCES);
        }

        fetching = true;
        progress.setVisibility(View.VISIBLE);
        statusView.setText(R.string.fetching);

        new Thread(() -> {
            String body = null, err = null;
            for (String u : urls) {
                try {
                    body = httpGet(u);
                    if (body != null && body.length() > 500) break;
                    body = null;
                } catch (Exception e) {
                    err = u + " -> " + e.getMessage();
                    body = null;
                }
            }
            final String b = body, er = err;
            runOnUiThread(() -> {
                fetching = false;
                progress.setVisibility(View.GONE);
                if (b == null) {
                    statusView.setText(getString(R.string.fetch_fail) + (er == null ? "" : "\n" + er));
                    return;
                }
                parseCsv(b);
                if (all.isEmpty()) {
                    statusView.setText(R.string.parse_fail);
                    return;
                }
                buildCountrySpinner();
                applyFilter();
                statusView.setText(getString(R.string.vpg_fetched_n, all.size()));
            });
        }, "fetcher").start();
    }

    private String httpGet(String urlStr) throws Exception {
        String current = urlStr;
        for (int hop = 0; hop < 5; hop++) {
            URL url = new URL(current);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(12000);
            c.setReadTimeout(25000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) vpngate-companion/2.0");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new Exception("redirect without Location");
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new Exception("HTTP " + code);
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            c.disconnect();
            return bo.toString("UTF-8");
        }
        throw new Exception("too many redirects");
    }

    private void parseCsv(String body) {
        all.clear();
        String[] lines = body.replace("\r", "").split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("*")) continue;
            String[] p = line.split(",");
            if (p.length < 15) continue;
            Server s = new Server();
            s.host = p[0];
            s.ip = p[1];
            try { s.score = Long.parseLong(p[2].trim()); } catch (Exception ig) { }
            try { s.ping = Long.parseLong(p[3].trim()); } catch (Exception ig) { }
            try { s.speed = Long.parseLong(p[4].trim()); } catch (Exception ig) { }
            s.countryLong = p[5];
            s.countryShort = p[6];
            try { s.sessions = (int) Long.parseLong(p[7].trim()); } catch (Exception ig) { }
            s.b64 = p[p.length - 1].trim();
            if (s.b64.isEmpty() || s.ip.isEmpty()) continue;
            all.add(s);
        }
        Collections.sort(all, (a, b) -> Long.compare(b.score, a.score));
    }

    private void buildCountrySpinner() {
        List<String> codes = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (Server s : all) if (seen.add(s.countryShort)) codes.add(s.countryShort);
        Collections.sort(codes);

        List<String> items = new ArrayList<>();
        items.add(getString(R.string.vpg_all_countries) + " (" + all.size() + ")");
        for (String c : codes) {
            int n = 0;
            for (Server s : all) if (s.countryShort.equals(c)) n++;
            items.add(c + " (" + n + ")");
        }

        final List<String> finalCodes = codes;
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        countrySpin.setAdapter(ad);
        countrySpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                countryFilter = pos == 0 ? "" : finalCodes.get(pos - 1);
                applyFilter();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void applyFilter() {
        shown.clear();
        for (Server s : all) {
            if (countryFilter.isEmpty() || s.countryShort.equals(countryFilter)) shown.add(s);
        }
        adapter.notifyDataSetChanged();
        if (shown.isEmpty()) statusView.setText(R.string.vpg_empty_list);
    }

    // ---------- آداپتر لیست ----------

    private class ServerAdapter extends ArrayAdapter<Server> {
        ServerAdapter() {
            super(MainActivity.this, R.layout.item_server, shown);
        }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            if (cv == null) {
                cv = LayoutInflater.from(getContext()).inflate(R.layout.item_server, parent, false);
            }
            Server s = shown.get(pos);
            TextView l1 = cv.findViewById(R.id.line1);
            TextView l2 = cv.findViewById(R.id.line2);
            l1.setText(s.countryShort + " · " + s.countryLong);
            l2.setText(fmtSpeed(s.speed) + "  |  " + s.ping + " ms  |  " + s.sessions + " کاربر");
            return cv;
        }
    }

    private String fmtSpeed(long bps) {
        if (bps >= 1048576) return String.format(Locale.US, "%.1f MB/s", bps / 1048576.0);
        return String.format(Locale.US, "%.0f KB/s", bps / 1024.0);
    }
}
