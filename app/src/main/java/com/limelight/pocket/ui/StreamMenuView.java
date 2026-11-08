package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.pocket.ConsoleActivity;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.PocketSounds;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-stream console menu: a glass side panel driven entirely by the controller
 * (D-pad / left stick to move, A to choose, B to go back, left/right to change values,
 * L1/R1 to jump between sections). It never takes Android focus, so it works the same
 * with any controller, keyboard or touch.
 */
public class StreamMenuView extends FrameLayout {

    public interface ValueStepper {
        /** Change the value (direction -1 / +1) and return the new text to show. */
        String step(int direction);
    }

    public static final class Item {
        final String title;
        String value;
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

    private final float d;
    private final View scrim;
    private final LinearLayout panel;
    private final TextView titleView;
    private final TextView subtitleView;
    private final TextView statsView;
    private final TextView pageTitleView;
    private final ScrollView scroll;
    private final LinearLayout list;
    private final LinearLayout hints;
    private final List<Page> stack = new ArrayList<>();
    private final List<View> rowViews = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean showing;
    private String confirmLabel = "Press A again";

    // Left-stick / D-pad repeat
    private int stickDir;
    private int stickXDir;
    private final Runnable stickRepeat = new Runnable() {
        @Override
        public void run() {
            if (stickDir != 0 && showing) {
                move(stickDir);
                handler.postDelayed(this, 110);
            }
        }
    };

