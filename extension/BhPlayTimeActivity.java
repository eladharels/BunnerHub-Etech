package app.revanced.extension.gamehub;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * BhPlayTimeActivity — "Play Time" screen from the launcher's left menu
 * (HomeLeftMenuDialog item id 15). Lists every game BhPlayTime has recorded,
 * most played first, with total time and last-played day.
 */
public class BhPlayTimeActivity extends Activity {

    private static final int BG      = 0xFF0D0D0D;
    private static final int SURFACE = 0xFF1A1A1A;
    private static final int ACCENT  = 0xFF6C63FF;
    private static final int WHITE   = 0xFFFFFFFF;
    private static final int GREY    = 0xFFAAAAAA;
    private static final int DIVIDER = 0xFF2A2A2A;
    private static final int GOLD    = 0xFFFFD700;

    private TextView subtitle;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(buildHeader());

        View headerDivider = new View(this);
        headerDivider.setBackgroundColor(0xFF3A3A5C);
        wrapper.addView(headerDivider, new LinearLayout.LayoutParams(-1, dp(2)));

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(4), 0, dp(4));
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        wrapper.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        root.addView(wrapper, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private LinearLayout buildHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setBackgroundColor(SURFACE);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(12), dp(10), dp(12), dp(10));

        GradientDrawable backBg = new GradientDrawable();
        backBg.setColor(0x00000000);
        backBg.setCornerRadius(dp(6));
        Button back = new Button(this);
        back.setText("←");
        back.setTextColor(WHITE);
        back.setBackground(backBg);
        back.setTextSize(18f);
        back.setPadding(dp(6), dp(4), dp(10), dp(4));
        back.setFocusable(true);
        back.setOnFocusChangeListener((v, f) -> {
            backBg.setColor(f ? 0xFF2A2A4E : 0x00000000);
            backBg.setStroke(f ? dp(2) : 0, f ? GOLD : 0x00000000);
        });
        back.setOnClickListener(v -> finish());
        h.addView(back);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLp.leftMargin = dp(4);

        TextView title = new TextView(this);
        title.setText("Play Time");
        title.setTextColor(WHITE);
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        titleCol.addView(title);

        subtitle = new TextView(this);
        subtitle.setTextColor(GREY);
        subtitle.setTextSize(11f);
        titleCol.addView(subtitle);

        h.addView(titleCol, titleLp);
        return h;
    }

    private void refresh() {
        list.removeAllViews();
        List<BhPlayTime.Entry> entries = BhPlayTime.all(this);

        long total = 0;
        for (BhPlayTime.Entry e : entries) total += e.totalMs;
        subtitle.setText(entries.isEmpty()
                ? "Time is counted while a game is on screen"
                : entries.size() + (entries.size() == 1 ? " game" : " games")
                        + "  ·  " + BhPlayTime.formatDuration(total) + " in total");

        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No play time recorded yet.\nStart a game and it will show up here.");
            empty.setTextColor(GREY);
            empty.setTextSize(14f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(48), dp(24), dp(48));
            list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }

        long max = entries.get(0).totalMs;
        for (BhPlayTime.Entry e : entries) {
            list.addView(buildRow(e, max));
            View div = new View(this);
            div.setBackgroundColor(DIVIDER);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(1));
            dlp.leftMargin = dp(16);
            dlp.rightMargin = dp(16);
            list.addView(div, dlp);
        }
    }

    private View buildRow(BhPlayTime.Entry e, long max) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        // Focusable so a controller can scroll through the list.
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x00000000);
        bg.setCornerRadius(dp(6));
        row.setBackground(bg);
        row.setFocusable(true);
        row.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? 0xFF2A2A4E : 0x00000000);
            bg.setStroke(f ? dp(2) : 0, f ? GOLD : 0x00000000);
        });

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(e.name != null && !e.name.isEmpty() ? e.name : e.id);
        name.setTextColor(WHITE);
        name.setTextSize(15f);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView time = new TextView(this);
        time.setText(BhPlayTime.formatDuration(e.totalMs));
        time.setTextColor(ACCENT);
        time.setTextSize(15f);
        time.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = dp(12);
        top.addView(time, tlp);
        row.addView(top);

        // Bar relative to the most played game.
        FrameLayout track = new FrameLayout(this);
        track.setBackgroundColor(DIVIDER);
        View fill = new View(this);
        fill.setBackgroundColor(ACCENT);
        float share = max > 0 ? (float) e.totalMs / max : 0f;
        LinearLayout bar = new LinearLayout(this);
        bar.addView(fill, new LinearLayout.LayoutParams(0, -1, share));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1f - share));
        track.addView(bar, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(3));
        blp.topMargin = dp(6);
        row.addView(track, blp);

        TextView last = new TextView(this);
        last.setText("Last played " + BhPlayTime.formatDay(e.lastPlayed));
        last.setTextColor(GREY);
        last.setTextSize(11f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = dp(4);
        row.addView(last, llp);
        return row;
    }

    private int dp(int v) {
        return BhPlayTime.dp(this, v);
    }
}
