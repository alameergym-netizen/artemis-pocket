package com.limelight.pocket.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.limelight.R;
import com.limelight.pocket.PocketPrefs;

/**
 * The ways a library can be laid out, switched with one button (SELECT, the "view" button): a
 * shelf of tiles, covers or wide capsules under a big hero; a grid of each beside a details
 * pane; or a list. Each switch shows a small chip with a picture of the layout and its name.
 */
public final class Layouts {
    private Layouts() {}

    public static final int SHELF = 0;
    public static final int GRID = 1;
    public static final int LIST = 2;

    public static final class Layout {
        public final String id;
        public final int label;
        public final int arrange;
        /** LibraryLayout.STYLE_* */
        public final String shape;

        Layout(String id, int label, int arrange, String shape) {
            this.id = id;
            this.label = label;
            this.arrange = arrange;
            this.shape = shape;
        }

        String mode() {
            return arrange == GRID ? LibraryLayout.MODE_GRID
                    : (arrange == LIST ? LibraryLayout.MODE_XMB : LibraryLayout.MODE_CAROUSEL);
        }
    }

    public static final Layout[] ALL = {
            new Layout("tiles", R.string.pocket_layout_tiles, SHELF, LibraryLayout.STYLE_ICON),
            new Layout("covers", R.string.pocket_layout_covers, SHELF, LibraryLayout.STYLE_COVER),
            new Layout("wide", R.string.pocket_layout_wide, SHELF, LibraryLayout.STYLE_WIDE),
            new Layout("grid_covers", R.string.pocket_layout_grid_covers, GRID, LibraryLayout.STYLE_COVER),
            new Layout("grid_tiles", R.string.pocket_layout_grid_tiles, GRID, LibraryLayout.STYLE_ICON),
            new Layout("grid_wide", R.string.pocket_layout_grid_wide, GRID, LibraryLayout.STYLE_WIDE),
            new Layout("list", R.string.pocket_layout_list, LIST, LibraryLayout.STYLE_ICON),
    };

    // ---- Home --------------------------------------------------------------------------------

    private static final String KEY_HOME = "pocket_home_layout";

