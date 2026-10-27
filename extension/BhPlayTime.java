package app.revanced.extension.gamehub;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * BhPlayTime — per-game play time, measured on the device.
 *
 * Recording (runs in the :wine process): WineActivity.onResume/onPause call
 * {@link #onGameResume} / {@link #onGamePause} (smali patch in
 * patches/smali_classes15/com/xj/winemu/WineActivity.smali). Time counts only
 * while the game screen is in the foreground, and is flushed to disk every
 * {@link #FLUSH_SECONDS} s, so a crash or force-stop loses at most that much.
 *
 * The game is identified by WineActivity's WineActivityData field (a = gameId,
 * h = gameName, e = isLaunchDesktop). The gameId is the server id ("95389")
 * or the local id ("local_<uuid>"), the same ids GameDetailEntity exposes via
 * getId() / getLocalGameId().
 *
 * Storage: one properties file per game in files/bh_playtime/, written by the
 * :wine process (temp file + rename) and read by the launcher process.
 *
 * Display: {@link #onDetail} adds a label to GameDetailActivity (build.yml
 * patch at the top of GameDetailActivity.T3), and BhPlayTimeActivity lists
 * every game (HomeLeftMenuDialog menu item).
 */
public final class BhPlayTime {

    private static final String TAG = "BhPlayTime";
    private static final String DIR = "bh_playtime";
    private static final long FLUSH_SECONDS = 30;
    private static final String LABEL_TAG = "bh_playtime_label";

    private static final String K_ID = "id";
    private static final String K_NAME = "name";
    private static final String K_TOTAL = "total_ms";
    private static final String K_LAST = "last_played";

    /** One game's record. */
    public static final class Entry {
        public String id;
        public String name;
        public long totalMs;
        public long lastPlayed;
    }

    private BhPlayTime() {}

    // ── Recording (:wine process) ────────────────────────────────────────────

    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor();
    private static ScheduledFuture<?> ticker;
    private static Context appCtx;
    private static String curId;
    private static String curName;
    private static long lastMark;
    private static boolean running;

    public static synchronized void onGameResume(Activity activity) {
        try {
            Object data = wineActivityData(activity);
            if (data == null) return;
            if (Boolean.TRUE.equals(readField(data, "e"))) return; // container desktop, not a game
            Object id = readField(data, "a");
            if (!(id instanceof String) || ((String) id).isEmpty()) return;

            appCtx = activity.getApplicationContext();
            curId = (String) id;
            Object name = readField(data, "h");
            curName = name instanceof String ? (String) name : null;
            BhCrashReporter.onGameIdentified(appCtx, curId, curName);
            lastMark = SystemClock.elapsedRealtime();
            running = true;
            if (ticker != null) ticker.cancel(false);
            ticker = IO.scheduleWithFixedDelay(BhPlayTime::tick, FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS);
            Log.i(TAG, "tracking " + curId + " (" + curName + ")");
        } catch (Throwable t) {
            Log.w(TAG, "onGameResume failed", t);
        }
    }

    public static synchronized void onGamePause(Activity activity) {
        try {
            if (!running) return;
            running = false;
            if (ticker != null) {
                ticker.cancel(false);
                ticker = null;
            }
            // Capture the elapsed time now: a quick resume would reset lastMark
            // before a queued task could read it.
            long elapsed = SystemClock.elapsedRealtime() - lastMark;
            Context ctx = appCtx;
            String id = curId;
            String name = curName;
            IO.execute(() -> add(ctx, id, name, elapsed));
        } catch (Throwable t) {
            Log.w(TAG, "onGamePause failed", t);
        }
    }

    /** Periodic flush while the game is in the foreground. Runs on IO. */
    private static void tick() {
        Context ctx;
        String id;
        String name;
        long elapsed;
        synchronized (BhPlayTime.class) {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            elapsed = now - lastMark;
            lastMark = now;
            ctx = appCtx;
            id = curId;
            name = curName;
        }
        add(ctx, id, name, elapsed);
    }

    /** Adds elapsed ms to a game's record. Runs on IO only (single thread). */
    private static void add(Context ctx, String id, String name, long elapsed) {
        if (elapsed <= 0 || ctx == null || id == null) return;
        try {
            Entry e = read(ctx, id);
            if (e == null) {
                e = new Entry();
                e.id = id;
            }
            if (name != null && !name.isEmpty()) e.name = name;
            e.totalMs += elapsed;
            e.lastPlayed = System.currentTimeMillis();
            write(ctx, e);
            recordSession(ctx, id, e.lastPlayed - elapsed, e.lastPlayed, elapsed);
            BhCrashReporter.onAlive(ctx);
        } catch (Throwable t) {
            Log.w(TAG, "flush failed for " + id, t);
        }
    }

