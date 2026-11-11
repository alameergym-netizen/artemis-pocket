package com.limelight.pocket.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.limelight.pocket.PocketArt;
import com.limelight.pocket.PocketPrefs;

/**
 * A game's logo in place of its name in a hero (home and library): the name shows while the logo
 * loads, when there is none, or when it would be unreadable (too dark). Logo to logo, the old one
 * slips out the way you moved and the new one comes in, like the names do.
 */
public final class HeroLogo {

    private final Activity activity;
    private final ImageView view;
    private final TextView title;
    private Bitmap shown;
    private String forUuid;
    private int forAppId = -1;
    private int direction;
    private CharSequence name;

    public HeroLogo(Activity activity, ImageView view, TextView title) {
        this.activity = activity;
        this.view = view;
        this.title = title;
    }

    /** The game's logo (or its name). direction: the way focus moved (-1, 0, 1). */
    public void forGame(final String uuid, final int appId, CharSequence name, int direction) {
        if (view == null || title == null || uuid == null) {
            return;
        }
        this.direction = direction;
        this.name = name;
        forUuid = uuid;
        forAppId = appId;
        if (PocketArt.cached(activity, uuid, appId, PocketArt.LOGO) == null) {
            if (view.getVisibility() == View.VISIBLE && PocketArt.logosForNames(activity)
                    && PocketArt.has(activity, uuid, appId, PocketArt.LOGO)) {
                // Its logo is on its way (a moment): the previous one steps out meanwhile, rather
                // than the name flashing up in between
                view.animate().alpha(0f).setStartDelay(0).setDuration(90).withEndAction(null).start();
                // Whatever logo comes next (even the same one again) must bring it back
                shown = null;
            }
            else {
                // The previous game's logo must not linger next to this game's details
                show(null);
            }
        }
        PocketArt.load(activity, uuid, appId, PocketArt.LOGO, bitmap -> {
            if (forAppId == appId && uuid.equals(forUuid) && !activity.isFinishing()) {
                show(bitmap);
            }
        });
    }

    /** No game here (a PC is focused): just its name. */
    public void clear(CharSequence name) {
        forUuid = null;
        forAppId = -1;
        this.name = name;
        show(null);
    }

    /** Shows this logo (null: the name). */
    public void show(Bitmap logo) {
        if (view == null || title == null) {
            return;
        }
        if (logo != null && (!PocketArt.logosForNames(activity) || PocketArt.isDarkLogo(logo))) {
            logo = null;
        }
        if (name != null) {
            // The "Starting..." screen shows it too
            LaunchOverlay.rememberLogo(name.toString(), logo);
        }
        if (logo == shown && (logo != null) == (view.getVisibility() == View.VISIBLE)) {
            return;
        }
        shown = logo;
        if (logo == null) {
            // Takes over alpha and X (drops a logo swap in progress) without freezing an entrance
            view.animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(0).withEndAction(null).start();
            view.setVisibility(View.GONE);
            view.setImageDrawable(null);
            if (title.getVisibility() != View.VISIBLE) {
                // The name was hidden behind a logo: it comes in as this game's name straight away
                // (not as the previous game's name leaving first)
                if (name != null) {
                    title.setText(name);
                }
                title.setVisibility(View.VISIBLE);
                if (PocketPrefs.animationsEnabled(activity)) {
                    title.setAlpha(0f);
                    title.animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(260)
                            .setInterpolator(PocketMotion.DECELERATE).withEndAction(null).start();
                }
                else {
                    title.animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(0)
                            .withEndAction(null).start();
                }
            }
            return;
        }
        boolean wasShowing = view.getVisibility() == View.VISIBLE && view.getDrawable() != null;
        view.setContentDescription(name != null ? name : title.getText());
        title.setVisibility(View.GONE);
        view.setVisibility(View.VISIBLE);
        if (PocketPrefs.animationsEnabled(activity)) {
            PocketMotion.swapImage(view, logo, wasShowing ? direction : 0);
        }
        else {
            view.setImageBitmap(logo);
            view.setAlpha(1f);
            view.setTranslationX(0f);
        }
    }

    /** Forget what is shown, so the next request puts it up again (new art arrived). */
    public void invalidate() {
        shown = null;
    }

    /** The logo on screen (null when the name shows). */
    public Bitmap shown() {
        return shown;
    }
}
