package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;

import com.limelight.pocket.PocketPrefs;

/**
 * The accent color (Settings > System > Accent) applied to the console's focus and highlight
 * drawables. White keeps the original mono glass look.
 */
public final class PocketTheme {
    private PocketTheme() {}

    /** Accent presets: white, ice, mint, lilac, rose, amber. */
    public static final int[] ACCENTS = {0xFFFFFFFF, 0xFF9FD8FF, 0xFF86F0C8, 0xFFC3B2FF, 0xFFFF9EBE, 0xFFFFC978,
            0xFFFF8F7A, 0xFFC8F27A, 0xFF6FE6F0, 0xFF9E8CFF, 0xFF4CFF8F};

    public static int accent(Context context) {
        return PocketPrefs.accentColor(context);
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static GradientDrawable shape(float radiusPx, int fill, int strokePx, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusPx);
        d.setColor(fill);
        if (strokePx > 0) {
            d.setStroke(strokePx, strokeColor);
        }
        return d;
    }

    /** Settings row: glass when idle, filled with the accent when focused. */
    public static Drawable row(Context context) {
        float d = context.getResources().getDisplayMetrics().density;
        float r = 14 * d;
        int accent = accent(context);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, shape(r, withAlpha(accent, 0xF2), 0, 0));
        s.addState(new int[]{android.R.attr.state_pressed}, shape(r, 0x33FFFFFF, 0, 0));
        s.addState(new int[]{}, new LayerDrawable(new Drawable[]{
                shape(r, 0x12FFFFFF, 0, 0),
                shape(r, 0x00000000, Math.max(1, Math.round(d)), 0x1AFFFFFF)}));
        return s;
    }

    /** A settings row under a FocusGlider: the glider draws the focus, the row only its glass. */
    public static Drawable rowGlide(Context context) {
        float d = context.getResources().getDisplayMetrics().density;
        float r = 14 * d;
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, shape(r, 0x00000000, 0, 0));
        s.addState(new int[]{android.R.attr.state_pressed}, shape(r, 0x33FFFFFF, 0, 0));
        s.addState(new int[]{}, new LayerDrawable(new Drawable[]{
                shape(r, 0x12FFFFFF, 0, 0),
                shape(r, 0x00000000, Math.max(1, Math.round(d)), 0x1AFFFFFF)}));
        s.setEnterFadeDuration(160);
        s.setExitFadeDuration(220);
        return s;
    }

    /** A settings tab under a FocusGlider. */
    public static Drawable tabGlide(Context context) {
        float d = context.getResources().getDisplayMetrics().density;
        float r = 100 * d;
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, shape(r, 0x00000000, 0, 0));
        s.addState(new int[]{android.R.attr.state_selected}, shape(r, 0x2EFFFFFF, Math.max(1, Math.round(d)), 0x33FFFFFF));
        s.addState(new int[]{}, shape(r, 0x00000000, 0, 0));
        s.setEnterFadeDuration(180);
        s.setExitFadeDuration(180);
        return s;
    }

    /** Settings tab pill. */
    public static Drawable tab(Context context) {
        float d = context.getResources().getDisplayMetrics().density;
        float r = 100 * d;
        int accent = accent(context);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, shape(r, accent, 0, 0));
        s.addState(new int[]{android.R.attr.state_selected}, shape(r, 0x2EFFFFFF, Math.max(1, Math.round(d)), 0x33FFFFFF));
        s.addState(new int[]{}, shape(r, 0x00000000, 0, 0));
        return s;
    }

    /** "On" toggle pill (black when its row is focused, so it stands out on the accent). */
    public static Drawable toggleOn(Context context) {
        float r = 100 * context.getResources().getDisplayMetrics().density;
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, shape(r, 0xFF000000, 0, 0));
        s.addState(new int[]{}, shape(r, accent(context), 0, 0));
        return s;
    }
}
