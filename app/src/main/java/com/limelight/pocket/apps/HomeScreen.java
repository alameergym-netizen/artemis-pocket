package com.limelight.pocket.apps;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;

import com.limelight.PcView;

/**
 * Pocket Nexus as the device's home screen. Android starts this when Home is pressed; it brings
 * the Pocket Nexus home screen forward (or the stream, while one is running) and goes away.
 */
public class HomeScreen extends Activity {

    /** True while a stream is open (set by the stream itself). */
    public static volatile boolean streaming;

    /** True when Pocket Nexus is the device's home app. */
    public static boolean isDefault(Context context) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo info = context.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return info != null && info.activityInfo != null
                    && context.getPackageName().equals(info.activityInfo.packageName);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Android's "Default home app" page (where Pocket Nexus can be picked, or another launcher). */
    public static void openChooser(Activity from) {
        try {
            from.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (RuntimeException e) {
            try {
                from.startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = new Intent(this, PcView.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        if (streaming) {
            // Like tapping the app's icon: the task comes back as it was (the stream on top)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
        else {
            // Home: Pocket Nexus's Home, whatever screen was open above it
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(PcView.EXTRA_SHOW_HOME, true);
        }
        try {
            startActivity(intent);
            overridePendingTransition(0, 0);
        } catch (RuntimeException ignored) {
        }
        finish();
    }
}
