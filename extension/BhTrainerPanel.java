package app.revanced.extension.gamehub;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * BhTrainerPanel — the Trainer page of the in-game sidebar (WineActivity
 * drawer, tab id sidebar_trainer). Built in code; the smali
 * BhTrainerFragment (patches/smali_classes16) only calls {@link #create},
 * {@link #onShown} and {@link #onHidden}. Memory work is in {@link BhTrainer}.
 *
 * Search: type a value and New search; change the value in the game, type
 * the new one and Next search; repeat until a few results are left. Tap a
 * result to set it or save it under a name. Saved values can be set, frozen
 * (kept at a value) and deleted, and come back for the same game.
 */
public final class BhTrainerPanel {

    private static final int WHITE = 0xFFFAFAFA;
    private static final int GREY = 0xFF9DA0A6;
    private static final int CARD = 0x14FFFFFF;
    private static final int ACCENT = 0xFF6C63FF;
    private static final int SHOWN_RESULTS = 50;
    private static final int LOCK_ALL_MAX = 10;

    private static Context ctx;
    private static EditText valueInput;
    private static Button typeButton, nextButton, finderType, lockAll;
    private static LinearLayout tableBox, programBox;
    private static TextView programStatus;
    private static Button programStartStop;
    private static boolean programChecking;
    private static boolean tableReading, tableLoading;
    private static final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final List<Button> compareButtons = new java.util.ArrayList<>();
    private static TextView status, gameLine;
    private static LinearLayout resultsBox, savedBox;
    private static boolean busy;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshValues();
            ui.postDelayed(this, 1000);
        }
    };

    private BhTrainerPanel() {}

    /** BhTrainerFragment.onCreateView. */
    public static View create(Context context) {
        ctx = context;
        BhTrainer.attach(context.getApplicationContext());

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));
        scroll.addView(root);

        TextView title = text(18, WHITE, true);
        title.setText("Trainer");
        root.addView(title);
        gameLine = text(12, GREY, false);
        root.addView(gameLine, margins(0, 2, 0, 12));

        // ── Trainer program (a ready-made trainer .exe run in the game's Wine) ──
        LinearLayout program = card();
        root.addView(program);
        TextView programTitle = text(14, WHITE, true);
        programTitle.setText("Trainer program");
        program.addView(programTitle);
        programBox = new LinearLayout(ctx);
        programBox.setOrientation(LinearLayout.VERTICAL);
        program.addView(programBox, margins(0, 6, 0, 0));

        // ── Cheat table (Cheat Engine .CT) ──
        LinearLayout table = card();
        root.addView(table, margins(0, 12, 0, 0));
        TextView tableTitle = text(14, WHITE, true);
        tableTitle.setText("Cheat table");
        table.addView(tableTitle);
        tableBox = new LinearLayout(ctx);
        tableBox.setOrientation(LinearLayout.VERTICAL);
        table.addView(tableBox, margins(0, 6, 0, 0));

        // ── Health finder: unknown value + lost / gained / same ──
        LinearLayout finder = card();
        root.addView(finder, margins(0, 12, 0, 0));
        TextView finderTitle = text(14, WHITE, true);
        finderTitle.setText("Health finder");
        finder.addView(finderTitle);
        TextView finderHelp = text(11, GREY, false);
        finderHelp.setText("For health bars and other values with no number on screen. "
                + "Tap Start, then tell it each time your health goes down or up.");
        finder.addView(finderHelp, margins(0, 2, 0, 8));
        LinearLayout startRow = new LinearLayout(ctx);
        Button start = button("Start", true);
        start.setOnClickListener(v -> runScan(BhTrainer.Scan.UNKNOWN, false, true));
        finderType = button("", false);
        finderType.setOnClickListener(v -> {
            BhTrainer.Type next = BhTrainer.type() == BhTrainer.Type.FLOAT ? BhTrainer.Type.INT32 : BhTrainer.Type.FLOAT;
            if (busy || !BhTrainer.setType(next)) {
                toast("Wait for the search to finish");
                return;
            }
            updateTypeLabels();
            showResults();
        });
        startRow.addView(start, new LinearLayout.LayoutParams(0, -2, 1f));
        startRow.addView(finderType, wrapLeft(8));
        finder.addView(startRow);
        compareButtons.clear();
        LinearLayout lostRow = new LinearLayout(ctx);
        Button lost = bigButton("I lost health ↓");
        lost.setOnClickListener(v -> runGuided(BhTrainer.Scan.DECREASED));
        Button gained = bigButton("I gained health ↑");
        gained.setOnClickListener(v -> runGuided(BhTrainer.Scan.INCREASED));
        lostRow.addView(lost, new LinearLayout.LayoutParams(0, -2, 1f));
        lostRow.addView(gained, weightMargins(1f, 8));
        finder.addView(lostRow, margins(0, 8, 0, 0));
        Button same = button("Health didn't change", false);
        same.setOnClickListener(v -> runScan(BhTrainer.Scan.UNCHANGED, true, true));
        finder.addView(same, margins(0, 8, 0, 0));
        compareButtons.add(lost);
        compareButtons.add(gained);
        compareButtons.add(same);

        // ── Search by number ──
        LinearLayout search = card();
        root.addView(search, margins(0, 12, 0, 0));
        TextView searchTitle = text(14, WHITE, true);
        searchTitle.setText("Search by number");
        search.addView(searchTitle);

        LinearLayout inputRow = new LinearLayout(ctx);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        valueInput = new EditText(ctx);
        valueInput.setHint("Value, e.g. 1250");
        valueInput.setHintTextColor(0x66FFFFFF);
        valueInput.setTextColor(WHITE);
        valueInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        valueInput.setSingleLine(true);
        valueInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        valueInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        valueInput.setBackground(rounded(0x22FFFFFF, 8));
        valueInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        inputRow.addView(valueInput, new LinearLayout.LayoutParams(0, -2, 1f));
        typeButton = button("", false);
        typeButton.setOnClickListener(v -> cycleType());
        inputRow.addView(typeButton, wrapLeft(8));
        search.addView(inputRow, margins(0, 6, 0, 0));

        LinearLayout buttons = new LinearLayout(ctx);
        Button first = button("New search", true);
        first.setOnClickListener(v -> runScan(BhTrainer.Scan.EXACT, false));
        nextButton = button("Next search", true);
        nextButton.setOnClickListener(v -> runScan(BhTrainer.Scan.EXACT, true));
        Button reset = button("Reset", false);
        reset.setOnClickListener(v -> {
            if (busy || !BhTrainer.clearResults()) {
                toast("Wait for the search to finish");
                return;
            }
            showResults();
        });
        buttons.addView(first, new LinearLayout.LayoutParams(0, -2, 1f));
        buttons.addView(nextButton, weightMargins(1f, 8));
        buttons.addView(reset, wrapLeft(8));
        search.addView(buttons, margins(0, 10, 0, 0));
        // Compared with the last search (no number needed), for any kind of value.
        search.addView(compareRow(BhTrainer.Scan.DECREASED, "Decreased", BhTrainer.Scan.INCREASED, "Increased"),
                margins(0, 8, 0, 0));
        search.addView(compareRow(BhTrainer.Scan.CHANGED, "Changed", BhTrainer.Scan.UNCHANGED, "Unchanged"),
                margins(0, 8, 0, 0));

        // ── Results (both kinds of search) ──
        LinearLayout res = card();
        root.addView(res, margins(0, 12, 0, 0));
        status = text(12, GREY, false);
        res.addView(status);
        resultsBox = new LinearLayout(ctx);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        res.addView(resultsBox, margins(0, 6, 0, 0));
        lockAll = button("Lock all at current value", true);
        lockAll.setOnClickListener(v -> lockAllResults());
        res.addView(lockAll, margins(0, 8, 0, 0));

        // ── Saved values ──
        TextView savedTitle = text(14, WHITE, true);
        savedTitle.setText("Saved values");
        root.addView(savedTitle, margins(0, 18, 0, 6));
        savedBox = new LinearLayout(ctx);
        savedBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(savedBox);

        TextView help = text(11, GREY, false);
        help.setText("Cheat table: tap Find online (FearLess Revolution, searched for this game; sign in once, "
                + "then tap a table's download link and it imports by itself), or put a .CT file in Download "
                + "and tap Import file. Then tap a cheat to set or freeze it. Pick a table for your game's "
                + "version. Value cheats work; script cheats are not supported yet.\n"
                + "Health finder: heal to full, tap Start, then each time you lose or gain health tap the "
                + "matching button (it also drops values that keep changing by themselves). When 10 or fewer "
                + "are left, heal to full and tap Lock all. Nothing found? Switch Float / 4 bytes and Start again. "
                + "If your health regenerates or drains by itself (poison, an animated bar), wait until it "
                + "stops before tapping.\n"
                + "Search by number: type a value you see (e.g. your XP), New search; change it in the game, "
                + "type the new value, Next search; repeat. Tap a result to set or save it.\n"
                + "Start saves a copy of the game's memory (a few hundred MB of app storage) until the "
                + "results are narrowed down or you tap Reset.\n"
                + "Saved addresses can change after the game restarts; search again if a value stops working.\n"
                + "Single-player games only.");
        root.addView(help, margins(0, 18, 0, 0));

        tableLoading = true;
        io.execute(() -> {
            try {
                BhCheatTable.attach(context.getApplicationContext());
            } finally {
                ui.post(() -> {
                    tableLoading = false;
                    showTable();
                });
            }
        });
        updateTypeLabels();
        updateGameLine();
        showProgram();
        showTable();
        showResults();
        showSaved();
        return scroll;
    }

    /** BhTrainerFragment.onResume. */
    public static void onShown() {
        ui.removeCallbacks(refresher);
        ui.post(refresher);
    }

    /** BhTrainerFragment.onPause. */
    public static void onHidden() {
        ui.removeCallbacks(refresher);
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private static void cycleType() {
        BhTrainer.Type[] all = BhTrainer.Type.values();
        BhTrainer.Type next = all[(BhTrainer.type().ordinal() + 1) % all.length];
        if (busy || !BhTrainer.setType(next)) {
            toast("Wait for the search to finish");
            return;
        }
        updateTypeLabels();
        showResults();
    }

    private static LinearLayout compareRow(BhTrainer.Scan a, String aLabel, BhTrainer.Scan b, String bLabel) {
        LinearLayout row = new LinearLayout(ctx);
        Button ba = button(aLabel, false);
        ba.setOnClickListener(v -> runScan(a, true));
        Button bb = button(bLabel, false);
        bb.setOnClickListener(v -> runScan(b, true));
        row.addView(ba, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(bb, weightMargins(1f, 8));
        compareButtons.add(ba);
        compareButtons.add(bb);
        return row;
    }

    private static void updateTypeLabels() {
        BhTrainer.Type t = BhTrainer.type();
        if (typeButton != null) typeButton.setText(t.label);
        if (finderType != null) finderType.setText(t == BhTrainer.Type.INT32 ? "4 bytes" : t.label);
    }

    /**
     * I lost / gained health: Decreased or Increased, then by itself Unchanged
     * a moment later (drops timers, positions and other values that keep
     * changing while the player does nothing).
     */
    private static void runGuided(BhTrainer.Scan kind) {
        if (busy) return;
        if (!BhTrainer.scanned()) {
            toast("Tap Start first (with full health)");
            return;
        }
        busy = true;
        status.setText("Comparing…");
        new Thread(() -> {
            String error = null;
            try {
                BhTrainer.Progress p = pct -> ui.post(() -> status.setText("Comparing… " + pct + "%"));
                BhTrainer.nextScan(kind, 0, p);
                if (BhTrainer.resultCount() > 1) {
                    Thread.sleep(500);
                    ui.post(() -> status.setText("Dropping values that change by themselves…"));
                    BhTrainer.nextScan(BhTrainer.Scan.UNCHANGED, 0, p, true);
                }
            } catch (Throwable t) {
                error = t.getMessage() != null ? t.getMessage() : t.toString();
            }
            String err = error;
            ui.post(() -> {
                busy = false;
                if (err != null) status.setText("Search failed: " + err);
                else afterScan(true);
                updateGameLine();
            });
        }, "BhTrainerGuided").start();
    }

    /** Shows the results; in the Health finder, with none left as Float, offers 4 bytes next. */
    private static void afterScan(boolean finder) {
        if (finder && BhTrainer.scanned() && BhTrainer.resultCount() == 0 && BhTrainer.type() == BhTrainer.Type.FLOAT
                && BhTrainer.setType(BhTrainer.Type.INT32)) {
            updateTypeLabels();
            showResults();
            status.setText("Not found as a Float. Switched to 4 bytes: heal to full and tap Start again.");
            return;
        }
        showResults();
    }

    /** Lock all: saves each remaining result as "Health" and freezes it at its current value. */
    private static void lockAllResults() {
        if (busy || !BhTrainer.listed()) return;
        long n = BhTrainer.resultCount();
        if (n < 1 || n > LOCK_ALL_MAX) return;
        BhTrainer.Type t = BhTrainer.type();
        java.util.Set<Long> saved = new java.util.HashSet<>();
        for (BhTrainer.Entry e : BhTrainer.entries()) if (e.type == t) saved.add(e.address);
        int locked = 0;
        for (int i = 0; i < (int) n; i++) {
            long addr = BhTrainer.result(i);
            if (saved.contains(addr)) continue; // already locked
            Double now = BhTrainer.read(addr, t);
            if (now == null) continue;
            BhTrainer.Entry e = BhTrainer.addEntry(ctx.getApplicationContext(),
                    n == 1 ? "Health" : "Health " + (i + 1), addr, t);
            BhTrainer.setFrozen(e, true, now);
            locked++;
        }
        toast(locked == 0 ? "Already locked (see Saved values)" : "Locked " + locked + (locked == 1 ? " value" : " values")
                + ". If the game acts strangely, unfreeze the extra ones in Saved values.");
        lockAll.setVisibility(View.GONE);
        showSaved();
    }

    // ── Trainer program ──────────────────────────────────────────────────────

    private static void showProgram() {
        if (programBox == null) return;
        programBox.removeAllViews();
        programStatus = null;
        programStartStop = null;
        String id = BhPlayTime.currentGameId();
        if (id == null) {
            TextView none = text(12, GREY, false);
            none.setText("No game running.");
            programBox.addView(none);
            return;
        }
        java.io.File exe = BhTrainerProgram.exe(ctx, id);
        if (exe == null) {
            TextView none = text(12, GREY, false);
            none.setText("No trainer for this game yet. Find online opens a trainer site searched for this game: "
                    + "tap its download link once, and from then on it starts with the game.");
            programBox.addView(none);
            LinearLayout row = new LinearLayout(ctx);
            Button find = button("Find online", true);
            find.setOnClickListener(v -> programFindOnline());
            Button file = button("Use a file", false);
            file.setOnClickListener(v -> programPickFile());
            row.addView(find, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(file, weightMargins(1f, 8));
            programBox.addView(row, margins(0, 8, 0, 0));
            return;
        }
        TextView name = text(13, WHITE, false);
        name.setText(exe.getName());
        programBox.addView(name);
        programStatus = text(12, GREY, false);
        programStatus.setText("Checking…");
        programBox.addView(programStatus, margins(0, 2, 0, 0));

        LinearLayout row = new LinearLayout(ctx);
        programStartStop = button("Start", true);
        programStartStop.setOnClickListener(v -> programStartOrStop());
        Button auto = button(BhTrainerProgram.autostart(ctx, id) ? "Starts with game: ON" : "Starts with game: OFF", false);
        auto.setOnClickListener(v -> {
            BhTrainerProgram.setAutostart(ctx, id, !BhTrainerProgram.autostart(ctx, id));
            showProgram();
        });
        row.addView(programStartStop, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(auto, weightMargins(1f, 8));
        programBox.addView(row, margins(0, 8, 0, 0));

        LinearLayout row2 = new LinearLayout(ctx);
        Button other = button("Find another", false);
        other.setOnClickListener(v -> programFindOnline());
        Button remove = button("Remove", false);
        remove.setOnClickListener(v -> new AlertDialog.Builder(ctx)
                .setTitle("Remove this trainer?")
                .setMessage(exe.getName())
                .setPositiveButton("Remove", (d, w) -> io.execute(() -> {
                    BhTrainerProgram.remove(ctx.getApplicationContext(), id);
                    ui.post(BhTrainerPanel::showProgram);
                }))
                .setNegativeButton("Cancel", null)
                .show());
        row2.addView(other, new LinearLayout.LayoutParams(0, -2, 1f));
        row2.addView(remove, weightMargins(1f, 8));
        programBox.addView(row2, margins(0, 8, 0, 0));

        TextView note = text(11, GREY, false);
        note.setText("The trainer's window opens over the game: switch cheats there, then close this menu. "
                + "Tell us which cheats work: that decides the next step (switches right here).");
        programBox.addView(note, margins(0, 8, 0, 0));
        refreshProgram();
    }

    /** Running or not (scans processes, so off the UI thread). */
    private static void refreshProgram() {
        if (programStatus == null || programChecking || !programStatus.isShown()) return;
        programChecking = true;
        Context app = ctx.getApplicationContext();
        io.execute(() -> {
            int pid = BhTrainerProgram.runningPid(app);
            ui.post(() -> {
                programChecking = false;
                if (programStatus == null || programStartStop == null) return;
                programStatus.setText(pid > 0 ? "Running" : "Not running");
                programStartStop.setText(pid > 0 ? "Stop" : "Start");
                programStartStop.setTag(pid > 0);
            });
        });
    }

    private static void programStartOrStop() {
        boolean running = Boolean.TRUE.equals(programStartStop.getTag());
        Context app = ctx.getApplicationContext();
        programStatus.setText(running ? "Stopping…" : "Starting…");
        io.execute(() -> {
            String msg;
            try {
                if (running) {
                    BhTrainerProgram.stop();
                    msg = "Trainer stopped";
                } else {
                    msg = BhTrainerProgram.start(app);
                }
            } catch (Throwable t) {
                msg = "Couldn't start: " + (t.getMessage() != null ? t.getMessage() : t.toString());
            }
            String m = msg;
            ui.post(() -> {
                toast(m);
                ui.postDelayed(BhTrainerPanel::refreshProgram, 1500);
            });
        });
    }

    private static void programFindOnline() {
        if (BhPlayTime.currentGameId() == null) {
            toast("No game running");
            return;
        }
        BhCheatBrowser.show(ctx, BhTrainerProgram.searchUrl(BhPlayTime.currentGameName()),
                "Find a trainer for this game, then tap its download link",
                BhTrainerProgram::install,
                msg -> {
                    toast(msg + ". It starts with the game; tap Start to run it now.");
                    showProgram();
                });
    }

    private static void programPickFile() {
        List<java.io.File> files = BhTrainerProgram.pickable();
        if (files.isEmpty()) {
            new AlertDialog.Builder(ctx)
                    .setTitle("No trainers found")
                    .setMessage("Put the trainer (.exe or .zip) in your phone's Download folder, then try again.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        String[] names = new String[files.size()];
        for (int i = 0; i < names.length; i++) names[i] = files.get(i).getName();
        new AlertDialog.Builder(ctx)
                .setTitle("Use a trainer for this game")
                .setItems(names, (d, which) -> io.execute(() -> {
                    String msg;
                    try {
                        msg = BhTrainerProgram.install(ctx.getApplicationContext(), files.get(which));
                    } catch (Throwable t) {
                        msg = "Couldn't use it: " + (t.getMessage() != null ? t.getMessage() : t.toString());
                    }
                    String m = msg;
                    ui.post(() -> {
                        toast(m);
                        showProgram();
                    });
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Cheat table ──────────────────────────────────────────────────────────

    private static void showTable() {
        if (tableBox == null) return;
        tableBox.removeAllViews();
        if (tableLoading) {
            TextView wait = text(12, GREY, false);
            wait.setText("Loading…");
            tableBox.addView(wait);
            return;
        }
        if (BhPlayTime.currentGameId() == null) {
            TextView none = text(12, GREY, false);
            none.setText("No game running.");
            tableBox.addView(none);
            return;
        }
        String name = BhCheatTable.tableName();
        if (name == null) {
            TextView none = text(12, GREY, false);
            none.setText("No table for this game. Find online opens FearLess Revolution searched for this game: "
                    + "sign in once and tap a table's download link. Or import a .CT file you downloaded.");
            tableBox.addView(none);
            LinearLayout row = new LinearLayout(ctx);
            Button find = button("Find online", true);
            find.setOnClickListener(v -> findOnline());
            Button imp = button("Import file", false);
            imp.setOnClickListener(v -> importDialog());
            row.addView(find, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(imp, weightMargins(1f, 8));
            tableBox.addView(row, margins(0, 8, 0, 0));
            return;
        }
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView tn = text(12, GREY, false);
        tn.setText(name);
        head.addView(tn, new LinearLayout.LayoutParams(0, -2, 1f));
        Button remove = button("Remove", false);
        remove.setOnClickListener(v -> new AlertDialog.Builder(ctx)
                .setTitle("Remove this table?")
                .setMessage(name)
                .setPositiveButton("Remove", (d, w) -> {
                    BhCheatTable.removeTable(ctx.getApplicationContext());
                    showTable();
                })
                .setNegativeButton("Cancel", null)
                .show());
        Button other = button("Find online", false);
        other.setOnClickListener(v -> findOnline());
        head.addView(other, wrapLeft(8));
        head.addView(remove, wrapLeft(8));
        tableBox.addView(head);
        int usable = 0;
        for (BhCheatTable.Entry e : BhCheatTable.entries()) {
            if (e.header) {
                TextView h = text(12, WHITE, true);
                h.setText(e.description);
                h.setPadding(dp(4 + 12 * e.depth), dp(10), 0, dp(2));
                tableBox.addView(h);
                continue;
            }
            LinearLayout row = listRow(e.description + (e.frozen ? "  ❄" : ""), e.usable() ? "…" : "—");
            row.setPadding(dp(12 + 12 * e.depth), dp(10), dp(12), dp(10));
            row.setTag(e);
            if (!e.usable()) row.setAlpha(0.45f);
            else usable++;
            row.setOnClickListener(v -> tableEntryDialog(e));
            tableBox.addView(row);
        }
        if (usable == 0) {
            TextView note = text(11, GREY, false);
            note.setText("None of this table's cheats can be used yet (they are scripts). "
                    + "Try another table for this game.");
            tableBox.addView(note, margins(0, 8, 0, 0));
        }
        refreshTable();
    }

    private static void findOnline() {
        if (BhPlayTime.currentGameId() == null) {
            toast("No game running");
            return;
        }
        BhCheatBrowser.show(ctx, msg -> {
            toast(msg);
            showTable();
        });
    }

    private static void importDialog() {
        List<java.io.File> files = BhCheatTable.importable();
        if (files.isEmpty()) {
            new AlertDialog.Builder(ctx)
                    .setTitle("No tables found")
                    .setMessage("Put a Cheat Engine table (.CT file) for this game in your phone's Download folder "
                            + "or in BunnerHub-Etech/tables, then tap Import again.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        String[] names = new String[files.size()];
        for (int i = 0; i < names.length; i++) names[i] = files.get(i).getName();
        new AlertDialog.Builder(ctx)
                .setTitle("Import a table for this game")
                .setItems(names, (d, which) -> {
                    java.io.File f = files.get(which);
                    io.execute(() -> {
                        String msg;
                        try {
                            int[] r = BhCheatTable.importTable(ctx.getApplicationContext(), f);
                            msg = r[1] == 0 ? "Imported, but the table has no cheats"
                                    : "Imported: " + r[0] + " of " + r[1] + " cheats can be used"
                                    + (r[0] < r[1] ? " (the rest are scripts, not supported yet)" : "");
                        } catch (Throwable t) {
                            msg = "Could not import: " + (t.getMessage() != null ? t.getMessage() : t.toString());
                        }
                        String m = msg;
                        ui.post(() -> {
                            toast(m);
                            showTable();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void tableEntryDialog(BhCheatTable.Entry e) {
        if (!e.usable()) {
            toast(e.description + ": " + e.why);
            return;
        }
        io.execute(() -> {
            Double now = BhCheatTable.read(e);
            String current = BhTrainer.format(now, e.type);
            ui.post(() -> {
                if (ctx == null) return;
                new AlertDialog.Builder(ctx)
                        .setTitle(e.description)
                        .setMessage(e.type.label + "\nCurrent value: " + current
                                + (now == null ? "\n(not found right now: the game may need to be further in, "
                                + "or the table is for another game version)" : "")
                                + (e.frozen ? "\nFrozen at " + BhTrainer.format(e.frozenValue, e.type) : ""))
                        .setPositiveButton("Set value", (d, w) -> askNumber("Set " + e.description,
                                now == null ? "" : current, val -> io.execute(() -> {
                                    boolean ok = BhCheatTable.write(e, val);
                                    if (e.frozen) BhCheatTable.setFrozen(e, true, val);
                                    ui.post(() -> toast(ok ? "Set to " + BhTrainer.format(val, e.type)
                                            : "Could not write the value"));
                                })))
                        .setNeutralButton(e.frozen ? "Unfreeze" : "Freeze", (d, w) -> {
                            if (e.frozen) {
                                BhCheatTable.setFrozen(e, false, 0);
                            } else if (now == null) {
                                toast("Can't read this value right now");
                                return;
                            } else {
                                BhCheatTable.setFrozen(e, true, now);
                            }
                            showTable();
                        })
                        .setNegativeButton("Close", null)
                        .show();
            });
        });
    }

    /** Reads the table's values off the UI thread (pointer chains mean several reads each). */
    private static void refreshTable() {
        if (tableBox == null || tableReading || tableLoading || BhCheatTable.tableName() == null) return;
        if (!tableBox.isShown()) return;
        tableReading = true;
        List<BhCheatTable.Entry> list = BhCheatTable.entries();
        io.execute(() -> {
            java.util.Map<BhCheatTable.Entry, String> vals = new java.util.HashMap<>();
            try {
                for (BhCheatTable.Entry e : list) {
                    if (e.usable()) vals.put(e, BhTrainer.format(BhCheatTable.read(e), e.type));
                }
            } finally {
                ui.post(() -> {
                    tableReading = false;
                    if (tableBox == null) return;
                    for (int i = 0; i < tableBox.getChildCount(); i++) {
                        View row = tableBox.getChildAt(i);
                        if (!(row.getTag() instanceof BhCheatTable.Entry)) continue;
                        String v = vals.get(row.getTag());
                        TextView value = row.findViewWithTag("value");
                        if (v != null && value != null) value.setText(v);
                    }
                });
            }
        });
    }

    private static Button bigButton(String label) {
        Button b = button(label, true);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setPadding(dp(12), dp(16), dp(12), dp(16));
        return b;
    }

    private static void runScan(BhTrainer.Scan kind, boolean next) {
        runScan(kind, next, false);
    }

    private static void runScan(BhTrainer.Scan kind, boolean next, boolean finderScan) {
        if (busy) return;
        Double typed = parse(valueInput.getText().toString());
        if (kind == BhTrainer.Scan.EXACT && typed == null) {
            toast("Type a number first");
            return;
        }
        if (next && !BhTrainer.scanned()) {
            toast("Tap Start or New search first");
            return;
        }
        double v = typed == null ? 0 : typed;
        busy = true;
        status.setText(kind == BhTrainer.Scan.UNKNOWN ? "Saving a copy of the game's memory…"
                : next ? "Comparing (" + kind.label + ")…" : "Searching the game's memory…");
        new Thread(() -> {
            String error = null;
            try {
                if (kind == BhTrainer.Scan.UNKNOWN) {
                    long mb = BhTrainer.snapshotSize() >> 20;
                    ui.post(() -> status.setText(String.format(Locale.US,
                            "Saving a copy of the game's memory (%,d MB)…", mb)));
                }
                BhTrainer.Progress p = pct -> ui.post(() -> status.setText(
                        (kind == BhTrainer.Scan.UNKNOWN ? "Saving memory… " : "Searching… ") + pct + "%"));
                if (next) BhTrainer.nextScan(kind, v, p);
                else BhTrainer.firstScan(kind, v, p);
            } catch (Throwable t) {
                error = t.getMessage() != null ? t.getMessage() : t.toString();
            }
            String err = error;
            ui.post(() -> {
                busy = false;
                if (err != null) status.setText("Search failed: " + err);
                else afterScan(kind != BhTrainer.Scan.EXACT && finderScan);
                updateGameLine();
            });
        }, "BhTrainerScan").start();
    }

    private static void showResults() {
        if (resultsBox == null) return;
        resultsBox.removeAllViews();
        long n = BhTrainer.resultCount();
        lockAll.setVisibility(View.GONE);
        boolean canNext = BhTrainer.scanned() && n > 0;
        nextButton.setEnabled(canNext);
        nextButton.setAlpha(canNext ? 1f : 0.4f);
        for (Button b : compareButtons) {
            b.setEnabled(canNext);
            b.setAlpha(canNext ? 1f : 0.4f);
        }
        if (!BhTrainer.scanned()) {
            status.setText("Results show here. Health bar: tap Start in Health finder. "
                    + "A number on screen: Search by number.");
            return;
        }
        if (n == 0) {
            status.setText("No results. Check the value and the type (" + BhTrainer.type().label + "), or Reset.");
            return;
        }
        String count = String.format(Locale.US, "%,d", n) + (BhTrainer.truncated() ? "+" : "");
        if (!BhTrainer.listed()) {
            status.setText(count + " possible values. Lose or gain some health in the game, "
                    + "then tap I lost health / I gained health.");
            return;
        }
        if (n > SHOWN_RESULTS) {
            status.setText(count + " results. Keep going: I lost / gained health, "
                    + "or change the number in the game and tap Next search.");
            return;
        }
        if (n <= LOCK_ALL_MAX) lockAll.setVisibility(View.VISIBLE);
        status.setText(count + (n == 1 ? " result" : " results") + ". Tap one to set or save it.");
        BhTrainer.Type t = BhTrainer.type();
        for (int i = 0; i < (int) n; i++) {
            long addr = BhTrainer.result(i);
            LinearLayout row = listRow(String.format(Locale.US, "0x%08X", addr),
                    BhTrainer.format(BhTrainer.read(addr, t), t));
            row.setTag(new Object[]{addr, t});
            row.setOnClickListener(v -> resultDialog(addr, t));
            resultsBox.addView(row);
        }
    }

    private static void resultDialog(long addr, BhTrainer.Type t) {
        String current = BhTrainer.format(BhTrainer.read(addr, t), t);
        new AlertDialog.Builder(ctx)
                .setTitle(String.format(Locale.US, "0x%08X  ·  %s", addr, t.label))
                .setMessage("Current value: " + current)
                .setPositiveButton("Set value", (d, w) -> askNumber("Set value", current, val -> {
                    if (BhTrainer.write(addr, t, val)) toast("Set to " + BhTrainer.format(val, t));
                    else toast("Could not write the value");
                    refreshValues();
                }))
                .setNeutralButton("Save", (d, w) -> askText("Name this value", "e.g. XP, Money, Ammo", name -> {
                    BhTrainer.addEntry(ctx.getApplicationContext(), name, addr, t);
                    showSaved();
                }))
                .setNegativeButton("Close", null)
                .show();
    }

    private static void showSaved() {
        if (savedBox == null) return;
        savedBox.removeAllViews();
        List<BhTrainer.Entry> list = BhTrainer.entries();
        if (list.isEmpty()) {
            TextView none = text(12, GREY, false);
            none.setText("Nothing saved for this game yet.");
            savedBox.addView(none);
            return;
        }
        for (BhTrainer.Entry e : list) {
            LinearLayout row = listRow(e.name + (e.frozen ? "  ❄" : ""),
                    BhTrainer.format(BhTrainer.read(e.address, e.type), e.type));
            row.setTag(e);
            row.setOnClickListener(v -> entryDialog(e));
            savedBox.addView(row);
        }
    }

    private static void entryDialog(BhTrainer.Entry e) {
        String current = BhTrainer.format(BhTrainer.read(e.address, e.type), e.type);
        new AlertDialog.Builder(ctx)
                .setTitle(e.name)
                .setMessage(String.format(Locale.US, "0x%08X  ·  %s\nCurrent value: %s%s", e.address, e.type.label,
                        current, e.frozen ? "\nFrozen at " + BhTrainer.format(e.frozenValue, e.type) : ""))
                .setPositiveButton("Set value", (d, w) -> askNumber("Set " + e.name, current, val -> {
                    BhTrainer.write(e.address, e.type, val);
                    if (e.frozen) BhTrainer.setFrozen(e, true, val);
                    refreshValues();
                }))
                .setNeutralButton(e.frozen ? "Unfreeze" : "Freeze", (d, w) -> {
                    if (e.frozen) {
                        BhTrainer.setFrozen(e, false, 0);
                    } else {
                        Double now = BhTrainer.read(e.address, e.type);
                        if (now == null) {
                            toast("Can't read this address; search again");
                            return;
                        }
                        BhTrainer.setFrozen(e, true, now);
                    }
                    showSaved();
                })
                .setNegativeButton("Delete", (d, w) -> {
                    BhTrainer.setFrozen(e, false, 0);
                    BhTrainer.removeEntry(ctx.getApplicationContext(), e);
                    showSaved();
                })
                .show();
    }

    /** Updates the live values of visible results and saved entries. */
    private static void refreshValues() {
        refreshTable();
        refreshProgram();
        if (busy) return;
        updateValues(resultsBox);
        updateValues(savedBox);
    }

    private static void updateValues(LinearLayout box) {
        if (box == null) return;
        for (int i = 0; i < box.getChildCount(); i++) {
            View row = box.getChildAt(i);
            Object tag = row.getTag();
            TextView value = row.findViewWithTag("value");
            if (value == null || tag == null) continue;
            if (tag instanceof BhTrainer.Entry) {
                BhTrainer.Entry e = (BhTrainer.Entry) tag;
                value.setText(BhTrainer.format(BhTrainer.read(e.address, e.type), e.type));
            } else if (tag instanceof Object[]) {
                Object[] at = (Object[]) tag;
                BhTrainer.Type t = (BhTrainer.Type) at[1];
                value.setText(BhTrainer.format(BhTrainer.read((Long) at[0], t), t));
            }
        }
    }

    private static void updateGameLine() {
        String name = BhPlayTime.currentGameName();
        int pid = BhTrainer.gamePid();
        gameLine.setText(pid < 0 ? "No game process found"
                : (name != null ? name : "Game") + "  ·  process " + pid);
    }

    // ── Dialog helpers ───────────────────────────────────────────────────────

    private interface NumberCallback {
        void accept(double v);
    }

    private interface TextCallback {
        void accept(String s);
    }

    private static void askNumber(String title, String initial, NumberCallback cb) {
        EditText in = new EditText(ctx);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        in.setText("?".equals(initial) ? "" : initial);
        in.setSelectAllOnFocus(true);
        new AlertDialog.Builder(ctx).setTitle(title).setView(padded(in))
                .setPositiveButton("Set", (d, w) -> {
                    Double v = parse(in.getText().toString());
                    if (v == null) toast("Not a number");
                    else cb.accept(v);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private static void askText(String title, String hint, TextCallback cb) {
        EditText in = new EditText(ctx);
        in.setHint(hint);
        in.setSingleLine(true);
        new AlertDialog.Builder(ctx).setTitle(title).setView(padded(in))
                .setPositiveButton("Save", (d, w) -> {
                    String s = in.getText().toString().trim();
                    cb.accept(s.isEmpty() ? "Value" : s);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private static View padded(View v) {
        LinearLayout box = new LinearLayout(ctx);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(v, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    // ── View helpers ─────────────────────────────────────────────────────────

    private static LinearLayout listRow(String left, String right) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(focusable());
        row.setFocusable(true);
        row.setClickable(true);
        TextView l = text(14, WHITE, false);
        l.setText(left);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView r = text(14, WHITE, true);
        r.setTag("value");
        r.setText(right);
        row.addView(r);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(4);
        row.setLayoutParams(lp);
        return row;
    }

    private static LinearLayout card() {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        c.setBackground(rounded(CARD, 12));
        return c;
    }

    private static Button button(String label, boolean primary) {
        Button b = new Button(ctx);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextColor(WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable on = new GradientDrawable();
        on.setColor(primary ? ACCENT : 0x33FFFFFF);
        on.setCornerRadius(dp(8));
        on.setStroke(dp(2), 0xE6FFFFFF);
        GradientDrawable off = new GradientDrawable();
        off.setColor(primary ? ACCENT : 0x33FFFFFF);
        off.setCornerRadius(dp(8));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, off);
        b.setBackground(s);
        return b;
    }

    private static StateListDrawable focusable() {
        GradientDrawable on = new GradientDrawable();
        on.setColor(0x1AFFFFFF);
        on.setCornerRadius(dp(8));
        on.setStroke(dp(2), 0xE6FFFFFF);
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x0DFFFFFF);
        off.setCornerRadius(dp(8));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{android.R.attr.state_pressed}, on);
        s.addState(new int[]{}, off);
        return s;
    }

    private static GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private static TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    /** Full width with margins. */
    private static LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    /** Natural size with a left margin, for buttons in a row. */
    private static LinearLayout.LayoutParams wrapLeft(int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(left);
        return lp;
    }

    private static LinearLayout.LayoutParams weightMargins(float weight, int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, weight);
        lp.leftMargin = dp(left);
        return lp;
    }

    private static Double parse(String s) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (Exception e) {
            return null;
        }
    }

    private static void toast(String s) {
        Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show();
    }

    private static int dp(int v) {
        return BhPlayTime.dp(ctx, v);
    }
}
