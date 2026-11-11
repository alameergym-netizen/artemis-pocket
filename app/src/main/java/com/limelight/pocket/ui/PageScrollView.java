package com.limelight.pocket.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ScrollView;

import com.limelight.pocket.PocketPrefs;

/**
 * A page that scrolls in sections, driven by the screen (not by whatever gets focus): moving the
 * focus never jerks the page around on its own; the screen glides it to the section the focus
 * entered, on the UI spring's curve, like a console's game hub. Listeners hear every scroll step
 * (for parallax).
 */
public class PageScrollView extends ScrollView {

    public interface OnScrolled {
        void onScrolled(int scrollY);
    }

    private ValueAnimator glide;
    private OnScrolled onScrolled;
    private int target = -1;

    public PageScrollView(Context context) {
        super(context);
        init();
    }

    public PageScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PageScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setSmoothScrollingEnabled(false);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(View.OVER_SCROLL_NEVER);
    }

    public void setOnScrolled(OnScrolled listener) {
        onScrolled = listener;
    }

    /** Keys never scroll it either (the D-pad moves the focus; the screen follows). */
    @Override
    public boolean executeKeyEvent(android.view.KeyEvent event) {
        return false;
    }

    /** Focus moves never scroll by themselves: the screen decides with glideTo(). */
    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        return 0;
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (onScrolled != null) {
            onScrolled.onScrolled(t);
        }
    }

    public int maxScroll() {
        View child = getChildCount() > 0 ? getChildAt(0) : null;
        return child == null ? 0 : Math.max(0, child.getHeight() - (getHeight() - getPaddingTop() - getPaddingBottom()));
    }

    /** Where the page is going (or is). */
    public int targetScroll() {
        return glide != null && glide.isRunning() ? target : getScrollY();
    }

    /** Glides to y (clamped to the page). */
    public void glideTo(int y) {
        final int to = Math.max(0, Math.min(maxScroll(), y));
        if (glide != null) {
            glide.cancel();
        }
        target = to;
        int from = getScrollY();
        if (from == to) {
            return;
        }
        if (!PocketPrefs.animationsEnabled(getContext())) {
            scrollTo(0, to);
            return;
        }
        // Longer trips take a little longer, never sluggish
        long duration = Math.min(560, Spring.UI_DURATION_MS + Math.abs(to - from) / 8);
        glide = ValueAnimator.ofInt(from, to);
        glide.setDuration(duration);
        glide.setInterpolator(Spring.UI_CURVE);
        glide.addUpdateListener(a -> scrollTo(0, (Integer) a.getAnimatedValue()));
        glide.start();
    }

    /** Glides by dy from where it is going. */
    public void glideBy(int dy) {
        glideTo(targetScroll() + dy);
    }
}
