package com.limelight.pocket.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.view.View;

import com.limelight.R;
import com.limelight.pocket.PocketPrefs;

/**
 * A game's key art, sharp across the top of the screen (console style), cross-faded between two
 * layers with a slow settle in size. Used by the home screen and the library alike.
 *
 * Layout: pocketKeyArtUnderlay (dims the blurred art while key art shows, so its colours still
 * glow through where the key art fades out), pocketKeyArtFrame holding pocketKeyArtA / B
 * (full screen; each fades itself out, see {@link KeyArtView}).
 */
public final class KeyArtStage {

    /** How much the underlay dims the blurred art while key art shows. */
    private static final float UNDER = 0.78f;

    private final Activity activity;
    private final View frame;
    private final View under;
    private final KeyArtView a;
    private final KeyArtView b;
    private boolean onA;
    private Bitmap shown;
    private android.animation.ObjectAnimator drift;

    private KeyArtStage(Activity activity, View frame, View under, KeyArtView a, KeyArtView b) {
        this.activity = activity;
        this.frame = frame;
        this.under = under;
        this.a = a;
        this.b = b;
    }

    /** The stage of this screen, or null when its layout has none. */
    public static KeyArtStage find(Activity activity) {
        View frame = activity.findViewById(R.id.pocketKeyArtFrame);
        View under = activity.findViewById(R.id.pocketKeyArtUnderlay);
        KeyArtView a = activity.findViewById(R.id.pocketKeyArtA);
        KeyArtView b = activity.findViewById(R.id.pocketKeyArtB);
        if (frame == null || under == null || a == null || b == null) {
            return null;
        }
        return new KeyArtStage(activity, frame, under, a, b);
    }

    /** Once settled, the art drifts very slowly (a living backdrop, like a console's). */
    private void startDrift(KeyArtView view) {
        stopDrift();
        if (shown == null || !PocketPrefs.animationsEnabled(activity) || view.getAlpha() < 0.99f) {
            return;
        }
        view.setPivotX(view.getWidth() * 0.75f);
        view.setPivotY(0f);
        android.animation.PropertyValuesHolder sx = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.04f);
        android.animation.PropertyValuesHolder sy = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.04f);
        android.animation.PropertyValuesHolder tx = android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f,
                -view.getWidth() * 0.012f);
        drift = android.animation.ObjectAnimator.ofPropertyValuesHolder(view, sx, sy, tx);
        drift.setDuration(22000);
        drift.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        drift.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        drift.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        drift.start();
    }

    private void stopDrift() {
        if (drift != null) {
            drift.cancel();
            drift = null;
        }
        a.setTranslationX(0f);
        b.setTranslationX(0f);
    }

    /** Forget what is shown, so the next request puts it up again (new art arrived). */
    public void invalidate() {
        shown = null;
    }

    /** True while key art is on screen. */
    public boolean showing() {
        return shown != null;
    }

    /** Shows this key art (null: none, the blurred art and lights show again). */
    public void show(Bitmap bitmap) {
        // Key art is made to be seen: every style shows it, except pure black
        if (bitmap != null && PocketPrefs.BACKDROP_BLACK.equals(PocketPrefs.backdrop(activity))) {
            bitmap = null;
        }
        if (bitmap != null && bitmap.isRecycled()) {
            bitmap = null;
        }
        if (bitmap == shown && (bitmap != null || frame.getAlpha() == 0f)) {
            return;
        }
        shown = bitmap;
        boolean animate = PocketPrefs.animationsEnabled(activity);
        if (bitmap == null) {
            stopDrift();
            if (animate) {
                frame.animate().alpha(0f).setDuration(300).start();
                under.animate().alpha(0f).setDuration(300).start();
            }
            else {
                frame.setAlpha(0f);
                under.setAlpha(0f);
            }
            return;
        }
        KeyArtView incoming = onA ? b : a;
        KeyArtView outgoing = onA ? a : b;
        onA = !onA;
        incoming.setImageBitmap(bitmap);
        if (PocketPrefs.colorBackgroundArt(activity)) {
            incoming.clearColorFilter();
        }
        else {
            ColorMatrix mono = new ColorMatrix();
            mono.setSaturation(0f);
            incoming.setColorFilter(new ColorMatrixColorFilter(mono));
        }
        // Background layers: no entrance runs on them, so taking them over completely is fine
        incoming.animate().cancel();
        outgoing.animate().cancel();
        stopDrift();
        if (animate) {
            // A soft cross-fade with a slow settle in size (like a console's home screen)
            incoming.setPivotY(0f);
            incoming.setScaleX(1.05f);
            incoming.setScaleY(1.05f);
            incoming.setAlpha(0f);
            final KeyArtView settled = incoming;
            incoming.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(900)
                    .setInterpolator(PocketMotion.DECELERATE)
                    .withEndAction(() -> startDrift(settled)).start();
            outgoing.animate().alpha(0f).setDuration(420).start();
            frame.animate().alpha(1f).setDuration(420).start();
            under.animate().alpha(UNDER).setDuration(420).start();
        }
        else {
            incoming.setAlpha(1f);
            incoming.setScaleX(1f);
            incoming.setScaleY(1f);
            outgoing.setAlpha(0f);
            frame.setAlpha(1f);
            under.setAlpha(UNDER);
        }
    }
}
