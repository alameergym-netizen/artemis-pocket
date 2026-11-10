package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Rect;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.pocket.PocketPrefs;

/**
 * Console-style horizontal scrolling: the focused card always glides into the same slot at the
 * start of the row (like a console home screen) instead of the row jumping only when focus hits
 * the edge. The row stops naturally at its ends.
 *
 * The glide is a critically damped spring stepped on the frame clock (not a timed tween): when you
 * hold the D-pad, every new card just moves the spring's target and the row keeps its speed, so it
 * flows instead of restarting (and jerking) at every step. It shares its stiffness with the focus
 * lens and the cards' own motion ({@link Spring#UI_STIFFNESS}), so they all arrive together.
 *
 * It also tells how much of the glide is still to come, so the focus lens can aim at where the
 * card will come to rest rather than chase it.
 */
public class AnchoredLayoutManager extends LinearLayoutManager {

    private static final float DAMPING = 2f * Spring.UI_OMEGA;

    private RecyclerView list;
    /** Where the spring is heading, relative to what has been scrolled so far (px). */
    private float remaining;
    /** The spring's position, relative to what has been scrolled so far (sub-pixel part). */
    private float offset;
    private float velocity;
    private long lastFrame;
    private boolean running;
    private boolean stepping;
    private int lastConsumed;

    // Stepped at the start of each frame, with that frame's vsync time
    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            step(frameTimeNanos);
        }
    };

    public AnchoredLayoutManager(Context context) {
        super(context, HORIZONTAL, false);
    }

    /** Pixels the row still has to scroll to finish the current glide (0 when at rest). */
    public int pendingScroll() {
        return running ? Math.round(remaining - offset) : 0;
    }

    @Override
    public void onAttachedToWindow(RecyclerView view) {
        super.onAttachedToWindow(view);
        list = view;
    }

    @Override
    public void onDetachedFromWindow(RecyclerView view, RecyclerView.Recycler recycler) {
        stop();
        list = null;
        super.onDetachedFromWindow(view, recycler);
    }

    @Override
    public void scrollToPosition(int position) {
        // A jump replaces any glide in progress
        stop();
        super.scrollToPosition(position);
    }

    @Override
    public void scrollToPositionWithOffset(int position, int offset) {
        stop();
        super.scrollToPositionWithOffset(position, offset);
    }

    @Override
    public int scrollHorizontallyBy(int dx, RecyclerView.Recycler recycler, RecyclerView.State state) {
        int consumed = super.scrollHorizontallyBy(dx, recycler, state);
        if (stepping) {
            lastConsumed = consumed;
        }
        return consumed;
    }

    @Override
    public void onScrollStateChanged(int state) {
        super.onScrollStateChanged(state);
        if (state == RecyclerView.SCROLL_STATE_DRAGGING) {
            // A finger took over
            stop();
        }
    }

    private void stop() {
        running = false;
        remaining = 0;
        offset = 0;
        velocity = 0;
        lastFrame = 0;
        Choreographer.getInstance().removeFrameCallback(frame);
    }

    private void step(long now) {
        RecyclerView rv = list;
        if (!running || rv == null) {
            return;
        }
        float dt = lastFrame == 0 ? 1f / 120f : (now - lastFrame) / 1e9f;
        lastFrame = now;
        // A long frame is caught up in small steps (the spring stays exact), but never more than
        // a few frames' worth at once
        dt = Math.max(0f, Math.min(dt, 0.05f));
        int steps = Math.max(1, (int) Math.ceil(dt / 0.004f));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            float accel = Spring.UI_STIFFNESS * (remaining - offset) - DAMPING * velocity;
            velocity += accel * h;
            offset += velocity * h;
        }
        boolean settled = Math.abs(remaining - offset) < 0.5f && Math.abs(velocity) < 12f;
        int want = settled ? Math.round(remaining) : (int) offset;
        if (want != 0) {
            stepping = true;
            lastConsumed = 0;
            rv.scrollBy(want, 0);
            stepping = false;
            if (lastConsumed != want) {
                // Reached the end of the row
                stop();
                return;
            }
            remaining -= want;
            offset -= want;
        }
        if (settled) {
            stop();
        }
        else {
            Choreographer.getInstance().postFrameCallback(frame);
        }
    }

    @Override
    public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                 @NonNull Rect rect, boolean immediate,
                                                 boolean focusedChildVisible) {
        list = parent;
        int dx;
        boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        // Measured from where the row will be once the glide in progress ends
        int ahead = pendingScroll();
        if (rtl) {
            dx = getDecoratedRight(child) - (getWidth() - getPaddingRight()) - ahead;
        }
        else {
            dx = getDecoratedLeft(child) - getPaddingLeft() - ahead;
        }
        // Near the ends of the row the glide stops short; aim for where it really stops. Cards
        // are all the same size, so an end that isn't laid out yet is just counted out (the
        // glide must never run into the end and stop dead)
        int pos = getPosition(child);
        RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) child.getLayoutParams();
        int pitch = getDecoratedMeasuredWidth(child) + lp.leftMargin + lp.rightMargin;
        int count = getItemCount();
        View last = count > 0 ? findViewByPosition(count - 1) : null;
        View first = count > 0 ? findViewByPosition(0) : null;
        if (rtl) {
            int lastLeft = last != null ? getDecoratedLeft(last) : getDecoratedLeft(child) - (count - 1 - pos) * pitch;
            dx = Math.max(dx, Math.min(0, lastLeft - getPaddingLeft() - ahead));
            int firstRight = first != null ? getDecoratedRight(first) : getDecoratedRight(child) + pos * pitch;
            dx = Math.min(dx, Math.max(0, firstRight - (getWidth() - getPaddingRight()) - ahead));
        }
        else {
            int lastRight = last != null ? getDecoratedRight(last) : getDecoratedRight(child) + (count - 1 - pos) * pitch;
            dx = Math.min(dx, Math.max(0, lastRight - (getWidth() - getPaddingRight()) - ahead));
            int firstLeft = first != null ? getDecoratedLeft(first) : getDecoratedLeft(child) - pos * pitch;
            dx = Math.max(dx, Math.min(0, firstLeft - getPaddingLeft() - ahead));
        }
        if (dx == 0) {
            return false;
        }
        if (immediate || !PocketPrefs.animationsEnabled(parent.getContext())) {
            int rest = ahead + dx;
            stop();
            parent.scrollBy(rest, 0);
            return true;
        }
        if (!running) {
            running = true;
            remaining = 0;
            offset = 0;
            velocity = 0;
            lastFrame = 0;
            Choreographer.getInstance().postFrameCallback(frame);
        }
        // Retarget: the row keeps its speed and simply heads further
        remaining += dx;
        return true;
    }
}
