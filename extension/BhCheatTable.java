package app.revanced.extension.gamehub;

import android.content.Context;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * BhCheatTable — Cheat Engine tables (.CT) for the in-game trainer.
 *
 * A .CT file is XML: CheatTable / CheatEntries / CheatEntry, each with a
 * Description, a VariableType, an Address expression and optional pointer
 * Offsets; groups nest CheatEntries. This reads the value entries:
 *
 *   Address  := term (('+' | '-') term)*
 *   term     := hex number | module name ("game.exe", GameAssembly.dll)
 *             | '[' Address ']'            (read a pointer there)
 *
 * Offsets follow Cheat Engine's memory record: offsets[0] is applied last,
 * so starting from the address, for i = n-1 .. 0: read a pointer, add
 * offsets[i]. Pointers are 4 or 8 bytes, from the game exe's PE header.
 * Module bases come from /proc/<pid>/maps (Wine maps PE files from disk);
 * for the main exe, the usual image bases are tried if it isn't listed.
 *
 * Not supported (shown as such): Auto Assembler scripts, symbols that a
 * script registers, and String / Array of byte / custom types.
 *
 * A table is imported per game (copied to files/bh_tables/<gameId>.ct) from
 * Download/ or /sdcard/BunnerHub-Etech/tables/. Frozen entries are
 * rewritten every 100 ms; their address is resolved again each time, so
 * pointer chains keep up with the game.
 */
public final class BhCheatTable {

    private static final String TAG = "BhCheatTable";
    private static final long FREEZE_MS = 100;

    /** One row of the table. */
    public static final class Entry {
        public String description;
        public int depth;
        public boolean header;          // group heading, no value
        public BhTrainer.Type type;     // null when unsupported or header
        public String why;              // why it can't be used, or null
        String address;
        long[] offsets = new long[0];
        public boolean frozen;
        public double frozenValue;

        public boolean usable() {
            return !header && type != null && why == null;
        }
    }

    private static String gameId;
    private static String tableName;
    private static final List<Entry> entries = new ArrayList<>();
    private static Thread freezer;

    // Per game process: module bases and pointer size.
    private static int cachedPid = -1;
    private static final Map<String, Long> modules = new HashMap<>();
    private static int pointerSize;

    private BhCheatTable() {}

    // ── Loading ──────────────────────────────────────────────────────────────

    /** BhTrainerPanel.create: loads the current game's table, if any. */
    static synchronized void attach(Context ctx) {
        String id = BhPlayTime.currentGameId();
        if (id == null || id.equals(gameId)) return;
        unfreezeAll();
        entries.clear();
        tableName = null;
        gameId = id;
        File f = tableFile(ctx, id);
        if (!f.isFile()) return;
        try {
            entries.addAll(parse(readAll(f)));
            tableName = ctx.getSharedPreferences("bh_tables", Context.MODE_PRIVATE).getString(id, f.getName());
        } catch (Throwable t) {
            Log.w(TAG, "cannot load " + f, t);
        }
    }

    static synchronized List<Entry> entries() {
        return new ArrayList<>(entries);
    }

    static synchronized String tableName() {
        return tableName;
    }

