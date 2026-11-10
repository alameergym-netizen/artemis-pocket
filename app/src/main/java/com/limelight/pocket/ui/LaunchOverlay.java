package com.limelight.pocket.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.pocket.ConsoleActivity;
import com.limelight.pocket.PocketPrefs;

/**
 * The console-style "Starting <game>" screen shown while the stream connects: the game's art,
 * softly blurred and slowly drifting, the title, the current step and a sliding light bar.
 * Replaces the plain Android progress dialog.
 */
public class LaunchOverlay extends FrameLayout {

    // The library hands over the selected game's art so this screen can show it
    private static String artTitle;
    private static Bitmap art;

    public static void rememberArt(String title, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || title == null) {
            return;
        }
        try {
            int w = Math.min(480, bitmap.getWidth());
            int h = Math.max(1, Math.round(bitmap.getHeight() * (w / (float) bitmap.getWidth())));
            Bitmap copy = Bitmap.createScaledBitmap(bitmap, w, h, true);
            if (copy == bitmap) {
                copy = bitmap.copy(bitmap.getConfig() != null ? bitmap.getConfig() : Bitmap.Config.ARGB_8888, false);
            }
            art = copy;
            artTitle = title;
        } catch (RuntimeException | OutOfMemoryError ignored) {
        }
    }

    private static Bitmap artFor(String title) {
        return title != null && title.equals(artTitle) ? art : null;
    }

    // ... and its logo and wide key art, when it has them (SteamGridDB)
    private static String logoTitle;
    private static Bitmap logo;
    private static String keyArtTitle;
    private static Bitmap keyArt;

    public static void rememberLogo(String title, Bitmap bitmap) {
        logoTitle = title;
        logo = bitmap;
    }

    public static void rememberKeyArt(String title, Bitmap bitmap) {
        keyArtTitle = title;
        keyArt = bitmap;
    }

    private final float d;
    private final LinearLayout column;
    private final TextView status;
    private final View bar;
    private final ImageView image;
    private ObjectAnimator barAnimator;

    public LaunchOverlay(Context context, String title, String detail, ConsoleActivity.Hint... hintItems) {
        super(context);
        d = getResources().getDisplayMetrics().density;
        setClickable(true);
        setBackgroundColor(0xFF000000);

        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        Bitmap wide = title != null && title.equals(keyArtTitle) ? keyArt : null;
        Bitmap bitmap = wide != null ? wide : artFor(title);
        if (bitmap != null) {
            image.setImageBitmap(bitmap);
            // Key art is made to be seen: only a light blur; a cover is blown up, so more
            image.setAlpha(wide != null ? 0.62f : 0.5f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                float blur = wide != null ? 6f : 36f;
                image.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP));
            }
            if (!PocketPrefs.colorBackgroundArt(context)) {
                ColorMatrix mono = new ColorMatrix();
                mono.setSaturation(0f);
                image.setColorFilter(new ColorMatrixColorFilter(mono));
            }
        }
        addView(image, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(context);
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xE6000000, 0x99000000, 0x33000000});
        scrim.setBackground(shade);
        addView(scrim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        LayoutParams colLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        colLp.setMarginStart(dp(48));
        addView(column, colLp);

        TextView caption = text(10.5f, 0x99FFFFFF, true);
        caption.setAllCaps(true);
        caption.setLetterSpacing(0.24f);
        caption.setText(context.getString(com.limelight.R.string.pocket_launch_caption));
        column.addView(caption);

        Bitmap mark = title != null && title.equals(logoTitle) ? logo : null;
        if (mark != null) {
            // The game's logo in place of its name
            ImageView logoView = new ImageView(context);
            logoView.setImageBitmap(mark);
            logoView.setAdjustViewBounds(true);
            logoView.setMaxWidth(dp(420));
            logoView.setMaxHeight(dp(84));
            logoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logoView.setContentDescription(title);
            LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            logoLp.topMargin = dp(10);
            logoLp.bottomMargin = dp(8);
            column.addView(logoView, logoLp);
        }
        else {
            TextView titleView = text(30, 0xFFFFFFFF, true);
            titleView.setText(title);
            titleView.setMaxWidth(dp(560));
            titleView.setMaxLines(2);
            titleView.setPadding(0, dp(6), 0, dp(4));
            column.addView(titleView);
        }

        TextView detailView = text(12.5f, 0xA3FFFFFF, false);
        detailView.setText(detail);
        detailView.setVisibility(detail == null || detail.isEmpty() ? GONE : VISIBLE);
        column.addView(detailView);

        FrameLayout track = new FrameLayout(context);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0x26FFFFFF);
        trackBg.setCornerRadius(dp(2));
        track.setBackground(trackBg);
        track.setClipChildren(true);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(dp(240), dp(3));
        trackLp.topMargin = dp(26);
        column.addView(track, trackLp);

        bar = new View(context);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(PocketPrefs.accentColor(context));
        barBg.setCornerRadius(dp(2));
        bar.setBackground(barBg);
        track.addView(bar, new LayoutParams(dp(72), ViewGroup.LayoutParams.MATCH_PARENT));

        status = text(11.5f, 0x80FFFFFF, false);
        status.setPadding(0, dp(10), 0, 0);
        column.addView(status);

        LinearLayout hints = new LinearLayout(context);
        hints.setOrientation(LinearLayout.HORIZONTAL);
        hints.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams hintsLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        hintsLp.bottomMargin = dp(20);
        hintsLp.setMarginEnd(dp(28));
        addView(hints, hintsLp);
        ConsoleActivity.fillHintBar(context, hints, hintItems);
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

    public void setStatus(String text) {
        status.setText(text);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        boolean animate = PocketPrefs.animationsEnabled(getContext());
        barAnimator = ObjectAnimator.ofFloat(bar, View.TRANSLATION_X, -dp(72), dp(240));
        barAnimator.setDuration(1100);
        barAnimator.setRepeatCount(ValueAnimator.INFINITE);
        barAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        barAnimator.start();
        if (animate) {
            image.setScaleX(1.1f);
            image.setScaleY(1.1f);
            image.animate().scaleX(1f).scaleY(1f).setDuration(9000).setInterpolator(PocketUi.EASE).start();
            // Caption, title, details and the light bar arrive one after another
            PocketMotion.cascade(column, 90, 70, -26, 0, 6);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (barAnimator != null) {
            barAnimator.cancel();
        }
        super.onDetachedFromWindow();
    }

    /** Fades away, uncovering the stream. */
    public void dismiss() {
        final ViewGroup parent = (ViewGroup) getParent();
        if (parent == null) {
            return;
        }
        if (PocketPrefs.animationsEnabled(getContext())) {
            // The text lifts away first, then the screen opens onto the game
            column.animate().alpha(0f).translationX(-dp(16)).setStartDelay(0).setDuration(160)
                    .setInterpolator(PocketUi.EASE).start();
            animate().alpha(0f).scaleX(1.05f).scaleY(1.05f).setStartDelay(80).setDuration(340)
                    .setInterpolator(PocketUi.EASE).withEndAction(() -> parent.removeView(this)).start();
        }
        else {
            parent.removeView(this);
        }
    }
}
