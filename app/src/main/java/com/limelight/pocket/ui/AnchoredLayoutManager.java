package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.pocket.PocketPrefs;

/**
 * Console-style horizontal scrolling: the focused card always glides into the same slot at the
 * start of the row (like a console home screen) instead of the row jumping only when focus hits
 * the edge. The row stops naturally at its ends.
 */
public class AnchoredLayoutManager extends LinearLayoutManager {
    private static final int SCROLL_MS = 240;

    public AnchoredLayoutManager(Context context) {
        super(context, HORIZONTAL, false);
    }

    @Override
    public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                 @NonNull Rect rect, boolean immediate,
                                                 boolean focusedChildVisible) {
        int dx;
        if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            dx = getDecoratedRight(child) - (getWidth() - getPaddingRight());
        }
        else {
            dx = getDecoratedLeft(child) - getPaddingLeft();
        }
        if (dx == 0) {
            return false;
        }
        if (immediate || !PocketPrefs.animationsEnabled(parent.getContext())) {
            parent.scrollBy(dx, 0);
        }
        else {
            parent.smoothScrollBy(dx, 0, PocketUi.EASE, SCROLL_MS);
        }
        return true;
    }
}
