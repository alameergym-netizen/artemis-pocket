package com.limelight.pocket.retro;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.pocket.ConsoleActivity;
import com.limelight.pocket.PocketArt;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.PocketSounds;
import com.limelight.pocket.ui.PocketFonts;
import com.limelight.pocket.ui.PocketMotion;
import com.limelight.pocket.ui.PocketTheme;
import com.limelight.pocket.ui.PocketUi;
import com.limelight.pocket.ui.RemoteImages;
import com.limelight.pocket.ui.Spring;

import java.util.ArrayList;
import java.util.List;

/**
 * A game's achievements, like a console's trophy list: the game, a ring with how much of it is
 * done and the points, then every achievement in two columns (unlocked first, newest first),
 * each with its badge, what it asks, how rare it is and when it was earned. L1 / R1 filter.
 */
public class AchievementsActivity extends ConsoleActivity {

    private static final String EXTRA_KIND = "pocket.trophies.kind";
    private static final String EXTRA_SYSTEM = "pocket.trophies.system";
    private static final String EXTRA_PATH = "pocket.trophies.path";
    private static final String EXTRA_STEAM = "pocket.trophies.steam";
    private static final String EXTRA_NAME = "pocket.trophies.name";
    private static final String EXTRA_ART_UUID = "pocket.trophies.artUuid";
    private static final String EXTRA_ART_ID = "pocket.trophies.artId";

    public static void open(Activity from, Achievements.Target t, String artUuid, int artId) {
        if (t == null) {
            return;
        }
        Intent i = new Intent(from, AchievementsActivity.class);
        i.putExtra(EXTRA_KIND, t.kind);
        i.putExtra(EXTRA_SYSTEM, t.system);
        i.putExtra(EXTRA_PATH, t.path);
        i.putExtra(EXTRA_STEAM, t.steamAppId);
        i.putExtra(EXTRA_NAME, t.name);
        i.putExtra(EXTRA_ART_UUID, artUuid);
        i.putExtra(EXTRA_ART_ID, artId);
        PocketSounds.play(from, PocketSounds.OPEN);
        from.startActivity(i);
        from.overridePendingTransition(R.anim.pocket_section_in, R.anim.pocket_section_out);
    }

    private static final int ALL = 0;
    private static final int UNLOCKED = 1;
    private static final int LOCKED = 2;

    private float d;
    private Achievements.Target target;
    private Achievements.Set set;
    private int filter = ALL;
    private final List<Achievements.Trophy> shown = new ArrayList<>();
    private RecyclerView list;
    private TextView title;
    private TextView platform;
    private TextView stats;
    private Ring ring;
    private ImageView icon;
    private ImageView art;
    private ProgressBar spinner;
    private TextView empty;
    private LinearLayout tabs;
    private final Rows rows = new Rows();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        d = getResources().getDisplayMetrics().density;
        Intent in = getIntent();
        String kind = in.getStringExtra(EXTRA_KIND);
        String name = in.getStringExtra(EXTRA_NAME);
        target = "ra".equals(kind)
                ? Achievements.Target.retro(in.getStringExtra(EXTRA_SYSTEM), in.getStringExtra(EXTRA_PATH), name)
                : Achievements.Target.steam(in.getIntExtra(EXTRA_STEAM, 0), name);
        setContentView(build(name));
        installLiveGlass();
        setHints(hintB(this, R.string.pocket_hint_back),
                new Hint("L1 R1", 0xFFFFFFFF, getString(R.string.pocket_trophy_filter)),
                new Hint("X", androidx.core.content.ContextCompat.getColor(this, R.color.pocket_btn_x),
                        getString(R.string.pocket_trophy_refresh)));

