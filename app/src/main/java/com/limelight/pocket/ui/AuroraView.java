package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * The living backdrop of the console screens: black with three soft pools of light (tinted by
 * the accent) that drift very slowly, like light moving behind frosted glass, plus a bottom
 * vignette. The shaders are built once per size and only moved with a matrix, and it redraws
 * at a calm 30 fps, so it costs next to nothing. Still when animations are off.
 */
public class AuroraView extends View {
    private final Paint glowA = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowB = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowC = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vignette = new Paint();
    private final Matrix matrix = new Matrix();

    private int colorA = 0x26FFFFFF;
    private int colorB = 0x17FFFFFF;
    private int colorC = 0x14FFFFFF;
    private boolean pureBlack;
    private boolean drifting;
    private int baseA;
    private int baseB;
    private android.animation.ValueAnimator tintAnimator;
    private long startMs;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
        }
    };

    public AuroraView(Context context) {
        super(context);
        init();
    }

    public AuroraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        String backdrop = com.limelight.pocket.PocketPrefs.backdrop(getContext());
        pureBlack = com.limelight.pocket.PocketPrefs.BACKDROP_BLACK.equals(backdrop);
        int accent = com.limelight.pocket.PocketPrefs.accentColor(getContext());
        colorA = PocketTheme.withAlpha(accent, 0x2A);
        colorB = PocketTheme.withAlpha(accent, 0x18);
        colorC = PocketTheme.withAlpha(0xFFFFFFFF, 0x12);
        baseA = colorA;
        baseB = colorB;
        drifting = !pureBlack && com.limelight.pocket.PocketPrefs.animationsEnabled(getContext());
        startMs = SystemClock.uptimeMillis() - (long) (Math.random() * 60000);
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

    /**
     * Ambient light: the glows take on a game's colour (0 = back to the theme's own light),
     * blending over 0.7 s.
     */
    public void tint(int color) {
        if (pureBlack) {
            return;
        }
        final int toA;
        final int toB;
        if (color == 0) {
            toA = baseA;
            toB = baseB;
        }
        else {
            float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            toA = PocketTheme.withAlpha(color, 0x78);
            // The second pool: a neighbouring, deeper hue, so the light has some depth
            hsv[0] = (hsv[0] + 330f) % 360f;
            hsv[2] = hsv[2] * 0.8f;
            toB = PocketTheme.withAlpha(Color.HSVToColor(hsv), 0x50);
        }
        if (tintAnimator != null) {
            tintAnimator.cancel();
        }
        final int fromA = colorA;
        final int fromB = colorB;
        if (fromA == toA && fromB == toB) {
            return;
        }
        if (!com.limelight.pocket.PocketPrefs.animationsEnabled(getContext()) || getWidth() == 0) {
            setColors(toA, toB);
            return;
        }
        final android.animation.ArgbEvaluator mix = new android.animation.ArgbEvaluator();
        tintAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        tintAnimator.setDuration(700);
        tintAnimator.setInterpolator(PocketMotion.DECELERATE);
        tintAnimator.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            colorA = (Integer) mix.evaluate(f, fromA, toA);
            colorB = (Integer) mix.evaluate(f, fromB, toB);
            buildShaders(getWidth(), getHeight());
            invalidate();
        });
        tintAnimator.start();
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
        float r = Math.max(w, h) * 0.7f;
        // Centred at the origin; onDraw moves them
        glowA.setShader(new RadialGradient(0, 0, r, new int[]{colorA, PocketTheme.withAlpha(colorA, Color.alpha(colorA) / 3), 0},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        glowB.setShader(new RadialGradient(0, 0, r * 0.9f, new int[]{colorB, PocketTheme.withAlpha(colorB, Color.alpha(colorB) / 3), 0},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        glowC.setShader(new RadialGradient(0, 0, r * 0.55f, colorC, 0x00000000, Shader.TileMode.CLAMP));
        vignette.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x00000000, 0x00000000, 0xE6000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startMs = SystemClock.uptimeMillis() - (long) (Math.random() * 60000);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        if (tintAnimator != null) {
            tintAnimator.cancel();
        }
        super.onDetachedFromWindow();
    }

    private void place(Paint paint, float x, float y) {
        matrix.setTranslate(x, y);
        paint.getShader().setLocalMatrix(matrix);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        canvas.drawColor(Color.BLACK);
        if (pureBlack || glowA.getShader() == null) {
            return;
        }
        double t = drifting ? (SystemClock.uptimeMillis() - startMs) / 1000.0 : 0;
        // Slow, unrelated periods so the light never visibly loops
        // The main light blooms top right (behind where a game's key art sits, away from the
        // hero text on the left); a second, deeper one rises from the lower right
        place(glowA, (float) (w * (0.74 + 0.08 * Math.sin(t / 23.0))), (float) (h * (0.02 + 0.07 * Math.sin(t / 17.0 + 1))));
        place(glowB, (float) (w * (0.96 + 0.05 * Math.sin(t / 29.0 + 2))), (float) (h * (0.78 + 0.06 * Math.cos(t / 19.0))));
        place(glowC, (float) (w * (0.55 + 0.22 * Math.sin(t / 37.0 + 4))), (float) (h * (0.45 + 0.15 * Math.cos(t / 31.0 + 3))));
        canvas.drawRect(0, 0, w, h, glowA);
        canvas.drawRect(0, 0, w, h, glowB);
        canvas.drawRect(0, 0, w, h, glowC);
        canvas.drawRect(0, 0, w, h, vignette);
        if (drifting && isShown()) {
            removeCallbacks(tick);
            postDelayed(tick, 33);
        }
    }
}
