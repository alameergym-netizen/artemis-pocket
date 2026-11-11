package com.limelight.pocket.apps;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.limelight.pocket.PocketPrefs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Android games and apps picked for the library (not every app on the handheld). Their
 * art and information are kept like a PC's games, under the made-up PC "android".
 */
public final class AndroidLibrary {
    private AndroidLibrary() {}

    /** Where their art and settings are kept (like a PC's uuid). */
    public static final String UUID = "android";

    private static final String KEY_LIBRARY = "pocket_android_library";
    private static final String KEY_SET_UP = "pocket_android_library_set";

    /** An app installed on the handheld that can be opened. */
    public static final class InstalledApp {
        public final String packageName;
        public final String label;
        public final int type;

        InstalledApp(String packageName, String label, int type) {
            this.packageName = packageName;
            this.label = label;
            this.type = type;
        }
    }

    public static Set<String> packages(Context c) {
        return new HashSet<>(PocketPrefs.prefs(c).getStringSet(KEY_LIBRARY, new HashSet<String>()));
    }

    /** True once the apps were picked the first time. */
    public static boolean isSetUp(Context c) {
        return PocketPrefs.prefs(c).getBoolean(KEY_SET_UP, false);
    }

    /** The library becomes exactly these apps. Returns the ones that are new. */
    public static Set<String> set(Context c, Collection<String> pkgs) {
        Set<String> before = packages(c);
        Set<String> now = new HashSet<>(pkgs);
        PocketPrefs.prefs(c).edit().putStringSet(KEY_LIBRARY, now).putBoolean(KEY_SET_UP, true).apply();
        Set<String> added = new HashSet<>(now);
        added.removeAll(before);
        return added;
    }

    public static void remove(Context c, String pkg) {
        Set<String> now = packages(c);
        now.remove(pkg);
        PocketPrefs.prefs(c).edit().putStringSet(KEY_LIBRARY, now).apply();
    }

    /** A stable id for an app's art and settings. */
    public static int idFor(String pkg) {
        int h = pkg.hashCode() & 0x3FFFFFFF;
        return h == 0 ? 1 : h;
    }

    /** Every app that can be opened (blocking: never on the main thread), this one left out. */
    public static List<InstalledApp> installed(Context c) {
        PackageManager pm = c.getPackageManager();
        List<InstalledApp> out = new ArrayList<>();
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        } catch (RuntimeException e) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) {
                continue;
            }
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || !seen.add(pkg)) {
                continue;
            }
            CharSequence label = ri.loadLabel(pm);
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            out.add(new InstalledApp(pkg, label != null ? label.toString() : pkg,
                    AndroidAppsActivity.typeOf(pkg, label, ai)));
        }
        return out;
    }
}
