package app.revanced.extension.gamehub;

import android.util.Log;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.util.concurrent.TimeUnit;

/**
 * BhNet — keeps GameHub's own server calls from stalling the launcher.
 *
 * Why: with the login bypass every call to GameHub's servers carries a fake
 * token, so the cloud-gaming and play-time endpoints always answer 401
 * "Please login first". On a good network that costs ~1 s each; on a weak
 * one each call can hang for the stock 20-30 s timeouts while the launcher
 * waits. Measured 2026-10-27: check_user_timer and getUserPlayTimeList
 * (twice) on every start, all 401.
 *
 * Hook: drake Net's NetOkHttpInterceptor.intercept() calls chain(request,
 * chain) before proceeding (build.yml "Apply network speed-up patch").
 * Calls that can only fail are refused at once with a ConnectException
 * (handled like being offline); other calls to GameHub's servers get short
 * timeouts. Everything else (stores, downloads) is untouched.
 */
public final class BhNet {
    private static final String TAG = "BhNet";

    /** GameHub's own servers. */
    private static final String GAMEHUB_HOST = ".vgabc.com";
    /** Endpoints that always answer 401 with the login bypass. */
    private static final String[] DEAD_PATHS = {"/cloud/", "/heartbeat/"};

    private static final int CONNECT_MS = 5000;
    private static final int READ_MS = 10000;

    private static Method urlMethod, withConnect, withRead;

    private BhNet() {}

    public static Object chain(Object request, Object chain) throws IOException {
        String url = url(request);
        if (url == null || !url.contains(GAMEHUB_HOST + "/")) return chain;
        for (String p : DEAD_PATHS) {
            if (url.contains(GAMEHUB_HOST + p)) {
                Log.d(TAG, "skipped (always 401 with login bypass): " + url);
                throw new ConnectException("BunnerHub: skipped " + url);
            }
        }
        try {
            if (withConnect == null) {
                Class<?> c = Class.forName("okhttp3.Interceptor$Chain");
                withConnect = c.getMethod("withConnectTimeout", int.class, TimeUnit.class);
                withRead = c.getMethod("withReadTimeout", int.class, TimeUnit.class);
            }
            Object ch = withConnect.invoke(chain, CONNECT_MS, TimeUnit.MILLISECONDS);
            return withRead.invoke(ch, READ_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            Log.w(TAG, "timeouts not changed", t);
            return chain;
        }
    }

    private static String url(Object request) {
        try {
            if (urlMethod == null) urlMethod = request.getClass().getMethod("url");
            return String.valueOf(urlMethod.invoke(request));
        } catch (Throwable t) {
            return null;
        }
    }
}
