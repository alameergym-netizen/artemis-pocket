package com.limelight.pocket.retro;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
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
import androidx.recyclerview.widget.LinearLayoutManager;
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
import com.limelight.pocket.ui.SoftRing;
import com.limelight.pocket.ui.Spring;

import java.util.ArrayList;
import java.util.List;

/**
 * A game's achievements, laid out like a console's: on the left, the game and how far along it
 * is (a bar that fills, the points, a gold "Mastered" when it's all done), and under it the
 * achievement you're on, big: its badge, what it asks, how rare it is, when you got it. On the
 * right, every achievement in a list (unlocked first, newest first). L1 / R1 filter, X refreshes.
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
    private static final int GOLD = 0xFFFFC94D;

    private float d;
    private int accent;
    private Achievements.Target target;
    private Achievements.Set set;
    private int filter = ALL;
    private final List<Achievements.Trophy> shown = new ArrayList<>();
    private RecyclerView list;
    private TextView title;
    private TextView platform;
    private TextView stats;
    private TextView mastered;
    private Bar bar;
    private TextView percent;
    private ImageView icon;
    private ImageView art;
    private ProgressBar spinner;
    private TextView empty;
    private LinearLayout tabs;
    private final Rows rows = new Rows();

    // The focused achievement, big
    private View detail;
    private ImageView bigBadge;
    private TextView bigTitle;
    private TextView bigDesc;
    private TextView bigRarity;
    private Bar rarityBar;
    private TextView bigWhen;
    private TextView bigPoints;
    private Achievements.Trophy detailFor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        d = getResources().getDisplayMetrics().density;
        accent = PocketTheme.accent(this);
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
                    art.animate().alpha(1f).setDuration(600).start();
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

    private TextView text(float sp, android.graphics.Typeface face, int color) {
        TextView t = new TextView(this);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static void round(View v, final float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
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
            art.setRenderEffect(RenderEffect.createBlurEffect(dp(22), dp(22), Shader.TileMode.CLAMP));
        }
        backdrop.addView(art, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xE6050507, 0xB3050507, 0xD9050507}));
        backdrop.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xCC000000}));
        backdrop.addView(bottom, new FrameLayout.LayoutParams(-1, dp(160), Gravity.BOTTOM));
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout main = new LinearLayout(this);
        main.setId(R.id.pocketMain);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(26), dp(12), dp(26), 0);
        main.setClipChildren(false);
        main.setClipToPadding(false);
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));

        // Top: where you are, and the status
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView where = text(14f, PocketFonts.semibold(), 0xFFFFFFFF);
        where.setId(R.id.appListText);
        where.setText(R.string.pocket_trophy_title);
        top.addView(where);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        for (int id : new int[]{R.id.pocketLinkChip, R.id.pocketBattery, R.id.pocketClock}) {
            TextView t = text(id == R.id.pocketClock ? 12.5f : 11f,
                    id == R.id.pocketClock ? PocketFonts.semibold() : PocketFonts.medium(), 0xFFFFFFFF);
            t.setId(id);
            t.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginStart(dp(12));
            top.addView(t, lp);
        }
        main.addView(top, new LinearLayout.LayoutParams(-1, dp(28)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        bodyLp.topMargin = dp(8);
        main.addView(body, bodyLp);

        body.addView(buildSide(name), new LinearLayout.LayoutParams(dp(268), -1));

        // The list, with its filter above it
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setClipChildren(false);
        right.setClipToPadding(false);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, -1, 1f);
        rightLp.setMarginStart(dp(18));
        body.addView(right, rightLp);

        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(-2, dp(28));
        tabsLp.bottomMargin = dp(6);
        right.addView(tabs, tabsLp);
        drawTabs();

        FrameLayout listBox = new FrameLayout(this);
        listBox.setClipChildren(false);
        listBox.setClipToPadding(false);
        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemAnimator(null);
        list.setClipToPadding(false);
        list.setClipChildren(false);
        list.setPadding(dp(6), dp(8), dp(10), dp(64));
        list.setVerticalFadingEdgeEnabled(true);
        list.setFadingEdgeLength(dp(28));
        list.setAdapter(rows);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            list.setDefaultFocusHighlightEnabled(false);
        }
        listBox.addView(list, new FrameLayout.LayoutParams(-1, -1));
        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        listBox.addView(spinner, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
        empty = text(13f, PocketFonts.medium(), 0xB3FFFFFF);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(dp(3), 1f);
        empty.setVisibility(View.GONE);
        listBox.addView(empty, new FrameLayout.LayoutParams(dp(380), -2, Gravity.CENTER));
        right.addView(listBox, new LinearLayout.LayoutParams(-1, 0, 1f));

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

    /** The left column: the game, how far along, and the achievement you're on. */
    private View buildSide(String name) {
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(dp(16), dp(16), dp(16), dp(14));
        side.setTag("glass");
        GradientDrawable panel = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB31A1A20, 0xCC101014});
        panel.setCornerRadius(dp(22));
        panel.setStroke(Math.max(1, dp(0.8f)), 0x1FFFFFFF);
        side.setBackground(panel);
        LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(-1, -1);
        side.setLayoutParams(sideLp);

        // The game
        LinearLayout game = new LinearLayout(this);
        game.setOrientation(LinearLayout.HORIZONTAL);
        game.setGravity(Gravity.CENTER_VERTICAL);
        icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setCornerRadius(dp(14));
        iconBg.setColor(0xFF1C1C21);
        icon.setBackground(iconBg);
        round(icon, dp(14));
        icon.setElevation(dp(6));
        game.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1f);
        nlp.setMarginStart(dp(12));
        platform = text(9.5f, PocketFonts.semibold(), 0x99FFFFFF);
        platform.setLetterSpacing(0.14f);
        platform.setAllCaps(true);
        platform.setMaxLines(1);
        platform.setEllipsize(TextUtils.TruncateAt.END);
        platform.setText("ra".equals(target.kind) ? "RetroAchievements" : "Steam");
        names.addView(platform);
        title = text(16f, PocketFonts.bold(), 0xFFFFFFFF);
        title.setText(name);
        title.setLetterSpacing(-0.02f);
        title.setMaxLines(2);
        title.setLineSpacing(0, 1.05f);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(4);
        names.addView(title, tlp);
        game.addView(names, nlp);
        side.addView(game);

        // How far along: a bar, the percentage, the points; gold when it's all done
        LinearLayout prog = new LinearLayout(this);
        prog.setOrientation(LinearLayout.HORIZONTAL);
        prog.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = dp(16);
        bar = new Bar(this, 8f);
        prog.addView(bar, new LinearLayout.LayoutParams(0, dp(8), 1f));
        percent = text(15f, PocketFonts.bold(), 0xFFFFFFFF);
        LinearLayout.LayoutParams pclp = new LinearLayout.LayoutParams(-2, -2);
        pclp.setMarginStart(dp(10));
        prog.addView(percent, pclp);
        side.addView(prog, plp);
        LinearLayout statRow = new LinearLayout(this);
        statRow.setOrientation(LinearLayout.HORIZONTAL);
        statRow.setGravity(Gravity.CENTER_VERTICAL);
        stats = text(11.5f, PocketFonts.medium(), 0xC7FFFFFF);
        statRow.addView(stats, new LinearLayout.LayoutParams(0, -2, 1f));
        mastered = text(9.5f, PocketFonts.bold(), 0xFF1A1405);
        mastered.setText(R.string.pocket_trophy_mastered_chip);
        mastered.setLetterSpacing(0.1f);
        mastered.setAllCaps(true);
        mastered.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable gold = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFFFE08A, GOLD, 0xFFE0A526});
        gold.setCornerRadius(dp(100));
        mastered.setBackground(gold);
        mastered.setVisibility(View.GONE);
        statRow.addView(mastered);
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(-1, -2);
        srlp.topMargin = dp(8);
        side.addView(statRow, srlp);

        View line = new View(this);
        line.setBackgroundColor(0x1AFFFFFF);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.8f)));
        llp.topMargin = dp(14);
        llp.bottomMargin = dp(14);
        side.addView(line, llp);

        // The achievement you're on
        LinearLayout det = new LinearLayout(this);
        det.setOrientation(LinearLayout.VERTICAL);
        detail = det;
        LinearLayout badgeRow = new LinearLayout(this);
        badgeRow.setOrientation(LinearLayout.HORIZONTAL);
        badgeRow.setGravity(Gravity.CENTER_VERTICAL);
        bigBadge = new ImageView(this);
        bigBadge.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(0xFF24242A);
        bb.setCornerRadius(dp(16));
        bigBadge.setBackground(bb);
        round(bigBadge, dp(16));
        bigBadge.setElevation(dp(8));
        badgeRow.addView(bigBadge, new LinearLayout.LayoutParams(dp(76), dp(76)));
        LinearLayout pointsCol = new LinearLayout(this);
        pointsCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams pcl = new LinearLayout.LayoutParams(0, -2, 1f);
        pcl.setMarginStart(dp(14));
        bigPoints = text(22f, PocketFonts.bold(), 0xFFFFFFFF);
        pointsCol.addView(bigPoints);
        bigWhen = text(10.5f, PocketFonts.medium(), 0xA6FFFFFF);
        bigWhen.setMaxLines(2);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-2, -2);
        wlp.topMargin = dp(5);
        pointsCol.addView(bigWhen, wlp);
        badgeRow.addView(pointsCol, pcl);
        det.addView(badgeRow);
        bigTitle = text(15.5f, PocketFonts.bold(), 0xFFFFFFFF);
        bigTitle.setMaxLines(2);
        bigTitle.setEllipsize(TextUtils.TruncateAt.END);
        bigTitle.setLineSpacing(0, 1.05f);
        LinearLayout.LayoutParams btl = new LinearLayout.LayoutParams(-1, -2);
        btl.topMargin = dp(14);
        det.addView(bigTitle, btl);
        bigDesc = text(12f, PocketFonts.regular(), 0xC7FFFFFF);
        bigDesc.setMaxLines(4);
        bigDesc.setEllipsize(TextUtils.TruncateAt.END);
        bigDesc.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams bdl = new LinearLayout.LayoutParams(-1, -2);
        bdl.topMargin = dp(6);
        det.addView(bigDesc, bdl);
        bigRarity = text(10.5f, PocketFonts.semibold(), 0xFFFFFFFF);
        LinearLayout.LayoutParams brl = new LinearLayout.LayoutParams(-1, -2);
        brl.topMargin = dp(12);
        det.addView(bigRarity, brl);
        rarityBar = new Bar(this, 4f);
        LinearLayout.LayoutParams rbl = new LinearLayout.LayoutParams(-1, dp(4));
        rbl.topMargin = dp(6);
        det.addView(rarityBar, rbl);
        det.setAlpha(0f);
        side.addView(det, new LinearLayout.LayoutParams(-1, -2));
        return side;
    }

    private void drawTabs() {
        tabs.removeAllViews();
        int[] labels = {R.string.pocket_trophy_all, R.string.pocket_trophy_unlocked, R.string.pocket_trophy_locked};
        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            boolean on = i == filter;
            TextView tab = text(12.5f, on ? PocketFonts.semibold() : PocketFonts.medium(), on ? 0xFFFFFFFF : 0x8CFFFFFF);
            String label = getString(labels[i]);
            if (set != null) {
                int n = i == ALL ? set.total : (i == UNLOCKED ? set.earned : set.total - set.earned);
                label += "  " + n;
            }
            tab.setText(label);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(2), 0, dp(2), dp(4));
            if (on) {
                // A short line under the chosen one (like the section tabs)
                GradientDrawable under = new GradientDrawable();
                under.setColor(accent == 0xFFFFFFFF ? 0xFFFFFFFF : accent);
                under.setCornerRadius(dp(2));
                android.graphics.drawable.LayerDrawable bg = new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{under});
                bg.setLayerGravity(0, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                bg.setLayerSize(0, dp(18), dp(3));
                tab.setBackground(bg);
            }
            tab.setOnClickListener(v -> setFilter(which));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -1);
            lp.setMarginEnd(dp(20));
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
            else {
                showDetail(null, true);
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
        PocketMotion.pop(percent, 0.9f);
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
                ? s.platform + "  ·  " + source : source);
        StringBuilder line = new StringBuilder(getString(R.string.pocket_trophy_count, s.earned, s.total));
        if (s.pointsTotal > 0) {
            line.append("\n").append(getString(R.string.pocket_trophy_points, s.points, s.pointsTotal));
        }
        stats.setText(line);
        stats.setLineSpacing(dp(3), 1f);
        float frac = s.total == 0 ? 0f : s.earned / (float) s.total;
        boolean done = s.total > 0 && s.earned >= s.total;
        bar.setColors(done ? new int[]{0xFFFFE08A, GOLD} : new int[]{0xFF6FB8FF, 0xFFC08CFF});
        bar.set(frac, !refresh);
        percent.setText(Math.round(frac * 100) + "%");
        percent.setTextColor(done ? GOLD : 0xFFFFFFFF);
        mastered.setVisibility(done ? View.VISIBLE : View.GONE);
        if (s.icon != null) {
            RemoteImages.load(this, s.icon, dp(54), b -> {
                if (b != null) {
                    icon.setImageBitmap(b);
                }
            });
        }
        else if (icon.getDrawable() == null) {
            icon.setImageResource(R.drawable.ic_pocket_trophy);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            icon.setPadding(dp(14), dp(14), dp(14), dp(14));
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
        else if (detailFor != null) {
            showDetail(Achievements.find(s, detailFor.title), false);
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

    /** The left column's big view of one achievement (null: none). */
    private void showDetail(final Achievements.Trophy t, boolean animate) {
        if (t == null) {
            detail.animate().alpha(0f).setDuration(120).start();
            detailFor = null;
            return;
        }
        boolean same = detailFor != null && detailFor.title.equals(t.title);
        detailFor = t;
        Runnable fillIn = () -> {
            bigTitle.setText(t.title);
            bigDesc.setText(t.description);
            bigPoints.setText(t.points > 0 ? getString(R.string.pocket_trophy_pts, t.points) : "");
            bigPoints.setTextColor(t.earned ? 0xFFFFFFFF : 0x99FFFFFF);
            StringBuilder when = new StringBuilder();
            if (t.earned) {
                when.append(getString(R.string.pocket_trophy_earned_on, t.earnedAt > 0
                        ? DateUtils.formatDateTime(this, t.earnedAt, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR
                        | DateUtils.FORMAT_ABBREV_MONTH) : ""));
                if (t.hardcore) {
                    when.append("  ·  ").append(getString(R.string.pocket_trophy_hardcore));
                }
            }
            else {
                when.append(getString(R.string.pocket_trophy_locked_note));
            }
            bigWhen.setText(when);
            if (t.rarity >= 0) {
                int[] tier = tier(t.rarity);
                bigRarity.setText(getString(tier[0]) + "  ·  " + getString(R.string.pocket_trophy_rarity_players,
                        formatPercent(t.rarity)));
                bigRarity.setTextColor(tier[1]);
                rarityBar.setColors(new int[]{tier[1], tier[1]});
                rarityBar.set(Math.max(0.02f, t.rarity / 100f), animate);
                bigRarity.setVisibility(View.VISIBLE);
                rarityBar.setVisibility(View.VISIBLE);
            }
            else {
                bigRarity.setVisibility(View.GONE);
                rarityBar.setVisibility(View.GONE);
            }
            bigBadge.setColorFilter(t.earned ? null : GREY);
            bigBadge.setAlpha(t.earned ? 1f : 0.6f);
            bigBadge.setImageDrawable(null);
            final String url = t.earned ? t.icon : (t.iconLocked != null ? t.iconLocked : t.icon);
            if (url != null) {
                RemoteImages.load(this, url, dp(76), b -> {
                    if (b != null && detailFor == t) {
                        bigBadge.setImageBitmap(b);
                    }
                });
            }
        };
        if (!animate || !PocketPrefs.animationsEnabled(this) || same) {
            fillIn.run();
            detail.setAlpha(1f);
            detail.setTranslationY(0);
            return;
        }
        detail.animate().cancel();
        detail.animate().alpha(0f).translationY(dp(4)).setDuration(90).withEndAction(() -> {
            fillIn.run();
            detail.setTranslationY(-dp(6));
            detail.animate().alpha(1f).translationY(0).setDuration(240)
                    .setInterpolator(PocketUi.EASE).withEndAction(null).start();
            PocketMotion.pop(bigBadge, 0.94f);
        }).start();
    }

    // ---- The rows ---------------------------------------------------------------------------------

    private static final class Row extends RecyclerView.ViewHolder {
        final ImageView badge;
        final ImageView lock;
        final TextView name;
        final TextView desc;
        final TextView rarity;
        final TextView points;
        final GradientDrawable face;
        final SoftRing ring;
        String url;

        Row(View v, ImageView badge, ImageView lock, TextView name, TextView desc, TextView rarity, TextView points,
            GradientDrawable face, SoftRing ring) {
            super(v);
            this.badge = badge;
            this.lock = lock;
            this.name = name;
            this.desc = desc;
            this.rarity = rarity;
            this.points = points;
            this.face = face;
            this.ring = ring;
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
            card.setPadding(dp(9), dp(8), dp(14), dp(8));
            card.setFocusable(true);
            card.setClickable(true);
            card.setClipChildren(false);
            GradientDrawable face = new GradientDrawable();
            face.setCornerRadius(dp(16));
            card.setBackground(face);
            SoftRing ring = SoftRing.forTheme(c, dp(16), false);
            ring.setAlpha(0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                card.setForeground(ring);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                card.setDefaultFocusHighlightEnabled(false);
            }
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, dp(66));
            lp.setMargins(0, dp(4), 0, dp(4));
            card.setLayoutParams(lp);

            FrameLayout badgeBox = new FrameLayout(c);
            ImageView badge = new ImageView(c);
            badge.setScaleType(ImageView.ScaleType.CENTER_CROP);
            round(badge, dp(11));
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(0xFF24242A);
            badgeBg.setCornerRadius(dp(11));
            badge.setBackground(badgeBg);
            badgeBox.addView(badge, new FrameLayout.LayoutParams(dp(48), dp(48)));
            ImageView lock = new ImageView(c);
            lock.setImageResource(R.drawable.ic_lock);
            lock.setColorFilter(0xFFFFFFFF);
            lock.setPadding(dp(3), dp(3), dp(3), dp(3));
            GradientDrawable lockBg = new GradientDrawable();
            lockBg.setShape(GradientDrawable.OVAL);
            lockBg.setColor(0xE6101014);
            lock.setBackground(lockBg);
            FrameLayout.LayoutParams lockLp = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.BOTTOM | Gravity.END);
            lockLp.setMargins(0, 0, -dp(3), -dp(3));
            badgeBox.setClipChildren(false);
            badgeBox.addView(lock, lockLp);
            card.addView(badgeBox, new LinearLayout.LayoutParams(dp(48), dp(48)));

            LinearLayout text = new LinearLayout(c);
            text.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
            tlp.setMarginStart(dp(12));
            TextView name = text(13.5f, PocketFonts.semibold(), 0xFFFFFFFF);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(name);
            TextView desc = text(11f, PocketFonts.regular(), 0xA6FFFFFF);
            desc.setMaxLines(1);
            desc.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
            dlp.topMargin = dp(4);
            text.addView(desc, dlp);
            card.addView(text, tlp);

            LinearLayout side = new LinearLayout(c);
            side.setOrientation(LinearLayout.VERTICAL);
            side.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(-2, -2);
            sideLp.setMarginStart(dp(10));
            TextView points = text(12.5f, PocketFonts.bold(), 0xFFFFFFFF);
            points.setGravity(Gravity.END);
            side.addView(points, new LinearLayout.LayoutParams(-2, -2));
            TextView rarity = text(10f, PocketFonts.semibold(), 0xFFFFFFFF);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
            rlp.topMargin = dp(4);
            rlp.gravity = Gravity.END;
            side.addView(rarity, rlp);
            card.addView(side, sideLp);

            final Row row = new Row(card, badge, lock, name, desc, rarity, points, face, ring);
            card.setOnFocusChangeListener((v, has) -> {
                look(row, has, true);
                if (has) {
                    PocketUi.tick(AchievementsActivity.this);
                    Object tag = v.getTag();
                    if (tag instanceof Achievements.Trophy) {
                        showDetail((Achievements.Trophy) tag, true);
                    }
                }
            });
            card.setOnKeyListener((v, keyCode, event) -> keyCode == KeyEvent.KEYCODE_DPAD_UP
                    && event.getAction() == KeyEvent.ACTION_DOWN && row.getBindingAdapterPosition() == 0);
            return row;
        }

        @Override
        public void onBindViewHolder(@NonNull Row h, int position) {
            Achievements.Trophy t = shown.get(position);
            h.name.setText(t.title);
            h.desc.setText(t.description);
            if (t.rarity >= 0) {
                int[] tier = tier(t.rarity);
                h.rarity.setText(formatPercent(t.rarity));
                h.rarity.setTextColor(tier[1]);
                h.rarity.setVisibility(View.VISIBLE);
            }
            else {
                h.rarity.setVisibility(View.GONE);
            }
            h.points.setText(t.points > 0 ? getString(R.string.pocket_trophy_pts, t.points) : "");
            h.points.setTextColor(t.earned ? 0xFFFFFFFF : 0x80FFFFFF);
            h.name.setTextColor(t.earned ? 0xFFFFFFFF : 0xB3FFFFFF);
            h.badge.setAlpha(t.earned ? 1f : 0.5f);
            h.badge.setColorFilter(t.earned ? null : GREY);
            h.lock.setVisibility(t.earned ? View.GONE : View.VISIBLE);
            final String url = t.earned ? t.icon : (t.iconLocked != null ? t.iconLocked : t.icon);
            h.url = url;
            h.badge.setImageDrawable(null);
            if (url != null) {
                RemoteImages.load(AchievementsActivity.this, url, dp(48), b -> {
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

    /** Earned: a warm glass; focused: the soft glow every focused thing has, and a little lift. */
    private void look(final Row r, boolean focused, boolean animate) {
        Object tag = r.itemView.getTag();
        boolean earned = tag instanceof Achievements.Trophy && ((Achievements.Trophy) tag).earned;
        if (earned) {
            r.face.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
            r.face.setColors(focused ? new int[]{0x4DFFD27A, 0x26FFFFFF} : new int[]{0x2EFFD27A, 0x14FFFFFF});
        }
        else {
            r.face.setColors(focused ? new int[]{0x33FFFFFF, 0x26FFFFFF} : new int[]{0x14FFFFFF, 0x0FFFFFFF});
        }
        r.face.setStroke(Math.max(1, dp(0.75f)), earned ? 0x33FFD27A : 0x14FFFFFF);
        float s = focused ? 1.02f : 1f;
        int to = focused ? 255 : 0;
        if (animate && PocketPrefs.animationsEnabled(this)) {
            r.itemView.animate().scaleX(s).scaleY(s).translationZ(focused ? dp(6) : 0)
                    .setDuration(Spring.UI_DURATION_MS).setInterpolator(Spring.UI_CURVE).start();
            ValueAnimator a = ValueAnimator.ofInt(r.ring.getAlpha(), to);
            a.setDuration(focused ? 180 : 140);
            a.addUpdateListener(an -> r.ring.setAlpha((int) an.getAnimatedValue()));
            a.start();
        }
        else {
            r.itemView.setScaleX(s);
            r.itemView.setScaleY(s);
            r.ring.setAlpha(to);
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

    // ---- A bar that fills ------------------------------------------------------------------------

    private final class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int[] colors = {0xFF6FB8FF, 0xFFC08CFF};
        private float value;
        private float shownValue;
        private ValueAnimator anim;

        Bar(Context c, float heightDp) {
            super(c);
            track.setColor(0x1FFFFFFF);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                glow.setMaskFilter(new android.graphics.BlurMaskFilter(heightDp * d, android.graphics.BlurMaskFilter.Blur.NORMAL));
            }
        }

        void setColors(int[] c) {
            colors = c;
            invalidate();
        }

        void set(float v, boolean animate) {
            value = Math.max(0f, Math.min(1f, v));
            if (anim != null) {
                anim.cancel();
            }
            if (animate && PocketPrefs.animationsEnabled(getContext())) {
                anim = ValueAnimator.ofFloat(shownValue, value);
                anim.setDuration(900);
                anim.setInterpolator(PocketMotion.DECELERATE);
                anim.addUpdateListener(a -> {
                    shownValue = (float) a.getAnimatedValue();
                    invalidate();
                });
                anim.start();
            }
            else {
                shownValue = value;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight();
            float w = getWidth();
            r.set(0, 0, w, h);
            c.drawRoundRect(r, h / 2f, h / 2f, track);
            if (shownValue <= 0f) {
                return;
            }
            float fw = Math.max(h, w * shownValue);
            r.set(0, 0, fw, h);
            fill.setShader(new LinearGradient(0, 0, Math.max(fw, 1f), 0, colors[0], colors[colors.length - 1],
                    Shader.TileMode.CLAMP));
            glow.setShader(fill.getShader());
            glow.setAlpha(0x80);
            c.drawRoundRect(r, h / 2f, h / 2f, glow);
            c.drawRoundRect(r, h / 2f, h / 2f, fill);
        }
    }
}
