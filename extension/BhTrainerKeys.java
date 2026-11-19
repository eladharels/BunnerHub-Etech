package app.revanced.extension.gamehub;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BhTrainerKeys — a trainer's cheats as switches: the cheat list (from the
 * trainer's download page) and pressing a cheat's keys inside the game.
 *
 * Trainers such as FLiNG's listen for hotkeys (Num 1, Ctrl+Num 1, F1…) even
 * while their own window is hidden, which it is here: GameHub draws the game
 * over every other Windows window. Keys are sent the way GameHub's own
 * keyboard does it: WinUIBridge.e0(KeyEvent) → XServer.sendKeyEvent, so they
 * reach Wine like a real keyboard's.
 *
 * Cheat lists: FLiNG trainer pages list options one per line, e.g.
 * "Num 1 – Infinite Health", "Ctrl+Num 1 – Edit Money", "F1 - God Mode".
 */
public final class BhTrainerKeys {

    private static final String TAG = "BhTrainerKeys";

    /** One cheat: its keys as shown ("Ctrl+Num 1"), the key codes to press, its name. */
    public static final class Option {
        public final String keys;
        public final int[] codes;
        public final String label;

        Option(String keys, int[] codes, String label) {
            this.keys = keys;
            this.codes = codes;
            this.label = label;
        }
    }

    private BhTrainerKeys() {}

    // ── Parsing a cheat list ────────────────────────────────────────────────

