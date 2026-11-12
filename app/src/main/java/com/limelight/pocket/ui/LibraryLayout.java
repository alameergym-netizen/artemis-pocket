package com.limelight.pocket.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.pocket.ConsoleActivity;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.PocketSounds;

import java.util.List;

/**
 * How the libraries are laid out: a carousel under the hero (the default), a grid of covers
 * (like a console's library page) or a list (XMB, the cross media bar of the handhelds of old:
 * an icon and a name per line, the one you're on in the middle); and the cards' shape: covers,
 * wide capsules (SteamGridDB's 920x430) or square icons (SteamGridDB's icons).
 */
public final class LibraryLayout {
    private LibraryLayout() {}

    public static final String MODE_CAROUSEL = "carousel";
    public static final String MODE_GRID = "grid";
    public static final String MODE_XMB = "xmb";
    public static final String[] MODES = {MODE_CAROUSEL, MODE_GRID, MODE_XMB};

    public static final String STYLE_COVER = "cover";
    public static final String STYLE_WIDE = "wide";
    public static final String STYLE_ICON = "icon";
    public static final String[] STYLES = {STYLE_COVER, STYLE_WIDE, STYLE_ICON};

    private static final String LABEL_TAG = "pocket_xmb_label";

    public static String viewMode(Context c) {
        return PocketPrefs.viewMode(c);
    }

    public static String cardStyle(Context c) {
        // The list always shows icons
        return MODE_XMB.equals(viewMode(c)) ? STYLE_ICON : PocketPrefs.cardStyle(c);
    }

    /** True when the library has no hero above it (grid, list): UP and DOWN move in the library. */
    public static boolean isVertical(Context c) {
        String m = viewMode(c);
        return MODE_GRID.equals(m) || MODE_XMB.equals(m);
    }

    public static float cardWidthDp(Context c) {
        float scale = MODE_GRID.equals(viewMode(c)) ? 0.8f : 1f;
        switch (cardStyle(c)) {
            case STYLE_WIDE:
                return 214f * scale;
            case STYLE_ICON:
                return (MODE_XMB.equals(viewMode(c)) ? 64f : 112f) * scale;
            default:
                return 108f * scale;
        }
    }

    public static float cardHeightDp(Context c) {
        float scale = MODE_GRID.equals(viewMode(c)) ? 0.8f : 1f;
        switch (cardStyle(c)) {
            case STYLE_WIDE:
                return 100f * scale;
            case STYLE_ICON:
                return (MODE_XMB.equals(viewMode(c)) ? 64f : 112f) * scale;
            default:
                return 162f * scale;
        }
    }

