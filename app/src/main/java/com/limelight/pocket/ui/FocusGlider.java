package com.limelight.pocket.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;

import com.limelight.pocket.PocketPrefs;

/**
 * A lit highlight that glides between the items of a list as focus moves (settings rows,
 * tabs), stretching to each item's size with spring physics, instead of each item lighting up
 * on its own. It is drawn as the list's background, over whatever background it had, so it
 * sits under the items and scrolls with them.
 */
public final class FocusGlider extends Drawable implements ViewTreeObserver.OnGlobalFocusChangeListener {
    private final ViewGroup host;
    private final Drawable inner;
    private final float radius;
    private final int color;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Spring x = new Spring(560f, 0.84f, 0f);
    private final Spring y = new Spring(560f, 0.84f, 0f);
    private final Spring w = new Spring(560f, 0.84f, 0f);
    private final Spring h = new Spring(560f, 0.84f, 0f);
    private final Spring shown = new Spring(400f, 1f, 0f);
    private final RectF rect = new RectF();
    private final boolean animated;
    private View target;
    private boolean placed;
    private long lastFrame;

    private FocusGlider(ViewGroup host, int color, float radius) {
        this.host = host;
        this.inner = host.getBackground();
        this.color = color;
        this.radius = radius;
        this.animated = PocketPrefs.animationsEnabled(host.getContext());
        paint.setColor(color);
        float d = host.getResources().getDisplayMetrics().density;
        glow.setColor(PocketTheme.withAlpha(color, 0x2E));
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(4 * d);
    }

    /** Puts a gliding highlight under the focusable children of host. */
    public static FocusGlider install(ViewGroup host, int color, float radius) {
        FocusGlider glider = new FocusGlider(host, color, radius);
        host.setBackground(glider);
        host.getViewTreeObserver().addOnGlobalFocusChangeListener(glider);
        return glider;
    }

    private View childHolding(View focused) {
        View v = focused;
        while (v != null) {
            ViewParent parent = v.getParent();
            if (parent == host) {
                return v;
            }
            v = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    @Override
    public void onGlobalFocusChanged(View oldFocus, View newFocus) {
        View child = childHolding(newFocus);
        target = child;
        if (child != null) {
            moveTo(child, !placed || shown.value < 0.05f || !animated);
            shown.target = 1f;
        }
        else {
            shown.target = 0f;
        }
        if (!animated) {
            shown.snap(shown.target);
        }
        lastFrame = 0;
        invalidateSelf();
    }

    private void moveTo(View child, boolean snap) {
        if (snap) {
            x.snap(child.getLeft());
            y.snap(child.getTop());
            w.snap(child.getWidth());
            h.snap(child.getHeight());
        }
        else {
            x.target = child.getLeft();
            y.target = child.getTop();
            w.target = child.getWidth();
            h.target = child.getHeight();
        }
        placed = true;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (inner != null) {
            inner.draw(canvas);
        }
        // Follow the target if the list re-laid out (rows rebuilt, scroll, resize)
        if (target != null && target.getParent() == host
                && (x.target != target.getLeft() || y.target != target.getTop()
                || w.target != target.getWidth() || h.target != target.getHeight())) {
            moveTo(target, !animated);
        }
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean moving = x.step(dt);
        moving |= y.step(dt);
        moving |= w.step(dt);
        moving |= h.step(dt);
        moving |= shown.step(dt);
        float a = Math.max(0f, Math.min(1f, shown.value));
        if (a > 0.01f && w.value > 1 && h.value > 1) {
            // Stretch a little while travelling, like something with weight
            float speed = Math.abs(y.velocity) + Math.abs(x.velocity);
            float stretch = Math.min(1f, speed / 4000f);
            float insetX = -2f * stretch;
            rect.set(x.value + insetX, y.value, x.value + w.value - insetX, y.value + h.value);
            int base = PocketTheme.withAlpha(color, Math.round(((color >>> 24) & 0xFF) * a));
            paint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                    base, PocketTheme.withAlpha(base, Math.round(((base >>> 24) & 0xFF) * 0.9f)), Shader.TileMode.CLAMP));
            float r = Math.min(radius, rect.height() / 2f);
            if (a > 0.5f) {
                glow.setAlpha(Math.round(0x2E * a));
                canvas.drawRoundRect(rect.left - 2, rect.top - 2, rect.right + 2, rect.bottom + 2, r + 2, r + 2, glow);
            }
            canvas.drawRoundRect(rect, r, r, paint);
        }
        if (moving) {
            invalidateSelf();
        }
        else {
            lastFrame = 0;
        }
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        if (inner != null) {
            inner.setBounds(bounds);
        }
    }

    @Override
    public void getOutline(@NonNull Outline outline) {
        if (inner != null) {
            inner.getOutline(outline);
        }
        else {
            super.getOutline(outline);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        if (inner != null) {
            inner.setAlpha(alpha);
        }
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        if (inner != null) {
            inner.setColorFilter(colorFilter);
        }
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
