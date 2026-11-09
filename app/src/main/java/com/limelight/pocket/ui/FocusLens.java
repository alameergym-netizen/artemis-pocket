package com.limelight.pocket.ui;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.pocket.PocketPrefs;

import java.util.WeakHashMap;

/**
 * The console focus "lens" for a carousel (think PS5 / Xbox home): one frame that lives on the row
 * instead of on each card.
 *
 *  - It aims at where the focused card will come to rest, so with the anchored row it simply stays
 *    in its slot while the row slides the next game into it; at the ends of the row it glides.
 *  - It locks on with a little squeeze when the row gains focus, and re-locks (a soft breath) on
 *    every move, stretching slightly when it travels fast.
 *  - While you rest on a card it breathes softly and a sheen of light sweeps over the card once.
 *  - The other cards dim a touch so the focused one pops.
 */
public class FocusLens extends RecyclerView.ItemDecoration {

    public interface Host {
        /** The scale the focused card is heading to. */
        float focusScale();
        /** Whether the outline is part of the chosen focus style. */
        boolean showRing();
    }

    private static final float GAP_DP = 5.5f;
    private static final float STROKE_DP = 2.25f;
    private static final float DIM = 0.30f;

    private final RecyclerView list;
    private final Host host;
    private final float d;
    private final float cardRadius;

    private final Spring cx = new Spring(900, 0.86f, 0);
    private final Spring cy = new Spring(900, 0.86f, 0);
    private final Spring w = new Spring(620, 0.62f, 0);
    private final Spring h = new Spring(620, 0.62f, 0);
    private float alpha;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix sheenMatrix = new Matrix();
    private final RectF rect = new RectF();
    private final WeakHashMap<View, float[]> dims = new WeakHashMap<>();

    private int accent;
    private View lastFocused;
    private int lastFocusedPos = RecyclerView.NO_POSITION;
    private long lastFrameNanos;
    private long focusChangedAt;
    private long sheenStart = -1;
    private boolean sheenDone;
    private boolean invalidatePosted;

