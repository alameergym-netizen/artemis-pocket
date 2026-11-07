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

    /** Apple-style "ease out" curve used for most motion. */
    public static final PathInterpolator EASE = new PathInterpolator(0.2f, 0.9f, 0.1f, 1f);

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

    /** Cross-fades a TextView to new text with a small upward slide. */
    public static void swapText(final TextView view, final CharSequence text) {
        if (view == null) {
            return;
        }
        if (TextUtils.equals(view.getText(), text)) {
            return;
        }
        if (!PocketPrefs.animationsEnabled(view.getContext()) || view.getVisibility() != View.VISIBLE) {
            view.setText(text);
            return;
        }
        final float shift = view.getResources().getDisplayMetrics().density * 6;
        view.animate().cancel();
        view.animate().alpha(0f).translationY(-shift).setDuration(90).withEndAction(() -> {
            view.setText(text);
            view.setTranslationY(shift);
            view.animate().alpha(1f).translationY(0).setDuration(240).setInterpolator(EASE).start();
        }).start();
    }

    /** Sweeps a soft band of light across a card (the view tagged "shine"). */
    public static void shine(View card) {
        if (card == null || !PocketPrefs.animationsEnabled(card.getContext())) {
            return;
        }
        final View band = card.findViewWithTag("shine");
        if (band == null) {
            return;
        }
        final float width = card.getWidth();
        if (width <= 0) {
            return;
        }
        band.animate().cancel();
        band.setVisibility(View.VISIBLE);
        band.setAlpha(1f);
        band.setTranslationX(-width);
        band.animate().translationX(width).setStartDelay(60).setDuration(750)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .withEndAction(() -> band.setVisibility(View.INVISIBLE)).start();
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
