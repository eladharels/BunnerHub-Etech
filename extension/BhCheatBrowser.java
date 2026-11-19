package app.revanced.extension.gamehub;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * BhCheatBrowser — "Find online" for cheat tables: a full-screen browser over
 * the game, opened on FearLess Revolution's search for the current game.
 *
 * It is an ordinary browser (the user signs in once; cookies are kept), not a
 * scraper. When a page starts a download, the file is fetched with the
 * browser's cookies and user agent; a .CT file (or the .CT files inside a
 * .zip) is saved to /sdcard/BunnerHub-Etech/tables/ and imported for the
 * current game with {@link BhCheatTable#importTable}, then the browser
 * closes.
 *
 * The browser runs in the :wine process. Android allows one WebView data
 * directory per process, and the main process already uses WebView (store
 * logins), so this process gets its own suffix before the first WebView.
 */
public final class BhCheatBrowser {

    private static final String TAG = "BhCheatBrowser";
    private static final String SITE = "https://fearlessrevolution.com/";
    private static final int MAX_DOWNLOAD = 32 << 20;
    private static boolean suffixSet;

    public interface Done {
        void onImported(String message);
    }

    /** What to do with a downloaded file: returns the message to show, or throws. */
    public interface Downloaded {
        String handle(Context ctx, byte[] data, String fileName) throws Exception;
    }

    private BhCheatBrowser() {}

    /** Opens the browser over the game on FearLess, importing a downloaded cheat table. */
    public static void show(Context context, Done done) {
        show(context, searchUrl(BhPlayTime.currentGameName()),
                "Find a cheat table: sign in once, then tap the table's download link",
                BhCheatBrowser::importTableData, done);
    }

    /** Opens the browser over the game at startUrl; a download goes to handler. */
    public static void show(Context context, String startUrl, String hint, Downloaded handler, Done done) {
        if (!(context instanceof Activity)) {
            Toast.makeText(context, "Can't open the browser here", Toast.LENGTH_SHORT).show();
            return;
        }
        Activity act = (Activity) context;
        prepareProcess();

        Dialog dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101B2E);

        // Top bar: back, title / status, close.
        LinearLayout bar = new LinearLayout(act);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(act, 8), dp(act, 6), dp(act, 8), dp(act, 6));
        Button back = barButton(act, "‹ Back");
        TextView title = new TextView(act);
        title.setTextColor(0xFFFAFAFA);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setSingleLine(true);
        title.setPadding(dp(act, 12), 0, dp(act, 12), 0);
        title.setText(hint);
        Button close = barButton(act, "Close");
        bar.addView(back);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(close);
        root.addView(bar);
        ProgressBar progress = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(act, 3)));

        FileReady[] onFileRef = new FileReady[1]; // set below, once the handlers exist
        WebView web = new WebView(act);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true); // the forum and its sign-in need it
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String scheme = req.getUrl().getScheme();
                return !"https".equals(scheme) && !"http".equals(scheme); // no intents, files, etc.
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                return intercept(req, onFileRef[0]);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
        });
        Handler ui = new Handler(Looper.getMainLooper());
        boolean[] busy = {false};
        // Some sites (FLiNG) refuse a second request for the same download (single-use
        // links, Referer checks), so a file is taken from the browser's own request
        // where possible (shouldInterceptRequest below), else fetched again with the page as Referer.
        FileReady onFile = (url, data, name, userAgent, referer) -> ui.post(() -> {
            if (busy[0]) return;
            busy[0] = true;
            title.setText("Downloading " + name + "…");
            String cookies = CookieManager.getInstance().getCookie(url);
            new Thread(() -> {
                String msg;
                boolean ok = false;
                try {
                    byte[] bytes = data != null ? data : download(url, userAgent, cookies, referer);
                    msg = handler.handle(act.getApplicationContext(), bytes, name);
                    ok = true;
                } catch (Throwable t) {
                    Log.w(TAG, "download failed: " + url, t);
                    msg = "Could not import: " + (t.getMessage() != null ? t.getMessage() : t.toString());
                }
                String m = msg;
                boolean success = ok;
                ui.post(() -> {
                    busy[0] = false;
                    if (success) {
                        dlg.dismiss();
                        if (done != null) done.onImported(m);
                    } else {
                        title.setText(m);
                        Toast.makeText(act, m, Toast.LENGTH_LONG).show();
                    }
                });
            }, "BhCheatDownload").start();
        });
        onFileRef[0] = onFile;
        web.setDownloadListener((url, userAgent, disposition, mime, length) -> {
            byte[] taken = stash.remove(url);
            onFile.ready(url, taken, URLUtil.guessFileName(url, disposition, mime), userAgent, web.getUrl());
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));

        back.setOnClickListener(v -> {
            if (web.canGoBack()) web.goBack();
        });
        close.setOnClickListener(v -> dlg.dismiss());
        dlg.setOnKeyListener((d, keyCode, ev) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && ev.getAction() == android.view.KeyEvent.ACTION_UP
                    && web.canGoBack()) {
                web.goBack();
                return true;
            }
            return false;
        });
        dlg.setOnDismissListener(d -> {
            CookieManager.getInstance().flush();
            web.stopLoading();
            web.destroy();
        });
        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dlg.show();
        web.loadUrl(startUrl);
    }

    /** FearLess search for the game's name (or the front page without one). */
    static String searchUrl(String game) {
        if (game == null || game.trim().isEmpty()) return SITE;
        String q = game.replaceAll("[™®©:]", " ").replaceAll("\\s+", " ").trim();
        return SITE + "search.php?sr=topics&keywords=" + Uri.encode(q);
    }

    interface FileReady {
        void ready(String url, byte[] data, String name, String userAgent, String referer);
    }

    /** Downloads taken from the browser's own requests, by URL (for the DownloadListener). */
    private static final java.util.Map<String, byte[]> stash = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.regex.Pattern DOWNLOADISH = java.util.regex.Pattern.compile(
            "(?i)(/downloads?[/?]|[?&]download=|download\\.php|file\\.php|\\.(zip|exe|ct|rar|7z)([?#]|$))");

    /**
     * WebView thread: for a top-level GET that looks like a download, make the
     * request here with the browser's headers and cookies. A file is handed to
     * onFile and the browser shows a short note; anything else goes back to the
     * browser (null), which loads it itself.
     */
    static android.webkit.WebResourceResponse intercept(WebResourceRequest req, FileReady onFile) {
        try {
            if (onFile == null || !req.isForMainFrame() || !"GET".equalsIgnoreCase(req.getMethod())) return null;
            String url = req.getUrl().toString();
            if (!url.startsWith("https://") || !DOWNLOADISH.matcher(url).find()) return null;
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            for (java.util.Map.Entry<String, String> h : req.getRequestHeaders().entrySet()) {
                c.setRequestProperty(h.getKey(), h.getValue());
            }
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) c.setRequestProperty("Cookie", cookies);
            try {
                int code = c.getResponseCode();
                keepCookies(c);
                String disposition = c.getHeaderField("Content-Disposition");
                String type = c.getContentType();
                if (code != 200) return null;
                byte[] head = new byte[0];
                byte[] data;
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        if (out.size() > MAX_DOWNLOAD) return null;
                    }
                    data = out.toByteArray();
                }
                boolean file = (disposition != null && disposition.toLowerCase(Locale.US).contains("attachment"))
                        || isZip(data) || (data.length > 1 && data[0] == 'M' && data[1] == 'Z')
                        || looksLikeTable(data) || isRarOr7z(data);
                if (!file) {
                    // A page after all: give the browser what we already have.
                    String mime = type == null ? "text/html" : type.split(";")[0].trim();
                    String charset = "utf-8";
                    if (type != null && type.toLowerCase(Locale.US).contains("charset=")) {
                        charset = type.substring(type.toLowerCase(Locale.US).indexOf("charset=") + 8).trim();
                    }
                    return new android.webkit.WebResourceResponse(mime, charset, new ByteArrayInputStream(data));
                }
                String name = URLUtil.guessFileName(c.getURL().toString(), disposition, type);
                if (name == null || name.isEmpty() || name.endsWith(".bin")) {
                    String n2 = URLUtil.guessFileName(url, disposition, type);
                    if (n2 != null && !n2.isEmpty()) name = n2;
                }
                onFile.ready(url, data, name, null, null);
                String note = "<html><body style='font-family:sans-serif;background:#142036;color:#fafafa;"
                        + "padding:24px'>Downloaded. Importing…</body></html>";
                return new android.webkit.WebResourceResponse("text/html", "utf-8",
                        new ByteArrayInputStream(note.getBytes("UTF-8")));
            } finally {
                c.disconnect();
            }
        } catch (Throwable t) {
            Log.w(TAG, "intercept failed, the browser loads it itself", t);
            return null;
        }
    }

    private static void keepCookies(HttpURLConnection c) {
        try {
            java.util.List<String> set = c.getHeaderFields().get("Set-Cookie");
            if (set == null) return;
            String at = c.getURL().toString();
            for (String v : set) CookieManager.getInstance().setCookie(at, v);
        } catch (Throwable ignored) {
        }
    }

    static boolean isRarOr7z(byte[] d) {
        return (d.length > 4 && d[0] == 'R' && d[1] == 'a' && d[2] == 'r' && d[3] == '!')
                || (d.length > 6 && (d[0] & 0xFF) == 0x37 && (d[1] & 0xFF) == 0x7A && (d[2] & 0xFF) == 0xBC);
    }

    /** One WebView data directory per process (Android 9+). */
    private static synchronized void prepareProcess() {
        if (suffixSet) return;
        suffixSet = true;
        try {
            WebView.setDataDirectorySuffix("bh_wine");
        } catch (Throwable t) {
            Log.w(TAG, "data directory suffix not set", t); // already set or WebView already used here
        }
    }

    // ── Download and import ──────────────────────────────────────────────────

    /** A downloaded cheat table (or a zip of them): save to BunnerHub-Etech/tables and import. */
    static String importTableData(Context ctx, byte[] data, String name) throws Exception {
        if (BhPlayTime.currentGameId() == null) throw new IllegalStateException("No game is running");
        List<NamedFile> tables = new ArrayList<>();
        if (isZip(data)) {
            tables.addAll(tablesInZip(data));
            if (tables.isEmpty()) throw new IllegalStateException("The zip has no .CT file");
        } else if (looksLikeTable(data)) {
            tables.add(new NamedFile(name.toLowerCase(Locale.US).endsWith(".ct") ? name : name + ".CT", data));
        } else if (name.toLowerCase(Locale.US).endsWith(".cetrainer") || name.toLowerCase(Locale.US).endsWith(".exe")) {
            throw new IllegalStateException(name + " is a packaged trainer, not a table");
        } else {
            throw new IllegalStateException(name + " is not a cheat table (are you signed in?)");
        }
        // Several tables in one zip: take the newest-looking name (last when sorted).
        tables.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        NamedFile pick = tables.get(tables.size() - 1);
        File dir = new File(Environment.getExternalStorageDirectory(), "BunnerHub-Etech/tables");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create " + dir);
        File out = new File(dir, safeName(pick.name));
        try (FileOutputStream fo = new FileOutputStream(out)) {
            fo.write(pick.data);
        }
        int[] r = BhCheatTable.importTable(ctx, out);
        String more = tables.size() > 1 ? " (the zip had " + tables.size() + " tables; the others are in BunnerHub-Etech/tables)" : "";
        for (NamedFile f : tables) {
            if (f == pick) continue;
            try (FileOutputStream fo = new FileOutputStream(new File(dir, safeName(f.name)))) {
                fo.write(f.data);
            } catch (Throwable ignored) {
            }
        }
        return (r[1] == 0 ? "Imported " + pick.name + ", but it has no cheats"
                : "Imported " + pick.name + ": " + r[0] + " of " + r[1] + " cheats can be used"
                + (r[0] < r[1] ? " (the rest are scripts, not supported yet)" : "")) + more;
    }

    static byte[] download(String url, String userAgent, String cookies, String referer) throws Exception {
        URL u = new URL(url);
        if (!"https".equals(u.getProtocol())) throw new IllegalStateException("Only https downloads");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
        if (cookies != null) c.setRequestProperty("Cookie", cookies);
        if (referer != null) c.setRequestProperty("Referer", referer);
        c.setRequestProperty("Accept", "*/*");
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("Download failed (HTTP " + code + ")"
                        + (code == 403 ? ": the site refused it. Try the download link again, or download it "
                        + "in your phone's browser and use Use a file / Import file." : ""));
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_DOWNLOAD) throw new IllegalStateException("File too large");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    static final class NamedFile {
        final String name;
        final byte[] data;

        NamedFile(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    static boolean isZip(byte[] d) {
        return d.length > 4 && d[0] == 'P' && d[1] == 'K' && d[2] == 3 && d[3] == 4;
    }

    /** Cheat Engine tables are XML with a CheatTable root. */
    static boolean looksLikeTable(byte[] d) {
        int n = Math.min(d.length, 4096);
        String head = new String(d, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1);
        return head.contains("<CheatTable");
    }

    static List<NamedFile> tablesInZip(byte[] zip) throws Exception {
        List<NamedFile> out = new ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            long total = 0;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                if (e.isDirectory() || !n.toLowerCase(Locale.US).endsWith(".ct")) continue;
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int r;
                while ((r = z.read(buf)) > 0) {
                    b.write(buf, 0, r);
                    total += r;
                    if (total > MAX_DOWNLOAD) throw new IllegalStateException("Zip too large");
                }
                if (looksLikeTable(b.toByteArray())) {
                    int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
                    out.add(new NamedFile(slash >= 0 ? n.substring(slash + 1) : n, b.toByteArray()));
                }
            }
        }
        return out;
    }

    static String safeName(String n) {
        String s = n.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        if (s.isEmpty()) s = "table.CT";
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    private static Button barButton(Context c, String label) {
        Button b = new Button(c);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextColor(0xFFFAFAFA);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x33FFFFFF);
        g.setCornerRadius(dp(c, 8));
        b.setBackground(g);
        return b;
    }

    private static int dp(Context c, int v) {
        return BhPlayTime.dp(c, v);
    }
}
