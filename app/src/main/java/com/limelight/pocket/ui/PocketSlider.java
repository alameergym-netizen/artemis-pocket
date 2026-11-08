package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * A console slider (0-100): a glass track, a lit fill and a knob that glides to the new value
 * with spring motion. Follows its row's focus (dark on the light focused row, accent on glass
 * otherwise). Driven by left/right from its row, or dragged by touch.
 */
public class PocketSlider extends View {

    public interface Listener {
        void onSliderChanged(int value);
    }

    private final float d;
    private final int accent;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Spring shown = new Spring(420f, 0.82f, 0f);
    private final Spring grow = new Spring(500f, 0.7f, 0f);
    private int value;
    private long lastFrame;
    private Listener listener;

    public PocketSlider(Context context) {
        super(context);
        d = getResources().getDisplayMetrics().density;
        accent = PocketTheme.accent(context);
        setFocusable(false);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int v, boolean animate) {
        value = Math.max(0, Math.min(100, v));
        if (animate && com.limelight.pocket.PocketPrefs.animationsEnabled(getContext())) {
            shown.target = value / 100f;
            // A small kick so each step feels like a notch
            grow.velocity += 6f;
        }
        else {
            shown.snap(value / 100f);
        }
        lastFrame = 0;
        invalidate();
    }

    private boolean focused() {
        for (int s : getDrawableState()) {
            if (s == android.R.attr.state_focused) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        grow.target = focused() ? 1f : 0f;
        lastFrame = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean moving = shown.step(dt);
        moving |= grow.step(dt);

        boolean focus = focused();
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        float knobR = (7f + 2.5f * Math.max(0f, grow.value)) * d;
        float left = getPaddingLeft() + knobR;
        float right = getWidth() - getPaddingRight() - knobR;
        float cy = getHeight() / 2f;
        float h = 5 * d;
        float frac = Math.max(0f, Math.min(1f, shown.value));
        float x = rtl ? right - (right - left) * frac : left + (right - left) * frac;

        // Track (glass)
        track.setColor(focus ? 0x24000000 : 0x29FFFFFF);
        rect.set(left - knobR * 0.4f, cy - h / 2, right + knobR * 0.4f, cy + h / 2);
        canvas.drawRoundRect(rect, h / 2, h / 2, track);

        // Fill
        int c1 = focus ? 0xFF1A1A1A : PocketTheme.withAlpha(accent, 0xB3);
        int c2 = focus ? 0xFF000000 : accent;
        if (rtl) {
            rect.set(x, cy - h / 2, right + knobR * 0.4f, cy + h / 2);
            fill.setShader(new LinearGradient(rect.right, 0, rect.left, 0, c1, c2, Shader.TileMode.CLAMP));
        }
        else {
            rect.set(left - knobR * 0.4f, cy - h / 2, x, cy + h / 2);
            fill.setShader(new LinearGradient(rect.left, 0, rect.right, 0, c1, c2, Shader.TileMode.CLAMP));
        }
        if (rect.width() > 0) {
            canvas.drawRoundRect(rect, h / 2, h / 2, fill);
        }

        // Knob with a soft halo when focused
        if (grow.value > 0.02f) {
            halo.setColor(focus ? 0x1F000000 : PocketTheme.withAlpha(accent, 0x33));
            canvas.drawCircle(x, cy, knobR * (1.7f + 0.2f * grow.value), halo);
        }
        knob.setColor(focus ? 0xFF000000 : 0xFFFFFFFF);
        knob.setShadowLayer(3 * d, 0, d, 0x66000000);
        canvas.drawCircle(x, cy, knobR, knob);
        if (focus) {
            knob.clearShadowLayer();
            knob.setColor(0xFFFFFFFF);
            canvas.drawCircle(x, cy, knobR * 0.32f, knob);
        }

        if (moving) {
            postInvalidateOnAnimation();
        }
        else {
            lastFrame = 0;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                // fall through
            case MotionEvent.ACTION_MOVE: {
                float knobR = 9.5f * d;
                float left = getPaddingLeft() + knobR;
                float right = getWidth() - getPaddingRight() - knobR;
                float frac = (event.getX() - left) / Math.max(1f, right - left);
                if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) {
                    frac = 1 - frac;
                }
                int v = Math.round(Math.max(0f, Math.min(1f, frac)) * 20) * 5;
                if (v != value) {
                    setValue(v, true);
                    if (listener != null) {
                        listener.onSliderChanged(v);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }
}
