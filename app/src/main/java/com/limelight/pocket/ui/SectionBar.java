package com.limelight.pocket.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.PocketSounds;

/**
 * The sections across the top of the main screens, console style: Home (everything you pinned
 * or played, and widgets), Streaming (the PCs and their games), Cloud (Xbox Cloud Gaming,
 * GeForce NOW ...), Android (the handheld's own games and apps) and Retro (the emulated systems,
 * like ES-DE). One acrylic capsule across the top; the section you're in is a white capsule.
 * L1 / R1 go from one to the next; B in any section goes Home.
 */
public final class SectionBar {
    private SectionBar() {}

    public static final int HOME = 0;
    public static final int STREAMING = 1;
    public static final int CLOUD = 2;
    public static final int ANDROID = 3;
    public static final int RETRO = 4;
    private static final int COUNT = 5;
    private static final int[] LABELS = {R.string.pocket_section_home, R.string.pocket_section_streaming,
            R.string.pocket_section_cloud, R.string.pocket_section_android, R.string.pocket_section_retro};

    /** Puts the bar at the top left of the screen (in place of the screen's own title). */
    public static void attach(final Activity activity, final int active) {
        ViewGroup row = null;
        int index = 0;
        View host = activity.findViewById(R.id.pocketSectionHost);
        View brand = activity.findViewById(R.id.pocketBrand);
        if (host instanceof ViewGroup) {
            row = (ViewGroup) host;
            index = 0;
        }
        else if (brand != null && brand.getParent() instanceof ViewGroup) {
            row = (ViewGroup) brand.getParent();
            // The tabs sit where every section has them: the wordmark makes way
            brand.setVisibility(View.GONE);
            View mark = activity.findViewById(R.id.pocketBrandLogo);
            if (mark != null) {
                mark.setVisibility(View.GONE);
            }
            index = row.indexOfChild(brand) + 1;
        }
        else {
            View title = activity.findViewById(R.id.appListText);
            if (title != null && title.getParent() instanceof View && ((View) title.getParent()).getParent() instanceof ViewGroup) {
                View pill = (View) title.getParent();
                row = (ViewGroup) pill.getParent();
                index = row.indexOfChild(pill) + 1;
                // The tabs say where you are: the screen's own "< Title" isn't needed
                pill.setVisibility(View.GONE);
            }
        }
        if (row == null || row.findViewWithTag("pocket_sections") != null) {
            return;
        }
        float d = activity.getResources().getDisplayMetrics().density;
        int accent = PocketTheme.accent(activity);
        // One acrylic capsule; the section you're in is a white capsule inside it
        LinearLayout bar = new LinearLayout(activity);
        bar.setTag("pocket_sections");
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Math.round(3 * d), 0, Math.round(3 * d), 0);
        bar.setMinimumHeight(Math.round(26 * d));
        GradientDrawable plain = new GradientDrawable();
        plain.setCornerRadius(13 * d);
        plain.setColor(0xD91C1C1F);
        plain.setStroke(Math.max(1, Math.round(0.75f * d)), 0x26FFFFFF);
        bar.setBackground(plain);

