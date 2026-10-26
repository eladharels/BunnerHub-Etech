package app.revanced.extension.gamehub;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.List;

/**
 * BhPlayTimeActivity — "Play Time" screen from the launcher's left menu
 * (HomeLeftMenuDialog item id 15). Lists every game BhPlayTime has recorded,
 * most played first.
 *
 * The UI is patches/res/layout/bh_activity_play_time.xml and
 * bh_item_play_time.xml, built from stock GameHub views (launcher
 * background, CommonTopBarView, FocusableConstraintLayout rows, settings text
 * styles) so it looks like the native settings screens. Native screens hide
 * the system bars in their FocusableActivity base; this plain Activity does
 * the same in {@link #hideSystemBars}.
 */
public class BhPlayTimeActivity extends Activity {

    private ViewGroup list;
    private TextView summary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hideSystemBars();
        setContentView(res("bh_activity_play_time", "layout"));

        list = findViewById(res("bh_pt_list", "id"));
        summary = findViewById(res("bh_pt_summary", "id"));

        // Top bar back arrow (stock CommonTopBarView child) and the B hint.
        View back = findViewById(res("fl_open_nav", "id"));
        if (back != null) back.setOnClickListener(v -> finish());
        View backHint = findViewById(res("bh_pt_back_hint", "id"));
        if (backHint != null) backHint.setOnClickListener(v -> finish());
        View backKey = findViewById(res("bh_pt_back_key", "id"));
        if (backKey != null) {
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(0xFFFFFFFF);
            backKey.setBackground(circle);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void refresh() {
        list.removeAllViews();
        List<BhPlayTime.Entry> entries = BhPlayTime.all(this);

        long total = 0;
        for (BhPlayTime.Entry e : entries) total += e.totalMs;
        summary.setText(entries.isEmpty()
                ? "No play time recorded yet. Start a game and it will show up here."
                : entries.size() + (entries.size() == 1 ? " game" : " games")
                        + "  ·  " + BhPlayTime.formatDuration(total) + " in total");

        LayoutInflater inflater = LayoutInflater.from(this);
        int rowLayout = res("bh_item_play_time", "layout");
        int nameId = res("bh_pt_name", "id");
        int lastId = res("bh_pt_last", "id");
        int timeId = res("bh_pt_time", "id");
        for (BhPlayTime.Entry e : entries) {
            View row = inflater.inflate(rowLayout, list, false);
            ((TextView) row.findViewById(nameId)).setText(
                    e.name != null && !e.name.isEmpty() ? e.name : e.id);
            ((TextView) row.findViewById(lastId)).setText(
                    "Last played " + BhPlayTime.formatDay(e.lastPlayed));
            ((TextView) row.findViewById(timeId)).setText(BhPlayTime.formatDuration(e.totalMs));
            list.addView(row);
        }
        if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
    }

    private void hideSystemBars() {
        getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /**
     * CI renames the package only in the manifest, so the resource table can
     * keep the original package name: fall back to the table's own name.
     */
    private int res(String name, String type) {
        int id = getResources().getIdentifier(name, type, getPackageName());
        if (id == 0) {
            try {
                String resPkg = getResources().getResourcePackageName(getApplicationInfo().icon);
                id = getResources().getIdentifier(name, type, resPkg);
            } catch (Exception ignored) {
            }
        }
        return id;
    }
}
