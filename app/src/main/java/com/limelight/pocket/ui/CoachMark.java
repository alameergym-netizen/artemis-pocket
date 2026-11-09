package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.limelight.R;
import com.limelight.pocket.ConsoleActivity;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.PocketSounds;

/**
 * A first-time tip: the screen dims, a spotlight closes in on one thing (an iris that shrinks onto
 * it and then breathes), and a small glass card next to it says what you can do there. Any button
 * closes it. Shown once per tip; Settings can bring them back.
 */
public final class CoachMark extends FrameLayout {

    private static final int SCRIM = 0xB8000000;

    private final View target;
    private final float d;
    private final int accent;
    private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path hole = new Path();
    private final RectF spot = new RectF();
    private final RectF ringRect = new RectF();
    private final int[] loc = new int[2];
    private final int[] self = new int[2];
    private final LinearLayout card;
    private final float radius;
    private final long shownAt = SystemClock.uptimeMillis();
    private long leftAt = -1;
    private Runnable onClosed;
    private boolean placed;

    public CoachMark(Context context, View target, float targetRadiusDp, CharSequence title, CharSequence body) {
        super(context);
        this.target = target;
        d = getResources().getDisplayMetrics().density;
        accent = PocketTheme.accent(context);
        radius = (targetRadiusDp + 8) * d;
        setWillNotDraw(false);
        setClickable(true);
        setOnClickListener(v -> dismiss());
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription(title + ". " + body);

        scrim.setColor(SCRIM);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2f * d);

        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.pocket_panel_bg);
        int pad = Math.round(16 * d);
        card.setPadding(pad, Math.round(12 * d), pad, Math.round(10 * d));

        TextView caption = new TextView(context);
        caption.setText(R.string.pocket_tip_caption);
        caption.setAllCaps(true);
        caption.setLetterSpacing(0.2f);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        caption.setTextColor(accent);
        caption.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        card.addView(caption);

        TextView head = new TextView(context);
        head.setText(title);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        head.setTextColor(ContextCompat.getColor(context, R.color.pocket_text));
        head.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        head.setPadding(0, Math.round(3 * d), 0, Math.round(3 * d));
        card.addView(head);

        TextView text = new TextView(context);
        text.setText(body);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        text.setLineSpacing(2 * d, 1f);
        text.setTextColor(ContextCompat.getColor(context, R.color.pocket_text_dim));
        card.addView(text);

        LinearLayout hints = new LinearLayout(context);
        hints.setOrientation(LinearLayout.HORIZONTAL);
        hints.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        hints.setPadding(0, Math.round(10 * d), 0, 0);
        card.addView(hints, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ConsoleActivity.fillHintBar(context, hints, ConsoleActivity.hintA(context, R.string.pocket_tip_got_it));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.round(300 * d), ViewGroup.LayoutParams.WRAP_CONTENT);
        addView(card, lp);
        card.setAlpha(0f);
    }

    public void setOnClosed(Runnable r) {
        onClosed = r;
    }

    public boolean isClosing() {
        return leftAt >= 0;
    }

    private boolean animated() {
        return PocketPrefs.animationsEnabled(getContext());
    }

    /** Closes the tip (any button). */
    public void dismiss() {
        if (leftAt >= 0) {
            return;
        }
        leftAt = SystemClock.uptimeMillis();
        PocketSounds.play(getContext(), PocketSounds.CLOSE);
        card.animate().cancel();
        if (animated()) {
            card.animate().alpha(0f).translationY(6 * d).setDuration(160).setInterpolator(PocketUi.EASE).start();
        }
        invalidate();
    }

    private void remove() {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) {
            parent.removeView(this);
        }
        if (onClosed != null) {
            Runnable r = onClosed;
            onClosed = null;
            r.run();
        }
    }

    /** Where the target is on screen right now (its live scale included). */
    private boolean findTarget(RectF out) {
        if (target == null || !target.isAttachedToWindow() || target.getWidth() == 0) {
            return false;
        }
        target.getLocationInWindow(loc);
        getLocationInWindow(self);
        float w = target.getWidth();
        float h = target.getHeight();
        View frame = target.getParent() instanceof View ? (View) target.getParent() : null;
        float s = frame != null ? frame.getScaleX() * target.getScaleX() : target.getScaleX();
        out.set(loc[0] - self[0], loc[1] - self[1], loc[0] - self[0] + w * s, loc[1] - self[1] + h * s);
        out.inset(-8 * d, -8 * d);
        return true;
    }

    private void placeCard() {
        if (card.getWidth() == 0) {
            return;
        }
        float gap = 16 * d;
        float x = spot.left;
        float y;
        if (spot.bottom + gap + card.getHeight() < getHeight() - 8 * d) {
            y = spot.bottom + gap;
        }
        else if (spot.top - gap - card.getHeight() > 8 * d) {
            y = spot.top - gap - card.getHeight();
        }
        else {
            // No room above or below: beside it
            y = Math.max(8 * d, Math.min(getHeight() - card.getHeight() - 8 * d, spot.centerY() - card.getHeight() / 2f));
            x = spot.right + gap;
            if (x + card.getWidth() > getWidth() - 8 * d) {
                x = spot.left - gap - card.getWidth();
            }
        }
        x = Math.max(16 * d, Math.min(getWidth() - card.getWidth() - 16 * d, x));
        card.setX(x);
        card.setY(y);
        if (!placed) {
            placed = true;
            if (animated()) {
                card.setTranslationY(14 * d);
                card.setY(y + 14 * d);
                card.animate().alpha(1f).y(y).setStartDelay(160).setDuration(520)
                        .setInterpolator(Spring.SOFT).start();
            }
            else {
                card.setAlpha(1f);
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (findTarget(spot)) {
            placed = placed && !changed;
            if (!placed) {
                placeCard();
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        boolean anim = animated();
        float in = anim ? PocketUi.EASE.getInterpolation(Math.min(1f, (now - shownAt) / 420f)) : 1f;
        float out = leftAt < 0 ? 0f : (anim ? Math.min(1f, (now - leftAt) / 200f) : 1f);
        if (out >= 1f) {
            post(this::remove);
            return;
        }
        if (!findTarget(spot)) {
            spot.set(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f, getHeight() / 2f);
        }
        float visible = in * (1 - out);

        // The iris closes in on the target from far around it
        float open = (1 - in) * Math.max(getWidth(), getHeight()) * 0.6f;
        RectF r = ringRect;
        r.set(spot);
        r.inset(-open, -open);
        float rad = radius + open;

        scrim.setAlpha(Math.round(Color_alpha(SCRIM) * visible));
        hole.reset();
        hole.setFillType(Path.FillType.EVEN_ODD);
        hole.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        hole.addRoundRect(r, rad, rad, Path.Direction.CW);
        canvas.drawPath(hole, scrim);

        // A soft ring that breathes around the spotlight
        float breathe = anim ? (float) (0.5 + 0.5 * Math.sin((now - shownAt) / 1000.0 * Math.PI * 2 / 1.6)) : 0.5f;
        float grow = 3 * d + 4 * d * breathe;
        r.inset(-grow, -grow);
        ring.setColor(PocketTheme.withAlpha(accent, Math.round(255 * visible * (0.35f + 0.4f * (1 - breathe)))));
        canvas.drawRoundRect(r, rad + grow, rad + grow, ring);

        if (!placed) {
            placeCard();
        }
        if (anim || out > 0) {
            postInvalidateOnAnimation();
        }
    }

    private static int Color_alpha(int color) {
        return color >>> 24;
    }
}
