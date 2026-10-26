package app.revanced.extension.gamehub;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.util.Log;
import android.widget.LinearLayout;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * BhPlayTimeActivity — "Play Time" screen from the launcher's left menu
 * (HomeLeftMenuDialog item id 15). Lists every game BhPlayTime has recorded,
 * most played first, with last-7-days / last-30-days totals; opening a game
 * shows its recent sessions. Styled by {@link BhNativeScreen}.
 */
public class BhPlayTimeActivity extends Activity {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final int SHOWN_SESSIONS = 30;

    private BhNativeScreen screen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        screen = new BhNativeScreen(this, "Play Time");
    }

    @Override
    protected void onResume() {
        super.onResume();
        screen.onResume();
        try {
            refresh();
        } catch (Throwable t) {
            Log.e("BhPlayTime", "refresh failed", t);
            screen.summary.setText("Could not load play time: " + t);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        screen.onWindowFocusChanged(hasFocus);
    }

    private void refresh() {
        screen.list.removeAllViews();
        List<BhPlayTime.Entry> entries = BhPlayTime.all(this);
        if (entries.isEmpty()) {
            screen.summary.setText("No play time recorded yet. Start a game and it will show up here.");
            return;
        }

        long now = System.currentTimeMillis();
        long total = 0, week = 0, month = 0;
        for (BhPlayTime.Entry e : entries) {
            List<BhPlayTime.Session> sessions = BhPlayTime.sessions(this, e.id);
            long eWeek = BhPlayTime.playedSince(sessions, now - 7 * DAY_MS);
            total += e.totalMs;
            week += eWeek;
            month += BhPlayTime.playedSince(sessions, now - 30 * DAY_MS);

            StringBuilder sub = new StringBuilder("Last played ").append(BhPlayTime.formatDay(e.lastPlayed));
            if (!sessions.isEmpty()) {
                sub.append("  ·  ").append(sessions.size()).append(sessions.size() == 1 ? " session" : " sessions");
            }
            if (eWeek > 0) sub.append("  ·  ").append(BhPlayTime.formatDuration(eWeek)).append(" in the last 7 days");

            String name = e.name != null && !e.name.isEmpty() ? e.name : e.id;
            LinearLayout row = screen.row(name, sub.toString(), BhPlayTime.formatDuration(e.totalMs));
            row.setOnClickListener(v -> showSessions(name, e.id));
            screen.list.addView(row);
        }

        screen.summary.setText(entries.size() + (entries.size() == 1 ? " game" : " games")
                + "  ·  " + BhPlayTime.formatDuration(total) + " in total"
                + "  ·  " + duration(week) + " in the last 7 days"
                + "  ·  " + duration(month) + " in the last 30 days");
        if (screen.list.getChildCount() > 0) screen.list.getChildAt(0).requestFocus();
    }

    private void showSessions(String name, String id) {
        List<BhPlayTime.Session> sessions = BhPlayTime.sessions(this, id);
        StringBuilder msg = new StringBuilder();
        if (sessions.isEmpty()) {
            msg.append("No sessions recorded yet. Session history started with this version; "
                    + "earlier play time is only in the total.");
        } else {
            SimpleDateFormat day = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault());
            int shown = 0;
            for (int i = sessions.size() - 1; i >= 0 && shown < SHOWN_SESSIONS; i--, shown++) {
                BhPlayTime.Session s = sessions.get(i);
                msg.append(day.format(new Date(s.start))).append("   ")
                        .append(BhPlayTime.formatDuration(s.playedMs)).append('\n');
            }
            if (sessions.size() > SHOWN_SESSIONS) {
                msg.append("…and ").append(sessions.size() - SHOWN_SESSIONS).append(" earlier sessions");
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setMessage(msg.toString().trim())
                .setPositiveButton("Close", null)
                .show();
    }

    /** "0 m" rather than "under 1 m" for totals that are really zero. */
    private static String duration(long ms) {
        return ms <= 0 ? "0 m" : BhPlayTime.formatDuration(ms);
    }
}
