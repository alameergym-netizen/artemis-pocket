package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.limelight.R;
import com.limelight.nvstream.http.NvApp;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.PocketSounds;

import java.util.ArrayList;
import java.util.List;

/**
 * Quick Resume (like a console's): a glass panel that slides in from the side with what is
 * running on your PCs (pick it up exactly where it is) and what you played last (start it right
 * away), on every PC at once. SELECT opens it from the home screen and the library (and M1 / M2
 * when set to).
 */
public final class QuickResumePanel extends FrameLayout {

    /** A game in the panel. */
    public static final class Entry {
        public final String uuid;
        public final String pcName;
        public final NvApp app;
        public final boolean running;
        public final long lastPlayed;

        public Entry(String uuid, String pcName, NvApp app, boolean running, long lastPlayed) {
            this.uuid = uuid;
            this.pcName = pcName;
            this.app = app;
            this.running = running;
            this.lastPlayed = lastPlayed;
        }
    }

    /** A screen that can show the panel. */
    public interface Source {
        /** Running games first, then the most recently played (any order inside: sorted here). */
        List<Entry> quickResumeEntries();

        /** Start or resume it (the panel is already closing). */
        void quickLaunch(Entry entry);
    }

    public interface Glass {
        /** Makes the view real glass over the screen's backdrop (or does nothing). */
        void makeGlass(View view);
    }

    private final Source source;
    private final View scrim;
    private final LinearLayout panel;
    private final LinearLayout list;
    private final List<View> rows = new ArrayList<>();
    private final Runnable onClosed;
    private boolean closing;
    private final float d;

    public QuickResumePanel(Context context, Source source, Glass glass, Runnable onClosed) {
        super(context);
        this.source = source;
        this.onClosed = onClosed;
        this.d = getResources().getDisplayMetrics().density;
        setClickable(true);
        setFocusable(false);

        scrim = new View(context);
        scrim.setBackgroundColor(0x99000000);
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(20), dp(20), dp(16));
        GradientDrawable fallback = new GradientDrawable();
        fallback.setCornerRadius(dp(22));
        fallback.setColor(0xE6121419);
        fallback.setStroke(Math.max(1, dp(1)), 0x2EFFFFFF);
        panel.setBackground(fallback);
        LayoutParams plp = new LayoutParams(dp(340), LayoutParams.MATCH_PARENT, Gravity.END);
        plp.setMargins(0, dp(12), dp(12), dp(12));
        addView(panel, plp);
        if (glass != null) {
            glass.makeGlass(panel);
        }

        TextView eyebrow = text(context, 9.5f, R.color.pocket_text_dim, "sans-serif-medium");
        eyebrow.setText(R.string.pocket_quick_title);
        eyebrow.setLetterSpacing(0.2f);
        eyebrow.setAllCaps(true);
        panel.addView(eyebrow);
        TextView title = text(context, 20f, R.color.pocket_text, "sans-serif-black");
        title.setText(R.string.pocket_quick_subtitle);
        title.setPadding(0, dp(2), 0, dp(10));
        panel.addView(title);

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hints = text(context, 10f, R.color.pocket_text_faint, null);
        hints.setText(context.getString(R.string.pocket_quick_hints));
        hints.setPadding(0, dp(10), 0, 0);
        panel.addView(hints);

