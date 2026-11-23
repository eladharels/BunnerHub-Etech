package app.revanced.extension.gamehub;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BhPerfHistory — per-game performance history for the FSR tab.
 *
 * BhUpscalerLog feeds it one sample every 10 s while a game with the BunnerHub layer on is
 * drawing (average FPS over those 10 s, GPU load, CPU / GPU temperature, power). Samples with
 * the same settings (game size, FSR output, sharpness) form a segment; a segment of 30 s or
 * more becomes one line in files/bh_perf/<game id>.tsv when the settings change or the game
 * screen pauses (last 100 kept):
 *   start ms, seconds, settings, avg FPS, low FPS (worst 10% of the 10 s averages), GPU %,
 *   max CPU °C, max GPU °C, power W (-1 if the phone was charging).
 */
final class BhPerfHistory {

    private static final String TAG = "BhPerfHistory";
    private static final int MIN_SAMPLES = 3, KEEP = 100;

    static final class Entry {
        long start;
        int seconds;
        String settings;
        double avg, low;
        int gpu, cpuMax, gpuMax;
        double power;
    }

    private static final class Segment {
        String game, settings;
        long start;
        final List<Double> fps = new ArrayList<>();
        long gpuSum;
        int cpuMax, gpuMax, powerN;
        double powerSum;
        boolean charging;
    }

    private static Segment cur;

    private BhPerfHistory() {}

    /** One 10 s sample (BhUpscalerLog). */
    static synchronized void sample(Context ctx, String game, String settings, double fps, int gpu, int cpuT, int gpuT,
                                    float power, boolean charging) {
        if (cur != null && (!cur.game.equals(game) || !cur.settings.equals(settings))) flush(ctx);
        if (cur == null) {
            cur = new Segment();
            cur.game = game;
            cur.settings = settings;
            cur.start = System.currentTimeMillis() - 10_000;
        }
        cur.fps.add(fps);
        cur.gpuSum += Math.max(0, gpu);
        cur.cpuMax = Math.max(cur.cpuMax, cpuT);
        cur.gpuMax = Math.max(cur.gpuMax, gpuT);
        if (charging) cur.charging = true;
        else if (power > 0) {
            cur.powerSum += power;
            cur.powerN++;
        }
    }

    /** Ends the current segment (settings changed, or the game screen paused). */
    static synchronized void flush(Context ctx) {
        Segment s = cur;
        cur = null;
        if (s == null || s.fps.size() < MIN_SAMPLES || ctx == null) return;
        double sum = 0;
        for (double v : s.fps) sum += v;
        List<Double> sorted = new ArrayList<>(s.fps);
        Collections.sort(sorted);
        double low = sorted.get((int) Math.floor((sorted.size() - 1) * 0.1));
        String line = s.start + "\t" + s.fps.size() * 10 + "\t" + s.settings.replace('\t', ' ') + "\t"
                + String.format(Locale.US, "%.1f\t%.1f\t%d\t%d\t%d\t%.1f", sum / s.fps.size(), low,
                s.gpuSum / s.fps.size(), s.cpuMax, s.gpuMax, s.charging || s.powerN == 0 ? -1.0 : s.powerSum / s.powerN)
                + "\n";
        try {
            File f = file(ctx, s.game);
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            List<String> lines = readLines(f);
            lines.add(line.trim());
            while (lines.size() > KEEP) lines.remove(0);
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                for (String l : lines) o.write((l + "\n").getBytes("UTF-8"));
            }
            if (!tmp.renameTo(f)) Log.w(TAG, "cannot write " + f);
        } catch (Throwable t) {
            Log.w(TAG, "cannot save the session", t);
        }
    }

    static File file(Context ctx, String game) {
        return new File(ctx.getFilesDir(), "bh_perf/" + game.replaceAll("[^A-Za-z0-9._-]", "_") + ".tsv");
    }

    static List<Entry> read(Context ctx, String game) {
        List<Entry> out = new ArrayList<>();
        if (game == null) return out;
        for (String l : readLines(file(ctx, game))) {
            String[] p = l.split("\t");
            if (p.length < 9) continue;
            try {
                Entry e = new Entry();
                e.start = Long.parseLong(p[0]);
                e.seconds = Integer.parseInt(p[1]);
                e.settings = p[2];
                e.avg = Double.parseDouble(p[3]);
                e.low = Double.parseDouble(p[4]);
                e.gpu = Integer.parseInt(p[5]);
                e.cpuMax = Integer.parseInt(p[6]);
                e.gpuMax = Integer.parseInt(p[7]);
                e.power = Double.parseDouble(p[8]);
                out.add(e);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** The FSR tab's text: settings ranked by average FPS, then the latest sessions. */
    static String summary(Context ctx, String game) {
        List<Entry> all = read(ctx, game);
        if (all.isEmpty()) {
            return "No sessions yet. With the upscaler loaded (report only or FSR), every 30 s or more of play with "
                    + "the same settings is recorded here.";
        }
        // Settings ranked: FPS weighted by play time.
        Map<String, double[]> by = new LinkedHashMap<>(); // fps*s, low*s, s, cpuMax, power*s, powerS
        for (Entry e : all) {
            double[] a = by.get(e.settings);
            if (a == null) by.put(e.settings, a = new double[6]);
            a[0] += e.avg * e.seconds;
            a[1] += e.low * e.seconds;
            a[2] += e.seconds;
            a[3] = Math.max(a[3], e.cpuMax);
            if (e.power > 0) {
                a[4] += e.power * e.seconds;
                a[5] += e.seconds;
            }
        }
        List<Map.Entry<String, double[]>> ranked = new ArrayList<>(by.entrySet());
        ranked.sort((x, y) -> Double.compare(y.getValue()[0] / y.getValue()[2], x.getValue()[0] / x.getValue()[2]));
        StringBuilder b = new StringBuilder("By settings (best average FPS first):\n");
        for (int i = 0; i < Math.min(5, ranked.size()); i++) {
            double[] a = ranked.get(i).getValue();
            b.append("• ").append(ranked.get(i).getKey()).append("\n   ")
                    .append(String.format(Locale.US, "%.0f FPS (low %.0f) · %s played · CPU up to %d°C",
                            a[0] / a[2], a[1] / a[2], minutes(a[2]), (int) a[3]));
            if (a[5] > 0) b.append(String.format(Locale.US, " · %.1f W", a[4] / a[5]));
            b.append('\n');
        }
        b.append("\nLatest sessions:\n");
        SimpleDateFormat df = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());
        for (int i = all.size() - 1; i >= Math.max(0, all.size() - 4); i--) {
            Entry e = all.get(i);
            b.append("• ").append(df.format(new Date(e.start))).append(" · ").append(minutes(e.seconds)).append(" · ")
                    .append(e.settings).append("\n   ")
                    .append(String.format(Locale.US, "%.0f FPS (low %.0f) · GPU %d%% · CPU up to %d°C", e.avg, e.low,
                            e.gpu, e.cpuMax));
            if (e.power > 0) b.append(String.format(Locale.US, " · %.1f W", e.power));
            b.append('\n');
        }
        return b.toString().trim();
    }

    private static String minutes(double seconds) {
        return seconds < 90 ? Math.round(seconds) + " s" : Math.round(seconds / 60) + " min";
    }

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<>();
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) if (!l.trim().isEmpty()) out.add(l);
        } catch (Throwable ignored) {
        }
        return out;
    }
}
