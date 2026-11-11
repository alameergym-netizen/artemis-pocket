package com.limelight.pocket.home;

import android.widget.Toast;

import com.limelight.R;
import com.limelight.pocket.ConsoleActivity;
import com.limelight.pocket.PocketSounds;
import com.limelight.pocket.ui.StreamMenuView;

/** "Add to Home" / "Remove from Home" for any screen's options. */
public final class HomePins {
    private HomePins() {}

    public static StreamMenuView.Item item(final ConsoleActivity a, final HomeItem item) {
        final boolean pinned = HomeStore.isPinned(a, item);
        return StreamMenuView.Item.action(a.getString(pinned ? R.string.pocket_home_unpin : R.string.pocket_home_pin), () -> {
            if (pinned) {
                HomeStore.unpin(a, item);
                PocketSounds.play(a, PocketSounds.TOGGLE_OFF);
                Toast.makeText(a, a.getString(R.string.pocket_home_unpinned_toast, item.name), Toast.LENGTH_SHORT).show();
            }
            else {
                HomeStore.pin(a, item);
                PocketSounds.play(a, PocketSounds.TOGGLE_ON);
                Toast.makeText(a, a.getString(R.string.pocket_home_pinned_toast, item.name), Toast.LENGTH_SHORT).show();
            }
            a.hideConsolePanel();
        });
    }
}
