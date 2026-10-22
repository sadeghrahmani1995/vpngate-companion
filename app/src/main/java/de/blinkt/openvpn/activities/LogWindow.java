package de.blinkt.openvpn.activities;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.core.LogItem;
import de.blinkt.openvpn.core.VpnStatus;

/**
 * Minimal log window used as the notification tap target of the embedded
 * ics-openvpn engine. Shows the recent engine log inside a scroll view.
 */
public class LogWindow extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#10141A"));
        root.setPadding(24, 32, 24, 24);

        TextView title = new TextView(this);
        title.setText(R.string.vpg_engine_log);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        ScrollView sc = new ScrollView(this);
        TextView log = new TextView(this);
        log.setTextColor(Color.parseColor("#9FE8C8"));
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(12);
        StringBuilder sb = new StringBuilder();
        try {
            LogItem[] buf = VpnStatus.getlogbuffer();
            if (buf != null) {
                for (LogItem li : buf) {
                    sb.append(li.getString(this)).append('\n');
                }
            }
        } catch (Throwable ignored) {
        }
        if (sb.length() == 0) sb.append("...");
        log.setText(sb.toString());
        sc.addView(log);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = 24;
        root.addView(sc, lp);

        setContentView(root);
    }
}