    /** Gives a game card (app_grid_item) the size of the chosen style. PC cards are left alone. */
    public static void sizeCard(Context c, View card) {
        if (card == null || card.findViewById(R.id.grid_mask) == null) {
            return;
        }
        ViewGroup.LayoutParams lp = card.getLayoutParams();
        float d = c.getResources().getDisplayMetrics().density;
        int w = Math.round(cardWidthDp(c) * d);
        int h = Math.round(cardHeightDp(c) * d);
        // In the grid the card sits in the middle of its column (not at its start)
        int gravity = MODE_GRID.equals(viewMode(c)) ? Gravity.CENTER_HORIZONTAL : FrameLayout.LayoutParams.UNSPECIFIED_GRAVITY;
        if (lp == null) {
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(w, h);
            flp.gravity = gravity;
            card.setLayoutParams(flp);
        }
        else if (lp.width != w || lp.height != h
                || (lp instanceof FrameLayout.LayoutParams && ((FrameLayout.LayoutParams) lp).gravity != gravity)) {
            lp.width = w;
            lp.height = h;
            if (lp instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) lp).gravity = gravity;
            }
            card.setLayoutParams(lp);
        }
    }

    /**
     * The card's frame in the list: as wide as its column in the grid (so the card centres in
     * it), and scaled around the card itself in the list (not around the card and its name), so
     * the focus ring stays exactly on the card.
     */
    public static void frame(View frame, View card) {
        if (!(frame instanceof FrameLayout) || card == null || card.findViewById(R.id.grid_mask) == null) {
            return;
        }
        Context c = frame.getContext();
        String mode = viewMode(c);
        ViewGroup.LayoutParams lp = frame.getLayoutParams();
        int want = MODE_GRID.equals(mode) ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        if (lp != null && lp.width != want) {
            lp.width = want;
            frame.setLayoutParams(lp);
        }
        if (MODE_XMB.equals(mode)) {
            float d = c.getResources().getDisplayMetrics().density;
            frame.setPivotX(frame.getPaddingLeft() + cardWidthDp(c) * d / 2f);
            frame.setPivotY(frame.getPaddingTop() + cardHeightDp(c) * d / 2f);
        }
    }

    /** The list's name next to a card (XMB), or none. Called for every bound card. */
    public static void label(View frame, View card) {
        if (!(frame instanceof FrameLayout)) {
            return;
        }
        FrameLayout f = (FrameLayout) frame;
        TextView label = f.findViewWithTag(LABEL_TAG);
        Context c = frame.getContext();
        if (!MODE_XMB.equals(viewMode(c)) || card == null || card.findViewById(R.id.grid_mask) == null) {
            if (label != null) {
                f.removeView(label);
            }
            return;
        }
        float d = c.getResources().getDisplayMetrics().density;
        if (label == null) {
            label = new TextView(c);
            label.setTag(LABEL_TAG);
            label.setTypeface(PocketFonts.semibold());
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            label.setLetterSpacing(-0.01f);
            label.setTextColor(0xFFFFFFFF);
            label.setShadowLayer(8 * d, 0, 0, 0x99000000);
            label.setMaxLines(1);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            // Next to the card, past the room its focus ring and zoom take
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(380 * d),
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.START);
            lp.setMarginStart(Math.round(f.getPaddingLeft() + (cardWidthDp(c) * 1.13f + 16) * d));
            f.addView(label, lp);
        }
        CharSequence name = card.getContentDescription();
        if (name == null || name.length() == 0) {
            TextView t = card.findViewById(R.id.grid_text);
            name = t != null ? t.getText() : "";
        }
        label.setText(name);
        // The frame itself grows around the card: the name only brightens
        label.setAlpha(frame.hasFocus() ? 1f : 0.55f);
        label.setScaleX(1f);
        label.setScaleY(1f);
    }

    /** A list or grid that keeps the card you're on in the middle of the screen. */
    private static final class CenteredList extends LinearLayoutManager {
        CenteredList(Context c) {
            super(c, VERTICAL, false);
        }

        @Override
        public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child, @NonNull Rect rect,
                                                     boolean immediate, boolean focusedChildVisible) {
            return centre(parent, child, immediate);
        }
    }

    private static final class CenteredGrid extends GridLayoutManager {
        CenteredGrid(Context c, int spans) {
            super(c, spans, VERTICAL, false);
        }

        @Override
        public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child, @NonNull Rect rect,
                                                     boolean immediate, boolean focusedChildVisible) {
            return centre(parent, child, immediate);
        }
    }

    private static boolean centre(RecyclerView parent, View child, boolean immediate) {
        int dy = (child.getTop() + child.getHeight() / 2) - parent.getHeight() / 2;
        if (dy == 0) {
            return false;
        }
        if (immediate || !PocketPrefs.animationsEnabled(parent.getContext())) {
            parent.scrollBy(0, dy);
        }
        else {
            parent.smoothScrollBy(0, dy, Spring.UI_CURVE);
        }
        return true;
    }

    /**
     * Lays the screen out for the chosen mode (after the screen set its carousel up). The
     * carousel needs nothing; the grid and the list take the hero's place.
     */
    public static void apply(Activity a, RecyclerView list) {
        String mode = viewMode(a);
        if (MODE_CAROUSEL.equals(mode) || list == null) {
            return;
        }
        float d = a.getResources().getDisplayMetrics().density;
        final View hero = a.findViewById(R.id.pocketHero);
        if (hero != null) {
            // Gone for good: the screens show and hide their hero as the focus moves, and a hero
            // that came back pushed the whole grid / list down the screen
            ViewGroup.LayoutParams hlp = hero.getLayoutParams();
            hlp.height = 0;
            hero.setLayoutParams(hlp);
            hero.setVisibility(View.GONE);
            hero.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (hero.getVisibility() != View.GONE) {
                    hero.setVisibility(View.GONE);
                }
            });
        }
        View indicator = a.findViewById(R.id.pocketRowIndicator);
        if (indicator != null) {
            indicator.setVisibility(View.GONE);
        }
        // The library fills the space the hero had
        if (list.getParent() instanceof LinearLayout) {
            LinearLayout column = (LinearLayout) list.getParent();
            ViewGroup.LayoutParams clp = column.getLayoutParams();
            clp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            column.setLayoutParams(clp);
        }
        ViewGroup.LayoutParams lp = list.getLayoutParams();
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        list.setLayoutParams(lp);
        list.setClipToPadding(false);
        int screenW = a.getResources().getDisplayMetrics().widthPixels;
        int screenH = a.getResources().getDisplayMetrics().heightPixels;
        if (MODE_XMB.equals(mode)) {
            list.setPadding(Math.round(24 * d), screenH / 3, Math.round(screenW * 0.35f), screenH / 3);
            list.setLayoutManager(new CenteredList(a));
        }
        else {
            int cell = Math.round((cardWidthDp(a) + 28) * d);
            int usable = screenW - Math.round(40 * d);
            int spans = Math.max(2, usable / Math.max(1, cell));
            list.setPadding(Math.round(20 * d), Math.round(6 * d), Math.round(20 * d), screenH / 4);
            list.setLayoutManager(new CenteredGrid(a, spans));
        }
    }

    // ---- Settings ---------------------------------------------------------------------------

    private static int indexOf(String[] all, String v) {
        for (int i = 0; i < all.length; i++) {
            if (all[i].equals(v)) {
                return i;
            }
        }
        return 0;
    }

    /** "View" and "Cards" in a screen's options (the screen comes back laid out the new way). */
    public static void addSettings(final ConsoleActivity a, List<StreamMenuView.Item> items) {
        final String[] modeLabels = {a.getString(R.string.pocket_view_carousel), a.getString(R.string.pocket_view_grid),
                a.getString(R.string.pocket_view_xmb)};
        final String[] styleLabels = {a.getString(R.string.pocket_cards_cover), a.getString(R.string.pocket_cards_wide),
                a.getString(R.string.pocket_cards_icon)};
        items.add(StreamMenuView.Item.value(a.getString(R.string.pocket_view_mode),
                modeLabels[indexOf(MODES, viewMode(a))], direction -> {
                    int next = (indexOf(MODES, viewMode(a)) + (direction >= 0 ? 1 : -1) + MODES.length) % MODES.length;
                    PocketPrefs.setViewMode(a, MODES[next]);
                    PocketSounds.play(a, PocketSounds.NAV);
                    a.getWindow().getDecorView().postDelayed(() -> {
                        if (!a.isFinishing()) {
                            a.recreate();
                        }
                    }, 350);
                    return modeLabels[next];
                }));
        items.add(StreamMenuView.Item.value(a.getString(R.string.pocket_cards_style),
                styleLabels[indexOf(STYLES, PocketPrefs.cardStyle(a))], direction -> {
                    int next = (indexOf(STYLES, PocketPrefs.cardStyle(a)) + (direction >= 0 ? 1 : -1) + STYLES.length) % STYLES.length;
                    PocketPrefs.setCardStyle(a, STYLES[next]);
                    PocketSounds.play(a, PocketSounds.NAV);
                    a.getWindow().getDecorView().postDelayed(() -> {
                        if (!a.isFinishing()) {
                            a.recreate();
                        }
                    }, 350);
                    return styleLabels[next];
                }));
    }
}
