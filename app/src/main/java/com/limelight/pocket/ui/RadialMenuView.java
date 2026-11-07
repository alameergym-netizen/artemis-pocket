package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.limelight.pocket.PocketAction;

/**
 * In-stream radial menu (black and white glass). Pick a slot with either stick or the D-pad;
 * the host activity decides what confirms the choice (releasing the held button, or A).
 */
public class RadialMenuView extends View {
    private static final float GAP_DEGREES = 3f;
    private static final float STICK_DEADZONE = 0.45f;

    private final Paint scrimPaint = new Paint();
    private final Paint slotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint centerBigPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint centerSmallPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path sector = new Path();
    private final RectF outer = new RectF();
    private final RectF inner = new RectF();

    private PocketAction[] slots = new PocketAction[0];
    private String[] labels = new String[0];
    private String centerTop = "";
    private String centerBottom = "";
    private int selected = -1;
    private boolean showing;

    public RadialMenuView(Context context) {
        super(context);
        float d = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        scrimPaint.setColor(0x8C000000);
        slotPaint.setColor(0x24FFFFFF);
        selectedPaint.setColor(0xFFFFFFFF);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(1f * d);
        ringPaint.setColor(0x33FFFFFF);
        centerPaint.setColor(0xE6101010);
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        labelPaint.setTypeface(medium);
        labelPaint.setTextSize(11.5f * sp);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        centerBigPaint.setTypeface(medium);
        centerBigPaint.setTextSize(18f * sp);
        centerBigPaint.setColor(0xFFFFFFFF);
        centerBigPaint.setTextAlign(Paint.Align.CENTER);
        centerSmallPaint.setTextSize(11f * sp);
        centerSmallPaint.setColor(0xA3FFFFFF);
        centerSmallPaint.setTextAlign(Paint.Align.CENTER);
        setVisibility(GONE);
        setFocusable(false);
        setClickable(true);
    }

    public boolean isShowing() {
        return showing;
    }

    public void show(PocketAction[] slots, String centerTop, String centerBottom) {
        this.slots = slots;
        this.labels = new String[slots.length];
        for (int i = 0; i < slots.length; i++) {
            labels[i] = slots[i] == PocketAction.NONE ? "" : slots[i].shortLabel(getContext());
        }
        this.centerTop = centerTop;
        this.centerBottom = centerBottom;
        this.selected = -1;
        this.showing = true;
        animate().cancel();
        setVisibility(VISIBLE);
        setAlpha(0f);
        setScaleX(0.94f);
        setScaleY(0.94f);
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        invalidate();
    }

    public void hide() {
        if (!showing) {
            return;
        }
        showing = false;
        animate().cancel();
        animate().alpha(0f).setDuration(110).withEndAction(() -> setVisibility(GONE)).start();
    }

    /** Selected slot, or -1 when the stick is centred / nothing chosen. */
    public int getSelected() {
        if (selected < 0 || selected >= slots.length || slots[selected] == PocketAction.NONE) {
            return -1;
        }
        return selected;
    }

    public PocketAction getSelectedAction() {
        int s = getSelected();
        return s < 0 ? null : slots[s];
    }

    /** Stick position in -1..1 (y down positive). */
    public void onStick(float x, float y) {
        if (!showing || slots.length == 0) {
            return;
        }
        float mag = (float) Math.hypot(x, y);
        if (mag < STICK_DEADZONE) {
            return; // keep the last choice so a released stick doesn't lose it
        }
        double angle = Math.toDegrees(Math.atan2(y, x)) + 90; // 0 = up, clockwise
        if (angle < 0) {
            angle += 360;
        }
        float step = 360f / slots.length;
        int slot = (int) Math.floor(((angle + step / 2) % 360) / step);
        select(slot);
    }

    /** D-pad: direction in 45 degree steps (0 = up, 2 = right, 4 = down, 6 = left). */
    public void onDirection(int eighth) {
        if (!showing || slots.length == 0) {
            return;
        }
        int slot = Math.round(eighth * slots.length / 8f) % slots.length;
        select(slot);
    }

