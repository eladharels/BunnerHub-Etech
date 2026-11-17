package app.revanced.extension.gamehub;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BhFsr — per-game AMD FSR 1 upscaling through Wine's fullscreen hack
 * (Proton / GE-Proton: WINE_FULLSCREEN_FSR). The game renders at a lower
 * fullscreen resolution and Wine's Vulkan path upscales it to the Wine screen
 * size with FSR 1 (EASU + RCAS sharpening). Works for Vulkan swapchains, so
 * DXVK (DX9-11) and VKD3D-Proton (DX12) games; not for OpenGL.
 *
 * GameHub always exports WINE_DISABLE_FULLSCREEN_HACK=1. FSR lives inside
 * that hack, so when FSR is on for the game this removes it and adds the
 * WINE_FULLSCREEN_FSR* variables. Settings only take effect at launch.
 *
 * Called from {@link BhWineLog#env} (ProgramLauncher.e() smali hook) for
 * every Wine launcher. Every launch also checks the Wine build's unix
 * libraries for the FSR variable names, so the panel can tell whether this
 * Wine supports it. While FSR is on, the session's Wine output is captured
 * ({@link BhWineLog}) with DXVK_LOG_LEVEL=info, and {@link #renderSize()}
 * reads DXVK's "Buffer size: WxH" line from it: the size the game renders at.
 */
public final class BhFsr {

    private static final String TAG = "BhFsr";
    private static final String PREFS = "bh_fsr";

    static final String V_FSR = "WINE_FULLSCREEN_FSR";
    static final String V_STRENGTH = "WINE_FULLSCREEN_FSR_STRENGTH";
    static final String V_MODE = "WINE_FULLSCREEN_FSR_MODE";
    static final String V_HACK_OFF = "WINE_DISABLE_FULLSCREEN_HACK";

    public static final int DEFAULT_STRENGTH = 2;
    public static final int MAX_STRENGTH = 5;

    /** FSR 1 quality modes (per-axis scale factors from AMD's FSR 1 docs). */
    public enum Mode {
        GAME("Game's own setting", null, 1f),
        ULTRA("Ultra quality (77%)", "ultra", 1.3f),
        QUALITY("Quality (67%)", "quality", 1.5f),
        BALANCED("Balanced (59%)", "balanced", 1.7f),
        PERFORMANCE("Performance (50%)", "performance", 2f);

        public final String label;
        final String value;
        public final float scale;

        Mode(String label, String value, float scale) {
            this.label = label;
            this.value = value;
            this.scale = scale;
        }
    }

    /** Result of checking the Wine build for FSR. */
    public static final class Support {
        public static final int UNKNOWN = 0, YES = 1, NO = 2;
        public final int state;
        public final boolean mode, strength;
        public final String wineDir;

        Support(int state, boolean mode, boolean strength, String wineDir) {
            this.state = state;
            this.mode = mode;
            this.strength = strength;
            this.wineDir = wineDir;
        }
    }

    /** What the last Wine launch of this process actually got. */
    public static final class Applied {
        public final String gameId;
        public final boolean on;
        public final int strength;
        public final Mode mode;

        Applied(String gameId, boolean on, int strength, Mode mode) {
            this.gameId = gameId;
            this.on = on;
            this.strength = strength;
            this.mode = mode;
        }
    }

    private static volatile Support support;
    private static volatile Applied applied;

    private BhFsr() {}

    // ── Settings (per game) ─────────────────────────────────────────────────

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context ctx, String id) {
        return id != null && prefs(ctx).getBoolean("on_" + id, false);
    }

    public static void setEnabled(Context ctx, String id, boolean on) {
        if (id != null) prefs(ctx).edit().putBoolean("on_" + id, on).apply();
    }

    public static int strength(Context ctx, String id) {
        int s = id == null ? DEFAULT_STRENGTH : prefs(ctx).getInt("strength_" + id, DEFAULT_STRENGTH);
        return Math.max(0, Math.min(MAX_STRENGTH, s));
    }

    public static void setStrength(Context ctx, String id, int s) {
        if (id != null) prefs(ctx).edit().putInt("strength_" + id, Math.max(0, Math.min(MAX_STRENGTH, s))).apply();
    }

    public static Mode mode(Context ctx, String id) {
        String m = id == null ? null : prefs(ctx).getString("mode_" + id, null);
        if (m != null) {
            for (Mode x : Mode.values()) if (x.name().equals(m)) return x;
        }
        return Mode.GAME;
    }

    public static void setMode(Context ctx, String id, Mode m) {
        if (id != null) prefs(ctx).edit().putString("mode_" + id, m.name()).apply();
    }

    public static Support support() {
        return support;
    }

    public static Applied applied() {
        return applied;
    }

    // ── Launch hook ─────────────────────────────────────────────────────────

    /** BhWineLog.env: adjusts the environment of every Wine launcher. */
    public static String[] env(String[] env) {
        try {
            Context ctx = currentApp();
            if (ctx == null || env == null) return env;
            Support s = detect(ctx, env);
            String id = BhPlayTime.currentGameId();
            if (id == null || !enabled(ctx, id) || s.state == Support.NO) {
                applied = new Applied(id, false, 0, Mode.GAME);
                return env;
            }
            int strength = strength(ctx, id);
            Mode mode = mode(ctx, id);
            if (!s.mode) mode = Mode.GAME;

            List<String> out = new ArrayList<>(Arrays.asList(env));
            remove(out, V_HACK_OFF);
            remove(out, V_FSR);
            remove(out, V_STRENGTH);
            remove(out, V_MODE);
            out.add(V_FSR + "=1");
            out.add(V_STRENGTH + "=" + strength);
            if (mode.value != null) out.add(V_MODE + "=" + mode.value);
            // GameHub sets "warn"; "info" is needed for DXVK's swapchain size line.
            String level = get(out, "DXVK_LOG_LEVEL");
            if (level != null && !level.equals("info") && !level.equals("debug") && !level.equals("trace")) {
                remove(out, "DXVK_LOG_LEVEL");
                out.add("DXVK_LOG_LEVEL=info");
            }
            applied = new Applied(id, true, strength, mode);
            Log.i(TAG, "FSR on for " + id + ": strength " + strength + ", mode " + mode);
            return out.toArray(new String[0]);
        } catch (Throwable t) {
            Log.w(TAG, "env hook failed", t);
            return env;
        }
    }

    /** True when this environment has FSR switched on (BhWineLog then captures output). */
    static boolean isOn(String[] env) {
        if (env == null) return false;
        for (String kv : env) {
            if ((V_FSR + "=1").equals(kv)) return true;
        }
        return false;
    }

    private static void remove(List<String> env, String key) {
        String prefix = key + "=";
        for (Iterator<String> it = env.iterator(); it.hasNext(); ) {
            String kv = it.next();
            if (kv != null && kv.startsWith(prefix)) it.remove();
        }
    }

    private static String get(List<String> env, String key) {
        String prefix = key + "=";
        for (String kv : env) {
            if (kv != null && kv.startsWith(prefix)) return kv.substring(prefix.length()).trim();
        }
        return null;
    }

    // ── Wine support check ──────────────────────────────────────────────────

    private static final byte[][] NEEDLES = {
            (V_FSR + "\0").getBytes(),
            (V_MODE + "\0").getBytes(),
            (V_STRENGTH + "\0").getBytes(),
    };

    /**
     * GameHub's PATH is "<rootfs>/bin:<wine>/<abi>/bin:/bin". The last "x/bin"
     * with a "x/lib*\/wine" (or one level up) is the Wine install; its winex11 / winevulkan
     * / win32u unix libraries are searched for the FSR variable names.
     * Cached per install (path, sizes, mtimes).
     */
    private static Support detect(Context ctx, String[] env) {
        String path = null;
        for (String kv : env) {
            if (kv != null && kv.startsWith("PATH=")) path = kv.substring(5);
        }
        if (path == null) return remember(new Support(Support.UNKNOWN, false, false, null));

        // Last entry first: Wine's own bin comes after the rootfs one.
        String[] dirs = path.split(":");
        for (int d = dirs.length - 1; d >= 0; d--) {
            String dir = dirs[d];
            if (!dir.endsWith("/bin") || dir.equals("/bin")) continue;
            File root = new File(dir).getParentFile();
            if (root == null) continue;
            List<File> libs = new ArrayList<>();
            // <wine>/<abi>/lib/wine/..., or <wine>/lib/wine/... next to <abi>/bin
            for (File base : new File[]{root, root.getParentFile()}) {
                if (base == null) continue;
                for (String lib : new String[]{"lib", "lib64"}) {
                    collect(new File(new File(base, lib), "wine"), libs, 0);
                }
            }
            if (libs.isEmpty()) continue;

            StringBuilder key = new StringBuilder(root.getAbsolutePath());
            for (File f : libs) key.append('|').append(f.getName()).append(f.length()).append(':').append(f.lastModified());
            SharedPreferences p = prefs(ctx);
            int found;
            if (key.toString().equals(p.getString("sup_key", null))) {
                found = p.getInt("sup_val", 0);
            } else {
                found = 0;
                for (File f : libs) found |= scan(f);
                p.edit().putString("sup_key", key.toString()).putInt("sup_val", found).apply();
                Log.i(TAG, "checked " + libs.size() + " Wine libraries in " + root + ": " + found);
            }
            boolean fsr = (found & 1) != 0;
            return remember(new Support(fsr ? Support.YES : Support.NO,
                    (found & 2) != 0, (found & 4) != 0, root.getAbsolutePath()));
        }
        return remember(new Support(Support.UNKNOWN, false, false, null));
    }

    private static Support remember(Support s) {
        support = s;
        return s;
    }

    private static void collect(File dir, List<File> out, int depth) {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files);
        for (File f : files) {
            if (f.isDirectory()) {
                if (depth < 2) collect(f, out, depth + 1);
                continue;
            }
            String n = f.getName().toLowerCase(Locale.US);
            if (n.endsWith(".so") && (n.startsWith("winex11") || n.startsWith("winevulkan") || n.startsWith("win32u"))) {
                out.add(f);
            }
        }
    }

    /** Bit i set when NEEDLES[i] occurs in the file. */
    private static int scan(File f) {
        int found = 0;
        int overlap = 64;
        byte[] buf = new byte[1 << 20];
        try (InputStream in = new FileInputStream(f)) {
            int keep = 0;
            int n;
            while ((n = in.read(buf, keep, buf.length - keep)) > 0) {
                int len = keep + n;
                for (int i = 0; i < NEEDLES.length; i++) {
                    if ((found & (1 << i)) == 0 && indexOf(buf, len, NEEDLES[i]) >= 0) found |= 1 << i;
                }
                if (found == (1 << NEEDLES.length) - 1) break;
                keep = Math.min(overlap, len);
                System.arraycopy(buf, len - keep, buf, 0, keep);
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot read " + f, t);
        }
        return found;
    }

    private static int indexOf(byte[] hay, int len, byte[] needle) {
        byte first = needle[0];
        outer:
        for (int i = 0; i <= len - needle.length; i++) {
            if (hay[i] != first) continue;
            for (int j = 1; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // ── Render size (from DXVK's log) ───────────────────────────────────────

    private static final Pattern BUFFER = Pattern.compile("Buffer size:\\s*(\\d+)\\s*x\\s*(\\d+)");
    private static final int CHUNK = 256 * 1024;
    private static final int OVERLAP = 128;

    private static File scannedFile;
    private static long scannedTo;
    private static int[] lastSize;

    /**
     * {width, height} of the game's latest swapchain, or null if not seen
     * yet. Reads only what the log gained since the last call, so call it
     * off the UI thread.
     */
    public static synchronized int[] renderSize() {
        File log = BhWineLog.currentSessionFile();
        if (log == null || !log.isFile()) return null;
        if (!log.equals(scannedFile)) {
            scannedFile = log;
            scannedTo = 0;
            lastSize = null;
        }
        try (RandomAccessFile f = new RandomAccessFile(log, "r")) {
            long len = f.length();
            if (len < scannedTo) {
                scannedTo = 0;
                lastSize = null;
            }
            byte[] buf = new byte[CHUNK + OVERLAP];
            while (scannedTo < len) {
                long from = Math.max(0, scannedTo - OVERLAP);
                int n = (int) Math.min(buf.length, len - from);
                f.seek(from);
                f.readFully(buf, 0, n);
                Matcher m = BUFFER.matcher(new String(buf, 0, n, "ISO-8859-1"));
                while (m.find()) {
                    lastSize = new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
                }
                scannedTo = from + n;
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot read " + log, t);
        }
        return lastSize;
    }

    private static Context currentApp() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            return (Context) at.getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
