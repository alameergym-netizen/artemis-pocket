package com.limelight.pocket;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.limelight.R;
import com.limelight.pocket.ui.AuroraView;
import com.limelight.pocket.ui.FocusGlider;
import com.limelight.pocket.ui.PocketMotion;
import com.limelight.pocket.ui.PocketTheme;
import com.limelight.pocket.ui.PocketUi;
import com.limelight.pocket.ui.PulseRingsView;
import com.limelight.pocket.ui.Spring;

import java.util.ArrayList;
import java.util.List;

/**
 * First boot setup, console style (think PS5 / Switch first boot): a few calm pages you move
 * through with A and B, each with a short title on the left and something to look at or try on the
 * right. Welcome, what the PC needs, pick your look (live), a hands-on Back button tutorial, stream
 * defaults, done. Runs once on a fresh install (after the boot sequence) and again from Settings.
 */
public class OnboardingActivity extends ConsoleActivity {

    public static final String EXTRA_RERUN = "pocket_rerun";

    private static boolean open;

    /** True while the setup is on screen (so it is never opened twice). */
    public static boolean isOpen() {
        return open;
    }

    private static final int PAGE_WELCOME = 0;
    private static final int PAGE_CONNECT = 1;
    private static final int PAGE_LOOK = 2;
    private static final int PAGE_BUTTONS = 3;
    private static final int PAGE_STREAM = 4;
    private static final int PAGE_DONE = 5;
    private static final int PAGES = 6;

    private interface IndexGetter { int get(); }
    private interface IndexSetter { void set(int index); }

    /** A row whose value changes with LEFT / RIGHT. */
    private final class Option {
        final String[] labels;
        final IndexGetter getter;
        final IndexSetter setter;
        final boolean toggle;
        TextView value;
        LinearLayout swatches;

        Option(String[] labels, IndexGetter getter, IndexSetter setter, boolean toggle) {
            this.labels = labels;
            this.getter = getter;
            this.setter = setter;
            this.toggle = toggle;
        }

        void step(int direction) {
            int n = labels.length;
            int current = Math.max(0, getter.get());
            int next = toggle ? 1 - current : ((current + direction) % n + n) % n;
            setter.set(next);
            refresh(true);
            if (toggle) {
                PocketSounds.play(OnboardingActivity.this, next == 1 ? PocketSounds.TOGGLE_ON : PocketSounds.TOGGLE_OFF);
            }
            else {
                PocketUi.tick(OnboardingActivity.this);
            }
        }

