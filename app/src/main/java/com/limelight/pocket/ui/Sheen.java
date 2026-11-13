package com.limelight.pocket.ui;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/**
 * A band of light that sweeps once across a tile when it's chosen (a console's "shine"), drawn
 * on top of the tile's picture (its foreground) and clipped to its rounded corners. Nothing is
 * drawn when it isn't playing.
 */
public final class Sheen extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private float radius;
    private float progress = -1f;
    private ValueAnimator anim;

    public Sheen(float radiusPx) {
        radius = radiusPx;
        paint.setShader(new LinearGradient(-1f, 0f, 1f, 0f,
                new int[]{0x00FFFFFF, 0x14FFFFFF, 0x4DFFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.3f, 0.5f, 0.7f, 1f}, Shader.TileMode.CLAMP));
    }

    public void setRadius(float radiusPx) {
        if (radius != radiusPx) {
            radius = radiusPx;
            invalidateSelf();
        }
    }

    /** One sweep, left to right (any sweep still going is restarted). */
    public void play() {
        stop();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(720);
        anim.setInterpolator(PocketUi.EASE);
        anim.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                progress = -1f;
                invalidateSelf();
            }
        });
        anim.start();
    }

    public void stop() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        if (progress >= 0f) {
            progress = -1f;
            invalidateSelf();
        }
    }

    @Override
    public void draw(@NonNull Canvas c) {
        if (progress < 0f) {
            return;
        }
        rect.set(getBounds());
        if (rect.width() <= 0 || rect.height() <= 0) {
            return;
        }
        float band = rect.width() * 0.6f;
        float x = rect.left - band + (rect.width() + 2 * band) * progress;
        matrix.reset();
        matrix.setScale(band, band);
        matrix.postRotate(-20f);
        matrix.postTranslate(x, rect.centerY());
        paint.getShader().setLocalMatrix(matrix);
        // Softer at both ends of the sweep
        paint.setAlpha(Math.round(255 * (1f - Math.abs(2 * progress - 1) * 0.4f)));
        c.save();
        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        c.clipPath(clip);
        c.drawRect(rect, paint);
        c.restore();
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
