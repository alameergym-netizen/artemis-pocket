package com.limelight.pocket.ui;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.pocket.ConsoleActivity;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.PocketSounds;

import java.util.ArrayList;
import java.util.List;

/**
 * The hero "lifted" (like a console's game hub): UP from the row gives the hero the focus. Its
 * Play / Resume button grows and lights up, the name and buttons glide down to where the row was,
 * the row sinks away and the shades over the art clear, so the key art and the logo take the
 * screen. A plays, DOWN or B puts everything back; LEFT / RIGHT still go through the games.
 *
 * The real focus stays on the card in the row the whole time (so the row, its lens and every
 * button keep working as before); this only moves views and takes the keys it needs.
 */
public final class HeroFocus {

    public interface Host {
        /** True when the row under the hero has the focus (UP from it lifts the hero). */
        boolean heroCanLift();

        /** A on the lifted hero, or a touch on Play: what A on the focused card does. */
        void heroPlay();
    }

    // Play lights up where it is: a little growth and a ring, nothing slides around (moving the
    // whole hero pushed Play outside its box, where it was cut off)
    private static final float PLAY_GROWTH = 0.07f;
    private static final float LOGO_GROWTH = 0f;
    private static final float TITLE_GROWTH = 0f;

    private final Activity activity;
    private final Host host;
    private final View hero;
    private final TextView play;
    private final View logo;
    private final View title;
    private final View section;
    private final View row;
    private final View heroScrim;
    private final View bottomScrim;
    private final ViewGroup hintBar;
    private final GradientDrawable ring = new GradientDrawable();
    /** The ring a little outside Play (Play is white: a ring on it wouldn't show). */
    private android.graphics.drawable.Drawable ringOutside;
    private final float d;

    private final Spring spring = new Spring(260f, 0.9f, 0f);
    private final FrameClock clock = new FrameClock(this::frame);
    private long lastNs;
    private boolean active;
    private boolean applied;
    private boolean pressed;
    private int swallowUp = -1;
    private List<View> savedHints;
    private View heroHint;

    private HeroFocus(Activity activity, Host host, View hero, TextView play, View row) {
        this.activity = activity;
        this.host = host;
        this.hero = hero;
        this.play = play;
        this.row = row;
        this.logo = activity.findViewById(R.id.pocketHeroLogo);
        this.title = activity.findViewById(R.id.pocketHeroTitle);
        this.section = activity.findViewById(R.id.pocketSectionTitle);
        this.heroScrim = activity.findViewById(R.id.pocketHeroScrim);
        this.bottomScrim = activity.findViewById(R.id.pocketBottomScrim);
        this.hintBar = activity.findViewById(R.id.pocketHintBar);
        this.d = activity.getResources().getDisplayMetrics().density;
        ring.setCornerRadius(PocketUi.PLAY_RADIUS_DP * d);
        ring.setColor(0x00000000);
        ring.setStroke(Math.max(2, Math.round(2 * d)), 0xFFFFFFFF);
        ring.setAlpha(0);
        ringOutside = new android.graphics.drawable.InsetDrawable(ring, -Math.round(4.5f * d));
        play.setClickable(true);
        play.setOnClickListener(v -> {
            PocketSounds.play(activity, PocketSounds.SELECT);
            host.heroPlay();
        });
    }

    /** The hero of this screen (null when its layout has none). row: what sits under the hero. */
    public static HeroFocus install(Activity activity, Host host, int rowId) {
        View hero = activity.findViewById(R.id.pocketHero);
        TextView play = activity.findViewById(R.id.pocketHeroPlay);
        View row = activity.findViewById(rowId);
        if (hero == null || play == null || row == null) {
            return null;
        }
        if (hero instanceof ViewGroup) {
            // The grown button and logo reach a little outside their boxes
            ((ViewGroup) hero).setClipChildren(false);
            ((ViewGroup) hero).setClipToPadding(false);
        }
        if (play.getParent() instanceof ViewGroup) {
            ((ViewGroup) play.getParent()).setClipChildren(false);
        }
        return new HeroFocus(activity, host, hero, play, row);
    }

    public boolean isActive() {
        return active;
    }

    // ---- Keys and touch -----------------------------------------------------------------------

