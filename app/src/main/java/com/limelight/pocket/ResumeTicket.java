package com.limelight.pocket;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Base64;

import com.limelight.Game;

/**
 * Seamless suspend and resume.
 *
 * Every stream remembers how it was started. When the stream window goes away without the
 * player ending it (screen off, Home, the handheld sleeping) the game keeps running on the PC
 * and the ticket is "armed". The next console screen that comes back starts the same stream
 * again on its own. Quality changes reuse the same path: the stream ends, then comes straight
 * back with the new bitrate or frame rate (the game never stops on the PC).
 */
public final class ResumeTicket {
    private ResumeTicket() {}

    public static final String EXTRA_BITRATE = "pocket_bitrate_override";
    public static final String EXTRA_FPS = "pocket_fps_override";
    public static final String EXTRA_ADAPTIVE_STEPS = "pocket_adaptive_steps";

    public static final int REASON_SUSPEND = 0;
    public static final int REASON_QUALITY = 1;
    public static final int REASON_ADAPTIVE = 2;
    /** The stream dropped by itself: reconnect to the same game. */
    public static final int REASON_RECOVER = 3;
    /** How many reconnects in a row (so a dead link doesn't loop forever). */
    public static final String EXTRA_RECOVER = "pocket_recover_attempt";

    private static final String PREFS = "pocket_resume";
    private static final String KEY_EXTRAS = "extras";
    private static final String KEY_ARMED_AT = "armed_at_wall";
    private static final String KEY_REASON = "reason";
    /** A suspended game is picked up again for this long. */
    private static final long SUSPEND_MAX_AGE_MS = 3 * 60 * 60 * 1000L;
    /** Quality switches must happen right away or not at all. */
    private static final long QUALITY_MAX_AGE_MS = 60 * 1000L;

    private static Bundle memoryExtras;
    private static long stopStartedAt;

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** A stream started: remember how to start it again and forget any older ticket. */
    public static void remember(Context context, Intent gameIntent) {
        try {
            Bundle extras = gameIntent.getExtras() != null ? new Bundle(gameIntent.getExtras()) : new Bundle();
            memoryExtras = extras;
            prefs(context).edit()
                    .putString(KEY_EXTRAS, encode(extras))
                    .putLong(KEY_ARMED_AT, 0)
                    .apply();
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * The stream is going away but the game should come back. bitrateKbps / fps of 0 keep the
     * current values.
     */
    public static void arm(Context context, int reason, int bitrateKbps, int fps, int adaptiveSteps) {
        arm(context, reason, bitrateKbps, fps, adaptiveSteps, -1);
    }

    /** recoverAttempt >= 0 marks a reconnect after a drop; -1 clears the count. */
    public static void arm(Context context, int reason, int bitrateKbps, int fps, int adaptiveSteps, int recoverAttempt) {
        try {
            Bundle extras = memoryExtras != null ? new Bundle(memoryExtras) : decode(prefs(context).getString(KEY_EXTRAS, null));
            if (extras == null) {
                return;
            }
            if (bitrateKbps > 0) {
                extras.putInt(EXTRA_BITRATE, bitrateKbps);
            }
            else if (bitrateKbps < 0) {
                extras.remove(EXTRA_BITRATE);
            }
            if (fps > 0) {
                extras.putInt(EXTRA_FPS, fps);
            }
            else if (fps < 0) {
                extras.remove(EXTRA_FPS);
            }
            if (adaptiveSteps >= 0) {
                extras.putInt(EXTRA_ADAPTIVE_STEPS, adaptiveSteps);
            }
            if (recoverAttempt >= 0) {
                extras.putInt(EXTRA_RECOVER, recoverAttempt);
            }
            else {
                extras.remove(EXTRA_RECOVER);
            }
            memoryExtras = extras;
            prefs(context).edit()
                    .putString(KEY_EXTRAS, encode(extras))
                    .putLong(KEY_ARMED_AT, System.currentTimeMillis())
                    .putInt(KEY_REASON, reason)
                    .apply();
        } catch (RuntimeException ignored) {
        }
    }

    public static void disarm(Context context) {
        prefs(context).edit().putLong(KEY_ARMED_AT, 0).apply();
    }

    public static boolean isArmed(Context context) {
        long armedAt = prefs(context).getLong(KEY_ARMED_AT, 0);
        if (armedAt == 0) {
            return false;
        }
        long age = System.currentTimeMillis() - armedAt;
        long max = prefs(context).getInt(KEY_REASON, REASON_SUSPEND) == REASON_SUSPEND
                ? SUSPEND_MAX_AGE_MS : QUALITY_MAX_AGE_MS;
        return age >= 0 && age < max;
    }

    public static int reason(Context context) {
        return prefs(context).getInt(KEY_REASON, REASON_SUSPEND);
    }

    /** The armed stream, ready to start, or null. Taking it disarms it. */
    public static Intent take(Context context) {
        if (!isArmed(context)) {
            return null;
        }
        Bundle extras = memoryExtras != null ? new Bundle(memoryExtras) : decode(prefs(context).getString(KEY_EXTRAS, null));
        disarm(context);
        if (extras == null || extras.getString(Game.EXTRA_HOST) == null) {
            return null;
        }
        Intent intent = new Intent(context, Game.class);
        intent.putExtras(extras);
        return intent;
    }

    /** Puts a taken ticket back (the screen went away before it was used). */
    public static void putBack(Context context, int reason) {
        prefs(context).edit()
                .putLong(KEY_ARMED_AT, System.currentTimeMillis())
                .putInt(KEY_REASON, reason)
                .apply();
    }

    public static String appName(Intent intent) {
        String name = intent != null ? intent.getStringExtra(Game.EXTRA_APP_NAME) : null;
        return name != null ? name : "";
    }

    public static boolean isUsb(Intent intent) {
        return intent != null && UsbLink.isUsbAddress(intent.getStringExtra(Game.EXTRA_HOST));
    }

    // ---- The previous stream's shutdown (so the next one doesn't race it) ----------------

    public static void markStopStarted() {
        stopStartedAt = SystemClock.elapsedRealtime();
    }

    public static void markStopFinished() {
        stopStartedAt = 0;
    }

    /** True while the last stream is still saying goodbye to the PC (at most a few seconds). */
    public static boolean isPreviousStreamStopping() {
        return stopStartedAt != 0 && SystemClock.elapsedRealtime() - stopStartedAt < 5000;
    }

    // ---- Storage ------------------------------------------------------------------------

    private static String encode(Bundle bundle) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(bundle);
            return Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP);
        } finally {
            parcel.recycle();
        }
    }

    private static Bundle decode(String encoded) {
        if (encoded == null) {
            return null;
        }
        Parcel parcel = Parcel.obtain();
        try {
            byte[] data = Base64.decode(encoded, Base64.NO_WRAP);
            parcel.unmarshall(data, 0, data.length);
            parcel.setDataPosition(0);
            return parcel.readBundle(ResumeTicket.class.getClassLoader());
        } catch (RuntimeException e) {
            return null;
        } finally {
            parcel.recycle();
        }
    }
}
