package app.revanced.extension.gamehub;

import android.app.Activity;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BhFsrPanel — the FSR page of the in-game sidebar (WineActivity drawer, tab
 * id sidebar_fsr). Built in code; the smali BhFsrFragment
 * (patches/smali_classes16) only calls {@link #create}, {@link #onShown} and
 * {@link #onHidden}. Launch-time logic is in {@link BhFsr}.
 *
 * Shows, refreshed every second: the size the game renders at (DXVK's
 * swapchain), the Wine screen size FSR upscales to, the device display, and
 * the upscale factor. Settings are per game and apply on the next launch.
 */
public final class BhFsrPanel {

    private static final int WHITE = 0xFFFAFAFA;
    private static final int GREY = 0xFF9DA0A6;
    private static final int GREEN = 0xFF4CD08A;
    private static final int AMBER = 0xFFFFB74D;
    private static final int RED = 0xFFFF6B6B;
    private static final int CARD = 0x14FFFFFF;
    private static final int ACCENT = 0xFF6C63FF;

    private static Context ctx;
    private static String gameId;
    private static TextView gameLine, wineLine, sessionLine, pendingLine;
    private static TextView renderValue, outputValue, displayValue, factorValue, hintLine;
    private static Button toggle, strengthLabel, modeButton;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static volatile int[] render;
    private static final AtomicBoolean reading = new AtomicBoolean();
    private static final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            if (renderValue == null) return;
            // Skip the work while the drawer is closed, and never queue a
            // second log read behind one that is still running.
            if (renderValue.isShown() && reading.compareAndSet(false, true)) {
                io.execute(() -> {
                    try {
                        render = BhFsr.renderSize();
                    } finally {
                        reading.set(false);
                    }
                    ui.post(BhFsrPanel::refreshSizes);
                });
            }
            ui.postDelayed(this, 1000);
        }
    };

    private BhFsrPanel() {}

    /** BhFsrFragment.onCreateView. */
    public static android.view.View create(Context context) {
        ctx = context;
        gameId = BhPlayTime.currentGameId();
        render = null;

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));
        scroll.addView(root);

        TextView title = text(18, WHITE, true);
        title.setText("FSR upscaling");
        root.addView(title);
        gameLine = text(12, GREY, false);
        String name = BhPlayTime.currentGameName();
        gameLine.setText(gameId == null ? "No game running" : (name != null ? name : "Game " + gameId));
        root.addView(gameLine, margins(0, 2, 0, 12));

        // ── Resolutions (live) ──
        LinearLayout sizes = card();
        root.addView(sizes);
        TextView sizesTitle = text(14, WHITE, true);
        sizesTitle.setText("Resolution now");
        sizes.addView(sizesTitle);
        renderValue = row(sizes, "Game renders at");
        outputValue = row(sizes, "FSR upscales to (Wine screen)");
        displayValue = row(sizes, "Your display");
        factorValue = row(sizes, "Upscale factor");
        hintLine = text(11, GREY, false);
        sizes.addView(hintLine, margins(0, 8, 0, 0));

        // ── Status ──
        LinearLayout status = card();
        root.addView(status, margins(0, 12, 0, 0));
        TextView statusTitle = text(14, WHITE, true);
        statusTitle.setText("Status");
        status.addView(statusTitle);
        wineLine = text(12, GREY, false);
        status.addView(wineLine, margins(0, 6, 0, 0));
        sessionLine = text(12, GREY, false);
        status.addView(sessionLine, margins(0, 4, 0, 0));
        pendingLine = text(12, AMBER, false);
        status.addView(pendingLine, margins(0, 4, 0, 0));

        // ── Settings ──
        LinearLayout settings = card();
        root.addView(settings, margins(0, 12, 0, 0));
        TextView setTitle = text(14, WHITE, true);
        setTitle.setText("Settings for this game");
        settings.addView(setTitle);

        toggle = button("", true);
        toggle.setOnClickListener(v -> {
            if (gameId == null) return;
            BhFsr.setEnabled(ctx, gameId, !BhFsr.enabled(ctx, gameId));
            refreshSettings();
        });
        settings.addView(toggle, margins(0, 10, 0, 0));

        TextView sharpTitle = text(12, GREY, false);
        sharpTitle.setText("FSR strength (0 = sharpest, 5 = softest; default 2)");
        settings.addView(sharpTitle, margins(0, 12, 0, 4));
        LinearLayout sharpRow = new LinearLayout(ctx);
        sharpRow.setGravity(Gravity.CENTER_VERTICAL);
        Button minus = button("−", false);
        minus.setOnClickListener(v -> changeStrength(-1));
        strengthLabel = button("", false);
        strengthLabel.setFocusable(false);
        strengthLabel.setClickable(false);
        Button plus = button("+", false);
        plus.setOnClickListener(v -> changeStrength(1));
        sharpRow.addView(minus);
        sharpRow.addView(strengthLabel, weightMargins(1f, 8));
        sharpRow.addView(plus, wrapLeft(8));
        settings.addView(sharpRow);

        TextView modeTitle = text(12, GREY, false);
        modeTitle.setText("Render size");
        settings.addView(modeTitle, margins(0, 12, 0, 4));
        modeButton = button("", false);
        modeButton.setOnClickListener(v -> cycleMode());
        settings.addView(modeButton);

        TextView help = text(11, GREY, false);
        help.setText("How it works: the game draws a smaller picture and Wine's FSR 1 upscales and "
                + "sharpens it to the Wine screen size (the resolution set in the game's GameHub "
                + "settings). Fewer pixels to draw means more FPS.\n"
                + "• Changes apply the next time you start the game.\n"
                + "• With \"Game's own setting\", choose a lower resolution in the game's video options "
                + "and use fullscreen (not windowed or borderless).\n"
                + "• Works for DirectX 9–12 games (DXVK / VKD3D). OpenGL games are not upscaled.\n"
                + "• Needs a Proton/GE-based Wine that includes FSR; the Status line says whether yours does.\n"
                + "• The render size is read from DXVK's output; DX12 games may show it as unknown.");
        root.addView(help, margins(0, 16, 0, 0));

        refreshSettings();
        refreshSizes();
        return scroll;
    }

    /** BhFsrFragment.onResume. */
    public static void onShown() {
        ui.removeCallbacks(refresher);
        ui.post(refresher);
    }

    /** BhFsrFragment.onPause. */
    public static void onHidden() {
        ui.removeCallbacks(refresher);
    }

    /** BhFsrFragment.onDestroyView: drop the views and the Activity. */
    public static void onDestroyed() {
        ui.removeCallbacks(refresher);
        ctx = null;
        gameLine = wineLine = sessionLine = pendingLine = null;
        renderValue = outputValue = displayValue = factorValue = hintLine = null;
        toggle = strengthLabel = modeButton = null;
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private static void changeStrength(int delta) {
        if (gameId == null) return;
        BhFsr.setStrength(ctx, gameId, BhFsr.strength(ctx, gameId) + delta);
        refreshSettings();
    }

    private static void cycleMode() {
        if (gameId == null) return;
        BhFsr.Mode[] all = BhFsr.Mode.values();
        BhFsr.setMode(ctx, gameId, all[(BhFsr.mode(ctx, gameId).ordinal() + 1) % all.length]);
        refreshSettings();
        refreshSizes();
    }

    // ── Display ──────────────────────────────────────────────────────────────

    private static void refreshSettings() {
        if (toggle == null || ctx == null) return;
        boolean on = BhFsr.enabled(ctx, gameId);
        int strength = BhFsr.strength(ctx, gameId);
        BhFsr.Mode mode = BhFsr.mode(ctx, gameId);
        BhFsr.Support sup = BhFsr.support();
        boolean modeOk = sup == null || sup.state != BhFsr.Support.YES || sup.mode;

        toggle.setText(on ? "FSR: ON" : "FSR: OFF");
        strengthLabel.setText(String.valueOf(strength));
        modeButton.setText(mode.label + (modeOk ? "" : " (not in this Wine)"));
        modeButton.setEnabled(gameId != null && modeOk);

        if (sup == null || sup.state == BhFsr.Support.UNKNOWN) {
            wineLine.setTextColor(AMBER);
            wineLine.setText("Wine build: couldn't check for FSR");
        } else if (sup.state == BhFsr.Support.YES) {
            wineLine.setTextColor(GREEN);
            wineLine.setText("Wine build: supports FSR" + (sup.mode ? " (with render-size modes)" : ""));
        } else {
            wineLine.setTextColor(RED);
            wineLine.setText("Wine build: no FSR. Pick a Proton/GE-based Wine in this game's settings.");
        }

        BhFsr.Applied a = BhFsr.applied();
        boolean thisGame = a != null && gameId != null && gameId.equals(a.gameId);
        if (thisGame && a.on) {
            sessionLine.setTextColor(GREEN);
            sessionLine.setText("This session: FSR ON, strength " + a.strength + ", " + a.mode.label);
        } else {
            sessionLine.setTextColor(GREY);
            sessionLine.setText("This session: FSR off");
        }

        boolean pending;
        if (!thisGame) pending = on;
        else if (a.on != on) pending = true;
        else pending = on && (a.strength != strength || (modeOk && a.mode != mode));
        if (on && sup != null && sup.state == BhFsr.Support.NO) {
            pendingLine.setText("FSR stays off with this Wine build.");
        } else {
            pendingLine.setText(pending ? "Restart the game to apply your changes." : "");
        }
    }

    private static void refreshSizes() {
        if (renderValue == null || ctx == null) return;
        int[] out = wineScreen();
        int[] disp = display();
        int[] render = BhFsrPanel.render;
        BhFsr.Applied a = BhFsr.applied();
        boolean fsrNow = a != null && a.on && gameId != null && gameId.equals(a.gameId);

        if (render != null) {
            renderValue.setText(size(render));
        } else if (fsrNow) {
            renderValue.setText("waiting for DXVK…");
        } else {
            BhFsr.Mode mode = BhFsr.mode(ctx, gameId);
            renderValue.setText("shown while FSR is on");
            if (out != null && mode.value != null) {
                renderValue.setText("~" + Math.round(out[0] / mode.scale) + "x"
                        + Math.round(out[1] / mode.scale) + " with " + mode.label);
            }
        }
        outputValue.setText(out != null ? size(out) : "unknown");
        displayValue.setText(disp != null ? size(disp) : "unknown");

        if (render != null && out != null && render[0] > 0) {
            float f = out[0] / (float) render[0];
            factorValue.setText(String.format(Locale.US, "%.2f×  (%d%% of the pixels)",
                    f, Math.round(100f * render[0] * render[1] / ((float) out[0] * out[1]))));
            if (!fsrNow) {
                hintLine.setText("FSR is off this session: Wine stretches the picture without FSR.");
            } else if (render[0] >= out[0] && render[1] >= out[1]) {
                hintLine.setText("The game renders at full Wine screen size, so FSR has nothing to upscale. "
                        + "Pick a lower resolution in the game (fullscreen) or a Render size mode.");
            } else {
                hintLine.setText("FSR is upscaling " + size(render) + " → " + size(out) + ".");
            }
        } else {
            factorValue.setText("—");
            hintLine.setText(fsrNow ? "The game's render size shows up once it draws its first frame "
                    + "(DirectX games through DXVK)." : "");
        }
    }

    /** Wine's screen size: WineActivity.h (WinUIBridge) → Config → Config$Resolution. */
    private static int[] wineScreen() {
        try {
            Object bridge = fieldOfType(ctx, "com.winemu.openapi.WinUIBridge");
            Object config = bridge == null ? null : fieldOfType(bridge, "com.winemu.openapi.Config");
            Object res = config == null ? null : fieldOfType(config, "com.winemu.openapi.Config$Resolution");
            if (res == null) return null;
            int w = (Integer) res.getClass().getMethod("getWidth").invoke(res);
            int h = (Integer) res.getClass().getMethod("getHeight").invoke(res);
            return w > 0 && h > 0 ? new int[]{w, h} : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int[] display() {
        try {
            WindowManager wm = ctx instanceof Activity ? ((Activity) ctx).getWindowManager()
                    : (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            Point p = new Point();
            wm.getDefaultDisplay().getRealSize(p);
            return new int[]{Math.max(p.x, p.y), Math.min(p.x, p.y)};
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object fieldOfType(Object obj, String typeName) throws IllegalAccessException {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getName().equals(typeName)) {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v != null) return v;
                }
            }
        }
        return null;
    }

    private static String size(int[] s) {
        return s[0] + "x" + s[1];
    }

    // ── View helpers (same look as BhTrainerPanel) ───────────────────────────

    private static TextView row(LinearLayout parent, String label) {
        LinearLayout r = new LinearLayout(ctx);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = text(13, GREY, false);
        l.setText(label);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = text(14, WHITE, true);
        v.setGravity(Gravity.END);
        r.addView(v, wrapLeft(8));
        parent.addView(r, margins(0, 8, 0, 0));
        return v;
    }

    private static LinearLayout card() {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        c.setBackground(rounded(CARD, 12));
        return c;
    }

    private static Button button(String label, boolean primary) {
        Button b = new Button(ctx);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextColor(WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(dp(44));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable on = new GradientDrawable();
        on.setColor(primary ? ACCENT : 0x33FFFFFF);
        on.setCornerRadius(dp(8));
        on.setStroke(dp(2), 0xE6FFFFFF);
        GradientDrawable off = new GradientDrawable();
        off.setColor(primary ? ACCENT : 0x33FFFFFF);
        off.setCornerRadius(dp(8));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, off);
        b.setBackground(s);
        return b;
    }

    private static GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private static TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private static LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    private static LinearLayout.LayoutParams wrapLeft(int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(left);
        return lp;
    }

    private static LinearLayout.LayoutParams weightMargins(float weight, int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, weight);
        lp.leftMargin = dp(left);
        return lp;
    }

    private static int dp(int v) {
        return BhPlayTime.dp(ctx, v);
    }
}
