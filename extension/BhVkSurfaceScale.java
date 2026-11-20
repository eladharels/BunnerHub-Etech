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
    private static long lastApplyMs;
    /** What happened last, for the FSR tab ("Screen fit: …"). */
    static volatile String result = "not started";
    /**
     * Whether GameHub's Native Rendering+ (DirectRendering) is on: null until it reports.
     * Without it frames go through GameHub's X server, which crops the larger frame.
     */
    static volatile Boolean direct;
    private static boolean listening;

    private BhVkSurfaceScale() {}

    /** A game launch with "FSR to display": follow the layer's reports. */
    static synchronized void watch(Context ctx) {
        app = ctx.getApplicationContext();
        if (!running) note("watching (process " + android.os.Process.myPid() + ")", null);
        listenForDirectRendering();
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

    /** DirectRendering.Companion.a(DirectRenderingStateListener): told when Native Rendering+ turns on/off. */
    private static void listenForDirectRendering() {
        if (listening) return;
        try {
            Class<?> dr = Class.forName("com.winemu.core.DirectRendering");
            Class<?> type = Class.forName("com.winemu.core.DirectRenderingStateListener");
            Object listener = java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> {
                        if (args != null && args.length == 1 && args[0] instanceof Boolean) {
                            direct = (Boolean) args[0];
                            Log.i(TAG, "Native Rendering+ " + (direct ? "on" : "off"));
                            return null;
                        }
                        switch (method.getName()) {
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == args[0];
                            case "toString": return "BhVkSurfaceScale.listener";
                            default: return null;
                        }
                    });
            Field companion = dr.getDeclaredField("e");
            companion.setAccessible(true);
            Object c = companion.get(null);
            c.getClass().getMethod("a", type).invoke(c, listener);
            listening = true;
        } catch (Throwable t) {
            Log.w(TAG, "cannot listen for Native Rendering+", t);
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
        boolean same = Math.abs(sx - curX) < 1e-4f && Math.abs(sy - curY) < 1e-4f;
        long now = android.os.SystemClock.elapsedRealtime();
        // Re-apply now and then while scaled, in case GameHub resets the surface.
        if (same && (sx == 1f && sy == 1f || now - lastApplyMs < 2000)) return;
        try {
            SurfaceControl sc = surface();
            if (sc == null) {
                note("DirectRendering surface not found", null);
                return;
            }
            if (!sc.isValid()) {
                note("DirectRendering surface not valid yet", null);
                return;
            }
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
            lastApplyMs = now;
            if (!same) note(String.format(java.util.Locale.US, "scaled %.3f x %.3f (%s)", sx, sy, sc), null);
            curX = sx;
            curY = sy;
            result = sx == 1f && sy == 1f ? "not scaled" : String.format(java.util.Locale.US,
                    "scaled %.3f x %.3f", sx, sy);
        } catch (Throwable e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null
                    ? e.getCause() : e;
            note("failed: " + c, c);
        }
    }

    /** Records what happened (FSR tab line, logcat, and logs/upscaler.log once per new message). */
    private static void note(String msg, Throwable t) {
        if (msg.startsWith("scaled") || msg.startsWith("failed")) result = msg.startsWith("scaled")
                ? msg.substring(0, msg.indexOf(" (") > 0 ? msg.indexOf(" (") : msg.length()) : msg;
        else result = msg;
        if (msg.equals(lastError)) return;
        lastError = msg;
        if (t != null) Log.w(TAG, msg, t);
        else Log.i(TAG, msg);
        try {
            java.io.File dir = new java.io.File(android.os.Environment.getExternalStorageDirectory(),
                    "BunnerHub-Etech/logs");
            dir.mkdirs();
            java.io.StringWriter sw = new java.io.StringWriter();
            if (t != null) t.printStackTrace(new java.io.PrintWriter(sw));
            String line = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date()) + " [" + android.os.Process.myPid() + "] " + msg + "\n" + sw;
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(dir, "upscaler.log"), true)) {
                o.write(line.getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
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
