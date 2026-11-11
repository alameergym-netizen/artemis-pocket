package com.limelight.pocket.home;

import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** The icon a shortcut came with (Winlator, GameHub ...), kept for its card on Home. */
public final class ShortcutIcons {
    private ShortcutIcons() {}

    private static File fileFor(Context c, HomeItem item) {
        File dir = new File(c.getFilesDir(), "home_icons");
        if (!dir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, Integer.toHexString(item.key().hashCode()) + ".png");
    }

    public static void save(Context c, HomeItem item, ShortcutInfo info) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1 || info == null) {
            return;
        }
        try {
            LauncherApps la = (LauncherApps) c.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            Drawable d = la.getShortcutIconDrawable(info, c.getResources().getDisplayMetrics().densityDpi);
            save(c, item, d);
        } catch (RuntimeException ignored) {
        }
    }

    public static void save(Context c, HomeItem item, Drawable d) {
        if (d == null) {
            return;
        }
        int s = Math.max(128, Math.max(d.getIntrinsicWidth(), d.getIntrinsicHeight()));
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        d.setBounds(0, 0, s, s);
        d.draw(canvas);
        try (FileOutputStream out = new FileOutputStream(fileFor(c, item))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException ignored) {
        }
    }

    public static Bitmap load(Context c, HomeItem item) {
        File f = fileFor(c, item);
        return f.isFile() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
    }
}
