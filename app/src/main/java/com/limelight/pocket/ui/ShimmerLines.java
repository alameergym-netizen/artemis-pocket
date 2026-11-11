package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import com.limelight.pocket.PocketPrefs;

/**
 * Placeholder lines with a soft light passing over them, shown where text is on its way (the
 * game's description while it is looked up).
 */
public class ShimmerLines extends View {

    private static final float[] WIDTHS = {1f, 0.92f, 0.64f};

    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private LinearGradient shader;
    private int lines = 3;

    public ShimmerLines(Context context) {
        super(context);
        init();
    }

    public ShimmerLines(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        base.setColor(0x1FFFFFFF);
    }

    public void setLines(int lines) {
        this.lines = Math.max(1, lines);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        shader = new LinearGradient(0, 0, w * 0.35f, 0,
                new int[]{0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
        light.setShader(shader);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float gap = h / (float) lines;
        float bar = Math.min(gap * 0.46f, 9 * getResources().getDisplayMetrics().density);
        float radius = bar / 2f;
        boolean animate = PocketPrefs.animationsEnabled(getContext()) && isShown();
        float t = (SystemClock.uptimeMillis() % 1500) / 1500f;
        matrix.setTranslate(-w * 0.35f + t * w * 1.35f, 0);
        if (shader != null) {
            shader.setLocalMatrix(matrix);
        }
        for (int i = 0; i < lines; i++) {
            float top = i * gap + (gap - bar) / 2f;
            rect.set(0, top, w * WIDTHS[Math.min(i, WIDTHS.length - 1)], top + bar);
            canvas.drawRoundRect(rect, radius, radius, base);
            if (animate) {
                canvas.drawRoundRect(rect, radius, radius, light);
            }
        }
        if (animate) {
            postInvalidateOnAnimation();
        }
    }
}
