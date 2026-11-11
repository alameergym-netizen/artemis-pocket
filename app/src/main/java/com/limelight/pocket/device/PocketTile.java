package com.limelight.pocket.device;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.limelight.PcView;

/**
 * A Quick Settings tile: pull down the shade from any app and jump straight to Pocket Nexus Home.
 */
@TargetApi(Build.VERSION_CODES.N)
public class PocketTile extends TileService {

    @Override
    public void onStartListening() {
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.updateTile();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onClick() {
        Intent intent = new Intent(this, PcView.class)
                .putExtra(PcView.EXTRA_SHOW_HOME, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            startActivityAndCollapse(pi);
        }
        else {
            startActivityAndCollapse(intent);
        }
    }
}
