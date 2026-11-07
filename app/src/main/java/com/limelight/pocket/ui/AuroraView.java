package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Still backdrop for the console screens: pure black with two faint white glows and a soft
 * bottom vignette. Shaders are built once per size, so drawing costs nothing per frame.
 */
public class AuroraView extends View {
    private final Paint glowA = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowB = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vignette = new Paint();

    private int colorA = 0x1FFFFFFF;
    private int colorB = 0x12FFFFFF;

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

    /** Tint the glows (kept for callers; the mono theme leaves them white). */
    public void setColors(int a, int b) {
        colorA = a;
        colorB = b;
        buildShaders(getWidth(), getHeight());
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildShaders(w, h);
    }

    private void buildShaders(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        float r = Math.max(w, h) * 0.6f;
        glowA.setShader(new RadialGradient(w * 0.2f, h * 0.02f, r, colorA, 0x00000000, Shader.TileMode.CLAMP));
        glowB.setShader(new RadialGradient(w * 0.85f, h * 0.98f, r * 0.9f, colorB, 0x00000000, Shader.TileMode.CLAMP));
        vignette.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x00000000, 0x00000000, 0xE6000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        canvas.drawColor(Color.BLACK);
        if (glowA.getShader() == null) {
            return;
        }
        canvas.drawRect(0, 0, w, h, glowA);
        canvas.drawRect(0, 0, w, h, glowB);
        canvas.drawRect(0, 0, w, h, vignette);
    }
}
