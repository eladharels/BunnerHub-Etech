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

    private BhTrainerProgram() {}

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
            } else if (lower.endsWith(".rar") || lower.endsWith(".7z")) {
                throw new IllegalStateException(fileName + " is a .rar/.7z archive; only .zip and .exe are supported");
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
        // Prefer a name that says trainer; else the largest.
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
        String prefix = BhWineLaunchHelper.getWinePrefix();
        if (prefix == null) throw new IllegalStateException("Can't find the game's Wine");
        File dst = new File(prefix, "drive_c/bh_trainer");
        deleteTree(dst);
        copyTree(dir(id), dst);
        String rel = exe.getAbsolutePath().substring(dir(id).getAbsolutePath().length() + 1);
        File target = new File(dst, rel);
        if (!target.isFile()) throw new IllegalStateException("Copying the trainer failed");
        BhWineLaunchHelper.launchExe(ctx, target.getAbsolutePath());
        startedAt = System.currentTimeMillis();
        Log.i(TAG, "started " + target);
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
