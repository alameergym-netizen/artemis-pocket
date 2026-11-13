package com.limelight.pocket.home;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.pocket.PocketArt;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.ui.LogoView;
import com.limelight.pocket.ui.PocketFonts;
import com.limelight.pocket.ui.PocketMotion;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;

/**
 * Home left alone for a while: the screen dims and your games' art drifts by, one after another
 * (each slowly zooming across, then fading into the next), with the time, the date and the
 * game's logo. Nothing stays in one place for long (the words move a little every minute, kind
 * to an OLED screen), and any button brings Home back. After a while it lets the screen sleep.
 */
public final class AmbientScreen {

    /** Minutes of nothing before it shows (0: never). */
    public static final String KEY_MINUTES = "pocket_ambient_minutes";
    public static final int[] MINUTES = {0, 1, 2, 5, 10};

    private static final long ART_MS = 24_000;
    private static final long FADE_MS = 1_800;
    private static final long KEEP_ON_MS = 20 * 60_000;

    /** A game to show: where its art is and its name. */
    public static final class Item {
        public final String uuid;
        public final int id;
        public final String name;

        public Item(String uuid, int id, String name) {
            this.uuid = uuid;
            this.id = id;
            this.name = name;
        }
    }

    public static int minutes(Activity a) {
        return PocketPrefs.prefs(a).getInt(KEY_MINUTES, 2);
    }

    private final Activity activity;
    private final float d;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private FrameLayout root;
    private ImageView[] art;
    private int front;
    private LinearLayout words;
    private TextView clock;
    private TextView date;
    private LogoView logo;
    private TextView name;
    private List<Item> items = new ArrayList<>();
    private int index;
    private boolean showing;
    private float brightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;

    public AmbientScreen(Activity activity) {
        this.activity = activity;
        this.d = activity.getResources().getDisplayMetrics().density;
    }

