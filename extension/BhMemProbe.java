package app.revanced.extension.gamehub;

import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * BhMemProbe — one-off feasibility check for an in-game trainer: can the app
 * read and write the running game's memory through /proc/<pid>/mem?
 *
 * Runs only when /sdcard/BunnerHub-Etech/memprobe exists (created by hand for
 * the test), 20 s after the game screen first shows. It finds the game's Wine
 * process (largest *.exe process that isn't a Wine system program), reads 16
 * bytes from its first writable mapping below 4 GB (where 32-bit games live
 * under FEX), writes the same 16 bytes back and reads them again, so the game
 * is not changed. The result goes to logcat (tag BhMemProbe) and to
 * /sdcard/BunnerHub-Etech/memprobe-result.txt.
 */
public final class BhMemProbe {

    private static final String TAG = "BhMemProbe";
    private static volatile boolean started;

    private static final String[] SYSTEM_EXES = {
            "services.exe", "winedevice.exe", "plugplay.exe", "svchost.exe", "explorer.exe",
            "rpcss.exe", "tabtip.exe", "winmonitor", "conhost.exe", "start.exe", "run.exe",
            "winedbg", "steam.exe", "wineboot.exe",
    };

    private BhMemProbe() {}

    public static void maybeStart() {
        File trigger = new File(Environment.getExternalStorageDirectory(), "BunnerHub-Etech/memprobe");
        if (started || !trigger.exists()) return;
        started = true;
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            try {
                Thread.sleep(20_000);
                run(out);
            } catch (Throwable t) {
                out.append("probe crashed: ").append(t).append('\n');
            }
            Log.i(TAG, out.toString());
            try (FileWriter w = new FileWriter(new File(Environment.getExternalStorageDirectory(),
                    "BunnerHub-Etech/memprobe-result.txt"))) {
                w.write(out.toString());
            } catch (Throwable t) {
                Log.w(TAG, "cannot write result", t);
            }
        }, "BhMemProbe").start();
    }

    private static void run(StringBuilder out) throws Exception {
        out.append("self pid ").append(android.os.Process.myPid())
                .append(" context ").append(readFirstLine("/proc/self/attr/current")).append('\n');

        int pid = -1;
        long bestRss = -1;
        String bestCmd = null;
        File[] procs = new File("/proc").listFiles();
        for (File p : procs == null ? new File[0] : procs) {
            String name = p.getName();
            if (!name.matches("\\d+")) continue;
            String cmd = readCmdline(p);
            if (cmd == null || !cmd.toLowerCase().contains(".exe")) continue;
            boolean system = false;
            for (String s : SYSTEM_EXES) if (cmd.toLowerCase().contains(s)) system = true;
            if (system) continue;
            long rss = rssPages(p);
            if (rss > bestRss) {
                bestRss = rss;
                pid = Integer.parseInt(name);
                bestCmd = cmd;
            }
        }
        if (pid < 0) {
            out.append("no game process found\n");
            return;
        }
        out.append("game pid ").append(pid).append(" rss pages ").append(bestRss)
                .append(" cmd ").append(bestCmd).append('\n');
        out.append("game context ").append(readFirstLine("/proc/" + pid + "/attr/current")).append('\n');
        out.append("game status: ").append(grep("/proc/" + pid + "/status", "Uid|TracerPid")).append('\n');

        long addr = -1;
        long regions = 0, writable = 0, low = 0;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/" + pid + "/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                regions++;
                String[] f = line.split("\\s+");
                String[] range = f[0].split("-");
                long start = Long.parseUnsignedLong(range[0], 16);
                long end = Long.parseUnsignedLong(range[1], 16);
                if (!f[1].startsWith("rw")) continue;
                writable++;
                if (end <= 0x1_0000_0000L) low += end - start;
                if (addr < 0 && start >= 0x10000 && end <= 0x1_0000_0000L && end - start >= 4096) addr = start;
            }
            out.append("maps: ").append(regions).append(" regions, ").append(writable)
                    .append(" writable, ").append(low / (1024 * 1024)).append(" MB writable below 4 GB\n");
        } catch (Throwable t) {
            out.append("maps: FAILED ").append(t).append('\n');
            return;
        }
        if (addr < 0) {
            out.append("no writable mapping below 4 GB\n");
            return;
        }
        out.append("test address 0x").append(Long.toHexString(addr)).append('\n');

        byte[] before = new byte[16];
        try (RandomAccessFile mem = new RandomAccessFile("/proc/" + pid + "/mem", "r")) {
            mem.seek(addr);
            mem.readFully(before);
            out.append("READ: OK ").append(hex(before)).append('\n');
        } catch (Throwable t) {
            out.append("READ: FAILED ").append(t).append('\n');
            return;
        }
        try (RandomAccessFile mem = new RandomAccessFile("/proc/" + pid + "/mem", "rw")) {
            mem.seek(addr);
            mem.write(before);
            byte[] after = new byte[16];
            mem.seek(addr);
            mem.readFully(after);
            out.append("WRITE (same bytes back): OK, read-back ")
                    .append(Arrays.equals(before, after) ? "matches" : "DIFFERS " + hex(after)).append('\n');
        } catch (Throwable t) {
            out.append("WRITE: FAILED ").append(t).append('\n');
        }
    }

    private static String readCmdline(File p) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(new File(p, "cmdline"))) {
            byte[] buf = new byte[512];
            int n = in.read(buf);
            if (n <= 0) return null;
            return new String(buf, 0, n).replace('\0', ' ').trim();
        } catch (Throwable t) {
            return null;
        }
    }

    private static long rssPages(File p) {
        String s = readFirstLine(new File(p, "statm").getPath());
        try {
            return Long.parseLong(s.split(" ")[1]);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String readFirstLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String l = r.readLine();
            return l == null ? "" : l.trim();
        } catch (Throwable t) {
            return "(" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String grep(String path, String regex) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String l;
            while ((l = r.readLine()) != null) if (l.matches("(" + regex + "):.*")) sb.append(l.replace('\t', ' ')).append("; ");
        } catch (Throwable t) {
            sb.append(t);
        }
        return sb.toString();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
