package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * "Looking for your PCs": soft rings of light ripple out from a glowing point, one after
 * another, like a beacon. Replaces the spinner. Still when animations are off.
 */
public class PulseRingsView extends View {
    private static final float PERIOD = 2.4f;
    private static final int RINGS = 3;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accent;
    private final boolean animated;
    private final long start = SystemClock.uptimeMillis();

    public PulseRingsView(Context context) {
        this(context, null);
    }

    public PulseRingsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        accent = PocketTheme.accent(context);
        animated = com.limelight.pocket.PocketPrefs.animationsEnabled(context);
        float d = getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.5f * d);
        ring.setColor(accent);
        core.setColor(accent);
        halo.setColor(accent);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float max = Math.min(cx, cy) - ring.getStrokeWidth();
        float t = animated ? (SystemClock.uptimeMillis() - start) / 1000f : 0.6f;
        for (int i = 0; i < RINGS; i++) {
            float p = ((t / PERIOD) + i / (float) RINGS) % 1f;
            float eased = 1 - (1 - p) * (1 - p);
            float r = max * (0.16f + 0.84f * eased);
            ring.setAlpha(Math.round(200 * (1 - p) * (1 - p)));
            canvas.drawCircle(cx, cy, r, ring);
        }
        float breathe = 0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI / PERIOD);
        halo.setAlpha(Math.round(40 + 40 * breathe));
        canvas.drawCircle(cx, cy, max * (0.2f + 0.05f * breathe), halo);
        core.setAlpha(255);
        canvas.drawCircle(cx, cy, max * 0.09f, core);
        if (animated && isShown()) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            invalidate();
        }
    }
}
