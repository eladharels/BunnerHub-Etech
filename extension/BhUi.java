package app.revanced.extension.gamehub;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * BhUi — the shared look of BunnerHub's own screens (FSR tab, Trainer tab,
 * Saves dialog): colors, buttons with pressed / controller-focus states and a
 * haptic tick, segmented selectors, switches, status pills, banners and a
 * snackbar that replaces Android toasts. Everything is built in code (the
 * screens have no layouts of their own).
 */
final class BhUi {

    static final int WHITE = 0xFFFAFAFA;
    static final int GREY = 0xFFA3A7B8;    // secondary text (passes 4.5:1 on the sidebar)
    static final int SOFT = 0xFFC2C5D3;    // unselected choices
    static final int GREEN = 0xFF4CD08A;
    static final int AMBER = 0xFFFFB74D;
    static final int ACCENT = 0xFF5B52F0;  // filled buttons, selected choices (white text 5:1)
    static final int ACCENT_SOFT = 0xFF2A2F66;
    static final int CARD = 0x14FFFFFF;
    static final int CARD_HI = 0x24FFFFFF;
    static final int WELL = 0x40000000;    // background of segmented selectors and inputs
    static final int LINE = 0x2EFFFFFF;

    static final int PRIMARY = 0, SECONDARY = 1, WARN = 2, QUIET = 3;

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private BhUi() {}

    // ── Basics ───────────────────────────────────────────────────────────────

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable outlined(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable g = rounded(c, fill, radiusDp);
        g.setStroke(dp(c, 1), stroke);
        return g;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        l.setBackground(rounded(c, CARD, 12));
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** Full width, wrap height, with a top margin. */
    static LinearLayout.LayoutParams below(Context c, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topDp);
        return lp;
    }

    /** Wrap size, with a left margin (next item in a row). */
    static LinearLayout.LayoutParams next(Context c, int leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(c, leftDp);
        return lp;
    }

    /** Takes the rest of a row. */
    static LinearLayout.LayoutParams fill(Context c, int leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(c, leftDp);
        return lp;
    }

    // ── Buttons ──────────────────────────────────────────────────────────────

