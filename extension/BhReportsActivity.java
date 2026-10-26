package app.revanced.extension.gamehub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * BhReportsActivity — "Crash Reports" screen from the launcher's left menu
 * (HomeLeftMenuDialog item id 16). Lists what BhCrashReporter saved in
 * /sdcard/BunnerHub-Etech/reports/, newest first: game reports
 * (report-*.txt summary next to its .zip) and app crashes (app-crash-*.txt).
 * Tapping one shows the summary with Share and Delete.
 *
 * Sharing goes through the app's androidx FileProvider ("<package>.provider"),
 * whose paths only cover the external cache dir, so the file is copied there
 * first.
 */
public class BhReportsActivity extends Activity {

    private static final String TAG = "BhReports";
    private BhNativeScreen screen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        screen = new BhNativeScreen(this, "Crash Reports");
    }

    @Override
    protected void onResume() {
        super.onResume();
        screen.onResume();
        try {
            refresh();
        } catch (Throwable t) {
            Log.e(TAG, "refresh failed", t);
            screen.summary.setText("Could not load reports: " + t);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        screen.onWindowFocusChanged(hasFocus);
    }

    private void refresh() {
        screen.list.removeAllViews();
        File dir = BhCrashReporter.reportDir();
        File[] summaries = dir.listFiles((d, n) -> n.endsWith(".txt")
                && (n.startsWith("report-") || n.startsWith("app-crash-")));
        if (summaries == null) summaries = new File[0];
        Arrays.sort(summaries, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        screen.summary.setText(summaries.length == 0
                ? "No crash reports. When a game crashes or hangs, a report appears here."
                : summaries.length + (summaries.length == 1 ? " report" : " reports")
                        + " in " + dir.getAbsolutePath());

        for (File s : summaries) {
            String text = readText(s);
            String title = field(text, "Game: ");
            String result = field(text, "Result: ");
            if (s.getName().startsWith("app-crash-")) {
                title = "BunnerHub Etech crashed";
                result = firstStackLine(text);
            }
            LinearLayout row = screen.row(
                    title.isEmpty() ? s.getName() : title,
                    result,
                    BhPlayTime.formatDay(s.lastModified()));
            row.setOnClickListener(v -> open(s, text));
            screen.list.addView(row);
        }
        if (screen.list.getChildCount() > 0) screen.list.getChildAt(0).requestFocus();
    }

    private void open(File summary, String text) {
        File zip = new File(summary.getParentFile(), summary.getName().replaceAll("\\.txt$", ".zip"));
        File toShare = zip.isFile() ? zip : summary;
        new AlertDialog.Builder(this)
                .setTitle(summary.getName())
                .setMessage(text.length() > 6000 ? text.substring(0, 6000) + "\n…" : text)
                .setPositiveButton("Share", (d, w) -> share(toShare))
                .setNegativeButton("Delete", (d, w) -> {
                    boolean ok = summary.delete();
                    if (zip.isFile()) ok &= zip.delete();
                    if (!ok) Toast.makeText(this, "Could not delete all files", Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton("Close", null)
                .show();
    }

    private void share(File f) {
        try {
            File dir = new File(getExternalCacheDir(), "share");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
            File copy = new File(dir, f.getName());
            try (FileInputStream in = new FileInputStream(f); FileOutputStream out = new FileOutputStream(copy)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Uri uri = (Uri) Class.forName("androidx.core.content.FileProvider")
                    .getMethod("getUriForFile", android.content.Context.class, String.class, File.class)
                    .invoke(null, this, getPackageName() + ".provider", copy);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(f.getName().endsWith(".zip") ? "application/zip" : "text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, f.getName());
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share report"));
        } catch (Throwable t) {
            Log.e(TAG, "share failed", t);
            Toast.makeText(this, "Share failed. The file is at " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
        }
    }

    private static String readText(File f) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static String field(String text, String prefix) {
        for (String line : text.split("\n")) {
            if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
        }
        return "";
    }

    /** The exception line of an app-crash file (first line after the blank line). */
    private static String firstStackLine(String text) {
        int i = text.indexOf("\n\n");
        if (i < 0) return "";
        String rest = text.substring(i + 2);
        int nl = rest.indexOf('\n');
        return nl < 0 ? rest : rest.substring(0, nl);
    }
}
