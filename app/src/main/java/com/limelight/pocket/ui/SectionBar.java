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
        // Plain words, like the status on the right (PlayStation Portal style): the section
        // you're in is white and bold, the others faded; no capsule, no border
        LinearLayout bar = new LinearLayout(activity);
        bar.setTag("pocket_sections");
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(Math.round(28 * d));
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        bar.addView(glyph(activity, "L1", d));
        for (int s = 0; s < COUNT; s++) {
            final int section = s;
            TextView tab = portalTab(activity, activity.getString(LABELS[s]), s == active, d);
            tab.setOnClickListener(v -> go(activity, active, section));
            // Tapped, or switched with L1 / R1: never a stop for the d-pad
            tab.setFocusable(false);
            bar.addView(tab);
        }
        bar.addView(glyph(activity, "R1", d));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                Math.round(28 * d));
        lp.gravity = Gravity.CENTER_VERTICAL;
        if (row instanceof LinearLayout) {
            row.addView(bar, index, lp);
        }
        else {
            row.addView(bar, index, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    Math.round(28 * d), Gravity.CENTER_VERTICAL | Gravity.START));
        }
    }

    /** A tab as plain words: white and bold when it's the one you're on, faded otherwise. */
    public static TextView portalTab(Activity activity, String text, boolean on, float d) {
        TextView tab = new TextView(activity);
        tab.setText(text);
        tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tab.setTypeface(on ? PocketFonts.bold() : PocketFonts.medium());
        tab.setLetterSpacing(-0.01f);
        tab.setIncludeFontPadding(false);
        tab.setGravity(Gravity.CENTER);
        tab.setTextColor(on ? 0xFFFFFFFF : 0x8CFFFFFF);
        // A soft light around the one you're on; a shadow keeps the others readable on art
        tab.setShadowLayer(on ? 10 * d : 6 * d, 0, 0, on ? 0x59FFFFFF : 0x99000000);
        tab.setPadding(Math.round(9 * d), 0, Math.round(9 * d), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                Math.round(28 * d));
        lp.gravity = Gravity.CENTER_VERTICAL;
        tab.setLayoutParams(lp);
        if (on && PocketPrefs.animationsEnabled(activity)) {
            tab.setAlpha(0.6f);
            tab.animate().alpha(1f).setDuration(260).setInterpolator(PocketMotion.DECELERATE).start();
        }
        return tab;
    }

    public interface TabPick {
        void pick(int index);
    }

    /**
     * Tabs inside a screen (a PC's Apps and its PC games): the same acrylic capsule as the
     * sections, after the screen's "< Title", switched with L1 / R1. onPick gets the tab tapped.
     */
    public static void attachTabs(final Activity activity, int[] labels, int active,
                                  final TabPick onPick) {
        View title = activity.findViewById(R.id.appListText);
        if (title == null || !(title.getParent() instanceof View)
                || !(((View) title.getParent()).getParent() instanceof ViewGroup)) {
            return;
        }
        View pill = (View) title.getParent();
        ViewGroup row = (ViewGroup) pill.getParent();
        if (row.findViewWithTag("pocket_tabs") != null) {
            return;
        }
        float d = activity.getResources().getDisplayMetrics().density;
        LinearLayout bar = new LinearLayout(activity);
        bar.setTag("pocket_tabs");
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(glyph(activity, "L1", d));
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView tab = portalTab(activity, activity.getString(labels[i]), i == active, d);
            tab.setFocusable(false);
            tab.setOnClickListener(v -> {
                if (index != active) {
                    onPick.pick(index);
                }
            });
            bar.addView(tab);
        }
        bar.addView(glyph(activity, "R1", d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                Math.round(28 * d));
        lp.gravity = Gravity.CENTER_VERTICAL;
        lp.setMarginStart(Math.round(10 * d));
        row.addView(bar, row.indexOfChild(pill) + 1, lp);
    }

    /**
     * A clear button with an icon at the top of a screen, after its "< Title" (a PC's games):
     * the icon, its name and the button that opens it (R1). Tapping it works too.
     */
    public static void attachAction(final Activity activity, int icon, int label, String button,
                                    final Runnable onClick) {
        View title = activity.findViewById(R.id.appListText);
        if (title == null || !(title.getParent() instanceof View)
                || !(((View) title.getParent()).getParent() instanceof ViewGroup)) {
            return;
        }
        View pill = (View) title.getParent();
        ViewGroup row = (ViewGroup) pill.getParent();
        if (row.findViewWithTag("pocket_action") != null) {
            return;
        }
        float d = activity.getResources().getDisplayMetrics().density;
        LinearLayout action = new LinearLayout(activity);
        action.setTag("pocket_action");
        action.setOrientation(LinearLayout.HORIZONTAL);
        action.setGravity(Gravity.CENTER_VERTICAL);
        action.setPadding(Math.round(10 * d), 0, Math.round(8 * d), 0);
        GradientDrawable face = new GradientDrawable();
        face.setCornerRadius(100 * d);
        face.setColor(0x2EFFFFFF);
        action.setBackground(face);
        android.widget.ImageView glyphIcon = new android.widget.ImageView(activity);
        glyphIcon.setImageResource(icon);
        glyphIcon.setColorFilter(0xFFFFFFFF);
        action.addView(glyphIcon, new LinearLayout.LayoutParams(Math.round(17 * d), Math.round(17 * d)));
        TextView name = new TextView(activity);
        name.setText(label);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        name.setTypeface(PocketFonts.semibold());
        name.setTextColor(0xFFFFFFFF);
        name.setIncludeFontPadding(false);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.setMarginStart(Math.round(7 * d));
        action.addView(name, nlp);
        if (button != null) {
            action.addView(glyph(activity, button, d));
        }
        action.setFocusable(false);
        action.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                Math.round(28 * d));
        lp.gravity = Gravity.CENTER_VERTICAL;
        lp.setMarginStart(Math.round(10 * d));
        row.addView(action, row.indexOfChild(pill) + 1, lp);
    }

    /** The shoulder button that switches: small, faded words (no outline, like the rest). */
    private static TextView glyph(Activity a, String text, float d) {
        TextView g = new TextView(a);
        g.setText(text);
        g.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        g.setTypeface(PocketFonts.bold());
        g.setTextColor(0x73FFFFFF);
        g.setIncludeFontPadding(false);
        g.setGravity(Gravity.CENTER);
        g.setShadowLayer(6 * d, 0, 0, 0x99000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Math.round(4 * d), 0, Math.round(4 * d), 0);
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