        bar.addView(glyph(activity, "L1", d));
        for (int s = 0; s < COUNT; s++) {
            final int section = s;
            boolean on = s == active;
            TextView tab = new TextView(activity);
            tab.setText(LABELS[s]);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            tab.setTypeface(on ? PocketFonts.semibold() : PocketFonts.medium());
            tab.setLetterSpacing(-0.005f);
            tab.setIncludeFontPadding(false);
            tab.setGravity(Gravity.CENTER);
            tab.setTextColor(on ? 0xFF0B0B0D : 0xA6FFFFFF);
            tab.setPadding(Math.round(10 * d), 0, Math.round(10 * d), 0);
            tab.setMinHeight(Math.round(20 * d));
            if (on) {
                GradientDrawable chip = new GradientDrawable();
                chip.setCornerRadius(10 * d);
                chip.setColor(accent == 0xFFFFFFFF ? 0xFFFFFFFF : accent);
                tab.setBackground(chip);
                tab.setElevation(2 * d);
                if (PocketPrefs.animationsEnabled(activity)) {
                    tab.setScaleX(0.9f);
                    tab.setScaleY(0.9f);
                    tab.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(PocketMotion.DECELERATE).start();
                }
            }
            tab.setOnClickListener(v -> go(activity, active, section));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    Math.round(20 * d));
            tlp.setMargins(Math.round(0.5f * d), 0, Math.round(0.5f * d), 0);
            bar.addView(tab, tlp);
        }
        bar.addView(glyph(activity, "R1", d));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                Math.round(26 * d));
        lp.gravity = Gravity.CENTER_VERTICAL;
        if (row instanceof LinearLayout) {
            row.addView(bar, index, lp);
        }
        else {
            row.addView(bar, index, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    Math.round(26 * d), Gravity.CENTER_VERTICAL | Gravity.START));
        }
        if (activity instanceof com.limelight.pocket.ConsoleActivity) {
            // Real acrylic over the screen's art (when the device can blur live)
            ((com.limelight.pocket.ConsoleActivity) activity).makeGlass(bar);
        }
    }

    /** The shoulder button, drawn as a small outlined key. */
    private static TextView glyph(Activity a, String text, float d) {
        TextView g = new TextView(a);
        g.setText(text);
        g.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7.5f);
        g.setTypeface(PocketFonts.bold());
        g.setTextColor(0xC7FFFFFF);
        g.setIncludeFontPadding(false);
        g.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(4 * d);
        bg.setColor(0x00000000);
        bg.setStroke(Math.max(1, Math.round(0.75f * d)), 0x59FFFFFF);
        g.setBackground(bg);
        g.setPadding(Math.round(4.5f * d), Math.round(2 * d), Math.round(4.5f * d), Math.round(2 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Math.round(5 * d), 0, Math.round(5 * d), 0);
        lp.gravity = Gravity.CENTER_VERTICAL;
        g.setLayoutParams(lp);
        return g;
    }

    /** L1 / R1 on a section screen: the next section that way (true: used). */
    public static boolean step(Activity activity, int active, int direction) {
        int next = active + (direction > 0 ? 1 : -1);
        if (next < 0 || next >= COUNT) {
            View bar = activity.getWindow().getDecorView().findViewWithTag("pocket_sections");
            if (bar != null) {
                PocketMotion.pop(bar, 0.96f);
            }
            return true;
        }
        go(activity, active, next);
        return true;
    }

    /** B in a section: back to Home. */
    public static void goHome(Activity activity, int from) {
        go(activity, from, HOME);
    }

    /** Opens a section (a quick cross-fade, like switching tabs). */
    public static void go(Activity activity, int from, int to) {
        if (from == to) {
            return;
        }
        PocketSounds.play(activity, PocketSounds.NAV);
        Class<?> target;
        switch (to) {
            case HOME:
                target = com.limelight.pocket.home.HomeActivity.class;
                break;
            case CLOUD:
                target = com.limelight.pocket.cloud.CloudActivity.class;
                break;
            case ANDROID:
                target = com.limelight.pocket.apps.AndroidAppsActivity.class;
                break;
            case RETRO:
                target = com.limelight.pocket.retro.RetroActivity.class;
                break;
            default:
                target = null;
                break;
        }
        if (target != null) {
            activity.startActivity(new Intent(activity, target));
        }
        else {
            // Streaming is the screen under all the others: going there closes what's above it
            activity.startActivity(new Intent(activity, PcView.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }
        // Only Streaming stays under a section
        if (from != STREAMING) {
            activity.finish();
        }
        activity.overridePendingTransition(R.anim.pocket_section_in, R.anim.pocket_section_out);
    }
}
