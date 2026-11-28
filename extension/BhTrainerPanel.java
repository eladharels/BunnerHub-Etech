package app.revanced.extension.gamehub;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * BhTrainerPanel — the Trainer page of the in-game sidebar (WineActivity
 * drawer, tab id sidebar_trainer). Built in code with {@link BhUi}; the smali
 * BhTrainerFragment (patches/smali_classes16) only calls {@link #create},
 * {@link #onShown} and {@link #onHidden}. Memory work is in {@link BhTrainer}.
 *
 * Three sections, the last one used is remembered:
 * - Cheats: the trainer program (a ready-made trainer .exe run in the game's
 *   Wine) with a switch per cheat that presses its keys, and the cheat table
 *   (Cheat Engine .CT) with its value cheats.
 * - Search: the guided Health finder, and search by number (type a value, New
 *   search; change it in the game, type the new one, Next; repeat).
 * - Saved: values saved from a search, each with a freeze switch.
 */
public final class BhTrainerPanel {

    private static final int SHOWN_RESULTS = 50;
    private static final int LOCK_ALL_MAX = 10;
    private static final int CHEATS = 0, SEARCH = 1, SAVED = 2;
    private static final BhTrainer.Type[] MAIN_TYPES = {BhTrainer.Type.INT32, BhTrainer.Type.FLOAT,
            BhTrainer.Type.INT16, BhTrainer.Type.BYTE};

    private static Context ctx;
    private static FrameLayout host;
    private static LinearLayout[] pages;
    private static BhUi.Segmented tabs, typeSeg;
    private static EditText valueInput;
    private static Button nextButton, finderType, lockAll;
    private static LinearLayout tableBox, programBox, finderBody;
    private static TextView programStatus;
    private static Button programStartStop, programMono;
    private static boolean programChecking, programBusy, programRefocus;
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
        root.setPadding(dp(20), dp(16), dp(20), dp(72)); // room for a snackbar
        scroll.addView(root);

        // ── Header: title, game, sections ──
        LinearLayout header = BhUi.row(ctx);
        LinearLayout titles = new LinearLayout(ctx);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = BhUi.text(ctx, 18, BhUi.WHITE, true);
        title.setText("Trainer");
        titles.addView(title);
        gameLine = BhUi.text(ctx, 12, BhUi.GREY, false);
        gameLine.setSingleLine(true);
        gameLine.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(gameLine);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs = new BhUi.Segmented(ctx, new String[]{"Cheats", "Search", "Saved"}, BhTrainerPanel::showPage);
        header.addView(tabs.view, BhUi.next(ctx, 10));
        root.addView(header);

        pages = new LinearLayout[3];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = new LinearLayout(ctx);
            pages[i].setOrientation(LinearLayout.VERTICAL);
            root.addView(pages[i], BhUi.below(ctx, 14));
        }
        buildCheats(pages[CHEATS]);
        buildSearch(pages[SEARCH]);
        buildSaved(pages[SAVED]);

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

        host = BhUi.host(ctx, scroll);
        int page = prefs().getInt("page", -1);
        if (page < 0) {
            String id = BhPlayTime.currentGameId();
            page = id != null && BhTrainerProgram.exe(ctx, id) != null ? CHEATS
                    : !BhTrainer.entries().isEmpty() ? SAVED : CHEATS;
        }
        tabs.select(page);
        showPage(page);
        return host;
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

    private static SharedPreferences prefs() {
        return ctx.getSharedPreferences("bh_trainer_ui", Context.MODE_PRIVATE);
    }

    private static void showPage(int page) {
        for (int i = 0; i < pages.length; i++) pages[i].setVisibility(i == page ? View.VISIBLE : View.GONE);
        prefs().edit().putInt("page", page).apply();
        refreshValues();
    }

    // ── Cheats section ───────────────────────────────────────────────────────

    private static void buildCheats(LinearLayout page) {
        programBox = new LinearLayout(ctx);
        programBox.setOrientation(LinearLayout.VERTICAL);
        page.addView(programBox);

        TextView tableTitle = sectionTitle("Cheat table");
        page.addView(tableTitle, BhUi.below(ctx, 20));
        tableBox = new LinearLayout(ctx);
        tableBox.setOrientation(LinearLayout.VERTICAL);
        page.addView(tableBox, BhUi.below(ctx, 6));
        page.addView(note("Find online opens FearLess Revolution searched for this game (sign in once, then tap a "
                + "table's download link), or put a .CT file in Download and tap Import file. Pick a table for "
                + "your game's version. Value cheats work; script cheats are not supported yet. Single-player games "
                + "only."), BhUi.below(ctx, 10));
    }

    // ── Search section ───────────────────────────────────────────────────────

    private static void buildSearch(LinearLayout page) {
        // Search by number
        LinearLayout inputRow = BhUi.row(ctx);
        valueInput = new EditText(ctx);
        valueInput.setHint("Value, e.g. 1250");
        valueInput.setHintTextColor(0x80FFFFFF);
        valueInput.setTextColor(BhUi.WHITE);
        valueInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        valueInput.setSingleLine(true);
        valueInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        valueInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        valueInput.setBackground(BhUi.states(ctx, BhUi.WELL, BhUi.LINE, 12));
        valueInput.setPadding(dp(14), 0, dp(14), 0);
        valueInput.setContentDescription("Value to find");
        valueInput.setOnEditorActionListener((v, action, ev) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            runScan(BhTrainer.Scan.EXACT, BhTrainer.scanned() && BhTrainer.resultCount() > 0);
            return true;
        });
        inputRow.addView(valueInput, new LinearLayout.LayoutParams(0, dp(44), 1f));
        Button first = BhUi.button(ctx, "New search", BhUi.PRIMARY);
        BhUi.onTap(first, () -> runScan(BhTrainer.Scan.EXACT, false));
        inputRow.addView(first, BhUi.next(ctx, 8));
        nextButton = BhUi.button(ctx, "Next", BhUi.SECONDARY);
        BhUi.onTap(nextButton, () -> runScan(BhTrainer.Scan.EXACT, true));
        inputRow.addView(nextButton, BhUi.next(ctx, 8));
        page.addView(inputRow);

        LinearLayout typeRow = BhUi.row(ctx);
        typeSeg = new BhUi.Segmented(ctx, new String[]{"4 bytes", "Float", "2 bytes", "1 byte", "More"}, i -> {
            if (i < MAIN_TYPES.length) {
                setType(MAIN_TYPES[i]);
            } else {
                String[] more = {BhTrainer.Type.DOUBLE.label, BhTrainer.Type.INT64.label};
                new AlertDialog.Builder(ctx).setTitle("Value type")
                        .setItems(more, (d, w) -> setType(w == 0 ? BhTrainer.Type.DOUBLE : BhTrainer.Type.INT64))
                        .setOnDismissListener(d -> updateTypeLabels())
                        .show();
            }
        });
        typeRow.addView(typeSeg.view);
        Button reset = BhUi.button(ctx, "Reset", BhUi.QUIET);
        BhUi.onTap(reset, () -> {
            if (busy || !BhTrainer.clearResults()) {
                toast("Wait for the search to finish");
                return;
            }
            showResults();
        });
        typeRow.addView(reset, BhUi.next(ctx, 4));
        page.addView(typeRow, BhUi.below(ctx, 8));

        // Compared with the last search (no number needed), for any kind of value.
        page.addView(compareRow(BhTrainer.Scan.DECREASED, "Decreased", BhTrainer.Scan.INCREASED, "Increased",
                BhTrainer.Scan.CHANGED, "Changed", BhTrainer.Scan.UNCHANGED, "Same"), BhUi.below(ctx, 8));

        // Results (both kinds of search)
        status = BhUi.text(ctx, 13, BhUi.SOFT, false);
        page.addView(status, BhUi.below(ctx, 12));
        resultsBox = new LinearLayout(ctx);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        page.addView(resultsBox, BhUi.below(ctx, 4));
        lockAll = BhUi.button(ctx, "Lock all at current value", BhUi.PRIMARY);
        BhUi.onTap(lockAll, BhTrainerPanel::lockAllResults);
        page.addView(lockAll, BhUi.below(ctx, 8));

        // Health finder: unknown value + lost / gained / same, folded away until opened
        page.addView(BhUi.linkRow(ctx, "Health bar with no number?", "Guided finder: Start, then tell it when "
                + "health goes down or up", () -> finderBody.setVisibility(
                finderBody.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)), BhUi.below(ctx, 14));
        finderBody = BhUi.card(ctx);
        finderBody.setVisibility(View.GONE);
        finderBody.addView(note("1. Heal to full, tap Start.  2. Each time you lose or gain health, tap the matching "
                + "button (it also drops values that change by themselves).  3. When 10 or fewer are left, heal to "
                + "full and tap Lock all. Nothing found? Switch Float / 4 bytes and Start again. If health "
                + "regenerates or drains by itself, wait until it stops before tapping."));
        LinearLayout startRow = BhUi.row(ctx);
        Button start = BhUi.button(ctx, "Start", BhUi.PRIMARY);
        BhUi.onTap(start, () -> runScan(BhTrainer.Scan.UNKNOWN, false, true));
        startRow.addView(start, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        finderType = BhUi.button(ctx, "", BhUi.SECONDARY);
        BhUi.onTap(finderType, () -> setType(BhTrainer.type() == BhTrainer.Type.FLOAT
                ? BhTrainer.Type.INT32 : BhTrainer.Type.FLOAT));
        startRow.addView(finderType, BhUi.next(ctx, 8));
        finderBody.addView(startRow, BhUi.below(ctx, 10));
        compareButtons.clear();
        LinearLayout lostRow = BhUi.row(ctx);
        Button lost = BhUi.button(ctx, "I lost health ↓", BhUi.SECONDARY);
        BhUi.onTap(lost, () -> runGuided(BhTrainer.Scan.DECREASED));
        Button gained = BhUi.button(ctx, "I gained health ↑", BhUi.SECONDARY);
        BhUi.onTap(gained, () -> runGuided(BhTrainer.Scan.INCREASED));
        lostRow.addView(lost, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        lostRow.addView(gained, BhUi.fill(ctx, 8));
        finderBody.addView(lostRow, BhUi.below(ctx, 8));
        Button same = BhUi.button(ctx, "Health didn't change", BhUi.SECONDARY);
        BhUi.onTap(same, () -> runScan(BhTrainer.Scan.UNCHANGED, true, true));
        finderBody.addView(same, BhUi.below(ctx, 8));
        compareButtons.add(lost);
        compareButtons.add(gained);
        compareButtons.add(same);
        page.addView(finderBody, BhUi.below(ctx, 8));

        page.addView(note("New search saves a copy of the game's memory (a few hundred MB of app storage) until "
                + "the results are narrowed down or you tap Reset. Saved addresses can change after the game "
                + "restarts; search again if a value stops working."), BhUi.below(ctx, 12));
    }

    // ── Saved section ────────────────────────────────────────────────────────

    private static void buildSaved(LinearLayout page) {
        savedBox = new LinearLayout(ctx);
        savedBox.setOrientation(LinearLayout.VERTICAL);
        page.addView(savedBox);
        page.addView(note("Switch on = frozen at its value (rewritten 10 times a second). Tap a value to change "
                + "or delete it."), BhUi.below(ctx, 10));
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private static void setType(BhTrainer.Type t) {
        if (busy || !BhTrainer.setType(t)) {
            toast("Wait for the search to finish");
        }
        updateTypeLabels();
        showResults();
    }

    private static LinearLayout compareRow(Object... scansAndLabels) {
        LinearLayout row = BhUi.row(ctx);
        for (int i = 0; i < scansAndLabels.length; i += 2) {
            BhTrainer.Scan scan = (BhTrainer.Scan) scansAndLabels[i];
            Button b = BhUi.button(ctx, (String) scansAndLabels[i + 1], BhUi.SECONDARY);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            b.setPadding(dp(8), 0, dp(8), 0);
            BhUi.onTap(b, () -> runScan(scan, true));
            row.addView(b, i == 0 ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    : BhUi.fill(ctx, 6));
            compareButtons.add(b);
        }
        return row;
    }

    private static void updateTypeLabels() {
        BhTrainer.Type t = BhTrainer.type();
        if (typeSeg != null) {
            int idx = MAIN_TYPES.length;
            for (int i = 0; i < MAIN_TYPES.length; i++) if (MAIN_TYPES[i] == t) idx = i;
            typeSeg.items[MAIN_TYPES.length].setText(idx == MAIN_TYPES.length ? t.label : "More");
            typeSeg.select(idx);
        }
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
        snackToSaved(locked == 0 ? "Already locked" : "Locked " + locked + (locked == 1 ? " value" : " values")
                + ". If the game acts strangely, unfreeze the extra ones.");
        lockAll.setVisibility(View.GONE);
        showSaved();
    }

    /** One result: save it and freeze it at its current value. */
    private static void lockOne(long addr, BhTrainer.Type t) {
        for (BhTrainer.Entry e : BhTrainer.entries()) {
            if (e.address == addr && e.type == t) {
                snackToSaved("Already saved");
                return;
            }
        }
        Double now = BhTrainer.read(addr, t);
        if (now == null) {
            toast("Can't read this value right now");
            return;
        }
        BhTrainer.Entry e = BhTrainer.addEntry(ctx.getApplicationContext(), "Value " + (BhTrainer.entries().size() + 1),
                addr, t);
        BhTrainer.setFrozen(e, true, now);
        showSaved();
        snackToSaved("Locked at " + BhTrainer.format(now, t));
    }

    private static void snackToSaved(String msg) {
        BhUi.snack(host, msg, "View", () -> {
            tabs.select(SAVED);
            showPage(SAVED);
        });
    }

    // ── Trainer program ──────────────────────────────────────────────────────

    private static void showProgram() {
        if (programBox == null) return;
        // Rebuilding drops the focused button: put controller focus back in the card.
        boolean refocus = programBox.hasFocus() || programRefocus;
        programRefocus = false;
        if (refocus) programBox.post(() -> {
            if (programBox == null) return;
            View first = programBox.findFocus() != null ? null : firstFocusable(programBox);
            if (first != null) first.requestFocus();
        });
        programBox.removeAllViews();
        programStatus = null;
        programStartStop = null;
        programMono = null;
        String id = BhPlayTime.currentGameId();
        if (id == null) {
            programBox.addView(note("No game running."));
            return;
        }
        java.io.File exe = BhTrainerProgram.exe(ctx, id);
        if (exe == null) {
            LinearLayout card = BhUi.card(ctx);
            TextView t = BhUi.text(ctx, 14, BhUi.WHITE, true);
            t.setText("Trainer program");
            card.addView(t);
            card.addView(note("No trainer for this game yet. Find online opens a trainer site searched for this "
                    + "game: tap its download link once, and from then on it starts with the game."), BhUi.below(ctx, 4));
            LinearLayout row = BhUi.row(ctx);
            Button find = BhUi.button(ctx, "Find online", BhUi.PRIMARY);
            BhUi.onTap(find, BhTrainerPanel::programFindOnline);
            Button file = BhUi.button(ctx, "Use a file", BhUi.SECONDARY);
            BhUi.onTap(file, BhTrainerPanel::programPickFile);
            row.addView(find, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(file, BhUi.fill(ctx, 8));
            card.addView(row, BhUi.below(ctx, 10));
            programBox.addView(card);
            return;
        }

        // Status row: name, running state, Start/Stop, more
        LinearLayout head = BhUi.row(ctx);
        head.setPadding(dp(14), dp(8), dp(6), dp(8));
        head.setBackground(BhUi.rounded(ctx, BhUi.CARD_HI, 12));
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = BhUi.text(ctx, 14, BhUi.WHITE, true);
        name.setText(exe.getName().replaceAll("(?i)\\.exe$", ""));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        programStatus = BhUi.text(ctx, 12, BhUi.GREY, false);
        programStatus.setText("Checking…");
        texts.addView(programStatus);
        head.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        programStartStop = BhUi.button(ctx, "Start", BhUi.PRIMARY);
        BhUi.onTap(programStartStop, BhTrainerPanel::programStartOrStop);
        head.addView(programStartStop, BhUi.next(ctx, 8));
        Button more = BhUi.button(ctx, "⋮", BhUi.QUIET);
        more.setContentDescription("More trainer options");
        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        more.setPadding(0, 0, 0, 0);
        BhUi.onTap(more, () -> programMenu(more, exe, id));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(44), dp(44));
        ml.leftMargin = dp(2);
        head.addView(more, ml);
        programBox.addView(head);

        programMono = BhUi.button(ctx, "Install .NET support", BhUi.PRIMARY);
        programMono.setVisibility(View.GONE);
        BhUi.onTap(programMono, BhTrainerPanel::installMono);
        programBox.addView(programMono, BhUi.below(ctx, 8));

        // ── Cheat switches: each presses the trainer's keys in the game ──
        List<BhTrainerKeys.Option> opts = BhTrainerKeys.load(BhTrainerProgram.dir(id));
        if (opts.isEmpty()) {
            programBox.addView(note("No cheat list for this trainer. Download it with Find another (⋮) to read the "
                    + "list from its page, or use the Key pad (⋮) with the keys the trainer shows."), BhUi.below(ctx, 10));
        } else {
            for (BhTrainerKeys.Option o : opts) programBox.addView(cheatRow(o), BhUi.below(ctx, 6));
            programBox.addView(note("ON/OFF follows your taps (the trainer can't be asked): tap again if it's out of "
                    + "step."), BhUi.below(ctx, 6));
        }

        LinearLayout autoRow = BhUi.row(ctx);
        autoRow.setPadding(dp(4), 0, 0, 0);
        TextView autoLabel = BhUi.text(ctx, 14, BhUi.WHITE, false);
        autoLabel.setText("Start with the game");
        autoRow.addView(autoLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        BhUi.Toggle auto = new BhUi.Toggle(ctx, "Start with the game");
        auto.set(BhTrainerProgram.autostart(ctx, id), false);
        auto.onChange(on -> BhTrainerProgram.setAutostart(ctx, id, on));
        autoRow.addView(auto.view, auto.params());
        programBox.addView(autoRow, BhUi.below(ctx, 6));
        refreshProgram();
    }

    private static void programMenu(View anchor, java.io.File exe, String id) {
        PopupMenu m = new PopupMenu(ctx, anchor);
        m.getMenu().add(0, 1, 0, "Key pad");
        m.getMenu().add(0, 2, 1, "Show trainer window");
        m.getMenu().add(0, 3, 2, "Back to the game");
        m.getMenu().add(0, 4, 3, "Find another trainer");
        m.getMenu().add(0, 5, 4, "Remove trainer");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    keyPad();
                    break;
                case 2:
                    windowAction(true);
                    break;
                case 3:
                    windowAction(false);
                    break;
                case 4:
                    programFindOnline();
                    break;
                case 5:
                    new AlertDialog.Builder(ctx)
                            .setTitle("Remove this trainer?")
                            .setMessage(exe.getName())
                            .setPositiveButton("Remove", (d, w) -> io.execute(() -> {
                                BhTrainerProgram.remove(ctx.getApplicationContext(), id);
                                ui.post(() -> {
                                    programRefocus = true;
                                    showProgram();
                                });
                            }))
                            .setNegativeButton("Cancel", null)
                            .show();
                    break;
                default:
                    return false;
            }
            return true;
        });
        m.show();
    }

    /** Running or not (scans processes, so off the UI thread). */
    private static void refreshProgram() {
        if (programStatus == null || programChecking || programBusy || !programStatus.isShown()) return;
        programChecking = true;
        Context app = ctx.getApplicationContext();
        io.execute(() -> {
            int pid = BhTrainerProgram.runningPid(app);
            boolean starting = pid < 0 && BhTrainerProgram.starting();
            boolean mono = pid < 0 && !starting && BhTrainerProgram.needsMono();
            ui.post(() -> {
                programChecking = false;
                if (programStatus == null || programStartStop == null || programBusy) return;
                setProgramStatus(pid > 0 ? "Running · window hidden" : starting ? "Starting… (a few seconds)"
                        : mono ? "Needs .NET support (Wine Mono): install it once for this game, about 80 MB"
                        : "Not running", pid > 0 ? BhUi.GREEN : starting || mono ? BhUi.AMBER : BhUi.GREY);
                if (programMono != null) programMono.setVisibility(mono ? View.VISIBLE : View.GONE);
                boolean wasRunning = Boolean.TRUE.equals(programStartStop.getTag());
                programStartStop.setText(pid > 0 ? "Stop" : "Start");
                programStartStop.setTag(pid > 0);
                if (wasRunning != pid > 0) BhUi.style(programStartStop, pid > 0 ? BhUi.SECONDARY : BhUi.PRIMARY);
                programStartStop.setEnabled(!starting);
                programStartStop.setAlpha(starting ? 0.4f : 1f);
            });
        });
    }

    private static void setProgramStatus(String text, int color) {
        if (programStatus == null) return;
        programStatus.setText(text);
        programStatus.setTextColor(color);
    }

    private static void programStartOrStop() {
        if (programBusy || programStartStop == null) return;
        boolean running = Boolean.TRUE.equals(programStartStop.getTag());
        Context app = ctx.getApplicationContext();
        programBusy = true;
        programStartStop.setEnabled(false);
        programStartStop.setAlpha(0.4f);
        setProgramStatus(running ? "Stopping…" : "Starting… (a few seconds)", BhUi.AMBER);
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
                msg = (running ? "Couldn't stop: " : "Couldn't start: ")
                        + (t.getMessage() != null ? t.getMessage() : t.toString());
            }
            String m = msg;
            ui.post(() -> {
                programBusy = false;
                toast(m);
                refreshProgram(); // shows "Starting…" with the button disabled until the trainer appears
            });
        });
    }

    private static String installedMessage(String saved) {
        String id = BhPlayTime.currentGameId();
        return saved + (BhTrainerProgram.autostart(ctx, id)
                ? ". It starts with the game; tap Start to run it now."
                : ". Tap Start to run it.");
    }

    /** Cheat states as the app last set them, by game and keys (the trainer can't be asked). */
    private static final java.util.Map<String, Boolean> cheatOn = new java.util.HashMap<>();

    private static LinearLayout cheatRow(BhTrainerKeys.Option o) {
        String id = BhPlayTime.currentGameId();
        String key = id + "|" + o.keys;
        LinearLayout row = BhUi.row(ctx);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(14), dp(4), dp(6), dp(4));
        row.setBackground(BhUi.rounded(ctx, BhUi.CARD, 12));
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView label = BhUi.text(ctx, 14, BhUi.WHITE, true);
        label.setText(o.label);
        texts.addView(label);
        TextView keys = BhUi.text(ctx, 12, BhUi.GREY, false);
        keys.setText("Key " + o.keys);
        texts.addView(keys);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        BhUi.Toggle sw = new BhUi.Toggle(ctx, o.label);
        sw.set(Boolean.TRUE.equals(cheatOn.get(key)), false);
        sw.onChange(now -> {
            if (!BhTrainerKeys.press(ctx, o.codes)) {
                sw.set(!now, true); // nothing was pressed: put it back
                toast("Can't reach the game's input");
                return;
            }
            cheatOn.put(key, now);
        });
        row.addView(sw.view, sw.params());
        return row;
    }

    /** Key pad: the keys trainers use, with Ctrl / Alt / Shift latches. */
    private static void keyPad() {
        boolean[] mods = new boolean[3]; // ctrl, alt, shift
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        String[][] rows = {
                {"Ctrl", "Alt", "Shift", "NumLock"},
                {"Num 7", "Num 8", "Num 9", "Num -"},
                {"Num 4", "Num 5", "Num 6", "Num +"},
                {"Num 1", "Num 2", "Num 3", "Num ."},
                {"Num 0", "Num *", "Num /", "Home"},
                {"F1", "F2", "F3", "F4"},
                {"F5", "F6", "F7", "F8"},
                {"F9", "F10", "F11", "F12"},
        };
        for (String[] r : rows) {
            LinearLayout line = new LinearLayout(ctx);
            for (String k : r) {
                Button b = BhUi.button(ctx, k, BhUi.SECONDARY);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                b.setPadding(dp(4), 0, dp(4), 0);
                BhUi.onTap(b, () -> {
                    int m = k.equals("Ctrl") ? 0 : k.equals("Alt") ? 1 : k.equals("Shift") ? 2 : -1;
                    if (m >= 0) {
                        mods[m] = !mods[m];
                        BhUi.style(b, mods[m] ? BhUi.PRIMARY : BhUi.SECONDARY);
                        return;
                    }
                    StringBuilder combo = new StringBuilder();
                    if (mods[0]) combo.append("Ctrl+");
                    if (mods[1]) combo.append("Alt+");
                    if (mods[2]) combo.append("Shift+");
                    combo.append(k);
                    int[] codes = BhTrainerKeys.codes(combo.toString());
                    if (codes == null || !BhTrainerKeys.press(ctx, codes)) toast("Can't reach the game's input");
                    else toast("Pressed " + combo);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                line.addView(b, lp);
            }
            box.addView(line);
        }
        ScrollView sv = new ScrollView(ctx);
        sv.addView(box);
        new AlertDialog.Builder(ctx)
                .setTitle("Key pad: press a trainer key")
                .setView(sv)
                .setPositiveButton("Close", null)
                .show();
    }

    private static void windowAction(boolean show) {
        Context app = ctx.getApplicationContext();
        toast(show ? "Showing the trainer window…" : "Bringing the game back…");
        io.execute(() -> {
            String m = show ? BhTrainerProgram.showTrainer(app) : BhTrainerProgram.backToGame(app);
            ui.post(() -> toast(m));
        });
    }

    private static void installMono() {
        if (programBusy || programMono == null) return;
        programBusy = true;
        programMono.setEnabled(false);
        programMono.setAlpha(0.4f);
        if (programStartStop != null) {
            programStartStop.setEnabled(false);
            programStartStop.setAlpha(0.4f);
        }
        io.execute(() -> {
            String msg;
            try {
                msg = BhTrainerProgram.installMono(text -> ui.post(() -> setProgramStatus(text, BhUi.AMBER)));
            } catch (Throwable t) {
                msg = "Couldn't install .NET support: " + (t.getMessage() != null ? t.getMessage() : t.toString());
            }
            String m = msg;
            ui.post(() -> {
                programBusy = false;
                toast(m);
                showProgram();
            });
        });
    }

    private static void programFindOnline() {
        if (BhPlayTime.currentGameId() == null) {
            toast("No game running");
            return;
        }
        String gameId = BhPlayTime.currentGameId();
        BhCheatBrowser.show(ctx, BhTrainerProgram.searchUrl(BhPlayTime.currentGameName()),
                "Find a trainer for this game, then tap its download link",
                (c, data, name) -> {
                    String saved = BhTrainerProgram.install(c, data, name);
                    // The page the download came from lists the cheats and their keys.
                    int n = BhTrainerKeys.savePage(BhTrainerProgram.dir(gameId), BhCheatBrowser.lastPageText());
                    return saved + (n > 0 ? " (" + n + " cheats found)" : "");
                },
                msg -> {
                    toast(installedMessage(msg));
                    programRefocus = true;
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
                        msg = installedMessage(BhTrainerProgram.install(ctx.getApplicationContext(), files.get(which)));
                    } catch (Throwable t) {
                        msg = "Couldn't use it: " + (t.getMessage() != null ? t.getMessage() : t.toString());
                    }
                    String m = msg;
                    ui.post(() -> {
                        toast(m);
                        programRefocus = true;
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
            tableBox.addView(note("Loading…"));
            return;
        }
        if (BhPlayTime.currentGameId() == null) {
            tableBox.addView(note("No game running."));
            return;
        }
        String name = BhCheatTable.tableName();
        if (name == null) {
            tableBox.addView(note("No table for this game."));
            LinearLayout row = BhUi.row(ctx);
            Button find = BhUi.button(ctx, "Find online", BhUi.SECONDARY);
            BhUi.onTap(find, BhTrainerPanel::findOnline);
            Button imp = BhUi.button(ctx, "Import file", BhUi.SECONDARY);
            BhUi.onTap(imp, BhTrainerPanel::importDialog);
            row.addView(find, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(imp, BhUi.fill(ctx, 8));
            tableBox.addView(row, BhUi.below(ctx, 8));
            return;
        }
        LinearLayout head = BhUi.row(ctx);
        TextView tn = BhUi.text(ctx, 12, BhUi.GREY, false);
        tn.setText(name);
        tn.setSingleLine(true);
        tn.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        head.addView(tn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button other = BhUi.button(ctx, "Find online", BhUi.QUIET);
        BhUi.onTap(other, BhTrainerPanel::findOnline);
        Button remove = BhUi.button(ctx, "Remove", BhUi.QUIET);
        BhUi.onTap(remove, () -> new AlertDialog.Builder(ctx)
                .setTitle("Remove this table?")
                .setMessage(name)
                .setPositiveButton("Remove", (d, w) -> {
                    BhCheatTable.removeTable(ctx.getApplicationContext());
                    showTable();
                })
                .setNegativeButton("Cancel", null)
                .show());
        head.addView(other, BhUi.next(ctx, 4));
        head.addView(remove, BhUi.next(ctx, 4));
        tableBox.addView(head);
        int usable = 0;
        for (BhCheatTable.Entry e : BhCheatTable.entries()) {
            if (e.header) {
                TextView h = BhUi.text(ctx, 12, BhUi.WHITE, true);
                h.setText(e.description);
                h.setPadding(dp(4 + 12 * e.depth), dp(10), 0, dp(2));
                tableBox.addView(h);
                continue;
            }
            LinearLayout row = listRow(e.description, e.frozen ? "Frozen" : null, e.usable() ? "…" : "—");
            row.setPadding(dp(14 + 12 * e.depth), dp(6), dp(14), dp(6));
            row.setTag(e);
            if (!e.usable()) row.setAlpha(0.45f);
            else usable++;
            BhUi.onTap(row, () -> tableEntryDialog(e));
            tableBox.addView(row, BhUi.below(ctx, 4));
        }
        if (usable == 0) {
            tableBox.addView(note("None of this table's cheats can be used yet (they are scripts). "
                    + "Try another table for this game."), BhUi.below(ctx, 8));
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

    // ── Search ───────────────────────────────────────────────────────────────

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
            status.setText("Type a number you see in the game (e.g. your XP) and tap New search. "
                    + "Health bar with no number: use the guided finder below.");
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
            status.setText(count + " results. Change the value in the game, type the new one and tap Next "
                    + "(or use Decreased / Increased).");
            return;
        }
        if (n <= LOCK_ALL_MAX) lockAll.setVisibility(View.VISIBLE);
        status.setText(count + (n == 1 ? " result" : " results") + ". Edit one, or Lock it to keep its value.");
        BhTrainer.Type t = BhTrainer.type();
        for (int i = 0; i < (int) n; i++) {
            long addr = BhTrainer.result(i);
            resultsBox.addView(resultRow(addr, t), BhUi.below(ctx, 4));
        }
    }

    private static LinearLayout resultRow(long addr, BhTrainer.Type t) {
        LinearLayout row = BhUi.row(ctx);
        row.setMinimumHeight(dp(52));
        row.setPadding(dp(14), dp(4), dp(6), dp(4));
        row.setBackground(BhUi.rounded(ctx, BhUi.CARD, 12));
        row.setTag(new Object[]{addr, t});
        TextView a = BhUi.text(ctx, 13, BhUi.GREY, false);
        a.setTypeface(android.graphics.Typeface.MONOSPACE);
        a.setText(String.format(Locale.US, "0x%08X", addr));
        row.addView(a, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = BhUi.text(ctx, 15, BhUi.WHITE, true);
        v.setTag("value");
        v.setText(BhTrainer.format(BhTrainer.read(addr, t), t));
        row.addView(v, BhUi.next(ctx, 8));
        Button edit = BhUi.button(ctx, "Edit", BhUi.SECONDARY);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        BhUi.onTap(edit, () -> askNumber("Set value", BhTrainer.format(BhTrainer.read(addr, t), t), val -> {
            if (BhTrainer.write(addr, t, val)) toast("Set to " + BhTrainer.format(val, t));
            else toast("Could not write the value");
            refreshValues();
        }));
        row.addView(edit, BhUi.next(ctx, 10));
        Button lock = BhUi.button(ctx, "Lock", BhUi.PRIMARY);
        lock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        BhUi.onTap(lock, () -> lockOne(addr, t));
        row.addView(lock, BhUi.next(ctx, 6));
        return row;
    }

    // ── Saved ────────────────────────────────────────────────────────────────

    private static void showSaved() {
        if (savedBox == null) return;
        savedBox.removeAllViews();
        List<BhTrainer.Entry> list = BhTrainer.entries();
        if (tabs != null) tabs.items[SAVED].setText(list.isEmpty() ? "Saved" : "Saved · " + list.size());
        if (list.isEmpty()) {
            savedBox.addView(note("Nothing saved for this game yet. Lock a search result, or use the Health "
                    + "finder's Lock all."));
            return;
        }
        for (BhTrainer.Entry e : list) {
            LinearLayout row = listRow(e.name, String.format(Locale.US, "0x%08X · %s", e.address, e.type.label),
                    BhTrainer.format(BhTrainer.read(e.address, e.type), e.type));
            row.setTag(e);
            BhUi.onTap(row, () -> entryDialog(e));
            BhUi.Toggle freeze = new BhUi.Toggle(ctx, "Freeze " + e.name);
            freeze.set(e.frozen, false);
            freeze.onChange(on -> {
                if (on) {
                    Double now = BhTrainer.read(e.address, e.type);
                    if (now == null) {
                        freeze.set(false, true);
                        toast("Can't read this address; search again");
                        return;
                    }
                    BhTrainer.setFrozen(e, true, now);
                } else {
                    BhTrainer.setFrozen(e, false, 0);
                }
            });
            row.addView(freeze.view, freeze.params());
            savedBox.addView(row, BhUi.below(ctx, 6));
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
                .setNeutralButton("Rename", (d, w) -> askText("Name this value", "e.g. XP, Money, Ammo", name -> {
                    boolean frozen = e.frozen;
                    double at = e.frozenValue;
                    BhTrainer.setFrozen(e, false, 0);
                    BhTrainer.removeEntry(ctx.getApplicationContext(), e);
                    BhTrainer.Entry n = BhTrainer.addEntry(ctx.getApplicationContext(), name, e.address, e.type);
                    if (frozen) BhTrainer.setFrozen(n, true, at);
                    showSaved();
                }))
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
        if (box == null || !box.isShown()) return;
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
        box.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    // ── View helpers ─────────────────────────────────────────────────────────

    /** A tappable row: name (and a detail line) on the left, the live value on the right. */
    private static LinearLayout listRow(String name, String detail, String value) {
        LinearLayout row = BhUi.row(ctx);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(14), dp(4), dp(6), dp(4));
        row.setBackground(BhUi.states(ctx, BhUi.CARD, 0, 12));
        row.setFocusable(true);
        row.setClickable(true);
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView l = BhUi.text(ctx, 14, BhUi.WHITE, true);
        l.setText(name);
        texts.addView(l);
        if (detail != null) {
            TextView d = BhUi.text(ctx, 12, BhUi.GREY, false);
            d.setText(detail);
            texts.addView(d);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView r = BhUi.text(ctx, 15, BhUi.WHITE, true);
        r.setTag("value");
        r.setText(value);
        r.setGravity(Gravity.END);
        row.addView(r, BhUi.next(ctx, 8));
        return row;
    }

    private static TextView sectionTitle(String s) {
        TextView t = BhUi.text(ctx, 14, BhUi.WHITE, true);
        t.setText(s);
        return t;
    }

    private static TextView note(String s) {
        TextView t = BhUi.text(ctx, 12, BhUi.GREY, false);
        t.setText(s);
        t.setLineSpacing(0, 1.1f);
        return t;
    }

    private static View firstFocusable(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.isFocusable() && c.isEnabled() && (c instanceof Button || c instanceof FrameLayout)) return c;
            if (c instanceof ViewGroup) {
                View f = firstFocusable((ViewGroup) c);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static Double parse(String s) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (Exception e) {
            return null;
        }
    }

    private static void toast(String s) {
        BhUi.snack(host, s, null, null);
    }

    private static int dp(int v) {
        return BhUi.dp(ctx, v);
    }
}
