package app.revanced.extension.gamehub;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import java.util.List;

/**
 * BhPlayTimeActivity — "Play Time" screen from the launcher's left menu
 * (HomeLeftMenuDialog item id 15). Lists every game BhPlayTime has recorded,
 * most played first. Styled by {@link BhNativeScreen}.
 */
public class BhPlayTimeActivity extends Activity {

    private BhNativeScreen screen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        screen = new BhNativeScreen(this, "Play Time");
    }

    @Override
    protected void onResume() {
        super.onResume();
        screen.onResume();
        try {
            refresh();
        } catch (Throwable t) {
            Log.e("BhPlayTime", "refresh failed", t);
            screen.summary.setText("Could not load play time: " + t);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        screen.onWindowFocusChanged(hasFocus);
    }

    private void refresh() {
        screen.list.removeAllViews();
        List<BhPlayTime.Entry> entries = BhPlayTime.all(this);

        long total = 0;
        for (BhPlayTime.Entry e : entries) total += e.totalMs;
        screen.summary.setText(entries.isEmpty()
                ? "No play time recorded yet. Start a game and it will show up here."
                : entries.size() + (entries.size() == 1 ? " game" : " games")
                        + "  ·  " + BhPlayTime.formatDuration(total) + " in total");

        for (BhPlayTime.Entry e : entries) {
            screen.list.addView(screen.row(
                    e.name != null && !e.name.isEmpty() ? e.name : e.id,
                    "Last played " + BhPlayTime.formatDay(e.lastPlayed),
                    BhPlayTime.formatDuration(e.totalMs)));
        }
        if (screen.list.getChildCount() > 0) screen.list.getChildAt(0).requestFocus();
    }
}
