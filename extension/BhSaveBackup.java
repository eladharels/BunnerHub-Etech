package app.revanced.extension.gamehub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * BhSaveBackup — back up and restore a game's saves, from a "Saves" chip on
 * the game's detail page.
 *
 * Windows games keep saves and settings in the user folders of the game's
 * Wine container ({filesDir}/usr/home/virtual_containers/{gameId}/drive_c/
 * users/…: Documents, AppData, Saved Games), and Steam Cloud games in the
 * container's Steam userdata. The container is in the app's private storage,
 * out of reach of a file manager and gone if the app's data is cleared, so
 * Back up zips those folders to /sdcard/BunnerHub-Etech/saves/<game>/ (last
 * 5 kept) and Restore unzips one back (after backing up the current state).
 * Caches and temp folders are left out.
 *
 * Called from BhPlayTime.onDetail (the GameDetailActivity hook).
 */
public final class BhSaveBackup {

    private static final String TAG = "BhSaveBackup";
    private static final String CHIP_TAG = "bh_saves_chip";
    private static final int KEEP = 5;
    /** Automatic backups (after playing) kept, separately from the manual ones. */
    private static final int KEEP_AUTO = 3;
    private static final long WARN_BYTES = 1L << 30;
    private static final long MAX_BYTES = 4L << 30;

    /** Folder names (any level) that hold caches, not saves. */
    private static final String[] SKIP = {
            "temp", "tmp", "cache", "caches", "inetcache", "d3dscache", "shadercache", "gpucache",
            "nvidia", "nvidia corporation", "crashdumps", "dxvk-cache", "vkd3d-proton",
    };

    private BhSaveBackup() {}

    // ── Detail page chip ─────────────────────────────────────────────────────

    static void onDetail(Activity activity, Object entity) {
        // GameDetailActivity's observer runs on the UI thread: do the file checks elsewhere.
        new Thread(() -> {
            try {
                String id = gameId(activity, entity);
                Object nameObj = call(entity, "getName");
                String name = nameObj instanceof String && !((String) nameObj).isEmpty()
                        ? (String) nameObj : "Game " + id;
                boolean has = id != null && (container(activity, id).isDirectory() || !backups(name, id).isEmpty());
                activity.runOnUiThread(() -> {
                    if (has) showChip(activity, id, name);
                    else removeChip(activity); // also clears an earlier game's chip
                });
                // Fallback for the backup after playing (the game's process may have ended first).
                if (id != null) autoBackup(activity, id, name);
            } catch (Throwable t) {
                Log.w(TAG, "onDetail failed", t);
            }
        }, "BhSaveBackupDetail").start();
    }

    /**
     * The game's container id: whichever of the server id / local id has a
     * container; else whichever has a backup folder (e.g. after the app's data
     * was cleared); else the server id.
     */
    static String gameId(Context ctx, Object entity) {
        Object server = call(entity, "getId");
        Object local = call(entity, "getLocalGameId");
        String s = server instanceof Integer && (Integer) server > 0 ? String.valueOf(server) : null;
        String l = local instanceof String && !((String) local).isEmpty() ? (String) local : null;
        if (s != null && container(ctx, s).isDirectory()) return s;
        if (l != null && container(ctx, l).isDirectory()) return l;
        if (s != null && findDir(s) != null) return s;
        if (l != null && findDir(l) != null) return l;
        return s != null ? s : l;
    }