    public StreamMenuView(Context context) {
        super(context);
        d = getResources().getDisplayMetrics().density;
        setVisibility(GONE);
        setClickable(true);
        setFocusable(false);

        scrim = new View(context);
        scrim.setBackgroundColor(0x99000000);
        scrim.setOnClickListener(v -> hide());
        addView(scrim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF20E0E0E);
        bg.setStroke(Math.max(1, Math.round(d)), 0x26FFFFFF);
        float r = 22 * d;
        bg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        panel.setBackground(bg);
        panel.setPadding(dp(22), dp(18), dp(18), dp(12));
        LayoutParams panelLp = new LayoutParams(dp(330), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
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
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(8));
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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

    private int dp(float v) {
        return Math.round(v * d);
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
        statsView.setText(stats);
        statsView.setVisibility(stats == null || stats.isEmpty() ? GONE : VISIBLE);
        ConsoleActivity.fillHintBar(getContext(), hints, hintItems);
        stack.clear();
        stack.add(new Page(null, root));
        render(true);
        showing = true;
        PocketSounds.play(getContext(), PocketSounds.OPEN);

        animate().cancel();
        panel.animate().cancel();
        setVisibility(VISIBLE);
        if (PocketPrefs.animationsEnabled(getContext())) {
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(160).start();
            panel.setTranslationX(-dp(40));
            panel.setAlpha(0f);
            panel.animate().translationX(0).alpha(1f).setDuration(200).setInterpolator(PocketUi.EASE).start();
        }
        else {
            scrim.setAlpha(1f);
            panel.setTranslationX(0);
            panel.setAlpha(1f);
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
        if (PocketPrefs.animationsEnabled(getContext())) {
            scrim.animate().alpha(0f).setDuration(130).start();
            panel.animate().translationX(-dp(30)).alpha(0f).setDuration(130).setInterpolator(PocketUi.EASE)
                    .withEndAction(() -> setVisibility(GONE)).start();
        }
        else {
            setVisibility(GONE);
        }
        if (listener != null) {
            listener.onMenuClosed();
        }
    }

    /** Opens a sub page (Shortcuts, PC commands, ...). B comes back. */
    public void push(String title, List<Item> items) {
        stack.add(new Page(title, items));
        render(true);
    }

    private boolean pop() {
        if (stack.size() <= 1) {
            return false;
        }
        stack.remove(stack.size() - 1);
        render(false);
        return true;
    }

    private Page page() {
        return stack.get(stack.size() - 1);
    }

    // ---- Rendering ----------------------------------------------------------------------

    private void render(boolean resetSelection) {
        Page page = page();
        if (resetSelection || page.selected < 0 || page.selected >= page.items.size()
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
        updateSelection(false);
        scroll.scrollTo(0, 0);
        if (PocketPrefs.animationsEnabled(getContext())) {
            list.setAlpha(0f);
            list.animate().alpha(1f).setDuration(120).start();
        }
    }

    private View buildHeader(Item item) {
        TextView h = text(9.5f, 0x8CFFFFFF, true);
        h.setText(item.title);
        h.setAllCaps(true);
        h.setLetterSpacing(0.18f);
        h.setPadding(dp(6), list.getChildCount() == 0 ? 0 : dp(12), 0, dp(4));
        return h;
    }

    private View buildRow(final Item item, final int index) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(40));
        row.setPadding(dp(12), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(3);
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setFocusable(false);
        row.setOnClickListener(v -> {
            Page page = page();
            page.selected = index;
            updateSelection(false);
            activate();
        });

        boolean note = item.action == null && item.stepper == null && item.value == null && !item.submenu;
        TextView title = text(note ? 12f : 13.5f, 0xFFFFFFFF, false);
        title.setText(item.title);
        if (note) {
            // A plain note (tips, explanations): wrap instead of cutting it off
            title.setMaxLines(5);
            title.setLineSpacing(0, 1.12f);
        }
        else {
            title.setSingleLine(true);
        }
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = text(12, 0xA3FFFFFF, true);
        value.setSingleLine(true);
        value.setPadding(dp(8), 0, 0, 0);
        row.addView(value);
        row.setTag(new TextView[]{title, value});
        refreshRowValue(item, row);
        return row;
    }

    private void refreshRowValue(Item item, View row) {
        TextView value = ((TextView[]) row.getTag())[1];
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
    }

    private void updateSelection(boolean scrollIntoView) {
        Page page = page();
        for (int i = 0; i < rowViews.size(); i++) {
            Item item = page.items.get(i);
            if (item.header) {
                continue;
            }
            View row = rowViews.get(i);
            boolean selected = i == page.selected;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(12 * d);
            bg.setColor(selected ? PocketTheme.withAlpha(PocketTheme.accent(getContext()), 0xF2) : 0x00000000);
            row.setBackground(bg);
            TextView[] views = (TextView[]) row.getTag();
            views[0].setTextColor(selected ? 0xFF000000 : 0xFFFFFFFF);
            views[1].setTextColor(selected ? 0x99000000 : (item.armed ? 0xFFFFFFFF : 0xA3FFFFFF));
            if (!selected && item.armed) {
                item.armed = false;
                refreshRowValue(item, row);
            }
        }
        if (scrollIntoView && page.selected >= 0 && page.selected < rowViews.size()) {
            final View row = rowViews.get(page.selected);
            row.post(() -> {
                int top = row.getTop() - dp(24);
                int bottom = row.getBottom() + dp(24) - scroll.getHeight();
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
            updateSelection(true);
        }
    }

    private void jumpSection(int direction) {
        Page page = page();
        int n = page.items.size();
        int i = page.selected;
        // Walk to the next/previous header, then to the first row after it
        for (int step = 0; step < n; step++) {
            i += direction;
            if (i < 0 || i >= n) {
                return;
            }
            if (page.items.get(i).header) {
                int target = i + 1;
                if (direction < 0) {
                    // previous section: find its header first
                    int j = i - 1;
                    while (j >= 0 && !page.items.get(j).header) {
                        j--;
                    }
                    target = j + 1;
                }
                if (target >= 0 && target < n && !page.items.get(target).header) {
                    page.selected = target;
                    PocketUi.tick(getContext());
                    updateSelection(true);
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
        item.value = item.stepper.step(direction);
        refreshRowValue(item, rowViews.get(page.selected));
        PocketUi.tick(getContext());
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
            refreshRowValue(item, rowViews.get(page.selected));
            updateSelection(false);
            PocketSounds.play(getContext(), PocketSounds.SELECT);
            return;
        }
        PocketSounds.play(getContext(), PocketSounds.SELECT);
        if (!item.keepOpen) {
            hide();
        }
        if (item.action != null) {
            item.action.run();
        }
    }

    private void back() {
        PocketSounds.play(getContext(), PocketSounds.BACK);
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
                    PocketSounds.play(getContext(), PocketSounds.BACK);
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
