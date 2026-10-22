package com.vpngate.companion;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_IMPORT = 41;

    private static final String[] DEFAULT_SOURCES = {
            "https://www.vpngate.net/api/iphone/",
            "http://www.vpngate.net/api/iphone/",
            "https://raw.githubusercontent.com/GeorgeXie2333/vpngate-list-mirror/main/data/vpngate.csv",
            "https://cdn.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@main/data/vpngate.csv"
    };

    private TextView statusView;
    private ProgressBar progress;
    private Spinner countrySpin;
    private ListView listView;

    private final List<Server> all = new ArrayList<>();
    private final List<Server> shown = new ArrayList<>();
    private ServerAdapter adapter;
    private String countryFilter = "";
    private boolean fetching = false;

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
        progress = findViewById(R.id.progress);
        countrySpin = findViewById(R.id.countrySpin);
        listView = findViewById(R.id.listView);

        adapter = new ServerAdapter();
        listView.setAdapter(adapter);

        findViewById(R.id.fetchBtn).setOnClickListener(v -> fetchList());
        findViewById(R.id.importBtn).setOnClickListener(v -> pickFile());

        listView.setOnItemClickListener((parent, view, pos, id) -> {
            if (pos >= 0 && pos < shown.size()) openInOpenVpn(shown.get(pos));
        });
    }

    // ---------- دریافت لیست ----------

    private void fetchList() {
        if (fetching) return;
        String custom = ((android.widget.EditText) findViewById(R.id.urlEdit)).getText().toString().trim();
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
                statusView.setText(getString(R.string.fetched_n, all.size()));
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
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) vpngate-companion/1.0");
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
        items.add(getString(R.string.all_countries) + " (" + all.size() + ")");
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
        if (shown.isEmpty()) statusView.setText(R.string.empty_list);
    }

    // ---------- بازکردن کانفیگ در OpenVPN ----------

    private void openInOpenVpn(Server s) {
        try {
            byte[] data = Base64.decode(s.b64, Base64.DEFAULT);
            String safe = (s.host == null || s.host.isEmpty()) ? "server" : s.host.replaceAll("[^A-Za-z0-9._-]", "_");
            File f = new File(getCacheDir(), "vg_" + safe + ".ovpn");
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(data);
            fo.close();
            launchOvpn(f);
        } catch (Exception e) {
            statusView.setText("خطا: " + e.getMessage());
        }
    }

    private void launchOvpn(File f) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);

        // 1) OpenVPN for Android (de.blinkt.openvpn)
        Intent i1 = new Intent(Intent.ACTION_VIEW);
        i1.setDataAndType(uri, "application/x-openvpn");
        i1.setPackage("de.blinkt.openvpn");
        i1.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (canOpen(i1)) {
            startActivity(i1);
            return;
        }

        // 2) OpenVPN Connect (net.openvpn.openvpn)
        Intent i2 = new Intent("net.openvpn.openvpn.OPENVPN_PROFILE");
        i2.putExtra(Intent.EXTRA_STREAM, uri);
        i2.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (canOpen(i2)) {
            startActivity(i2);
            return;
        }

        // 3) هیچ اپ OpenVPN نیست -> اشتراک‌گذاری عمومی + پیام راهنما
        statusView.setText(R.string.openvpn_missing);
        Intent sh = new Intent(Intent.ACTION_SEND);
        sh.setType("application/octet-stream");
        sh.putExtra(Intent.EXTRA_STREAM, uri);
        sh.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(sh, getString(R.string.open_with)));
    }

    private boolean canOpen(Intent i) {
        return getPackageManager().resolveActivity(i, 0) != null;
    }

    // ---------- واردکردن فایل از حافظه ----------

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            InputStream in = getContentResolver().openInputStream(data.getData());
            File f = new File(getCacheDir(), "imported_" + System.currentTimeMillis() + ".ovpn");
            FileOutputStream fo = new FileOutputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            fo.close();
            in.close();
            statusView.setText(R.string.imported_ok);
            launchOvpn(f);
        } catch (Exception e) {
            statusView.setText("خطا: " + e.getMessage());
        }
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
