package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.pocket.ConsoleActivity;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.PocketSounds;

import java.util.ArrayList;
import java.util.List;

/**
 * The console menu panel (in-stream menu, card options, game settings, connection test):
 * a floating glass card over frosted game art, driven entirely by the controller
 * (D-pad / left stick to move, A to choose, B to go back, left/right to change values,
 * L1/R1 to jump between sections). A lit pill glides between rows with spring motion and pages
 * slide in and out. It never takes Android focus, so it works the same with any controller,
 * keyboard or touch.
 */
public class StreamMenuView extends FrameLayout {

    public interface ValueStepper {
        /** Change the value (direction -1 / +1) and return the new text to show. */
        String step(int direction);
    }

    public static final class Item {
        final String title;
        String value;
        String icon;
        final Runnable action;
        final ValueStepper stepper;
        final boolean header;
        final boolean submenu;
        final boolean confirm;
        final boolean keepOpen;
        boolean armed;

        private Item(String title, String value, Runnable action, ValueStepper stepper,
                     boolean header, boolean submenu, boolean confirm, boolean keepOpen) {
            this.title = title;
            this.value = value;
            this.action = action;
            this.stepper = stepper;
            this.header = header;
            this.submenu = submenu;
            this.confirm = confirm;
            this.keepOpen = keepOpen;
        }

        /** Runs and closes the menu. */
        public static Item action(String title, Runnable action) {
            return new Item(title, null, action, null, false, false, false, false);
        }

        /** Runs and keeps the menu open (e.g. volume). */
        public static Item repeatable(String title, Runnable action) {
            return new Item(title, null, action, null, false, false, false, true);
        }

        /** Asks for a second A press before running ("Press A again"). */
        public static Item confirm(String title, Runnable action) {
            return new Item(title, null, action, null, false, false, true, false);
        }

        /** Opens another page (the runnable calls push()). */
        public static Item submenu(String title, Runnable open) {
            return new Item(title, null, open, null, false, true, false, true);
        }

        /** A value changed with left / right (A also steps forward). */
        public static Item value(String title, String current, ValueStepper stepper) {
            return new Item(title, current, null, stepper, false, false, false, true);
        }

        public static Item header(String title) {
            return new Item(title, null, null, null, true, false, false, true);
        }

        /** A dim note shown at the end of the row (e.g. "game keeps running"). */
        public Item withValue(String value) {
            this.value = value;
            return this;
        }

        /** A line icon in front of the title (PocketIcons name). */
        public Item withIcon(String icon) {
            this.icon = icon;
            return this;
        }

        boolean isNote() {
            return action == null && stepper == null && value == null && !submenu && !header;
        }
    }

    public interface Listener {
        void onMenuClosed();
    }

    private static final class Page {
        final String title;
        final List<Item> items;
        int selected;

        Page(String title, List<Item> items) {
            this.title = title;
            this.items = items;
        }
    }

    private static final class RowViews {
        TextView title;
        TextView value;
        ImageView icon;
    }

    private final float d;
    private final int accent;
    private final ImageView backdrop;
    private final View scrim;
    private final LinearLayout panel;
    private final TextView titleView;
    private final TextView subtitleView;
    private final TextView statsView;
    private final TextView pageTitleView;
    private final ScrollView scroll;
    private final FrameLayout listFrame;
    private final View pill;
    private final LinearLayout list;
    private final LinearLayout hints;
    private final List<Page> stack = new ArrayList<>();
    private final List<View> rowViews = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean showing;
    private boolean hasBackdrop;
    private String confirmLabel = "Press A again";
    private int pillRetries;

    // Left-stick / D-pad repeat
    private int stickDir;
    private int stickXDir;
    private final Runnable stickRepeat = new Runnable() {
        @Override
        public void run() {
            if (stickDir != 0 && showing) {
                move(stickDir);
                handler.postDelayed(this, 105);
            }
        }
    };

