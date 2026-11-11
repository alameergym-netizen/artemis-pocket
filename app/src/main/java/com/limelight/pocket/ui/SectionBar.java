package com.limelight.pocket.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.pocket.PocketSounds;

/**
 * The sections at the top of the main screens, console style: Streaming (the PCs and their
 * games), Cloud (Xbox Cloud Gaming, GeForce NOW ...), Android (the handheld's own games and
 * apps) and Retro (the emulated systems, like ES-DE). L1 / R1 go from one to the next; the one
 * you're in is lit, so it's always clear where each kind of game is.
 */
public final class SectionBar {
    private SectionBar() {}

    public static final int STREAMING = 0;
    public static final int CLOUD = 1;
    public static final int ANDROID = 2;
    public static final int RETRO = 3;
    private static final int COUNT = 4;
    private static final int[] LABELS = {R.string.pocket_section_streaming, R.string.pocket_section_cloud,
            R.string.pocket_section_android, R.string.pocket_section_retro};

    /** Puts the bar in the screen's status bar, right after its title. */
    public static void attach(final Activity activity, final int active) {
        ViewGroup row = null;
        int index = 0;
        View brand = activity.findViewById(R.id.pocketBrand);
        if (brand != null && brand.getParent() instanceof ViewGroup) {
            row = (ViewGroup) brand.getParent();
            index = row.indexOfChild(brand) + 1;
        }
        else {
            View title = activity.findViewById(R.id.appListText);
            if (title != null && title.getParent() instanceof View && ((View) title.getParent()).getParent() instanceof ViewGroup) {
                View pill = (View) title.getParent();
                row = (ViewGroup) pill.getParent();
                index = row.indexOfChild(pill) + 1;
            }
        }
        if (row == null || row.findViewWithTag("pocket_sections") != null) {
            return;
        }
        float d = activity.getResources().getDisplayMetrics().density;
        int accent = PocketTheme.accent(activity);
        LinearLayout bar = new LinearLayout(activity);
        bar.setTag("pocket_sections");
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.pocket_glass_pill);
        int p = Math.round(4 * d);
        bar.setPadding(p, p, p, p);

        bar.addView(glyph(activity, "L1", d));
        for (int s = 0; s < COUNT; s++) {
            final int section = s;
            TextView tab = new TextView(activity);
            tab.setText(LABELS[s]);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tab.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tab.setPadding(Math.round(12 * d), Math.round(5 * d), Math.round(12 * d), Math.round(5 * d));
            boolean on = s == active;
            if (on) {
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(100 * d);
                bg.setColor(accent);
                tab.setBackground(bg);
                float lum = (0.299f * android.graphics.Color.red(accent) + 0.587f * android.graphics.Color.green(accent)
                        + 0.114f * android.graphics.Color.blue(accent)) / 255f;
                tab.setTextColor(lum > 0.62f ? 0xFF000000 : 0xFFFFFFFF);
            }
            else {
                tab.setTextColor(0xB3FFFFFF);
            }
            tab.setOnClickListener(v -> go(activity, active, section));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(Math.round(3 * d));
            bar.addView(tab, lp);
        }
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.setMarginStart(Math.round(3 * d));
        bar.addView(glyph(activity, "R1", d), glp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Math.round(14 * d));
        lp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(bar, index, lp);
    }

    private static TextView glyph(Activity a, String text, float d) {
        TextView g = new TextView(a);
        g.setText(text);
        g.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        g.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        g.setTextColor(0xFF000000);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(100 * d);
        bg.setColor(0xD9FFFFFF);
        g.setBackground(bg);
        g.setPadding(Math.round(7 * d), Math.round(2 * d), Math.round(7 * d), Math.round(2 * d));
        return g;
    }

    /** L1 / R1 on a section screen: the next section that way (false when there's none). */
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

    /** Opens a section (a quick cross-fade, like switching tabs). */
    public static void go(Activity activity, int from, int to) {
        if (from == to) {
            return;
        }
        PocketSounds.play(activity, PocketSounds.NAV);
        Class<?> target;
        switch (to) {
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
            activity.startActivity(new Intent(activity, PcView.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }
        // Only the home screen stays under a section (B from any section goes home)
        if (from != STREAMING) {
            activity.finish();
        }
        activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
