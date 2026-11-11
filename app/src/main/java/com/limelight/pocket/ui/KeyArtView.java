package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * A game's key art across the whole screen, the way the new consoles show it: scaled to cover
 * (never stretched), the subject kept to the right where the name and buttons don't sit, and
 * faded into the background with a long, soft mask (the art itself turns transparent, so there's
 * never a hard edge, whatever the picture's size). A portrait picture (a cover used as the
 * backdrop) stands at the right, its left side melting away, instead of being blown up.
 */
public class KeyArtView extends View {

    /** How much of the height the art covers before it has faded out. */
    private static final float AREA = 0.86f;
    /** Below this width / height the picture is a portrait (a cover). */
    private static final float PORTRAIT = 1.25f;

    private Bitmap bitmap;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final RectF dst = new RectF();
    private Shader vertical;
    private Shader horizontal;
    private boolean portraitShaders;
    private float shaderW;
    private float shaderH;
    private float shaderLeft;

    public KeyArtView(Context context) {
        this(context, null);
    }

    public KeyArtView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // Its own layer: the mask takes away from the art only, not from what's behind
        setLayerType(LAYER_TYPE_HARDWARE, null);
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setImageBitmap(Bitmap b) {
        bitmap = b;
        vertical = null;
        invalidate();
    }

    public Bitmap getBitmap() {
        return bitmap;
    }

    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidate();
    }

    public void clearColorFilter() {
        setColorFilter(null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        vertical = null;
    }

    private void layoutArt(float w, float h) {
        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();
        float area = h * AREA;
        if (bw / bh < PORTRAIT) {
            // A cover: full height at the right
            float sh = area * 1.02f;
            float sw = bw * (sh / bh);
            if (sw > w * 0.62f) {
                sw = w * 0.62f;
                sh = bh * (sw / bw);
            }
            dst.set(w - sw, 0, w, sh);
            return;
        }
        float scale = Math.max(w / bw, area / bh);
        float sw = bw * scale;
        float sh = bh * scale;
        // Wider than the screen: crop more on the left (the subject tends to the right of a
        // hero picture, and the name sits on the left anyway); taller: crop more at the bottom
        float dx = (w - sw) * 0.62f;
        float dy = (area - sh) * 0.22f;
        dst.set(dx, dy, dx + sw, dy + sh);
    }

    private void buildMask(float w, float h, boolean portrait) {
        float area = h * AREA;
        // An eased fade (smooth, no visible band where it starts or ends)
        vertical = new LinearGradient(0, 0, 0, Math.min(area, dst.bottom), new int[] {
                0xFF000000, 0xFF000000, 0xE6000000, 0xA6000000, 0x59000000, 0x1F000000, 0x00000000},
                new float[] {0f, 0.38f, 0.55f, 0.70f, 0.83f, 0.93f, 1f}, Shader.TileMode.CLAMP);
        if (portrait) {
            float left = dst.left;
            float span = (dst.right - dst.left) * 0.55f;
            horizontal = new LinearGradient(left, 0, left + span, 0, new int[] {
                    0x00000000, 0x4D000000, 0xB3000000, 0xFF000000},
                    new float[] {0f, 0.3f, 0.65f, 1f}, Shader.TileMode.CLAMP);
            shaderLeft = left;
        }
        else {
            // The left side stays a little lighter (the name and buttons sit there)
            horizontal = new LinearGradient(0, 0, w * 0.55f, 0, new int[] {0x8C000000, 0xD9000000, 0xFF000000},
                    new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            shaderLeft = 0;
        }
        portraitShaders = portrait;
        shaderW = w;
        shaderH = h;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Bitmap b = bitmap;
        if (b == null || b.isRecycled()) {
            return;
        }
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        layoutArt(w, h);
        boolean portrait = b.getWidth() / (float) b.getHeight() < PORTRAIT;
        if (vertical == null || portraitShaders != portrait || shaderW != w || shaderH != h
                || (portrait && shaderLeft != dst.left)) {
            buildMask(w, h, portrait);
        }
        canvas.drawBitmap(b, null, dst, paint);
        mask.setShader(vertical);
        canvas.drawRect(0, 0, w, h, mask);
        mask.setShader(horizontal);
        canvas.drawRect(0, 0, w, h, mask);
    }
}
