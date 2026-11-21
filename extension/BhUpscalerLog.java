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
            int gpu = readGpu();
            if (gpu >= 0) {
                gpuSum += gpu;
                gpuCount++;
            }
            float w = readPower(ctx);
            if (w > 0) {
                powerSum += w;
                powerCount++;
            }
            if (++samples < SAMPLES_PER_LINE) return;

            String settings = settings(ctx, id, st);
            StringBuilder b = new StringBuilder();
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            if (!settings.equals(lastSettings)) {
                b.append('\n').append(time).append("  ── ").append(gameName != null ? gameName : id)
                        .append(" · ").append(settings).append('\n');
                lastSettings = settings;
            }
            b.append(time).append("  ");
            if (frames >= framesAtStart && framesAtStart >= 0 && now > msAtStart && age < 5) {
                double avg = (frames - framesAtStart) * 1000.0 / (now - msAtStart);
                // min is sampled every 2 s, so a short stall can pull the average under it.
                b.append(String.format(Locale.US, "fps avg %5.1f  min %5.1f", avg,
                        minFps == Double.MAX_VALUE ? avg : Math.min(minFps, avg)));
            } else {
                b.append("fps   (game not drawing)     ");
            }
            b.append("  gpu ").append(gpuCount > 0 ? (gpuSum / gpuCount) + "%" : "?");
            b.append(String.format(Locale.US, "  power %s", powerCount > 0
                    ? String.format(Locale.US, "%.1fW", powerSum / powerCount) : "?"));
            b.append("  temp cpu ").append(temp(readZoneTemp(new String[]{"cpu-cluster0", "cpu0-thermal",
                    "cpu-thermal", "cpucluster", "cpu"}, null)));
            b.append(" gpu ").append(temp(readZoneTemp(new String[]{"gpuss-0", "gpuss", "gpu-thermal", "gpu"},
                    "/sys/class/kgsl/kgsl-3d0/temp")));
            b.append(" bat ").append(temp(readBatteryTemp(ctx)));
            if (isCharging(ctx)) b.append("  charging");
            b.append('\n');
            write(b.toString());
            resetInterval();
        } catch (Throwable t) {
            Log.w(TAG, "tick failed", t);
            resetInterval();
        }
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

    // ── Readings (same sources as BhDetailedHud) ─────────────────────────────

    private static String temp(int c) {
        return c > 0 ? c + "°C" : "?";
    }

    private static int readGpu() {
        String v = line("/sys/class/kgsl/kgsl-3d0/gpubusy");
        if (v != null) {
            try {
                String[] p = v.trim().split("\\s+");
                if (p.length >= 2) {
                    long busy = Long.parseLong(p[0]), total = Long.parseLong(p[1]);
                    if (total > 0) return (int) (100L * busy / total);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        for (String path : new String[]{"/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                "/sys/class/misc/mali0/device/utilisation"}) {
            v = line(path);
            if (v != null) {
                try {
                    return Integer.parseInt(v.trim().replaceAll("[^0-9]", ""));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    private static float readPower(Context ctx) {
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            if (bm == null) return 0f;
            long current = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (current == Long.MIN_VALUE) return 0f;
            float volts = 3.7f;
            String v = line("/sys/class/power_supply/battery/voltage_now");
            if (v != null) {
                try {
                    volts = Float.parseFloat(v.trim()) / 1_000_000f;
                } catch (NumberFormatException ignored) {
                }
            }
            float amps = Math.abs(current) / 1_000_000f;
            if (amps < 0.01f) amps = Math.abs(current) / 1_000f;
            return volts * amps;
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static boolean isCharging(Context ctx) {
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int s = i == null ? -1 : i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Battery temperature from Android's battery broadcast (tenths of °C), else sysfs. */
    private static int readBatteryTemp(Context ctx) {
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int t = i == null ? -1 : i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            if (t > 0) return t / 10;
        } catch (Throwable ignored) {
        }
        String v = line("/sys/class/power_supply/battery/temp");
        try {
            return v == null ? 0 : Integer.parseInt(v.trim()) / 10;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int readZoneTemp(String[] types, String direct) {
        if (direct != null) {
            int t = milli(line(direct));
            if (t > 0) return t;
        }
        for (int z = 0; z < 40; z++) {
            String type = line("/sys/class/thermal/thermal_zone" + z + "/type");
            if (type == null) continue;
            String tl = type.trim().toLowerCase(Locale.US);
            for (String t : types) {
                if (tl.contains(t)) {
                    int c = milli(line("/sys/class/thermal/thermal_zone" + z + "/temp"));
                    if (c > 0) return c;
                }
            }
        }
        return 0;
    }

    private static int milli(String v) {
        if (v == null) return 0;
        try {
            int t = Integer.parseInt(v.trim());
            return t > 1000 ? t / 1000 : t;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String line(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.readLine();
        } catch (Throwable t) {
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
