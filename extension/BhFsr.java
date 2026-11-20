package app.revanced.extension.gamehub;

import android.content.Context;
import android.util.Log;

/**
 * BhFsr — launch hook for the FSR tab's upscaler. Called from
 * {@link BhWineLog#env} (ProgramLauncher.e() smali hook) for every Wine
 * launcher; when the current game has the BunnerHub upscaler on, adds the
 * Vulkan layer's variables ({@link BhVkLayer#apply}).
 *
 * (The earlier Wine fullscreen-hack FSR, WINE_FULLSCREEN_FSR, was removed on
 * 2026-11-20: it needs a Proton/GE Wine build, and the layer works with any.)
 */
public final class BhFsr {

    private static final String TAG = "BhFsr";

    private BhFsr() {}

    /** BhWineLog.env: adjusts the environment of every Wine launcher. */
    public static String[] env(String[] env) {
        try {
            Context ctx = currentApp();
            String id = BhPlayTime.currentGameId();
            if (ctx != null && env != null && BhVkLayer.enabled(ctx, id)) env = BhVkLayer.apply(ctx, env);
        } catch (Throwable t) {
            Log.w(TAG, "layer hook failed", t);
        }
        return env;
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
