package app.revanced.extension.gamehub;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * BhThermal — heat control and the overheat warning, while a game is in the foreground (:wine
 * process, started / stopped with BhPlayTime's resume / pause).
 *
 * Every 2 s it reads the CPU and GPU temperature (BhSensors) and takes the hotter one.
 *
 * Heat control (needs the BunnerHub layer loaded: "report only" or FSR): when the phone stays at
 * or above the mode's limit (Balanced 80 °C, Cool 72 °C) for 6 s, the frame cap steps down
 * (none → 45 → 40 → 30 FPS, at most every 30 s); when it stays 8 °C under the limit for 30 s it
 * steps back up. The cap goes to the layer through its control file (fps_cap=), which paces
 * frames at present, so the phone works less and stays out of the heavy throttling seen in the
 * test logs (60 FPS for a minute or two, then 25–35).
 *
 * Overheat warning (works without the layer): a toast at 85 °C or more, at most every 3 minutes.
 *
 * Settings are global, kept in this process's SharedPreferences (the FSR tab runs here too).
 */
final class BhThermal {

    private static final String TAG = "BhThermal";
    static final int OFF = 0, BALANCED = 1, COOL = 2;
    static final String[] MODE_LABELS = {"Off", "Balanced (keeps under 80°C)", "Cool (keeps under 72°C)"};
    private static final int[] LIMIT_C = {0, 80, 72};
    static final int[] CAPS = {0, 45, 40, 30};
    private static final int WARN_C = 85;
    private static final long TICK_S = 2, HOT_MS = 6_000, COOL_MS = 30_000, STEP_DOWN_GAP_MS = 30_000,
            STEP_UP_GAP_MS = 30_000, WARN_GAP_MS = 180_000;

    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "BhThermal");
        t.setDaemon(true);
        return t;
    });
    private static ScheduledFuture<?> task;
    private static Context app;
    private static String gameId;

    private static volatile int level;      // index into CAPS
    private static volatile int lastTemp;   // hotter of CPU / GPU, °C
    private static long hotSince, coolSince, lastStep, lastWarn = -WARN_GAP_MS;

    private BhThermal() {}

    // ── Settings ─────────────────────────────────────────────────────────────

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences("bh_thermal", Context.MODE_PRIVATE);
    }

    static int mode(Context ctx) {
        return Math.max(OFF, Math.min(COOL, prefs(ctx).getInt("mode", OFF)));
    }

    static void setMode(Context ctx, int m) {
        prefs(ctx).edit().putInt("mode", m).apply();
        if (m == OFF) setLevel(0); // the cap goes at once
        IO.execute(BhThermal::tick);
    }

    static boolean warnEnabled(Context ctx) {
        return prefs(ctx).getBoolean("warn", true);
    }

    static void setWarnEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("warn", on).apply();
    }

    /** The frame cap heat control asks for now (0 = none). BhVkLayer.writeControl reads it. */
    static int cap() {
        return CAPS[level];
    }

    // ── Lifecycle (BhPlayTime) ───────────────────────────────────────────────

    static synchronized void start(Context ctx, String id) {
        app = ctx.getApplicationContext();
        lastStep = SystemClock.elapsedRealtime() - STEP_UP_GAP_MS;
        if (!id.equals(gameId)) { // a new game starts uncapped
            level = 0;
            hotSince = coolSince = 0;
        }
        gameId = id;
        if (task != null) task.cancel(false);
        task = IO.scheduleWithFixedDelay(BhThermal::tick, TICK_S, TICK_S, TimeUnit.SECONDS);
    }

    static synchronized void stop() {
        if (task != null) task.cancel(false);
        task = null;
    }

    // ── The 2 s check ────────────────────────────────────────────────────────

    private static void tick() {
        Context ctx;
        synchronized (BhThermal.class) {
            if (task == null || app == null) return;
            ctx = app;
        }
        try {
            int t = Math.max(BhSensors.cpuTemp(), BhSensors.gpuTemp());
            if (t <= 0) return; // no sensor readable
            onTemp(ctx, t, SystemClock.elapsedRealtime());
        } catch (Throwable e) {
            Log.w(TAG, "tick failed", e);
        }
    }

    /** One reading: warning, then heat control's step up / down. (Separate for testing.) */
    static synchronized void onTemp(Context ctx, int t, long now) {
        {
            lastTemp = t;

            if (t >= WARN_C && warnEnabled(ctx) && now - lastWarn >= WARN_GAP_MS) {
                lastWarn = now;
                String msg = "🔥 Phone is hot (" + t + "°C): FPS will start dropping."
                        + (mode(ctx) == OFF ? " Heat control in the FSR tab can help." : "");
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
            }

            int mode = mode(ctx);
            if (mode == OFF) {
                hotSince = coolSince = 0;
                if (level != 0) setLevel(0);
                return;
            }
            int limit = LIMIT_C[mode];
            if (t >= limit) {
                coolSince = 0;
                if (hotSince == 0) hotSince = now;
                if (now - hotSince >= HOT_MS && now - lastStep >= STEP_DOWN_GAP_MS && level < CAPS.length - 1) {
                    lastStep = now;
                    setLevel(level + 1);
                    Log.i(TAG, t + "°C: frame cap " + cap());
                }
            } else if (t <= limit - 8) {
                hotSince = 0;
                if (coolSince == 0) coolSince = now;
                if (now - coolSince >= COOL_MS && now - lastStep >= STEP_UP_GAP_MS && level > 0) {
                    lastStep = now;
                    coolSince = now; // one step per 30 s cool spell
                    setLevel(level - 1);
                    Log.i(TAG, t + "°C: frame cap " + (cap() == 0 ? "off" : cap()));
                }
            } else {
                hotSince = coolSince = 0;
            }
        }
    }

    private static void setLevel(int l) {
        level = l;
        Context ctx = app;
        String id = gameId;
        if (ctx != null && id != null && BhVkLayer.live(id)) BhVkLayer.writeControl(ctx, id);
    }

    /** The FSR tab's line. */
    static String statusLine(Context ctx, String id) {
        int mode = mode(ctx);
        String temp = lastTemp > 0 ? lastTemp + "°C" : "temperature not read yet";
        if (mode == OFF) return "Now " + temp + ".";
        if (!BhVkLayer.live(id)) {
            return "Needs the upscaler loaded (\"report only\" is enough): set it, then restart the game. Now " + temp + ".";
        }
        int c = cap();
        return String.format(Locale.US, "Now %s · %s", temp, c == 0 ? "no frame cap" : "frame cap " + c + " FPS");
    }
}
