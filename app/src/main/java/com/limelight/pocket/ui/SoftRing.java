package com.limelight.pocket.ui;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;

import androidx.annotation.NonNull;

/**
 * The focus, everywhere the same: a white line just outside what's focused, its edge a little
 * soft, with a glow of light around it (in the theme's colour). Drawn outside its bounds, so its
 * parents must not clip.
 */
public final class SoftRing extends Drawable {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float out;
    private final float radius;
    private final boolean pill;
    private int alpha = 255;

    /** Around something with rounded corners (radius in px); pill: fully round ends. */
    public SoftRing(float density, float radius, boolean pill, int glowColor) {
        this.out = 4f * density;
        this.radius = radius + out;
        this.pill = pill;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.4f * density);
        line.setColor(0xF2FFFFFF);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(5f * density);
        halo.setColor((glowColor & 0x00FFFFFF) | 0x80000000);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            line.setMaskFilter(new BlurMaskFilter(0.9f * density, BlurMaskFilter.Blur.NORMAL));
            halo.setMaskFilter(new BlurMaskFilter(9f * density, BlurMaskFilter.Blur.NORMAL));
        }
    }

    public static SoftRing forTheme(android.content.Context c, float radius, boolean pill) {
        return new SoftRing(c.getResources().getDisplayMetrics().density, radius, pill, PocketTheme.accent(c));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        r.set(getBounds());
        r.inset(-out, -out);
        float rad = pill ? r.height() / 2f : radius;
        canvas.drawRoundRect(r, rad, rad, halo);
        canvas.drawRoundRect(r, rad, rad, line);
    }

    @Override
    public void setAlpha(int a) {
        alpha = a;
        line.setAlpha(Math.round(0xF2 * a / 255f));
        halo.setAlpha(Math.round(0x80 * a / 255f));
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
