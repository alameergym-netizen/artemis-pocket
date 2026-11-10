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
        if (rapid(view)) {
            // Moving quickly (holding the D-pad): no time for the old name to leave; the new one
            // just slides in, so the names stay readable instead of blinking out at every step
            view.animate().cancel();
            view.setText(text);
            arriveQuick(view, shift);
            return;
        }
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

    /**
     * Changes a picture (a game's logo) the way swapText changes a name: the old one slips out
     * in the direction you moved, the new one comes in from the other side. direction 0: the
     * picture simply arrives. Moving on before it finishes just carries on with the newest.
     */
    public static void swapImage(final android.widget.ImageView view, final android.graphics.Bitmap bitmap,
                                 final int direction) {
        if (view == null) {
            return;
        }
        if (!on(view)) {
            view.setImageBitmap(bitmap);
            return;
        }
        final float shift = dp(view, 18) * (direction == 0 ? 1 : direction);
        if (direction != 0 && view.getDrawable() != null && rapid(view)) {
            view.animate().cancel();
            view.setImageBitmap(bitmap);
            arriveQuick(view, shift);
            return;
        }
        if (direction == 0 || view.getDrawable() == null || view.getAlpha() < 0.05f) {
            view.animate().cancel();
            view.setImageBitmap(bitmap);
            view.setAlpha(0f);
            view.setTranslationX(shift * 0.67f);
            view.animate().alpha(1f).translationX(0).setStartDelay(0).setDuration(420)
                    .setInterpolator(Spring.SOFT).withEndAction(null).start();
            return;
        }
        // A new animation of the same properties cancels the previous one and its end action,
        // so only the newest picture is ever put in
        view.animate().alpha(0f).translationX(-shift * 0.6f).setStartDelay(0).setDuration(90)
                .setInterpolator(PocketUi.EASE)
                .withEndAction(() -> {
                    view.setImageBitmap(bitmap);
                    view.setTranslationX(shift);
                    view.animate().alpha(1f).translationX(0).setDuration(420)
                            .setInterpolator(Spring.SOFT).withEndAction(null).start();
                }).start();
    }

    /** When each view last changed (to tell a held D-pad from single moves). */
    private static final java.util.WeakHashMap<View, Long> LAST_CHANGE = new java.util.WeakHashMap<>();
    private static final long RAPID_MS = 170;

    /** True when the view changed moments ago too (records this change). */
    private static boolean rapid(View view) {
        long now = android.os.SystemClock.uptimeMillis();
        Long last = LAST_CHANGE.put(view, now);
        return last != null && now - last < RAPID_MS;
    }

    /** The quick arrival used while moving fast: from where it is, a short slide, never hidden. */
    private static void arriveQuick(View view, float shift) {
        view.setAlpha(Math.max(view.getAlpha(), 0.45f));
        view.setTranslationX(shift * 0.45f);
        view.animate().alpha(1f).translationX(0).setStartDelay(0).setDuration(260)
                .setInterpolator(DECELERATE).withEndAction(null).start();
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

    /**
     * Something just changed (a chip, a pill): it rises a few dp into place while it fades up.
     * Calm on purpose: no swelling or springing back, which read as a pulse on every move.
     */
    public static void bump(final View v) {
        if (!on(v)) {
            return;
        }
        // Takes over alpha and Y only (an entrance still running on the view carries on). Held
        // D-pad: it stays readable, just settling, instead of dimming at every step
        if (rapid(v)) {
            v.setAlpha(Math.max(v.getAlpha(), 0.7f));
        }
        else {
            v.setAlpha(0.35f);
            v.setTranslationY(dp(v, 4));
        }
        v.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(320)
                .setInterpolator(DECELERATE).withEndAction(null).start();
    }

    /**
     * The time of the frame being drawn (its vsync), in ns. Animations stepped by hand use it so
     * they move on the same clock as the system's: a delayed frame then still shows things where
     * they belong, instead of the lens lagging behind or jumping after the cards.
     */
    public static long frameNanos() {
        try {
            return android.view.Choreographer.getInstance().getFrameTimeNanos();
        } catch (RuntimeException e) {
            return System.nanoTime();
        }
    }

    /** Starts quickly and lands softly, never overshooting. */
    public static final android.view.animation.Interpolator DECELERATE =
            new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);
}
