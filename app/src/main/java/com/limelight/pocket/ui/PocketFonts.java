package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Typeface;

import androidx.core.content.res.ResourcesCompat;

import com.limelight.R;

/**
 * The interface's typeface: Inter (a clean, modern grotesque in the spirit of Apple's system
 * font, SIL Open Font License), in the weights the console uses. Arabic and other scripts fall
 * back to the system's font for their letters.
 */
public final class PocketFonts {
    private PocketFonts() {}

    private static Typeface regular;
    private static Typeface medium;
    private static Typeface semibold;
    private static Typeface bold;
    private static Typeface display;

    /** Loads them once (the application calls this; anything else just gets the fallbacks). */
    public static synchronized void init(Context context) {
        if (regular != null) {
            return;
        }
        Context c = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        regular = load(c, R.font.inter_regular, Typeface.create("sans-serif", Typeface.NORMAL));
        medium = load(c, R.font.inter_medium, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        semibold = load(c, R.font.inter_semibold, Typeface.create("sans-serif-medium", Typeface.BOLD));
        bold = load(c, R.font.inter_bold, Typeface.create("sans-serif", Typeface.BOLD));
        display = load(c, R.font.inter_display, bold);
    }

    private static Typeface load(Context c, int id, Typeface fallback) {
        try {
            Typeface t = ResourcesCompat.getFont(c, id);
            return t != null ? t : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static Typeface regular() {
        return regular != null ? regular : Typeface.create("sans-serif", Typeface.NORMAL);
    }

    public static Typeface medium() {
        return medium != null ? medium : Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    public static Typeface semibold() {
        return semibold != null ? semibold : Typeface.create("sans-serif-medium", Typeface.BOLD);
    }

    public static Typeface bold() {
        return bold != null ? bold : Typeface.create("sans-serif", Typeface.BOLD);
    }

    /** Big titles (Inter's display cut: tighter, made for large sizes). */
    public static Typeface display() {
        return display != null ? display : bold();
    }

    /** The old family names, mapped to Inter. */
    public static Typeface of(String family) {
        if (family == null) {
            return regular();
        }
        switch (family) {
            case "sans-serif-medium":
                return medium();
            case "sans-serif-black":
            case "sans-serif-condensed-bold":
                return bold();
            case "sans-serif-light":
            case "sans-serif-thin":
            case "sans-serif":
                return regular();
            default:
                return Typeface.create(family, Typeface.NORMAL);
        }
    }
}
