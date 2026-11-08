package com.limelight.pocket;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;

import com.limelight.R;

/** Soft console interface sounds (move, select, back, menu). Turn off in Settings → System. */
public final class PocketSounds {
    private PocketSounds() {}

    public static final int NAV = 0;
    public static final int SELECT = 1;
    public static final int BACK = 2;
    public static final int OPEN = 3;

    private static final int[] RES = {R.raw.pocket_nav, R.raw.pocket_select, R.raw.pocket_back, R.raw.pocket_open};
    private static final float[] VOLUME = {0.35f, 0.5f, 0.45f, 0.5f};

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
        pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build();
        pool.setOnLoadCompleteListener((sp, sampleId, status) -> {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == sampleId && status == 0) {
                    loaded[i] = true;
                }
            }
        });
        Context app = context.getApplicationContext();
        for (int i = 0; i < RES.length; i++) {
            ids[i] = pool.load(app, RES[i], 1);
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

    public static void play(Context context, int sound) {
        try {
            if (context == null || !PocketPrefs.uiSounds(context)) {
                return;
            }
            if (sound == NAV) {
                // Holding the D-pad shouldn't machine-gun the tick
                long now = SystemClock.uptimeMillis();
                if (now - lastNavMs < 45) {
                    return;
                }
                lastNavMs = now;
            }
            ensureLoaded(context);
            if (loaded[sound]) {
                pool.play(ids[sound], VOLUME[sound], VOLUME[sound], 1, 0, 1f);
            }
        } catch (RuntimeException ignored) {
        }
    }
}
