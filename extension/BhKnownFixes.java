package app.revanced.extension.gamehub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * BhKnownFixes — fixes found by debugging, offered for the games they belong to.
 *
 * Shown on the game details page (called from BhPlayTime.onDetail, i.e. the
 * GameDetailActivity.T3 hook): a "Known fix" chip under the play time label
 * opens a dialog explaining the fix, with Apply for fixes that are settings
 * (environment variables) and plain instructions for the rest.
 *
 * Env vars go into the same place GameHub's own Environment Variables dialog
 * uses: the string "pc_ls_environment_variable" in SharedPreferences
 * "pc_g_setting<gameId>", entries joined by commas ("A=1,B=2"). Existing
 * entries are kept. The startup exe is stored in GameHub's game database, not
 * in those prefs, so exe fixes are instructions only.
 *
 * Each fix records where it was found (PROGRESS_LOG entry).
 */
public final class BhKnownFixes {

    private static final String TAG = "BhKnownFixes";
    private static final String CHIP_TAG = "bh_known_fix_chip";
    private static final String ENV_KEY = "pc_ls_environment_variable";

    /** One fix. Matches on Steam app id, game name or exe path. */
    static final class Fix {
        String title;
        String explanation;
        String source;
        String steamAppId;     // exact match, or null
        String namePattern;    // regex, case-insensitive, full match, or null
        String exeContains;    // case-insensitive substring of the exe path, or null
        String exeFixedIf;     // the fix is done when the exe path contains this
        String envName;        // fix = set this env var...
        String envValue;       // ...to this value
    }

    private static final List<Fix> FIXES = new ArrayList<>();

    static {
        Fix proto = new Fix();
        proto.title = "Black screen at start";
        proto.explanation = "Prototype hangs on a black screen before its first frame: two threads wait "
                + "forever on a lock that sits at an odd memory address. The x86 instruction that updates it "
                + "is not atomic in FEX's default mode on ARM, so an update gets lost.\n\n"
                + "Fix: environment variable FEX_STRICTINPROCESSSPLITLOCKS=1 (FEX handles such locks safely). "
                + "Tested: the game reaches the title screen at 60 FPS.";
        proto.source = "PROGRESS_LOG: Prototype black screen = FEX split-lock race (2026-10-25)";
        proto.steamAppId = "10150";
        proto.namePattern = "prototype\\s*(™|\\(tm\\))?";
        proto.envName = "FEX_STRICTINPROCESSSPLITLOCKS";
        proto.envValue = "1";
        FIXES.add(proto);

        Fix dogs = new Fix();
        dogs.title = "Crashes at startup (LE version)";
        dogs.explanation = "The Sleeping Dogs LE executable (LE\\HKShip.exe) in this pack is a modified exe; "
                + "its copy-protection check fails under Wine and it kills itself during startup.\n\n"
                + "Fix: use the Definitive Edition instead. In this game's settings, set Startup File Path to "
                + "DE\\sdhdship.exe (same folder as LE). Tested: the DE version runs.";
        dogs.source = "Debug session 2026-10-25: crash in HKShip.exe's static initializer at 0x5C5940";
        dogs.namePattern = "sleeping\\s*dogs.*";
        dogs.exeContains = "hkship.exe";
        dogs.exeFixedIf = "sdhdship.exe";
        FIXES.add(dogs);
    }

    private BhKnownFixes() {}

    // ── Details page ─────────────────────────────────────────────────────────

    /** From BhPlayTime.onDetail (any thread). */
    static void onDetail(Activity activity, Object entity) {
        try {
            String prefsId = prefsId(entity);
            String steamId = str(call(entity, "getSteamAppId"));
            String name = str(call(entity, "getName"));
            String exe = str(call(entity, "getFilePath"));
            Fix fix = match(steamId, name, exe);
            if (fix == null || prefsId == null) {
                activity.runOnUiThread(() -> removeChip(activity));
                return;
            }
            activity.runOnUiThread(() -> showChip(activity, fix, prefsId, exe));
        } catch (Throwable t) {
            Log.w(TAG, "onDetail failed", t);
        }
    }

    static Fix match(String steamId, String name, String exe) {
        String lowExe = exe == null ? "" : exe.toLowerCase(Locale.ROOT);
        String lowName = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        for (Fix f : FIXES) {
            boolean hit = (f.steamAppId != null && f.steamAppId.equals(steamId))
                    || (f.namePattern != null && lowName.matches(f.namePattern))
                    || (f.exeContains != null && lowExe.contains(f.exeContains));
            if (!hit) continue;
            // An exe fix only applies while the game still points at the bad exe.
            if (f.exeFixedIf != null && lowExe.contains(f.exeFixedIf)) continue;
            return f;
        }
        return null;
    }

