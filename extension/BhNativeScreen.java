package app.revanced.extension.gamehub;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.BatteryManager;
import android.os.Build;
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

/**
 * BhNativeScreen — builds BunnerHub screens that look like GameHub's own
 * settings screens (Game Settings): stock launcher background and back icon,
 * MiSans font, battery + clock status row, settings-style rows with a light
 * focus outline, and a "B Back" hint.
 *
 * It uses only plain stock resources looked up by name, never GameHub's view
 * classes (CommonTopBarView, Focusable*): those are written for GameHub's
 * FocusableActivity, and a screen built from them crashed in a plain
 * Activity. Missing resources fall back to a plain color / default font.
 *
 * Usage: {@code BhNativeScreen s = new BhNativeScreen(activity, "Title");}
 * then add rows to {@link #list}, set {@link #summary}, and call
 * {@link #onResume()} / {@link #onWindowFocusChanged(boolean)}.
 */
public final class BhNativeScreen {

    public static final int WHITE = 0xFFFAFAFA;
    public static final int GREY = 0xFF9DA0A6;
    private static final String TAG = "BhNativeScreen";

    private final Activity act;
    public final Typeface font;
    public final LinearLayout list;
    public final TextView summary;
    private TextView battery;

    public BhNativeScreen(Activity activity, String title) {
        act = activity;
        font = loadFont(activity);

        FrameLayout root = new FrameLayout(act);
        Drawable bg = stockDrawable(act, "llanuncher_bg_default");
        if (bg != null) root.setBackground(bg);
        else root.setBackgroundColor(0xFF0B1020);

        LinearLayout column = new LinearLayout(act);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(buildTopBar(title));

        summary = text(12, GREY, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(12);
        slp.leftMargin = dp(60);
        slp.rightMargin = dp(60);
        column.addView(summary, slp);

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(56));
        list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(60), dp(4), dp(60), 0);
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(-1, 0, 1f);
        sclp.topMargin = dp(8);
        column.addView(scroll, sclp);

        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildBackHint(), new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END));
        act.setContentView(root);
        hideSystemBars();
    }

    public void onResume() {
        updateBattery();
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus) hideSystemBars();
    }

    // ── Pieces ───────────────────────────────────────────────────────────────

    private View buildTopBar(String title) {
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(22), dp(18), dp(24), 0);

        ImageView back = new ImageView(act);
        Drawable backIcon = stockDrawable(act, "comm_ic_topbar_back");
        if (backIcon != null) back.setImageDrawable(backIcon);
        back.setPadding(dp(2), dp(2), dp(2), dp(2));
        back.setBackground(focusOutline(dp(14)));
        back.setFocusable(true);
        back.setOnClickListener(v -> act.finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(28), dp(28)));

        TextView t = text(20, WHITE, true);
        t.setText(title);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = dp(12);
        bar.addView(t, tlp);

        battery = text(12, WHITE, true);
        bar.addView(battery);

        TextClock clock = new TextClock(act);
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
        LinearLayout hint = new LinearLayout(act);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        hint.setPadding(dp(8), dp(8), dp(24), dp(16));
        hint.setOnClickListener(v -> act.finish());

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

    /**
     * A settings-style row: title + grey subtitle on the left, bold value on
     * the right. Focusable, with the native-looking outline.
     */
    public LinearLayout row(String title, String subtitle, String value) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        row.setMinimumHeight(dp(64));
        row.setBackground(focusOutline(dp(12)));
        row.setFocusable(true);
        row.setClickable(true);

        LinearLayout texts = new LinearLayout(act);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView name = text(14, WHITE, false);
        name.setText(title);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(12, GREY, false);
            sub.setText(subtitle);
            sub.setMaxLines(2);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
            llp.topMargin = dp(2);
            texts.addView(sub, llp);
        }

        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.rightMargin = dp(16);
        row.addView(texts, tlp);

        if (value != null) {
            TextView v = text(14, WHITE, true);
            v.setText(value);
            row.addView(v);
        }

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(4);
        row.setLayoutParams(rlp);
        return row;
    }

    /** Transparent at rest; a dark card with a light outline when focused or pressed. */
    public Drawable focusOutline(int radius) {
        GradientDrawable on = new GradientDrawable();
        on.setColor(0x1AFFFFFF);
        on.setCornerRadius(radius);
        on.setStroke(dp(2), 0xE6FFFFFF);
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x00000000);
        off.setCornerRadius(radius);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, on);
        states.addState(new int[]{android.R.attr.state_pressed}, on);
        states.addState(new int[]{}, off);
        return states;
    }

    public TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(font, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    public int dp(int v) {
        return BhPlayTime.dp(act, v);
    }

    private void updateBattery() {
        try {
            Intent b = act.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            battery.setText(level >= 0 && scale > 0 ? (level * 100 / scale) + "%" : "");
        } catch (Throwable t) {
            battery.setText("");
        }
    }

    /** Native screens hide the system bars in GameHub's FocusableActivity base. */
    private void hideSystemBars() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                act.getWindow().setDecorFitsSystemWindows(false);
                WindowInsetsController c = act.getWindow().getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                act.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not hide system bars", t);
        }
    }

    private static Typeface loadFont(Context ctx) {
        try {
            int id = res(ctx, "misans_vf", "font");
            if (id != 0) return ctx.getResources().getFont(id);
        } catch (Throwable t) {
            Log.w(TAG, "MiSans font not available", t);
        }
        return Typeface.DEFAULT;
    }

    private static Drawable stockDrawable(Context ctx, String name) {
        try {
            int id = res(ctx, name, "drawable");
            return id == 0 ? null : ctx.getDrawable(id);
        } catch (Throwable t) {
            Log.w(TAG, "drawable " + name + " not available", t);
            return null;
        }
    }

    /**
     * CI renames the package only in the manifest, so the resource table can
     * keep the original package name: fall back to the table's own name.
     */
    static int res(Context ctx, String name, String type) {
        int id = ctx.getResources().getIdentifier(name, type, ctx.getPackageName());
        if (id == 0) {
            try {
                String resPkg = ctx.getResources().getResourcePackageName(ctx.getApplicationInfo().icon);
                id = ctx.getResources().getIdentifier(name, type, resPkg);
            } catch (Exception ignored) {
            }
        }
        return id;
    }
}
