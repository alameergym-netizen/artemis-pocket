package com.limelight.pocket;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.List;
import java.util.Locale;

/**
 * LSFG-Android (Lossless Scaling Frame Generation as an Android overlay app, with the user's own
 * Lossless.dll). It is a separate app: in this mode the stream runs at half the screen's refresh
 * rate with the screen kept at its fastest, and LSFG-Android's overlay doubles the picture.
 * Found by name, since it has had more than one package name.
 */
public final class LsfgApp {
    private LsfgApp() {}

    /** Launch intent of the installed LSFG app, or null. */
    public static Intent launchIntent(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
            for (ResolveInfo info : apps) {
                if (info.activityInfo == null || context.getPackageName().equals(info.activityInfo.packageName)) {
                    continue;
                }
                String pkg = info.activityInfo.packageName.toLowerCase(Locale.ROOT);
                CharSequence label = info.loadLabel(pm);
                String name = label != null ? label.toString().toLowerCase(Locale.ROOT) : "";
                if (pkg.contains("lsfg") || name.contains("lsfg") || name.contains("lossless")) {
                    Intent launch = pm.getLaunchIntentForPackage(info.activityInfo.packageName);
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        return launch;
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    public static boolean isInstalled(Context context) {
        return launchIntent(context) != null;
    }

    /** Opens LSFG-Android; false when it isn't installed. */
    public static boolean open(Context context) {
        Intent launch = launchIntent(context);
        if (launch == null) {
            return false;
        }
        try {
            context.startActivity(launch);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
