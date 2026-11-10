package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.limelight.pocket.PocketPrefs;

/**
 * A slim position track under a row of cards: a short bright thumb glides along a faint line as
 * the focus moves (critically damped, no overshoot). Hidden when there is nothing to scroll.
 */
public class RowIndicator extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Spring position = new Spring(420, 1f, 0);
    private final float d;
    private int count;
    private int index;
    private float alpha;
    private long lastFrame;

    public RowIndicator(Context context) {
        this(context, null);
    }

    public RowIndicator(Context context, AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        track.setColor(0x24FFFFFF);
        thumb.setColor(0xE6FFFFFF);
    }

    /** Where the focus is (0-based) among count cards. */
    public void set(int index, int count) {
        boolean first = this.count == 0;
        this.count = count;
        this.index = Math.max(0, Math.min(index, Math.max(0, count - 1)));
        thumb.setColor(PocketTheme.withAlpha(PocketTheme.accent(getContext()), 0xE6));
        float target = count > 1 ? this.index / (float) (count - 1) : 0f;
        if (first || !PocketPrefs.animationsEnabled(getContext())) {
            position.snap(target);
        }
        else {
            position.target = target;
        }
        lastFrame = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 1f / 60f : Math.min(1f / 30f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        boolean moving = position.step(dt);

        float alphaTarget = count > 1 ? 1f : 0f;
        if (alpha != alphaTarget) {
            alpha += (alphaTarget - alpha) * Math.min(1f, dt * 10f);
            if (Math.abs(alpha - alphaTarget) < 0.02f) {
                alpha = alphaTarget;
            }
            else {
                moving = true;
            }
        }
        if (alpha > 0.01f) {
            float h = 2f * d;
            float cy = getHeight() / 2f;
            float w = getWidth();
            track.setAlpha(Math.round(0x24 * alpha));
            rect.set(0, cy - h / 2f, w, cy + h / 2f);
            canvas.drawRoundRect(rect, h, h, track);

            float tw = Math.max(14 * d, Math.min(w, w * 3f / Math.max(3, count)));
            float x = (w - tw) * Math.max(0f, Math.min(1f, position.value));
            thumb.setAlpha(Math.round(0xE6 * alpha));
            rect.set(x, cy - h / 2f, x + tw, cy + h / 2f);
            canvas.drawRoundRect(rect, h, h, thumb);
        }
        if (moving) {
            postInvalidateOnAnimation();
        }
        else {
            lastFrame = 0;
        }
    }
}
