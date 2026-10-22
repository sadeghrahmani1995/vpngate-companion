package com.vpngate.companion;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Date;

/**
 * CrashCapture — ضبط آخرین کرش جاوا در هر پروسه (main / :openvpn) داخل فایل محلی.
 * در اجرای بعدی، MainActivity متن آن را نمایش می‌دهد و در کلیپ‌بورد می‌گذارد
 * تا کاربر بتواند علت دقیق کرش را برای اشکال‌زدایی ارسال کند.
 */
public final class CrashCapture {

    private static final long MAX_AGE_MS = 7L * 24 * 3600 * 1000;

    private CrashCapture() {
    }

    public static void install(final Context ctx) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                File f = new File(ctx.getFilesDir(), "lastcrash_" + processTag() + ".txt");
                PrintWriter pw = new PrintWriter(new FileWriter(f, false));
                pw.println("time: " + new Date());
                pw.println("process: " + processName());
                pw.println("thread: " + t.getName());
                pw.println(Log.getStackTraceString(e));
                pw.close();
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    public static String processName() {
        try {
            if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName();
            try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/cmdline"))) {
                String s = r.readLine();
                if (s != null) return s.trim().replace('\0', ' ').trim();
            }
        } catch (Throwable ignored) {
        }
        return "main";
    }

    private static String processTag() {
        String p = processName();
        return (p != null && p.endsWith(":openvpn")) ? "engine" : "main";
    }

    /** آخرین کرش‌های ثبت‌شده (main + engine) را برمی‌گرداند — در MainActivity نمایش داده می‌شود */
    public static String collectRecent(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (String tag : new String[]{"main", "engine"}) {
            File f = new File(ctx.getFilesDir(), "lastcrash_" + tag + ".txt");
            if (!f.exists()) continue;
            if (System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS) {
                f.delete();
                continue;
            }
            try {
                BufferedReader br = new BufferedReader(new FileReader(f));
                java.util.ArrayDeque<String> dq = new java.util.ArrayDeque<>();
                String ln;
                while ((ln = br.readLine()) != null) {
                    dq.addLast(ln);
                    if (dq.size() > 50) dq.removeFirst();
                }
                br.close();
                sb.append("---- گزارش کرش (").append(tag).append(") ----\n");
                for (String s : dq) sb.append(s).append('\n');
                sb.append("\n");
                f.delete(); // یک‌بار نمایش کافی است
            } catch (Throwable ignored) {
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
