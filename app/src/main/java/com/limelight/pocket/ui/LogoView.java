package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import java.util.WeakHashMap;

/**
 * A game's logo in a box of a fixed size, placed the way a console does it: the empty margins
 * round the picture are ignored (logos are measured by what's drawn), every shape gets about the
 * same weight (a very wide logo is wider and lower, a square one taller and narrower, never
 * bigger than the box), and it sits at the box's bottom start, so the gap to what's under it is
 * always the same. Because it scales the picture itself, a small logo grows as well as a big one
 * shrinks: the logo size setting just changes the box.
 */
public class LogoView extends ImageView {

    /** Where the drawn part of each logo is (in its pixels), worked out once per picture. */
    private static final WeakHashMap<Bitmap, RectF> CONTENT = new WeakHashMap<>();

    /** The aspect (width / height) of a logo that fills the box's height exactly. */
    private static final float BALANCE = 2.6f;

    private final Matrix matrix = new Matrix();
    private boolean centered;

    public LogoView(Context context) {
        super(context);
        init();
    }

    public LogoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LogoView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        setScaleType(ScaleType.MATRIX);
        setAdjustViewBounds(false);
    }

    /** Centred in its box (a launch screen) rather than at the bottom start (a hero). */
    public void setCentered(boolean centered) {
        this.centered = centered;
        place();
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        place();
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        place();
    }

    @Override
    protected boolean setFrame(int l, int t, int r, int b) {
        boolean changed = super.setFrame(l, t, r, b);
        place();
        return changed;
    }

    private void place() {
        if (matrix == null) {
            return; // still being made (the picture from the layout comes before the fields)
        }
        if (getScaleType() != ScaleType.MATRIX) {
            setScaleType(ScaleType.MATRIX);
        }
        Drawable d = getDrawable();
        int vw = getWidth() - getPaddingLeft() - getPaddingRight();
        int vh = getHeight() - getPaddingTop() - getPaddingBottom();
        if (d == null || vw <= 0 || vh <= 0) {
            return;
        }
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) {
            return;
        }
        RectF c = new RectF(0, 0, dw, dh);
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            RectF in = content(b);
            if (in != null && b.getWidth() > 0 && b.getHeight() > 0) {
                float sx = dw / (float) b.getWidth();
                float sy = dh / (float) b.getHeight();
                c.set(in.left * sx, in.top * sy, in.right * sx, in.bottom * sy);
            }
        }
        float a = c.width() / Math.max(1f, c.height());
        // The same "weight" for every shape: the area of a logo that just fills the height
        float area = vh * vh * BALANCE;
        float h = (float) Math.sqrt(area / Math.max(0.05f, a));
        float w = h * a;
        if (h > vh) {
            h = vh;
            w = h * a;
        }
        if (w > vw) {
            w = vw;
            h = w / a;
        }
        float s = w / Math.max(1f, c.width());
        boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float x = centered ? (vw - w) / 2f : (rtl ? vw - w : 0);
        float y = centered ? (vh - h) / 2f : vh - h;
        matrix.reset();
        matrix.setTranslate(-c.left, -c.top);
        matrix.postScale(s, s);
        matrix.postTranslate(getPaddingLeft() + x, getPaddingTop() + y);
        setImageMatrix(matrix);
        invalidate();
    }

    /** The drawn part of a logo (where it isn't transparent), in its pixels; null: all of it. */
    static RectF content(Bitmap b) {
        if (b == null || b.isRecycled()) {
            return null;
        }
        synchronized (CONTENT) {
            RectF known = CONTENT.get(b);
            if (known != null) {
                return known;
            }
        }
        RectF out = null;
        try {
            Bitmap src = b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && b.getConfig() == Bitmap.Config.HARDWARE) {
                src = b.copy(Bitmap.Config.ARGB_8888, false);
            }
            if (src != null && src.hasAlpha()) {
                int sw = Math.min(160, src.getWidth());
                int sh = Math.max(1, Math.round(src.getHeight() * sw / (float) src.getWidth()));
                Bitmap small = sw == src.getWidth() ? src : Bitmap.createScaledBitmap(src, sw, sh, true);
                int w = small.getWidth();
                int h = small.getHeight();
                int[] px = new int[w * h];
                small.getPixels(px, 0, w, 0, 0, w, h);
                int minX = w;
                int minY = h;
                int maxX = -1;
                int maxY = -1;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        if (Color.alpha(px[y * w + x]) > 28) {
                            if (x < minX) {
                                minX = x;
                            }
                            if (x > maxX) {
                                maxX = x;
                            }
                            if (y < minY) {
                                minY = y;
                            }
                            if (y > maxY) {
                                maxY = y;
                            }
                        }
                    }
                }
                if (maxX >= minX && maxY >= minY) {
                    float k = b.getWidth() / (float) w;
                    float kh = b.getHeight() / (float) h;
                    out = new RectF(Math.max(0, (minX - 0.5f) * k), Math.max(0, (minY - 0.5f) * kh),
                            Math.min(b.getWidth(), (maxX + 1.5f) * k), Math.min(b.getHeight(), (maxY + 1.5f) * kh));
                }
            }
        } catch (RuntimeException | OutOfMemoryError ignored) {
            // measured as a whole
        }
        if (out == null) {
            out = new RectF(0, 0, b.getWidth(), b.getHeight());
        }
        synchronized (CONTENT) {
            CONTENT.put(b, out);
        }
        return out;
    }
}
