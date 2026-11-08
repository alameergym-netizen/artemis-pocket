package com.limelight.pocket.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * Live stream graphs for the in-stream menu: frame rate, ping and decode time over the last
 * minute, each as a small sparkline with a soft fill, on a glass strip. New samples slide in
 * from the right. Ping turns warm when it climbs, so a bad patch is easy to spot.
 */
public class PerfGraphView extends View {
    private static final int SAMPLES = 60;
    public static final int FPS = 0;
    public static final int PING = 1;
    public static final int DECODE = 2;

    private final float d;
    private final int accent;
    private final float[][] data = new float[3][SAMPLES];
    private int count;
    private final String[] labels;
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint divider = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path fill = new Path();
    private final RectF rect = new RectF();
    private float shift;
    private ValueAnimator slide;

    public PerfGraphView(Context context, String fpsLabel, String pingLabel, String decodeLabel) {
        super(context);
        d = getResources().getDisplayMetrics().density;
        float sd = getResources().getDisplayMetrics().scaledDensity;
        accent = PocketTheme.accent(context);
        labels = new String[]{fpsLabel, pingLabel, decodeLabel};
        glass.setColor(0x14FFFFFF);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, d));
        rim.setColor(0x1FFFFFFF);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.6f * d);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        label.setColor(0x8CFFFFFF);
        label.setTextSize(8.5f * sd);
        label.setLetterSpacing(0.14f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        value.setColor(0xFFFFFFFF);
        value.setTextSize(14f * sd);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        divider.setColor(0x14FFFFFF);
        divider.setStrokeWidth(Math.max(1f, d));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, Math.round(62 * d));
    }

    /** One sample per second: fps, ping (ms) and decode time (ms). */
    public void push(float fps, float pingMs, float decodeMs) {
        for (int k = 0; k < 3; k++) {
            System.arraycopy(data[k], 1, data[k], 0, SAMPLES - 1);
        }
        data[FPS][SAMPLES - 1] = fps;
        data[PING][SAMPLES - 1] = pingMs;
        data[DECODE][SAMPLES - 1] = decodeMs;
        count = Math.min(SAMPLES, count + 1);
        if (!isShown()) {
            shift = 0;
            return;
        }
        if (slide != null) {
            slide.cancel();
        }
        slide = ValueAnimator.ofFloat(1f, 0f);
        slide.setDuration(520);
        slide.setInterpolator(PocketUi.EASE);
        slide.addUpdateListener(a -> {
            shift = (Float) a.getAnimatedValue();
            invalidate();
        });
        slide.start();
    }

    public boolean hasData() {
        return count > 0;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float r = 14 * d;
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, glass);
        rect.inset(rim.getStrokeWidth() / 2, rim.getStrokeWidth() / 2);
        canvas.drawRoundRect(rect, r, r, rim);
        float col = w / 3f;
        for (int k = 0; k < 3; k++) {
            float x0 = col * k;
            if (k > 0) {
                canvas.drawLine(x0, 10 * d, x0, h - 10 * d, divider);
            }
            drawMetric(canvas, k, x0 + 10 * d, x0 + col - 10 * d, h);
        }
    }

    private void drawMetric(Canvas canvas, int k, float left, float right, float h) {
        float[] v = data[k];
        float current = count > 0 ? v[SAMPLES - 1] : 0;
        boolean warn = (k == PING && current > 40) || (k == DECODE && current > 12);
        int color = warn ? 0xFFFFB55C : accent;
        canvas.drawText(labels[k], left, 14 * d, label);
        String text = count == 0 ? "–" : (k == FPS ? String.valueOf(Math.round(current))
                : (current < 10 ? String.format(java.util.Locale.ROOT, "%.1f", current) : String.valueOf(Math.round(current))));
        canvas.drawText(text, left, 31 * d, value);
        if (k != FPS) {
            float tw = value.measureText(text);
            canvas.drawText("ms", left + tw + 3 * d, 31 * d, label);
        }
        if (count < 2) {
            return;
        }
        // Scale: fps against its own peak, times against a sensible floor
        float max = k == FPS ? 30f : 8f;
        for (int i = SAMPLES - count; i < SAMPLES; i++) {
            max = Math.max(max, v[i]);
        }
        max *= 1.15f;
        float top = 38 * d;
        float bottom = h - 7 * d;
        float step = (right - left) / (SAMPLES - 1);
        path.reset();
        fill.reset();
        float lastX = 0;
        float lastY = 0;
        boolean first = true;
        for (int i = SAMPLES - count; i < SAMPLES; i++) {
            float x = left + step * (i + shift);
            if (x > right) {
                continue;
            }
            float y = bottom - (bottom - top) * Math.max(0f, Math.min(1f, v[i] / max));
            if (first) {
                path.moveTo(x, y);
                fill.moveTo(x, bottom);
                fill.lineTo(x, y);
                first = false;
            }
            else {
                path.lineTo(x, y);
                fill.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
        }
        if (first) {
            return;
        }
        fill.lineTo(lastX, bottom);
        fill.close();
        area.setShader(new LinearGradient(0, top, 0, bottom,
                PocketTheme.withAlpha(color, 0x40), PocketTheme.withAlpha(color, 0), Shader.TileMode.CLAMP));
        canvas.drawPath(fill, area);
        line.setColor(color);
        canvas.drawPath(path, line);
        dot.setColor(color);
        canvas.drawCircle(lastX, lastY, 2.4f * d, dot);
    }
}
