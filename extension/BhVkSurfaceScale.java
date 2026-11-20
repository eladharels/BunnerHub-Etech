package app.revanced.extension.gamehub;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.SurfaceControl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

/**
 * BhVkSurfaceScale — makes the upscaled frame fit the screen.
 *
 * GameHub shows a game through its DirectRendering surface
 * (com.winemu.core.DirectRendering, field a: the "Direct Rendering Surface"
 * SurfaceControl), which sits in the game view scaled by display / Wine
 * screen. GameScopeVK posts each swapchain image to it at the image's own
 * pixel size, so when the Vulkan layer outputs a frame larger than the Wine
 * screen (FSR to the display size), the view's scale pushes it past the edges
 * and it is cropped (seen on the device: a zoomed-in top-left part).
 *
 * While the layer reports upscaling (status file: upscale=on, swapchain=in,
 * output=out) this scales that surface by in / out, so the frame takes the
 * Wine screen's place again and the view's scale brings it to full screen,
 * now from a full-resolution FSR image. Otherwise the scale is 1. Polls the
 * status file twice a second on its own thread while a game uses the upscaler.
 */
final class BhVkSurfaceScale {

    private static final String TAG = "BhVkSurfaceScale";
    private static final long PERIOD_MS = 500;

    private static Handler handler;
    private static Context app;
    private static boolean running;
    private static float curX = 1f, curY = 1f;
    private static String lastError;

    private BhVkSurfaceScale() {}

    /** A game launch with "FSR to display": follow the layer's reports. */
    static synchronized void watch(Context ctx) {
        app = ctx.getApplicationContext();
        if (handler == null) {
            HandlerThread t = new HandlerThread("BhVkSurfaceScale");
            t.start();
            handler = new Handler(t.getLooper());
        }
        if (!running) {
            running = true;
            handler.post(BhVkSurfaceScale::tick);
        }
    }

    /** A game launch without upscaling: back to scale 1 and stop polling. */
    static synchronized void reset() {
        running = false;
        if (handler != null) handler.post(() -> apply(1f, 1f));
    }

    private static void tick() {
        Context ctx;
        synchronized (BhVkSurfaceScale.class) {
            if (!running) return;
            ctx = app;
        }
        float sx = 1f, sy = 1f;
        try {
            Map<String, String> m = BhVkLayer.status(ctx);
            if ("on".equals(m.get("upscale"))) {
                int[] in = size(m.get("swapchain")), out = size(m.get("output"));
                if (in != null && out != null) {
                    sx = (float) in[0] / out[0];
                    sy = (float) in[1] / out[1];
                }
            }
        } catch (Throwable ignored) {
        }
        apply(sx, sy);
        synchronized (BhVkSurfaceScale.class) {
            if (running) handler.postDelayed(BhVkSurfaceScale::tick, PERIOD_MS);
        }
    }

    private static int[] size(String s) {
        if (s == null) return null;
        int x = s.indexOf('x');
        if (x <= 0) return null;
        try {
            int w = Integer.parseInt(s.substring(0, x)), h = Integer.parseInt(s.substring(x + 1));
            return w > 0 && h > 0 ? new int[]{w, h} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Sets the DirectRendering surface's scale if it changed. Runs on the handler thread. */
    private static void apply(float sx, float sy) {
        if (Math.abs(sx - curX) < 1e-4f && Math.abs(sy - curY) < 1e-4f) return;
        try {
            SurfaceControl sc = surface();
            if (sc == null) return;
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            Method set;
            try {
                set = SurfaceControl.Transaction.class.getMethod("setScale", SurfaceControl.class, float.class,
                        float.class); // API 31+
                set.invoke(t, sc, sx, sy);
            } catch (NoSuchMethodException e) {
                set = SurfaceControl.Transaction.class.getMethod("setMatrix", SurfaceControl.class, float.class,
                        float.class, float.class, float.class);
                set.invoke(t, sc, sx, 0f, 0f, sy);
            }
            t.apply();
            curX = sx;
            curY = sy;
            lastError = null;
            Log.i(TAG, "DirectRendering surface scale " + sx + " x " + sy);
        } catch (Throwable e) {
            String msg = String.valueOf(e);
            if (!msg.equals(lastError)) Log.w(TAG, "cannot scale the DirectRendering surface", e);
            lastError = msg;
        }
    }

    /** DirectRendering's instance (a static kotlin.Lazy field) → its first SurfaceControl field (a). */
    private static SurfaceControl surface() throws Exception {
        Class<?> dr = Class.forName("com.winemu.core.DirectRendering");
        Class<?> lazyType = Class.forName("kotlin.Lazy");
        Object inst = null;
        for (Field f : dr.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == lazyType) {
                f.setAccessible(true);
                Object lazy = f.get(null);
                if (lazy != null) inst = lazyType.getMethod("getValue").invoke(lazy);
                break;
            }
        }
        if (inst == null) return null;
        Field a = dr.getDeclaredField("a");
        a.setAccessible(true);
        Object sc = a.get(inst);
        return sc instanceof SurfaceControl ? (SurfaceControl) sc : null;
    }
}
