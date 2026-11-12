package com.limelight.pocket.ui;

import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The small tag in a card's corner that says where a game comes from (Steam, Epic, Android, a
 * system ...) and whether it's installed: a coloured dot and the name in small capitals on a dark
 * rounded rectangle, quiet enough to sit on any cover.
 */
public final class CardTag {
    private CardTag() {}

    private static final String TAG = "pocket_card_tag";

    public static final int STEAM = 0xFF66C0F4;
    public static final int EPIC = 0xFFE8E8EC;
    public static final int GOG = 0xFFB98CFF;
    public static final int NON_STEAM = 0xFFFFB547;
    public static final int PC = 0xFF5BE3A6;
    public static final int ANDROID = 0xFF3DDC84;
    public static final int CLOUD = 0xFF4FC3F7;
    public static final int RETRO = 0xFFFF7A90;
    public static final int SHORTCUT = 0xFFFFD166;

    /** The colour of a store's dot (by its label). */
    public static int colorFor(String store) {
        if (store == null) {
            return PC;
        }
        String s = store.toLowerCase(Locale.ROOT);
        if (s.startsWith("non")) {
            return NON_STEAM;
        }
        if (s.contains("steam")) {
            return STEAM;
        }
        if (s.contains("epic")) {
            return EPIC;
        }
        if (s.contains("gog")) {
            return GOG;
        }
        return PC;
    }

    /**
     * Puts (or updates) the tag in the card's top corner. state: null when installed, "↓" when
     * not, or a percentage while it installs. host: the card's mask (RelativeLayout) or frame.
     */
    public static void apply(ViewGroup host, String label, int dot, String state) {
        if (host == null) {
            return;
        }
        TextView tag = host.findViewWithTag(TAG);
        float d = host.getResources().getDisplayMetrics().density;
        if (tag == null) {
            tag = new TextView(host.getContext());
            tag.setTag(TAG);
            tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
            tag.setTypeface(PocketFonts.semibold());
            tag.setLetterSpacing(0.07f);
            tag.setAllCaps(true);
            tag.setMaxLines(1);
            tag.setGravity(Gravity.CENTER_VERTICAL);
            tag.setTextColor(0xF2FFFFFF);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(6 * d);
            bg.setColor(0xB30C0C10);
            bg.setStroke(Math.max(1, Math.round(0.75f * d)), 0x1FFFFFFF);
            tag.setBackground(bg);
            tag.setPadding(Math.round(6 * d), Math.round(2.5f * d), Math.round(7 * d), Math.round(2.5f * d));
            tag.setCompoundDrawablePadding(Math.round(5 * d));
            int m = Math.round(8 * d);
            if (host instanceof RelativeLayout) {
                RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                lp.addRule(RelativeLayout.ALIGN_PARENT_START);
                lp.topMargin = m;
                lp.setMarginStart(m);
                host.addView(tag, lp);
            }
            else {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
                lp.topMargin = m;
                lp.setMarginStart(m);
                host.addView(tag, lp);
            }
        }
        if (label == null || label.isEmpty()) {
            tag.setVisibility(View.GONE);
            return;
        }
        tag.setVisibility(View.VISIBLE);
        // A small card (the grid, icons, the list) has no room for words: just the dot
        boolean compact = LibraryLayout.cardWidthDp(host.getContext()) < 100f;
        if (compact) {
            tag.setPadding(Math.round(4 * d), Math.round(4 * d), Math.round(4 * d), Math.round(4 * d));
            tag.setCompoundDrawablePadding(0);
        }
        else {
            tag.setPadding(Math.round(6 * d), Math.round(2.5f * d), Math.round(7 * d), Math.round(2.5f * d));
            tag.setCompoundDrawablePadding(Math.round(5 * d));
        }
        GradientDrawable dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dotShape.setColor(dot);
        int size = Math.round(5.5f * d);
        dotShape.setSize(size, size);
        dotShape.setBounds(0, 0, size, size);
        tag.setCompoundDrawablesRelative(dotShape, null, null, null);
        String text = state != null && !state.isEmpty() ? label + "  " + state : label;
        tag.setText(compact ? (state != null && !state.isEmpty() ? state : "") : text);
        tag.setContentDescription(text);
        tag.setAlpha(state != null && state.startsWith("↓") ? 0.86f : 1f);
    }

    /** Removes it (for a card shown again with other content). */
    public static void clear(ViewGroup host) {
        if (host == null) {
            return;
        }
        View tag = host.findViewWithTag(TAG);
        if (tag != null) {
            tag.setVisibility(View.GONE);
        }
    }
}
