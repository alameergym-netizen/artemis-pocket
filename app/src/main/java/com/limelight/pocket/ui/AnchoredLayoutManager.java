package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.pocket.PocketPrefs;

/**
 * Console-style horizontal scrolling: the focused card always glides into the same slot at the
 * start of the row (like a console home screen) instead of the row jumping only when focus hits
 * the edge. The row stops naturally at its ends.
 *
 * It also remembers how much of the glide is still to come, so the focus lens can aim at where the
 * card will come to rest rather than chase it.
 */
public class AnchoredLayoutManager extends LinearLayoutManager {
    private static final int SCROLL_MS = 300;
    /** Starts fast, lands softly (an "emphasized decelerate" curve). */
    private static final Interpolator GLIDE = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    private int pending;

    public AnchoredLayoutManager(Context context) {
        super(context, HORIZONTAL, false);
    }

    /** Pixels the row still has to scroll to finish the current glide (0 when at rest). */
    public int pendingScroll() {
        return pending;
    }

    @Override
    public int scrollHorizontallyBy(int dx, RecyclerView.Recycler recycler, RecyclerView.State state) {
        int consumed = super.scrollHorizontallyBy(dx, recycler, state);
        if (pending != 0) {
            if (consumed != 0 && Integer.signum(consumed) == Integer.signum(pending)
                    && Math.abs(consumed) < Math.abs(pending)) {
                pending -= consumed;
            }
            else {
                pending = 0;
            }
        }
        return consumed;
    }

    @Override
    public void onScrollStateChanged(int state) {
        super.onScrollStateChanged(state);
        if (state != RecyclerView.SCROLL_STATE_SETTLING) {
            // Finished, cut short at the end of the row, or taken over by a finger
            pending = 0;
        }
    }

    @Override
    public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                 @NonNull Rect rect, boolean immediate,
                                                 boolean focusedChildVisible) {
        int dx;
        boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        if (rtl) {
            dx = getDecoratedRight(child) - (getWidth() - getPaddingRight());
        }
        else {
            dx = getDecoratedLeft(child) - getPaddingLeft();
        }
        // Near the end of the row the glide stops short; aim for where it really stops
        View last = getItemCount() > 0 ? findViewByPosition(getItemCount() - 1) : null;
        if (last != null) {
            if (rtl) {
                dx = Math.max(dx, Math.min(0, getDecoratedLeft(last) - getPaddingLeft()));
            }
            else {
                dx = Math.min(dx, Math.max(0, getDecoratedRight(last) - (getWidth() - getPaddingRight())));
            }
        }
        if (dx == 0) {
            return false;
        }
        if (immediate || !PocketPrefs.animationsEnabled(parent.getContext())) {
            pending = 0;
            parent.scrollBy(dx, 0);
        }
        else {
            parent.smoothScrollBy(dx, 0, GLIDE, SCROLL_MS);
            pending = dx;
        }
        return true;
    }
}
