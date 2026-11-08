package com.limelight.pocket;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.limelight.R;

/**
 * The console's interface sounds, in three styles (Glass, Soft, Crisp), all 48 kHz and designed
 * as sets. They play on the media volume (the volume the handheld's buttons change) and are
 * scaled by the sliders in Settings > System: master, navigation, menus and boot.
 * The long boot sound plays through a MediaPlayer so it can be any length.
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
    /** The boot sequence (MediaPlayer, see prepareBoot / startBoot). */
    public static final int BOOT = 11;
    public static final int BOOT_EXIT = 12;

    public static final int CATEGORY_NAV = 0;
    public static final int CATEGORY_MENU = 1;
    public static final int CATEGORY_BOOT = 2;

    private static final int[][] STYLES = {
            {R.raw.pocket_glass_nav, R.raw.pocket_glass_select, R.raw.pocket_glass_back, R.raw.pocket_glass_open,
                    R.raw.pocket_glass_close, R.raw.pocket_glass_radial_open, R.raw.pocket_glass_radial_tick,
                    R.raw.pocket_glass_radial_select, R.raw.pocket_glass_toggle_on, R.raw.pocket_glass_toggle_off,
                    R.raw.pocket_glass_error},
            {R.raw.pocket_soft_nav, R.raw.pocket_soft_select, R.raw.pocket_soft_back, R.raw.pocket_soft_open,
                    R.raw.pocket_soft_close, R.raw.pocket_soft_radial_open, R.raw.pocket_soft_radial_tick,
                    R.raw.pocket_soft_radial_select, R.raw.pocket_soft_toggle_on, R.raw.pocket_soft_toggle_off,
                    R.raw.pocket_soft_error},
            {R.raw.pocket_crisp_nav, R.raw.pocket_crisp_select, R.raw.pocket_crisp_back, R.raw.pocket_crisp_open,
                    R.raw.pocket_crisp_close, R.raw.pocket_crisp_radial_open, R.raw.pocket_crisp_radial_tick,
                    R.raw.pocket_crisp_radial_select, R.raw.pocket_crisp_toggle_on, R.raw.pocket_crisp_toggle_off,
                    R.raw.pocket_crisp_error},
    };
    // The files are balanced against each other; these only trim the busiest ones
    private static final float[] TRIM = {0.8f, 0.95f, 0.9f, 0.9f, 0.85f, 0.95f, 0.8f, 0.95f, 0.85f, 0.85f, 0.9f, 1f, 0.9f};

    private static SoundPool pool;
    private static int poolStyle = -1;
    private static final int[] ids = new int[13];
    private static final boolean[] loaded = new boolean[13];
    private static long lastNavMs;

    private static MediaPlayer bootPlayer;
    private static boolean bootReady;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static AudioAttributes attributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
    }

    public static int category(int sound) {
        if (sound == NAV || sound == RADIAL_TICK) {
            return CATEGORY_NAV;
        }
        if (sound == BOOT || sound == BOOT_EXIT) {
            return CATEGORY_BOOT;
        }
        return CATEGORY_MENU;
    }

    /** 0..1: master x category slider. */
    public static float volume(Context context, int category) {
        float master = PocketPrefs.soundVolume(context, PocketPrefs.KEY_VOL_MASTER) / 100f;
        String key = category == CATEGORY_NAV ? PocketPrefs.KEY_VOL_NAV
                : category == CATEGORY_BOOT ? PocketPrefs.KEY_VOL_BOOT : PocketPrefs.KEY_VOL_MENU;
        float part = PocketPrefs.soundVolume(context, key) / 100f;
        // A gentle curve so the low end of the sliders is still usable
        return (float) Math.pow(master * part, 1.3);
    }

    private static synchronized void ensureLoaded(Context context) {
        int style = PocketPrefs.soundStyleIndex(context);
        if (pool != null && poolStyle == style) {
            return;
        }
        if (pool != null) {
            pool.release();
        }
        pool = new SoundPool.Builder().setMaxStreams(5).setAudioAttributes(attributes()).build();
        poolStyle = style;
        for (int i = 0; i < loaded.length; i++) {
            loaded[i] = false;
            ids[i] = 0;
        }
        pool.setOnLoadCompleteListener((sp, sampleId, status) -> {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == sampleId && status == 0) {
                    loaded[i] = true;
                }
            }
        });
        Context app = context.getApplicationContext();
        int[] res = STYLES[Math.max(0, Math.min(STYLES.length - 1, style))];
        // The ones heard first, first
        ids[BOOT_EXIT] = pool.load(app, R.raw.pocket_boot_exit, 1);
        for (int i = 0; i < res.length; i++) {
            ids[i] = pool.load(app, res[i], 1);
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

    /** Call after the sound style changes. */
    public static void reload(Context context) {
        try {
            ensureLoaded(context);
        } catch (RuntimeException ignored) {
        }
    }

    public static boolean isLoaded(int sound) {
        if (sound == BOOT) {
            return bootReady;
        }
        return pool != null && sound >= 0 && sound < loaded.length && loaded[sound];
    }

    public static void play(Context context, int sound) {
        try {
            if (context == null || !PocketPrefs.uiSounds(context)) {
                return;
            }
            if (sound == BOOT) {
                startBoot(context);
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
            float v = TRIM[sound] * volume(context, category(sound));
            if (loaded[sound] && v > 0.001f) {
                pool.play(ids[sound], v, v, 1, 0, 1f);
            }
        } catch (RuntimeException ignored) {
        }
    }

    /** Plays a sound of that category at its current level (for the sliders). */
    public static void preview(Context context, int category) {
        if (category == CATEGORY_NAV) {
            lastNavMs = 0;
            play(context, NAV);
        }
        else if (category == CATEGORY_BOOT) {
            play(context, BOOT_EXIT);
        }
        else {
            play(context, SELECT);
        }
    }

    // ---- Boot ---------------------------------------------------------------------------

    /** Gets the boot sound ready (call as the boot screen appears). */
    public static void prepareBoot(Context context) {
        releaseBoot();
        if (!PocketPrefs.uiSounds(context)) {
            return;
        }
        try {
            final MediaPlayer player = new MediaPlayer();
            player.setAudioAttributes(attributes());
            AssetFileDescriptor afd = context.getResources().openRawResourceFd(R.raw.pocket_boot);
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            player.setOnPreparedListener(mp -> {
                if (bootPlayer == mp) {
                    bootReady = true;
                }
            });
            player.setOnCompletionListener(mp -> releaseBoot());
            bootPlayer = player;
            bootReady = false;
            player.prepareAsync();
        } catch (Exception e) {
            releaseBoot();
        }
    }

    private static void startBoot(Context context) {
        if (bootPlayer == null || !bootReady) {
            return;
        }
        try {
            float v = TRIM[BOOT] * volume(context, CATEGORY_BOOT);
            bootPlayer.setVolume(v, v);
            bootPlayer.start();
        } catch (RuntimeException ignored) {
        }
    }

    /** Fades the boot sound out (when the boot is skipped). */
    public static void fadeOutBoot(Context context, final long ms) {
        final MediaPlayer player = bootPlayer;
        if (player == null) {
            return;
        }
        final float start = TRIM[BOOT] * volume(context, CATEGORY_BOOT);
        final int steps = 8;
        for (int i = 1; i <= steps; i++) {
            final int k = i;
            main.postDelayed(() -> {
                if (bootPlayer != player) {
                    return;
                }
                try {
                    float v = start * (1 - k / (float) steps);
                    player.setVolume(v, v);
                } catch (RuntimeException ignored) {
                }
                if (k == steps) {
                    releaseBoot();
                }
            }, ms * i / steps);
        }
    }

    public static void releaseBoot() {
        MediaPlayer player = bootPlayer;
        bootPlayer = null;
        bootReady = false;
        if (player != null) {
            try {
                player.release();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