    public StreamMenuView(Context context) {
        super(context);
        d = getResources().getDisplayMetrics().density;
        accent = PocketTheme.accent(context);
        setVisibility(GONE);
        setClickable(true);
        setFocusable(false);

        backdrop = new ImageView(context);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(backdrop, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        scrim = new View(context);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xB3000000, 0x73000000, 0x40000000}));
        scrim.setOnClickListener(v -> hide());
        addView(scrim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        float r = 26 * d;
        GradientDrawable body = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE6161618, 0xEB0C0C0E});
        body.setCornerRadius(r);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x14FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        sheen.setCornerRadius(r);
        GradientDrawable edge = new GradientDrawable();
        edge.setCornerRadius(r);
        edge.setColor(0x00000000);
        edge.setStroke(Math.max(1, Math.round(d)), 0x2EFFFFFF);
        panel.setBackground(new LayerDrawable(new android.graphics.drawable.Drawable[]{body, sheen, edge}));
        panel.setElevation(24 * d);
        panel.setPadding(dp(20), dp(18), dp(14), dp(12));
        LayoutParams panelLp = new LayoutParams(dp(340), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        panelLp.setMargins(dp(12), dp(12), 0, dp(12));
        addView(panel, panelLp);

        titleView = text(19, 0xFFFFFFFF, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        panel.addView(titleView);
        subtitleView = text(11.5f, 0xA3FFFFFF, false);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitleView.setPadding(0, dp(2), 0, 0);
        panel.addView(subtitleView);
        statsView = text(10.5f, 0x73FFFFFF, false);
        statsView.setMaxLines(2);
        statsView.setLineSpacing(0, 1.15f);
        statsView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statsView.setPadding(0, dp(2), 0, dp(10));
        panel.addView(statsView);

        pageTitleView = text(10, 0xA3FFFFFF, true);
        pageTitleView.setAllCaps(true);
        pageTitleView.setLetterSpacing(0.18f);
        pageTitleView.setPadding(dp(4), dp(2), 0, dp(6));
        pageTitleView.setVisibility(GONE);
        panel.addView(pageTitleView);

        scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        listFrame = new FrameLayout(context);
        listFrame.setClipChildren(false);

        // The lit pill behind the selected row: it glides from row to row
        pill = new View(context);
        GradientDrawable pillBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{PocketTheme.withAlpha(accent, 0xFA), PocketTheme.withAlpha(accent, 0xE6)});
        pillBg.setCornerRadius(14 * d);
        pill.setBackground(pillBg);
        pill.setPivotX(0);
        pill.setVisibility(INVISIBLE);
        listFrame.addView(pill, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(8));
        listFrame.addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.addView(listFrame, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        hints = new LinearLayout(context);
        hints.setOrientation(LinearLayout.HORIZONTAL);
        hints.setGravity(Gravity.CENTER_VERTICAL);
        hints.setPadding(0, dp(8), 0, 0);
        panel.addView(hints);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setConfirmLabel(String label) {
        confirmLabel = label;
    }

    public boolean isShowing() {
        return showing;
    }

    /** Frosted game picture behind the panel (from FrostedGlass.snapshot). */
    public void setBackdrop(Bitmap bitmap) {
        if (!showing) {
            return;
        }
        hasBackdrop = bitmap != null;
        FrostedGlass.apply(backdrop, bitmap, 0.55f);
    }

    private int dp(float v) {
        return Math.round(v * d);
    }

    private boolean animated() {
        return PocketPrefs.animationsEnabled(getContext());
    }

    private TextView text(float sp, int color, boolean medium) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (medium) {
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return t;
    }

    // ---- Showing ------------------------------------------------------------------------

    public void show(String title, String subtitle, String stats, List<Item> root,
                     ConsoleActivity.Hint... hintItems) {
        titleView.setText(title);
        subtitleView.setText(subtitle);
        subtitleView.setVisibility(subtitle == null || subtitle.isEmpty() ? GONE : VISIBLE);
        statsView.setText(stats);
        statsView.setVisibility(stats == null || stats.isEmpty() ? GONE : VISIBLE);
        ConsoleActivity.fillHintBar(getContext(), hints, hintItems);
        stack.clear();
        stack.add(new Page(null, root));
        boolean wasShowing = showing;
        showing = true;
        if (!wasShowing) {
            backdrop.setImageDrawable(null);
            hasBackdrop = false;
        }
        render(0);
        PocketSounds.play(getContext(), PocketSounds.OPEN);

        animate().cancel();
        panel.animate().cancel();
        scrim.animate().cancel();
        setVisibility(VISIBLE);
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        scrim.setBackground(new GradientDrawable(rtl ? GradientDrawable.Orientation.RIGHT_LEFT
                : GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xB3000000, 0x73000000, 0x40000000}));
        if (animated() && !wasShowing) {
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(180).start();
            panel.setTranslationX(rtl ? dp(48) : -dp(48));
            panel.setAlpha(0f);
            panel.setScaleX(0.97f);
            panel.setScaleY(0.97f);
            panel.animate().translationX(0).alpha(1f).scaleX(1f).scaleY(1f).setDuration(460)
                    .setInterpolator(Spring.SOFT).start();
        }
        else {
            scrim.setAlpha(1f);
            panel.setTranslationX(0);
            panel.setAlpha(1f);
            panel.setScaleX(1f);
            panel.setScaleY(1f);
        }
    }

    public void hide() {
        if (!showing) {
            return;
        }
        showing = false;
        stickDir = 0;
        stickXDir = 0;
        handler.removeCallbacks(stickRepeat);
        if (animated()) {
            scrim.animate().alpha(0f).setDuration(150).start();
            backdrop.animate().alpha(0f).setDuration(170).start();
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            panel.animate().translationX(rtl ? dp(36) : -dp(36)).alpha(0f).scaleX(0.98f).scaleY(0.98f).setDuration(160)
                    .setInterpolator(PocketUi.EASE)
                    .withEndAction(() -> {
                        setVisibility(GONE);
                        backdrop.setImageDrawable(null);
                    }).start();
        }
        else {
            setVisibility(GONE);
            backdrop.setImageDrawable(null);
        }
        if (listener != null) {
            listener.onMenuClosed();
        }
    }

    /** Opens a sub page (Shortcuts, PC commands, ...). B comes back. */
    public void push(String title, List<Item> items) {
        stack.add(new Page(title, items));
        render(1);
    }

    private boolean pop() {
        if (stack.size() <= 1) {
            return false;
        }
        stack.remove(stack.size() - 1);
        render(-1);
        return true;
    }

    private Page page() {
        return stack.get(stack.size() - 1);
    }

    // ---- Rendering ----------------------------------------------------------------------

    /** direction: 1 = page pushed (slides in from the right), -1 = popped, 0 = fresh. */
    private void render(int direction) {
        Page page = page();
        // A popped page keeps its row; a new page starts at the top
        if (direction >= 0 || page.selected < 0 || page.selected >= page.items.size()
                || page.items.get(page.selected).header) {
            page.selected = firstSelectable(page);
        }
        for (Item item : page.items) {
            item.armed = false;
        }
        pageTitleView.setText(page.title != null ? "‹  " + page.title : "");
        pageTitleView.setVisibility(page.title != null ? VISIBLE : GONE);

        list.removeAllViews();
        rowViews.clear();
        for (int i = 0; i < page.items.size(); i++) {
            Item item = page.items.get(i);
            View row = item.header ? buildHeader(item) : buildRow(item, i);
            list.addView(row);
            rowViews.add(row);
        }
        scroll.scrollTo(0, 0);
        pill.setVisibility(INVISIBLE);
        // Place the pill once rows have a size, without sliding from the old page
        pillRetries = 0;
        list.post(() -> updateSelection(false, false));

        if (animated() && direction != 0) {
            listFrame.setTranslationX(direction * dp(28));
            listFrame.setAlpha(0f);
            listFrame.animate().translationX(0).alpha(1f).setDuration(340).setInterpolator(Spring.SOFT).start();
        }
        else if (animated()) {
            listFrame.setAlpha(0f);
            listFrame.animate().alpha(1f).setDuration(160).start();
        }
    }

    private View buildHeader(Item item) {
        TextView h = text(9.5f, 0x8CFFFFFF, true);
        h.setText(item.title);
        h.setAllCaps(true);
        h.setLetterSpacing(0.18f);
        h.setPadding(dp(8), list.getChildCount() == 0 ? 0 : dp(14), 0, dp(5));
        return h;
    }

    private View buildRow(final Item item, final int index) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(42));
        row.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(2);
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setFocusable(false);
        row.setOnClickListener(v -> {
            Page page = page();
            page.selected = index;
            updateSelection(true, false);
            activate();
        });

        RowViews views = new RowViews();
        if (item.icon != null) {
            views.icon = new ImageView(getContext());
            views.icon.setImageDrawable(PocketIcons.drawable(item.icon, dp(20), 0xFFFFFFFF));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(20), dp(20));
            ilp.setMarginEnd(dp(12));
            row.addView(views.icon, ilp);
        }

        boolean note = item.isNote();
        views.title = text(note ? 12f : 13.5f, 0xFFFFFFFF, false);
        views.title.setText(item.title);
        if (note) {
            // A plain note (tips, explanations): wrap instead of cutting it off
            views.title.setMaxLines(5);
            views.title.setLineSpacing(0, 1.12f);
            views.title.setAlpha(0.8f);
        }
        else {
            views.title.setSingleLine(true);
        }
        views.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(views.title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        views.value = text(12, 0xA3FFFFFF, true);
        views.value.setSingleLine(true);
        views.value.setPadding(dp(8), 0, 0, 0);
        row.addView(views.value);
        row.setTag(views);
        refreshRowValue(item, row, false);
        return row;
    }

    private void refreshRowValue(Item item, View row, boolean animate) {
        TextView value = ((RowViews) row.getTag()).value;
        String text;
        if (item.armed) {
            text = confirmLabel;
        }
        else if (item.stepper != null) {
            text = "‹  " + (item.value != null ? item.value : "") + "  ›";
        }
        else if (item.submenu) {
            text = item.value != null ? item.value + "  ›" : "›";
        }
        else {
            text = item.value != null ? item.value : "";
        }
        value.setText(text);
        value.setVisibility(text.isEmpty() ? GONE : VISIBLE);
        if (animate && animated()) {
            value.setTranslationX(dp(6));
            value.setAlpha(0.3f);
            value.animate().translationX(0).alpha(1f).setDuration(220).setInterpolator(Spring.POP).start();
        }
    }

    /** Moves the pill to the selected row and recolours rows. */
    private void updateSelection(boolean slide, boolean scrollIntoView) {
        Page page = page();
        View target = null;
        for (int i = 0; i < rowViews.size() && i < page.items.size(); i++) {
            Item item = page.items.get(i);
            if (item.header) {
                continue;
            }
            View row = rowViews.get(i);
            boolean selected = i == page.selected;
            RowViews views = (RowViews) row.getTag();
            int fg = selected ? 0xFF0A0A0A : 0xFFFFFFFF;
            views.title.setTextColor(fg);
            views.value.setTextColor(selected ? 0xA6000000 : (item.armed ? 0xFFFFFFFF : 0xA3FFFFFF));
            if (views.icon != null) {
                views.icon.setColorFilter(fg, PorterDuff.Mode.SRC_IN);
            }
            if (!selected && item.armed) {
                item.armed = false;
                refreshRowValue(item, row, false);
            }
            if (selected) {
                target = row;
            }
        }
        if (target == null || target.getHeight() == 0) {
            pill.setVisibility(INVISIBLE);
            if (target != null && showing && pillRetries++ < 4) {
                // Not laid out yet: try again after the next layout pass
                list.post(() -> updateSelection(false, false));
            }
            return;
        }
        ViewGroup.LayoutParams lp = pill.getLayoutParams();
        int height = target.getHeight();
        float y = target.getTop();
        boolean wasVisible = pill.getVisibility() == VISIBLE;
        pill.setVisibility(VISIBLE);
        pill.animate().cancel();
        if (slide && wasVisible && animated()) {
            if (lp.height != height) {
                lp.height = height;
                pill.setLayoutParams(lp);
            }
            pill.animate().translationY(y).setDuration(260).setInterpolator(Spring.SOFT).start();
            // A soft "press" so the move reads as physical
            pill.setScaleX(0.985f);
            pill.animate().scaleX(1f).setDuration(260).setInterpolator(Spring.POP).start();
        }
        else {
            lp.height = height;
            pill.setLayoutParams(lp);
            pill.setTranslationY(y);
            if (animated() && !wasVisible) {
                pill.setAlpha(0f);
                pill.animate().alpha(1f).setDuration(160).start();
            }
        }
        if (scrollIntoView) {
            final View row = target;
            row.post(() -> {
                int top = row.getTop() - dp(28);
                int bottom = row.getBottom() + dp(28) - scroll.getHeight();
                if (top < scroll.getScrollY()) {
                    scroll.smoothScrollTo(0, Math.max(0, top));
                }
                else if (bottom > scroll.getScrollY()) {
                    scroll.smoothScrollTo(0, bottom);
                }
            });
        }
    }

    private static int firstSelectable(Page page) {
        for (int i = 0; i < page.items.size(); i++) {
            if (!page.items.get(i).header) {
                return i;
            }
        }
        return -1;
    }

    // ---- Input --------------------------------------------------------------------------

    private void move(int direction) {
        Page page = page();
        int n = page.items.size();
        if (n == 0) {
            return;
        }
        int i = page.selected;
        for (int tries = 0; tries < n; tries++) {
            i = (i + direction + n) % n;
            if (!page.items.get(i).header) {
                break;
            }
        }
        if (i != page.selected) {
            page.selected = i;
            PocketUi.tick(getContext());
            updateSelection(true, true);
        }
    }

    private void jumpSection(int direction) {
        Page page = page();
        int n = page.items.size();
        int i = page.selected;
        for (int step = 0; step < n; step++) {
            i += direction;
            if (i < 0 || i >= n) {
                return;
            }
            if (page.items.get(i).header) {
                int target = i + 1;
                if (direction < 0) {
                    int j = i - 1;
                    while (j >= 0 && !page.items.get(j).header) {
                        j--;
                    }
                    target = j + 1;
                }
                if (target >= 0 && target < n && !page.items.get(target).header) {
                    page.selected = target;
                    PocketUi.tick(getContext());
                    updateSelection(true, true);
                }
                return;
            }
        }
    }

    private void stepValue(int direction) {
        Page page = page();
        if (page.selected < 0) {
            return;
        }
        Item item = page.items.get(page.selected);
        if (item.stepper == null) {
            return;
        }
        String before = item.value;
        item.value = item.stepper.step(direction);
        refreshRowValue(item, rowViews.get(page.selected), true);
        updateSelection(false, false);
        String on = getContext().getString(com.limelight.R.string.pocket_on);
        String off = getContext().getString(com.limelight.R.string.pocket_off);
        if (on.equals(item.value) && !on.equals(before)) {
            PocketSounds.play(getContext(), PocketSounds.TOGGLE_ON);
        }
        else if (off.equals(item.value) && !off.equals(before)) {
            PocketSounds.play(getContext(), PocketSounds.TOGGLE_OFF);
        }
        else {
            PocketUi.tick(getContext());
        }
    }

    private void activate() {
        Page page = page();
        if (page.selected < 0) {
            return;
        }
        Item item = page.items.get(page.selected);
        if (item.stepper != null) {
            stepValue(1);
            return;
        }
        if (item.confirm && !item.armed) {
            item.armed = true;
            refreshRowValue(item, rowViews.get(page.selected), true);
            updateSelection(false, false);
            PocketSounds.play(getContext(), PocketSounds.SELECT);
            return;
        }
        if (item.action == null && item.keepOpen) {
            // An info row (connection test, notes): nothing to do
            return;
        }
        PocketSounds.play(getContext(), PocketSounds.SELECT);
        if (animated()) {
            pill.animate().scaleX(0.97f).setDuration(70).withEndAction(() ->
                    pill.animate().scaleX(1f).setDuration(220).setInterpolator(Spring.POP).start()).start();
        }
        if (!item.keepOpen) {
            hide();
        }
        if (item.action != null) {
            item.action.run();
        }
    }

    private void back() {
        PocketSounds.play(getContext(), stack.size() > 1 ? PocketSounds.BACK : PocketSounds.CLOSE);
        if (!pop()) {
            hide();
        }
    }

    /** All keys go here while the menu is open. Always consumes. */
    public boolean handleKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (!down) {
            return true;
        }
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                move(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                stepValue(rtl ? 1 : -1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                stepValue(rtl ? -1 : 1);
                break;
            case KeyEvent.KEYCODE_BUTTON_L1:
                jumpSection(-1);
                break;
            case KeyEvent.KEYCODE_BUTTON_R1:
                jumpSection(1);
                break;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() == 0) {
                    activate();
                }
                break;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (event.getRepeatCount() == 0) {
                    back();
                }
                break;
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_MENU:
                if (event.getRepeatCount() == 0) {
                    PocketSounds.play(getContext(), PocketSounds.CLOSE);
                    hide();
                }
                break;
            default:
                break;
        }
        return true;
    }

    /** Left stick / hat (y down positive). Up/down move with auto-repeat; left/right change values. */
    public void onStick(float x, float y) {
        if (!showing) {
            return;
        }
        int xDir = x > 0.6f ? 1 : (x < -0.6f ? -1 : 0);
        if (xDir != stickXDir) {
            stickXDir = xDir;
            if (xDir != 0 && Math.abs(x) > Math.abs(y)) {
                boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
                stepValue(rtl ? -xDir : xDir);
            }
        }
        if (Math.abs(x) > Math.abs(y) && Math.abs(x) > 0.6f) {
            y = 0;
        }
        int dir = 0;
        if (y > 0.6f) {
            dir = 1;
        }
        else if (y < -0.6f) {
            dir = -1;
        }
        if (dir != stickDir) {
            stickDir = dir;
            handler.removeCallbacks(stickRepeat);
            if (dir != 0) {
                move(dir);
                handler.postDelayed(stickRepeat, 380);
            }
        }
    }
}
