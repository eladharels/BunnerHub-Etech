package app.revanced.extension.gamehub;

import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * BhWineLog — saves Wine/FEX stdout+stderr of a game session to
 * /sdcard/BunnerHub-Etech/logs/wine-<date>.log so launch failures can be
 * debugged from a file (adb pull, or a zip sent to a cloud session).
 *
 * Why: GameHub's own stdout/stderr callbacks (ProgramController ->
 * WinUIBridge -> LogHttpServer) never received the game's Wine output in
 * practice, and the app is a release build, so nothing under /data is
 * readable by adb.
 *
 * Switch: the per-game "Wine Debug Parameters" developer option. When it is
 * set, EnvironmentController exports it as WINEDEBUG instead of the stock
 * "-all", and this class turns capture on. Empty field = stock behaviour.
 *
 * Wiring: a smali patch in ProgramLauncher.e() (com.winemu.core.server.
 * environment.plugins) passes the env array through {@link #env} and the
 * argv through {@link #command} right before ProcessHelper.g() execs it.
 * Every Wine launcher of a session (explorer, services, the game) goes
 * through e(), so they all append to the same file.
 *
 * The command becomes  sh -c 'exec "$@" >>LOG 2>&1' sh <argv...> : the
 * shell execs into the original argv, so the PID that GameHub records (and
 * later kills) is still the Wine process. Children inherit fds 1 and 2.
 *
 * Any failure returns the arguments unchanged: worst case is stock GameHub.
 */
public final class BhWineLog {

    private static final String TAG = "BhWineLog";
    private static final String LOG_DIR = "BunnerHub-Etech/logs";
    private static final int KEEP_LOGS = 10;
    /** Launchers started within this window share one session file. */
    private static final long SESSION_WINDOW_MS = 120_000;

    private static File sessionFile;
    private static long lastWrapMs;

    private BhWineLog() {}

    /** Adds FEX logging switches when capture is on. */
    public static String[] env(String[] env) {
        try {
            if (!isEnabled(env)) return env;
            List<String> out = new ArrayList<>(Arrays.asList(env));
            addIfMissing(out, "FEX_SILENTLOG", "0");
            addIfMissing(out, "FEX_OUTPUTLOG", "stderr");
            return out.toArray(new String[0]);
        } catch (Throwable t) {
            Log.w(TAG, "env hook failed", t);
            return env;
        }
    }

    /** Wraps argv so its stdout and stderr are appended to the session log. */
    public static String[] command(String[] argv, String[] env) {
        // Mark where this session starts in /sdcard/wine.log, for crash reports.
        BhCrashReporter.onLaunch();
        try {
            if (!isEnabled(env)) return argv;
            File log = sessionFile();
            if (log == null) return argv;
            writeHeader(log, argv, env);

            String[] out = new String[argv.length + 4];
            out[0] = "/system/bin/sh";
            out[1] = "-c";
            out[2] = "exec \"$@\" >>'" + log.getAbsolutePath() + "' 2>&1";
            out[3] = "sh";
            System.arraycopy(argv, 0, out, 4, argv.length);
            Log.i(TAG, "capturing to " + log + ": " + String.join(" ", argv));
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "command hook failed", t);
            return argv;
        }
    }

    private static boolean isEnabled(String[] env) {
        if (env == null) return false;
        for (String kv : env) {
            if (kv != null && kv.startsWith("WINEDEBUG=")) {
                String v = kv.substring("WINEDEBUG=".length()).trim();
                return !v.isEmpty() && !v.equals("-all");
            }
        }
        return false;
    }

    private static void addIfMissing(List<String> env, String key, String value) {
        String prefix = key + "=";
        for (String kv : env) {
            if (kv != null && kv.startsWith(prefix)) return;
        }
        env.add(prefix + value);
    }

    private static synchronized File sessionFile() {
        long now = System.currentTimeMillis();
        if (sessionFile == null || now - lastWrapMs > SESSION_WINDOW_MS) {
            File dir = new File(Environment.getExternalStorageDirectory(), LOG_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                Log.w(TAG, "cannot create " + dir);
                return null;
            }
            prune(dir);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(now));
            sessionFile = new File(dir, "wine-" + stamp + ".log");
        }
        lastWrapMs = now;
        return sessionFile;
    }

    /** Keeps the newest KEEP_LOGS - 1 logs, making room for the new one. */
    private static void prune(File dir) {
        File[] logs = dir.listFiles((d, name) -> name.startsWith("wine-") && name.endsWith(".log"));
        if (logs == null || logs.length < KEEP_LOGS) return;
        Arrays.sort(logs, (a, b) -> a.getName().compareTo(b.getName()));
        for (int i = 0; i <= logs.length - KEEP_LOGS; i++) {
            if (!logs[i].delete()) Log.w(TAG, "cannot delete " + logs[i]);
        }
    }

    /** The log sits on shared storage: hide values of secret-looking variables. */
    private static String mask(String kv) {
        int eq = kv.indexOf('=');
        if (eq < 0) return kv;
        String key = kv.substring(0, eq).toUpperCase(Locale.US);
        if (key.contains("TOKEN") || key.contains("SECRET") || key.contains("PASS")
                || key.contains("KEY") || key.contains("AUTH") || key.contains("COOKIE")) {
            return kv.substring(0, eq + 1) + "<hidden>";
        }
        return kv;
    }

    private static synchronized void writeHeader(File log, String[] argv, String[] env) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(log, true), "UTF-8")) {
            String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
            w.write("\n===== " + time + " launch: " + String.join(" ", argv) + "\n");
            String[] sorted = env.clone();
            Arrays.sort(sorted);
            for (String kv : sorted) w.write("  env " + mask(kv) + "\n");
            w.write("=====\n");
        } catch (Throwable t) {
            Log.w(TAG, "header write failed", t);
        }
    }
}