        void refresh(boolean animate) {
            int i = Math.max(0, getter.get());
            if (value != null) {
                if (toggle) {
                    boolean on = i == 1;
                    value.setText(on ? R.string.pocket_on : R.string.pocket_off);
                    if (on) {
                        value.setBackground(PocketTheme.toggleOn(OnboardingActivity.this));
                    }
                    else {
                        value.setBackgroundResource(R.drawable.pocket_toggle_off);
                    }
                    value.setTextColor(ContextCompat.getColorStateList(OnboardingActivity.this,
                            on ? R.color.pocket_toggle_on_text : R.color.pocket_toggle_off_text));
                }
                else {
                    value.setText("‹  " + labels[i] + "  ›");
                }
                if (animate && PocketPrefs.animationsEnabled(OnboardingActivity.this)) {
                    value.setScaleX(1.12f);
                    value.setScaleY(1.12f);
                    value.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(Spring.POP).start();
                }
            }
            if (swatches != null) {
                for (int s = 0; s < swatches.getChildCount(); s++) {
                    View dot = swatches.getChildAt(s);
                    boolean selected = s == i;
                    dot.animate().cancel();
                    float scale = selected ? 1.25f : 1f;
                    if (animate && PocketPrefs.animationsEnabled(OnboardingActivity.this)) {
                        dot.animate().scaleX(scale).scaleY(scale).setDuration(360).setInterpolator(Spring.POP).start();
                    }
                    else {
                        dot.setScaleX(scale);
                        dot.setScaleY(scale);
                    }
                    ((GradientDrawable) dot.getBackground()).setStroke(selected ? dp(2) : 0, 0xFFFFFFFF);
                }
            }
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int page = -1;
    private boolean switching;

    private ImageView logo;
    private LinearLayout dots;
    private LinearLayout pageView;
    private TextView kicker;
    private TextView title;
    private TextView body;
    private TextView next;
    private LinearLayout stage;
    private LinearLayout rows;
    private FocusGlider glider;
    private LinearLayout hintBar;

    // Back button tutorial
    private final TextView[] gestureTiles = new TextView[3];
    private final boolean[] gestureTried = new boolean[3];
    private boolean backDown;
    private boolean holdFired;
    private boolean tapPending;
    private boolean secondPress;
    private final Runnable holdRunnable = () -> {
        if (backDown) {
            holdFired = true;
            tapPending = false;
            handler.removeCallbacks(this.tapRunnable);
            gestureDone(ButtonActions.GESTURE_HOLD);
        }
    };
    private final Runnable tapRunnable = () -> {
        tapPending = false;
        gestureDone(ButtonActions.GESTURE_TAP);
    };

    @Override
    protected boolean allowsAutoResume() {
        return false;
    }

    @Override
    protected void onMenuButton() {
        // No settings shortcut in the middle of the setup
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        open = true;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.addView(new AuroraView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(30), dp(14), dp(30), dp(12));
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top: brand on one side, progress on the other
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_pocket_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        top.addView(logo, new LinearLayout.LayoutParams(dp(26), dp(12)));
        TextView brand = new TextView(this);
        brand.setText(R.string.pocket_brand);
        brand.setLetterSpacing(0.22f);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        brand.setTextColor(ContextCompat.getColor(this, R.color.pocket_text));
        brand.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brandLp.setMarginStart(dp(9));
        top.addView(brand, brandLp);
        top.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1f));
        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < PAGES; i++) {
            View dot = new View(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(4));
            bg.setColor(0x4DFFFFFF);
            dot.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(6));
            lp.setMarginStart(dp(6));
            dots.addView(dot, lp);
        }
        top.addView(dots);

        // The page: words on one side, something to look at / try on the other
        pageView = new LinearLayout(this);
        pageView.setOrientation(LinearLayout.HORIZONTAL);
        pageView.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(pageView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPaddingRelative(dp(6), 0, dp(26), 0);
        pageView.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        kicker = new TextView(this);
        kicker.setAllCaps(true);
        kicker.setLetterSpacing(0.2f);
        kicker.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        kicker.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        words.addView(kicker);

        title = new TextView(this);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        title.setLetterSpacing(-0.01f);
        title.setTextColor(ContextCompat.getColor(this, R.color.pocket_text));
        title.setPadding(0, dp(6), 0, dp(8));
        words.addView(title);

        body = new TextView(this);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.setLineSpacing(dp(3), 1f);
        body.setTextColor(ContextCompat.getColor(this, R.color.pocket_text_dim));
        words.addView(body);

        next = new TextView(this);
        next.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        next.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        next.setTextColor(0xFF000000);
        next.setGravity(Gravity.CENTER);
        next.setMinHeight(dp(40));
        next.setPadding(dp(22), 0, dp(22), 0);
        next.setOnClickListener(v -> advance());
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nextLp.topMargin = dp(20);
        words.addView(next, nextLp);

        stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.VERTICAL);
        stage.setGravity(Gravity.CENTER);
        pageView.addView(stage, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.05f));

        // Bottom: the controls for this page
        hintBar = new LinearLayout(this);
        hintBar.setOrientation(LinearLayout.HORIZONTAL);
        hintBar.setBackgroundResource(R.drawable.pocket_glass_pill);
        hintBar.setPadding(dp(2), dp(6), dp(14), dp(6));
        hintBar.setId(R.id.pocketHintBar);
        LinearLayout.LayoutParams hintsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintsLp.gravity = Gravity.END;
        column.addView(hintBar, hintsLp);

        setContentView(root);
        applyAccent();
        showPage(PAGE_WELCOME, 1);
    }

    @Override
    protected void onDestroy() {
        open = false;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        backDown = false;
        handler.removeCallbacks(holdRunnable);
        handler.removeCallbacks(tapRunnable);
    }

    private boolean rtl() {
        return getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    private boolean animated() {
        return PocketPrefs.animationsEnabled(this);
    }

    // ---- Accent (live while you pick it) ---------------------------------------------------

    private void applyAccent() {
        int accent = PocketTheme.accent(this);
        logo.setColorFilter(accent);
        kicker.setTextColor(accent);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(100));
        pill.setColor(accent);
        next.setBackground(pill);
        tintDots();
        if (rows != null) {
            if (glider != null) {
                rows.getViewTreeObserver().removeOnGlobalFocusChangeListener(glider);
            }
            rows.setBackground(null);
            glider = FocusGlider.install(rows, PocketTheme.withAlpha(accent, 0xF2), 14 * getResources().getDisplayMetrics().density);
            View focused = getCurrentFocus();
            if (focused != null) {
                glider.onGlobalFocusChanged(null, focused);
            }
        }
    }

    private void tintDots() {
        int accent = PocketTheme.accent(this);
        for (int i = 0; i < dots.getChildCount(); i++) {
            GradientDrawable bg = (GradientDrawable) dots.getChildAt(i).getBackground();
            bg.setColor(i == page ? accent : (i < page ? 0x99FFFFFF : 0x40FFFFFF));
        }
    }

    /** The progress pills: the current page's pill stretches out. */
    private void updateDots(boolean animate) {
        tintDots();
        for (int i = 0; i < dots.getChildCount(); i++) {
            final View dot = dots.getChildAt(i);
            final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dot.getLayoutParams();
            int target = i == page ? dp(22) : dp(6);
            if (lp.width == target) {
                continue;
            }
            if (animate && animated()) {
                ValueAnimator a = ValueAnimator.ofInt(lp.width, target);
                a.setDuration(420);
                a.setInterpolator(Spring.SOFT);
                a.addUpdateListener(an -> {
                    lp.width = (Integer) an.getAnimatedValue();
                    dot.setLayoutParams(lp);
                });
                a.start();
            }
            else {
                lp.width = target;
                dot.setLayoutParams(lp);
            }
        }
    }

    // ---- Pages ------------------------------------------------------------------------------

    private void showPage(final int target, final int direction) {
        if (target < 0 || target >= PAGES || target == page) {
            return;
        }
        final boolean first = page < 0;
        page = target;
        updateDots(!first);
        if (first || !animated()) {
            buildPage(target);
            entrance();
            return;
        }
        switching = true;
        final float dx = dp(40) * direction * (rtl() ? -1 : 1);
        pageView.animate().cancel();
        pageView.animate().alpha(0f).translationX(-dx).setStartDelay(0).setDuration(150)
                .setInterpolator(PocketUi.EASE)
                .withEndAction(() -> {
                    buildPage(target);
                    pageView.setTranslationX(dx * 0.6f);
                    pageView.animate().alpha(1f).translationX(0).setDuration(520)
                            .setInterpolator(Spring.SOFT).start();
                    entrance();
                    switching = false;
                }).start();
    }

    /** The words rise in one by one, then the stage builds up. */
    private void entrance() {
        if (!animated()) {
            pageView.setAlpha(1f);
            return;
        }
        List<View> parts = new ArrayList<>();
        parts.add(kicker);
        parts.add(title);
        parts.add(body);
        parts.add(next);
        PocketMotion.stagger(parts, 40, 55, 0, 12);
        PocketMotion.cascade(stage, 120, 60, 0, 16, 8);
        if (rows != null) {
            PocketMotion.cascade(rows, 160, 45, 0, 12, 8);
        }
    }

    private void buildPage(int p) {
        stage.removeAllViews();
        if (rows != null && glider != null) {
            rows.getViewTreeObserver().removeOnGlobalFocusChangeListener(glider);
        }
        rows = null;
        glider = null;
        backDown = false;
        handler.removeCallbacks(holdRunnable);
        handler.removeCallbacks(tapRunnable);

        int nextLabel = R.string.pocket_ob_continue;
        switch (p) {
            case PAGE_WELCOME:
                setWords(R.string.pocket_ob_kicker_welcome, R.string.pocket_ob_welcome_title, R.string.pocket_ob_welcome_body);
                nextLabel = R.string.pocket_ob_start;
                buildWelcomeStage();
                break;
            case PAGE_CONNECT:
                setWords(R.string.pocket_ob_kicker_connect, R.string.pocket_ob_connect_title, R.string.pocket_ob_connect_body);
                buildConnectStage();
                break;
            case PAGE_LOOK:
                setWords(R.string.pocket_ob_kicker_look, R.string.pocket_ob_look_title, R.string.pocket_ob_look_body);
                buildLookStage();
                break;
            case PAGE_BUTTONS:
                setWords(R.string.pocket_ob_kicker_buttons, R.string.pocket_ob_buttons_title, R.string.pocket_ob_buttons_body);
                buildButtonsStage();
                break;
            case PAGE_STREAM:
                setWords(R.string.pocket_ob_kicker_stream, R.string.pocket_ob_stream_title, R.string.pocket_ob_stream_body);
                buildStreamStage();
                break;
            default:
                setWords(R.string.pocket_ob_kicker_done, R.string.pocket_ob_done_title, R.string.pocket_ob_done_body);
                nextLabel = R.string.pocket_ob_finish;
                buildDoneStage();
                break;
        }
        next.setText(getString(nextLabel) + "   →");
        if (rtl()) {
            next.setText("←   " + getString(nextLabel));
        }
        updateHints();

        if (rows != null && rows.getChildCount() > 0) {
            applyAccent();
            final View firstRow = rows.getChildAt(0);
            firstRow.post(firstRow::requestFocus);
        }
        else {
            View focused = getCurrentFocus();
            if (focused != null) {
                focused.clearFocus();
            }
        }
    }

    private void setWords(int k, int t, int b) {
        kicker.setText(k);
        title.setText(t);
        body.setText(b);
    }

    private void updateHints() {
        List<Hint> hints = new ArrayList<>();
        if (rows != null) {
            hints.add(new Hint("◀ ▶", 0xFFFFFFFF, getString(R.string.pocket_ob_hint_change)));
        }
        if (page == PAGE_BUTTONS) {
            hints.add(new Hint("↩", 0xFFFFFFFF, getString(R.string.pocket_ob_try_back)));
        }
        hints.add(hintA(this, page == PAGE_DONE ? R.string.pocket_ob_finish : R.string.pocket_ob_continue));
        if (page > 0) {
            hints.add(hintB(this, R.string.pocket_hint_back));
        }
        if (page < PAGE_DONE) {
            hints.add(hintX(this, R.string.pocket_ob_skip));
        }
        fillHintBar(this, hintBar, hints.toArray(new Hint[0]));
    }

    private TextView text(CharSequence s, float sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(ContextCompat.getColor(this, color));
        if (medium) {
            t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        }
        return t;
    }

    private LinearLayout glassPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.pocket_panel_bg);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        return panel;
    }

    // Welcome: the logo, alive, and three things to know
    private void buildWelcomeStage() {
        FrameLayout hero = new FrameLayout(this);
        hero.addView(new PulseRingsView(this), new FrameLayout.LayoutParams(dp(230), dp(230), Gravity.CENTER));
        ImageView big = new ImageView(this);
        big.setImageResource(R.drawable.ic_pocket_logo);
        big.setColorFilter(PocketTheme.accent(this));
        big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        hero.addView(big, new FrameLayout.LayoutParams(dp(112), dp(53), Gravity.CENTER));
        stage.addView(hero, new LinearLayout.LayoutParams(dp(230), dp(200)));
        if (animated()) {
            big.setScaleX(0.8f);
            big.setScaleY(0.8f);
            big.animate().scaleX(1f).scaleY(1f).setStartDelay(160).setDuration(700).setInterpolator(Spring.POP).start();
        }

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER);
        int[] labels = {R.string.pocket_ob_chip_120, R.string.pocket_ob_chip_vd, R.string.pocket_ob_chip_usb};
        for (int label : labels) {
            TextView chip = text(getString(label), 11, R.color.pocket_text, true);
            chip.setBackgroundResource(R.drawable.pocket_glass_pill);
            chip.setPadding(dp(12), dp(5), dp(12), dp(5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(4));
            lp.setMarginEnd(dp(4));
            chips.addView(chip, lp);
        }
        stage.addView(chips);
    }

    // What the PC needs: three numbered steps and a note
    private void buildConnectStage() {
        LinearLayout panel = glassPanel();
        int[][] steps = {
                {R.string.pocket_ob_step1_title, R.string.pocket_ob_step1_sub},
                {R.string.pocket_ob_step2_title, R.string.pocket_ob_step2_sub},
                {R.string.pocket_ob_step3_title, R.string.pocket_ob_step3_sub},
        };
        int accent = PocketTheme.accent(this);
        for (int i = 0; i < steps.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));

            TextView number = text(String.valueOf(i + 1), 12, R.color.pocket_text, true);
            number.setTextColor(0xFF000000);
            number.setGravity(Gravity.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(accent);
            number.setBackground(circle);
            row.addView(number, new LinearLayout.LayoutParams(dp(26), dp(26)));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPaddingRelative(dp(12), 0, 0, 0);
            texts.addView(text(getString(steps[i][0]), 13.5f, R.color.pocket_text, true));
            TextView sub = text(getString(steps[i][1]), 11, R.color.pocket_text_dim, false);
            sub.setPadding(0, dp(1), 0, 0);
            texts.addView(sub);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            panel.addView(row);
        }
        stage.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = text(getString(R.string.pocket_ob_helper_note), 11, R.color.pocket_text_faint, false);
        note.setPadding(dp(6), dp(10), dp(6), 0);
        stage.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private SharedPreferences prefs() {
        return PocketPrefs.prefs(this);
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return -1;
    }

    private void startRows() {
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        stage.addView(rows, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private LinearLayout addRow(String label, String sub, final Option option) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(46));
        row.setPaddingRelative(dp(14), dp(7), dp(12), dp(7));
        row.setBackground(PocketTheme.rowGlide(this));
        row.setFocusable(true);
        row.setClickable(true);
        row.setTag(option);
        row.setOnClickListener(v -> option.step(1));
        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                PocketUi.tick(this);
            }
        });

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setDuplicateParentStateEnabled(true);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        t.setTextColor(ContextCompat.getColorStateList(this, R.color.pocket_row_text));
        t.setDuplicateParentStateEnabled(true);
        texts.addView(t);
        if (!TextUtils.isEmpty(sub)) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            s.setTextColor(ContextCompat.getColorStateList(this, R.color.pocket_row_text_dim));
            s.setDuplicateParentStateEnabled(true);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        rows.addView(row, lp);
        return row;
    }

    private void addChoice(String label, String sub, Option option) {
        LinearLayout row = addRow(label, sub, option);
        TextView value = new TextView(this);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        value.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        value.setTextColor(ContextCompat.getColorStateList(this, R.color.pocket_row_text));
        value.setDuplicateParentStateEnabled(true);
        value.setGravity(Gravity.CENTER);
        value.setMinWidth(dp(110));
        row.addView(value);
        option.value = value;
        option.refresh(false);
    }

    private void addToggle(String label, String sub, Option option) {
        LinearLayout row = addRow(label, sub, option);
        TextView pill = new TextView(this);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        pill.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        pill.setGravity(Gravity.CENTER);
        pill.setMinWidth(dp(52));
        pill.setPadding(dp(10), dp(4), dp(10), dp(4));
        pill.setDuplicateParentStateEnabled(true);
        row.addView(pill);
        option.value = pill;
        option.refresh(false);
    }

    // Look: accent swatches (live), background, sounds, motion
    private void buildLookStage() {
        startRows();
        final int[] accents = PocketTheme.ACCENTS;
        String[] accentLabels = {getString(R.string.pocket_accent_white), getString(R.string.pocket_accent_ice),
                getString(R.string.pocket_accent_mint), getString(R.string.pocket_accent_lilac),
                getString(R.string.pocket_accent_rose), getString(R.string.pocket_accent_amber)};
        Option accent = new Option(accentLabels, () -> {
            int current = PocketPrefs.accentColor(this);
            for (int i = 0; i < accents.length; i++) {
                if (accents[i] == current) {
                    return i;
                }
            }
            return 0;
        }, index -> {
            prefs().edit().putInt(PocketPrefs.KEY_ACCENT, accents[index]).apply();
            applyAccent();
        }, false);
        LinearLayout accentRow = addRow(getString(R.string.pocket_set_accent), null, accent);
        LinearLayout swatches = new LinearLayout(this);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        swatches.setGravity(Gravity.CENTER_VERTICAL);
        swatches.setDuplicateParentStateEnabled(true);
        for (int color : accents) {
            View dot = new View(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(color);
            dot.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(16), dp(16));
            lp.setMarginStart(dp(7));
            swatches.addView(dot, lp);
        }
        accentRow.addView(swatches);
        accent.swatches = swatches;
        accent.refresh(false);

        final String[] backdropValues = {PocketPrefs.BACKDROP_ART, PocketPrefs.BACKDROP_GLOW, PocketPrefs.BACKDROP_BLACK};
        String[] backdropLabels = {getString(R.string.pocket_backdrop_art), getString(R.string.pocket_backdrop_glow),
                getString(R.string.pocket_backdrop_black)};
        addChoice(getString(R.string.pocket_set_backdrop), null, new Option(backdropLabels,
                () -> indexOf(backdropValues, PocketPrefs.backdrop(this)),
                index -> prefs().edit().putString(PocketPrefs.KEY_BACKDROP, backdropValues[index]).apply(), false));

        String[] styleLabels = {getString(R.string.pocket_sound_glass), getString(R.string.pocket_sound_soft),
                getString(R.string.pocket_sound_crisp)};
        addChoice(getString(R.string.pocket_set_sound_style), null, new Option(styleLabels,
                () -> PocketPrefs.soundStyleIndex(this),
                index -> {
                    prefs().edit().putString(PocketPrefs.KEY_SOUND_STYLE, PocketPrefs.SOUND_STYLES[index]).apply();
                    PocketSounds.reload(this);
                    handler.postDelayed(() -> PocketSounds.preview(this, PocketSounds.CATEGORY_MENU), 260);
                }, false));

        String[] onOff = {getString(R.string.pocket_off), getString(R.string.pocket_on)};
        addToggle(getString(R.string.pocket_set_animations), getString(R.string.pocket_set_animations_sub), new Option(onOff,
                () -> PocketPrefs.animationsEnabled(this) ? 1 : 0,
                index -> prefs().edit().putBoolean(PocketPrefs.KEY_ANIMATIONS, index == 1).apply(), true));
    }

    // Controls: try the Back button's three moves
    private void buildButtonsStage() {
        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.setGravity(Gravity.CENTER);
        String[] marks = {"●", "● ●", "━━"};
        for (int g = 0; g < 3; g++) {
            gestureTried[g] = false;
            PocketAction action = ButtonActions.get(this, ButtonActions.BUTTON_BACK, g);
            TextView tile = new TextView(this);
            tile.setGravity(Gravity.CENTER);
            tile.setTextColor(ContextCompat.getColor(this, R.color.pocket_text));
            tile.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tile.setLineSpacing(dp(2), 1f);
            tile.setText(marks[g] + "\n\n" + getString(ButtonActions.gestureLabel(g)) + "\n"
                    + (action == PocketAction.NONE ? "—" : action.shortLabel(this)));
            tile.setBackground(tileBackground(false));
            tile.setPadding(dp(8), dp(14), dp(8), dp(14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(132), 1f);
            lp.setMarginStart(dp(5));
            lp.setMarginEnd(dp(5));
            tiles.addView(tile, lp);
            gestureTiles[g] = tile;
        }
        stage.addView(tiles, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView more = text(getString(R.string.pocket_ob_buttons_more), 11, R.color.pocket_text_faint, false);
        more.setGravity(Gravity.CENTER);
        more.setPadding(dp(6), dp(14), dp(6), 0);
        stage.addView(more, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private GradientDrawable tileBackground(boolean lit) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        int accent = PocketTheme.accent(this);
        if (lit) {
            bg.setColor(PocketTheme.withAlpha(accent, 0x38));
            bg.setStroke(dp(2), accent);
        }
        else {
            bg.setColor(0x14FFFFFF);
            bg.setStroke(Math.max(1, dp(1) / 2 + 1), 0x1FFFFFFF);
        }
        return bg;
    }

    private void practiceDown() {
        if (backDown) {
            practiceUp();
        }
        backDown = true;
        holdFired = false;
        secondPress = tapPending;
        if (secondPress) {
            tapPending = false;
            handler.removeCallbacks(tapRunnable);
        }
        else {
            handler.postDelayed(holdRunnable, 420);
        }
    }

    private void practiceUp() {
        if (!backDown) {
            return;
        }
        backDown = false;
        handler.removeCallbacks(holdRunnable);
        if (holdFired) {
            holdFired = false;
            return;
        }
        if (secondPress) {
            secondPress = false;
            gestureDone(ButtonActions.GESTURE_DOUBLE);
            return;
        }
        tapPending = true;
        handler.postDelayed(tapRunnable, 260);
    }

    private void gestureDone(int gesture) {
        if (page != PAGE_BUTTONS || gestureTiles[gesture] == null) {
            return;
        }
        final TextView tile = gestureTiles[gesture];
        tile.setBackground(tileBackground(true));
        PocketSounds.play(this, gesture == ButtonActions.GESTURE_HOLD ? PocketSounds.RADIAL_OPEN : PocketSounds.SELECT);
        if (animated()) {
            tile.animate().cancel();
            tile.setScaleX(1.1f);
            tile.setScaleY(1.1f);
            tile.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(460).setInterpolator(Spring.POP).start();
        }
        gestureTried[gesture] = true;
        if (gestureTried[0] && gestureTried[1] && gestureTried[2]) {
            PocketMotion.swapText(body, getString(R.string.pocket_ob_buttons_done), 1);
        }
        // The tile glows for a moment, then rests as "done"
        handler.postDelayed(() -> {
            if (page == PAGE_BUTTONS) {
                GradientDrawable bg = tileBackground(false);
                bg.setStroke(dp(2), PocketTheme.withAlpha(PocketTheme.accent(this), 0x99));
                tile.setBackground(bg);
            }
        }, 700);
    }

    // Streaming: picture preset, virtual display, reconnect, resume
    private void buildStreamStage() {
        startRows();
        final String[] presetKeys = {PocketSetup.PRESET_BATTERY, PocketSetup.PRESET_BALANCED, PocketSetup.PRESET_QUALITY};
        String[] presetLabels = {getString(R.string.pocket_preset_battery), getString(R.string.pocket_preset_balanced),
                getString(R.string.pocket_preset_quality)};
        addChoice(getString(R.string.pocket_set_preset), getString(R.string.pocket_set_preset_sub), new Option(presetLabels,
                () -> {
                    int i = indexOf(presetKeys, prefs().getString(PocketPrefs.KEY_PRESET, PocketSetup.PRESET_QUALITY));
                    return i < 0 ? 2 : i;
                },
                index -> {
                    SharedPreferences.Editor e = prefs().edit();
                    PocketSetup.applyPresetValues(e, presetKeys[index]);
                    e.apply();
                }, false));

        String[] onOff = {getString(R.string.pocket_off), getString(R.string.pocket_on)};
        addToggle(getString(R.string.pocket_set_vdisplay), getString(R.string.pocket_set_vdisplay_sub), new Option(onOff,
                () -> prefs().getBoolean("checkbox_use_virtual_display", true) ? 1 : 0,
                index -> prefs().edit().putBoolean("checkbox_use_virtual_display", index == 1).apply(), true));
        addToggle(getString(R.string.pocket_set_auto_recover), getString(R.string.pocket_set_auto_recover_sub), new Option(onOff,
                () -> prefs().getBoolean(PocketPrefs.KEY_AUTO_RECOVER, true) ? 1 : 0,
                index -> prefs().edit().putBoolean(PocketPrefs.KEY_AUTO_RECOVER, index == 1).apply(), true));
        addToggle(getString(R.string.pocket_set_resume), getString(R.string.pocket_set_resume_sub), new Option(onOff,
                () -> PocketPrefs.seamlessResume(this) ? 1 : 0,
                index -> prefs().edit().putBoolean(PocketPrefs.KEY_SEAMLESS_RESUME, index == 1).apply(), true));
    }

    // Done: a check that draws itself inside the pulse
    private void buildDoneStage() {
        FrameLayout hero = new FrameLayout(this);
        hero.addView(new PulseRingsView(this), new FrameLayout.LayoutParams(dp(230), dp(230), Gravity.CENTER));
        hero.addView(new CheckMark(this), new FrameLayout.LayoutParams(dp(92), dp(92), Gravity.CENTER));
        stage.addView(hero, new LinearLayout.LayoutParams(dp(230), dp(230)));
        // A soft chime as the tick lands
        handler.postDelayed(() -> {
            if (page == PAGE_DONE) {
                PocketSounds.play(this, PocketSounds.TOGGLE_ON);
            }
        }, 820);
    }

    /** A circle and a tick that draw themselves (the "all set" moment). */
    private static final class CheckMark extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tick = new Path();
        private final Path piece = new Path();
        private final RectF oval = new RectF();
        private final long start = SystemClock.uptimeMillis();
        private final boolean animated;
        private PathMeasure measure;

        CheckMark(Context context) {
            super(context);
            float d = getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(4 * d);
            paint.setColor(PocketTheme.accent(context));
            animated = PocketPrefs.animationsEnabled(context);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            float s = Math.min(w, h);
            float inset = paint.getStrokeWidth();
            oval.set(inset, inset, s - inset, s - inset);
            tick.reset();
            tick.moveTo(s * 0.29f, s * 0.52f);
            tick.lineTo(s * 0.44f, s * 0.66f);
            tick.lineTo(s * 0.72f, s * 0.37f);
            measure = new PathMeasure(tick, false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (measure == null) {
                return;
            }
            float t = animated ? (SystemClock.uptimeMillis() - start - 250) / 1000f : 2f;
            float ring = Math.max(0f, Math.min(1f, t / 0.55f));
            float check = Math.max(0f, Math.min(1f, (t - 0.45f) / 0.35f));
            ring = 1 - (1 - ring) * (1 - ring) * (1 - ring);
            check = 1 - (1 - check) * (1 - check);
            canvas.drawArc(oval, -90, 360 * ring, false, paint);
            if (check > 0) {
                piece.reset();
                measure.getSegment(0, measure.getLength() * check, piece, true);
                canvas.drawPath(piece, paint);
            }
            if (animated && check < 1f) {
                postInvalidateOnAnimation();
            }
        }
    }

    // ---- Moving through ----------------------------------------------------------------------

    private void advance() {
        if (switching) {
            return;
        }
        PocketSounds.play(this, PocketSounds.SELECT);
        if (page >= PAGE_DONE) {
            finishSetup();
            return;
        }
        showPage(page + 1, 1);
    }

    private void back() {
        if (switching || page <= 0) {
            return;
        }
        PocketSounds.play(this, PocketSounds.BACK);
        showPage(page - 1, -1);
    }

    private void finishSetup() {
        PocketPrefs.setOnboardingPending(this, false);
        finish();
        overridePendingTransition(R.anim.pocket_pop_enter, R.anim.pocket_pop_exit);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event);
        }
        int action = event.getAction();
        boolean firstDown = action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0;
        if (firstDown) {
            pulseHint(hintBar, keyCode);
        }
        if (switching) {
            return true;
        }

        // On the controls page the Back button is for practising
        if (page == PAGE_BUTTONS && PocketPrefs.matchesBack(PocketPrefs.getBackKey(this), event)) {
            if (firstDown) {
                practiceDown();
            }
            else if (action == KeyEvent.ACTION_UP) {
                practiceUp();
            }
            return true;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (firstDown) {
                    advance();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (firstDown) {
                    back();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (firstDown && page < PAGE_DONE) {
                    PocketSounds.play(this, PocketSounds.CLOSE);
                    finishSetup();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (action == KeyEvent.ACTION_DOWN) {
                    View focused = getCurrentFocus();
                    if (focused != null && focused.getTag() instanceof Option) {
                        boolean right = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
                        ((Option) focused.getTag()).step(right != rtl() ? 1 : -1);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_R2:
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }
}
