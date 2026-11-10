package com.limelight.pocket.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/** A small battery for the status bar: outline, fill to the level, a bolt while charging. */
public class BatteryGlyph extends Drawable {

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bolt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path boltPath = new Path();
    private final float d;
    private int level = 100;
    private boolean charging;
    private int color = 0xB3FFFFFF;

    public BatteryGlyph(float density) {
        d = density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.2f * d);
        fill.setStyle(Paint.Style.FILL);
        bolt.setStyle(Paint.Style.FILL);
        setBounds(0, 0, Math.round(21 * d), Math.round(10 * d));
    }

    public void set(int level, boolean charging, int color) {
        this.level = Math.max(0, Math.min(100, level));
        this.charging = charging;
        this.color = color;
        invalidateSelf();
    }

    @Override
    public int getIntrinsicWidth() {
        return Math.round(21 * d);
    }

    @Override
    public int getIntrinsicHeight() {
        return Math.round(10 * d);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        float w = getBounds().width();
        float h = getBounds().height();
        float left = getBounds().left;
        float top = getBounds().top;
        float sw = stroke.getStrokeWidth();
        float nub = 2f * d;

        stroke.setColor(color);
        rect.set(left + sw / 2f, top + sw / 2f, left + w - nub - sw / 2f, top + h - sw / 2f);
        canvas.drawRoundRect(rect, 2.6f * d, 2.6f * d, stroke);

        // The little terminal on the right
        fill.setColor(color);
        rect.set(left + w - nub, top + h * 0.32f, left + w, top + h * 0.68f);
        canvas.drawRoundRect(rect, 0.8f * d, 0.8f * d, fill);

        // Level
        float inset = sw + 1.1f * d;
        float inner = (w - nub - 2 * inset);
        rect.set(left + inset, top + inset, left + inset + inner * level / 100f, top + h - inset);
        fill.setColor(level <= 15 && !charging ? 0xFFFF6B6B : color);
        canvas.drawRoundRect(rect, 1.4f * d, 1.4f * d, fill);

        if (charging) {
            // A bolt cut through the middle
            float cx = left + (w - nub) / 2f;
            float cy = top + h / 2f;
            boltPath.reset();
            boltPath.moveTo(cx + 0.8f * d, cy - 4.2f * d);
            boltPath.lineTo(cx - 2.6f * d, cy + 0.6f * d);
            boltPath.lineTo(cx - 0.2f * d, cy + 0.6f * d);
            boltPath.lineTo(cx - 0.8f * d, cy + 4.2f * d);
            boltPath.lineTo(cx + 2.6f * d, cy - 0.6f * d);
            boltPath.lineTo(cx + 0.2f * d, cy - 0.6f * d);
            boltPath.close();
            bolt.setColor(0xFF000000);
            canvas.drawPath(boltPath, bolt);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        stroke.setAlpha(alpha);
        fill.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        stroke.setColorFilter(colorFilter);
        fill.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
