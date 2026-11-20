package app.revanced.extension.gamehub;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BhVkLayer — installs and switches on BunnerHub Etech's own Vulkan layer
 * (tools/vklayer/bh_vklayer.c, built by CI into assets/bunnerhub-etech/
 * libbh_vklayer.so) for a game.
 *
 * GameHub's Wine uses a desktop-style Vulkan loader: it reads ICD manifests
 * from ~/.config/vulkan/icd.d (that is how libGameScopeVK is loaded), so it
 * also reads implicit layer manifests from ~/.config/vulkan/implicit_layer.d.
 * The layer's manifest there has enable_environment BH_VKLAYER=1, so it loads
 * only into games whose launch environment sets that (BhFsr.env, per game).
 *
 * It reports the game's swapchain and frame rate in a status file
 * (BH_VKLAYER_STATUS), shown in the FSR tab. With "FSR to display" it also
 * sets BH_VKLAYER_OUT (the display size): the layer upscales each frame from
 * the Wine screen size to that with FSR 1, and {@link BhVkSurfaceScale} scales
 * GameHub's DirectRendering surface so the larger frame fits the screen
 * instead of being cropped (see tools/vklayer/bh_vklayer.c).
 */
public final class BhVkLayer {

    private static final String TAG = "BhVkLayer";
    private static final String ASSET = "bunnerhub-etech/libbh_vklayer.so";
    private static final String LIB = "usr/lib/libbh_vklayer.so";
    private static final String MANIFEST = "bh_vklayer.json";
    private static final String STATUS = "bh_vklayer_status.txt";

    /** Game id the last launch switched the layer on for (this process), and what it got. */
    static volatile String appliedGame;
    static volatile int appliedMode, appliedSharp, appliedTarget;

    static final int OFF = 0, REPORT = 1, UPSCALE = 2;
    static final String[] MODE_LABELS = {"Off", "On, report only", "On, FSR to display"};
    /** RCAS sharpness in stops (0 = sharpest), for levels 1..5 shown as "5/5 … 1/5". */
    private static final float[] SHARP_STOPS = {2.0f, 1.0f, 0.5f, 0.25f, 0.0f};
    static final int SHARP_LEVELS = SHARP_STOPS.length, DEFAULT_SHARP = 3;
    /** "Upscale to" choices: 0 = the display's own size, then fixed sizes. */
    static final int[][] TARGETS = {null, {1920, 1080}, {1600, 900}, {1280, 720}};

    private BhVkLayer() {}