    /** Background with a pressed shade and a white ring when the controller focuses it. */
    static Drawable states(Context c, int fill, int stroke, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable pressed = stroke != 0 ? outlined(c, blend(fill), stroke, radiusDp) : rounded(c, blend(fill), radiusDp);
        GradientDrawable focused = rounded(c, fill, radiusDp);
        focused.setStroke(dp(c, 2), 0xE6FFFFFF);
        GradientDrawable normal = stroke != 0 ? outlined(c, fill, stroke, radiusDp) : rounded(c, fill, radiusDp);
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, normal);
        return s;
    }

    /** A lighter shade for the pressed state (works for opaque and see-through fills). */
    private static int blend(int color) {
        int a = color >>> 24;
        if (a < 0xFF) return ((Math.min(0xFF, a + 0x24)) << 24) | (color & 0xFFFFFF);
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        r += (255 - r) / 6;
        g += (255 - g) / 6;
        b += (255 - b) / 6;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static Button button(Context c, String label, int style) {
        Button b = new Button(c);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinHeight(0);
        b.setMinimumHeight(dp(c, 44));
        b.setMinWidth(0);
        b.setMinimumWidth(dp(c, 44));
        b.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        b.setStateListAnimator(null);
        style(b, style);
        return b;
    }

    static void style(Button b, int style) {
        Context c = b.getContext();
        switch (style) {
            case PRIMARY:
                b.setTextColor(0xFFFFFFFF);
                b.setBackground(states(c, ACCENT, 0, 12));
                break;
            case WARN:
                b.setTextColor(0xFF2A1E05);
                b.setBackground(states(c, AMBER, 0, 10));
                break;
            case QUIET:
                b.setTextColor(SOFT);
                b.setBackground(states(c, 0x00FFFFFF, 0, 10));
                break;
            default:
                b.setTextColor(WHITE);
                b.setBackground(states(c, CARD_HI, LINE, 12));
        }
    }

    /** A tap with a haptic tick. */
    static void onTap(View v, Runnable action) {
        v.setOnClickListener(x -> {
            x.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            action.run();
        });
    }

    /** Dim and block a control (and everything in it). */
    static void enable(View v, boolean on) {
        v.setAlpha(on ? 1f : 0.4f);
        setEnabledDeep(v, on);
    }

    private static void setEnabledDeep(View v, boolean on) {
        v.setEnabled(on);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) setEnabledDeep(g.getChildAt(i), on);
        }
    }

    // ── Segmented selector ───────────────────────────────────────────────────

    interface Choice {
        void picked(int index);
    }

    /** All choices side by side; the selected one is filled. */
    static final class Segmented {
        final LinearLayout view;
        final Button[] items;
        private int selected = -1;

        Segmented(Context c, String[] labels, Choice onPick) {
            view = row(c);
            view.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
            view.setBackground(rounded(c, WELL, 12));
            items = new Button[labels.length];
            for (int i = 0; i < labels.length; i++) {
                Button b = new Button(c);
                b.setAllCaps(false);
                b.setText(labels[i]);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                b.setMinHeight(0);
                b.setMinimumHeight(dp(c, 40));
                b.setMinWidth(0);
                b.setMinimumWidth(dp(c, 52));
                b.setPadding(dp(c, 12), 0, dp(c, 12), 0);
                b.setStateListAnimator(null);
                final int idx = i;
                onTap(b, () -> {
                    if (idx == selected) return;
                    select(idx);
                    onPick.picked(idx);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(c, 40));
                if (i > 0) lp.leftMargin = dp(c, 4);
                view.addView(b, lp);
                items[i] = b;
            }
            select(0);
        }

        void select(int idx) {
            if (idx == selected) return;
            selected = idx;
            for (int i = 0; i < items.length; i++) {
                Button b = items[i];
                boolean on = i == idx;
                b.setTextColor(on ? 0xFFFFFFFF : SOFT);
                b.setBackground(states(b.getContext(), on ? ACCENT : 0x00FFFFFF, 0, 9));
                b.setSelected(on);
            }
        }

        int selected() {
            return selected;
        }
    }

    // ── Switch ───────────────────────────────────────────────────────────────

    interface Flip {
        void changed(boolean on);
    }

    /** An ON/OFF switch with a sliding knob (44×26 track in a 56×44 touch area). */
    static final class Toggle {
        final FrameLayout view;
        private final View track, knob;
        private final String label;
        private boolean on;
        private Flip listener;

        Toggle(Context c, String label) {
            this.label = label;
            view = new FrameLayout(c);
            view.setFocusable(true);
            view.setClickable(true);
            view.setBackground(states(c, 0x00FFFFFF, 0, 10));
            track = new View(c);
            track.setBackground(rounded(c, 0xFF3A4063, 13));
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(dp(c, 44), dp(c, 26), Gravity.CENTER);
            view.addView(track, tl);
            knob = new View(c);
            GradientDrawable k = new GradientDrawable();
            k.setShape(GradientDrawable.OVAL);
            k.setColor(0xFFFFFFFF);
            knob.setBackground(k);
            FrameLayout.LayoutParams kl = new FrameLayout.LayoutParams(dp(c, 20), dp(c, 20), Gravity.CENTER);
            view.addView(knob, kl);
            onTap(view, () -> {
                set(!on, true);
                if (listener != null) listener.changed(on);
            });
            set(false, false);
        }

        LinearLayout.LayoutParams params() {
            return new LinearLayout.LayoutParams(dp(view.getContext(), 56), dp(view.getContext(), 44));
        }

        void onChange(Flip f) {
            listener = f;
        }

        boolean isOn() {
            return on;
        }

        void set(boolean value, boolean animate) {
            on = value;
            float x = dp(view.getContext(), value ? 9 : -9);
            int from = ((GradientDrawable) track.getBackground()).getColor() != null
                    ? ((GradientDrawable) track.getBackground()).getColor().getDefaultColor() : 0xFF3A4063;
            int to = value ? GREEN : 0xFF3A4063;
            if (animate) {
                knob.animate().translationX(x).setDuration(150).start();
                ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
                a.setDuration(150);
                a.addUpdateListener(an -> ((GradientDrawable) track.getBackground()).setColor((Integer) an.getAnimatedValue()));
                a.start();
            } else {
                knob.setTranslationX(x);
                ((GradientDrawable) track.getBackground()).setColor(to);
            }
            view.setContentDescription(label + (value ? ", on" : ", off"));
        }
    }

    // ── Pills, banners ───────────────────────────────────────────────────────

    /** A rounded status badge with a dot in its color. */
    static TextView pill(Context c) {
        TextView t = text(c, 13, WHITE, true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(c, 10), dp(c, 5), dp(c, 12), dp(c, 5));
        t.setCompoundDrawablePadding(dp(c, 6));
        return t;
    }

    static void setPill(TextView t, String label, int fg, int bg) {
        Context c = t.getContext();
        t.setText(label);
        t.setTextColor(fg);
        t.setBackground(rounded(c, bg, 999));
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(fg);
        dot.setSize(dp(c, 8), dp(c, 8));
        t.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null);
    }

    /** A small boxed value ("48 FPS"). */
    static TextView chip(Context c) {
        TextView t = text(c, 12, 0xFFD5D7E2, false);
        t.setPadding(dp(c, 9), dp(c, 4), dp(c, 9), dp(c, 4));
        t.setBackground(rounded(c, CARD_HI, 8));
        return t;
    }

    /** An amber notice row: message and an optional action button. */
    static final class Banner {
        final LinearLayout view;
        final TextView message;
        final Button action;

        Banner(Context c, String actionLabel) {
            view = row(c);
            view.setPadding(dp(c, 12), dp(c, 6), dp(c, 6), dp(c, 6));
            view.setMinimumHeight(dp(c, 48));
            view.setBackground(outlined(c, 0x29FFB74D, 0x66FFB74D, 12));
            message = text(c, 13, 0xFFFFD9A0, false);
            view.addView(message, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (actionLabel != null) {
                action = button(c, actionLabel, WARN);
                action.setMinimumHeight(dp(c, 36));
                action.setPadding(dp(c, 12), 0, dp(c, 12), 0);
                view.addView(action, next(c, 8));
            } else {
                action = null;
            }
        }
    }

    /** A tappable row: title, optional subtitle, and a chevron. */
    static LinearLayout linkRow(Context c, String title, String subtitle, Runnable onTap) {
        LinearLayout r = row(c);
        r.setFocusable(true);
        r.setClickable(true);
        r.setMinimumHeight(dp(c, 48));
        r.setPadding(dp(c, 14), dp(c, 6), dp(c, 12), dp(c, 6));
        r.setBackground(states(c, CARD_HI, 0, 12));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(c, 14, WHITE, true);
        t.setText(title);
        texts.addView(t);
        if (subtitle != null) {
            TextView s = text(c, 12, GREY, false);
            s.setText(subtitle);
            texts.addView(s);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView chev = text(c, 20, GREY, false);
        chev.setText("›");
        r.addView(chev, next(c, 8));
        onTap(r, onTap);
        return r;
    }

    // ── Snackbar ─────────────────────────────────────────────────────────────

    /**
     * A short message at the bottom of {@code host} (replaces Android toasts):
     * fades in, stays 3 s (5 s with an action), fades out. A new one replaces
     * the one showing.
     */
    static void snack(FrameLayout host, String message, String actionLabel, Runnable action) {
        if (host == null) return;
        UI.post(() -> {
            Context c = host.getContext();
            Object old = host.getTag(TAG_SNACK);
            if (old instanceof View) host.removeView((View) old);
            LinearLayout bar = row(c);
            bar.setPadding(dp(c, 14), dp(c, 4), dp(c, 6), dp(c, 4));
            bar.setMinimumHeight(dp(c, 44));
            bar.setBackground(rounded(c, 0xFFE8E9F2, 10));
            bar.setElevation(dp(c, 6));
            TextView t = text(c, 13, 0xFF121834, false);
            t.setText(message);
            bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (actionLabel != null && action != null) {
                Button b = button(c, actionLabel, QUIET);
                b.setTextColor(0xFF3F37C9);
                b.setMinimumHeight(dp(c, 36));
                onTap(b, () -> {
                    host.removeView(bar);
                    action.run();
                });
                bar.addView(b, next(c, 4));
            }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            lp.setMargins(dp(c, 12), 0, dp(c, 12), dp(c, 12));
            bar.setAlpha(0f);
            bar.setTranslationY(dp(c, 12));
            host.addView(bar, lp);
            host.setTag(TAG_SNACK, bar);
            bar.animate().alpha(1f).translationY(0).setDuration(160).start();
            host.postDelayed(() -> bar.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                host.removeView(bar);
                if (host.getTag(TAG_SNACK) == bar) host.setTag(TAG_SNACK, null);
            }).start(), actionLabel != null ? 5000 : 3000);
        });
    }

    /** Any id that's ours (tags need an app-resource-style id; this is outside aapt's range). */
    private static final int TAG_SNACK = 0x7EB40001;

    /** Wraps a screen so snackbars can float over it. */
    static FrameLayout host(Context c, View content) {
        FrameLayout f = new FrameLayout(c);
        f.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return f;
    }
}
