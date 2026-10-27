package app.revanced.extension.gamehub;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * BhTrainer — the memory side of the in-game trainer (Cheat Engine style),
 * running in the :wine process next to WineActivity.
 *
 * The game is a Wine process of the same app (same uid and SELinux domain),
 * so /proc/<pid>/mem can be read and written (device-tested with
 * BhMemProbe). 32-bit games under FEX keep their memory below 4 GB with
 * guest addresses equal to host addresses, so addresses fit in an int.
 *
 * Flow: {@link #firstScan} finds every aligned value equal to the input in
 * the game's writable memory; {@link #nextScan} keeps the ones that now hold
 * a new value; {@link #write} sets one; frozen entries are rewritten every
 * 100 ms by a background thread until unfrozen or the game exits.
 *
 * Saved entries (name, address, type) are per game in SharedPreferences
 * "bh_trainer" under the game id; addresses can move between game launches.
 */
public final class BhTrainer {

    private static final String TAG = "BhTrainer";
    /** Results kept from a first scan; more means "search a rarer value". */
    static final int MAX_RESULTS = 4_000_000;
    private static final int CHUNK = 1 << 20;
    private static final long FREEZE_MS = 100;

    /** Value types, Cheat Engine names. */
    public enum Type {
        INT32("4 bytes", 4, 4), FLOAT("Float", 4, 4), INT16("2 bytes", 2, 2),
        BYTE("1 byte", 1, 1), DOUBLE("Double", 8, 4);

        final String label;
        final int size;
        final int align;

        Type(String label, int size, int align) {
            this.label = label;
            this.size = size;
            this.align = align;
        }
    }

    /** A saved or frozen address. */
    public static final class Entry {
        public String name;
        public long address;
        public Type type;
        public boolean frozen;
        public double frozenValue;
    }

    public interface Progress {
        void onProgress(int percent);
    }

    // State. The panel is recreated whenever the sidebar opens; this is not.
    private static int pid = -1;
    private static String gameId;
    private static Type type = Type.INT32;
    private static int[] results = new int[0];
    private static int resultCount;
    private static boolean truncated;
    private static boolean scanned;
    private static final List<Entry> entries = new ArrayList<>();
    private static Thread freezer;

    private BhTrainer() {}

    // ── Game process ─────────────────────────────────────────────────────────

    private static final String[] SYSTEM_EXES = {
            "services.exe", "winedevice.exe", "plugplay.exe", "svchost.exe", "explorer.exe",
            "rpcss.exe", "tabtip.exe", "winmonitor", "conhost.exe", "start.exe", "run.exe",
            "winedbg", "wineboot.exe", "steam.exe",
    };

    /** The game's process: the largest running *.exe that isn't a Wine program. */
    static synchronized int gamePid() {
        if (pid > 0 && new File("/proc/" + pid + "/mem").exists()) return pid;
        int best = -1;
        long bestRss = -1;
        File[] procs = new File("/proc").listFiles();
        for (File p : procs == null ? new File[0] : procs) {
            if (!p.getName().matches("\\d+")) continue;
            String cmd = cmdline(p);
            if (cmd == null || !cmd.toLowerCase(Locale.ROOT).contains(".exe")) continue;
            boolean system = false;
            for (String s : SYSTEM_EXES) if (cmd.toLowerCase(Locale.ROOT).contains(s)) system = true;
            if (system) continue;
            long rss = rssPages(p);
            if (rss > bestRss) {
                bestRss = rss;
                best = Integer.parseInt(p.getName());
            }
        }
        if (best != pid) clearResults(); // a new game process: old addresses are meaningless
        pid = best;
        return pid;
    }

    /** Called when the panel opens: binds saved entries to the running game. */
    static synchronized void attach(Context ctx) {
        String id = BhPlayTime.currentGameId();
        if (id != null && !id.equals(gameId)) {
            gameId = id;
            unfreezeAll();
            entries.clear();
            entries.addAll(loadEntries(ctx, id));
            clearResults();
        }
        gamePid();
    }

    // ── Scanning ─────────────────────────────────────────────────────────────

    static synchronized void setType(Type t) {
        if (t != type) clearResults();
        type = t;
    }

    static Type type() {
        return type;
    }

    static synchronized void clearResults() {
        results = new int[0];
        resultCount = 0;
        truncated = false;
        scanned = false;
    }

    static int resultCount() {
        return resultCount;
    }

    static boolean truncated() {
        return truncated;
    }

    static boolean scanned() {
        return scanned;
    }

    /** Address of result i (unsigned). */
    static long result(int i) {
        return results[i] & 0xFFFFFFFFL;
    }

    /** Every aligned value equal to {@code value} in the game's writable memory below 4 GB. */
    static void firstScan(double value, Progress progress) throws Exception {
        int p = gamePid();
        if (p < 0) throw new IllegalStateException("No game is running");
        Type t = type;
        List<long[]> regions = writableRegions(p);
        long total = 0;
        for (long[] r : regions) total += r[1] - r[0];

        int[] found = new int[1 << 16];
        int count = 0;
        boolean cut = false;
        long done = 0;
        byte[] buf = new byte[CHUNK + 8];
        try (RandomAccessFile mem = new RandomAccessFile("/proc/" + p + "/mem", "r")) {
            outer:
            for (long[] r : regions) {
                for (long base = r[0]; base < r[1]; base += CHUNK) {
                    int len = (int) Math.min(CHUNK, r[1] - base);
                    int n = readAt(mem, base, buf, len);
                    ByteBuffer bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN);
                    for (int off = 0; off + t.size <= n; off += t.align) {
                        if (!matches(t, bb, off, value)) continue;
                        if (count == found.length) {
                            if (count >= MAX_RESULTS) {
                                cut = true;
                                break outer;
                            }
                            found = Arrays.copyOf(found, Math.min(count * 2, MAX_RESULTS));
                        }
                        found[count++] = (int) (base + off);
                    }
                    done += len;
                    if (progress != null && total > 0) progress.onProgress((int) (done * 100 / total));
                }
            }
        }
        synchronized (BhTrainer.class) {
            results = found;
            resultCount = count;
            truncated = cut;
            scanned = true;
        }
        Log.i(TAG, "first scan " + t + " " + value + ": " + count + (cut ? "+ (truncated)" : "")
                + " in " + regions.size() + " regions, " + (total >> 20) + " MB");
    }

    /** Keeps the results that now hold {@code value}. */
    static void nextScan(double value, Progress progress) throws Exception {
        int p = gamePid();
        if (p < 0) throw new IllegalStateException("No game is running");
        Type t = type;
        int[] in;
        int n;
        synchronized (BhTrainer.class) {
            in = results;
            n = resultCount;
        }
        int[] out = new int[Math.max(16, n)];
        int kept = 0;
        byte[] buf = new byte[65536 + 8];
        try (RandomAccessFile mem = new RandomAccessFile("/proc/" + p + "/mem", "r")) {
            int i = 0;
            while (i < n) {
                // Read one window covering as many (sorted) results as fit.
                long start = in[i] & 0xFFFFFFFFL;
                int j = i;
                while (j < n && (in[j] & 0xFFFFFFFFL) + t.size - start <= 65536) j++;
                long end = (in[j - 1] & 0xFFFFFFFFL) + t.size;
                int got;
                try {
                    got = readAt(mem, start, buf, (int) (end - start));
                } catch (Exception e) {
                    got = 0; // unmapped since the first scan
                }
                ByteBuffer bb = ByteBuffer.wrap(buf, 0, got).order(ByteOrder.LITTLE_ENDIAN);
                for (int k = i; k < j; k++) {
                    int off = (int) ((in[k] & 0xFFFFFFFFL) - start);
                    if (off + t.size <= got && matches(t, bb, off, value)) out[kept++] = in[k];
                }
                i = j;
                if (progress != null) progress.onProgress((int) ((long) i * 100 / n));
            }
        }
        synchronized (BhTrainer.class) {
            results = out;
            resultCount = kept;
            truncated = false;
        }
        Log.i(TAG, "next scan " + value + ": " + n + " -> " + kept);
    }

    private static boolean matches(Type t, ByteBuffer bb, int off, double v) {
        switch (t) {
            case INT32: return bb.getInt(off) == (long) v;
            case INT16: return bb.getShort(off) == (long) v || (bb.getShort(off) & 0xFFFF) == (long) v;
            case BYTE: return (bb.get(off) & 0xFF) == (long) v || bb.get(off) == (long) v;
            case FLOAT: {
                float f = bb.getFloat(off);
                return !Float.isNaN(f) && Math.abs(f - v) < 0.5; // "rounded" match, like Cheat Engine
            }
            case DOUBLE: {
                double d = bb.getDouble(off);
                return !Double.isNaN(d) && Math.abs(d - v) < 0.5;
            }
        }
        return false;
    }

    // ── Reading and writing one value ────────────────────────────────────────

    static Double read(long address, Type t) {
        int p = gamePid();
        if (p < 0) return null;
        byte[] b = new byte[8];
        try (RandomAccessFile mem = new RandomAccessFile("/proc/" + p + "/mem", "r")) {
            if (readAt(mem, address, b, t.size) < t.size) return null;
        } catch (Exception e) {
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        switch (t) {
            case INT32: return (double) bb.getInt(0);
            case INT16: return (double) bb.getShort(0);
            case BYTE: return (double) (bb.get(0) & 0xFF);
            case FLOAT: return (double) bb.getFloat(0);
            case DOUBLE: return bb.getDouble(0);
        }
        return null;
    }

    static boolean write(long address, Type t, double value) {
        int p = gamePid();
        if (p < 0) return false;
        ByteBuffer bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        switch (t) {
            case INT32: bb.putInt(0, (int) value); break;
            case INT16: bb.putShort(0, (short) value); break;
            case BYTE: bb.put(0, (byte) value); break;
            case FLOAT: bb.putFloat(0, (float) value); break;
            case DOUBLE: bb.putDouble(0, value); break;
        }
        try (RandomAccessFile mem = new RandomAccessFile("/proc/" + p + "/mem", "rw")) {
            mem.seek(address);
            mem.write(bb.array(), 0, t.size);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "write 0x" + Long.toHexString(address) + " failed", e);
            return false;
        }
    }

    static String format(Double v, Type t) {
        if (v == null) return "?";
        if (t == Type.FLOAT || t == Type.DOUBLE) {
            String s = String.format(Locale.US, "%.3f", v);
            return s.replaceAll("\\.?0+$", "");
        }
        return Long.toString((long) (double) v);
    }

    // ── Entries and freezing ─────────────────────────────────────────────────

    static synchronized List<Entry> entries() {
        return new ArrayList<>(entries);
    }

    static synchronized void addEntry(Context ctx, String name, long address, Type t) {
        Entry e = new Entry();
        e.name = name;
        e.address = address;
        e.type = t;
        entries.add(e);
        saveEntries(ctx);
    }

    static synchronized void removeEntry(Context ctx, Entry e) {
        entries.remove(e);
        saveEntries(ctx);
    }

    static synchronized void setFrozen(Entry e, boolean frozen, double value) {
        e.frozen = frozen;
        e.frozenValue = value;
        if (frozen) startFreezer();
    }

    private static synchronized void unfreezeAll() {
        for (Entry e : entries) e.frozen = false;
    }

    private static synchronized void startFreezer() {
        if (freezer != null && freezer.isAlive()) return;
        freezer = new Thread(() -> {
            while (true) {
                List<Entry> frozen = new ArrayList<>();
                synchronized (BhTrainer.class) {
                    for (Entry e : entries) if (e.frozen) frozen.add(e);
                    if (frozen.isEmpty()) {
                        freezer = null;
                        return;
                    }
                }
                for (Entry e : frozen) write(e.address, e.type, e.frozenValue);
                try {
                    Thread.sleep(FREEZE_MS);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }, "BhTrainerFreeze");
        freezer.setDaemon(true);
        freezer.start();
    }

    private static List<Entry> loadEntries(Context ctx, String id) {
        List<Entry> out = new ArrayList<>();
        try {
            String raw = prefs(ctx).getString(id, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry();
                e.name = o.optString("name", "Value");
                e.address = o.optLong("address");
                e.type = Type.valueOf(o.optString("type", "INT32"));
                out.add(e);
            }
        } catch (Throwable t) {
            Log.w(TAG, "loading entries failed", t);
        }
        return out;
    }

    private static void saveEntries(Context ctx) {
        if (gameId == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("name", e.name);
                o.put("address", e.address);
                o.put("type", e.type.name());
                arr.put(o);
            }
            prefs(ctx).edit().putString(gameId, arr.toString()).apply();
        } catch (Throwable t) {
            Log.w(TAG, "saving entries failed", t);
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("bh_trainer", Context.MODE_PRIVATE);
    }

    // ── /proc helpers ────────────────────────────────────────────────────────

    /** Writable, private-or-anonymous mappings below 4 GB: {start, end}. */
    private static List<long[]> writableRegions(int p) throws Exception {
        List<long[]> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/" + p + "/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\\s+", 6);
                if (f.length < 2 || !f[1].startsWith("rw")) continue;
                String path = f.length >= 6 ? f[5] : "";
                // Skip device and GPU mappings: reading them can be slow or have side effects.
                if (path.startsWith("/dev/") && !path.startsWith("/dev/ashmem") && !path.startsWith("/dev/zero")) continue;
                String[] range = f[0].split("-");
                long start = Long.parseUnsignedLong(range[0], 16);
                long end = Long.parseUnsignedLong(range[1], 16);
                if (start < 0x10000 || start >= 0x1_0000_0000L) continue;
                out.add(new long[]{start, Math.min(end, 0x1_0000_0000L)});
            }
        }
        return out;
    }

    /** Reads up to len bytes; stops early (returns fewer) at an unreadable page. */
    private static int readAt(RandomAccessFile mem, long address, byte[] buf, int len) throws Exception {
        mem.seek(address);
        int got = 0;
        while (got < len) {
            int n;
            try {
                n = mem.read(buf, got, len - got);
            } catch (java.io.IOException e) {
                break;
            }
            if (n <= 0) break;
            got += n;
        }
        return got;
    }

    private static String cmdline(File p) {
        try (FileInputStream in = new FileInputStream(new File(p, "cmdline"))) {
            byte[] buf = new byte[512];
            int n = in.read(buf);
            return n <= 0 ? null : new String(buf, 0, n).replace('\0', ' ').trim();
        } catch (Throwable t) {
            return null;
        }
    }

    private static long rssPages(File p) {
        try (BufferedReader r = new BufferedReader(new FileReader(new File(p, "statm")))) {
            String l = r.readLine();
            return l == null ? 0 : Long.parseLong(l.trim().split("\\s+")[1]);
        } catch (Throwable t) {
            return 0;
        }
    }
}
