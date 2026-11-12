package com.limelight.pocket;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.limelight.pocket.apps.AndroidLibrary;
import com.limelight.pocket.home.HomeItem;
import com.limelight.pocket.home.HomeStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * For the screenshots the build takes on an Android emulator: settings, Home items and art read
 * from a file the build puts in this app's own folder, so every screen can be seen with games in it.
 * Only ever runs on an emulator; on a real handheld it does nothing.
 *
 * seed.txt, one instruction per line (tab separated):
 *   pref  bool|int|string  key  value
 *   pin   kind  name  a  b  c  n            (a Home item, as HomeItem.toLine)
 *   art   package  kind(0-5)  file          (art for an Android app, file next to seed.txt)
 */
public final class PreviewSeed {
    private PreviewSeed() {}

    static boolean isEmulator() {
        String hw = Build.HARDWARE != null ? Build.HARDWARE.toLowerCase(Locale.ROOT) : "";
        return hw.contains("ranchu") || hw.contains("goldfish");
    }

    /** The build's emulator screenshots skip the boot (its animated blur strains the emulator). */
    public static boolean skipsBoot(Context context) {
        return isEmulator() && PocketPrefs.prefs(context).getBoolean("pocket_preview_no_boot", false);
    }

    public static void apply(Context context) {
        if (!isEmulator()) {
            return;
        }
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            return;
        }
        File dir = new File(base, "preview");
        File seed = new File(dir, "seed.txt");
        if (!seed.exists()) {
            return;
        }
        SharedPreferences.Editor e = PocketPrefs.prefs(context).edit();
        try (BufferedReader r = new BufferedReader(new FileReader(seed))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 2) {
                    continue;
                }
                switch (p[0]) {
                    case "pref":
                        if (p.length >= 4) {
                            if ("bool".equals(p[1])) {
                                e.putBoolean(p[2], Boolean.parseBoolean(p[3]));
                            }
                            else if ("set".equals(p[1])) {
                                e.putStringSet(p[2], new java.util.HashSet<>(java.util.Arrays.asList(p[3].split(","))));
                            }
                            else if ("int".equals(p[1])) {
                                e.putInt(p[2], Integer.parseInt(p[3]));
                            }
                            else {
                                e.putString(p[2], p[3]);
                            }
                        }
                        break;
                    case "pin": {
                        HomeItem item = HomeItem.parse(line.substring(4));
                        if (item != null) {
                            HomeStore.pin(context, item);
                        }
                        break;
                    }
                    case "art":
                        if (p.length >= 4) {
                            File f = new File(dir, p[3]);
                            if (f.exists()) {
                                PocketArt.save(context, AndroidLibrary.UUID, AndroidLibrary.idFor(p[1]),
                                        Integer.parseInt(p[2]), read(f));
                            }
                        }
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        e.commit();
        //noinspection ResultOfMethodCallIgnored
        seed.renameTo(new File(dir, "seed.done"));
    }

    private static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            return data;
        }
    }
}