    private static boolean applied(Context ctx, Fix f, String prefsId) {
        if (f.envName == null) return false;
        String env = prefs(ctx, prefsId).getString(ENV_KEY, "");
        for (String kv : env.split(",")) {
            if (kv.trim().equals(f.envName + "=" + f.envValue)) return true;
        }
        return false;
    }

    private static void showChip(Activity activity, Fix fix, String prefsId, String exe) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return;
        View existing = content.findViewWithTag(CHIP_TAG);
        TextView chip;
        if (existing instanceof TextView) {
            chip = (TextView) existing;
        } else {
            chip = new TextView(activity);
            chip.setTag(CHIP_TAG);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            chip.setTypeface(null, Typeface.BOLD);
            int padH = BhPlayTime.dp(activity, 12), padV = BhPlayTime.dp(activity, 6);
            chip.setPadding(padH, padV, padH, padV);
            chip.setFocusable(true);
            chip.setClickable(true);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            lp.topMargin = BhPlayTime.dp(activity, 96);
            lp.rightMargin = BhPlayTime.dp(activity, 24);
            content.addView(chip, lp);
        }
        boolean done = applied(activity, fix, prefsId);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(done ? 0xB32E7D32 : 0xE6FFB300);
        bg.setCornerRadius(BhPlayTime.dp(activity, 16));
        chip.setBackground(bg);
        chip.setTextColor(done ? 0xFFFFFFFF : 0xFF000000);
        chip.setText(done ? "✔  Known fix applied" : "🔧  Known fix: " + fix.title);
        chip.setOnClickListener(v -> showDialog(activity, fix, prefsId, exe));
    }

    private static void removeChip(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        View chip = content == null ? null : content.findViewWithTag(CHIP_TAG);
        if (chip != null) ((ViewGroup) chip.getParent()).removeView(chip);
    }

    private static void showDialog(Activity activity, Fix fix, String prefsId, String exe) {
        boolean done = applied(activity, fix, prefsId);
        String msg = fix.explanation + "\n\nSource: " + fix.source;
        if (fix.envName != null && done) msg += "\n\nStatus: applied (" + fix.envName + "=" + fix.envValue + ").";
        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle(fix.title)
                .setMessage(msg)
                .setNegativeButton("Close", null);
        if (fix.envName != null && !done) {
            b.setPositiveButton("Apply", (d, w) -> {
                if (applyEnv(activity, prefsId, fix.envName, fix.envValue)) {
                    Toast.makeText(activity, "Applied. It takes effect the next time the game starts.",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(activity, "Could not apply the fix.", Toast.LENGTH_LONG).show();
                }
                showChip(activity, fix, prefsId, exe);
            });
        } else if (fix.envName != null) {
            b.setNeutralButton("Remove", (d, w) -> {
                removeEnv(activity, prefsId, fix.envName);
                showChip(activity, fix, prefsId, exe);
            });
        }
        b.show();
    }

    // ── Env vars in GameHub's per-game settings ──────────────────────────────

    /** Adds or replaces NAME=VALUE, keeping the game's other variables. */
    static boolean applyEnv(Context ctx, String prefsId, String name, String value) {
        try {
            SharedPreferences sp = prefs(ctx, prefsId);
            List<String> out = new ArrayList<>();
            for (String kv : sp.getString(ENV_KEY, "").split(",")) {
                String t = kv.trim();
                // Drop this variable's old value and empty-name junk ("=1").
                if (t.isEmpty() || t.startsWith("=") || t.startsWith(name + "=")) continue;
                out.add(t);
            }
            out.add(name + "=" + value);
            return sp.edit().putString(ENV_KEY, String.join(",", out)).commit();
        } catch (Throwable t) {
            Log.w(TAG, "applyEnv failed", t);
            return false;
        }
    }

    static void removeEnv(Context ctx, String prefsId, String name) {
        SharedPreferences sp = prefs(ctx, prefsId);
        List<String> out = new ArrayList<>();
        for (String kv : sp.getString(ENV_KEY, "").split(",")) {
            String t = kv.trim();
            if (!t.isEmpty() && !t.startsWith(name + "=")) out.add(t);
        }
        sp.edit().putString(ENV_KEY, String.join(",", out)).commit();
    }

    private static SharedPreferences prefs(Context ctx, String prefsId) {
        return ctx.getSharedPreferences("pc_g_setting" + prefsId, Context.MODE_PRIVATE);
    }

    /** Same id as GameHub's per-game prefs and BhPlayTime: server id, else local id. */
    private static String prefsId(Object entity) {
        Object id = call(entity, "getId");
        if (id instanceof Integer && (Integer) id > 0) return String.valueOf(id);
        Object local = call(entity, "getLocalGameId");
        return local instanceof String && !((String) local).isEmpty() ? (String) local : null;
    }

    private static Object call(Object obj, String method) {
        try {
            Method m = obj.getClass().getMethod(method);
            return m.invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
