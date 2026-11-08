package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.pocket.ConsoleActivity;
import com.limelight.pocket.PocketPrefs;

/**
 * Full-screen "Resuming <game>" card shown by the console screens right before a suspended
 * stream (or a quality switch) starts again. A thin bar fills up while it waits.
 */
public class ResumeOverlay extends FrameLayout {
    private final float d;
    private final TextView caption;
    private final TextView title;
    private final TextView subtitle;
    private final View fill;
    private final LinearLayout hints;

    public ResumeOverlay(Context context) {
        super(context);
        d = getResources().getDisplayMetrics().density;
        setClickable(true);
        setBackgroundColor(0xF2000000);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        addView(column, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        caption = text(10.5f, 0x99FFFFFF, true);
        caption.setAllCaps(true);
        caption.setLetterSpacing(0.24f);
        column.addView(caption);

        title = text(26, 0xFFFFFFFF, true);
        title.setGravity(Gravity.CENTER);
        title.setMaxWidth(dp(520));
        title.setPadding(0, dp(8), 0, dp(4));
        column.addView(title);

        subtitle = text(12.5f, 0xA3FFFFFF, false);
        subtitle.setGravity(Gravity.CENTER);
        column.addView(subtitle);

        FrameLayout track = new FrameLayout(context);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0x26FFFFFF);
        trackBg.setCornerRadius(dp(2));
        track.setBackground(trackBg);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(dp(220), dp(3));
        trackLp.topMargin = dp(22);
        column.addView(track, trackLp);

        fill = new View(context);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(PocketPrefs.accentColor(context));
        fillBg.setCornerRadius(dp(2));
        fill.setBackground(fillBg);
        fill.setPivotX(0);
        fill.setScaleX(0f);
        track.addView(fill, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hints = new LinearLayout(context);
        hints.setOrientation(LinearLayout.HORIZONTAL);
        hints.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams hintsLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintsLp.bottomMargin = dp(22);
        addView(hints, hintsLp);
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

    public void bind(String captionText, String titleText, String subtitleText, ConsoleActivity.Hint... hintItems) {
        caption.setText(captionText);
        title.setText(titleText);
        subtitle.setText(subtitleText);
        subtitle.setVisibility(subtitleText == null || subtitleText.isEmpty() ? GONE : VISIBLE);
        ConsoleActivity.fillHintBar(getContext(), hints, hintItems);
    }

    /** Fades in and fills the bar over durationMs. */
    public void start(long durationMs) {
        boolean animate = PocketPrefs.animationsEnabled(getContext());
        fill.animate().cancel();
        fill.setScaleX(0f);
        fill.animate().scaleX(1f).setDuration(Math.max(100, durationMs)).setInterpolator(new LinearInterpolator()).start();
        if (animate) {
            setAlpha(0f);
            animate().alpha(1f).setDuration(160).start();
            title.setTranslationY(dp(10));
            title.animate().translationY(0).setDuration(260).setInterpolator(PocketUi.EASE).start();
        }
    }

    /** Waiting longer than planned (the previous stream is still closing). */
    public void holdFull() {
        fill.animate().cancel();
        fill.setScaleX(1f);
    }

    public void dismiss() {
        fill.animate().cancel();
        final ViewGroup parent = (ViewGroup) getParent();
        if (parent == null) {
            return;
        }
        if (PocketPrefs.animationsEnabled(getContext())) {
            animate().alpha(0f).setDuration(140).withEndAction(() -> parent.removeView(this)).start();
        }
        else {
            parent.removeView(this);
        }
    }
}
