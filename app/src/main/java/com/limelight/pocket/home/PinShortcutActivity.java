package com.limelight.pocket.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import com.limelight.R;

/**
 * "Add to home screen" from another app (a Winlator or GameHub game, a web page ...): with
 * Pocket Nexus as the home app, Android asks it to keep the shortcut. It goes straight to Home.
 */
public class PinShortcutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            accept(getIntent());
        }
        finish();
    }

    private void accept(Intent intent) {
        try {
            LauncherApps la = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
            LauncherApps.PinItemRequest req = la != null ? la.getPinItemRequest(intent) : null;
            if (req == null || !req.isValid() || req.getRequestType() != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) {
                return;
            }
            ShortcutInfo si = req.getShortcutInfo();
            if (si == null || !req.accept()) {
                return;
            }
            CharSequence label = si.getShortLabel() != null ? si.getShortLabel()
                    : (si.getLongLabel() != null ? si.getLongLabel() : si.getId());
            String uri = "";
            Intent target = si.getIntent();
            if (target != null) {
                uri = target.toUri(Intent.URI_INTENT_SCHEME);
            }
            HomeItem item = new HomeItem(HomeItem.SHORTCUT, label.toString(), si.getPackage(), si.getId(), uri, 0);
            ShortcutIcons.save(this, item, si);
            HomeStore.pin(this, item);
            Toast.makeText(this, getString(R.string.pocket_home_pinned_toast, label), Toast.LENGTH_SHORT).show();
        } catch (RuntimeException ignored) {
        }
    }
}
