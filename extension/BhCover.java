package app.revanced.extension.gamehub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * BhCover — your own cover picture for a game.
 *
 * GameHub only lets you pick a cover while importing a game. This keeps a
 * picture per game in files/bh_covers/ (index.properties: key → file) and
 * hands it out instead of the game's own: build.yml patches
 * GameDetailEntity.getCover_image() and getSquare_image() to return
 * {@link #cover}'s result, so the library cards and the game page show it
 * wherever GameHub loads the cover (Glide takes the file path). The library
 * database is never changed; "Use original" removes the override.
 *
 * Keys: "l_<localGameId>" and "g_<server id>" (both are stored, either matches).
 * The picker (a 🖼 Cover chip on the game page) lists recent pictures from
 * Pictures, DCIM, Download and BunnerHub-Etech/screenshots.
 */
public final class BhCover {

    private static final String TAG = "BhCover";
    private static final String CHIP_TAG = "bh_cover_chip";
    private static final String DIR = "bh_covers";
    private static final int MAX_SIDE = 1280, MAX_PICKS = 60;

    private static volatile Map<String, String> covers;
    private static Method localIdM, idM;

    private BhCover() {}

    // ── Hook (GameDetailEntity getters) ──────────────────────────────────────

    /** GameDetailEntity.getCover_image / getSquare_image: the custom picture's path, else {@code original}. */
    public static String cover(Object entity, String original) {
        Map<String, String> m = covers;
        if (m == null) m = load();
        if (m.isEmpty() || entity == null) return original;
        try {
            for (String key : keys(entity)) {
                String path = m.get(key);
                if (path != null) return path;
            }
        } catch (Throwable t) {
            // never break GameHub's own cover
        }
        return original;
    }

    private static List<String> keys(Object entity) throws Exception {
        if (localIdM == null) localIdM = entity.getClass().getMethod("getLocalGameId");
        if (idM == null) idM = entity.getClass().getMethod("getId");
        List<String> k = new ArrayList<>(2);
        Object l = localIdM.invoke(entity);
        if (l instanceof String && !((String) l).isEmpty()) k.add("l_" + l);
        Object id = idM.invoke(entity);
        if (id instanceof Integer && (Integer) id > 0) k.add("g_" + id);
        return k;
    }

    private static synchronized Map<String, String> load() {
        if (covers != null) return covers;
        Context c = app();
        if (c == null) return Collections.emptyMap(); // try again on the next call
        Map<String, String> m = new HashMap<>();
        Properties p = readIndex(c);
        File dir = dir(c);
        for (String key : p.stringPropertyNames()) {
            File f = new File(dir, p.getProperty(key));
            if (f.isFile()) m.put(key, f.getAbsolutePath());
        }
        covers = m;
        return m;
    }

    private static Context app() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            return (Context) at.getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Storage ──────────────────────────────────────────────────────────────

    private static File dir(Context c) {
        return new File(c.getFilesDir(), DIR);
    }

    private static Properties readIndex(Context c) {
        Properties p = new Properties();
        File f = new File(dir(c), "index.properties");
        if (f.isFile()) {
            try (FileInputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (Throwable t) {
                Log.w(TAG, "cannot read the index", t);
            }
        }
        return p;
    }

    private static void writeIndex(Context c, Properties p) throws Exception {
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) throw new IllegalStateException("cannot create " + d);
        File tmp = new File(d, "index.properties.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            p.store(out, "BunnerHub custom covers: key = file");
        }
        if (!tmp.renameTo(new File(d, "index.properties"))) throw new IllegalStateException("cannot save the index");
    }

    /** Saves {@code picture} (scaled down) as the cover for these keys. Off the UI thread. */
    static synchronized void set(Context c, List<String> keys, File picture) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(picture.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IllegalArgumentException("not a picture");
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(picture.getAbsolutePath(), d);
        if (bmp == null) throw new IllegalArgumentException("cannot read the picture");
        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(bmp.getWidth(), bmp.getHeight()));
        if (scale < 1f) {
            Bitmap s = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * scale),
                    Math.round(bmp.getHeight() * scale), true);
            bmp.recycle();
            bmp = s;
        }
        File dir = dir(c);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
        // A new name each time: Glide caches by path, so an overwritten file would keep showing the old picture.
        String name = keys.get(0).replaceAll("[^A-Za-z0-9_-]", "_") + "_" + System.currentTimeMillis() + ".jpg";
        File out = new File(dir, name);
        try (FileOutputStream os = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, os);
        }
        bmp.recycle();
        Properties p = readIndex(c);
        for (String k : keys) p.setProperty(k, name);
        writeIndex(c, p);
        prune(c, p);
        covers = null; // reload with the new picture
    }

    /** Back to GameHub's own cover. */
    static synchronized void clear(Context c, List<String> keys) throws Exception {
        Properties p = readIndex(c);
        for (String k : keys) p.remove(k);
        writeIndex(c, p);
        prune(c, p);
        covers = null;
    }

    static boolean has(Context c, List<String> keys) {
        Properties p = readIndex(c);
        for (String k : keys) if (p.getProperty(k) != null) return true;
        return false;
    }

    /** Deletes pictures no key points to any more. */
    private static void prune(Context c, Properties p) {
        File[] files = dir(c).listFiles();
        if (files == null) return;
        java.util.Set<String> used = new java.util.HashSet<>();
        for (String k : p.stringPropertyNames()) used.add(p.getProperty(k));
        for (File f : files) {
            if (f.getName().endsWith(".jpg") && !used.contains(f.getName())) f.delete();
        }
    }

    // ── Game page chip and picker ────────────────────────────────────────────

    /** From BhPlayTime.onDetail (GameDetailActivity's observer). */
    static void onDetail(Activity activity, Object entity) {
        try {
            List<String> keys = keys(entity);
            if (keys.isEmpty()) return;
            Object nameObj = entity.getClass().getMethod("getName").invoke(entity);
            String name = nameObj instanceof String && !((String) nameObj).isEmpty() ? (String) nameObj : "this game";
            activity.runOnUiThread(() -> showChip(activity, keys, name));
        } catch (Throwable t) {
            Log.w(TAG, "onDetail failed", t);
        }
    }

    private static void showChip(Activity activity, List<String> keys, String name) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return;
        View existing = content.findViewWithTag(CHIP_TAG);
        TextView chip;
        if (existing instanceof TextView) {
            chip = (TextView) existing;
        } else {
            chip = BhUi.text(activity, 13, 0xFFFFFFFF, true);
            chip.setTag(CHIP_TAG);
            chip.setPadding(BhUi.dp(activity, 12), BhUi.dp(activity, 6), BhUi.dp(activity, 12), BhUi.dp(activity, 6));
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setBackground(BhUi.states(activity, 0xB3000000, 0x66FFFFFF, 16));
            chip.setText("🖼  Cover");
            chip.setContentDescription("Change this game's cover picture");
            // Below the Saves chip (BhSaveBackup puts it at 136 dp).
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            lp.topMargin = BhUi.dp(activity, 180);
            lp.rightMargin = BhUi.dp(activity, 24);
            content.addView(chip, lp);
        }
        BhUi.onTap(chip, () -> showPicker(activity, keys, name));
    }

    private static void showPicker(Activity activity, List<String> keys, String name) {
        Context c = activity;
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(BhUi.dp(c, 20), BhUi.dp(c, 18), BhUi.dp(c, 20), BhUi.dp(c, 12));
        FrameLayout host = BhUi.host(c, root);
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(host).create();

        TextView title = BhUi.text(c, 18, BhUi.WHITE, true);
        title.setText("Cover picture");
        root.addView(title);
        TextView sub = BhUi.text(c, 12, BhUi.GREY, false);
        sub.setText(name + "  ·  pictures from Pictures, DCIM, Download and BunnerHub-Etech/screenshots");
        root.addView(sub);

        LinearLayout grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(c);
        scroll.addView(grid);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sl.topMargin = BhUi.dp(c, 12);
        root.addView(scroll, sl);
        TextView loading = BhUi.text(c, 13, BhUi.GREY, false);
        loading.setText("Looking for pictures…");
        grid.addView(loading);

        LinearLayout bottom = BhUi.row(c);
        bottom.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        android.widget.Button original = BhUi.button(c, "Use original", BhUi.SECONDARY);
        original.setVisibility(View.GONE);
        bottom.addView(original);
        android.widget.Button close = BhUi.button(c, "Close", BhUi.QUIET);
        BhUi.onTap(close, dialog::dismiss);
        bottom.addView(close, BhUi.next(c, 8));
        root.addView(bottom, BhUi.below(c, 8));

        BhUi.onTap(original, () -> new Thread(() -> {
            String msg;
            try {
                clear(activity, keys);
                msg = "Back to the original cover";
            } catch (Throwable t) {
                msg = "Couldn't change it: " + t.getMessage();
            }
            String m = msg;
            activity.runOnUiThread(() -> {
                dialog.dismiss();
                BhUi.snack(contentHost(activity), m, null, null);
                activity.recreate();
            });
        }, "BhCover").start());

        dialog.show();
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(BhUi.outlined(c, 0xFF161C3D, 0xFF2E3666, 16));
            android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
            w.setLayout(Math.min(BhUi.dp(c, 720), (int) (dm.widthPixels * 0.92f)),
                    Math.min(BhUi.dp(c, 600), (int) (dm.heightPixels * 0.92f)));
        }

        new Thread(() -> {
            boolean custom = has(activity, keys);
            List<File> pics = recentPictures();
            int cell = BhUi.dp(c, 120);
            List<Bitmap> thumbs = new ArrayList<>();
            for (File f : pics) thumbs.add(thumb(f, cell));
            activity.runOnUiThread(() -> {
                original.setVisibility(custom ? View.VISIBLE : View.GONE);
                grid.removeAllViews();
                if (pics.isEmpty()) {
                    loading.setText("No pictures found. Save the picture you want in Download or Pictures, then try "
                            + "again.");
                    grid.addView(loading);
                    return;
                }
                LinearLayout line = null;
                int perRow = 5;
                for (int i = 0; i < pics.size(); i++) {
                    if (i % perRow == 0) {
                        line = BhUi.row(c);
                        grid.addView(line, BhUi.below(c, i == 0 ? 0 : 8));
                    }
                    File f = pics.get(i);
                    ImageView iv = new ImageView(c);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    iv.setImageBitmap(thumbs.get(i));
                    iv.setFocusable(true);
                    iv.setClickable(true);
                    iv.setClipToOutline(true);
                    iv.setBackground(BhUi.states(c, BhUi.CARD_HI, 0, 10));
                    iv.setPadding(BhUi.dp(c, 2), BhUi.dp(c, 2), BhUi.dp(c, 2), BhUi.dp(c, 2));
                    iv.setContentDescription(f.getName());
                    BhUi.onTap(iv, () -> pick(activity, dialog, host, keys, f));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, cell, 1f);
                    if (i % perRow != 0) lp.leftMargin = BhUi.dp(c, 8);
                    line.addView(iv, lp);
                }
                // Fill the last row so pictures keep their size.
                for (int i = pics.size() % perRow; i != 0 && i < perRow; i++) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, cell, 1f);
                    lp.leftMargin = BhUi.dp(c, 8);
                    line.addView(new View(c), lp);
                }
            });
        }, "BhCoverList").start();
    }

    private static void pick(Activity activity, AlertDialog dialog, FrameLayout host, List<String> keys, File f) {
        BhUi.snack(host, "Setting the cover…", null, null);
        new Thread(() -> {
            String err = null;
            try {
                set(activity, keys, f);
            } catch (Throwable t) {
                Log.w(TAG, "set failed", t);
                err = t.getMessage() != null ? t.getMessage() : t.toString();
            }
            String e = err;
            activity.runOnUiThread(() -> {
                if (e != null) {
                    BhUi.snack(host, "Couldn't use this picture: " + e, null, null);
                    return;
                }
                dialog.dismiss();
                activity.recreate(); // the game page loads the new cover
            });
        }, "BhCover").start();
    }

    private static FrameLayout contentHost(Activity a) {
        View v = a.findViewById(android.R.id.content);
        return v instanceof FrameLayout ? (FrameLayout) v : null;
    }

    /** Newest pictures first from the usual folders (one level of subfolders). */
    private static List<File> recentPictures() {
        File sd = Environment.getExternalStorageDirectory();
        List<File> roots = Arrays.asList(new File(sd, "Pictures"), new File(sd, "DCIM"), new File(sd, "Download"),
                new File(sd, "BunnerHub-Etech/screenshots"));
        List<File> all = new ArrayList<>();
        for (File r : roots) collect(r, all, 2);
        all.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return all.size() > MAX_PICKS ? new ArrayList<>(all.subList(0, MAX_PICKS)) : all;
    }

    private static void collect(File dir, List<File> out, int depth) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth > 0) collect(f, out, depth - 1);
            } else {
                String n = f.getName().toLowerCase(java.util.Locale.US);
                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) out.add(f);
            }
        }
    }

    private static Bitmap thumb(File f, int size) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int sample = 1;
            while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= size) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), d);
        } catch (Throwable t) {
            return null;
        }
    }
}
