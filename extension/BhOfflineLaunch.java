package app.revanced.extension.gamehub;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.util.concurrent.CancellationException;

/**
 * BhOfflineLaunch — keeps the online steps of a PC game launch from blocking
 * offline play.
 *
 * When a game starts, GameHub runs setup tasks (com.xj.winemu.setup.tasks):
 * GameConfigDownloadTask fetches the game's recommended config,
 * SetGameRecommConfigTask applies it and DependencyInstallTask refreshes
 * dependency components. Without internet each one fails, and
 * BaseSetupTask$execute$2 turns any exception into SetupTaskResult.Failure,
 * which stops the launch.
 *
 * Two layers (both wired in build.yml, "Apply Offline Launch smali patch"):
 *  1. {@link #launchOnline()} replaces NetworkUtils.isConnected() in the
 *     existing offline patches (GameConfigDownloadTask, SetupTaskFactory). It
 *     also treats Wi-Fi without internet / captive portals as offline (Android's
 *     NET_CAPABILITY_VALIDATED), so those steps are skipped at once instead of
 *     waiting for timeouts.
 *  2. {@link #softFail} is called from BaseSetupTask$execute$2's generic
 *     failure path. For the steps above, a failure becomes a success with a
 *     note, so the launch continues with the settings saved last time.
 *     Install steps (image, container, components) are never skipped.
 *
 * Either way the player gets one short notice per launch.
 */
public final class BhOfflineLaunch {

    private static final String TAG = "BhOfflineLaunch";
    private static final long NOTICE_GAP_MS = 60_000;
    private static long lastNotice;

    private BhOfflineLaunch() {}

    /** True when the device has validated internet. Shows the notice when not. */
    public static boolean launchOnline() {
        boolean on = hasInternet();
        if (!on) {
            Log.i(TAG, "no internet: skipping online launch checks");
            notice();
        }
        return on;
    }

    /**
     * BaseSetupTask$execute$2, before a task failure is reported. Returns a
     * success message when this task may be skipped, or null to fail as usual.
     */
    public static String softFail(Object task, Throwable error) {
        try {
            if (task == null || error == null || error instanceof CancellationException) return null;
            String name = task.getClass().getSimpleName();
            boolean skip;
            switch (name) {
                case "GameConfigDownloadTask":
                case "SetGameRecommConfigTask":
                    // Recommended settings only: the game runs with its saved
                    // (or default) settings without them.
                    skip = true;
                    break;
                case "DependencyInstallTask":
                    // Only a network failure: a real install error should still show.
                    skip = isNetworkError(error) || !hasInternet();
                    break;
                default:
                    skip = false;
            }
            if (!skip) return null;
            Log.w(TAG, "continuing without " + name + ": " + error);
            notice();
            return "Skipped (offline): " + name;
        } catch (Throwable t) {
            Log.w(TAG, "softFail failed", t);
            return null;
        }
    }

    static boolean isNetworkError(Throwable t) {
        for (int i = 0; t != null && i < 10; i++, t = t.getCause()) {
            if (t instanceof IOException) return true;
        }
        return false;
    }

    static boolean hasInternet() {
        try {
            Context ctx = currentApp();
            if (ctx == null) return true;
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null) return false;
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable t) {
            return true; // unknown: behave like stock
        }
    }

    /** One notice per launch. Never throws: a notice must not decide the launch. */
    private static synchronized void notice() {
        try {
            long now = SystemClock.elapsedRealtime();
            if (lastNotice != 0 && now - lastNotice < NOTICE_GAP_MS) return;
            lastNotice = now;
            final Context ctx = currentApp();
            if (ctx == null) return;
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Toast.makeText(ctx, "No internet: skipped the online config check. "
                            + "Starting with your saved settings.", Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "notice failed", t);
        }
    }

    private static Context currentApp() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            return (Context) at.getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
