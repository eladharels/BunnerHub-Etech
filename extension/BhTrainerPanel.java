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

    private static Context ctx;
    private static EditText valueInput;
    private static Button typeButton, nextButton;
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

        // ── Search card ──
        LinearLayout search = card();
        root.addView(search);

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
        typeButton = button(BhTrainer.type().label, false);
        typeButton.setOnClickListener(v -> cycleType());
        inputRow.addView(typeButton, wrapLeft(8));
        search.addView(inputRow);

        LinearLayout buttons = new LinearLayout(ctx);
        Button first = button("New search", true);
        first.setOnClickListener(v -> runScan(false));
        nextButton = button("Next search", true);
        nextButton.setOnClickListener(v -> runScan(true));
        Button reset = button("Reset", false);
        reset.setOnClickListener(v -> {
            BhTrainer.clearResults();
            showResults();
        });
        buttons.addView(first, new LinearLayout.LayoutParams(0, -2, 1f));
        buttons.addView(nextButton, weightMargins(1f, 8));
        buttons.addView(reset, wrapLeft(8));
        search.addView(buttons, margins(0, 10, 0, 0));

        status = text(12, GREY, false);
        search.addView(status, margins(0, 10, 0, 0));
        resultsBox = new LinearLayout(ctx);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        search.addView(resultsBox, margins(0, 6, 0, 0));

        // ── Saved values ──
        TextView savedTitle = text(14, WHITE, true);
        savedTitle.setText("Saved values");
        root.addView(savedTitle, margins(0, 18, 0, 6));
        savedBox = new LinearLayout(ctx);
        savedBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(savedBox);

        TextView help = text(11, GREY, false);
        help.setText("How to: type the value you see in the game (e.g. your XP) and tap New search. "
                + "Change it in the game (earn or spend some), type the new value and tap Next search. "
                + "Repeat until only a few results are left, then tap one to set it or save it.\n"
                + "Saved addresses can change after the game restarts; search again if a value stops working.\n"
                + "Single-player games only.");
        root.addView(help, margins(0, 18, 0, 0));

        updateGameLine();
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
        BhTrainer.setType(next);
        typeButton.setText(next.label);
        showResults();
    }

    private static void runScan(boolean next) {
        if (busy) return;
        Double v = parse(valueInput.getText().toString());
        if (v == null) {
            toast("Type a number first");
            return;
        }
        if (next && !BhTrainer.scanned()) {
            toast("Do a New search first");
            return;
        }
        busy = true;
        status.setText(next ? "Searching the previous results…" : "Searching the game's memory…");
        new Thread(() -> {
            String error = null;
            try {
                BhTrainer.Progress p = pct -> ui.post(() -> status.setText("Searching… " + pct + "%"));
                if (next) BhTrainer.nextScan(v, p);
                else BhTrainer.firstScan(v, p);
            } catch (Throwable t) {
                error = t.getMessage() != null ? t.getMessage() : t.toString();
            }
            String err = error;
            ui.post(() -> {
                busy = false;
                if (err != null) status.setText("Search failed: " + err);
                else showResults();
                updateGameLine();
            });
        }, "BhTrainerScan").start();
    }

    private static void showResults() {
        if (resultsBox == null) return;
        resultsBox.removeAllViews();
        int n = BhTrainer.resultCount();
        nextButton.setEnabled(BhTrainer.scanned() && n > 0);
        nextButton.setAlpha(nextButton.isEnabled() ? 1f : 0.4f);
        if (!BhTrainer.scanned()) {
            status.setText("Type a value you can see in the game and tap New search.");
            return;
        }
        if (n == 0) {
            status.setText("No results. Check the value and the type (" + BhTrainer.type().label + "), or Reset.");
            return;
        }
        String count = String.format(Locale.US, "%,d", n) + (BhTrainer.truncated() ? "+" : "");
        if (n > SHOWN_RESULTS) {
            status.setText(count + " results. Change the value in the game, type the new value and tap Next search.");
            return;
        }
        status.setText(count + (n == 1 ? " result" : " results") + ". Tap one to set or save it.");
        BhTrainer.Type t = BhTrainer.type();
        for (int i = 0; i < n; i++) {
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
