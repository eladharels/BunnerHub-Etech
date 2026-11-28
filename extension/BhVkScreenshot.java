package app.revanced.extension.gamehub;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * BhVkScreenshot — the FSR tab's Screenshot button.
 *
 * Asks the BunnerHub Vulkan layer for the frame being shown (BhVkLayer.requestShot: shot=<n> in
 * its control file). The layer copies it — the FSR output when upscaling, so full resolution and
 * without the sidebar or on-screen controls — to files/bh_vklayer_shot.raw ("BHS1", width,
 * height, channel order, pixels) and reports shot=<n> in its status file. This turns that into
 * /sdcard/BunnerHub-Etech/screenshots/<game>_<date>.png.
 */
final class BhVkScreenshot {

    private static final String TAG = "BhVkScreenshot";
    private static final long WAIT_MS = 6000;

    private static final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();

    private BhVkScreenshot() {}

    /** Called on the main thread with the result; {@code png} is the saved file, or null if none. */
    interface Done {
        void result(String message, File png);
    }

    static void take(Context ctx, String gameId, String gameName) {
        take(ctx, gameId, gameName, null);
    }

    /** Takes a screenshot; reports through {@code done}, or as toasts when it is null. */
    static void take(Context ctx, String gameId, String gameName, Done done) {
        Context app = ctx.getApplicationContext();
        if (!BhVkLayer.live(gameId)) {
            report(app, done, "Screenshots need the upscaler loaded: set it to Report only or FSR and restart the game.",
                    null);
            return;
        }
        if (!busy.compareAndSet(false, true)) return; // one at a time: they share the layer's file
        BhVkLayer.shotFile(app).delete(); // never mistake an old capture for this one
        int req = BhVkLayer.requestShot(app, gameId);
        if (req < 0) {
            busy.set(false);
            return;
        }
        if (done == null) toast(app, "Taking a screenshot…");
        new Thread(() -> {
            String msg;
            File[] saved = new File[1];
            try {
                msg = waitAndSave(app, req, gameName != null ? gameName : gameId, saved);
            } catch (Throwable t) {
                Log.w(TAG, "screenshot failed", t);
                msg = "Screenshot failed: " + t.getMessage();
            } finally {
                busy.set(false);
            }
            report(app, done, msg, saved[0]);
        }, "BhVkScreenshot").start();
    }

    private static void report(Context app, Done done, String msg, File png) {
        if (done == null) toast(app, msg);
        else new Handler(Looper.getMainLooper()).post(() -> done.result(msg, png));
    }

    /** Opens Android's share sheet for a saved screenshot (through the app's FileProvider). */
    static void share(Context ctx, File png) {
        try {
            File dir = new File(ctx.getExternalCacheDir(), "share");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
            File copy = new File(dir, png.getName());
            try (FileInputStream in = new FileInputStream(png); FileOutputStream out = new FileOutputStream(copy)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            android.net.Uri uri = (android.net.Uri) Class.forName("androidx.core.content.FileProvider")
                    .getMethod("getUriForFile", Context.class, String.class, File.class)
                    .invoke(null, ctx, ctx.getPackageName() + ".provider", copy);
            android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
            send.setType("image/png");
            send.putExtra(android.content.Intent.EXTRA_STREAM, uri);
            send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            android.content.Intent chooser = android.content.Intent.createChooser(send, "Share screenshot");
            if (!(ctx instanceof android.app.Activity)) chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(chooser);
        } catch (Throwable t) {
            Log.w(TAG, "share failed", t);
            toast(ctx.getApplicationContext(), "Share failed. The file is in BunnerHub-Etech/screenshots.");
        }
    }

    private static String waitAndSave(Context app, int req, String name, File[] saved) throws Exception {
        long end = System.currentTimeMillis() + WAIT_MS;
        Map<String, String> st;
        while (true) {
            st = BhVkLayer.status(app);
            if (parse(st.get("shot")) >= req) break;
            if (System.currentTimeMillis() > end) {
                return "No screenshot: the game didn't draw a frame in time (is it paused?).";
            }
            Thread.sleep(100);
        }
        String note = st.get("shot_note");
        if (note != null && !note.isEmpty()) return "No screenshot: " + note + ".";

        File raw = BhVkLayer.shotFile(app);
        int w, h, order;
        byte[] px;
        try (DataInputStream in = new DataInputStream(new FileInputStream(raw))) {
            int magic = Integer.reverseBytes(in.readInt());
            w = Integer.reverseBytes(in.readInt());
            h = Integer.reverseBytes(in.readInt());
            order = Integer.reverseBytes(in.readInt());
            if (magic != 0x31534842 || w <= 0 || h <= 0 || w > 8192 || h > 8192) return "No screenshot: bad file.";
            px = new byte[w * h * 4];
            in.readFully(px);
        }
        for (int i = 0; i < px.length; i += 4) {
            if (order == 1) { // BGRA → RGBA
                byte t = px[i];
                px[i] = px[i + 2];
                px[i + 2] = t;
            }
            px[i + 3] = (byte) 0xFF;
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(px));

        File dir = new File(Environment.getExternalStorageDirectory(), "BunnerHub-Etech/screenshots");
        if (!dir.isDirectory() && !dir.mkdirs()) return "No screenshot: cannot create " + dir;
        String safe = name.replaceAll("[^A-Za-z0-9 ._-]", "").trim();
        if (safe.isEmpty()) safe = "game";
        File out = new File(dir, safe + "_" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + ".png");
        try (FileOutputStream o = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
        bmp.recycle();
        raw.delete();
        try {
            MediaScannerConnection.scanFile(app, new String[]{out.getAbsolutePath()}, new String[]{"image/png"}, null);
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "saved " + out + " (" + w + "x" + h + ")");
        saved[0] = out;
        return "Screenshot saved (" + w + "x" + h + "): BunnerHub-Etech/screenshots/" + out.getName();
    }

    private static int parse(String s) {
        try {
            return s == null ? -1 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void toast(Context app, String msg) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app, msg, Toast.LENGTH_LONG).show());
    }
}
