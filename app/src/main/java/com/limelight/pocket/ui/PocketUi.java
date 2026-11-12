package com.limelight.pocket.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.pocket.PocketPrefs;

/** Shared look-and-feel helpers for the console screens. */
public final class PocketUi {
    private PocketUi() {}

    /** Calm decelerating curve used for all motion (no overshoot). */
    public static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    /**
     * Applies the user's interface size by scaling the screen density for this context.
     * Every dp/sp in the console screens grows or shrinks with it.
     */
    /**
     * The hero's Play / Resume button: frosted glass (when the screen has live glass) tinted with
     * the accent, a light rim, and a soft glow of the accent under it.
     */
    public static void stylePlayPill(android.widget.TextView play) {
        if (play == null || Boolean.TRUE.equals(play.getTag(com.limelight.R.id.pocketHeroPlay))) {
            return;
        }
        play.setTag(com.limelight.R.id.pocketHeroPlay, Boolean.TRUE);
        Context c = play.getContext();
        float d = c.getResources().getDisplayMetrics().density;
        // Like the new consoles' and streaming apps' Play: solid white, black label, a capsule,
        // the one bright thing on the screen
        play.setBackground(playFace(d));
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            play.setForeground(null);
        }
        play.setTypeface(PocketFonts.semibold());
        play.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f);
        play.setLetterSpacing(-0.01f);
        play.setIncludeFontPadding(false);
        play.setGravity(android.view.Gravity.CENTER);
        // Sized like a console's Play (it was a size too big next to the logo)
        play.setMinHeight(Math.round(34 * d));
        play.setMinWidth(Math.round(100 * d));
        play.setPadding(Math.round(16 * d), Math.round(8 * d), Math.round(21 * d), Math.round(8 * d));
        play.setShadowLayer(0f, 0f, 0f, 0);
        // Touch presses it; with buttons, the hero takes "focus" without stealing it from the row
        play.setFocusable(false);
        play.setClickable(true);
        play.setTextColor(0xFF0B0B0F);
        play.setElevation(6 * d);
        play.setClipToOutline(false);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            play.setOutlineSpotShadowColor(0xFF000000);
            play.setOutlineAmbientShadowColor(0xFF000000);
        }
    }

    /**
     * Play's face: a white capsule with a soft top-to-bottom sheen and a hairline edge, so it
     * reads as a solid, pressable thing (not a flat white shape) on bright art too.
     */
    public static android.graphics.drawable.Drawable playFace(float d) {
        android.graphics.drawable.GradientDrawable body = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFFFFFFFF, 0xFFF4F4F7, 0xFFE4E4EA});
        body.setCornerRadius(PLAY_RADIUS_DP * d);
        body.setStroke(Math.max(1, Math.round(0.75f * d)), 0x1F000000);
        android.graphics.drawable.GradientDrawable shine = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x99FFFFFF, 0x00FFFFFF});
        shine.setCornerRadius(PLAY_RADIUS_DP * d);
        android.graphics.drawable.LayerDrawable face = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{body, shine});
        int in = Math.max(1, Math.round(1.5f * d));
        face.setLayerInset(1, in, in, in, Math.round(14 * d));
        return face;
    }

    /** The corner of Play and the hero's buttons (dp): a capsule. */
    public static final float PLAY_RADIUS_DP = 100f;

    public static Context wrapWithUiScale(Context base) {
        int percent;
        try {
            percent = PocketPrefs.uiScalePercent(base);
        } catch (RuntimeException e) {
            return base;
        }
        if (percent == 100 || percent < 50 || percent > 150) {
            return base;
        }
        Configuration config = new Configuration(base.getResources().getConfiguration());
        int baseDpi = config.densityDpi;
        if (baseDpi <= 0) {
            return base;
        }
        config.densityDpi = Math.max(120, Math.round(baseDpi * percent / 100f));
        return base.createConfigurationContext(config);
    }

    /** Haptics talk to a system service: done off the main thread so a move never waits on it. */
    private static final java.util.concurrent.ExecutorService HAPTICS =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private static long quietUntil;

    /** No move sound for a moment (a screen placing its first focus isn't a move). */
    public static void quietFor(long ms) {
        quietUntil = android.os.SystemClock.uptimeMillis() + ms;
    }

    /** A very light tick when moving between items, like a console menu. */
    public static void tick(Context context) {
        if (android.os.SystemClock.uptimeMillis() < quietUntil) {
            return;
        }
        com.limelight.pocket.PocketSounds.play(context, com.limelight.pocket.PocketSounds.NAV);
        if (!PocketPrefs.hapticsEnabled(context)) {
            return;
        }
        final Context app = context.getApplicationContext();
        HAPTICS.execute(() -> {
            try {
                Vibrator vibrator = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator == null || !vibrator.hasVibrator()) {
                    return;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
                }
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(8, 60));
                }
            } catch (RuntimeException ignored) {
            }
        });
    }

    /** Sets text without flicker (kept as a helper so callers stay simple). */
    public static void swapText(final TextView view, final CharSequence text) {
        if (view != null && !TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
    }

    /**
     * Frosted-glass effect: blurs everything inside the given view (Android 12+), used behind
     * overlays and panels. Falls back to dimming on older Android versions.
     */
    public static void setBackdropBlur(final View view, boolean on) {
        if (view == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Object tag = view.getTag(R.id.pocket_blur_animator);
            if (tag instanceof ValueAnimator) {
                ((ValueAnimator) tag).cancel();
            }
            float target = on ? 28f : 0f;
            Object currentTag = view.getTag(R.id.pocket_blur_radius);
            float current = currentTag instanceof Float ? (Float) currentTag : 0f;
            if (!PocketPrefs.animationsEnabled(view.getContext())) {
                applyBlur(view, target);
                return;
            }
            ValueAnimator anim = ValueAnimator.ofFloat(current, target);
            anim.setDuration(on ? 320 : 220);
            anim.setInterpolator(EASE);
            anim.addUpdateListener(a -> applyBlur(view, (Float) a.getAnimatedValue()));
            view.setTag(R.id.pocket_blur_animator, anim);
            anim.start();
        }
        else {
            view.animate().alpha(on ? 0.35f : 1f).setDuration(220).start();
        }
    }

    private static void applyBlur(View view, float radius) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setTag(R.id.pocket_blur_radius, radius);
            if (radius < 0.5f) {
                view.setRenderEffect(null);
            }
            else {
                view.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
            }
        }
    }
}
