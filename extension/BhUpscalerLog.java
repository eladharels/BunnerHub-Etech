package app.revanced.extension.gamehub;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * BhUpscalerLog — a test log for comparing upscaler settings:
 * /sdcard/BunnerHub-Etech/logs/upscaler-sessions.log.
 *
 * Runs in the :wine process while a game with the BunnerHub layer on ("report
 * only" or "FSR to display") is in the foreground (BhPlayTime resume/pause).
 * Samples every 2 s and writes one line every 10 s: the settings (game size,
 * FSR on/off and output, Upscale to, sharpness, Native Rendering+), the
 * average and lowest frame rate (from the layer's status file), and GPU load,
 * power, CPU / GPU / battery temperature and charging (the same sources as the
 * detailed HUD). "Report only" gives the baseline without FSR.
 */
final class BhUpscalerLog {

    private static final String TAG = "BhUpscalerLog";
    private static final long MAX_BYTES = 1024 * 1024;
    private static final int SAMPLE_S = 2, SAMPLES_PER_LINE = 5;

    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "BhUpscalerLog");
        t.setDaemon(true);
        return t;
    });
    private static ScheduledFuture<?> task;
    private static Context app;
    private static String gameId, gameName;

    // Current interval.
    private static int samples, gpuSum, gpuCount;
    private static float powerSum;
    private static int powerCount;
    private static double minFps;
    private static long framesAtStart = -1, msAtStart;
    private static String lastSettings;

    private BhUpscalerLog() {}

    /** BhPlayTime.onGameResume. */
    static synchronized void start(Context ctx, String id, String name) {
        if (BhVkLayer.mode(ctx, id) == BhVkLayer.OFF) return;
        app = ctx.getApplicationContext();
        gameId = id;
        gameName = name;
        resetInterval();
        lastSettings = null;
        if (task != null) task.cancel(false);
        task = IO.scheduleWithFixedDelay(BhUpscalerLog::tick, SAMPLE_S, SAMPLE_S, TimeUnit.SECONDS);
    }

    /** BhPlayTime.onGamePause. */
    static synchronized void stop() {
        if (task != null) task.cancel(false);
        task = null;
        Context ctx = app;
        IO.execute(() -> BhPerfHistory.flush(ctx)); // end the history segment
    }

    private static void resetInterval() {
        samples = gpuSum = gpuCount = powerCount = 0;
        powerSum = 0f;
        minFps = Double.MAX_VALUE;
        framesAtStart = -1;
    }

    private static void tick() {
        Context ctx;
        String id;
        synchronized (BhUpscalerLog.class) {
            if (task == null) return;
            ctx = app;
            id = gameId;
        }
        try {
            if (!id.equals(BhVkLayer.appliedGame)) return; // the layer isn't on for this run
            Map<String, String> st = BhVkLayer.status(ctx);
            long frames = parseLong(st.get("frames"), -1);
            long now = System.currentTimeMillis();
            if (framesAtStart < 0 && frames >= 0) {
                framesAtStart = frames;
                msAtStart = now;
            }
            double fps = parseDouble(st.get("fps"), -1);
            long age = now / 1000 - parseLong(st.get("time"), 0);
            if (fps > 0 && age < 5) minFps = Math.min(minFps, fps); // 0 = not drawn yet
            int gpu = BhSensors.gpuLoad();
            if (gpu >= 0) {
                gpuSum += gpu;
                gpuCount++;
            }
            float w = BhSensors.power(ctx);
            if (w > 0) {
                powerSum += w;
                powerCount++;
            }
            if (++samples < SAMPLES_PER_LINE) return;
            // Nothing worth a line yet: a helper window (e.g. GTA IV's 1x1 at start) or no frames counted.
            int[] size = size(st.get("swapchain"));
            if (size == null || size[0] < 320 || size[1] < 200 || frames <= 0) {
                resetInterval();
                return;
            }

            String settings = settings(ctx, id, st);
            StringBuilder b = new StringBuilder();
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            if (!settings.equals(lastSettings)) {
                b.append('\n').append(time).append("  ── ").append(gameName != null ? gameName : id)
                        .append(" · ").append(settings).append('\n');
                lastSettings = settings;
            }
            b.append(time).append("  ");
            int gpuAvg = gpuCount > 0 ? gpuSum / gpuCount : -1;
            float powerAvg = powerCount > 0 ? powerSum / powerCount : 0f;
            int cpuT = BhSensors.cpuTemp();
            int gpuT = BhSensors.gpuTemp();
            boolean charging = BhSensors.charging(ctx);
            if (frames >= framesAtStart && framesAtStart >= 0 && now > msAtStart && age < 5) {
                double avg = (frames - framesAtStart) * 1000.0 / (now - msAtStart);
                // min is sampled every 2 s, so a short stall can pull the average under it.
                b.append(String.format(Locale.US, "fps avg %5.1f  min %5.1f", avg,
                        minFps == Double.MAX_VALUE ? avg : Math.min(minFps, avg)));
                if (avg >= 1) BhPerfHistory.sample(ctx, id, shortSettings(st), avg, gpuAvg, cpuT, gpuT, powerAvg, charging);
            } else {
                String stuck = st.get("stuck");
                b.append("fps   (game not drawing")
                        .append(stuck != null && !stuck.isEmpty() ? ": " + stuck : "").append(")     ");
            }
            b.append("  gpu ").append(gpuAvg >= 0 ? gpuAvg + "%" : "?");
            b.append(String.format(Locale.US, "  power %s", powerCount > 0
                    ? String.format(Locale.US, "%.1fW", powerAvg) : "?"));
            b.append("  temp cpu ").append(temp(cpuT));
            b.append(" gpu ").append(temp(gpuT));
            b.append(" bat ").append(temp(BhSensors.batteryTemp(ctx)));
            if (charging) b.append("  charging");
            b.append('\n');
            write(b.toString());
            resetInterval();
        } catch (Throwable t) {
            Log.w(TAG, "tick failed", t);
            resetInterval();
        }
    }

    /** The history's settings label: "960x540 → 2340x1080 FSR · sharpness 4/5" or "1280x720 · no FSR". */
    private static String shortSettings(Map<String, String> st) {
        String game = st.get("swapchain");
        StringBuilder s = new StringBuilder(game != null ? game : "?");
        if ("on".equals(st.get("upscale"))) {
            s.append(" → ").append(st.get("output")).append(" FSR · sharpness ").append(BhVkLayer.appliedSharp + 1)
                    .append('/').append(BhVkLayer.SHARP_LEVELS);
            if (Boolean.FALSE.equals(BhVkSurfaceScale.direct)) s.append(" (Native Rendering+ off)");
        } else {
            s.append(" · no FSR");
        }
        String cap = st.get("fps_cap");
        if (cap != null && !cap.equals("0")) s.append(" · cap ").append(cap);
        return s.toString();
    }

    private static String settings(Context ctx, String id, Map<String, String> st) {
        StringBuilder s = new StringBuilder();
        String game = st.get("swapchain");
        s.append("game ").append(game != null ? game : "?");
        if ("on".equals(st.get("upscale"))) {
            s.append(" → ").append(st.get("output")).append(" FSR 1");
            s.append(" (upscale to ").append(BhVkLayer.targetLabel(ctx, BhVkLayer.appliedTarget));
            s.append(", sharpness ").append(BhVkLayer.appliedSharp + 1).append('/').append(BhVkLayer.SHARP_LEVELS)
                    .append(')');
            Boolean direct = BhVkSurfaceScale.direct;
            s.append(" · Native Rendering+ ").append(direct == null ? "?" : direct ? "on" : "OFF (cropped)");
        } else if (BhVkLayer.appliedMode == BhVkLayer.UPSCALE) {
            String note = st.get("note");
            s.append(" · FSR not running").append(note != null && !note.isEmpty() ? ": " + note : "");
        } else {
            s.append(" · no FSR (report only)");
        }
        String cap = st.get("fps_cap");
        if (cap != null && !cap.equals("0")) s.append(" · heat control cap ").append(cap).append(" FPS");
        return s.toString();
    }

    private static void write(String text) {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "BunnerHub-Etech/logs");
            dir.mkdirs();
            File f = new File(dir, "upscaler-sessions.log");
            if (f.length() > MAX_BYTES) {
                File old = new File(dir, "upscaler-sessions.old.log");
                old.delete();
                f.renameTo(old);
            }
            try (FileOutputStream o = new FileOutputStream(f, true)) {
                o.write(text.getBytes("UTF-8"));
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot write the log", t);
        }
    }

    private static String temp(int c) {
        return c > 0 ? c + "°C" : "?";
    }

    private static int[] size(String s) {
        if (s == null) return null;
        int x = s.indexOf('x');
        try {
            return x > 0 ? new int[]{Integer.parseInt(s.substring(0, x)), Integer.parseInt(s.substring(x + 1))} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long parseLong(String s, long dflt) {
        try {
            return s == null ? dflt : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static double parseDouble(String s, double dflt) {
        try {
            return s == null ? dflt : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
