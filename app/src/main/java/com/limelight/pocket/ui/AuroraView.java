package com.limelight.pocket.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.limelight.pocket.PocketPrefs;

/**
 * Slowly drifting two-tone glow behind the console screens.
 * Cheap to draw (two radial gradients + a vignette) and paused when off screen.
 */
public class AuroraView extends View {
    private final Paint glowA = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowB = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vignette = new Paint();
    private ValueAnimator animator;
    private float phase;

    private int colorA = 0x445CF2D0;
    private int colorB = 0x558C6CFF;

    public AuroraView(Context context) {
        super(context);
        init();
    }

    public AuroraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Tint the glow, e.g. from the selected game's art. */
    public void setColors(int a, int b) {
        colorA = a;
        colorB = b;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!PocketPrefs.animationsEnabled(getContext())) {
            phase = 0.3f;
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(24000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (animator != null) {
            if (visibility == VISIBLE) {
                animator.resume();
            }
            else {
                animator.pause();
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        vignette.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x00000000, 0x00000000, 0xCC05070B},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        canvas.drawColor(Color.rgb(10, 13, 20));

        double t = phase * Math.PI * 2;
        float ax = (float) (w * (0.25 + 0.12 * Math.sin(t)));
        float ay = (float) (h * (0.30 + 0.10 * Math.cos(t * 1.3)));
        float bx = (float) (w * (0.78 + 0.10 * Math.cos(t * 0.8)));
        float by = (float) (h * (0.65 + 0.12 * Math.sin(t * 1.1)));
        float r = Math.max(w, h) * 0.55f;

        glowA.setShader(new RadialGradient(ax, ay, r, colorA, 0x00000000, Shader.TileMode.CLAMP));
        glowB.setShader(new RadialGradient(bx, by, r * 0.9f, colorB, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, glowA);
        canvas.drawRect(0, 0, w, h, glowB);
        canvas.drawRect(0, 0, w, h, vignette);
    }
}
