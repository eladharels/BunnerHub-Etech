package app.revanced.extension.gamehub;

import android.app.Activity;
import android.content.Context;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BhFsrPanel — the FSR page of the in-game sidebar (WineActivity drawer, tab
 * id sidebar_fsr). Built in code with {@link BhUi}; the smali BhFsrFragment
 * (patches/smali_classes16) only calls {@link #create}, {@link #onShown},
 * {@link #onHidden} and {@link #onDestroyed}. Launch-time logic is in {@link BhFsr}.
 *
 * Top to bottom: a status badge; a live line (game resolution → screen, FPS,
 * GPU load, temperature) refreshed every second; a Native Rendering+ banner
 * with a one-tap fix; the upscaler's mode, output size and sharpness as
 * selectors (per game, applied live through {@link BhVkLayer}); Screenshot,
 * Performance history (a page of its own) and ⓘ (help and the layer's
 * technical status).
 */
public final class BhFsrPanel {

    private static Context ctx;
    private static String gameId;
    private static FrameLayout host;
    private static ScrollView mainPage, historyPage;
    private static TextView pill, resLine, fpsChip, gpuChip, tempChip, techLine;
    private static LinearLayout helpBox, targetRow, sharpRow, historyList;
    private static BhUi.Banner nativeBanner, notice;
    private static BhUi.Segmented modeSeg, targetSeg;
    private static View[] sharpBars;
    private static long historyLoaded;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static volatile Map<String, String> status = new HashMap<>();
    private static volatile String statusText;
    private static volatile int gpu = -1, temp;
    private static final AtomicBoolean reading = new AtomicBoolean();
    private static final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            if (host == null) return;
            // Skip the work while the drawer is closed, and never queue a
            // second read behind one that is still running.
            if (host.isShown() && reading.compareAndSet(false, true)) {
                io.execute(() -> {
                    try {
                        Context c = ctx;
                        String id = gameId;
                        boolean running = c != null && id != null && id.equals(BhVkLayer.appliedGame);
                        status = running ? BhVkLayer.status(c) : new HashMap<>();
                        statusText = running ? BhVkLayer.statusLine(c, id) : null;
                        gpu = BhUpscalerLog.gpuLoad();
                        temp = BhUpscalerLog.hotTemp();
                    } finally {
                        reading.set(false);
                    }
                    ui.post(BhFsrPanel::refresh);
                    if (android.os.SystemClock.elapsedRealtime() - historyLoaded > 30_000) ui.post(BhFsrPanel::loadHistory);
                });
            }
            ui.postDelayed(this, 1000);
        }
    };

    private BhFsrPanel() {}

    /** BhFsrFragment.onCreateView. */
    public static View create(Context context) {
        ctx = context;
        gameId = BhPlayTime.currentGameId();
        status = new HashMap<>();
        statusText = null;

        mainPage = new ScrollView(ctx);
        mainPage.setVerticalScrollBarEnabled(false);
        LinearLayout root = column();
        mainPage.addView(root);

        // ── Header: title, game, status badge ──
        LinearLayout header = BhUi.row(ctx);
        LinearLayout titles = new LinearLayout(ctx);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = BhUi.text(ctx, 18, BhUi.WHITE, true);
        title.setText("FSR upscaling");
        titles.addView(title);
        TextView gameLine = BhUi.text(ctx, 12, BhUi.GREY, false);
        String name = BhPlayTime.currentGameName();
        gameLine.setText(gameId == null ? "No game running" : (name != null ? name : "Game " + gameId));
        titles.addView(gameLine);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pill = BhUi.pill(ctx);
        header.addView(pill, BhUi.next(ctx, 8));
        root.addView(header);

        // ── Live line ──
        LinearLayout live = BhUi.card(ctx);
        TextView liveLabel = BhUi.text(ctx, 11, BhUi.GREY, false);
        liveLabel.setText("GAME RENDERS → SCREEN");
        liveLabel.setLetterSpacing(0.06f);
        live.addView(liveLabel);
        resLine = BhUi.text(ctx, 19, BhUi.WHITE, true);
        live.addView(resLine, BhUi.below(ctx, 2));
        LinearLayout chips = BhUi.row(ctx);
        fpsChip = BhUi.chip(ctx);
        gpuChip = BhUi.chip(ctx);
        tempChip = BhUi.chip(ctx);
        chips.addView(fpsChip);
        chips.addView(gpuChip, BhUi.next(ctx, 6));
        chips.addView(tempChip, BhUi.next(ctx, 6));
        live.addView(chips, BhUi.below(ctx, 8));
        root.addView(live, BhUi.below(ctx, 12));

        // ── Banners ──
        nativeBanner = new BhUi.Banner(ctx, "Turn on");
        nativeBanner.message.setText("Native Rendering+ is off, so the FSR picture is cropped.");
        BhUi.onTap(nativeBanner.action, BhFsrPanel::turnOnNative);
        nativeBanner.view.setVisibility(View.GONE);
        root.addView(nativeBanner.view, BhUi.below(ctx, 10));
        notice = new BhUi.Banner(ctx, null);
        notice.view.setVisibility(View.GONE);
        root.addView(notice.view, BhUi.below(ctx, 10));

        if (!BhVkLayer.available(ctx)) {
            TextView none = BhUi.text(ctx, 13, BhUi.GREY, false);
            none.setText("This build doesn't include the upscaler.");
            root.addView(none, BhUi.below(ctx, 14));
            return wrap();
        }

        // ── Upscaler mode ──
        modeSeg = new BhUi.Segmented(ctx, new String[]{"Off", "Report only", "FSR"}, i -> {
            if (gameId == null) return;
            BhVkLayer.setMode(ctx, gameId, i);
            BhVkLayer.settingsChanged(ctx, gameId);
            refresh();
        });
        root.addView(labeled("Upscaler", modeSeg.view), BhUi.below(ctx, 12));

        // ── Output size ──
        targetSeg = new BhUi.Segmented(ctx, new String[]{"Display", "1080p", "900p", "720p"}, i -> {
            if (gameId == null) return;
            BhVkLayer.setTarget(ctx, gameId, i);
            BhVkLayer.settingsChanged(ctx, gameId);
            refresh();
        });
        targetRow = labeled("Upscale to", targetSeg.view);
        root.addView(targetRow, BhUi.below(ctx, 8));

        // ── Sharpness: tap a step ──
        LinearLayout steps = BhUi.row(ctx);
        TextView soft = BhUi.text(ctx, 12, BhUi.GREY, false);
        soft.setText("Soft");
        steps.addView(soft);
        LinearLayout bars = BhUi.row(ctx);
        sharpBars = new View[BhVkLayer.SHARP_LEVELS];
        for (int i = 0; i < sharpBars.length; i++) {
            FrameLayout cell = new FrameLayout(ctx);
            cell.setFocusable(true);
            cell.setClickable(true);
            cell.setBackground(BhUi.states(ctx, 0x00FFFFFF, 0, 8));
            cell.setContentDescription("Sharpness " + (i + 1) + " of " + sharpBars.length);
            View bar = new View(ctx);
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    BhUi.dp(ctx, 8 + i * 3), Gravity.CENTER_VERTICAL);
            bl.leftMargin = bl.rightMargin = BhUi.dp(ctx, 3);
            cell.addView(bar, bl);
            sharpBars[i] = bar;
            final int level = i;
            BhUi.onTap(cell, () -> setSharp(level));
            bars.addView(cell, new LinearLayout.LayoutParams(0, BhUi.dp(ctx, 44), 1f));
        }
        steps.addView(bars, new LinearLayout.LayoutParams(BhUi.dp(ctx, 170), BhUi.dp(ctx, 44)));
        TextView sharp = BhUi.text(ctx, 12, BhUi.GREY, false);
        sharp.setText("Sharp");
        steps.addView(sharp);
        sharpRow = labeled("Sharpness", steps);
        root.addView(sharpRow, BhUi.below(ctx, 4));

        // ── Actions ──
        LinearLayout actions = BhUi.row(ctx);
        Button shot = BhUi.button(ctx, "Screenshot", BhUi.PRIMARY);
        BhUi.onTap(shot, BhFsrPanel::screenshot);
        actions.addView(shot);
        Button hist = BhUi.button(ctx, "Performance history  ›", BhUi.SECONDARY);
        BhUi.onTap(hist, () -> showHistory(true));
        actions.addView(hist, BhUi.next(ctx, 8));
        Button info = BhUi.button(ctx, "i", BhUi.SECONDARY);
        info.setContentDescription("How it works");
        info.setPadding(0, 0, 0, 0);
        BhUi.onTap(info, () -> helpBox.setVisibility(helpBox.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(BhUi.dp(ctx, 44), BhUi.dp(ctx, 44));
        il.leftMargin = BhUi.dp(ctx, 8);
        actions.addView(info, il);
        root.addView(actions, BhUi.below(ctx, 10));

        // ── Help (ⓘ) ──
        helpBox = BhUi.card(ctx);
        TextView help = BhUi.text(ctx, 13, 0xFFE3E4EC, false);
        help.setLineSpacing(0, 1.15f);
        help.setText("The game draws at the resolution set in its GameHub settings (set the same in the game's own "
                + "options); FSR 1 sharpens it up to the screen instead of a plain stretch. Lower the game's resolution "
                + "for more FPS. FSR needs Native Rendering+ on Force Enable. Changes apply instantly while the "
                + "upscaler is loaded. Screenshots go to BunnerHub-Etech/screenshots.");
        helpBox.addView(help);
        techLine = BhUi.text(ctx, 11, BhUi.GREY, false);
        helpBox.addView(techLine, BhUi.below(ctx, 8));
        helpBox.setVisibility(View.GONE);
        root.addView(helpBox, BhUi.below(ctx, 10));

        buildHistoryPage();
        loadHistory();
        return wrap();
    }

    private static View wrap() {
        host = BhUi.host(ctx, mainPage);
        if (historyPage != null) {
            historyPage.setVisibility(View.GONE);
            host.addView(historyPage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        refresh();
        return host;
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
        host = null;
        mainPage = historyPage = null;
        pill = resLine = fpsChip = gpuChip = tempChip = techLine = null;
        helpBox = targetRow = sharpRow = historyList = null;
        nativeBanner = notice = null;
        modeSeg = targetSeg = null;
        sharpBars = null;
        status = new HashMap<>();
        statusText = null;
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private static void setSharp(int level) {
        if (gameId == null) return;
        BhVkLayer.setSharp(ctx, gameId, level);
        BhVkLayer.settingsChanged(ctx, gameId);
        refresh();
    }

    private static void screenshot() {
        if (gameId == null) return;
        FrameLayout h = host;
        Context c = ctx;
        if (BhVkLayer.live(gameId)) BhUi.snack(h, "Taking a screenshot…", null, null);
        BhVkScreenshot.take(c, gameId, BhPlayTime.currentGameName(), (msg, png) -> {
            if (png != null) {
                BhUi.snack(h, "Saved to BunnerHub-Etech/screenshots", "Share", () -> BhVkScreenshot.share(c, png));
            } else {
                BhUi.snack(h, msg, null, null);
            }
        });
    }

    private static void turnOnNative() {
        boolean ok = ctx instanceof Activity && BhVkSurfaceScale.forceNativeRendering((Activity) ctx);
        BhUi.snack(host, ok ? "Native Rendering+ set to Force Enable"
                : "Couldn't change it here: set Performance → Native Rendering+ → Force Enable", null, null);
        if (ok) nativeBanner.view.setVisibility(View.GONE);
    }

    // ── Performance history page ─────────────────────────────────────────────

    private static void buildHistoryPage() {
        historyPage = new ScrollView(ctx);
        historyPage.setVerticalScrollBarEnabled(false);
        LinearLayout root = column();
        historyPage.addView(root);
        LinearLayout header = BhUi.row(ctx);
        Button back = BhUi.button(ctx, "‹", BhUi.SECONDARY);
        back.setContentDescription("Back");
        back.setPadding(0, 0, 0, 0);
        back.setTextSize(20);
        BhUi.onTap(back, () -> showHistory(false));
        header.addView(back, new LinearLayout.LayoutParams(BhUi.dp(ctx, 44), BhUi.dp(ctx, 44)));
        LinearLayout titles = new LinearLayout(ctx);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView t = BhUi.text(ctx, 16, BhUi.WHITE, true);
        t.setText("Performance history");
        titles.addView(t);
        TextView sub = BhUi.text(ctx, 12, BhUi.GREY, false);
        String name = BhPlayTime.currentGameName();
        sub.setText((name != null ? name : "This game") + " · best settings first");
        titles.addView(sub);
        header.addView(titles, BhUi.fill(ctx, 10));
        root.addView(header);
        historyList = new LinearLayout(ctx);
        historyList.setOrientation(LinearLayout.VERTICAL);
        root.addView(historyList, BhUi.below(ctx, 12));
    }

    private static void showHistory(boolean show) {
        if (historyPage == null) return;
        if (show) loadHistory();
        historyPage.setVisibility(show ? View.VISIBLE : View.GONE);
        mainPage.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    /** Reads the history off the UI thread (on open, then every 30 s while shown). */
    private static void loadHistory() {
        Context c = ctx;
        String id = gameId;
        if (c == null || historyList == null) return;
        historyLoaded = android.os.SystemClock.elapsedRealtime();
        io.execute(() -> {
            List<BhPerfHistory.Ranked> ranked = id == null ? null : BhPerfHistory.ranked(c, id);
            List<String> latest = id == null ? null : BhPerfHistory.latest(c, id, 4);
            ui.post(() -> fillHistory(ranked, latest));
        });
    }

    private static void fillHistory(List<BhPerfHistory.Ranked> ranked, List<String> latest) {
        LinearLayout list = historyList;
        if (list == null) return;
        list.removeAllViews();
        if (ranked == null || ranked.isEmpty()) {
            TextView none = BhUi.text(ctx, 13, BhUi.GREY, false);
            none.setText(gameId == null ? "No game running." : "No sessions yet. With the upscaler loaded (Report only "
                    + "or FSR), every 30 s or more of play with the same settings is recorded here.");
            list.addView(none);
            return;
        }
        double max = 1;
        for (BhPerfHistory.Ranked r : ranked) max = Math.max(max, r.fps);
        for (int i = 0; i < Math.min(6, ranked.size()); i++) {
            BhPerfHistory.Ranked r = ranked.get(i);
            LinearLayout item = new LinearLayout(ctx);
            item.setOrientation(LinearLayout.VERTICAL);
            TextView label = BhUi.text(ctx, 13, 0xFFE3E4EC, false);
            label.setText(r.settings);
            item.addView(label);
            LinearLayout barRow = BhUi.row(ctx);
            LinearLayout track = BhUi.row(ctx);
            track.setBackground(BhUi.rounded(ctx, BhUi.WELL, 5));
            View fill = new View(ctx);
            fill.setBackground(BhUi.rounded(ctx, BhUi.ACCENT, 5));
            track.addView(fill, new LinearLayout.LayoutParams(0, BhUi.dp(ctx, 10), (float) r.fps));
            track.addView(new View(ctx), new LinearLayout.LayoutParams(0, BhUi.dp(ctx, 10), (float) (max - r.fps)));
            barRow.addView(track, new LinearLayout.LayoutParams(0, BhUi.dp(ctx, 10), 1f));
            TextView fps = BhUi.text(ctx, 14, BhUi.WHITE, true);
            fps.setText(Math.round(r.fps) + " FPS");
            fps.setGravity(Gravity.END);
            barRow.addView(fps, new LinearLayout.LayoutParams(BhUi.dp(ctx, 72), ViewGroup.LayoutParams.WRAP_CONTENT));
            item.addView(barRow, BhUi.below(ctx, 4));
            TextView detail = BhUi.text(ctx, 11, BhUi.GREY, false);
            detail.setText(r.detail());
            item.addView(detail, BhUi.below(ctx, 2));
            list.addView(item, BhUi.below(ctx, i == 0 ? 0 : 12));
        }
        if (latest != null && !latest.isEmpty()) {
            TextView lt = BhUi.text(ctx, 14, BhUi.WHITE, true);
            lt.setText("Latest sessions");
            list.addView(lt, BhUi.below(ctx, 18));
            for (String l : latest) {
                TextView tv = BhUi.text(ctx, 12, BhUi.GREY, false);
                tv.setText(l);
                list.addView(tv, BhUi.below(ctx, 6));
            }
        }
        TextView foot = BhUi.text(ctx, 11, BhUi.GREY, false);
        foot.setText("Average FPS over all sessions with each setting (30 s or more each).");
        list.addView(foot, BhUi.below(ctx, 14));
    }

    // ── Display ──────────────────────────────────────────────────────────────

    private static void refresh() {
        if (host == null || ctx == null) return;
        Map<String, String> m = status;
        int[] disp = display();
        int[] wine = wineScreen();
        String sc = m.get("swapchain");
        boolean upscaling = "on".equals(m.get("upscale"));
        String render = sc != null && !sc.equals("0x0") ? sc : wine != null ? size(wine) : "—";
        render = render.replace('x', '×');
        String output = m.get("output");
        resLine.setText(upscaling && output != null ? render + " → " + output.replace('x', '×')
                : render + (disp != null ? " → " + size(disp).replace('x', '×') + " (stretched)" : ""));

        boolean live = gameId != null && BhVkLayer.live(gameId);
        long age = Long.MAX_VALUE;
        try {
            age = System.currentTimeMillis() / 1000 - Long.parseLong(m.get("time"));
        } catch (Throwable ignored) {
        }
        String fps = m.get("fps");
        show(fpsChip, live && fps != null && age < 5 ? Math.round(parse(fps)) + " FPS" : null);
        show(gpuChip, gpu >= 0 ? "GPU " + gpu + "%" : null);
        show(tempChip, temp > 0 ? temp + "°C" : null);

        int mode = gameId == null ? BhVkLayer.OFF : BhVkLayer.mode(ctx, gameId);
        boolean pending = !live && mode != BhVkLayer.OFF;
        String note = m.get("note");
        if (pending) {
            BhUi.setPill(pill, "Restart needed", BhUi.AMBER, 0x29FFB74D);
        } else if (live && upscaling) {
            BhUi.setPill(pill, "FSR on", BhUi.GREEN, 0x2E4CD08A);
        } else if (live && mode == BhVkLayer.UPSCALE) {
            BhUi.setPill(pill, "Not upscaling", BhUi.AMBER, 0x29FFB74D);
        } else if (live && mode == BhVkLayer.REPORT) {
            BhUi.setPill(pill, "Measuring", BhUi.SOFT, BhUi.CARD_HI);
        } else {
            BhUi.setPill(pill, "Off", BhUi.SOFT, BhUi.CARD_HI);
        }

        nativeBanner.view.setVisibility(live && mode == BhVkLayer.UPSCALE
                && Boolean.FALSE.equals(BhVkSurfaceScale.direct) ? View.VISIBLE : View.GONE);
        String noticeText = pending ? "Restart the game to load the upscaler. After that, changes apply right away."
                : live && mode == BhVkLayer.UPSCALE && !upscaling && note != null && !note.isEmpty() ? cap(note)
                : null;
        notice.view.setVisibility(noticeText != null ? View.VISIBLE : View.GONE);
        if (noticeText != null) notice.message.setText(noticeText);

        if (modeSeg == null) return;
        modeSeg.select(mode);
        targetSeg.select(gameId == null ? 0 : BhVkLayer.target(ctx, gameId));
        int sharp = gameId == null ? BhVkLayer.DEFAULT_SHARP : BhVkLayer.sharp(ctx, gameId);
        for (int i = 0; i < sharpBars.length; i++) {
            sharpBars[i].setBackground(BhUi.rounded(ctx, i <= sharp ? 0xFF7A72FF : 0x40FFFFFF, 4));
        }
        boolean fsr = mode == BhVkLayer.UPSCALE;
        BhUi.enable(targetRow, fsr);
        BhUi.enable(sharpRow, fsr);

        String st = statusText;
        StringBuilder tech = new StringBuilder(st != null ? st : live ? "" : "Upscaler not loaded in this run.");
        if (live && fsr) {
            Boolean direct = BhVkSurfaceScale.direct;
            tech.append("\nNative Rendering+: ").append(direct == null ? "not reported yet" : direct ? "on" : "off")
                    .append(" · screen fit: ").append(BhVkSurfaceScale.result);
        }
        techLine.setText(tech.toString().trim());
    }

    private static void show(TextView chip, String text) {
        chip.setVisibility(text != null ? View.VISIBLE : View.GONE);
        if (text != null) chip.setText(text);
    }

    private static String cap(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1) + (s.endsWith(".") ? "" : ".");
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Throwable t) {
            return 0;
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

    // ── Layout helpers ───────────────────────────────────────────────────────

    private static LinearLayout column() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(BhUi.dp(ctx, 20), BhUi.dp(ctx, 16), BhUi.dp(ctx, 20), BhUi.dp(ctx, 72)); // room for a snackbar
        return l;
    }

    /** A setting row: label on the left, its control on the right. */
    private static LinearLayout labeled(String label, View control) {
        LinearLayout r = BhUi.row(ctx);
        r.setMinimumHeight(BhUi.dp(ctx, 52));
        TextView l = BhUi.text(ctx, 14, BhUi.WHITE, true);
        l.setText(label);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(control, BhUi.next(ctx, 10));
        return r;
    }
}