    public static int homeIndex(Context c) {
        String id = PocketPrefs.prefs(c).getString(KEY_HOME, "tiles");
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].id.equals(id)) {
                return i;
            }
        }
        return 0;
    }

    public static Layout home(Context c) {
        return ALL[homeIndex(c)];
    }

    public static Layout nextHome(Context c) {
        int i = (homeIndex(c) + 1) % ALL.length;
        PocketPrefs.prefs(c).edit().putString(KEY_HOME, ALL[i].id).apply();
        return ALL[i];
    }

    // ---- The libraries (Streaming, Android, Retro, PC games) ----------------------------------

    public static int libraryIndex(Context c) {
        String mode = PocketPrefs.viewMode(c);
        String style = PocketPrefs.cardStyle(c);
        for (int i = 0; i < ALL.length; i++) {
            Layout l = ALL[i];
            if (l.mode().equals(mode) && (l.arrange == LIST || l.shape.equals(style))) {
                return i;
            }
        }
        return 1; // carousel of covers
    }

    /** The next layout for the libraries (saved); the screen re-creates itself to show it. */
    public static Layout nextLibrary(Context c) {
        int i = (libraryIndex(c) + 1) % ALL.length;
        Layout l = ALL[i];
        PocketPrefs.setViewMode(c, l.mode());
        if (l.arrange != LIST) {
            PocketPrefs.setCardStyle(c, l.shape);
        }
        pendingChip = i;
        pendingAt = SystemClock.uptimeMillis();
        return l;
    }

    private static int pendingChip = -1;
    private static long pendingAt;

    /** After a library screen re-created itself for a new layout: its chip. */
    public static void showPendingChip(Activity a) {
        if (pendingChip >= 0 && SystemClock.uptimeMillis() - pendingAt < 3000) {
            int i = pendingChip;
            pendingChip = -1;
            showChip(a, i);
        }
        pendingChip = -1;
    }

    // ---- The chip ----------------------------------------------------------------------------

    private static final String CHIP_TAG = "pocket_layout_chip";

    /** A frosted chip at the top: the layout's picture, its name and where it is in the list. */
    public static void showChip(Activity a, int index) {
        ViewGroup root = a.findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        float d = a.getResources().getDisplayMetrics().density;
        View old = root.findViewWithTag(CHIP_TAG);
        if (old != null) {
            old.animate().cancel();
            root.removeView(old);
        }
        Layout l = ALL[index];
        LinearLayout chip = new LinearLayout(a);
        chip.setTag(CHIP_TAG);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(Math.round(10 * d), Math.round(7 * d), Math.round(14 * d), Math.round(7 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(100 * d);
        bg.setColor(0xF01C1C1F);
        bg.setStroke(Math.max(1, Math.round(0.75f * d)), 0x2EFFFFFF);
        chip.setBackground(bg);
        chip.setElevation(14 * d);

        View glyph = new View(a);
        glyph.setBackground(new Glyph(l, d));
        chip.addView(glyph, new LinearLayout.LayoutParams(Math.round(34 * d), Math.round(22 * d)));

        TextView name = new TextView(a);
        name.setText(l.label);
        name.setTypeface(PocketFonts.semibold());
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        name.setTextColor(0xFFFFFFFF);
        name.setIncludeFontPadding(false);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.setMarginStart(Math.round(9 * d));
        chip.addView(name, nlp);

        // Where this layout is among them: dots
        LinearLayout dots = new LinearLayout(a);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < ALL.length; i++) {
            View dot = new View(a);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(i == index ? 0xFFFFFFFF : 0x4DFFFFFF);
            dot.setBackground(g);
            int s = Math.round((i == index ? 5f : 4f) * d);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(s, s);
            dlp.setMarginStart(Math.round(3 * d));
            dots.addView(dot, dlp);
        }
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dotsLp.setMarginStart(Math.round(12 * d));
        chip.addView(dots, dotsLp);

        TextView key = new TextView(a);
        key.setText(R.string.pocket_layout_next);
        key.setTypeface(PocketFonts.medium());
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        key.setTextColor(0x8CFFFFFF);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.setMarginStart(Math.round(12 * d));
        chip.addView(key, klp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = Math.round(46 * d);
        root.addView(chip, lp);
        boolean animate = PocketPrefs.animationsEnabled(a);
        if (animate) {
            chip.setAlpha(0f);
            chip.setTranslationY(-8 * d);
            chip.setScaleX(0.96f);
            chip.setScaleY(0.96f);
            chip.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(Spring.UI_CURVE).start();
        }
        chip.postDelayed(() -> {
            if (chip.getParent() == null) {
                return;
            }
            if (animate) {
                chip.animate().alpha(0f).translationY(-6 * d).setDuration(240)
                        .withEndAction(() -> root.removeView(chip)).start();
            }
            else {
                root.removeView(chip);
            }
        }, 1500);
    }

    /** A little picture of the layout (the hero, and its tiles in their arrangement). */
    static final class Glyph extends Drawable {
        private final Layout l;
        private final float d;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Glyph(Layout l, float d) {
            this.l = l;
            this.d = d;
            fill.setColor(0xFFFFFFFF);
            dim.setColor(0x59FFFFFF);
        }

        @Override
        public void draw(@NonNull Canvas c) {
            float w = getBounds().width();
            float h = getBounds().height();
            float x0 = getBounds().left;
            float y0 = getBounds().top;
            float rad = 1.2f * d;
            // tile proportions (w / h)
            float aspect = LibraryLayout.STYLE_COVER.equals(l.shape) ? 0.67f
                    : (LibraryLayout.STYLE_WIDE.equals(l.shape) ? 2f : 1f);
            if (l.arrange == SHELF) {
                // hero lines, then one row of tiles at the bottom
                r.set(x0, y0 + h * 0.08f, x0 + w * 0.45f, y0 + h * 0.2f);
                c.drawRoundRect(r, rad, rad, dim);
                r.set(x0, y0 + h * 0.28f, x0 + w * 0.3f, y0 + h * 0.36f);
                c.drawRoundRect(r, rad, rad, dim);
                float th = aspect >= 2f ? h * 0.3f : (aspect < 1f ? h * 0.48f : h * 0.38f);
                float tw = th * aspect;
                float gap = 2f * d;
                float x = x0;
                for (int i = 0; x + tw <= x0 + w + 0.5f && i < 6; i++) {
                    r.set(x, y0 + h - th, x + tw, y0 + h);
                    c.drawRoundRect(r, rad, rad, i == 0 ? fill : dim);
                    x += tw + gap;
                }
            }
            else if (l.arrange == GRID) {
                // a grid on the left, a details pane on the right
                float paneX = x0 + w * 0.7f;
                r.set(paneX + 2 * d, y0 + h * 0.1f, x0 + w, y0 + h * 0.24f);
                c.drawRoundRect(r, rad, rad, dim);
                r.set(paneX + 2 * d, y0 + h * 0.34f, x0 + w * 0.92f, y0 + h * 0.42f);
                c.drawRoundRect(r, rad, rad, dim);
                int cols = aspect >= 2f ? 2 : (aspect < 1f ? 4 : 4);
                float gap = 1.6f * d;
                float tw = (paneX - x0 - gap * (cols - 1)) / cols;
                float th = tw / aspect;
                float y = y0;
                for (int row = 0; y + th <= y0 + h + 0.5f && row < 4; row++) {
                    for (int col = 0; col < cols; col++) {
                        float x = x0 + col * (tw + gap);
                        r.set(x, y, x + tw, y + th);
                        c.drawRoundRect(r, rad, rad, row == 0 && col == 0 ? fill : dim);
                    }
                    y += th + gap;
                }
            }
            else {
                // a list: an icon and a line per game
                float rowH = h / 4f;
                for (int i = 0; i < 4; i++) {
                    float y = y0 + i * rowH;
                    float s = rowH - 2f * d;
                    r.set(x0, y, x0 + s, y + s);
                    c.drawRoundRect(r, rad, rad, i == 1 ? fill : dim);
                    r.set(x0 + s + 3 * d, y + s * 0.35f, x0 + w * (i == 1 ? 0.62f : 0.5f), y + s * 0.65f);
                    c.drawRoundRect(r, rad, rad, i == 1 ? fill : dim);
                }
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