    // ── Per-game switch ─────────────────────────────────────────────────────

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences("bh_fsr", Context.MODE_PRIVATE);
    }

    static int mode(Context ctx, String gameId) {
        if (gameId == null) return OFF;
        SharedPreferences p = prefs(ctx);
        int m = p.getInt("layer_mode_" + gameId, -1);
        if (m < 0) m = p.getBoolean("layer_" + gameId, false) ? REPORT : OFF; /* step 1's switch */
        return Math.max(OFF, Math.min(UPSCALE, m));
    }

    static void setMode(Context ctx, String gameId, int mode) {
        if (gameId != null) prefs(ctx).edit().putInt("layer_mode_" + gameId, mode).apply();
    }

    static boolean enabled(Context ctx, String gameId) {
        return mode(ctx, gameId) != OFF;
    }

    static int target(Context ctx, String gameId) {
        int v = gameId == null ? 0 : prefs(ctx).getInt("layer_out_" + gameId, 0);
        return Math.max(0, Math.min(TARGETS.length - 1, v));
    }

    static void setTarget(Context ctx, String gameId, int v) {
        if (gameId != null) prefs(ctx).edit().putInt("layer_out_" + gameId, ((v % TARGETS.length) + TARGETS.length)
                % TARGETS.length).apply();
    }

    /** The output size for a target choice, as "WxH", or null. */
    static String targetSize(Context ctx, int choice) {
        int[] t = TARGETS[Math.max(0, Math.min(TARGETS.length - 1, choice))];
        return t == null ? displaySize(ctx) : t[0] + "x" + t[1];
    }

    static String targetLabel(Context ctx, int choice) {
        String size = targetSize(ctx, choice);
        return choice == 0 ? "Display" + (size != null ? " (" + size + ")" : "") : size;
    }

    /** Sharpness level 0..SHARP_LEVELS-1 (higher = sharper). */
    static int sharp(Context ctx, String gameId) {
        int v = gameId == null ? DEFAULT_SHARP : prefs(ctx).getInt("layer_sharp_" + gameId, DEFAULT_SHARP);
        return Math.max(0, Math.min(SHARP_LEVELS - 1, v));
    }

    static void setSharp(Context ctx, String gameId, int v) {
        if (gameId != null)
            prefs(ctx).edit().putInt("layer_sharp_" + gameId, Math.max(0, Math.min(SHARP_LEVELS - 1, v))).apply();
    }

    /** The display's full size in landscape, as "WxH", or null. */
    static String displaySize(Context ctx) {
        try {
            android.view.WindowManager wm =
                    (android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            android.graphics.Point p = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(p);
            int w = Math.max(p.x, p.y), h = Math.min(p.x, p.y);
            return w > 0 && h > 0 ? w + "x" + h : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when this build carries the layer. */
    static boolean available(Context ctx) {
        try (InputStream in = ctx.getAssets().open(ASSET)) {
            return in.read() >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ── Launch (BhFsr.env) ──────────────────────────────────────────────────

    /**
     * Installs the layer and its manifest and adds the variables that switch
     * it on. Returns the environment unchanged if anything fails.
     */
    static String[] apply(Context ctx, String[] env) {
        try {
            File lib = new File(ctx.getFilesDir(), LIB);
            byte[] so;
            try (InputStream in = ctx.getAssets().open(ASSET)) {
                so = readAll(in);
            }
            if (!lib.isFile() || lib.length() != so.length) {
                File parent = lib.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                File tmp = new File(lib.getPath() + ".tmp");
                try (OutputStream o = new FileOutputStream(tmp)) {
                    o.write(so);
                }
                if (!tmp.renameTo(lib)) throw new IllegalStateException("cannot install " + lib);
            }
            String json = "{\n  \"file_format_version\": \"1.1.2\",\n  \"layer\": {\n"
                    + "    \"name\": \"VK_LAYER_BH_upscale\",\n    \"type\": \"GLOBAL\",\n"
                    + "    \"library_path\": \"" + lib.getAbsolutePath() + "\",\n"
                    + "    \"api_version\": \"1.3.216\",\n    \"implementation_version\": \"1\",\n"
                    + "    \"description\": \"BunnerHub Etech upscaling layer\",\n"
                    + "    \"enable_environment\": { \"BH_VKLAYER\": \"1\" },\n"
                    + "    \"disable_environment\": { \"BH_VKLAYER_DISABLE\": \"1\" }\n  }\n}\n";
            for (File dir : manifestDirs(ctx, env)) writeIfChanged(new File(dir, MANIFEST), json);

            List<String> out = new ArrayList<>(Arrays.asList(env));
            String id = BhPlayTime.currentGameId();
            int mode = mode(ctx, id);
            int sharp = sharp(ctx, id);
            int target = target(ctx, id);
            String display = mode == UPSCALE ? targetSize(ctx, target) : null;
            boolean upscale = display != null;
            for (String k : new String[] {"BH_VKLAYER", "BH_VKLAYER_STATUS", "BH_VKLAYER_OUT", "BH_VKLAYER_SHARP"})
                remove(out, k);
            out.add("BH_VKLAYER=1");
            out.add("BH_VKLAYER_STATUS=" + statusFile(ctx).getAbsolutePath());
            if (upscale) {
                out.add("BH_VKLAYER_OUT=" + display);
                out.add("BH_VKLAYER_SHARP=" + SHARP_STOPS[sharp]);
                BhVkSurfaceScale.watch(ctx);
            }
            // A report left by an earlier game must not look like this one's.
            statusFile(ctx).delete();
            appliedGame = id;
            appliedMode = upscale ? UPSCALE : REPORT;
            appliedSharp = sharp;
            appliedTarget = target;
            Log.i(TAG, "layer on for " + id + (upscale ? ", FSR to " + display + ", sharpness " + SHARP_STOPS[sharp]
                    : ", report only"));
            return out.toArray(new String[0]);
        } catch (Throwable t) {
            Log.w(TAG, "layer not installed", t);
            return env;
        }
    }

    /** ~/.config/vulkan/implicit_layer.d of the steamuser home and of $XDG_CONFIG_HOME (if set). */
    private static List<File> manifestDirs(Context ctx, String[] env) {
        List<File> dirs = new ArrayList<>();
        dirs.add(new File(ctx.getFilesDir(), "usr/home/steamuser/.config/vulkan/implicit_layer.d"));
        for (String kv : env) {
            if (kv != null && kv.startsWith("XDG_CONFIG_HOME=") && kv.length() > 16) {
                File d = new File(kv.substring(16), "vulkan/implicit_layer.d");
                if (!dirs.contains(d)) dirs.add(d);
            }
        }
        return dirs;
    }

    private static void writeIfChanged(File f, String text) throws Exception {
        byte[] want = text.getBytes("UTF-8");
        if (f.isFile() && f.length() == want.length) {
            try (InputStream in = new FileInputStream(f)) {
                if (Arrays.equals(readAll(in), want)) return;
            }
        }
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("cannot create " + parent);
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(want);
        }
    }

    // ── Status (written by the layer) ───────────────────────────────────────

    static File statusFile(Context ctx) {
        return new File(ctx.getFilesDir(), STATUS);
    }

    /** The layer's last report as key → value, or an empty map. */
    static Map<String, String> status(Context ctx) {
        Map<String, String> m = new HashMap<>();
        File f = statusFile(ctx);
        if (!f.isFile() || f.length() > 64 * 1024) return m;
        try (InputStream in = new FileInputStream(f)) {
            for (String line : new String(readAll(in), "UTF-8").split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) m.put(line.substring(0, eq), line.substring(eq + 1).trim());
            }
        } catch (Throwable ignored) {
        }
        return m;
    }

    /** One line for the FSR tab, or null when the layer is not on for this game. */
    static String statusLine(Context ctx, String gameId) {
        if (gameId == null || !gameId.equals(appliedGame)) return null;
        Map<String, String> m = status(ctx);
        if (m.isEmpty()) {
            return "Layer: switched on, no report yet. If this stays after the game shows its "
                    + "picture, the layer did not load (send logs).";
        }
        long age = Long.MAX_VALUE;
        try {
            age = System.currentTimeMillis() / 1000 - Long.parseLong(m.get("time"));
        } catch (Throwable ignored) {
        }
        String fps = m.get("fps");
        String sc = m.get("swapchain");
        StringBuilder b = new StringBuilder("Layer: loaded");
        if (sc != null && !sc.equals("0x0")) b.append(" · swapchain ").append(sc);
        if (fps != null && age < 5) b.append(" · ").append(fps).append(" fps");
        if (age >= 5) b.append(" · not presenting right now");
        if ("on".equals(m.get("upscale"))) {
            b.append("\nFSR 1: ").append(sc).append(" → ").append(m.get("output"));
        } else {
            String note = m.get("note");
            if (note != null && !note.isEmpty()) b.append("\n").append(note);
        }
        String app = m.get("app");
        if (app != null && !app.isEmpty()) b.append("\nApp: ").append(app);
        return b.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void remove(List<String> env, String key) {
        String prefix = key + "=";
        env.removeIf(kv -> kv != null && kv.startsWith(prefix));
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }
}