        fill(context);
    }

    private int dp(float v) {
        return Math.round(v * d);
    }

    private static TextView text(Context c, float sp, int color, String font) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(ContextCompat.getColor(c, color));
        if (font != null) {
            t.setTypeface(android.graphics.Typeface.create(font, android.graphics.Typeface.NORMAL));
        }
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private void fill(Context context) {
        List<Entry> all = source.quickResumeEntries();
        List<Entry> running = new ArrayList<>();
        List<Entry> recent = new ArrayList<>();
        for (Entry e : all) {
            (e.running ? running : recent).add(e);
        }
        java.util.Collections.sort(recent, (a, b) -> Long.compare(b.lastPlayed, a.lastPlayed));
        if (!running.isEmpty()) {
            section(context, R.string.pocket_quick_running);
            for (Entry e : running) {
                row(context, e, true);
            }
        }
        if (!recent.isEmpty()) {
            section(context, R.string.pocket_quick_recent);
            int n = 0;
            for (Entry e : recent) {
                if (n++ >= 10) {
                    break;
                }
                row(context, e, false);
            }
        }
        if (rows.isEmpty()) {
            TextView empty = text(context, 12f, R.color.pocket_text_dim, null);
            empty.setMaxLines(4);
            empty.setText(R.string.pocket_quick_empty);
            empty.setPadding(0, dp(12), 0, 0);
            list.addView(empty);
        }
    }

    private void section(Context context, int label) {
        TextView t = text(context, 9f, R.color.pocket_text_faint, "sans-serif-medium");
        t.setText(label);
        t.setAllCaps(true);
        t.setLetterSpacing(0.18f);
        t.setPadding(dp(4), dp(rows.isEmpty() ? 2 : 14), 0, dp(6));
        list.addView(t);
    }

    private void row(Context context, final Entry entry, boolean big) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(12), dp(7));
        row.setFocusable(true);
        row.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(dp(14));
        on.setColor(0x2EFFFFFF);
        on.setStroke(Math.max(1, dp(1.5f)), 0xE6FFFFFF);
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(dp(14));
        off.setColor(big ? 0x14FFFFFF : 0x00000000);
        bg.addState(new int[]{}, off);
        row.setBackground(bg);

        final ImageView cover = new ImageView(context);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setCornerRadius(dp(8));
        coverBg.setColor(0x1AFFFFFF);
        cover.setBackground(coverBg);
        cover.setClipToOutline(true);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(8));
            }
        });
        int cw = dp(big ? 52 : 40);
        row.addView(cover, new LinearLayout.LayoutParams(cw, Math.round(cw * 1.5f)));
        final String name = entry.app.getAppName();
        Bitmap art = HeroArt.cachedCard(context, entry.uuid, entry.app.getAppId(), name);
        if (art != null) {
            cover.setImageBitmap(art);
        }
        else {
            HeroArt.loadCardAsync(context, entry.uuid, entry.app.getAppId(), name, bitmap -> {
                if (bitmap != null) {
                    cover.setImageBitmap(bitmap);
                    cover.setAlpha(0f);
                    cover.animate().alpha(1f).setDuration(200).start();
                }
            });
        }

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, dp(6), 0);
        TextView title = text(context, big ? 14f : 12.5f, R.color.pocket_text, "sans-serif-medium");
        title.setText(name);
        TextView sub = text(context, 10.5f, R.color.pocket_text_dim, null);
        StringBuilder line = new StringBuilder();
        if (entry.pcName != null) {
            line.append(entry.pcName).append("  ·  ");
        }
        if (entry.running) {
            line.append(context.getString(R.string.pocket_now_playing));
        }
        else if (entry.lastPlayed > 0) {
            line.append(android.text.format.DateUtils.getRelativeTimeSpanString(entry.lastPlayed,
                    System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS));
        }
        else {
            line.append(context.getString(R.string.pocket_ready_to_play));
        }
        sub.setText(line);
        texts.addView(title);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView go = text(context, 11f, R.color.pocket_text, "sans-serif-medium");
        go.setText(entry.running ? "▶  " + context.getString(R.string.pocket_hero_resume) : "▶");
        row.addView(go);

        row.setOnFocusChangeListener((v, has) -> {
            float tx = has ? -dp(4) : 0;
            v.animate().translationX(tx).setDuration(Spring.UI_DURATION_MS).setInterpolator(Spring.UI_CURVE).start();
            if (has) {
                PocketUi.tick(getContext());
            }
        });
        row.setOnClickListener(v -> launch(entry));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        list.addView(row, lp);
        rows.add(row);
    }

    private void launch(final Entry entry) {
        if (closing) {
            return;
        }
        PocketSounds.play(getContext(), PocketSounds.SELECT);
        dismiss(() -> source.quickLaunch(entry));
    }

    /** Slides in over the screen, the focus on the first game. */
    public void show(ViewGroup root) {
        root.addView(this, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        PocketSounds.play(getContext(), PocketSounds.OPEN);
        boolean animate = PocketPrefs.animationsEnabled(getContext());
        if (animate) {
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(260).start();
            panel.setTranslationX(dp(360));
            panel.animate().translationX(0).setDuration(Spring.UI_DURATION_MS + 60)
                    .setInterpolator(Spring.UI_CURVE).start();
            PocketMotion.cascade(list, 90, 35, 22, 0, 10);
        }
        post(() -> {
            if (!rows.isEmpty()) {
                rows.get(0).requestFocus();
            }
        });
    }

    public boolean isClosing() {
        return closing;
    }

    public void dismiss(final Runnable then) {
        if (closing) {
            return;
        }
        closing = true;
        Runnable end = () -> {
            ViewGroup parent = (ViewGroup) getParent();
            if (parent != null) {
                parent.removeView(this);
            }
            if (onClosed != null) {
                onClosed.run();
            }
            if (then != null) {
                then.run();
            }
        };
        if (!PocketPrefs.animationsEnabled(getContext())) {
            end.run();
            return;
        }
        scrim.animate().alpha(0f).setDuration(200).start();
        panel.animate().translationX(dp(360)).setDuration(220).setInterpolator(PocketUi.EASE)
                .withEndAction(end).start();
    }

    /** Every key goes here while the panel is open. */
    public boolean onKey(KeyEvent event) {
        if (closing) {
            return true;
        }
        int key = event.getKeyCode();
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                if (up) {
                    PocketSounds.play(getContext(), PocketSounds.BACK);
                    dismiss(null);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (up) {
                    View focused = findFocus();
                    if (focused != null && rows.contains(focused)) {
                        focused.performClick();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    View focused = findFocus();
                    int i = focused != null ? rows.indexOf(focused) : -1;
                    int next = i + (key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                    if (i < 0 && !rows.isEmpty()) {
                        rows.get(0).requestFocus();
                    }
                    else if (next >= 0 && next < rows.size()) {
                        rows.get(next).requestFocus();
                    }
                }
                return true;
            default:
                // Nothing else reaches the screen under the panel
                return true;
        }
    }
}
