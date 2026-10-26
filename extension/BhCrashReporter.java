package app.revanced.extension.gamehub;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * BhCrashReporter — turns game crashes, hangs and app crashes into report
 * files in /sdcard/BunnerHub-Etech/reports/, and tells the user.
 *
 * Game sessions. Wine appends every session's stdout/stderr to
 * /sdcard/wine.log (set up by GameHub's Wine runtime, not by the APK).
 * <ol>
 *   <li>{@link #onLaunch()} (from BhWineLog's ProgramLauncher.e() hook, :wine
 *       process, before any Wine process starts) writes a session marker with
 *       the current wine.log size and the start time.</li>
 *   <li>{@link #onGameIdentified} (from BhPlayTime.onGameResume) adds the game
 *       id and name.</li>
 *   <li>{@link #onLauncherResume} (LandscapeLauncherMainActivity.onResume,
 *       main process) finalizes the marker once the :wine process is gone:
 *       it scans the session's part of wine.log for decisive lines and, if it
 *       finds any (or the game stopped right after starting), writes a report
 *       zip plus a .txt summary and queues a notice. Using a marker means
 *       force-stops and crashes are covered, not only clean exits.</li>
 * </ol>
 * App crashes: {@link #installCrashHandler} wraps the default uncaught
 * exception handler in both processes and writes app-crash-*.txt.
 *
 * Notices are shown as a dialog on the next launcher resume. Reports are
 * listed by BhReportsActivity (☰ → Crash Reports).
 */
public final class BhCrashReporter {

    private static final String TAG = "BhCrashReporter";
    static final String REPORT_DIR = "BunnerHub-Etech/reports";
    private static final String STATE_DIR = "bh_reports";
    private static final String MARKER = "session.properties";
    private static final long LAUNCH_WINDOW_MS = 120_000;
    private static final long QUICK_EXIT_MS = 20_000;
    private static final int MAX_LOG_BYTES = 8 * 1024 * 1024;
    private static final int MAX_MATCHES = 12;

    /** Lines worth reporting, most decisive first: {needle, headline}. */
    private static final String[][] PATTERNS = {
            {"Unhandled page fault", "Crash"},
            {"Unhandled exception", "Crash"},
            {"Unhandled stack overflow", "Crash"},
            {"VK_ERROR_DEVICE_LOST", "GPU device lost"},
            {"DxvkError", "DXVK error"},
            {"RtlpWaitForCriticalSection", "Hang (lock wait timed out)"},
            {"err:module:import_dll", "Missing DLL"},
            {"err:module:LdrInitializeThunk", "Failed to start"},
            {"err:module:load_dll", "Failed to load a DLL"},
    };

    private static volatile boolean handlerInstalled;

    private BhCrashReporter() {}

    // ── Session tracking ─────────────────────────────────────────────────────

    /** :wine process, before each Wine launcher starts. */
    public static synchronized void onLaunch() {
        try {
            Context ctx = currentApp();
            if (ctx == null) return;
            installCrashHandler(ctx);
            Properties m = readMarker(ctx);
            long now = System.currentTimeMillis();
            if (m != null) {
                long started = parseLong(m.getProperty("started"));
                if (now - started < LAUNCH_WINDOW_MS) return; // same session, later launcher
                finalizeSession(ctx, m); // an older session nobody finalized
            }
            Properties fresh = new Properties();
            fresh.setProperty("started", Long.toString(now));
            fresh.setProperty("offset", Long.toString(wineLog().length()));
            writeMarker(ctx, fresh);
        } catch (Throwable t) {
            Log.w(TAG, "onLaunch failed", t);
        }
    }

    /** :wine process, when the game screen first shows. */
    public static synchronized void onGameIdentified(Context ctx, String id, String name) {
        try {
            installCrashHandler(ctx);
            Properties m = readMarker(ctx);
            if (m == null || m.getProperty("id") != null) return;
            m.setProperty("id", id);
            if (name != null) m.setProperty("name", name);
            writeMarker(ctx, m);
        } catch (Throwable t) {
            Log.w(TAG, "onGameIdentified failed", t);
        }
    }

    /** Main process, LandscapeLauncherMainActivity.onResume. */
    public static void onLauncherResume(Activity activity) {
        try {
            Context ctx = activity.getApplicationContext();
            installCrashHandler(ctx);
            new Thread(() -> {
                try {
                    synchronized (BhCrashReporter.class) {
                        Properties m = readMarker(ctx);
                        if (m != null && !wineRunning(ctx)) finalizeSession(ctx, m);
                    }
                    List<Properties> notices = takeNotices(ctx);
                    if (!notices.isEmpty()) {
                        new Handler(Looper.getMainLooper()).post(() -> showNotices(activity, notices));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "launcher check failed", t);
                }
            }, "BhCrashReporter").start();
        } catch (Throwable t) {
            Log.w(TAG, "onLauncherResume failed", t);
        }
    }

    private static boolean wineRunning(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> procs = am == null ? null : am.getRunningAppProcesses();
        if (procs == null) return false;
        for (ActivityManager.RunningAppProcessInfo p : procs) {
            if (p.processName != null && p.processName.endsWith(":wine")) return true;
        }
        return false;
    }

    // ── Finalizing a session ─────────────────────────────────────────────────

    private static void finalizeSession(Context ctx, Properties m) {
        deleteMarker(ctx);
        File log = wineLog();
        long started = parseLong(m.getProperty("started"));
        long offset = parseLong(m.getProperty("offset"));
        long size = log.length();
        if (offset > size) offset = 0; // wine.log was truncated
        long ended = log.lastModified();

        byte[] slice = readSlice(log, offset, size);
        List<String> matches = new ArrayList<>();
        String headline = scan(slice, matches);
        // Only when this session wrote to wine.log; an untouched file's time is an older session's.
        boolean quickExit = size > offset && ended >= started && ended - started < QUICK_EXIT_MS;
        if (headline == null && !quickExit) return; // a normal session

        String id = m.getProperty("id");
        String name = m.getProperty("name", id != null ? id : "Game");
        if (headline == null) headline = "Stopped right after starting";
        String firstLine = matches.isEmpty() ? "" : matches.get(0);

        StringBuilder sum = new StringBuilder();
        sum.append("Game: ").append(name).append('\n');
        if (id != null) sum.append("Game id: ").append(id).append('\n');
        sum.append("Result: ").append(headline).append('\n');
        sum.append("Started: ").append(stamp(started, "yyyy-MM-dd HH:mm:ss")).append('\n');
        if (ended > 0) sum.append("Last log write: ").append(stamp(ended, "yyyy-MM-dd HH:mm:ss")).append('\n');
        if (id != null) {
            BhPlayTime.Entry pt = BhPlayTime.read(ctx, id);
            if (pt != null) sum.append("Total play time: ").append(BhPlayTime.formatDuration(pt.totalMs)).append('\n');
        }
        sum.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE).append('\n');
        sum.append("App: ").append(appVersion(ctx)).append('\n');
        sum.append("\nDecisive lines from wine.log:\n");
        if (matches.isEmpty()) sum.append("  (none; the game exited within ")
                .append(QUICK_EXIT_MS / 1000).append(" s of starting)\n");
        for (String line : matches) sum.append("  ").append(line).append('\n');

        String base = "report-" + stamp(started, "yyyyMMdd-HHmmss") + "-" + safe(name);
        File dir = reportDir();
        File zip = new File(dir, base + ".zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            put(z, "summary.txt", sum.toString().getBytes(StandardCharsets.UTF_8));
            put(z, "wine.log", slice);
            if (id != null) {
                putFile(z, "launch-params.txt", new File(ctx.getFilesDir(), "pcLaunchLog/launchLog" + id + ".txt"));
                putFile(z, "game-settings.xml", new File(ctx.getFilesDir().getParentFile(),
                        "shared_prefs/pc_g_setting" + id + ".xml"));
            }
            File wineSession = latestBhWineLog(started);
            if (wineSession != null) putFile(z, "bh-wine-session.log", wineSession);
            put(z, "logcat.txt", logcat());
        } catch (Throwable t) {
            Log.w(TAG, "writing " + zip + " failed", t);
        }
        writeText(new File(dir, base + ".txt"), sum.toString());
        queueNotice(ctx, name + ": " + headline, firstLine, zip.getAbsolutePath());
        Log.i(TAG, "report " + zip + " (" + headline + ")");
    }

    /** Returns the headline of the most decisive match and fills {@code out}. */
    private static String scan(byte[] slice, List<String> out) {
        int best = Integer.MAX_VALUE;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.io.ByteArrayInputStream(slice), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                for (int i = 0; i < PATTERNS.length; i++) {
                    if (line.contains(PATTERNS[i][0])) {
                        if (out.size() < MAX_MATCHES && !out.contains(line.trim())) out.add(line.trim());
                        best = Math.min(best, i);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // Most decisive line first.
        if (best != Integer.MAX_VALUE) {
            String needle = PATTERNS[best][0];
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).contains(needle)) {
                    out.add(0, out.remove(i));
                    break;
                }
            }
            return PATTERNS[best][1];
        }
        return null;
    }

    private static byte[] readSlice(File f, long from, long to) {
        if (!f.isFile() || to <= from) return new byte[0];
        long start = Math.max(from, to - MAX_LOG_BYTES);
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] buf = new byte[(int) (to - start)];
            raf.seek(start);
            raf.readFully(buf);
            return buf;
        } catch (Throwable t) {
            Log.w(TAG, "reading " + f + " failed", t);
            return new byte[0];
        }
    }

    private static File latestBhWineLog(long started) {
        File[] logs = new File(Environment.getExternalStorageDirectory(), "BunnerHub-Etech/logs")
                .listFiles((d, n) -> n.startsWith("wine-") && n.endsWith(".log"));
        if (logs == null) return null;
        File best = null;
        for (File f : logs) {
            if (f.lastModified() >= started && (best == null || f.lastModified() > best.lastModified())) best = f;
        }
        return best;
    }

    private static byte[] logcat() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-t", "3000"});
            try (InputStream in = p.getInputStream()) {
                return readAll(in);
            } finally {
                p.destroy();
            }
        } catch (Throwable t) {
            return ("logcat unavailable: " + t).getBytes(StandardCharsets.UTF_8);
        }
    }

    // ── App crashes ──────────────────────────────────────────────────────────

    public static void installCrashHandler(Context ctx) {
        if (handlerInstalled || ctx == null) return;
        synchronized (BhCrashReporter.class) {
            if (handlerInstalled) return;
            final Context app = ctx.getApplicationContext();
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
                try {
                    writeAppCrash(app, thread, ex);
                } catch (Throwable ignored) {
                }
                if (previous != null) previous.uncaughtException(thread, ex);
            });
            handlerInstalled = true;
        }
    }

    private static void writeAppCrash(Context ctx, Thread thread, Throwable ex) {
        long now = System.currentTimeMillis();
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        String process = Build.VERSION.SDK_INT >= 28 ? Application.getProcessName() : "?";
        String sum = "App crash\n"
                + "Time: " + stamp(now, "yyyy-MM-dd HH:mm:ss") + "\n"
                + "Process: " + process + "\n"
                + "Thread: " + thread.getName() + "\n"
                + "App: " + appVersion(ctx) + "\n"
                + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + "\n"
                + "\n" + sw;
        File f = new File(reportDir(), "app-crash-" + stamp(now, "yyyyMMdd-HHmmss") + ".txt");
        writeText(f, sum);
        queueNotice(ctx, "BunnerHub Etech crashed", String.valueOf(ex), f.getAbsolutePath());
    }

    // ── Notices ──────────────────────────────────────────────────────────────

    private static void queueNotice(Context ctx, String title, String line, String path) {
        try {
            Properties n = new Properties();
            n.setProperty("title", title);
            n.setProperty("line", line == null ? "" : line);
            n.setProperty("path", path);
            File f = new File(stateDir(ctx), "notice-" + System.currentTimeMillis() + ".properties");
            try (FileOutputStream out = new FileOutputStream(f)) {
                n.store(out, null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "queueNotice failed", t);
        }
    }

    private static List<Properties> takeNotices(Context ctx) {
        List<Properties> out = new ArrayList<>();
        File[] files = stateDir(ctx).listFiles((d, n) -> n.startsWith("notice-"));
        if (files == null) return out;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : files) {
            Properties p = new Properties();
            try (FileInputStream in = new FileInputStream(f)) {
                p.load(in);
                out.add(p);
            } catch (Throwable ignored) {
            }
            if (!f.delete()) Log.w(TAG, "cannot delete " + f);
        }
        return out;
    }

    private static void showNotices(Activity activity, List<Properties> notices) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Properties last = notices.get(notices.size() - 1);
        StringBuilder msg = new StringBuilder();
        String line = last.getProperty("line", "");
        if (!line.isEmpty()) msg.append(line).append("\n\n");
        msg.append("Report saved to ").append(last.getProperty("path")).append('.');
        if (notices.size() > 1) msg.append("\n\n").append(notices.size() - 1).append(" earlier report(s) too.");
        new AlertDialog.Builder(activity)
                .setTitle(last.getProperty("title"))
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .setNeutralButton("Open reports", (d, w) ->
                        activity.startActivity(new Intent(activity, BhReportsActivity.class)))
                .show();
    }

    // ── Files ────────────────────────────────────────────────────────────────

    static File wineLog() {
        return new File(Environment.getExternalStorageDirectory(), "wine.log");
    }

    static File reportDir() {
        File d = new File(Environment.getExternalStorageDirectory(), REPORT_DIR);
        if (!d.isDirectory() && !d.mkdirs()) Log.w(TAG, "cannot create " + d);
        return d;
    }

    private static File stateDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), STATE_DIR);
        if (!d.isDirectory() && !d.mkdirs()) Log.w(TAG, "cannot create " + d);
        return d;
    }

    private static Properties readMarker(Context ctx) {
        File f = new File(stateDir(ctx), MARKER);
        if (!f.isFile()) return null;
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(f)) {
            p.load(in);
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeMarker(Context ctx, Properties p) throws Exception {
        File dir = stateDir(ctx);
        File tmp = new File(dir, MARKER + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            p.store(out, null);
        }
        if (!tmp.renameTo(new File(dir, MARKER))) throw new IllegalStateException("rename failed");
    }

    private static void deleteMarker(Context ctx) {
        File f = new File(stateDir(ctx), MARKER);
        if (f.exists() && !f.delete()) Log.w(TAG, "cannot delete " + f);
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    private static void putFile(ZipOutputStream z, String name, File f) throws Exception {
        if (!f.isFile()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            put(z, name, readAll(in));
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static void writeText(File f, String text) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Log.w(TAG, "writing " + f + " failed", t);
        }
    }

    // ── Small helpers ────────────────────────────────────────────────────────

    private static Context currentApp() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            return (Context) at.getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String appVersion(Context ctx) {
        try {
            return ctx.getPackageName() + " "
                    + ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return ctx.getPackageName();
        }
    }

    private static String stamp(long time, String pattern) {
        return new SimpleDateFormat(pattern, Locale.US).format(new Date(time));
    }

    private static String safe(String s) {
        String r = s.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return r.isEmpty() ? "game" : (r.length() > 40 ? r.substring(0, 40) : r);
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
