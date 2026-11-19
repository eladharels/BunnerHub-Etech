package app.revanced.extension.gamehub;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * BhTrainerProgram — a ready-made Windows trainer (e.g. FLiNG's) per game,
 * started inside the game's own Wine. Step 1 of "trainers in the in-game
 * menu": download once from inside the game, start with the game or with one
 * tap, stop. (Step 2, a switch panel pressing the trainer's keys, comes once
 * we know trainer cheats take effect under FEX.)
 *
 * Files: /sdcard/BunnerHub-Etech/trainers/<game id>/ holds the trainer (the
 * .exe and anything that came with it in the zip). To start it, the folder is
 * copied into the running game's Wine prefix (drive_c/bh_trainer/), which
 * Wine always sees, and the .exe is launched with the game's Wine binary and
 * environment (BhWineLaunchHelper, as the Task Manager's Launch does).
 *
 * "Running" = a process whose command line holds the trainer's file name.
 * Auto-start: BhPlayTime.onGameResume calls {@link #onGameResume}; once the
 * game's process exists, it waits a few seconds and starts the trainer once
 * per game process.
 */
public final class BhTrainerProgram {

    private static final String TAG = "BhTrainerProgram";
    private static final String PREFS = "bh_trainer_program";
    private static final String SITE = "https://flingtrainer.com/";
    private static final long AUTOSTART_DELAY_MS = 8000;
    private static final long AUTOSTART_GIVE_UP_MS = 180_000;

    private static final long START_GRACE_MS = 30_000;
    /** A command line running an .exe from our copy folder (drive_c/bh_trainer or C:\\bh_trainer). */
    private static final java.util.regex.Pattern TRAINER_CMD =
            java.util.regex.Pattern.compile("(?i)[/\\\\]bh_trainer[/\\\\][^\\x00]*\\.exe");

    private static volatile int autostartedFor = -1; // game pid the trainer was auto-started for
    private static boolean waiting;
    private static long startedAt;                   // last start(), for the grace period below

    // How GameHub started the game: "/system/bin/linker64 <Wine launcher…> <game.exe> [args]",
    // with its environment, from the image fs root (ProgramLauncher.e(), via BhWineLog).
    private static volatile String[] gameArgv;
    private static volatile String[] gameEnv;
    private static volatile File workDir;

    private BhTrainerProgram() {}

    /** BhWineLog.command: remember the launch that runs the game's .exe. */
    static void recordLaunch(String[] argv, String[] env) {
        try {
            if (argv == null || exeIndex(argv) < 0) return;
            gameArgv = argv.clone();
            gameEnv = env == null ? null : env.clone();
            Log.i(TAG, "game launch: " + String.join(" ", argv));
        } catch (Throwable t) {
            Log.w(TAG, "recordLaunch failed", t);
        }
    }

    static void recordWorkDir(File dir) {
        workDir = dir;
    }

    /** Index of the game .exe argument (not a Wine program, not our trainer), or -1. */
    static int exeIndex(String[] argv) {
        for (int i = argv.length - 1; i >= 1; i--) {
            String a = argv[i] == null ? "" : argv[i].toLowerCase(Locale.US);
            if (!a.contains(".exe") || a.contains("bh_trainer")) continue;
            boolean system = false;
            for (String s : BhTrainer.SYSTEM_EXES) if (a.contains(s)) system = true;
            if (!system) return i;
        }
        return -1;
    }

    /** The game's launch command with the game's .exe swapped for the trainer (Windows path). */
    static String[] trainerCommand(String[] argv, String winPath) {
        int i = exeIndex(argv);
        if (i < 0) return null;
        String[] out = new String[i + 1];
        System.arraycopy(argv, 0, out, 0, i);
        out[i] = winPath; // the game's own arguments after its .exe are dropped
        return out;
    }

    // ── Per-game settings ───────────────────────────────────────────────────

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static File dir(String gameId) {
        return new File(Environment.getExternalStorageDirectory(),
                "BunnerHub-Etech/trainers/" + gameId.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    /** The trainer .exe for the game, or null. */
    static File exe(Context ctx, String gameId) {
        if (gameId == null) return null;
        String name = prefs(ctx).getString("exe_" + gameId, null);
        if (name == null) return null;
        File f = new File(dir(gameId), name);
        return f.isFile() ? f : null;
    }

    static boolean autostart(Context ctx, String gameId) {
        return gameId != null && prefs(ctx).getBoolean("auto_" + gameId, true);
    }

    static void setAutostart(Context ctx, String gameId, boolean on) {
        if (gameId != null) prefs(ctx).edit().putBoolean("auto_" + gameId, on).apply();
    }

    static synchronized void remove(Context ctx, String gameId) {
        if (gameId == null) return;
        stopAll();
        deleteTree(dir(gameId));
        prefs(ctx).edit().remove("exe_" + gameId).remove("auto_" + gameId).apply();
    }

    // ── Getting a trainer ───────────────────────────────────────────────────

    /** Trainer site search for the game's name. */
    static String searchUrl(String game) {
        if (game == null || game.trim().isEmpty()) return SITE;
        String q = game.replaceAll("[™®©:]", " ").replaceAll("\\s+", " ").trim();
        return SITE + "?s=" + android.net.Uri.encode(q);
    }

    /** Files the user can pick: .exe and .zip in Download, newest first. */
    static List<File> pickable() {
        List<File> out = new ArrayList<>();
        File dl = new File(Environment.getExternalStorageDirectory(), "Download");
        File[] files = dl.listFiles((d, n) -> {
            String l = n.toLowerCase(Locale.US);
            return l.endsWith(".exe") || l.endsWith(".zip");
        });
        if (files != null) for (File f : files) out.add(f);
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    static synchronized String install(Context ctx, File src) throws Exception {
        if (src.length() > 64L << 20) throw new IllegalStateException("File too large for a trainer");
        byte[] data = new byte[(int) src.length()];
        try (InputStream in = new FileInputStream(src)) {
            int got = 0;
            while (got < data.length) {
                int n = in.read(data, got, data.length - got);
                if (n < 0) break;
                got += n;
            }
        }
        return install(ctx, data, src.getName());
    }

    /** A downloaded trainer (.exe, or a .zip holding one): keep it for the current game. */
    static synchronized String install(Context ctx, byte[] data, String fileName) throws Exception {
        String id = BhPlayTime.currentGameId();
        if (id == null) throw new IllegalStateException("No game is running");
        String lower = fileName.toLowerCase(Locale.US);
        File staging = new File(dir(id).getPath() + ".new");
        deleteTree(staging);
        if (!staging.mkdirs()) throw new IllegalStateException("Cannot create " + staging);
        String exeName;
        try {
            if (BhCheatBrowser.isZip(data)) {
                exeName = unzip(data, staging);
            } else if (isPeExe(data)) {
                exeName = BhCheatBrowser.safeName(lower.endsWith(".exe") ? fileName : fileName + ".exe");
                write(new File(staging, exeName), data);
            } else if (BhCheatBrowser.isRarOr7z(data) || lower.endsWith(".rar") || lower.endsWith(".7z")) {
                exeName = extractWithWine(data, lower.endsWith(".7z") || (data.length > 1 && data[0] == 0x37)
                        ? "archive.7z" : "archive.rar", staging);
            } else {
                throw new IllegalStateException(fileName + " is not a trainer (.exe or .zip)");
            }
        } catch (Exception e) {
            deleteTree(staging);
            throw e;
        }
        stopAll();
        deleteTree(dir(id));
        if (!staging.renameTo(dir(id))) throw new IllegalStateException("Cannot save the trainer");
        prefs(ctx).edit().putString("exe_" + id, exeName).apply();
        autostartedFor = -1;
        return "Trainer saved: " + exeName;
    }

    /** Extracts a zip; returns the trainer .exe's path inside it (relative). */
    private static String unzip(byte[] zip, File into) throws Exception {
        List<String> exes = new ArrayList<>();
        long total = 0;
        String root = into.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                File out = new File(into, name);
                if (!out.getCanonicalPath().startsWith(root)) continue; // no ../ escapes
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    int n;
                    while ((n = z.read(buf)) > 0) {
                        o.write(buf, 0, n);
                        total += n;
                        if (total > 256L << 20) throw new IllegalStateException("Zip too large");
                    }
                }
                if (name.toLowerCase(Locale.US).endsWith(".exe")) exes.add(name);
            }
        }
        if (exes.isEmpty()) throw new IllegalStateException("The zip has no .exe");
        return chooseExe(into, exes);
    }

    /** The trainer among the .exe files (relative paths under root): named like one, else the largest. */
    static String chooseExe(File root, List<String> exes) {
        File into = root;
        String best = null;
        long bestSize = -1;
        for (String n : exes) {
            String file = n.substring(n.lastIndexOf('/') + 1).toLowerCase(Locale.US);
            if (file.contains("trainer")) return n;
            long size = new File(into, n).length();
            if (size > bestSize) {
                bestSize = size;
                best = n;
            }
        }
        return best;
    }

    static boolean isPeExe(byte[] d) {
        return d.length > 64 && d[0] == 'M' && d[1] == 'Z';
    }

    // ── Running it ───────────────────────────────────────────────────────────

    /** Copies the trainer into the game's Wine prefix and starts it there. */
    static synchronized String start(Context ctx) throws Exception {
        String id = BhPlayTime.currentGameId();
        File exe = exe(ctx, id);
        if (exe == null) throw new IllegalStateException("No trainer for this game");
        if (BhTrainer.gamePid() < 0) throw new IllegalStateException("Start the game first");
        if (runningPid(ctx) > 0) return "The trainer is already running";
        // Wine takes a few seconds to show the process: don't start (and re-copy) twice.
        if (System.currentTimeMillis() - startedAt < START_GRACE_MS) return "The trainer is already starting";
        String[] argv = gameArgv;
        if (argv == null) {
            throw new IllegalStateException("The app didn't see how this game was started. "
                    + "Restart the game once, then try again.");
        }
        String[] env = gameEnv;
        // The game's own prefix first (its launch environment), else any running Wine's.
        String prefix = envValue(env, "WINEPREFIX");
        if (prefix == null) prefix = BhWineLaunchHelper.getWinePrefix();
        if (prefix == null) throw new IllegalStateException("Can't find the game's Wine");
        File dst = new File(prefix, "drive_c/bh_trainer");
        deleteTree(dst);
        copyTree(dir(id), dst);
        String rel = exe.getAbsolutePath().substring(dir(id).getAbsolutePath().length() + 1);
        File target = new File(dst, rel);
        if (!target.isFile()) throw new IllegalStateException("Copying the trainer failed");
        String winPath = "C:\\bh_trainer\\" + rel.replace('/', '\\');
        String[] cmd = trainerCommand(argv, winPath);
        if (cmd == null) throw new IllegalStateException("Can't find the game in its launch command");

        File logs = new File(Environment.getExternalStorageDirectory(), "BunnerHub-Etech/logs");
        if (!logs.isDirectory()) logs.mkdirs();
        File note = new File(logs, "trainer-launch.txt");
        File output = new File(logs, "trainer-output.log");
        writeLaunchNote(cmd, argv, prefix, target);
        Process proc = launch(cmd, env, output, false);
        startedAt = System.currentTimeMillis();
        Log.i(TAG, "started: " + String.join(" ", cmd));
        watch(ctx.getApplicationContext(), proc, cmd, env, output, note, winPath);
        return "Starting " + exe.getName() + "…";
    }

    /** Pid of the running trainer, or -1. */
    static int runningPid(Context ctx) {
        List<Integer> p = pids();
        return p.isEmpty() ? -1 : p.get(0);
    }

    /** True while a start is in its grace period and the trainer hasn't shown up yet. */
    static synchronized boolean starting() {
        return System.currentTimeMillis() - startedAt < START_GRACE_MS && pids().isEmpty();
    }

    /** Every process started from the trainer's copy (Wine and FEX can make several). */
    static List<Integer> pids() {
        List<Integer> out = new ArrayList<>();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return out;
        int self = android.os.Process.myPid();
        for (File p : procs) {
            if (!p.getName().matches("\\d+")) continue;
            int pid = Integer.parseInt(p.getName());
            if (pid == self) continue;
            String cmd = cmdline(p);
            if (cmd != null && TRAINER_CMD.matcher(cmd).find()) out.add(pid);
        }
        return out;
    }

    /** Stops the trainer. Throws if it's still running afterwards. */
    static synchronized void stop() throws Exception {
        stopAll();
        if (!pids().isEmpty()) throw new IllegalStateException("The trainer is still running");
    }

    private static void stopAll() {
        startedAt = 0;
        for (int round = 0; round < 3; round++) {
            List<Integer> p = pids();
            if (p.isEmpty()) return;
            for (int pid : p) android.os.Process.killProcess(pid);
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                return;
            }
        }
    }

    /** /sdcard/BunnerHub-Etech/logs/trainer-launch.txt: the commands used, for troubleshooting. */
    private static void writeLaunchNote(String[] cmd, String[] gameCmd, String prefix, File copy) {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "BunnerHub-Etech/logs");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            String note = "time: " + new java.util.Date() + "\nworking dir: " + workDir
                    + "\nwine prefix: " + prefix + "\ntrainer copy: " + copy + " (" + copy.length() + " bytes)"
                    + "\ngame command:\n  " + String.join("\n  ", gameCmd)
                    + "\ntrainer command:\n  " + String.join("\n  ", cmd) + "\n";
            write(new File(dir, "trainer-launch.txt"), note.getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
    }

    private static void appendNote(File note, String line) {
        try (OutputStream o = new FileOutputStream(note, true)) {
            o.write((line + "\n").getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
    }

    private static String envValue(String[] env, String key) {
        if (env == null) return null;
        for (String kv : env) {
            if (kv != null && kv.startsWith(key + "=")) return kv.substring(key.length() + 1);
        }
        return null;
    }

    /** Starts cmd with the game's environment; output (with Wine errors) goes to the log. */
    private static Process launch(String[] cmd, String[] env, File output, boolean append) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workDir != null) pb.directory(workDir);
        if (env != null) {
            pb.environment().clear();
            for (String kv : env) {
                int eq = kv.indexOf('=');
                if (eq > 0) pb.environment().put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        pb.environment().put("WINEDEBUG", "err+all,fixme-all"); // Wine's errors, for the log
        pb.redirectErrorStream(true);
        pb.redirectOutput(append ? ProcessBuilder.Redirect.appendTo(output) : ProcessBuilder.Redirect.to(output));
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        return pb.start();
    }

    /**
     * Notes whether the trainer is still up after 10 s, or when it ended and how.
     * Ended within 8 s through GameHub's launcher (run.exe)? Try once more with Wine
     * directly (the trainer as Wine's own argument).
     */
    private static void watch(Context ctx, Process proc, String[] cmd, String[] env, File output, File note,
                              String winPath) {
        new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                boolean ended = proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                long secs = (System.currentTimeMillis() - t0) / 1000;
                if (!ended) {
                    appendNote(note, "result: still running after 10 s (" + pids().size() + " trainer process(es))");
                    autoHide(ctx, note);
                    return;
                }
                appendNote(note, "result: ended after " + secs + " s with exit code " + proc.exitValue());
                int runIdx = -1;
                for (int i = 0; i < cmd.length; i++) {
                    if (cmd[i].toLowerCase(Locale.US).contains("run.exe")) runIdx = i;
                }
                if (secs >= 8 || runIdx < 0 || !pids().isEmpty()) return;
                String[] direct = new String[cmd.length - 1];
                for (int i = 0, j = 0; i < cmd.length; i++) if (i != runIdx) direct[j++] = cmd[i];
                appendNote(note, "retry without the launcher:\n  " + String.join("\n  ", direct));
                Process p2 = launch(direct, env, output, true);
                synchronized (BhTrainerProgram.class) {
                    startedAt = System.currentTimeMillis();
                }
                boolean ended2 = p2.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                appendNote(note, ended2 ? "retry: ended with exit code " + p2.exitValue()
                        : "retry: still running after 10 s (" + pids().size() + " trainer process(es))");
                if (!pids().isEmpty()) autoHide(ctx, note);
            } catch (Throwable t) {
                appendNote(note, "watch failed: " + t);
            }
        }, "BhTrainerWatch").start();
    }

    // ── The trainer's window (bh_winctl.exe inside the game's Wine) ──────────
    //
    // GameHub's Wine has no taskbar or Alt+Tab: a trainer window that comes up
    // over the game can't be put away, and the game stays black behind it. The
    // cheats are hotkeys and keep working with the window hidden, so once the
    // trainer is up its window is hidden and the game brought back. The helper
    // is a tiny Windows program built by CI (tools/bh_winctl.c) and shipped in
    // the APK's assets.

    private static final String WINCTL_ASSET = "bunnerhub-etech/bh_winctl.exe";

    /** Hides the trainer's window (retrying while it comes up), then brings the game back. */
    private static void autoHide(Context ctx, File note) {
        try {
            for (int i = 0; i < 12; i++) {
                if (pids().isEmpty()) return;
                int hid = winctl(ctx, "hide", "bh_trainer");
                if (hid == 0) {
                    Thread.sleep(500);
                    int back = winctl(ctx, "focus", gameExeName());
                    appendNote(note, "window: trainer hidden, game " + (back == 0 ? "brought back" : "not found (" + back + ")"));
                    return;
                }
                if (hid < 0) {
                    appendNote(note, "window: helper unavailable");
                    return;
                }
                Thread.sleep(2500);
            }
            appendNote(note, "window: no trainer window to hide after 30 s");
        } catch (Throwable t) {
            appendNote(note, "window: " + t);
        }
    }

    /** Back to the game: hide the trainer's window, activate the game. Returns a message. */
    static String backToGame(Context ctx) {
        int hid = winctl(ctx, "hide", "bh_trainer");
        int back = winctl(ctx, "focus", gameExeName());
        if (hid < 0 || back < 0) return "The window helper isn't available in this build";
        return back == 0 ? "Back to the game" + (hid == 0 ? " (trainer window hidden)" : "")
                : "Couldn't find the game's window";
    }

    /** Shows the trainer's window over the game (Back to the game hides it again). */
    static String showTrainer(Context ctx) {
        if (pids().isEmpty()) return "The trainer isn't running";
        int r = winctl(ctx, "show", "bh_trainer");
        if (r < 0) return "The window helper isn't available in this build";
        return r == 0 ? "Trainer window shown. Use Back to the game when you're done." : "No trainer window found";
    }

    /** The game's .exe file name from its launch command (lower case), or "" if unknown. */
    static String gameExeName() {
        String[] argv = gameArgv;
        int i = argv == null ? -1 : exeIndex(argv);
        if (i < 0) return "";
        String a = argv[i].replace('/', '\\');
        return a.substring(a.lastIndexOf('\\') + 1).toLowerCase(Locale.US);
    }

    /** Runs bh_winctl.exe in the game's Wine; its exit code, or -1 if it can't be run. */
    static int winctl(Context ctx, String cmd, String match) {
        try {
            String[] argv = gameArgv;
            String[] env = gameEnv;
            String prefix = envValue(env, "WINEPREFIX");
            int wineIdx = wineIndex(argv);
            if (argv == null || prefix == null || wineIdx < 0 || match == null || match.isEmpty()) return -1;
            File tools = new File(prefix, "drive_c/bh_tools");
            if (!tools.isDirectory() && !tools.mkdirs()) return -1;
            File exe = new File(tools, "bh_winctl.exe");
            try (InputStream in = ctx.getAssets().open(WINCTL_ASSET)) {
                byte[] data = readAll(in);
                if (!exe.isFile() || exe.length() != data.length) write(exe, data);
            }
            List<String> c = new ArrayList<>();
            for (int i = 0; i <= wineIdx; i++) c.add(argv[i]);
            c.add("C:\\bh_tools\\bh_winctl.exe");
            c.add(cmd);
            c.add(match);
            File logs = new File(Environment.getExternalStorageDirectory(), "BunnerHub-Etech/logs");
            if (!logs.isDirectory()) logs.mkdirs();
            Process p = launch(c.toArray(new String[0]), env, new File(logs, "winctl.log"), true);
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroy();
                return 3;
            }
            return p.exitValue();
        } catch (Throwable t) {
            Log.w(TAG, "winctl " + cmd + " failed", t);
            return -1;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    // ── .rar / .7z trainers (7-Zip inside the game's Wine) ───────────────────
    //
    // Android can only unpack zip. Older FLiNG trainers come as .rar, so the
    // first such download installs 7-Zip (about 2 MB, official .msi) into the
    // game's prefix with Wine's msiexec, and 7-Zip unpacks it there.

    private static final String SEVEN_ZIP_MSI = "7z2409-x64.msi";

    /** Unpacks a .rar / .7z with 7-Zip in the game's Wine into staging; returns the trainer .exe. */
    private static String extractWithWine(byte[] data, String archiveName, File staging) throws Exception {
        String[] argv = gameArgv;
        String[] env = gameEnv;
        String prefix = envValue(env, "WINEPREFIX");
        int wineIdx = wineIndex(argv);
        if (argv == null || prefix == null || wineIdx < 0) {
            throw new IllegalStateException("To unpack a .rar the game must be running (started after this update). "
                    + "Restart the game, then download again.");
        }
        File logs = new File(Environment.getExternalStorageDirectory(), "BunnerHub-Etech/logs");
        if (!logs.isDirectory()) logs.mkdirs();
        File log = new File(logs, "extract.log");
        File sevenZip = new File(prefix, "drive_c/Program Files/7-Zip/7z.exe");
        if (!sevenZip.isFile()) {
            File dir = new File(prefix, "drive_c/bh_tools");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create " + dir);
            File msi = new File(dir, SEVEN_ZIP_MSI);
            if (!msi.isFile() || msi.length() < 500_000) {
                Exception last = null;
                for (String u : new String[]{"https://www.7-zip.org/a/" + SEVEN_ZIP_MSI,
                        "https://github.com/ip7z/7zip/releases/download/24.09/" + SEVEN_ZIP_MSI}) {
                    try {
                        downloadTo(u, msi, text -> { });
                        last = null;
                        break;
                    } catch (Exception e) {
                        last = e;
                    }
                }
                if (last != null) throw new IllegalStateException("Couldn't download 7-Zip: " + last.getMessage());
            }
            runWine(argv, wineIdx, env, log, false, "msiexec", "/i", "C:\\bh_tools\\" + SEVEN_ZIP_MSI, "/qn");
            if (!sevenZip.isFile()) throw new IllegalStateException("Installing 7-Zip failed; see logs/extract.log");
        }
        File work = new File(prefix, "drive_c/bh_trainer_dl");
        deleteTree(work);
        if (!work.mkdirs()) throw new IllegalStateException("Cannot create " + work);
        write(new File(work, archiveName), data);
        runWine(argv, wineIdx, env, log, true, "C:\\Program Files\\7-Zip\\7z.exe", "x",
                "C:\\bh_trainer_dl\\" + archiveName, "-oC:\\bh_trainer_dl\\out", "-y");
        File out = new File(work, "out");
        List<String> exes = new ArrayList<>();
        listExes(out, "", exes);
        if (exes.isEmpty()) {
            deleteTree(work);
            throw new IllegalStateException("The archive has no .exe (or 7-Zip couldn't unpack it; see logs/extract.log)");
        }
        copyTree(out, staging);
        deleteTree(work);
        return chooseExe(staging, exes);
    }

    private static void listExes(File dir, String rel, List<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String r = rel.isEmpty() ? k.getName() : rel + "/" + k.getName();
            if (k.isDirectory()) listExes(k, r, out);
            else if (k.getName().toLowerCase(Locale.US).endsWith(".exe")) out.add(r);
        }
    }

    /** Index of the game's Wine binary ("…/bin/wine…") in its launch command, or -1. */
    static int wineIndex(String[] argv) {
        if (argv == null) return -1;
        int idx = -1;
        for (int i = 0; i < argv.length; i++) if (argv[i].contains("/bin/wine")) idx = i;
        return idx;
    }

    /** Runs a Windows command with the game's Wine and environment and waits (5 min); throws on failure. */
    private static void runWine(String[] argv, int wineIdx, String[] env, File log, boolean append, String... args)
            throws Exception {
        List<String> cmd = new ArrayList<>();
        for (int i = 0; i <= wineIdx; i++) cmd.add(argv[i]);
        for (String a : args) cmd.add(a);
        Process p = launch(cmd.toArray(new String[0]), env, log, append);
        if (!p.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)) {
            p.destroy();
            throw new IllegalStateException(args[0] + " took too long");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException(args[0] + " failed (code " + p.exitValue() + "); see logs/" + log.getName());
        }
    }

    // ── .NET support (Wine Mono) ─────────────────────────────────────────────
    //
    // Some trainers are .NET programs. Wine runs those with Wine Mono, which
    // GameHub's Wine builds leave out: the trainer then dies at once with
    // "Wine Mono is not installed" (mscoree CorBindToRuntime). The fix is a
    // one-time install of the Wine Mono .msi that matches the game's Wine
    // into the game's prefix, with Wine's own msiexec.

    /** True when the last trainer start died for want of Wine Mono. */
    static boolean needsMono() {
        File out = new File(Environment.getExternalStorageDirectory(), "BunnerHub-Etech/logs/trainer-output.log");
        if (!out.isFile() || out.length() > 4L << 20) return false;
        try {
            byte[] d = new byte[(int) out.length()];
            try (InputStream in = new FileInputStream(out)) {
                int got = 0;
                while (got < d.length) {
                    int n = in.read(d, got, d.length - got);
                    if (n < 0) break;
                    got += n;
                }
            }
            String t = new String(d, "ISO-8859-1");
            return t.contains("Wine Mono is not installed") || t.contains("mscoree.dll.CorBindToRuntime");
        } catch (Throwable e) {
            return false;
        }
    }

    interface Progress {
        void update(String text);
    }

    /** Downloads the matching Wine Mono and installs it into the game's prefix. */
    static synchronized String installMono(Progress progress) throws Exception {
        String[] argv = gameArgv;
        String[] env = gameEnv;
        if (argv == null) throw new IllegalStateException("Restart the game once, then try again.");
        String prefix = envValue(env, "WINEPREFIX");
        if (prefix == null) throw new IllegalStateException("Can't find the game's Wine");
        int wineIdx = wineIndex(argv);
        if (wineIdx < 0) throw new IllegalStateException("Can't find the game's Wine program");

        progress.update("Finding the Wine Mono version for this Wine…");
        File wineRoot = new File(argv[wineIdx]).getParentFile().getParentFile();
        String version = monoVersion(wineRoot);
        if (version == null) version = latestMonoVersion();
        if (version == null) throw new IllegalStateException("Can't tell which Wine Mono to install");

        File dir = new File(prefix, "drive_c/bh_wine_mono");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create " + dir);
        String msiName = "wine-mono-" + version + "-x86.msi";
        File msi = new File(dir, msiName);
        if (!msi.isFile() || msi.length() < (10L << 20)) {
            String[] urls = {
                    "https://dl.winehq.org/wine/wine-mono/" + version + "/" + msiName,
                    "https://github.com/madewokherd/wine-mono/releases/download/wine-mono-" + version + "/" + msiName,
            };
            Exception last = null;
            for (String u : urls) {
                try {
                    downloadTo(u, msi, progress);
                    last = null;
                    break;
                } catch (Exception e) {
                    last = e;
                }
            }
            if (last != null) throw new IllegalStateException("Download failed: " + last.getMessage());
        }

        progress.update("Installing Wine Mono " + version + " (a minute or two)…");
        List<String> cmd = new ArrayList<>();
        for (int i = 0; i <= wineIdx; i++) cmd.add(argv[i]);
        cmd.add("msiexec");
        cmd.add("/i");
        cmd.add("C:\\bh_wine_mono\\" + msiName);
        cmd.add("/qn");
        File logs = new File(Environment.getExternalStorageDirectory(), "BunnerHub-Etech/logs");
        if (!logs.isDirectory()) logs.mkdirs();
        Process p = launch(cmd.toArray(new String[0]), env, new File(logs, "mono-install.log"), false);
        if (!p.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)) {
            p.destroy();
            throw new IllegalStateException("The installer took too long");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("The installer failed (code " + p.exitValue() + "); see logs/mono-install.log");
        }
        // Empty the output log so needsMono() stops asking.
        write(new File(logs, "trainer-output.log"), new byte[0]);
        return "Wine Mono " + version + " installed. Tap Start.";
    }

    /** The Wine Mono version this Wine expects, from its mscoree.dll / appwiz.cpl, or null. */
    static String monoVersion(File wineRoot) {
        List<File> files = new ArrayList<>();
        for (File base : new File[]{wineRoot, wineRoot == null ? null : wineRoot.getParentFile()}) {
            if (base != null) findFiles(new File(base, "lib"), files, 0);
        }
        String best = null;
        java.util.regex.Pattern v = java.util.regex.Pattern.compile("^(\\d{1,2})\\.(\\d{1,2})\\.(\\d{1,2})$");
        for (File f : files) {
            for (String str : strings(f)) {
                java.util.regex.Matcher m = v.matcher(str);
                if (m.matches() && Integer.parseInt(m.group(1)) >= 4 && (best == null || newer(str, best))) best = str;
            }
        }
        return best;
    }

    private static void findFiles(File dir, List<File> out, int depth) {
        File[] kids = dir.listFiles();
        if (kids == null || depth > 4) return;
        for (File k : kids) {
            if (k.isDirectory()) findFiles(k, out, depth + 1);
            else if (k.getName().equalsIgnoreCase("mscoree.dll") || k.getName().equalsIgnoreCase("appwiz.cpl")) out.add(k);
        }
    }

    /** NUL-terminated printable strings of 5-8 characters (version-shaped). */
    private static List<String> strings(File f) {
        List<String> out = new ArrayList<>();
        if (f.length() > 64L << 20) return out;
        try (InputStream in = new java.io.BufferedInputStream(new FileInputStream(f))) {
            StringBuilder cur = new StringBuilder();
            int b;
            while ((b = in.read()) >= 0) {
                if (b >= 0x20 && b < 0x7F) {
                    if (cur.length() < 16) cur.append((char) b);
                    else cur.setLength(17); // too long to be a version: mark
                } else {
                    if (b == 0 && cur.length() >= 5 && cur.length() <= 8) out.add(cur.toString());
                    cur.setLength(0);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int d = Integer.parseInt(x[i]) - Integer.parseInt(y[i]);
            if (d != 0) return d > 0;
        }
        return false;
    }

    /** Latest Wine Mono release on GitHub, e.g. "10.3.0", or null. */
    private static String latestMonoVersion() {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("https://api.github.com/repos/madewokherd/wine-mono/releases/latest").openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"tag_name\"\\s*:\\s*\"wine-mono-([0-9.]+)\"")
                        .matcher(b.toString("UTF-8"));
                return m.find() ? m.group(1) : null;
            } finally {
                c.disconnect();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static void downloadTo(String url, File dst, Progress progress) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code + " from " + url);
            long total = c.getContentLengthLong();
            File part = new File(dst.getPath() + ".part");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[1 << 16];
                long got = 0, shown = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    if (got - shown > (2L << 20)) {
                        shown = got;
                        progress.update("Downloading Wine Mono… " + (got >> 20) + (total > 0 ? " of " + (total >> 20) : "") + " MB");
                    }
                }
            }
            if (!part.renameTo(dst)) throw new IllegalStateException("Cannot save " + dst);
        } finally {
            c.disconnect();
        }
    }

    // ── Auto-start ───────────────────────────────────────────────────────────

    /** BhPlayTime.onGameResume (the :wine process, each time the game screen shows). */
    static void onGameResume(Context ctx) {
        try {
            String id = BhPlayTime.currentGameId();
            if (id == null || exe(ctx, id) == null || !autostart(ctx, id)) return;
            synchronized (BhTrainerProgram.class) {
                if (waiting) return;
                waiting = true;
            }
            Context app = ctx.getApplicationContext();
            new Thread(() -> {
                try {
                    long until = System.currentTimeMillis() + AUTOSTART_GIVE_UP_MS;
                    int pid;
                    while ((pid = BhTrainer.gamePid()) < 0) {
                        if (System.currentTimeMillis() > until) return;
                        Thread.sleep(3000);
                    }
                    if (pid == autostartedFor) return; // already started for this run of the game
                    Thread.sleep(AUTOSTART_DELAY_MS);   // let the game get going
                    if (!id.equals(BhPlayTime.currentGameId())) return;
                    autostartedFor = pid;
                    String msg = start(app);
                    toast(app, "Trainer: " + msg);
                } catch (Throwable t) {
                    Log.w(TAG, "auto-start failed", t);
                    toast(app, "Trainer didn't start: " + (t.getMessage() != null ? t.getMessage() : t));
                } finally {
                    synchronized (BhTrainerProgram.class) {
                        waiting = false;
                    }
                }
            }, "BhTrainerAutostart").start();
        } catch (Throwable t) {
            Log.w(TAG, "onGameResume failed", t);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void toast(Context ctx, String msg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        });
    }

    private static void write(File f, byte[] data) throws Exception {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(data);
        }
    }

    private static void copyTree(File from, File to) throws Exception {
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new IllegalStateException("Cannot create " + to);
            File[] kids = from.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(to, k.getName()));
            return;
        }
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        if (!f.delete()) Log.w(TAG, "cannot delete " + f);
    }

    private static String cmdline(File p) {
        try (FileInputStream in = new FileInputStream(new File(p, "cmdline"))) {
            byte[] buf = new byte[1024];
            int n = in.read(buf);
            return n <= 0 ? null : new String(buf, 0, n).replace('\0', ' ');
        } catch (Throwable t) {
            return null;
        }
    }
}