    /** Takes the keys the lifted hero needs (true: used). Called before the screen's own keys. */
    public boolean handleKey(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (swallowUp >= 0 && key == swallowUp) {
            if (!down) {
                swallowUp = -1;
            }
            return true;
        }
        if (!active) {
            if (key == KeyEvent.KEYCODE_DPAD_UP && down && event.getRepeatCount() == 0 && canLift()) {
                enter();
                swallowUp = key;
                return true;
            }
            return false;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return true; // nothing above the hero
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (down && event.getRepeatCount() == 0) {
                    exit(true);
                    if (key != KeyEvent.KEYCODE_DPAD_DOWN) {
                        PocketSounds.play(activity, PocketSounds.BACK);
                    }
                    // Its release belongs to this press, not to the screen (B would leave it)
                    swallowUp = key;
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (down) {
                    if (event.getRepeatCount() == 0) {
                        pressed = true;
                        play.animate().alpha(0.82f).setDuration(70).withEndAction(null).start();
                    }
                }
                else if (pressed) {
                    pressed = false;
                    play.animate().alpha(1f).setDuration(220).withEndAction(null).start();
                    PocketSounds.play(activity, PocketSounds.SELECT);
                    host.heroPlay();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return false; // still goes through the games, with the hero lifted
            default:
                // Anything else (details, options, settings...) acts on the card as usual,
                // which is back in its place first
                if (down && event.getRepeatCount() == 0 && !isVolume(key)) {
                    reset();
                }
                return false;
        }
    }

    /** A touch anywhere but Play puts the hero back. */
    public void onTouch(MotionEvent event) {
        if (!active || event.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return;
        }
        int[] at = new int[2];
        play.getLocationOnScreen(at);
        float x = event.getRawX();
        float y = event.getRawY();
        float w = play.getWidth() * play.getScaleX();
        float h = play.getHeight() * play.getScaleY();
        if (x < at[0] || x > at[0] + w || y < at[1] || y > at[1] + h) {
            exit(true);
        }
    }

    private static boolean isVolume(int key) {
        return key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN
                || key == KeyEvent.KEYCODE_VOLUME_MUTE;
    }

    private boolean canLift() {
        if (LibraryLayout.isVertical(activity)) {
            return false; // grid and list: UP moves in the library
        }
        return play.getVisibility() == View.VISIBLE && play.isShown() && row.isShown() && row.getHeight() > 0
                && host.heroCanLift();
    }

    // ---- Lifting and putting back -------------------------------------------------------------

    public void enter() {
        if (active) {
            return;
        }
        active = true;
        PocketUi.tick(activity);
        showHeroHints();
        moveTo(1f, true);
    }

    public void exit(boolean animate) {
        if (!active && spring.target == 0f) {
            return;
        }
        active = false;
        pressed = false;
        if (animate) {
            PocketUi.tick(activity);
        }
        restoreHints();
        moveTo(0f, animate);
    }

    /** Back in place at once (leaving the screen, a dialog, another button). */
    public void reset() {
        swallowUp = -1;
        exit(false);
    }

    private void moveTo(float target, boolean animate) {
        if (!animate || !PocketPrefs.animationsEnabled(activity)) {
            clock.cancel();
            lastNs = 0;
            spring.snap(target);
            apply(target);
            setMoving(false);
            return;
        }
        spring.target = target;
        setMoving(true);
        clock.request();
    }

    private void frame() {
        long now = clock.now();
        float dt = lastNs == 0 ? 1f / 60f : Math.min(0.05f, (now - lastNs) / 1e9f);
        lastNs = now;
        boolean moving = spring.step(dt);
        apply(spring.value);
        if (moving) {
            clock.request();
        }
        else {
            lastNs = 0;
            setMoving(false);
        }
    }

    /** While it moves, the sinking row is drawn once into a layer instead of fading per card. */
    private void setMoving(boolean moving) {
        int type = moving ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
        if (row.getLayerType() != type) {
            row.setLayerType(type, null);
        }
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** Puts every part where it is at this much lifted (0 = in place, 1 = lifted). */
    private void apply(float p) {
        if (p == 0f && !applied) {
            return;
        }
        applied = p != 0f;
        float c = clamp(p);
        boolean rtl = hero.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float rowH = row.getHeight();

        // The hero stays put; the row steps back a little so Play is clearly what's chosen
        hero.setTranslationY(0f);
        row.setTranslationY(rowH * 0.06f * p);
        row.setAlpha(1f - 0.45f * c);

        // Play grows (from its start edge) and lights up with a ring
        float playScale = 1f + PLAY_GROWTH * p;
        play.setPivotX(rtl ? play.getWidth() : 0f);
        play.setPivotY(play.getHeight() / 2f);
        play.setScaleX(playScale);
        play.setScaleY(playScale);
        play.setElevation((7f + 9f * c) * d);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (c > 0f) {
                if (play.getForeground() != ringOutside) {
                    play.setForeground(ringOutside);
                }
                ring.setAlpha(Math.round(255 * c));
            }
            else if (play.getForeground() == ringOutside) {
                play.setForeground(null);
            }
        }
        // What follows Play on its line moves over by what Play gained
        if (play.getParent() instanceof ViewGroup) {
            ViewGroup line = (ViewGroup) play.getParent();
            float gained = play.getWidth() * PLAY_GROWTH * p * (rtl ? -1f : 1f);
            boolean after = false;
            for (int i = 0; i < line.getChildCount(); i++) {
                View child = line.getChildAt(i);
                if (child == play) {
                    after = true;
                }
                else if (after) {
                    child.setTranslationX(gained);
                }
            }
        }

        // The logo (or the name) grows upward into the space the hero left
        if (logo != null) {
            float s = 1f + LOGO_GROWTH * p;
            logo.setPivotX(rtl ? logo.getWidth() : 0f);
            logo.setPivotY(logo.getHeight());
            logo.setScaleX(s);
            logo.setScaleY(s);
        }
        if (title != null) {
            float s = 1f + TITLE_GROWTH * p;
            title.setPivotX(rtl ? title.getWidth() : 0f);
            title.setPivotY(title.getHeight());
            title.setScaleX(s);
            title.setScaleY(s);
        }
        if (section != null) {
            section.setAlpha(1f - c);
        }
        // The buttons beside the hints (PC games, profiles ...) step away with the row
        if (hintBar != null && hintBar.getParent() instanceof ViewGroup) {
            ViewGroup bottom = (ViewGroup) hintBar.getParent();
            for (int i = 0; i < bottom.getChildCount(); i++) {
                View child = bottom.getChildAt(i);
                if (child != hintBar) {
                    child.setAlpha(1f - 0.5f * c);
                    child.setTranslationY(0f);
                }
            }
        }

        // The shades over the art clear a little, so the art shows
        if (heroScrim != null) {
            heroScrim.setAlpha(1f - 0.25f * c);
        }
        if (bottomScrim != null) {
            bottomScrim.setAlpha(1f);
        }
    }

    // ---- Hints --------------------------------------------------------------------------------

    private void showHeroHints() {
        if (hintBar == null) {
            return;
        }
        savedHints = new ArrayList<>();
        for (int i = 0; i < hintBar.getChildCount(); i++) {
            savedHints.add(hintBar.getChildAt(i));
        }
        CharSequence label = play.getText();
        String text = label != null ? label.toString().replace("▶", "").replace("●", "").trim() : "";
        if (text.isEmpty()) {
            text = activity.getString(R.string.pocket_hero_play);
        }
        ConsoleActivity.fillHintBar(activity, hintBar,
                new ConsoleActivity.Hint("A", androidx.core.content.ContextCompat.getColor(activity, R.color.pocket_btn_a), text),
                ConsoleActivity.hintB(activity, R.string.pocket_hint_back));
        heroHint = hintBar.getChildCount() > 0 ? hintBar.getChildAt(0) : null;
    }

    private void restoreHints() {
        if (hintBar == null || savedHints == null) {
            return;
        }
        // Only if the bar still shows the hero's hints (the screen may have set new ones since)
        if (hintBar.getChildCount() > 0 && hintBar.getChildAt(0) == heroHint) {
            hintBar.removeAllViews();
            for (View v : savedHints) {
                hintBar.addView(v);
            }
        }
        savedHints = null;
        heroHint = null;
    }
}
