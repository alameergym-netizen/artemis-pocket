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

    /** A very light tick when moving between items, like a console menu. */
    public static void tick(Context context) {
        com.limelight.pocket.PocketSounds.play(context, com.limelight.pocket.PocketSounds.NAV);
        if (!PocketPrefs.hapticsEnabled(context)) {
            return;
        }
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
            }
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(8, 60));
            }
        } catch (RuntimeException ignored) {
        }
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