    public void rotate(int delta) {
        if (!showing || slots.length == 0) {
            return;
        }
        int base = selected < 0 ? 0 : selected;
        select(((base + delta) % slots.length + slots.length) % slots.length);
    }

    private void select(int slot) {
        if (slot != selected) {
            selected = slot;
            PocketUi.tick(getContext());
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        canvas.drawRect(0, 0, w, h, scrimPaint);
        if (slots.length == 0) {
            return;
        }
        float cx = w / 2f;
        float cy = h / 2f;
        float outerR = Math.min(w, h) * 0.40f;
        float innerR = outerR * 0.44f;
        outer.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR);
        inner.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR);

        float step = 360f / slots.length;
        for (int i = 0; i < slots.length; i++) {
            float start = -90f + i * step - step / 2 + GAP_DEGREES / 2;
            float sweep = step - GAP_DEGREES;
            sector.reset();
            sector.arcTo(outer, start, sweep, true);
            sector.arcTo(inner, start + sweep, -sweep);
            sector.close();
            boolean isSelected = i == selected && slots[i] != PocketAction.NONE;
            canvas.drawPath(sector, isSelected ? selectedPaint : slotPaint);

            if (labels[i].isEmpty()) {
                continue;
            }
            double mid = Math.toRadians(-90f + i * step);
            float lr = (outerR + innerR) / 2f;
            float lx = cx + (float) (Math.cos(mid) * lr);
            float ly = cy + (float) (Math.sin(mid) * lr);
            labelPaint.setColor(isSelected ? 0xFF000000 : 0xE6FFFFFF);
            drawWrapped(canvas, labels[i], lx, ly, (outerR - innerR) * 0.95f);
        }

        canvas.drawCircle(cx, cy, outerR + 1, ringPaint);
        canvas.drawCircle(cx, cy, innerR - 6, centerPaint);
        canvas.drawCircle(cx, cy, innerR - 6, ringPaint);

        PocketAction chosen = getSelectedAction();
        String big = chosen != null ? labels[selected] : centerTop;
        String small = chosen != null ? "" : centerBottom;
        float bigY = cy - (centerBigPaint.descent() + centerBigPaint.ascent()) / 2;
        if (!small.isEmpty()) {
            bigY -= centerSmallPaint.getTextSize() * 0.6f;
        }
        drawFitted(canvas, big, cx, bigY, (innerR - 10) * 1.7f, centerBigPaint);
        if (!small.isEmpty()) {
            canvas.drawText(small, cx, bigY + centerSmallPaint.getTextSize() * 1.4f, centerSmallPaint);
        }
    }

    private void drawWrapped(Canvas canvas, String text, float x, float y, float maxWidth) {
        if (labelPaint.measureText(text) <= maxWidth || !text.contains(" ")) {
            float baseline = y - (labelPaint.descent() + labelPaint.ascent()) / 2;
            drawFitted(canvas, text, x, baseline, maxWidth, labelPaint);
            return;
        }
        int split = text.lastIndexOf(' ', text.length() / 2 + 3);
        if (split <= 0) {
            split = text.indexOf(' ');
        }
        String a = text.substring(0, split);
        String b = text.substring(split + 1);
        float lineH = labelPaint.getTextSize() * 1.15f;
        float base = y - (labelPaint.descent() + labelPaint.ascent()) / 2;
        drawFitted(canvas, a, x, base - lineH / 2, maxWidth, labelPaint);
        drawFitted(canvas, b, x, base + lineH / 2, maxWidth, labelPaint);
    }

    private void drawFitted(Canvas canvas, String text, float x, float baseline, float maxWidth, Paint paint) {
        float width = paint.measureText(text);
        if (width <= maxWidth || width == 0) {
            canvas.drawText(text, x, baseline, paint);
            return;
        }
        float original = paint.getTextSize();
        paint.setTextSize(original * maxWidth / width);
        canvas.drawText(text, x, baseline, paint);
        paint.setTextSize(original);
    }
}