    /** .ct files the user can import, newest first. */
    static List<File> importable() {
        List<File> out = new ArrayList<>();
        File sd = android.os.Environment.getExternalStorageDirectory();
        for (File dir : new File[]{new File(sd, "Download"), new File(sd, "BunnerHub-Etech/tables")}) {
            File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.US).endsWith(".ct"));
            if (files != null) for (File f : files) out.add(f);
        }
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    /** Imports a table for the current game. Returns {usable, total}. */
    static synchronized int[] importTable(Context ctx, File src) throws Exception {
        String id = BhPlayTime.currentGameId();
        if (id == null) throw new IllegalStateException("No game is running");
        byte[] data = readAll(src);
        List<Entry> parsed = parse(data); // throws on a broken file: nothing is replaced
        File dst = tableFile(ctx, id);
        File dir = dst.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create " + dir);
        try (FileOutputStream out = new FileOutputStream(dst)) {
            out.write(data);
        }
        ctx.getSharedPreferences("bh_tables", Context.MODE_PRIVATE).edit().putString(id, src.getName()).apply();
        unfreezeAll();
        entries.clear();
        entries.addAll(parsed);
        gameId = id;
        tableName = src.getName();
        int usable = 0, total = 0;
        for (Entry e : parsed) {
            if (e.header) continue;
            total++;
            if (e.usable()) usable++;
        }
        return new int[]{usable, total};
    }

    static synchronized void removeTable(Context ctx) {
        if (gameId == null) return;
        unfreezeAll();
        entries.clear();
        tableName = null;
        File f = tableFile(ctx, gameId);
        if (f.exists() && !f.delete()) Log.w(TAG, "cannot delete " + f);
        ctx.getSharedPreferences("bh_tables", Context.MODE_PRIVATE).edit().remove(gameId).apply();
    }

    private static File tableFile(Context ctx, String id) {
        return new File(new File(ctx.getFilesDir(), "bh_tables"), id.replaceAll("[^A-Za-z0-9._-]", "_") + ".ct");
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    static List<Entry> parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setExpandEntityReferences(false);
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Throwable ignored) {
            // not every parser knows the feature
        }
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        Element root = doc.getDocumentElement();
        if (root == null || !"CheatTable".equals(root.getNodeName())) {
            throw new IllegalArgumentException("Not a Cheat Engine table");
        }
        List<Entry> out = new ArrayList<>();
        Element list = child(root, "CheatEntries");
        if (list != null) parseEntries(list, 0, out);
        return out;
    }

    private static void parseEntries(Element list, int depth, List<Entry> out) {
        NodeList kids = list.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (!(n instanceof Element) || !"CheatEntry".equals(n.getNodeName())) continue;
            Element el = (Element) n;
            Entry e = new Entry();
            e.depth = depth;
            e.description = unquote(text(el, "Description"));
            if (e.description.isEmpty()) e.description = "(no name)";
            String vt = text(el, "VariableType");
            boolean script = child(el, "AssemblerScript") != null || "Auto Assembler Script".equalsIgnoreCase(vt);
            Element nested = child(el, "CheatEntries");
            boolean group = "1".equals(text(el, "GroupHeader"));
            if (group || (vt.isEmpty() && !script && child(el, "Address") == null)) {
                e.header = true;
            } else if (script) {
                e.why = "script (not supported yet)";
            } else {
                e.type = type(vt);
                if (e.type == null) e.why = (vt.isEmpty() ? "no type" : vt) + " (not supported)";
                e.address = text(el, "Address");
                Element offs = child(el, "Offsets");
                if (offs != null) {
                    List<Long> o = new ArrayList<>();
                    NodeList on = offs.getChildNodes();
                    for (int k = 0; k < on.getLength(); k++) {
                        if (on.item(k) instanceof Element && "Offset".equals(on.item(k).getNodeName())) {
                            try {
                                o.add(parseHex(on.item(k).getTextContent().trim()));
                            } catch (Exception ex) {
                                e.why = "offset \"" + on.item(k).getTextContent().trim() + "\" (not supported)";
                            }
                        }
                    }
                    e.offsets = new long[o.size()];
                    for (int k = 0; k < o.size(); k++) e.offsets[k] = o.get(k);
                }
                if (e.why == null) {
                    if (e.address.isEmpty()) e.why = "no address";
                    else {
                        String bad = unsupportedSymbol(e.address);
                        if (bad != null) e.why = "uses \"" + bad + "\" from a script (not supported yet)";
                    }
                }
            }
            out.add(e);
            if (nested != null) parseEntries(nested, depth + 1, out);
        }
    }

    static BhTrainer.Type type(String vt) {
        switch (vt.trim().toLowerCase(Locale.US)) {
            case "4 bytes": return BhTrainer.Type.INT32;
            case "2 bytes": return BhTrainer.Type.INT16;
            case "byte": return BhTrainer.Type.BYTE;
            case "8 bytes": return BhTrainer.Type.INT64;
            case "float": return BhTrainer.Type.FLOAT;
            case "double": return BhTrainer.Type.DOUBLE;
            default: return null;
        }
    }

    // ── Address expressions ──────────────────────────────────────────────────

    /** The first name in an address that is neither hex nor a module, or null. */
    static String unsupportedSymbol(String expr) {
        for (String tok : tokens(expr)) {
            if (tok.length() == 1 && "+-[]".contains(tok)) continue;
            if (isModule(tok)) continue;
            try {
                parseHex(tok);
            } catch (Exception e) {
                return tok;
            }
        }
        return null;
    }

    private static boolean isModule(String tok) {
        String t = tok.toLowerCase(Locale.US);
        return t.endsWith(".exe") || t.endsWith(".dll");
    }

    static List<String> tokens(String expr) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (char ch : expr.toCharArray()) {
            if (ch == '"') {
                quoted = !quoted;
                continue;
            }
            if (!quoted && (ch == '+' || ch == '-' || ch == '[' || ch == ']')) {
                if (cur.toString().trim().length() > 0) out.add(cur.toString().trim());
                cur.setLength(0);
                out.add(String.valueOf(ch));
            } else if (!quoted && Character.isWhitespace(ch)) {
                if (cur.toString().trim().length() > 0) out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (cur.toString().trim().length() > 0) out.add(cur.toString().trim());
        return out;
    }

    /** Evaluates an address expression for process p, or null. */
    static Long eval(int p, String expr) {
        List<String> t = tokens(expr);
        int[] pos = {0};
        Long v = evalSum(p, t, pos);
        return pos[0] == t.size() ? v : null;
    }

    private static Long evalSum(int p, List<String> t, int[] pos) {
        Long v = evalTerm(p, t, pos);
        while (v != null && pos[0] < t.size() && ("+".equals(t.get(pos[0])) || "-".equals(t.get(pos[0])))) {
            boolean plus = "+".equals(t.get(pos[0]++));
            Long r = evalTerm(p, t, pos);
            if (r == null) return null;
            v = plus ? v + r : v - r;
        }
        return v;
    }

    private static Long evalTerm(int p, List<String> t, int[] pos) {
        if (pos[0] >= t.size()) return null;
        String tok = t.get(pos[0]++);
        if ("[".equals(tok)) {
            Long inner = evalSum(p, t, pos);
            if (inner == null || pos[0] >= t.size() || !"]".equals(t.get(pos[0]++))) return null;
            return BhTrainer.readPointer(p, inner, pointerSize(p));
        }
        if ("-".equals(tok)) {
            Long r = evalTerm(p, t, pos);
            return r == null ? null : -r;
        }
        if (isModule(tok)) return moduleBase(p, tok);
        try {
            return parseHex(tok);
        } catch (Exception e) {
            return null;
        }
    }

    static long parseHex(String s) {
        s = s.trim();
        if (s.startsWith("$")) s = s.substring(1);
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        if (s.isEmpty()) throw new NumberFormatException("empty");
        return Long.parseUnsignedLong(s, 16);
    }

    /** The address of an entry's value right now, or null. */
    static Long resolve(int p, Entry e) {
        if (!e.usable() || p < 0) return null;
        Long a = eval(p, e.address);
        if (a == null) return null;
        int ps = pointerSize(p);
        for (int i = e.offsets.length - 1; i >= 0; i--) {
            Long ptr = BhTrainer.readPointer(p, a, ps);
            if (ptr == null || ptr == 0) return null;
            a = ptr + e.offsets[i];
        }
        return a;
    }

    // ── Modules ──────────────────────────────────────────────────────────────

    private static synchronized void checkPid(int p) {
        if (p != cachedPid) {
            cachedPid = p;
            modules.clear();
            pointerSize = 0;
        }
    }

    /** Lowest mapping of the file named like the module, from /proc/<pid>/maps. */
    static synchronized Long moduleBase(int p, String name) {
        checkPid(p);
        String key = name.toLowerCase(Locale.US);
        Long known = modules.get(key);
        if (known != null) return known;
        Long best = null;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/" + p + "/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\\s+", 6);
                if (f.length < 6) continue;
                String path = f[5].trim();
                int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                String base = (slash >= 0 ? path.substring(slash + 1) : path).toLowerCase(Locale.US);
                if (!base.equals(key)) continue;
                long start = Long.parseUnsignedLong(f[0].split("-")[0], 16);
                if (best == null || Long.compareUnsigned(start, best) < 0) best = start;
            }
        } catch (Exception ignored) {
        }
        if (best == null && key.endsWith(".exe")) {
            // Not listed (copied into anonymous memory): try the usual image bases.
            for (long guess : new long[]{0x400000L, 0x140000000L, 0x10000000L}) {
                if (isPe(p, guess)) {
                    best = guess;
                    break;
                }
            }
        }
        if (best != null) modules.put(key, best);
        return best;
    }

    private static boolean isPe(int p, long base) {
        byte[] h = BhTrainer.readBytes(p, base, 0x40);
        if (h == null || h[0] != 'M' || h[1] != 'Z') return false;
        int lfanew = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getInt(0x3C);
        byte[] pe = BhTrainer.readBytes(p, base + lfanew, 6);
        return pe != null && pe[0] == 'P' && pe[1] == 'E' && pe[2] == 0 && pe[3] == 0;
    }

    /** 4 or 8, from the game exe's PE header (machine type); 8 if unknown. */
    static synchronized int pointerSize(int p) {
        checkPid(p);
        if (pointerSize != 0) return pointerSize;
        int size = 8;
        String exe = exeName(p);
        Long base = exe == null ? null : moduleBase(p, exe);
        if (base != null) {
            byte[] h = BhTrainer.readBytes(p, base, 0x40);
            if (h != null && h[0] == 'M' && h[1] == 'Z') {
                int lfanew = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getInt(0x3C);
                byte[] pe = BhTrainer.readBytes(p, base + lfanew, 6);
                if (pe != null && pe[0] == 'P' && pe[1] == 'E') {
                    int machine = (pe[4] & 0xFF) | ((pe[5] & 0xFF) << 8);
                    size = machine == 0x14C ? 4 : 8;
                }
            }
        }
        pointerSize = size;
        return size;
    }

    /** The game's exe file name from its command line, e.g. "Game.exe". */
    static String exeName(int p) {
        try (InputStream in = new FileInputStream("/proc/" + p + "/cmdline")) {
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            if (n <= 0) return null;
            for (String arg : new String(buf, 0, n).split("\0")) {
                if (arg.toLowerCase(Locale.US).endsWith(".exe")) {
                    int slash = Math.max(arg.lastIndexOf('/'), arg.lastIndexOf('\\'));
                    String name = slash >= 0 ? arg.substring(slash + 1) : arg;
                    if (!name.equalsIgnoreCase("wine64-preloader") && !name.equalsIgnoreCase("start.exe")) return name;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ── Values and freezing ──────────────────────────────────────────────────

    static Double read(Entry e) {
        Long a = resolve(BhTrainer.gamePid(), e);
        return a == null ? null : BhTrainer.read(a, e.type);
    }

    static boolean write(Entry e, double value) {
        Long a = resolve(BhTrainer.gamePid(), e);
        return a != null && BhTrainer.write(a, e.type, value);
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
                synchronized (BhCheatTable.class) {
                    for (Entry e : entries) if (e.frozen) frozen.add(e);
                    if (frozen.isEmpty()) {
                        freezer = null;
                        return;
                    }
                }
                for (Entry e : frozen) write(e, e.frozenValue);
                try {
                    Thread.sleep(FREEZE_MS);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }, "BhCheatTableFreeze");
        freezer.setDaemon(true);
        freezer.start();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Element child(Element parent, String name) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element && name.equals(kids.item(i).getNodeName())) {
                return (Element) kids.item(i);
            }
        }
        return null;
    }

    private static String text(Element parent, String name) {
        Element c = child(parent, name);
        return c == null ? "" : c.getTextContent().trim();
    }

    private static String unquote(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s;
    }

    private static byte[] readAll(File f) throws Exception {
        if (f.length() > 32L << 20) throw new IllegalArgumentException("File too large for a cheat table");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int got = 0;
            while (got < buf.length) {
                int n = in.read(buf, got, buf.length - got);
                if (n < 0) break;
                got += n;
            }
            return buf;
        }
    }
}