    public FocusLens(RecyclerView list, Host host, float cardRadiusDp) {
        this.list = list;
        this.host = host;
        this.d = list.getResources().getDisplayMetrics().density;
        this.cardRadius = cardRadiusDp * d;

        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(STROKE_DP * d);
        dimPaint.setColor(0xFF000000);

        int band = 0x38FFFFFF;
        sheenPaint.setShader(new LinearGradient(-1f, 0f, 1f, 0f,
                new int[]{0x00FFFFFF, band, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP));
        refreshAccent();
    }

    public void refreshAccent() {
        accent = PocketTheme.accent(list.getContext());
    }

    private boolean animated() {
        return PocketPrefs.animationsEnabled(list.getContext());
    }

    /** Called by the adapter on every focus change so the lens reacts on the next frame. */
    public void kick() {
        list.invalidate();
    }

    /** A press: the lens squeezes in and springs back, and the sheen plays again. */
    public void press() {
        if (!animated()) {
            return;
        }
        w.value -= 10 * d;
        h.value -= 10 * d;
        sheenStart = SystemClock.uptimeMillis();
        sheenDone = false;
        list.invalidate();
    }

    private static View content(View frame) {
        if (frame instanceof ViewGroup && ((ViewGroup) frame).getChildCount() > 0) {
            return ((ViewGroup) frame).getChildAt(0);
        }
        return frame;
    }

    /** The card's on-screen bounds right now (scale, shift and slide included). */
    private void liveBounds(View frame, RectF out) {
        View c = content(frame);
        if (c == frame) {
            out.set(0, 0, frame.getWidth(), frame.getHeight());
        }
        else {
            out.set(c.getLeft() + c.getTranslationX(), c.getTop() + c.getTranslationY(),
                    c.getRight() + c.getTranslationX(), c.getBottom() + c.getTranslationY());
        }
        frame.getMatrix().mapRect(out);
        out.offset(frame.getLeft(), frame.getTop());
    }

    private int pendingScroll() {
        RecyclerView.LayoutManager lm = list.getLayoutManager();
        return lm instanceof AnchoredLayoutManager ? ((AnchoredLayoutManager) lm).pendingScroll() : 0;
    }

    @Override
    public void onDrawOver(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 1f / 60f : Math.min(1f / 30f, (now - lastFrameNanos) / 1e9f);
        lastFrameNanos = now;
        boolean anim = animated();
        boolean moving = false;

        View focused = parent.getFocusedChild();
        boolean rowFocused = focused != null && parent.hasFocus();

        // The other cards dim gently while the row has focus
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            float[] dv = dims.get(child);
            if (dv == null) {
                dv = new float[]{0f};
                dims.put(child, dv);
            }
            float target = rowFocused && child != focused ? 1f : 0f;
            if (!anim) {
                dv[0] = target;
            }
            else if (dv[0] != target) {
                dv[0] += (target - dv[0]) * (1f - (float) Math.exp(-dt * 14f));
                if (Math.abs(target - dv[0]) < 0.01f) {
                    dv[0] = target;
                }
                else {
                    moving = true;
                }
            }
            if (dv[0] > 0.01f && child.getAlpha() > 0.05f) {
                liveBounds(child, rect);
                float r = cardRadius * child.getScaleX();
                dimPaint.setAlpha(Math.round(255 * DIM * dv[0] * child.getAlpha()));
                canvas.drawRoundRect(rect, r, r, dimPaint);
            }
        }

        // Where the lens is heading: the focused card at rest (after the row finishes sliding)
        if (rowFocused) {
            View c = content(focused);
            float s = host.focusScale();
            float left = focused.getLeft() + focused.getTranslationX() + (c != focused ? c.getLeft() : 0);
            float top = focused.getTop() + focused.getTranslationY() + (c != focused ? c.getTop() : 0);
            float cw = c.getWidth();
            float ch = c.getHeight();
            float tx = left + cw / 2f - pendingScroll();
            float ty = top + ch / 2f;
            float tw = cw * s + 2 * GAP_DP * d;
            float th = ch * s + 2 * GAP_DP * d;

            boolean lockOn = alpha < 0.05f || lastFocused == null;
            if (lockOn || !anim) {
                cx.snap(tx);
                cy.snap(ty);
                w.snap(tw);
                h.snap(th);
                if (anim) {
                    // Lock on: the lens starts a little wide and squeezes onto the card
                    w.value += 18 * d;
                    h.value += 18 * d;
                }
            }
            else {
                cx.target = tx;
                cy.target = ty;
                w.target = tw;
                h.target = th;
                if (parent.getChildAdapterPosition(focused) != lastFocusedPos) {
                    // Re-lock on the new card: a soft breath out and back in
                    w.velocity += 260 * d;
                    h.velocity += 260 * d;
                }
            }
            int pos = parent.getChildAdapterPosition(focused);
            if (pos != lastFocusedPos || lockOn) {
                focusChangedAt = SystemClock.uptimeMillis();
                sheenStart = -1;
                sheenDone = false;
                refreshAccent();
            }
            lastFocused = focused;
            lastFocusedPos = pos;
        }
        else {
            lastFocused = null;
            lastFocusedPos = RecyclerView.NO_POSITION;
        }

        float alphaTarget = rowFocused ? 1f : 0f;
        if (!anim) {
            alpha = alphaTarget;
        }
        else if (alpha != alphaTarget) {
            alpha += (alphaTarget - alpha) * (1f - (float) Math.exp(-dt * (rowFocused ? 26f : 18f)));
            if (Math.abs(alphaTarget - alpha) < 0.01f) {
                alpha = alphaTarget;
            }
            else {
                moving = true;
            }
        }

        moving |= cx.step(dt);
        moving |= cy.step(dt);
        moving |= w.step(dt);
        moving |= h.step(dt);

        if (alpha <= 0.01f) {
            schedule(moving, false);
            return;
        }

        // A sheen sweeps over the card once you rest on it
        if (rowFocused && anim) {
            long t = SystemClock.uptimeMillis();
            if (sheenStart < 0 && !sheenDone && t - focusChangedAt > 280) {
                sheenStart = t;
            }
            if (sheenStart >= 0 && !sheenDone) {
                float p = (t - sheenStart) / 760f;
                if (p >= 1f) {
                    sheenDone = true;
                    sheenStart = -1;
                }
                else {
                    liveBounds(focused, rect);
                    float e = PocketUi.EASE.getInterpolation(p);
                    float band = rect.width() * 0.55f;
                    float x = rect.left - band + (rect.width() + 2 * band) * e;
                    sheenMatrix.reset();
                    sheenMatrix.setScale(band, band);
                    sheenMatrix.postRotate(-18f);
                    sheenMatrix.postTranslate(x, rect.centerY());
                    sheenPaint.getShader().setLocalMatrix(sheenMatrix);
                    sheenPaint.setAlpha(Math.round(255 * alpha * (1f - Math.abs(2 * p - 1) * 0.35f)));
                    float r = cardRadius * focused.getScaleX();
                    canvas.drawRoundRect(rect, r, r, sheenPaint);
                    moving = true;
                }
            }
        }

        boolean breathing = false;
        if (host.showRing()) {
            // A little stretch along the way the lens travels
            float stretch = Math.min(1f, Math.abs(cx.velocity) / (2600f * d));
            float lw = w.value + stretch * 12 * d;
            float lh = h.value - stretch * 5 * d;
            rect.set(cx.value - lw / 2f, cy.value - lh / 2f, cx.value + lw / 2f, cy.value + lh / 2f);
            float r = cardRadius * host.focusScale() + GAP_DP * d;

            float glow = 0.55f;
            if (anim && rowFocused) {
                double phase = (SystemClock.uptimeMillis() % 3200L) / 3200.0 * Math.PI * 2;
                glow = 0.42f + 0.22f * (float) Math.sin(phase);
                breathing = true;
            }
            int a = Math.round(255 * alpha);
            ring.setColor(PocketTheme.withAlpha(accent, a));
            ring.setShadowLayer(9 * d, 0, 0, PocketTheme.withAlpha(accent, Math.round(a * glow)));
            canvas.drawRoundRect(rect, r, r, ring);
        }
        schedule(moving, breathing);
    }

    private void schedule(boolean moving, boolean breathing) {
        if (moving) {
            list.postInvalidateOnAnimation();
        }
        else if (breathing && !invalidatePosted) {
            // The breathing glow is slow; a calm 25 fps is plenty and spares the battery
            invalidatePosted = true;
            list.postDelayed(() -> {
                invalidatePosted = false;
                list.invalidate();
            }, 40);
        }
        else if (!moving && !breathing) {
            lastFrameNanos = 0;
        }
    }
}