        String artUuid = in.getStringExtra(EXTRA_ART_UUID);
        int artId = in.getIntExtra(EXTRA_ART_ID, 0);
        if (artUuid != null) {
            PocketArt.load(this, artUuid, artId, PocketArt.HERO, bitmap -> {
                if (bitmap != null && !isFinishing()) {
                    art.setImageBitmap(bitmap);
                    art.animate().alpha(1f).setDuration(500).start();
                }
            });
        }
        Achievements.Set known = Achievements.cached(this, target);
        if (known != null) {
            show(known, false);
        }
        load(false);
    }

    private void load(boolean force) {
        spinner.setVisibility(set == null ? View.VISIBLE : View.GONE);
        Achievements.load(this, target, force, s -> {
            if (isFinishing()) {
                return;
            }
            spinner.setVisibility(View.GONE);
            if (s == null && set == null) {
                empty.setVisibility(View.VISIBLE);
                empty.setText(Achievements.hasRa(this) || !"ra".equals(target.kind)
                        ? R.string.pocket_trophy_none : R.string.pocket_trophy_no_account);
                return;
            }
            if (s != null) {
                show(s, set != null);
            }
        });
    }

    // ---- Building the screen ------------------------------------------------------------------

    private int dp(float v) {
        return Math.round(v * d);
    }

    private View build(String name) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        // The game's art, far away and soft behind everything
        FrameLayout backdrop = new FrameLayout(this);
        backdrop.setId(R.id.pocketBackdrop);
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setAlpha(0f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            art.setRenderEffect(RenderEffect.createBlurEffect(dp(26), dp(26), Shader.TileMode.CLAMP));
        }
        backdrop.addView(art, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0xCC07070A, 0xF2000000}));
        backdrop.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout main = new LinearLayout(this);
        main.setId(R.id.pocketMain);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(30), dp(12), dp(30), 0);
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));

        // Top: where you are, and the status
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView where = new TextView(this);
        where.setId(R.id.appListText);
        where.setText(R.string.pocket_trophy_title);
        where.setTypeface(PocketFonts.semibold());
        where.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        where.setTextColor(0xFFFFFFFF);
        top.addView(where);
        View space = new View(this);
        top.addView(space, new LinearLayout.LayoutParams(0, 1, 1f));
        for (int id : new int[]{R.id.pocketLinkChip, R.id.pocketBattery, R.id.pocketClock}) {
            TextView t = new TextView(this);
            t.setId(id);
            t.setTypeface(id == R.id.pocketClock ? PocketFonts.semibold() : PocketFonts.medium());
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, id == R.id.pocketClock ? 12.5f : 11f);
            t.setTextColor(0xFFFFFFFF);
            t.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginStart(dp(12));
            top.addView(t, lp);
        }
        main.addView(top, new LinearLayout.LayoutParams(-1, dp(28)));

        // The game: its icon, name and where the achievements come from; the ring on the right
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(14), 0, dp(12));
        icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setCornerRadius(dp(16));
        iconBg.setColor(0xFF1C1C21);
        icon.setBackground(iconBg);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
            }
        });
        icon.setClipToOutline(true);
        icon.setElevation(dp(8));
        head.addView(icon, new LinearLayout.LayoutParams(dp(68), dp(68)));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1f);
        nlp.setMarginStart(dp(18));
        platform = new TextView(this);
        platform.setTypeface(PocketFonts.semibold());
        platform.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        platform.setLetterSpacing(0.12f);
        platform.setAllCaps(true);
        platform.setTextColor(0xB3FFFFFF);
        platform.setText("ra".equals(target.kind) ? "RetroAchievements" : "Steam");
        names.addView(platform);
        title = new TextView(this);
        title.setText(name);
        title.setTypeface(PocketFonts.bold());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        title.setLetterSpacing(-0.025f);
        title.setTextColor(0xFFFFFFFF);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = dp(2);
        names.addView(title, tlp);
        stats = new TextView(this);
        stats.setTypeface(PocketFonts.medium());
        stats.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        stats.setTextColor(0xCCFFFFFF);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(4);
        names.addView(stats, slp);
        head.addView(names, nlp);

        ring = new Ring(this);
        head.addView(ring, new LinearLayout.LayoutParams(dp(84), dp(84)));
        main.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // All / Unlocked / Locked
        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(dp(3), 0, dp(3), 0);
        GradientDrawable capsule = new GradientDrawable();
        capsule.setCornerRadius(dp(13));
        capsule.setColor(0xD91C1C1F);
        capsule.setStroke(Math.max(1, dp(0.75f)), 0x26FFFFFF);
        tabs.setBackground(capsule);
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(-2, dp(26));
        tabsLp.bottomMargin = dp(8);
        main.addView(tabs, tabsLp);
        drawTabs();

        FrameLayout body = new FrameLayout(this);
        list = new RecyclerView(this);
        list.setLayoutManager(new GridLayoutManager(this, 2));
        list.setItemAnimator(null);
        list.setClipToPadding(false);
        list.setClipChildren(false);
        list.setPadding(0, dp(6), 0, dp(70));
        list.setVerticalFadingEdgeEnabled(true);
        list.setFadingEdgeLength(dp(24));
        list.setAdapter(rows);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            list.setDefaultFocusHighlightEnabled(false);
        }
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        body.addView(spinner, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER));
        empty = new TextView(this);
        empty.setTypeface(PocketFonts.medium());
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        empty.setTextColor(0xB3FFFFFF);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        body.addView(empty, new FrameLayout.LayoutParams(dp(420), -2, Gravity.CENTER));
        main.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout hints = new LinearLayout(this);
        hints.setId(R.id.pocketHintBar);
        hints.setOrientation(LinearLayout.HORIZONTAL);
        hints.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        hlp.setMarginEnd(dp(18));
        hlp.bottomMargin = dp(9);
        root.addView(hints, hlp);
        return root;
    }

    private void drawTabs() {
        tabs.removeAllViews();
        int accent = PocketTheme.accent(this);
        int[] labels = {R.string.pocket_trophy_all, R.string.pocket_trophy_unlocked, R.string.pocket_trophy_locked};
        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            boolean on = i == filter;
            TextView tab = new TextView(this);
            String text = getString(labels[i]);
            if (set != null) {
                int n = i == ALL ? set.total : (i == UNLOCKED ? set.earned : set.total - set.earned);
                text += "  " + n;
            }
            tab.setText(text);
            tab.setTypeface(on ? PocketFonts.semibold() : PocketFonts.medium());
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            tab.setIncludeFontPadding(false);
            tab.setGravity(Gravity.CENTER);
            tab.setTextColor(on ? (accent == 0xFFFFFFFF ? 0xFF0B0B0D : 0xFFFFFFFF) : 0xB3FFFFFF);
            tab.setPadding(dp(12), 0, dp(12), 0);
            if (on) {
                GradientDrawable chip = new GradientDrawable();
                chip.setCornerRadius(dp(10));
                chip.setColor(accent);
                tab.setBackground(chip);
            }
            tab.setOnClickListener(v -> setFilter(which));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(20));
            lp.setMargins(dp(0.5f), 0, dp(0.5f), 0);
            tabs.addView(tab, lp);
        }
    }

    private void setFilter(int f) {
        if (f == filter) {
            return;
        }
        filter = f;
        PocketSounds.play(this, PocketSounds.NAV);
        drawTabs();
        fill();
        list.scrollToPosition(0);
        list.post(() -> {
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(0);
            if (h != null) {
                h.itemView.requestFocus();
            }
        });
    }

    @Override
    protected boolean onShoulder(int direction) {
        setFilter((filter + direction + 3) % 3);
        return true;
    }

    @Override
    protected boolean onButtonX() {
        PocketSounds.play(this, PocketSounds.SELECT);
        PocketMotion.pop(ring, 0.92f);
        load(true);
        return true;
    }

    // ---- Showing them ---------------------------------------------------------------------------

    private void show(Achievements.Set s, boolean refresh) {
        set = s;
        empty.setVisibility(s.total == 0 ? View.VISIBLE : View.GONE);
        if (s.total == 0) {
            empty.setText(R.string.pocket_trophy_none);
        }
        if (s.title != null && !s.title.isEmpty()) {
            title.setText(s.title);
        }
        String source = "ra".equals(s.source) ? "RetroAchievements" : "Steam";
        platform.setText(s.platform != null && !s.platform.isEmpty() && !"Steam".equals(s.platform)
                ? source + "  ·  " + s.platform : source);
        StringBuilder line = new StringBuilder(getString(R.string.pocket_trophy_count, s.earned, s.total));
        if (s.pointsTotal > 0) {
            line.append("   ·   ").append(getString(R.string.pocket_trophy_points, s.points, s.pointsTotal));
        }
        stats.setText(line);
        ring.set(s.total == 0 ? 0f : s.earned / (float) s.total, !refresh);
        if (s.icon != null) {
            RemoteImages.load(this, s.icon, dp(68), b -> {
                if (b != null) {
                    icon.setImageBitmap(b);
                }
            });
        }
        else if (icon.getDrawable() == null && art.getDrawable() == null) {
            icon.setImageResource(R.drawable.ic_pocket_trophy);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            icon.setPadding(dp(16), dp(16), dp(16), dp(16));
        }
        drawTabs();
        fill();
        if (!refresh) {
            list.post(() -> {
                RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(0);
                if (h != null) {
                    h.itemView.requestFocus();
                }
            });
        }
    }

    private void fill() {
        shown.clear();
        if (set != null) {
            for (Achievements.Trophy t : set.trophies) {
                if (filter == ALL || (filter == UNLOCKED) == t.earned) {
                    shown.add(t);
                }
            }
        }
        rows.notifyDataSetChanged();
    }

    // ---- The rows ---------------------------------------------------------------------------------

    private static final class Row extends RecyclerView.ViewHolder {
        final ImageView badge;
        final TextView name;
        final TextView desc;
        final TextView rarity;
        final TextView when;
        final GradientDrawable face;
        String url;

        Row(View v, ImageView badge, TextView name, TextView desc, TextView rarity, TextView when, GradientDrawable face) {
            super(v);
            this.badge = badge;
            this.name = name;
            this.desc = desc;
            this.rarity = rarity;
            this.when = when;
            this.face = face;
        }
    }

    private static final ColorMatrixColorFilter GREY;

    static {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0f);
        GREY = new ColorMatrixColorFilter(m);
    }

    private final class Rows extends RecyclerView.Adapter<Row> {
        @NonNull
        @Override
        public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = AchievementsActivity.this;
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(10), dp(9), dp(14), dp(9));
            card.setFocusable(true);
            card.setClickable(true);
            GradientDrawable face = new GradientDrawable();
            face.setCornerRadius(dp(14));
            card.setBackground(face);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                card.setDefaultFocusHighlightEnabled(false);
            }
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, dp(72));
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            card.setLayoutParams(lp);

            ImageView badge = new ImageView(c);
            badge.setScaleType(ImageView.ScaleType.CENTER_CROP);
            badge.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
                }
            });
            badge.setClipToOutline(true);
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(0xFF24242A);
            badgeBg.setCornerRadius(dp(10));
            badge.setBackground(badgeBg);
            card.addView(badge, new LinearLayout.LayoutParams(dp(52), dp(52)));

            LinearLayout text = new LinearLayout(c);
            text.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
            tlp.setMarginStart(dp(12));
            TextView name = new TextView(c);
            name.setTypeface(PocketFonts.semibold());
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            name.setTextColor(0xFFFFFFFF);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(name);
            TextView desc = new TextView(c);
            desc.setTypeface(PocketFonts.regular());
            desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            desc.setTextColor(0xB3FFFFFF);
            desc.setMaxLines(2);
            desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
            dlp.topMargin = dp(2);
            text.addView(desc, dlp);
            card.addView(text, tlp);

            LinearLayout side = new LinearLayout(c);
            side.setOrientation(LinearLayout.VERTICAL);
            side.setGravity(Gravity.END);
            LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(-2, -2);
            sideLp.setMarginStart(dp(10));
            TextView rarity = new TextView(c);
            rarity.setTypeface(PocketFonts.semibold());
            rarity.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            rarity.setIncludeFontPadding(false);
            rarity.setPadding(dp(7), dp(3), dp(7), dp(3));
            side.addView(rarity);
            TextView when = new TextView(c);
            when.setTypeface(PocketFonts.medium());
            when.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            when.setTextColor(0x99FFFFFF);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-2, -2);
            wlp.topMargin = dp(4);
            wlp.gravity = Gravity.END;
            side.addView(when, wlp);
            card.addView(side, sideLp);

            final Row row = new Row(card, badge, name, desc, rarity, when, face);
            card.setOnFocusChangeListener((v, has) -> {
                look(row, has, true);
                if (has) {
                    PocketUi.tick(AchievementsActivity.this);
                }
            });
            card.setOnKeyListener((v, keyCode, event) -> {
                // The first row's UP: nothing above the list
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.getAction() == KeyEvent.ACTION_DOWN
                        && row.getBindingAdapterPosition() < 2) {
                    return true;
                }
                return false;
            });
            return row;
        }

        @Override
        public void onBindViewHolder(@NonNull Row h, int position) {
            Achievements.Trophy t = shown.get(position);
            h.name.setText(t.title);
            h.desc.setText(t.description);
            // Rarity, coloured like the consoles' tiers
            if (t.rarity >= 0) {
                int[] tier = tier(t.rarity);
                h.rarity.setText(getString(tier[0]) + "  " + formatPercent(t.rarity));
                h.rarity.setTextColor(tier[1]);
                GradientDrawable pill = new GradientDrawable();
                pill.setCornerRadius(dp(100));
                pill.setColor((tier[1] & 0x00FFFFFF) | 0x26000000);
                h.rarity.setBackground(pill);
                h.rarity.setVisibility(View.VISIBLE);
            }
            else {
                h.rarity.setVisibility(View.GONE);
            }
            StringBuilder when = new StringBuilder();
            if (t.points > 0) {
                when.append(getString(R.string.pocket_trophy_pts, t.points));
            }
            if (t.earned && t.earnedAt > 0) {
                if (when.length() > 0) {
                    when.append("  ·  ");
                }
                when.append(DateUtils.formatDateTime(AchievementsActivity.this, t.earnedAt,
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH));
            }
            h.when.setText(when);
            h.when.setVisibility(when.length() > 0 ? View.VISIBLE : View.GONE);
            h.name.setTextColor(t.earned ? 0xFFFFFFFF : 0xC7FFFFFF);
            h.badge.setAlpha(t.earned ? 1f : 0.55f);
            h.badge.setColorFilter(t.earned ? null : GREY);
            final String url = t.earned ? t.icon : (t.iconLocked != null ? t.iconLocked : t.icon);
            h.url = url;
            h.badge.setImageDrawable(null);
            if (url != null) {
                RemoteImages.load(AchievementsActivity.this, url, dp(52), b -> {
                    if (b != null && url.equals(h.url)) {
                        h.badge.setImageBitmap(b);
                    }
                });
            }
            h.itemView.setTag(t);
            look(h, h.itemView.hasFocus(), false);
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }

    /** Earned: a warm edge; focused: a white ring and a little lift. */
    private void look(Row r, boolean focused, boolean animate) {
        Object tag = r.itemView.getTag();
        boolean earned = tag instanceof Achievements.Trophy && ((Achievements.Trophy) tag).earned;
        r.face.setColor(focused ? 0x40FFFFFF : (earned ? 0x2EFFD27A : 0x1FFFFFFF));
        r.face.setStroke(focused ? Math.max(2, dp(2)) : Math.max(1, dp(0.75f)),
                focused ? 0xFFFFFFFF : (earned ? 0x59FFD27A : 0x1FFFFFFF));
        float s = focused ? 1.025f : 1f;
        if (animate && PocketPrefs.animationsEnabled(this)) {
            r.itemView.animate().scaleX(s).scaleY(s).translationZ(focused ? dp(6) : 0)
                    .setDuration(Spring.UI_DURATION_MS).setInterpolator(Spring.UI_CURVE).start();
        }
        else {
            r.itemView.setScaleX(s);
            r.itemView.setScaleY(s);
        }
    }

    /** Ultra rare / very rare / rare / uncommon / common, and its colour. */
    private static int[] tier(float pct) {
        if (pct < 5f) {
            return new int[]{R.string.pocket_trophy_ultra_rare, 0xFFFFC94D};
        }
        if (pct < 15f) {
            return new int[]{R.string.pocket_trophy_very_rare, 0xFFC08CFF};
        }
        if (pct < 30f) {
            return new int[]{R.string.pocket_trophy_rare, 0xFF6FB8FF};
        }
        if (pct < 60f) {
            return new int[]{R.string.pocket_trophy_uncommon, 0xFF7EE0A6};
        }
        return new int[]{R.string.pocket_trophy_common, 0xFFB8BCC8};
    }

    private static String formatPercent(float pct) {
        return pct < 10f ? String.format(java.util.Locale.getDefault(), "%.1f%%", pct)
                : String.format(java.util.Locale.getDefault(), "%.0f%%", pct);
    }

    // ---- The ring ---------------------------------------------------------------------------------

    /** How much of the game is done: an arc that fills in, the percentage in the middle. */
    private final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float value;
        private float shownValue;

        Ring(Context c) {
            super(c);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(dp(7));
            track.setColor(0x26FFFFFF);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(dp(7));
            arc.setStrokeCap(Paint.Cap.ROUND);
            text.setColor(0xFFFFFFFF);
            text.setTypeface(PocketFonts.bold());
            text.setTextSize(dp(20));
            text.setTextAlign(Paint.Align.CENTER);
            small.setColor(0x99FFFFFF);
            small.setTypeface(PocketFonts.medium());
            small.setTextSize(dp(8.5f));
            small.setTextAlign(Paint.Align.CENTER);
            small.setLetterSpacing(0.1f);
        }

        void set(float v, boolean animate) {
            value = v;
            if (animate && PocketPrefs.animationsEnabled(getContext())) {
                android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, v);
                a.setDuration(1100);
                a.setInterpolator(PocketMotion.DECELERATE);
                a.addUpdateListener(an -> {
                    shownValue = (float) an.getAnimatedValue();
                    invalidate();
                });
                a.start();
            }
            else {
                shownValue = v;
                invalidate();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            float inset = dp(5);
            box.set(inset, inset, w - inset, h - inset);
            arc.setShader(new SweepGradient(w / 2f, h / 2f,
                    new int[]{0xFF7EE0A6, 0xFF6FB8FF, 0xFFC08CFF, 0xFFFFC94D, 0xFF7EE0A6}, null));
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawArc(box, 0, 360, false, track);
            if (shownValue > 0f) {
                c.save();
                c.rotate(-90, getWidth() / 2f, getHeight() / 2f);
                c.drawArc(box, 0, 360f * Math.min(1f, shownValue), false, arc);
                c.restore();
            }
            float cy = getHeight() / 2f;
            c.drawText(Math.round(shownValue * 100) + "%", getWidth() / 2f, cy + dp(5), text);
            c.drawText(getString(R.string.pocket_trophy_done).toUpperCase(java.util.Locale.getDefault()),
                    getWidth() / 2f, cy + dp(17), small);
        }
    }
}
