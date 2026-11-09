package com.limelight.pocket.ui;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.limelight.pocket.PocketPrefs;

import java.util.List;

/**
 * Shared motion for the console screens, so everything moves the same way: things arrive with
 * a soft spring and leave quickly; groups arrive one after another (a stagger); text changes
 * slide in the direction you moved; presses give a small physical "pop".
 */
public final class PocketMotion {
    private PocketMotion() {}

    public static boolean on(View view) {
        return view != null && PocketPrefs.animationsEnabled(view.getContext());
    }

    private static float dp(View v, float value) {
        return value * v.getResources().getDisplayMetrics().density;
    }

    /**
     * Brings views in one after another: each slides from (dx, dy) dp, fades and settles with
     * a spring. stepMs between them.
     */
    public static void stagger(List<View> views, long startDelay, long stepMs, float dxDp, float dyDp) {
        long delay = startDelay;
        for (View v : views) {
            if (v == null || v.getVisibility() != View.VISIBLE) {
                continue;
            }
            arrive(v, delay, dxDp, dyDp, 0.97f);
            delay += stepMs;
        }
    }

    /** One view arriving: from (dx, dy) dp (and fromScale, unless 1), to rest. */
    public static void arrive(final View v, long delay, float dxDp, float dyDp, float fromScale) {
        if (!on(v)) {
            return;
        }
        // No cancel(): a new animation only takes over the properties it animates, so a focused
        // card keeps springing to its size while it arrives
        v.setAlpha(0f);
        android.view.ViewPropertyAnimator anim = v.animate().alpha(1f);
        // Only the axes that move are touched (a focused card keeps its lift)
        if (dxDp != 0) {
            v.setTranslationX(dp(v, dxDp));
            anim.translationX(0);
        }
        if (dyDp != 0) {
            v.setTranslationY(dp(v, dyDp));
            anim.translationY(0);
        }
        if (fromScale != 1f) {
            // Views that manage their own scale (focused cards) are left alone
            v.setScaleX(fromScale);
            v.setScaleY(fromScale);
            anim.scaleX(1f).scaleY(1f);
        }
        anim.setStartDelay(delay).setDuration(560).setInterpolator(Spring.SOFT)
                // The start delay sticks to the view's animator: clear it for whoever animates next
                .withEndAction(() -> v.animate().setStartDelay(0)).start();
    }

    /** The children of a group, staggered (e.g. carousel cards, settings rows). */
    public static void cascade(ViewGroup group, long startDelay, long stepMs, float dxDp, float dyDp, int max) {
        if (group == null || !on(group)) {
            return;
        }
        long delay = startDelay;
        int n = Math.min(group.getChildCount(), max);
        for (int i = 0; i < n; i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            arrive(child, delay, dxDp, dyDp, 1f);
            delay += stepMs;
        }
    }

    /**
     * Changes a text with motion: the old text slips out (in the direction you moved), the new
     * one springs in from the other side. direction: 1 = moved forward, -1 = back, 0 = fade.
     */
    public static void swapText(final TextView view, final CharSequence text, final int direction) {
        if (view == null || TextUtils.equals(view.getText(), text)) {
            return;
        }
        if (!on(view)) {
            view.setText(text);
            return;
        }
        final float shift = dp(view, 18) * direction;
        // No cancel(): that would freeze an entrance that is still moving the view (it would stay
        // half-faded or off its line). A new animation only takes over alpha and X.
        view.animate().alpha(0f).translationX(-shift * 0.6f).setStartDelay(0).setDuration(90)
                .setInterpolator(PocketUi.EASE)
                .withEndAction(() -> {
                    view.setText(text);
                    view.setTranslationX(shift);
                    view.animate().alpha(1f).translationX(0).setDuration(420)
                            .setInterpolator(Spring.SOFT).withEndAction(null).start();
                }).start();
    }

    /** A small "pop": shrinks a touch, then springs back (button glyphs, chips). */
    public static void pop(final View v, float depth) {
        if (!on(v)) {
            return;
        }
        v.animate().cancel();
        v.animate().scaleX(depth).scaleY(depth).setStartDelay(0).setDuration(70).setInterpolator(PocketUi.EASE)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(380)
                        .setInterpolator(Spring.POP).withEndAction(null).start()).start();
    }

    /** Draws attention to something that just changed: it swells slightly and settles. */
    public static void bump(final View v) {
        if (!on(v)) {
            return;
        }
        // Takes over the scale only (an entrance still running on the view carries on)
        v.setScaleX(0.9f);
        v.setScaleY(0.9f);
        v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(420).setInterpolator(Spring.POP)
                .withEndAction(null).start();
    }
}
