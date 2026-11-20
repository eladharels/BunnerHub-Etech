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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BhFsrPanel — the FSR page of the in-game sidebar (WineActivity drawer, tab
 * id sidebar_fsr). Built in code; the smali BhFsrFragment
 * (patches/smali_classes16) only calls {@link #create}, {@link #onShown} and
 * {@link #onHidden}. Launch-time logic is in {@link BhFsr}.
 *
 * Shows, refreshed every second: the size the game renders at (the Vulkan
 * layer's swapchain report, else the Wine screen size), the size FSR 1
 * upscales it to (or GameHub's plain stretch to the display),
 * and the BunnerHub upscaler's settings and status ({@link BhVkLayer}).
 * Settings are per game and apply on the next launch.
 */
public final class BhFsrPanel {

    private static final int WHITE = 0xFFFAFAFA;
    private static final int GREY = 0xFF9DA0A6;
    private static final int GREEN = 0xFF4CD08A;
    private static final int AMBER = 0xFFFFB74D;
    private static final int CARD = 0x14FFFFFF;
    private static final int ACCENT = 0xFF6C63FF;

    private static Context ctx;
    private static String gameId;
    private static TextView gameLine, renderValue, outputValue, displayValue, layerLine;
    private static Button layerToggle, layerSharp;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static volatile Map<String, String> status = new HashMap<>();
    private static volatile String statusText;
    private static final AtomicBoolean reading = new AtomicBoolean();
    private static final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            if (layerLine == null) return;
            // Skip the work while the drawer is closed, and never queue a
            // second read behind one that is still running.
            if (layerLine.isShown() && reading.compareAndSet(false, true)) {
                io.execute(() -> {
                    try {
                        Context c = ctx;
                        String id = gameId;
                        boolean running = c != null && id != null && id.equals(BhVkLayer.appliedGame);
                        status = running ? BhVkLayer.status(c) : new HashMap<>();
                        statusText = running ? BhVkLayer.statusLine(c, id) : null;
                    } finally {
                        reading.set(false);
                    }
                    ui.post(BhFsrPanel::refresh);
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
        status = new HashMap<>();
        statusText = null;

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
        outputValue = row(sizes, "Upscaled to");
        displayValue = row(sizes, "Your display");

        // ── Upscaler ──
        LinearLayout layer = card();
        root.addView(layer, margins(0, 12, 0, 0));
        TextView layerTitle = text(14, WHITE, true);
        layerTitle.setText("BunnerHub upscaler (test)");
        layer.addView(layerTitle);
        boolean have = BhVkLayer.available(ctx);
        TextView layerHelp = text(11, GREY, false);
        layerHelp.setText(have
                ? "\"FSR to display\" upscales each frame from the game's resolution (set in its GameHub "
                + "settings) to your display with AMD FSR 1, instead of a plain stretch. For more FPS, "
                + "lower the GameHub resolution. Works with any Wine build. Restart the game after changing."
                : "This build doesn't include the upscaler.");
        layer.addView(layerHelp, margins(0, 4, 0, 0));
        layerLine = text(12, GREY, false);
        if (have) {
            layerToggle = button("", true);
            layerToggle.setOnClickListener(v -> {
                if (gameId == null) return;
                BhVkLayer.setMode(ctx, gameId, (BhVkLayer.mode(ctx, gameId) + 1) % BhVkLayer.MODE_LABELS.length);
                refresh();
            });
            layer.addView(layerToggle, margins(0, 10, 0, 0));
            TextView sharpT = text(12, GREY, false);
            sharpT.setText("Sharpness");
            layer.addView(sharpT, margins(0, 12, 0, 4));
            LinearLayout lsRow = new LinearLayout(ctx);
            lsRow.setGravity(Gravity.CENTER_VERTICAL);
            Button lsMinus = button("−", false);
            lsMinus.setOnClickListener(v -> changeSharp(-1));
            layerSharp = button("", false);
            layerSharp.setFocusable(false);
            layerSharp.setClickable(false);
            Button lsPlus = button("+", false);
            lsPlus.setOnClickListener(v -> changeSharp(1));
            lsRow.addView(lsMinus);
            lsRow.addView(layerSharp, weightMargins(1f, 8));
            lsRow.addView(lsPlus, wrapLeft(8));
            layer.addView(lsRow);
        }
        layer.addView(layerLine, margins(0, 10, 0, 0));

        refresh();
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
        gameLine = renderValue = outputValue = displayValue = layerLine = null;
        layerToggle = layerSharp = null;
        status = new HashMap<>();
        statusText = null;
    }

    private static void changeSharp(int delta) {
        if (gameId == null) return;
        BhVkLayer.setSharp(ctx, gameId, BhVkLayer.sharp(ctx, gameId) + delta);
        refresh();
    }

    // ── Display ──────────────────────────────────────────────────────────────

    private static void refresh() {
        if (layerLine == null || ctx == null) return;
        Map<String, String> m = status;
        int[] disp = display();
        int[] wine = wineScreen();
        String sc = m.get("swapchain");
        boolean upscaling = "on".equals(m.get("upscale"));
        String render = sc != null && !sc.equals("0x0") ? sc : wine != null ? size(wine) : "—";
        renderValue.setText(render);
        if (upscaling && m.get("output") != null) {
            outputValue.setText(m.get("output") + " (FSR 1)");
        } else {
            outputValue.setText(disp != null ? size(disp) + " (plain stretch)" : "—");
        }
        displayValue.setText(disp != null ? size(disp) : "—");

        if (layerToggle == null) return;
        int mode = BhVkLayer.mode(ctx, gameId);
        int sharp = BhVkLayer.sharp(ctx, gameId);
        layerToggle.setText("Upscaler: " + BhVkLayer.MODE_LABELS[mode]);
        layerSharp.setText((sharp + 1) + " / " + BhVkLayer.SHARP_LEVELS);
        boolean running = gameId != null && gameId.equals(BhVkLayer.appliedGame);
        boolean pending = running
                ? (mode != BhVkLayer.appliedMode || (mode == BhVkLayer.UPSCALE && sharp != BhVkLayer.appliedSharp))
                : mode != BhVkLayer.OFF;
        String st = statusText;
        if (running && st != null) {
            boolean ok = st.startsWith("Layer: loaded") && !st.contains("upscale off");
            layerLine.setTextColor(ok ? GREEN : AMBER);
            layerLine.setText(pending ? st + "\nRestart the game to apply your changes." : st);
        } else {
            layerLine.setTextColor(pending ? AMBER : GREY);
            layerLine.setText(pending ? "Restart the game to apply your changes." : "");
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
