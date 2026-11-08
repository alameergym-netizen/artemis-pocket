package com.limelight.pocket;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;

import com.limelight.R;

/**
 * The console's interface sounds: one soft "glass" palette at 48 kHz (designed together, all
 * through the same small hall reverb). Turn off in Settings > System.
 */
public final class PocketSounds {
    private PocketSounds() {}

    public static final int NAV = 0;
    public static final int SELECT = 1;
    public static final int BACK = 2;
    public static final int OPEN = 3;
    public static final int CLOSE = 4;
    public static final int RADIAL_OPEN = 5;
    public static final int RADIAL_TICK = 6;
    public static final int RADIAL_SELECT = 7;
    public static final int TOGGLE_ON = 8;
    public static final int TOGGLE_OFF = 9;
    public static final int ERROR = 10;
    public static final int BOOT = 11;

    private static final int[] RES = {
            R.raw.pocket_nav, R.raw.pocket_select, R.raw.pocket_back, R.raw.pocket_open, R.raw.pocket_close,
            R.raw.pocket_radial_open, R.raw.pocket_radial_tick, R.raw.pocket_radial_select,
            R.raw.pocket_toggle_on, R.raw.pocket_toggle_off, R.raw.pocket_error, R.raw.pocket_boot};
    // The files are already balanced against each other; these only trim the busiest ones
    private static final float[] VOLUME = {0.75f, 0.9f, 0.85f, 0.85f, 0.8f, 0.9f, 0.8f, 0.9f, 0.8f, 0.8f, 0.85f, 1f};

    private static SoundPool pool;
    private static final int[] ids = new int[RES.length];
    private static final boolean[] loaded = new boolean[RES.length];
    private static long lastNavMs;

    private static synchronized void ensureLoaded(Context context) {
        if (pool != null) {
            return;
        }
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build();
        pool.setOnLoadCompleteListener((sp, sampleId, status) -> {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == sampleId && status == 0) {
                    loaded[i] = true;
                }
            }
        });
        Context app = context.getApplicationContext();
        // The boot sound first, so it is ready for the boot screen
        ids[BOOT] = pool.load(app, RES[BOOT], 1);
        for (int i = 0; i < RES.length; i++) {
            if (i != BOOT) {
                ids[i] = pool.load(app, RES[i], 1);
            }
        }
    }

    /** Loads the sounds early so the first press isn't silent. */
    public static void preload(Context context) {
        try {
            if (PocketPrefs.uiSounds(context)) {
                ensureLoaded(context);
            }
        } catch (RuntimeException ignored) {
        }
    }

    public static boolean isLoaded(int sound) {
        return pool != null && loaded[sound];
    }

    public static void play(Context context, int sound) {
        try {
            if (context == null || !PocketPrefs.uiSounds(context)) {
                return;
            }
            if (sound == NAV || sound == RADIAL_TICK) {
                // Holding the D-pad shouldn't machine-gun the tick
                long now = SystemClock.uptimeMillis();
                if (now - lastNavMs < 40) {
                    return;
                }
                lastNavMs = now;
            }
            ensureLoaded(context);
            if (loaded[sound]) {
                pool.play(ids[sound], VOLUME[sound], VOLUME[sound], sound == BOOT ? 2 : 1, 0, 1f);
            }
        } catch (RuntimeException ignored) {
        }
    }
}