    private static Object wineActivityData(Activity activity) {
        for (Class<?> c = activity.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getName().equals("com.xj.winemu.api.bean.WineActivityData")) {
                    try {
                        f.setAccessible(true);
                        return f.get(activity);
                    } catch (Throwable ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static Object readField(Object obj, String name) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Storage ──────────────────────────────────────────────────────────────

    private static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), DIR);
    }

    private static File file(Context ctx, String id) {
        return new File(dir(ctx), id.replaceAll("[^A-Za-z0-9._-]", "_") + ".properties");
    }

    public static Entry read(Context ctx, String id) {
        if (id == null || id.isEmpty()) return null;
        return read(file(ctx, id));
    }

    private static Entry read(File f) {
        if (!f.isFile()) return null;
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (Throwable t) {
            return null;
        }
        Entry e = new Entry();
        e.id = p.getProperty(K_ID);
        e.name = p.getProperty(K_NAME);
        e.totalMs = parseLong(p.getProperty(K_TOTAL));
        e.lastPlayed = parseLong(p.getProperty(K_LAST));
        return e.id == null ? null : e;
    }

    private static void write(Context ctx, Entry e) throws Exception {
        File d = dir(ctx);
        if (!d.isDirectory() && !d.mkdirs()) throw new IllegalStateException("cannot create " + d);
        Properties p = new Properties();
        p.setProperty(K_ID, e.id);
        if (e.name != null) p.setProperty(K_NAME, e.name);
        p.setProperty(K_TOTAL, Long.toString(e.totalMs));
        p.setProperty(K_LAST, Long.toString(e.lastPlayed));
        File dst = file(ctx, e.id);
        File tmp = new File(d, dst.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            p.store(out, null);
        }
        if (!tmp.renameTo(dst)) throw new IllegalStateException("rename failed for " + dst);
    }

    // ── Sessions ─────────────────────────────────────────────────────────────
    //
    // files/bh_playtime/<id>.sessions, one line per session: "start,end,played"
    // (wall-clock ms, ms). A segment starting within SESSION_GAP_MS of the last
    // session's end extends it (pausing to answer a message is the same
    // session); otherwise it starts a new one. History started with the
    // build that added it: earlier play time is only in the totals.

    private static final long SESSION_GAP_MS = 5 * 60_000;
    private static final int MAX_SESSIONS = 200;

    /** One play session: wall-clock start/end and time actually on screen. */
    public static final class Session {
        public long start;
        public long end;
        public long playedMs;
    }

    private static File sessionsFile(Context ctx, String id) {
        return new File(dir(ctx), id.replaceAll("[^A-Za-z0-9._-]", "_") + ".sessions");
    }

