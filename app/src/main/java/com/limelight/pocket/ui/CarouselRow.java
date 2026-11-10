package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A carousel row whose cards stay inside it on the side they leave from.
 *
 * Focus anchors at the start of the row, so the cards before it slide out towards the start
 * edge. Rows draw outside their bounds (the focused card grows, the lens glows), which let those
 * cards slide over whatever sits beside the row, like the PC card next to "Jump back in". Here
 * they fade out across the start padding instead: nothing ever covers the neighbour, and the edge
 * reads as soft rather than sliced. The fade needs an offscreen layer, so it is only used while a
 * card is actually crossing it (during the glide); at rest a plain clip does.
 *
 * Above, below and past the end the row still draws freely. Optionally it draws a hairline in the
 * middle of the start padding to separate it from what comes before.
 */
public class CarouselRow extends RecyclerView {

    /** Room above and below the row that the focused card and its glow may use. */
    private static final float OVERFLOW_DP = 48f;
    /** The first part of the start padding (0..1) hides cards outright; the rest fades them in. */
    private static final float HIDDEN = 0.5f;

    private final Paint mask = new Paint();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean divider;
    private int shaderKey;

    public CarouselRow(@NonNull Context context) {
        super(context);
        init();
    }

    public CarouselRow(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CarouselRow(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        line.setColor(0x29FFFFFF);
        setWillNotDraw(false);
    }

    /** Draws a short hairline in the middle of the start padding (a section break). */
    public void setStartDivider(boolean show) {
        if (divider != show) {
            divider = show;
            invalidate();
        }
    }

    private boolean rtl() {
        return getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    /** True when part of a card is in the fade (between the hidden part and the cards' slot). */
    private boolean cardInFade(float from, float to) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0f) {
                continue;
            }
            float sx = child.getScaleX();
            float left = child.getLeft() + child.getTranslationX() + child.getPivotX() * (1f - sx);
            float right = left + child.getWidth() * sx;
            // Only the card counts, not the empty padding of its frame
            if (child instanceof ViewGroup && ((ViewGroup) child).getChildCount() > 0) {
                View card = ((ViewGroup) child).getChildAt(0);
                float l = left + (card.getLeft() + card.getTranslationX()) * sx;
                right = l + card.getWidth() * sx;
                left = l;
            }
            if (right > from && left < to) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void draw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        int edge = getPaddingStart();
        if (edge <= 0 || w <= 0 || h <= 0) {
            super.draw(canvas);
            return;
        }
        boolean rtl = rtl();
        float extra = OVERFLOW_DP * getResources().getDisplayMetrics().density;
        float top = -extra;
        float bottom = h + extra;
        // Before the fade nothing shows; past it the row may overflow freely
        float hidden = edge * HIDDEN;
        float clipLeft = rtl ? -w : hidden;
        float clipRight = rtl ? w - hidden : 2f * w;
        float fadeFrom = rtl ? w - edge : hidden;
        float fadeTo = rtl ? w - hidden : edge;

        int save;
        boolean fade = cardInFade(fadeFrom, fadeTo);
        if (fade) {
            save = canvas.saveLayer(clipLeft, top, clipRight, bottom, null);
        }
        else {
            save = canvas.save();
        }
        canvas.clipRect(clipLeft, top, clipRight, bottom);
        super.draw(canvas);

        if (fade) {
            // Cards dissolve towards the hidden part (the row's own lens and dimming with them)
            int key = (w * 31 + edge) * (rtl ? -1 : 1);
            if (key != shaderKey || mask.getShader() == null) {
                shaderKey = key;
                float gone = rtl ? w - hidden : hidden;
                float full = rtl ? w - edge : edge;
                mask.setShader(new LinearGradient(gone, 0, full, 0, 0xFF000000, 0x00000000,
                        Shader.TileMode.CLAMP));
            }
            canvas.drawRect(fadeFrom, top, fadeTo, bottom, mask);
        }
        canvas.restoreToCount(save);

        if (divider) {
            float d = getResources().getDisplayMetrics().density;
            float x = rtl ? w - edge / 2f : edge / 2f;
            float half = Math.min(48f * d, h * 0.3f);
            canvas.drawRect(x - d / 2f, h / 2f - half, x + d / 2f, h / 2f + half, line);
        }
    }
}
