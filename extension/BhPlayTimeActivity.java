package app.revanced.extension.gamehub;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.List;

/**
 * BhPlayTimeActivity — "Play Time" screen from the launcher's left menu
 * (HomeLeftMenuDialog item id 15). Lists every game BhPlayTime has recorded,
 * most played first.
 *
 * Looks like GameHub's settings screens (Game Settings): the stock launcher
 * background and back icon, the MiSans font, a status row (battery, clock),
 * settings-style rows with a light focus outline, and a "B Back" hint.
 *
 * It deliberately uses only plain stock resources (looked up by name), never
 * GameHub's own view classes: a version built from CommonTopBarView and
 * FocusableConstraintLayout crashed on open (cause not captured; they are
 * written for GameHub's FocusableActivity). Any missing resource falls back
 * to a plain color / default font.
 */
public class BhPlayTimeActivity extends Activity {

    private static final String TAG = "BhPlayTime";
    private static final int WHITE = 0xFFFAFAFA;
    private static final int GREY = 0xFF9DA0A6;

    private Typeface font;
    private LinearLayout list;
    private TextView summary;
    private TextView battery;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        font = loadFont();

        FrameLayout root = new FrameLayout(this);
        Drawable bg = stockDrawable("llanuncher_bg_default");
        if (bg != null) root.setBackground(bg);
        else root.setBackgroundColor(0xFF0B1020);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(buildTopBar());

        summary = text(12, GREY, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(12);
        slp.leftMargin = dp(60);
        column.addView(summary, slp);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(56));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(60), dp(4), dp(60), 0);
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(-1, 0, 1f);
        sclp.topMargin = dp(8);
        column.addView(scroll, sclp);

        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildBackHint(), new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END));
        setContentView(root);
        hideSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            refresh();
        } catch (Throwable t) {
            Log.e(TAG, "refresh failed", t);
            summary.setText("Could not load play time: " + t);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    // ── Header: back + title on the left, battery + clock on the right ───────

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(22), dp(18), dp(24), 0);

        ImageView back = new ImageView(this);
        Drawable backIcon = stockDrawable("comm_ic_topbar_back");
        if (backIcon != null) back.setImageDrawable(backIcon);
        back.setPadding(dp(2), dp(2), dp(2), dp(2));
        back.setBackground(focusOutline(dp(14)));
        back.setFocusable(true);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(28), dp(28)));

        TextView title = text(20, WHITE, true);
        title.setText("Play Time");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = dp(12);
        bar.addView(title, tlp);

        battery = text(12, WHITE, true);
        bar.addView(battery);

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("HH:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        clock.setTypeface(font, Typeface.BOLD);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.leftMargin = dp(16);
        bar.addView(clock, clp);
        return bar;
    }

    private View buildBackHint() {
        LinearLayout hint = new LinearLayout(this);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        hint.setPadding(dp(8), dp(8), dp(24), dp(16));
        hint.setOnClickListener(v -> finish());

        TextView key = text(13, 0xFF000000, true);
        key.setText("B");
        key.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFFFFFFFF);
        key.setBackground(circle);
        hint.addView(key, new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView label = text(14, WHITE, false);
        label.setText("Back");
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.leftMargin = dp(8);
        hint.addView(label, llp);
        return hint;
    }

    // ── Rows ─────────────────────────────────────────────────────────────────

    private void refresh() {
        updateBattery();
        list.removeAllViews();
        List<BhPlayTime.Entry> entries = BhPlayTime.all(this);

        long total = 0;
        for (BhPlayTime.Entry e : entries) total += e.totalMs;
        summary.setText(entries.isEmpty()
                ? "No play time recorded yet. Start a game and it will show up here."
                : entries.size() + (entries.size() == 1 ? " game" : " games")
                        + "  ·  " + BhPlayTime.formatDuration(total) + " in total");

        for (BhPlayTime.Entry e : entries) list.addView(buildRow(e));
        if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
    }

    private View buildRow(BhPlayTime.Entry e) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setMinimumHeight(dp(64));
        row.setBackground(focusOutline(dp(12)));
        row.setFocusable(true);
        row.setClickable(true);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView name = text(14, WHITE, false);
        name.setText(e.name != null && !e.name.isEmpty() ? e.name : e.id);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);

        TextView last = text(12, GREY, false);
        last.setText("Last played " + BhPlayTime.formatDay(e.lastPlayed));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = dp(2);
        texts.addView(last, llp);

        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.rightMargin = dp(16);
        row.addView(texts, tlp);

        TextView time = text(14, WHITE, true);
        time.setText(BhPlayTime.formatDuration(e.totalMs));
        row.addView(time);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(4);
        row.setLayoutParams(rlp);
        return row;
    }

    /** Transparent at rest; a dark card with a light outline when focused or pressed. */
    private Drawable focusOutline(int radius) {
        GradientDrawable on = new GradientDrawable();
        on.setColor(0x1AFFFFFF);
        on.setCornerRadius(radius);
        on.setStroke(dp(2), 0xE6FFFFFF);
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x00000000);
        off.setCornerRadius(radius);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, on);
        states.addState(new int[]{android.R.attr.state_pressed}, on);
        states.addState(new int[]{}, off);
        return states;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(font, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private void updateBattery() {
        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            battery.setText(level >= 0 && scale > 0 ? (level * 100 / scale) + "%" : "");
        } catch (Throwable t) {
            battery.setText("");
        }
    }

    private Typeface loadFont() {
        try {
            int id = res("misans_vf", "font");
            if (id != 0) return getResources().getFont(id);
        } catch (Throwable t) {
            Log.w(TAG, "MiSans font not available", t);
        }
        return Typeface.DEFAULT;
    }

    private Drawable stockDrawable(String name) {
        try {
            int id = res(name, "drawable");
            return id == 0 ? null : getDrawable(id);
        } catch (Throwable t) {
            Log.w(TAG, "drawable " + name + " not available", t);
            return null;
        }
    }

    /**
     * CI renames the package only in the manifest, so the resource table can
     * keep the original package name: fall back to the table's own name.
     */
    private int res(String name, String type) {
        int id = getResources().getIdentifier(name, type, getPackageName());
        if (id == 0) {
            try {
                String resPkg = getResources().getResourcePackageName(getApplicationInfo().icon);
                id = getResources().getIdentifier(name, type, resPkg);
            } catch (Exception ignored) {
            }
        }
        return id;
    }

    /** Native screens hide the system bars in GameHub's FocusableActivity base. */
    private void hideSystemBars() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                getWindow().setDecorFitsSystemWindows(false);
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not hide system bars", t);
        }
    }

    private int dp(int v) {
        return BhPlayTime.dp(this, v);
    }
}