    /** Sessions of one game, oldest first. */
    public static List<Session> sessions(Context ctx, String id) {
        List<Session> out = new ArrayList<>();
        File f = sessionsFile(ctx, id);
        if (!f.isFile()) return out;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split(",");
                if (p.length != 3) continue;
                Session s = new Session();
                s.start = parseLong(p[0]);
                s.end = parseLong(p[1]);
                s.playedMs = parseLong(p[2]);
                if (s.start > 0 && s.playedMs > 0) out.add(s);
            }
        } catch (Throwable t) {
            Log.w(TAG, "reading sessions of " + id + " failed", t);
        }
        return out;
    }

    private static void writeSessions(Context ctx, String id, List<Session> list) throws Exception {
        while (list.size() > MAX_SESSIONS) list.remove(0);
        File dst = sessionsFile(ctx, id);
        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        try (java.io.PrintWriter w = new java.io.PrintWriter(new FileOutputStream(tmp))) {
            for (Session s : list) w.println(s.start + "," + s.end + "," + s.playedMs);
        }
        if (!tmp.renameTo(dst)) throw new IllegalStateException("rename failed for " + dst);
    }

    private static void recordSession(Context ctx, String id, long segStart, long segEnd, long played) {
        try {
            List<Session> list = sessions(ctx, id);
            Session last = list.isEmpty() ? null : list.get(list.size() - 1);
            if (last != null && segStart - last.end <= SESSION_GAP_MS) {
                last.end = segEnd;
                last.playedMs += played;
            } else {
                Session s = new Session();
                s.start = segStart;
                s.end = segEnd;
                s.playedMs = played;
                list.add(s);
            }
            writeSessions(ctx, id, list);
        } catch (Throwable t) {
            Log.w(TAG, "recording session of " + id + " failed", t);
        }
    }

    /** Time played in sessions that ended after {@code since}. */
    public static long playedSince(List<Session> list, long since) {
        long sum = 0;
        for (Session s : list) if (s.end >= since) sum += s.playedMs;
        return sum;
    }

    // ── Export / import (BhSettingsExporter, local exports only) ─────────────

    /** Play time of one game as JSON, or null if none was recorded. */
    public static org.json.JSONObject toJson(Context ctx, String id) {
        try {
            Entry e = read(ctx, id);
            if (e == null || e.totalMs <= 0) return null;
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("total_ms", e.totalMs);
            o.put("last_played", e.lastPlayed);
            org.json.JSONArray arr = new org.json.JSONArray();
            for (Session s : sessions(ctx, id)) {
                arr.put(new org.json.JSONArray().put(s.start).put(s.end).put(s.playedMs));
            }
            o.put("sessions", arr);
            return o;
        } catch (Throwable t) {
            Log.w(TAG, "toJson failed for " + id, t);
            return null;
        }
    }

    /**
     * Merges exported play time into a game (possibly under a new id, e.g.
     * after a reinstall gave local games new ids): keeps the larger total and
     * the later last-played day, and adds sessions not already present.
     */
    public static void mergeJson(Context ctx, String id, String name, org.json.JSONObject o) {
        try {
            Entry e = read(ctx, id);
            if (e == null) {
                e = new Entry();
                e.id = id;
            }
            if (name != null && !name.isEmpty()) e.name = name;
            e.totalMs = Math.max(e.totalMs, o.optLong("total_ms"));
            e.lastPlayed = Math.max(e.lastPlayed, o.optLong("last_played"));
            write(ctx, e);

            List<Session> list = sessions(ctx, id);
            java.util.Set<Long> starts = new java.util.HashSet<>();
            for (Session s : list) starts.add(s.start);
            org.json.JSONArray arr = o.optJSONArray("sessions");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                org.json.JSONArray a = arr.optJSONArray(i);
                if (a == null || a.length() != 3 || starts.contains(a.optLong(0))) continue;
                Session s = new Session();
                s.start = a.optLong(0);
                s.end = a.optLong(1);
                s.playedMs = a.optLong(2);
                list.add(s);
            }
            list.sort((a, b) -> Long.compare(a.start, b.start));
            writeSessions(ctx, id, list);
        } catch (Throwable t) {
            Log.w(TAG, "mergeJson failed for " + id, t);
        }
    }

    /** All recorded games, most played first. */
    public static List<Entry> all(Context ctx) {
        List<Entry> out = new ArrayList<>();
        File[] files = dir(ctx).listFiles((d, n) -> n.endsWith(".properties"));
        if (files != null) {
            for (File f : files) {
                Entry e = read(f);
                if (e != null && e.totalMs > 0) out.add(e);
            }
        }
        out.sort((a, b) -> Long.compare(b.totalMs, a.totalMs));
        return out;
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    /** "45 m", "12 h 34 m", "under 1 m". */
    public static String formatDuration(long ms) {
        long minutes = ms / 60_000;
        if (minutes < 1) return "under 1 m";
        long h = minutes / 60;
        long m = minutes % 60;
        return h == 0 ? m + " m" : h + " h " + m + " m";
    }

    /** "today", "yesterday", "12 Oct", "12 Oct 2025". */
    public static String formatDay(long time) {
        if (time <= 0) return "never";
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(time);
        Calendar now = Calendar.getInstance();
        if (sameDay(then, now)) return "today";
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(then, now)) return "yesterday";
        String pattern = then.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR) ? "d MMM" : "d MMM yyyy";
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(time));
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    // ── Game details page (launcher process) ─────────────────────────────────

    /** Called with GameDetailActivity and its GameDetailEntity (may be null). */
    public static void onDetail(Activity activity, Object entity) {
        try {
            if (activity == null || entity == null) return;
            Entry e = null;
            Object serverId = call(entity, "getId");
            if (serverId instanceof Integer && (Integer) serverId > 0) {
                e = read(activity, String.valueOf(serverId));
            }
            if (e == null) {
                Object localId = call(entity, "getLocalGameId");
                if (localId instanceof String) e = read(activity, (String) localId);
            }
            String text = e == null || e.totalMs <= 0
                    ? "Not played yet"
                    : "Played " + formatDuration(e.totalMs) + "  ·  Last played " + formatDay(e.lastPlayed);
            activity.runOnUiThread(() -> showLabel(activity, text));
            BhKnownFixes.onDetail(activity, entity);
        } catch (Throwable t) {
            Log.w(TAG, "onDetail failed", t);
        }
    }

    private static void showLabel(Activity activity, String text) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return;
        View existing = content.findViewWithTag(LABEL_TAG);
        TextView label;
        if (existing instanceof TextView) {
            label = (TextView) existing;
        } else {
            label = new TextView(activity);
            label.setTag(LABEL_TAG);
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            label.setTypeface(null, Typeface.BOLD);
            int padH = dp(activity, 12), padV = dp(activity, 6);
            label.setPadding(padH, padV, padH, padV);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xB3000000);
            bg.setCornerRadius(dp(activity, 16));
            label.setBackground(bg);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            lp.topMargin = dp(activity, 56);
            lp.rightMargin = dp(activity, 24);
            content.addView(label, lp);
        }
        label.setText("🕒  " + text);
    }

    private static Object call(Object obj, String method) {
        try {
            Method m = obj.getClass().getMethod(method);
            return m.invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