    private static final String KEY =
            "(?:Num(?:pad)?\\s*(?:[0-9]|\\.|\\+|-|\\*|/)|F(?:1[0-2]|[1-9])|Home|End|Insert|Ins|Delete|Del"
                    + "|Page\\s*Up|PgUp|Page\\s*Down|PgDn|Space|Enter|Tab|Backspace|[A-Z0-9])";
    private static final String MOD = "(?:Ctrl|Control|Shift|Alt)";
    private static final Pattern LINE = Pattern.compile(
            "^\\s*((?:" + MOD + "\\s*\\+\\s*)*" + KEY + ")\\s*[–—:\\-]+\\s*(\\S.{0,80}?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** Cheats found in a trainer page's text, in order, without duplicates. */
    public static List<Option> parse(String text) {
        List<Option> out = new ArrayList<>();
        if (text == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String raw : text.split("\\r?\\n")) {
            Matcher m = LINE.matcher(raw.replace(' ', ' '));
            if (!m.matches()) continue;
            String keys = m.group(1).replaceAll("\\s*\\+\\s*", "+").replaceAll("\\s+", " ").trim();
            if (keys.toLowerCase(Locale.US).endsWith("num+")) keys = keys.substring(0, keys.length() - 1) + " +";
            int[] codes = codes(keys);
            if (codes == null) continue;
            String label = m.group(2).trim();
            if (label.isEmpty() || !seen.add(keys.toLowerCase(Locale.US))) continue;
            out.add(new Option(keys, codes, label));
        }
        // A real cheat list has several entries; one stray match is noise.
        return out.size() >= 2 ? out : new ArrayList<>();
    }

    /** Android key codes for "Ctrl+Num 1" style keys (modifiers first), or null. */
    public static int[] codes(String keys) {
        String[] parts = keys.split("\\+(?=.)");
        List<Integer> out = new ArrayList<>();
        // "Num +" ends in a plus: split above keeps it as its own part.
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim().toLowerCase(Locale.US);
            if (p.isEmpty()) continue;
            int c = code(p);
            if (c == KeyEvent.KEYCODE_UNKNOWN) return null;
            out.add(c);
        }
        if (out.isEmpty()) return null;
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    private static int code(String p) {
        switch (p) {
            case "ctrl": case "control": return KeyEvent.KEYCODE_CTRL_LEFT;
            case "shift": return KeyEvent.KEYCODE_SHIFT_LEFT;
            case "alt": return KeyEvent.KEYCODE_ALT_LEFT;
            case "home": return KeyEvent.KEYCODE_MOVE_HOME;
            case "end": return KeyEvent.KEYCODE_MOVE_END;
            case "insert": case "ins": return KeyEvent.KEYCODE_INSERT;
            case "delete": case "del": return KeyEvent.KEYCODE_FORWARD_DEL;
            case "page up": case "pageup": case "pgup": return KeyEvent.KEYCODE_PAGE_UP;
            case "page down": case "pagedown": case "pgdn": return KeyEvent.KEYCODE_PAGE_DOWN;
            case "space": return KeyEvent.KEYCODE_SPACE;
            case "enter": return KeyEvent.KEYCODE_ENTER;
            case "tab": return KeyEvent.KEYCODE_TAB;
            case "backspace": return KeyEvent.KEYCODE_DEL;
            case "numlock": case "num lock": return KeyEvent.KEYCODE_NUM_LOCK;
            default: break;
        }
        Matcher num = Pattern.compile("^num(?:pad)?\\s*(.)$").matcher(p);
        if (num.matches()) {
            char ch = num.group(1).charAt(0);
            if (ch >= '0' && ch <= '9') return KeyEvent.KEYCODE_NUMPAD_0 + (ch - '0');
            switch (ch) {
                case '.': return KeyEvent.KEYCODE_NUMPAD_DOT;
                case '+': return KeyEvent.KEYCODE_NUMPAD_ADD;
                case '-': return KeyEvent.KEYCODE_NUMPAD_SUBTRACT;
                case '*': return KeyEvent.KEYCODE_NUMPAD_MULTIPLY;
                case '/': return KeyEvent.KEYCODE_NUMPAD_DIVIDE;
                default: return KeyEvent.KEYCODE_UNKNOWN;
            }
        }
        Matcher f = Pattern.compile("^f(1[0-2]|[1-9])$").matcher(p);
        if (f.matches()) return KeyEvent.KEYCODE_F1 + Integer.parseInt(f.group(1)) - 1;
        if (p.length() == 1) {
            char ch = p.charAt(0);
            if (ch >= 'a' && ch <= 'z') return KeyEvent.KEYCODE_A + (ch - 'a');
            if (ch >= '0' && ch <= '9') return KeyEvent.KEYCODE_0 + (ch - '0');
        }
        return KeyEvent.KEYCODE_UNKNOWN;
    }

    // ── Saving a game's list ────────────────────────────────────────────────

    /** Saves the cheat list found in the page text next to the trainer; returns how many. */
    static int savePage(java.io.File trainerDir, String pageText) {
        List<Option> opts = parse(pageText);
        try {
            java.io.File f = new java.io.File(trainerDir, "page.txt");
            try (java.io.OutputStream o = new java.io.FileOutputStream(f)) {
                o.write((pageText == null ? "" : pageText).getBytes("UTF-8"));
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot save page text", t);
        }
        return opts.size();
    }

    /** The cheat list kept with a game's trainer (empty if none). */
    static List<Option> load(java.io.File trainerDir) {
        java.io.File f = new java.io.File(trainerDir, "page.txt");
        if (!f.isFile() || f.length() > 2L << 20) return new ArrayList<>();
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] d = new byte[(int) f.length()];
            int got = 0;
            while (got < d.length) {
                int n = in.read(d, got, d.length - got);
                if (n < 0) break;
                got += n;
            }
            return parse(new String(d, 0, got, "UTF-8"));
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    // ── Pressing keys in the game ───────────────────────────────────────────

    private static Handler ui;

    private static synchronized Handler ui() {
        if (ui == null) ui = new Handler(Looper.getMainLooper());
        return ui;
    }

    /**
     * Presses codes in the game: modifiers and key down in order, then up in
     * reverse, 80 ms apart. Returns false if the game's input can't be reached.
     */
    static boolean press(Context ctx, int[] codes) {
        Object bridge = bridge(ctx);
        Method send = bridge == null ? null : sender(bridge);
        if (send == null) return false;
        long t = 0;
        for (int i = 0; i < codes.length; i++) {
            final int c = codes[i];
            ui().postDelayed(() -> event(bridge, send, KeyEvent.ACTION_DOWN, c), t);
            t += 40;
        }
        t += 80;
        for (int i = codes.length - 1; i >= 0; i--) {
            final int c = codes[i];
            ui().postDelayed(() -> event(bridge, send, KeyEvent.ACTION_UP, c), t);
            t += 40;
        }
        return true;
    }

    private static void event(Object bridge, Method send, int action, int code) {
        try {
            long now = SystemClock.uptimeMillis();
            send.invoke(bridge, new KeyEvent(now, now, action, code, 0));
        } catch (Throwable t) {
            Log.w(TAG, "key " + code + " failed", t);
        }
    }

    /** The game screen's WinUIBridge (WineActivity field), or null. */
    private static Object bridge(Context ctx) {
        if (!(ctx instanceof Activity)) return null;
        for (Class<?> c = ctx.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.getType().getName().equals("com.winemu.openapi.WinUIBridge")) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(ctx);
                    if (v != null) return v;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** WinUIBridge.e0(KeyEvent): the X server key path (void, one KeyEvent). */
    private static Method sender(Object bridge) {
        try {
            Method m = bridge.getClass().getMethod("e0", KeyEvent.class);
            if (m.getReturnType() == void.class) return m;
        } catch (Throwable ignored) {
        }
        Method found = null;
        for (Method m : bridge.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() == void.class && p.length == 1 && p[0] == KeyEvent.class) {
                if (found != null) return null; // ambiguous: don't guess
                found = m;
            }
        }
        return found;
    }
}
