package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The stream's performance overlay, redrawn as a modern HUD: each number with what it is
 * (FPS, ping, decode, loss ...), a coloured dot that says at a glance whether it's fine (green),
 * borderline (amber) or a problem (red), and a small graph of the frame rate. Compact (one
 * line) or detailed (a second line: resolution, bitrate, bandwidth, battery, temperature).
 */
public class StreamHud extends View {

    public static final int GOOD = 0xFF4ADE80;
    public static final int WARN = 0xFFFBBF24;
    public static final int BAD = 0xFFF87171;
    public static final int NONE = 0x00000000;

    /** One number on the HUD. */
    public static final class Metric {
        final String label;
        final String value;
        final String unit;
        final int state;

        public Metric(String label, String value, String unit, int state) {
            this.label = label;
            this.value = value;
            this.unit = unit;
            this.state = state;
        }
    }

    private final float d;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint graph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint graphFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private List<Metric> top = new ArrayList<>();
    private List<Metric> bottom = new ArrayList<>();
    private final float[] history = new float[40];
    private int historyCount;
    private float target = 60;
    private boolean detailed;

    public StreamHud(Context context) {
        super(context);
        d = context.getResources().getDisplayMetrics().density;
        bg.setColor(0xB80C0C10);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, d * 0.75f));
        edge.setColor(0x24FFFFFF);
        labelPaint.setTypeface(PocketFonts.semibold());
        labelPaint.setTextSize(7.5f * d * context.getResources().getConfiguration().fontScale);
        labelPaint.setColor(0x8CFFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        valuePaint.setTypeface(PocketFonts.semibold());
        valuePaint.setTextSize(14f * d);
        valuePaint.setColor(0xFFFFFFFF);
        unitPaint.setTypeface(PocketFonts.medium());
        unitPaint.setTextSize(9.5f * d);
        unitPaint.setColor(0xA6FFFFFF);
        graph.setStyle(Paint.Style.STROKE);
        graph.setStrokeWidth(1.5f * d);
        graph.setStrokeJoin(Paint.Join.ROUND);
        graph.setColor(GOOD);
        graphFill.setColor(0x334ADE80);
        setWillNotDraw(false);
    }

    public void setDetailed(boolean on) {
        detailed = on;
        requestLayout();
        invalidate();
    }

    /** New numbers (once a second). fps is also kept for the graph. */
    public void update(List<Metric> first, List<Metric> second, float fps, float targetFps) {
        top = first;
        bottom = second != null ? second : new ArrayList<Metric>();
        target = targetFps > 0 ? targetFps : 60;
        System.arraycopy(history, 1, history, 0, history.length - 1);
        history[history.length - 1] = fps;
        historyCount = Math.min(history.length, historyCount + 1);
        requestLayout();
        invalidate();
    }

    private float cellWidth(Metric m) {
        float v = valuePaint.measureText(m.value) + (m.unit.isEmpty() ? 0 : unitPaint.measureText(m.unit) + 3 * d);
        float l = labelPaint.measureText(m.label.toUpperCase());
        return Math.max(v + (m.state != NONE ? 10 * d : 0), l) + 16 * d;
    }

    private float rowWidth(List<Metric> row) {
        float w = 0;
        for (Metric m : row) {
            w += cellWidth(m);
        }
        return w;
    }

    private float graphWidth() {
        return 54 * d;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float pad = 10 * d;
        float w = Math.max(rowWidth(top) + graphWidth(), detailed ? rowWidth(bottom) : 0) + pad * 2;
        float rowH = 34 * d;
        float h = rowH + (detailed && !bottom.isEmpty() ? rowH : 0) + pad;
        setMeasuredDimension(resolveSize(Math.round(w), widthMeasureSpec), resolveSize(Math.round(h), heightMeasureSpec));
    }

    private void drawRow(Canvas c, List<Metric> row, float x, float y) {
        for (Metric m : row) {
            float cw = cellWidth(m);
            c.drawText(m.label.toUpperCase(), x, y + 10 * d, labelPaint);
            float vx = x;
            if (m.state != NONE) {
                dot.setColor(m.state);
                c.drawCircle(x + 3 * d, y + 21.5f * d, 3 * d, dot);
                vx += 10 * d;
            }
            c.drawText(m.value, vx, y + 26 * d, valuePaint);
            if (!m.unit.isEmpty()) {
                c.drawText(m.unit, vx + valuePaint.measureText(m.value) + 3 * d, y + 26 * d, unitPaint);
            }
            x += cw;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        rect.set(0, 0, w, h);
        float r = 12 * d;
        canvas.drawRoundRect(rect, r, r, bg);
        rect.inset(edge.getStrokeWidth() / 2, edge.getStrokeWidth() / 2);
        canvas.drawRoundRect(rect, r, r, edge);
        float pad = 10 * d;
        drawRow(canvas, top, pad, pad * 0.6f);
        // The frame rate over the last seconds
        if (historyCount > 1) {
            float gx = pad + rowWidth(top) + 4 * d;
            float gw = graphWidth() - 8 * d;
            float gy = pad * 0.6f + 6 * d;
            float gh = 22 * d;
            float max = Math.max(target * 1.1f, 1f);
            path.reset();
            int start = history.length - historyCount;
            boolean low = false;
            for (int i = start; i < history.length; i++) {
                float px = gx + gw * (i - start) / (float) (historyCount - 1);
                float py = gy + gh - gh * Math.min(1f, history[i] / max);
                if (i == start) {
                    path.moveTo(px, py);
                }
                else {
                    path.lineTo(px, py);
                }
                low |= history[i] < target * 0.8f;
            }
            graph.setColor(low ? WARN : GOOD);
            canvas.drawPath(path, graph);
        }
        if (detailed && !bottom.isEmpty()) {
            drawRow(canvas, bottom, pad, pad * 0.6f + 34 * d);
        }
    }

    // ---- How each number is judged -------------------------------------------------------

    public static int fpsState(float fps, float target) {
        if (fps <= 0) {
            return NONE;
        }
        return fps >= target * 0.95f ? GOOD : (fps >= target * 0.8f ? WARN : BAD);
    }

    public static int pingState(float ms) {
        if (ms <= 0) {
            return NONE;
        }
        return ms < 20 ? GOOD : (ms < 45 ? WARN : BAD);
    }

    public static int decodeState(float ms, float target) {
        if (ms <= 0) {
            return NONE;
        }
        float frame = 1000f / Math.max(1f, target);
        return ms < frame * 0.5f ? GOOD : (ms < frame ? WARN : BAD);
    }

    public static int lossState(float pct) {
        return pct < 1f ? GOOD : (pct < 5f ? WARN : BAD);
    }
}