    private static void showChip(Activity activity, String id, String name) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return;
        View existing = content.findViewWithTag(CHIP_TAG);
        TextView chip;
        if (existing instanceof TextView) {
            chip = (TextView) existing;
        } else {
            chip = new TextView(activity);
            chip.setTag(CHIP_TAG);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            chip.setTypeface(null, Typeface.BOLD);
            chip.setTextColor(0xFFFFFFFF);
            int padH = BhPlayTime.dp(activity, 12), padV = BhPlayTime.dp(activity, 6);
            chip.setPadding(padH, padV, padH, padV);
            chip.setFocusable(true);
            chip.setClickable(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xB3000000);
            bg.setCornerRadius(BhPlayTime.dp(activity, 16));
            bg.setStroke(BhPlayTime.dp(activity, 1), 0x66FFFFFF);
            chip.setBackground(bg);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            lp.topMargin = BhPlayTime.dp(activity, 136);
            lp.rightMargin = BhPlayTime.dp(activity, 24);
            content.addView(chip, lp);
        }
        chip.setText("💾  Saves");
        chip.setContentDescription("Saves: back up or restore");
        chip.setOnClickListener(v -> showDialog(activity, id, name));
    }

    private static void removeChip(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        View chip = content == null ? null : content.findViewWithTag(CHIP_TAG);
        if (chip != null) ((ViewGroup) chip.getParent()).removeView(chip);
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────

    private static void showDialog(Activity activity, String id, String name) {
        new Thread(() -> {
            List<File> list = backups(name, id);
            boolean hasContainer = container(activity, id).isDirectory();
            File where = dir(name, id);
            activity.runOnUiThread(() -> showDialog(activity, id, name, list, hasContainer, where));
        }, "BhSaveBackupList").start();
    }

    private static void showDialog(Activity activity, String id, String name, List<File> list, boolean hasContainer,
                                   File where) {
        StringBuilder msg = new StringBuilder();
        msg.append("Copies this game's saves and settings (Documents, AppData, Saved Games and Steam saves in its "
                + "Wine container) to your phone's storage:\n")
                .append(where.getAbsolutePath().replace(Environment.getExternalStorageDirectory().getPath(),
                        "Internal storage"))
                .append("\n\n");
        msg.append(list.isEmpty() ? "No backups yet." : "Latest backup: " + describe(list.get(0)) + " ("
                + list.size() + (list.size() == 1 ? " backup" : " backups") + " kept: up to " + KEEP
                + " of yours and " + KEEP_AUTO + " automatic)");
        if (!hasContainer) {
            msg.append("\n\nThis game has no container right now (not launched since it was installed). "
                    + "Start it once, close it, then restore.");
        }
        android.widget.CheckBox auto = new android.widget.CheckBox(activity);
        auto.setText("Back up automatically after playing (only when saves changed; keeps the last "
                + KEEP_AUTO + ", apart from yours)");
        auto.setChecked(autoEnabled(activity));
        auto.setOnCheckedChangeListener((v, on) -> setAutoEnabled(activity, on));
        int pad = BhPlayTime.dp(activity, 20);
        auto.setPadding(auto.getPaddingLeft(), BhPlayTime.dp(activity, 8), pad, 0);
        FrameLayout box = new FrameLayout(activity);
        box.setPadding(pad, 0, pad, 0);
        box.addView(auto);
        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle("Saves: " + name)
                .setMessage(msg.toString())
                .setView(box)
                .setNegativeButton("Close", null);
        if (hasContainer) b.setPositiveButton("Back up now", (d, w) -> runBackup(activity, id, name, null));
        if (hasContainer && !list.isEmpty()) {
            b.setNeutralButton("Restore…", (d, w) -> chooseRestore(activity, id, name, list));
        }
        b.show();
    }

    private static void chooseRestore(Activity activity, String id, String name, List<File> list) {
        String[] items = new String[list.size()];
        for (int i = 0; i < items.length; i++) items[i] = describe(list.get(i));
        new AlertDialog.Builder(activity)
                .setTitle("Restore which backup?")
                .setItems(items, (d, which) -> new AlertDialog.Builder(activity)
                        .setTitle("Restore this backup?")
                        .setMessage(items[which] + "\n\nThe game's saves become exactly what this backup holds: "
                                + "saves made since then are removed. Your current saves are backed up first, "
                                + "so you can go back. Make sure the game isn't running.")
                        .setPositiveButton("Restore", (d2, w2) -> runBackup(activity, id, name, list.get(which)))
                        .setNegativeButton("Cancel", null)
                        .show())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Backs up now; with restoreFrom, backs up the current state and then restores that backup. */
    private static void runBackup(Activity activity, String id, String name, File restoreFrom) {
        AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle(restoreFrom == null ? "Backing up saves…" : "Restoring saves…")
                .setMessage("Please wait.")
                .setCancelable(false)
                .show();
        new Thread(() -> {
            String result;
            File made = null;
            try {
                made = backup(activity, id, name, restoreFrom); // never prunes the backup being restored
                if (restoreFrom == null) {
                    result = "Backed up: " + describe(made);
                } else {
                    int n = restore(activity, id, restoreFrom);
                    result = "Restored " + n + " files from " + describe(restoreFrom)
                            + ". Your previous saves are in the backup from " + describe(made) + ".";
                }
            } catch (Throwable t) {
                Log.w(TAG, "backup/restore failed", t);
                String why = t.getMessage() != null ? t.getMessage() : t.toString();
                if (restoreFrom == null) result = "Backup failed: " + why;
                else if (made == null) result = "Restore not started (the safety backup failed): " + why;
                else result = "Restore failed: " + why + ". Your saves from before are in the backup from "
                        + describe(made) + ": restore that one to go back.";
            }
            String r = result;
            activity.runOnUiThread(() -> {
                try {
                    progress.dismiss();
                } catch (Throwable ignored) {
                }
                Toast.makeText(activity, r, Toast.LENGTH_LONG).show();
            });
        }, "BhSaveBackup").start();
    }

    // ── Backup and restore ───────────────────────────────────────────────────

    static File container(Context ctx, String id) {
        return new File(ctx.getFilesDir(), "usr/home/virtual_containers/" + id.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    private static File savesRoot() {
        return new File(Environment.getExternalStorageDirectory(), "BunnerHub-Etech/saves");
    }

    /** The game's backup folder: an existing "<any name> (<id>)" folder, else "<name> (<id>)". */
    static File dir(String name, String id) {
        File found = findDir(id);
        if (found != null) return found;
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        if (safe.length() > 60) safe = safe.substring(0, 60).trim();
        return new File(savesRoot(), safe + " (" + id + ")");
    }

    /** The backup folder of a game id whatever its name part (names can change), or null. */
    static File findDir(String id) {
        String suffix = " (" + id + ")";
        File[] dirs = savesRoot().listFiles(f -> f.isDirectory() && f.getName().endsWith(suffix));
        if (dirs == null || dirs.length == 0) return null;
        Arrays.sort(dirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return dirs[0];
    }

    /** Backups of a game, newest first. */
    static List<File> backups(String name, String id) {
        List<File> out = new ArrayList<>();
        File[] f = dir(name, id).listFiles((d, n) -> n.startsWith("saves-") && n.endsWith(".zip"));
        if (f != null) out.addAll(Arrays.asList(f));
        out.sort((a, b) -> b.getName().compareTo(a.getName()));
        return out;
    }

    private static final String INFO = "bunnerhub-saves.txt";
    private static final String[] STEAM = {"drive_c/Program Files (x86)/Steam", "drive_c/Program Files/Steam"};

    /** The container's Steam folder that has userdata (the first found), or null. */
    static String steamDir(File container) {
        for (String st : STEAM) if (new File(container, st + "/userdata").isDirectory()) return st;
        return null;
    }

    /**
     * Zips the container's save folders; returns the new backup. Keeps the
     * newest KEEP backups, never deleting {@code keep} (a backup about to be
     * restored).
     */
    static File backup(Context ctx, String id, String name, File keep) throws Exception {
        return backup(ctx, id, name, keep, false);
    }

    static synchronized File backup(Context ctx, String id, String name, File keep, boolean auto) throws Exception {
        File container = container(ctx, id);
        if (!container.isDirectory()) throw new IllegalStateException("This game has no container yet");
        File dir = dir(name, id);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create " + dir);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File out = new File(dir, "saves-" + stamp + (auto ? "-auto" : "") + ".zip");
        File part = new File(dir, out.getName() + ".part");
        long[] size = {0};
        java.util.Set<String> seen = new java.util.HashSet<>(); // folders already zipped (links can loop)
        seen.add(savesRoot().getCanonicalPath()); // never zip the backups themselves
        String steam = steamDir(container);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(part))) {
            // Always one entry (an empty zip is an error), recording where Steam's saves came from.
            z.putNextEntry(new ZipEntry(INFO));
            z.write(("BunnerHub Etech save backup\ngame id: " + id + "\nname: " + name + "\nsteam: "
                    + (steam == null ? "" : steam) + "\n").getBytes("UTF-8"));
            z.closeEntry();
            File users = new File(container, "drive_c/users");
            if (users.isDirectory()) add(z, users, "users", size, seen);
            if (steam != null) add(z, new File(container, steam + "/userdata"), "steam_userdata", size, seen);
        } catch (Exception e) {
            if (!part.delete()) Log.w(TAG, "cannot delete " + part);
            throw e;
        }
        if (!part.renameTo(out)) throw new IllegalStateException("Cannot save the backup");
        // Manual and automatic backups are pruned separately, so automatic ones never push out yours.
        List<File> all = backups(name, id);
        int kept = 0, keptAuto = 0;
        for (File f : all) {
            boolean isAuto = isAuto(f);
            if (f.equals(keep) || f.equals(out) || (isAuto ? keptAuto < KEEP_AUTO : kept < KEEP)) {
                if (!f.equals(keep)) {
                    if (isAuto) keptAuto++;
                    else kept++;
                }
                continue;
            }
            if (!f.delete()) Log.w(TAG, "cannot delete " + f);
        }
        if (size[0] > WARN_BYTES) Log.w(TAG, "large backup: " + (size[0] >> 20) + " MB");
        return out;
    }

    private static void add(ZipOutputStream z, File f, String path, long[] size, java.util.Set<String> seen)
            throws Exception {
        if (f.isDirectory()) {
            if (skip(f.getName())) return;
            if (!seen.add(f.getCanonicalPath())) return; // a link back to a folder already zipped
            File[] kids = f.listFiles();
            if (kids == null) return;
            Arrays.sort(kids);
            if (kids.length == 0) {
                z.putNextEntry(new ZipEntry(path + "/"));
                z.closeEntry();
            }
            for (File k : kids) add(z, k, path + "/" + k.getName(), size, seen);
            return;
        }
        if (!f.isFile()) return; // sockets, broken links
        ZipEntry e = new ZipEntry(path);
        e.setTime(f.lastModified());
        z.putNextEntry(e);
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                z.write(buf, 0, n);
                size[0] += n;
                if (size[0] > MAX_BYTES) {
                    throw new IllegalStateException("Over 4 GB: the save folders hold more than saves");
                }
            }
        }
        z.closeEntry();
    }

    static boolean skip(String folder) {
        String n = folder.toLowerCase(Locale.US);
        for (String s : SKIP) if (n.equals(s)) return true;
        return false;
    }

    /** Where a zip entry goes in the container, or null (not ours, or unsafe). */
    private static String target(String entry, String steam) {
        String name = entry.replace('\\', '/');
        if (name.startsWith("/") || ("/" + name + "/").contains("/../")) return null; // no escapes
        if (name.equals("users") || name.startsWith("users/")) return "drive_c/" + name;
        if (name.equals("steam_userdata") || name.startsWith("steam_userdata/")) {
            return steam + "/userdata" + name.substring("steam_userdata".length());
        }
        return null;
    }

    /**
     * Makes the container's save folders exactly what the backup holds:
     * checks the whole zip first, clears the folders it covers (except cache
     * folders, which backups leave out), then writes each file through a
     * temporary file. Returns the number of files written.
     */
    static synchronized int restore(Context ctx, String id, File backup) throws Exception {
        File container = container(ctx, id);
        if (!container.isDirectory()) throw new IllegalStateException("This game has no container yet");

        // 1. Read the whole zip once (checks every entry's CRC) and find the Steam folder it came from.
        String steam = null;
        boolean hasSteam = false;
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(backup))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().equals(INFO)) {
                    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                    int n;
                    while ((n = z.read(buf)) > 0) b.write(buf, 0, n);
                    for (String line : b.toString("UTF-8").split("\n")) {
                        if (line.startsWith("steam: ") && line.length() > 7) steam = line.substring(7).trim();
                    }
                } else {
                    if (e.getName().startsWith("steam_userdata")) hasSteam = true;
                    while (z.read(buf) > 0) { /* CRC check */ }
                }
            }
        }
        if (steam == null || steam.isEmpty()) {
            String now = steamDir(container);
            steam = now != null ? now : STEAM[0];
        }

        // 2. Clear what the backup covers (its pre-restore copy was just made).
        clear(new File(container, "drive_c/users"));
        if (hasSteam) clear(new File(container, steam + "/userdata"));

        // 3. Write the files.
        int files = 0;
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(backup))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                String t = target(e.getName(), steam);
                if (t == null) continue;
                File out = new File(container, t);
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                File tmp = new File(parent, out.getName() + ".bhrestore");
                try (OutputStream o = new FileOutputStream(tmp)) {
                    int n;
                    while ((n = z.read(buf)) > 0) o.write(buf, 0, n);
                }
                if (!tmp.renameTo(out)) {
                    if (!tmp.delete()) Log.w(TAG, "cannot delete " + tmp);
                    throw new IllegalStateException("Cannot write " + t);
                }
                if (e.getTime() > 0) out.setLastModified(e.getTime());
                files++;
            }
        }
        return files;
    }

    /**
     * Deletes the files under dir (the save folders a restore replaces),
     * keeping cache folders and never deleting through links.
     */
    static void clear(File dir) throws Exception {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            boolean link = !k.getCanonicalPath().equals(k.getAbsoluteFile().getParentFile().getCanonicalPath()
                    + File.separator + k.getName());
            if (k.isDirectory() && !link) {
                if (skip(k.getName())) continue;
                clear(k);
                File[] left = k.listFiles();
                if (left != null && left.length == 0 && !k.delete()) Log.w(TAG, "cannot delete " + k);
            } else if (k.isFile() && !link) {
                if (!k.delete()) throw new IllegalStateException("Cannot replace " + k.getName());
            }
            // A link (e.g. Documents pointing elsewhere) is left alone: what it points to may
            // not be the game's to delete. The restore writes through it.
        }
    }

    // ── Automatic backup after playing ───────────────────────────────────────

    static boolean isAuto(File backup) {
        return backup.getName().endsWith("-auto.zip");
    }

    /** Off = this marker file exists. A file, not SharedPreferences: the :wine process (which
        backs up when a game closes) would keep a stale cached copy of the main process's prefs. */
    private static File autoOffFile(Context ctx) {
        return new File(ctx.getFilesDir(), "bh_saves_auto_off");
    }

    static boolean autoEnabled(Context ctx) {
        return !autoOffFile(ctx).exists();
    }

    static void setAutoEnabled(Context ctx, boolean on) {
        File f = autoOffFile(ctx);
        try {
            if (on) f.delete();
            else f.createNewFile();
        } catch (Exception e) {
            Log.w(TAG, "cannot change the automatic backup setting", e);
        }
    }

    /** BhPlayTime.onGamePause when the game screen closes: back up a moment later, off the UI thread. */
    static void autoBackupAsync(Context ctx, String id, String name) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                Thread.sleep(3000); // let the game's last writes land
            } catch (InterruptedException ignored) {
            }
            autoBackup(app, id, name);
        }, "BhSaveBackupAuto").start();
    }

    /**
     * Backs up the game's saves if any save file is newer than its newest backup (automatic or
     * not) and automatic backups are on. Safe to call from two processes at once (a lock file).
     */
    static void autoBackup(Context ctx, String id, String name) {
        try {
            if (!autoEnabled(ctx)) return;
            File container = container(ctx, id);
            if (!container.isDirectory()) return;
            String n = name != null && !name.isEmpty() ? name : "Game " + id;
            List<File> list = backups(n, id);
            long last = list.isEmpty() ? 0 : list.get(0).lastModified();
            long newest = newestSave(container);
            if (newest <= last + 2000) return; // nothing changed since the last backup
            File dir = dir(n, id);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            // An OS file lock: held by one process at a time and released if that process dies.
            File lockFile = new File(dir, ".auto.lock");
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(lockFile, "rw");
                 java.nio.channels.FileLock lock = raf.getChannel().tryLock()) {
                if (lock == null) return; // the other process is backing up right now
                // Again under the lock: the other process may have just made one.
                List<File> again = backups(n, id);
                long lastNow = again.isEmpty() ? 0 : again.get(0).lastModified();
                if (newestSave(container) <= lastNow + 2000) return;
                File made = backup(ctx, id, n, null, true);
                Log.i(TAG, "automatic backup: " + made);
            }
        } catch (Throwable t) {
            Log.w(TAG, "automatic backup failed", t);
        }
    }

    /** The newest modification time of the files a backup would hold (0 if none). */
    static long newestSave(File container) {
        long[] newest = {0};
        int[] budget = {200_000};
        java.util.Set<String> seen = new java.util.HashSet<>();
        newest(new File(container, "drive_c/users"), newest, budget, seen);
        String steam = steamDir(container);
        if (steam != null) newest(new File(container, steam + "/userdata"), newest, budget, seen);
        return newest[0];
    }

    private static void newest(File f, long[] newest, int[] budget, java.util.Set<String> seen) {
        if (--budget[0] < 0) return;
        if (f.isDirectory()) {
            if (skip(f.getName())) return;
            try {
                if (!seen.add(f.getCanonicalPath())) return;
            } catch (Exception e) {
                return;
            }
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) newest(k, newest, budget, seen);
        } else {
            long m = f.lastModified();
            if (m > newest[0]) newest[0] = m;
        }
    }

    static String describe(File backup) {
        String n = backup.getName();
        String when = n;
        try {
            Date d = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(n.substring(6, 21));
            when = new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(d);
        } catch (Throwable ignored) {
        }
        return when + "  ·  " + humanSize(backup.length()) + (isAuto(backup) ? "  ·  automatic" : "");
    }

    private static String humanSize(long b) {
        if (b < 1024 * 1024) return Math.max(1, b / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }

    private static Object call(Object o, String method) {
        try {
            Method m = o.getClass().getMethod(method);
            return m.invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }
}
