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
        try {
            String id = gameId(activity, entity);
            Object nameObj = call(entity, "getName");
            String name = nameObj instanceof String && !((String) nameObj).isEmpty() ? (String) nameObj : "Game " + id;
            if (id == null) return;
            boolean has = container(activity, id).isDirectory() || !backups(name, id).isEmpty();
            activity.runOnUiThread(() -> {
                if (has) showChip(activity, id, name);
                else removeChip(activity);
            });
        } catch (Throwable t) {
            Log.w(TAG, "onDetail failed", t);
        }
    }

    /** The game's container id: the server id if a container exists for it, else the local id. */
    static String gameId(Context ctx, Object entity) {
        Object server = call(entity, "getId");
        Object local = call(entity, "getLocalGameId");
        String s = server instanceof Integer && (Integer) server > 0 ? String.valueOf(server) : null;
        String l = local instanceof String && !((String) local).isEmpty() ? (String) local : null;
        if (s != null && container(ctx, s).isDirectory()) return s;
        if (l != null && container(ctx, l).isDirectory()) return l;
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
        List<File> list = backups(name, id);
        boolean hasContainer = container(activity, id).isDirectory();
        StringBuilder msg = new StringBuilder();
        msg.append("Copies this game's saves and settings (Documents, AppData, Saved Games and Steam saves in its "
                + "Wine container) to your phone's storage:\n")
                .append(dir(name, id).getAbsolutePath().replace(Environment.getExternalStorageDirectory().getPath(),
                        "Internal storage"))
                .append("\n\n");
        msg.append(list.isEmpty() ? "No backups yet." : "Latest backup: " + describe(list.get(0)) + " ("
                + list.size() + (list.size() == 1 ? " backup" : " backups") + " kept, up to " + KEEP + ")");
        if (!hasContainer) {
            msg.append("\n\nThis game has no container right now (not launched since it was installed). "
                    + "Start it once, close it, then restore.");
        }
        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle("Saves: " + name)
                .setMessage(msg.toString())
                .setNegativeButton("Close", null);
        if (hasContainer) b.setPositiveButton("Back up now", (d, w) -> runBackup(activity, id, name, null));
        if (hasContainer && !list.isEmpty()) b.setNeutralButton("Restore…", (d, w) -> chooseRestore(activity, id, name));
        b.show();
    }

    private static void chooseRestore(Activity activity, String id, String name) {
        List<File> list = backups(name, id);
        String[] items = new String[list.size()];
        for (int i = 0; i < items.length; i++) items[i] = describe(list.get(i));
        new AlertDialog.Builder(activity)
                .setTitle("Restore which backup?")
                .setItems(items, (d, which) -> new AlertDialog.Builder(activity)
                        .setTitle("Restore this backup?")
                        .setMessage(items[which] + "\n\nThe game's current saves are replaced. They are backed up "
                                + "first, so you can go back. Make sure the game isn't running.")
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
            try {
                File made = backup(activity, id, name);
                if (restoreFrom == null) {
                    result = "Backed up: " + describe(made);
                } else {
                    int n = restore(activity, id, restoreFrom);
                    result = "Restored " + n + " files from " + describe(restoreFrom)
                            + ". Your previous saves are in the backup from " + describe(made) + ".";
                }
            } catch (Throwable t) {
                Log.w(TAG, "backup/restore failed", t);
                result = (restoreFrom == null ? "Backup failed: " : "Restore failed: ")
                        + (t.getMessage() != null ? t.getMessage() : t.toString());
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

    static File dir(String name, String id) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        if (safe.length() > 60) safe = safe.substring(0, 60).trim();
        return new File(Environment.getExternalStorageDirectory(), "BunnerHub-Etech/saves/" + safe + " (" + id + ")");
    }

    /** Backups of a game, newest first. */
    static List<File> backups(String name, String id) {
        List<File> out = new ArrayList<>();
        File[] f = dir(name, id).listFiles((d, n) -> n.startsWith("saves-") && n.endsWith(".zip"));
        if (f != null) out.addAll(Arrays.asList(f));
        out.sort((a, b) -> b.getName().compareTo(a.getName()));
        return out;
    }

    /** What a backup holds: the folders below, by their path inside the zip. */
    static List<String[]> sources(File container) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"drive_c/users", "users"});
        for (String steam : new String[]{"drive_c/Program Files (x86)/Steam/userdata", "drive_c/Program Files/Steam/userdata"}) {
            if (new File(container, steam).isDirectory()) out.add(new String[]{steam, "steam_userdata"});
        }
        return out;
    }

    /** Zips the container's save folders; returns the new backup. */
    static synchronized File backup(Context ctx, String id, String name) throws Exception {
        File container = container(ctx, id);
        if (!container.isDirectory()) throw new IllegalStateException("This game has no container yet");
        File dir = dir(name, id);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create " + dir);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File out = new File(dir, "saves-" + stamp + ".zip");
        File part = new File(dir, out.getName() + ".part");
        long[] size = {0};
        java.util.Set<String> seen = new java.util.HashSet<>(); // folders already zipped (links can loop)
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(part))) {
            for (String[] src : sources(container)) {
                File root = new File(container, src[0]);
                if (root.isDirectory()) add(z, root, src[1], size, seen);
            }
        } catch (Exception e) {
            if (!part.delete()) Log.w(TAG, "cannot delete " + part);
            throw e;
        }
        if (!part.renameTo(out)) throw new IllegalStateException("Cannot save the backup");
        // Keep the newest KEEP backups.
        List<File> all = backups(name, id);
        for (int i = KEEP; i < all.size(); i++) {
            if (!all.get(i).delete()) Log.w(TAG, "cannot delete " + all.get(i));
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

    /** Unzips a backup into the container (overwriting); returns the number of files written. */
    static synchronized int restore(Context ctx, String id, File backup) throws Exception {
        File container = container(ctx, id);
        if (!container.isDirectory()) throw new IllegalStateException("This game has no container yet");
        String root = container.getCanonicalPath() + File.separator;
        int files = 0;
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(backup))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                String target;
                if (name.startsWith("users/") || name.equals("users")) {
                    target = "drive_c/" + name;
                } else if (name.startsWith("steam_userdata/") || name.equals("steam_userdata")) {
                    String steam = new File(container, "drive_c/Program Files/Steam").isDirectory()
                            && !new File(container, "drive_c/Program Files (x86)/Steam").isDirectory()
                            ? "drive_c/Program Files/Steam/userdata" : "drive_c/Program Files (x86)/Steam/userdata";
                    target = steam + name.substring("steam_userdata".length());
                } else {
                    continue; // not ours
                }
                File out = new File(container, target);
                if (!out.getCanonicalPath().startsWith(root)) continue; // no ../ escapes
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    int n;
                    while ((n = z.read(buf)) > 0) o.write(buf, 0, n);
                }
                if (e.getTime() > 0) out.setLastModified(e.getTime());
                files++;
            }
        }
        return files;
    }

    static String describe(File backup) {
        String n = backup.getName();
        String when = n;
        try {
            Date d = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(n.substring(6, 21));
            when = new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(d);
        } catch (Throwable ignored) {
        }
        return when + "  ·  " + humanSize(backup.length());
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