    public boolean showing() {
        return showing;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!showing) {
                return;
            }
            updateClock();
            shiftWords();
            main.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50);
        }
    };

    private final Runnable next = new Runnable() {
        @Override
        public void run() {
            if (!showing) {
                return;
            }
            showItem(index + 1, true);
            main.postDelayed(this, ART_MS);
        }
    };

    private final Runnable letSleep = () -> activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    public void show(List<Item> games, int start) {
        if (showing || activity.isFinishing()) {
            return;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        items = new ArrayList<>(games);
        index = Math.max(0, start);
        build();
        content.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.setElevation(60 * d);
        showing = true;
        boolean animate = PocketPrefs.animationsEnabled(activity);
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(animate ? 1200 : 0).start();
        words.setAlpha(0f);
        words.setTranslationY(10 * d);
        words.animate().alpha(1f).translationY(0f).setStartDelay(animate ? 500 : 0).setDuration(animate ? 900 : 0)
                .setInterpolator(PocketMotion.DECELERATE).start();
        updateClock();
        showItem(index, false);
        main.postDelayed(tick, 60_000 - System.currentTimeMillis() % 60_000 + 50);
        if (items.size() > 1) {
            main.postDelayed(next, ART_MS);
        }
        // Dimmer while it shows; the screen may sleep after a while
        WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
        brightness = lp.screenBrightness;
        lp.screenBrightness = 0.25f;
        activity.getWindow().setAttributes(lp);
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        main.postDelayed(letSleep, KEEP_ON_MS);
    }

    public void hide() {
        if (!showing) {
            return;
        }
        showing = false;
        main.removeCallbacks(tick);
        main.removeCallbacks(next);
        main.removeCallbacks(letSleep);
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
        lp.screenBrightness = brightness;
        activity.getWindow().setAttributes(lp);
        final FrameLayout gone = root;
        root = null;
        if (gone == null) {
            return;
        }
        for (ImageView v : art) {
            v.animate().cancel();
        }
        gone.animate().alpha(0f).setDuration(PocketPrefs.animationsEnabled(activity) ? 260 : 0)
                .withEndAction(() -> {
                    if (gone.getParent() instanceof ViewGroup) {
                        ((ViewGroup) gone.getParent()).removeView(gone);
                    }
                }).start();
    }

    private void build() {
        root = new FrameLayout(activity);
        root.setBackgroundColor(0xFF000000);
        root.setClickable(true);
        art = new ImageView[2];
        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(activity);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            art[i] = v;
        }
        // Deep at the bottom left, where the time is
        View shade = new View(activity);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.BL_TR,
                new int[]{0xE6000000, 0x66000000, 0x1A000000});
        shade.setBackground(g);
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        words = new LinearLayout(activity);
        words.setOrientation(LinearLayout.VERTICAL);
        clock = text(58f, PocketFonts.semibold(), 0xFFFFFFFF);
        clock.setLetterSpacing(-0.03f);
        clock.setShadowLayer(18 * d, 0, 0, 0x99000000);
        words.addView(clock);
        date = text(14f, PocketFonts.medium(), 0xD9FFFFFF);
        date.setShadowLayer(10 * d, 0, 0, 0x99000000);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.topMargin = Math.round(4 * d);
        words.addView(date, dl);
        logo = new LogoView(activity);
        logo.setAlpha(0.95f);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(Math.round(250 * d), Math.round(52 * d));
        ll.topMargin = Math.round(26 * d);
        words.addView(logo, ll);
        name = text(16f, PocketFonts.semibold(), 0xF2FFFFFF);
        name.setShadowLayer(10 * d, 0, 0, 0x99000000);
        name.setMaxLines(2);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(Math.round(320 * d),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.topMargin = Math.round(26 * d);
        words.addView(name, nl);
        TextView wake = text(10.5f, PocketFonts.medium(), 0x73FFFFFF);
        wake.setText(R.string.pocket_ambient_wake);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.topMargin = Math.round(12 * d);
        words.addView(wake, wl);
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        wlp.setMarginStart(Math.round(44 * d));
        wlp.bottomMargin = Math.round(40 * d);
        root.addView(words, wlp);
    }

    private TextView text(float sp, android.graphics.Typeface face, int color) {
        TextView t = new TextView(activity);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private void updateClock() {
        if (clock == null) {
            return;
        }
        long now = System.currentTimeMillis();
        clock.setText(DateFormat.getTimeFormat(activity).format(new Date(now)));
        date.setText(DateUtils.formatDateTime(activity, now,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_NO_YEAR));
    }

    /** A little somewhere else every minute (nothing burns into an OLED screen). */
    private void shiftWords() {
        if (words == null) {
            return;
        }
        float dx = (random.nextFloat() * 2f - 1f) * 14 * d;
        float dy = (random.nextFloat() * 2f - 1f) * 10 * d;
        words.animate().translationX(dx).translationY(dy).setDuration(2400)
                .setInterpolator(PocketMotion.DECELERATE).start();
    }

    /** The game at this place: its art slowly zooming across, its logo (or name). */
    private void showItem(int at, final boolean fade) {
        if (items.isEmpty() || art == null) {
            return;
        }
        index = ((at % items.size()) + items.size()) % items.size();
        final Item item = items.get(index);
        final ImageView incoming = art[fade ? 1 - front : front];
        final ImageView outgoing = art[fade ? front : 1 - front];
        PocketArt.load(activity, item.uuid, item.id, PocketArt.HERO, bitmap -> {
            if (!showing || items.isEmpty() || items.get(index) != item) {
                return;
            }
            if (bitmap == null && items.size() > 1 && fade) {
                // No art for this one: straight on to the next
                main.removeCallbacks(next);
                showItem(index + 1, true);
                main.postDelayed(next, ART_MS);
                return;
            }
            incoming.setImageBitmap(bitmap);
            boolean animate = PocketPrefs.animationsEnabled(activity);
            // Slow zoom and drift, a different way each time
            float fromX = (random.nextBoolean() ? -1 : 1) * 18 * d;
            incoming.animate().cancel();
            incoming.setScaleX(1.06f);
            incoming.setScaleY(1.06f);
            incoming.setTranslationX(fromX);
            if (animate) {
                incoming.animate().scaleX(1.16f).scaleY(1.16f).translationX(-fromX)
                        .setDuration(ART_MS + FADE_MS * 2).setInterpolator(new LinearInterpolator()).start();
            }
            incoming.setAlpha(0f);
            incoming.animate().alpha(0.72f).setDuration(animate ? FADE_MS : 0).start();
            if (fade) {
                outgoing.animate().alpha(0f).setDuration(animate ? FADE_MS : 0).start();
                front = 1 - front;
            }
        });
        PocketArt.load(activity, item.uuid, item.id, PocketArt.LOGO, bitmap -> {
            if (!showing || items.isEmpty() || items.get(index) != item) {
                return;
            }
            boolean useLogo = bitmap != null && PocketArt.logosForNames(activity) && !PocketArt.isDarkLogo(bitmap);
            if (useLogo) {
                logo.setImageBitmap(bitmap);
            }
            logo.setVisibility(useLogo ? View.VISIBLE : View.GONE);
            name.setText(item.name);
            name.setVisibility(useLogo ? View.GONE : View.VISIBLE);
            if (fade && PocketPrefs.animationsEnabled(activity)) {
                View v = useLogo ? logo : name;
                v.setAlpha(0f);
                v.animate().alpha(useLogo ? 0.95f : 1f).setDuration(900).start();
            }
        });
    }
}
