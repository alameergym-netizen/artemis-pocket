package com.limelight.pocket.ui;

import android.view.animation.Interpolator;

/**
 * Spring physics for the console UI: a real damped spring that can be re-targeted mid-flight
 * (so motion never jumps), plus an interpolator version for ordinary view animations.
 */
public final class Spring {
    public float value;
    public float velocity;
    public float target;
    private final float stiffness;
    private final float damping;

    /** dampingRatio 1 = no overshoot, ~0.6 = a lively bounce. */
    public Spring(float stiffness, float dampingRatio, float initial) {
        this.stiffness = stiffness;
        this.damping = (float) (2 * dampingRatio * Math.sqrt(stiffness));
        this.value = initial;
        this.target = initial;
    }

    public void snap(float v) {
        value = v;
        target = v;
        velocity = 0;
    }

    public boolean isAtRest() {
        return value == target && velocity == 0;
    }

    /** Advances dt seconds. Returns true while it is still moving. */
    public boolean step(float dt) {
        if (isAtRest()) {
            return false;
        }
        int steps = Math.max(1, (int) Math.ceil(dt / 0.004f));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            float accel = -stiffness * (value - target) - damping * velocity;
            velocity += accel * h;
            value += velocity * h;
        }
        if (Math.abs(value - target) < 0.0005f && Math.abs(velocity) < 0.003f) {
            value = target;
            velocity = 0;
            return false;
        }
        return true;
    }

    /** Spring curve for ViewPropertyAnimator & co. (settles exactly at the end of the duration). */
    public static final class SpringInterpolator implements Interpolator {
        private final float zeta;
        private final float omega;
        private final float omegaD;

        public SpringInterpolator(float dampingRatio) {
            zeta = Math.max(0.2f, Math.min(0.95f, dampingRatio));
            omega = 7f / zeta;
            omegaD = (float) (omega * Math.sqrt(1 - zeta * zeta));
        }

        @Override
        public float getInterpolation(float t) {
            if (t >= 1f) {
                return 1f;
            }
            double decay = Math.exp(-zeta * omega * t);
            return (float) (1 - decay * (Math.cos(omegaD * t) + (zeta * omega / omegaD) * Math.sin(omegaD * t)));
        }
    }

    /**
     * The one feel of the console UI: how fast the row scrolls, the focus lens travels and the
     * cards grow and step aside. All of them use this stiffness (critically damped), so things
     * that move together arrive together.
     */
    public static final float UI_STIFFNESS = 640f;
    /** Natural frequency of the UI spring (rad/s). */
    public static final float UI_OMEGA = (float) Math.sqrt(UI_STIFFNESS);
    /** About when the UI spring has settled (to a fraction of a pixel), for timed animations. */
    public static final long UI_DURATION_MS = Math.round(8.0 / UI_OMEGA * 1000);
    /** The UI spring's curve for ViewPropertyAnimator & co. (use with UI_DURATION_MS). */
    public static final Interpolator UI_CURVE = new CriticalInterpolator(8f);

    /**
     * The curve of a critically damped spring released from rest: no overshoot, eases in over
     * the first few frames and glides to a stop. omegaT = natural frequency x duration.
     */
    public static final class CriticalInterpolator implements Interpolator {
        private final double a;
        private final double norm;

        public CriticalInterpolator(float omegaT) {
            a = omegaT;
            norm = 1 - (1 + a) * Math.exp(-a);
        }

        @Override
        public float getInterpolation(float t) {
            if (t >= 1f) {
                return 1f;
            }
            if (t <= 0f) {
                return 0f;
            }
            double x = a * t;
            return (float) ((1 - (1 + x) * Math.exp(-x)) / norm);
        }
    }

    /** A soft settle with a hint of overshoot (cards, panels). */
    public static final Interpolator SOFT = new SpringInterpolator(0.78f);
    /** A livelier pop (radial selection, toggles). */
    public static final Interpolator POP = new SpringInterpolator(0.6f);
}
