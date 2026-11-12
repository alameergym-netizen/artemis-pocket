package com.limelight.pocket.apps;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.pocket.ConsoleActivity;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.PocketSounds;
import com.limelight.pocket.home.HomeItem;
import com.limelight.pocket.home.HomePins;
import com.limelight.pocket.ui.PocketFonts;
import com.limelight.pocket.ui.PocketMotion;
import com.limelight.pocket.ui.PocketTheme;
import com.limelight.pocket.ui.PocketUi;
import com.limelight.pocket.ui.StreamMenuView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Every app on the handheld, the way a console shows its library: big tiles in the app's own
 * colours, a light behind the grid that takes the colour of the one in focus, categories on
 * L1 / R1 (all, games, emulators, apps), L2 / R2 jumping letter to letter, SELECT for the order
 * (A to Z, played lately, newest), X to search, Y for an app's options (Home, the Android section,
 * app info, uninstall). Nothing here needs a touch screen.
 */
public class AppDrawerActivity extends ConsoleActivity {

    public static void open(Activity from) {
        PocketSounds.play(from, PocketSounds.OPEN);
        from.startActivity(new Intent(from, AppDrawerActivity.class));
        from.overridePendingTransition(R.anim.pocket_section_in, R.anim.pocket_section_out);
    }

    private static final String KEY_USED = "pocket_apps_used_";
    private static final String KEY_SORT = "pocket_drawer_sort";
    private static final long NEW_FOR_MS = 3L * 24 * 60 * 60 * 1000;

    private static final int TAB_ALL = 0;
    private static final int TAB_GAMES = 1;
    private static final int TAB_EMULATORS = 2;
    private static final int TAB_APPS = 3;
    private static final int[] TABS = {R.string.pocket_drawer_all, R.string.pocket_drawer_games,
            R.string.pocket_drawer_emulators, R.string.pocket_drawer_apps};

    private static final int SORT_NAME = 0;
    private static final int SORT_RECENT = 1;
    private static final int SORT_NEW = 2;
    private static final int[] SORTS = {R.string.pocket_drawer_sort_name, R.string.pocket_drawer_sort_recent,
            R.string.pocket_drawer_sort_new};

    static final class App {
        final String pkg;
        final String label;
        final int type;
        final long installed;
        long used;
        boolean inLibrary;
        final String letter;

        App(String pkg, String label, int type, long installed, long used, boolean inLibrary) {
            this.pkg = pkg;
            this.label = label;
            this.type = type;
            this.installed = installed;
            this.used = used;
            this.inLibrary = inLibrary;
            String first = label.trim().isEmpty() ? "#" : label.trim().substring(0, 1).toUpperCase(Locale.getDefault());
            this.letter = Character.isLetter(first.charAt(0)) ? first : "#";
        }
    }

    private float d;
    private int accent;
    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private int tab = TAB_ALL;
    private int sort;
    private String query = "";
    private RecyclerView grid;
    private GridLayoutManager layout;
    private final Tiles tiles = new Tiles();
    private LinearLayout tabsView;
    private TextView count;
    private TextView sortView;
    private EditText search;
    private View searchBox;
    private TextView empty;
    private TextView letterToast;
    private View glow;
    private GradientDrawable glowFace;
    private ValueAnimator glowAnim;
    private int glowColor = 0xFF1A1A22;
    private int focusPos = -1;
    private int columns = 6;
    private int tileSize;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService iconWork = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> ICONS = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final Map<String, Integer> COLORS = new HashMap<>();

    @Override
    protected boolean allowsAutoResume() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        d = getResources().getDisplayMetrics().density;
        accent = PocketTheme.accent(this);
        sort = Math.max(0, Math.min(SORTS.length - 1, PocketPrefs.prefs(this).getInt(KEY_SORT, SORT_NAME)));
        setContentView(build());
        installLiveGlass();
        setHints(hintA(this, R.string.pocket_apps_open),
                hintY(this, R.string.pocket_hint_options),
                hintX(this, R.string.pocket_drawer_search),
                new Hint("L1 R1", 0xFFFFFFFF, getString(R.string.pocket_drawer_hint_category)),
                new Hint("L2 R2", 0xFFFFFFFF, getString(R.string.pocket_drawer_hint_letter)),
                hintSelect(this, R.string.pocket_drawer_hint_sort),
                hintB(this, R.string.pocket_hint_back));
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Something may have been installed or removed meanwhile
        if (!all.isEmpty()) {
            load();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        work.shutdownNow();
        iconWork.shutdownNow();
        if (glowAnim != null) {
            glowAnim.cancel();
        }
    }

    private int dp(float v) {
        return Math.round(v * d);
    }

    // ---- Building the screen ------------------------------------------------------------------

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        // Behind the grid: light in the colour of the app in focus
        FrameLayout backdrop = new FrameLayout(this);
        backdrop.setId(R.id.pocketBackdrop);
        glow = new View(this);
        glowFace = new GradientDrawable();
        glowFace.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowFace.setGradientCenter(0.85f, 0.0f);
        glowFace.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.8f);
        setGlowColors(glowColor);
        glow.setBackground(glowFace);
        backdrop.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x26000000, 0x8C050507, 0xE6000000}));
        backdrop.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout main = new LinearLayout(this);
        main.setId(R.id.pocketMain);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(30), dp(12), dp(30), 0);
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));

        // Top: what this is and how many; the status on the right (plain, like a console's)
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_pocket_all_apps);
        mark.setAlpha(0.9f);
        top.addView(mark, new LinearLayout.LayoutParams(dp(15), dp(15)));
        TextView where = new TextView(this);
        where.setId(R.id.appListText);
        where.setText(R.string.pocket_drawer_title);
        where.setTypeface(PocketFonts.semibold());
        where.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        where.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-2, -2);
        wlp.setMarginStart(dp(8));
        top.addView(where, wlp);
        count = new TextView(this);
        count.setTypeface(PocketFonts.medium());
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        count.setTextColor(0x8CFFFFFF);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.setMarginStart(dp(8));
        top.addView(count, clp);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
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

        // The categories as plain words (PlayStation Portal style), the order and search after them
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        tabsView = new LinearLayout(this);
        tabsView.setOrientation(LinearLayout.HORIZONTAL);
        tabsView.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(tabsView, new LinearLayout.LayoutParams(-2, dp(36)));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        sortView = plainAction("");
        sortView.setOnClickListener(v -> onSelectButton());
        bar.addView(sortView);
        TextView find = plainAction("\u2315  " + getString(R.string.pocket_drawer_search));
        find.setOnClickListener(v -> onButtonX());
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
        flp.setMarginStart(dp(18));
        bar.addView(find, flp);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(36));
        barLp.topMargin = dp(8);
        main.addView(bar, barLp);

        // Search (X): a field that filters as you type
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setCornerRadius(dp(10));
        boxBg.setColor(0x2EFFFFFF);
        box.setBackground(boxBg);
        TextView lens = new TextView(this);
        lens.setText("\u2315");
        lens.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        lens.setTextColor(0xB3FFFFFF);
        box.addView(lens);
        search = new EditText(this);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setHint(R.string.pocket_drawer_search_hint);
        search.setHintTextColor(0x66FFFFFF);
        search.setTextColor(0xFFFFFFFF);
        search.setTypeface(PocketFonts.medium());
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        search.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setPadding(dp(8), 0, 0, 0);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String q = s.toString().trim();
                if (!q.equals(query)) {
                    query = q;
                    apply(false);
                }
            }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            hideKeyboard();
            focusAt(0);
            return true;
        });
        search.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                hideKeyboard();
                focusAt(0);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                closeSearch();
                return true;
            }
            return false;
        });
        box.addView(search, new LinearLayout.LayoutParams(0, -1, 1f));
        searchBox = box;
        searchBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(34));
        blp.topMargin = dp(6);
        main.addView(box, blp);
        drawTabs();

        // The grid, in a box of its own: nothing scrolls over the header, and its top and bottom
        // edges melt away (a light blur where the handheld can, a soft shade otherwise)
        FrameLayout body = new FrameLayout(this);
        int screenW = getResources().getDisplayMetrics().widthPixels - dp(60);
        columns = Math.max(5, Math.min(9, screenW / dp(118)));
        tileSize = Math.round(Math.min(dp(98), screenW / (float) columns - dp(20)));
        grid = new RecyclerView(this);
        layout = new GridLayoutManager(this, columns);
        grid.setLayoutManager(layout);
        grid.setItemAnimator(null);
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        // Room for the focused tile's glow inside the box
        grid.setPadding(dp(16), dp(18), dp(16), dp(96));
        grid.setAdapter(tiles);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            grid.setDefaultFocusHighlightEnabled(false);
        }
        body.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        View topFade = new View(this);
        View bottomFade = new View(this);
        body.addView(topFade, new FrameLayout.LayoutParams(-1, dp(26), Gravity.TOP));
        body.addView(bottomFade, new FrameLayout.LayoutParams(-1, dp(120), Gravity.BOTTOM));
        View topShade = new View(this);
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x00000000}));
        body.addView(topShade, new FrameLayout.LayoutParams(-1, dp(22), Gravity.TOP));
        View bottomShade = new View(this);
        bottomShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x8C000000, 0xF2000000}));
        body.addView(bottomShade, new FrameLayout.LayoutParams(-1, dp(110), Gravity.BOTTOM));
        if (com.limelight.pocket.ui.LiveGlass.supported() && PocketPrefs.liveGlass(this)) {
            com.limelight.pocket.ui.LiveGlass.Backdrop blur = new com.limelight.pocket.ui.LiveGlass.Backdrop(grid, 12f, false);
            com.limelight.pocket.ui.LiveGlass.attach(topFade, blur, com.limelight.pocket.ui.LiveGlass.STYLE_FADE_UP);
            com.limelight.pocket.ui.LiveGlass.attach(bottomFade, blur, com.limelight.pocket.ui.LiveGlass.STYLE_FADE);
        }
        empty = new TextView(this);
        empty.setTypeface(PocketFonts.medium());
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        empty.setTextColor(0xB3FFFFFF);
        empty.setGravity(Gravity.CENTER);
        empty.setText(R.string.pocket_apps_loading);
        body.addView(empty, new FrameLayout.LayoutParams(dp(420), -2, Gravity.CENTER));
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        bodyLp.setMargins(-dp(16), dp(2), -dp(16), 0);
        main.addView(body, bodyLp);

        // The letter that L2 / R2 jumped to, big in the middle for a moment
        letterToast = new TextView(this);
        letterToast.setTypeface(PocketFonts.bold());
        letterToast.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f);
        letterToast.setTextColor(0xFFFFFFFF);
        letterToast.setGravity(Gravity.CENTER);
        GradientDrawable toastBg = new GradientDrawable();
        toastBg.setCornerRadius(dp(18));
        toastBg.setColor(0xD916161A);
        letterToast.setBackground(toastBg);
        letterToast.setAlpha(0f);
        letterToast.setElevation(dp(16));
        root.addView(letterToast, new FrameLayout.LayoutParams(dp(104), dp(104), Gravity.CENTER));

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

    /** A plain action in the bar (the order, search): words, no outline. */
    private TextView plainAction(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(PocketFonts.medium());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        t.setTextColor(0xD9FFFFFF);
        t.setShadowLayer(6 * d, 0, 0, 0x99000000);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private void drawTabs() {
        tabsView.removeAllViews();
        tabsView.addView(dimKey("L1"));
        for (int i = 0; i < TABS.length; i++) {
            final int which = i;
            String text = getString(TABS[i]);
            int n = countIn(i);
            if (n > 0) {
                text += "  " + n;
            }
            TextView t = com.limelight.pocket.ui.SectionBar.portalTab(this, text, i == tab, d);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            t.setPadding(dp(11), 0, dp(11), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            lp.gravity = Gravity.CENTER_VERTICAL;
            t.setLayoutParams(lp);
            t.setOnClickListener(v -> setTab(which));
            tabsView.addView(t);
        }
        tabsView.addView(dimKey("R1"));
    }

    /** The shoulder button that switches the categories: small faded words. */
    private TextView dimKey(String text) {
        TextView g = new TextView(this);
        g.setText(text);
        g.setTypeface(PocketFonts.bold());
        g.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        g.setTextColor(0x73FFFFFF);
        g.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER_VERTICAL;
        lp.setMargins(dp(2), 0, dp(2), 0);
        g.setLayoutParams(lp);
        return g;
    }

    private int countIn(int which) {
        int n = 0;
        for (App a : all) {
            if (inTab(a, which)) {
                n++;
            }
        }
        return n;
    }

    private static boolean inTab(App a, int which) {
        switch (which) {
            case TAB_GAMES:
                return a.type == AndroidAppsActivity.TYPE_GAME;
            case TAB_EMULATORS:
                return a.type == AndroidAppsActivity.TYPE_EMULATOR;
            case TAB_APPS:
                return a.type == AndroidAppsActivity.TYPE_APP;
            default:
                return true;
        }
    }

    // ---- The apps -------------------------------------------------------------------------------

    private void load() {
        final Set<String> library = AndroidLibrary.packages(this);
        work.execute(() -> {
            PackageManager pm = getPackageManager();
            List<AndroidLibrary.InstalledApp> found = AndroidLibrary.installed(this);
            final List<App> apps = new ArrayList<>();
            for (AndroidLibrary.InstalledApp a : found) {
                long installed = 0;
                try {
                    installed = pm.getPackageInfo(a.packageName, 0).firstInstallTime;
                } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                }
                long used = PocketPrefs.prefs(this).getLong(KEY_USED + a.packageName, 0);
                apps.add(new App(a.packageName, a.label, a.type, installed, used, library.contains(a.packageName)));
            }
            main.post(() -> {
                if (isFinishing()) {
                    return;
                }
                String keep = focusPos >= 0 && focusPos < shown.size() ? shown.get(focusPos).pkg : null;
                all.clear();
                all.addAll(apps);
                drawTabs();
                apply(true, keep);
            });
        });
    }

    private void apply(boolean refocus) {
        apply(refocus, null);
    }

    private void apply(boolean refocus, String keep) {
        shown.clear();
        String q = query.toLowerCase(Locale.getDefault());
        for (App a : all) {
            if (inTab(a, tab) && (q.isEmpty() || a.label.toLowerCase(Locale.getDefault()).contains(q)
                    || a.pkg.toLowerCase(Locale.ROOT).contains(q))) {
                shown.add(a);
            }
        }
        final Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY);
        switch (sort) {
            case SORT_RECENT:
                Collections.sort(shown, (a, b) -> a.used != b.used ? Long.compare(b.used, a.used) : collator.compare(a.label, b.label));
                break;
            case SORT_NEW:
                Collections.sort(shown, (a, b) -> a.installed != b.installed ? Long.compare(b.installed, a.installed)
                        : collator.compare(a.label, b.label));
                break;
            default:
                Collections.sort(shown, (a, b) -> collator.compare(a.label, b.label));
                break;
        }
        tiles.notifyDataSetChanged();
        count.setText(getResources().getQuantityString(R.plurals.pocket_drawer_count, shown.size(), shown.size()));
        sortView.setText("\u2261  " + getString(SORTS[sort]));
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        if (shown.isEmpty()) {
            empty.setText(all.isEmpty() ? R.string.pocket_apps_loading : R.string.pocket_drawer_nothing);
        }
        if (refocus && !shown.isEmpty() && (search == null || !search.hasFocus())) {
            int target = 0;
            for (int i = 0; keep != null && i < shown.size(); i++) {
                if (shown.get(i).pkg.equals(keep)) {
                    target = i;
                }
            }
            focusAt(target);
        }
    }

    private void focusAt(final int position) {
        if (position < 0 || position >= shown.size()) {
            return;
        }
        grid.scrollToPosition(position);
        grid.post(() -> focusAttempt(position, 0));
    }

    private void focusAttempt(int position, int attempt) {
        if (isFinishing()) {
            return;
        }
        RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(position);
        if (h != null && h.itemView.requestFocus()) {
            return;
        }
        if (attempt < 6) {
            grid.postDelayed(() -> focusAttempt(position, attempt + 1), attempt < 2 ? 16 : 100);
        }
    }

    // ---- Buttons --------------------------------------------------------------------------------

    private void setTab(int t) {
        if (t == tab) {
            return;
        }
        tab = t;
        PocketSounds.play(this, PocketSounds.NAV);
        drawTabs();
        apply(true);
    }

    /** L1 / R1: the category. */
    @Override
    protected boolean onShoulder(int direction) {
        setTab((tab + direction + TABS.length) % TABS.length);
        return true;
    }

    /** L2 / R2: to the previous / next letter (A to Z), else a page up / down. */
    @Override
    protected boolean onTrigger(int direction) {
        if (shown.isEmpty()) {
            return true;
        }
        int from = Math.max(0, Math.min(shown.size() - 1, focusPos));
        int target;
        if (sort == SORT_NAME) {
            String now = shown.get(from).letter;
            target = from;
            if (direction > 0) {
                while (target < shown.size() - 1 && shown.get(target).letter.equals(now)) {
                    target++;
                }
            }
            else {
                // To the first of this letter, or (already there) the first of the one before
                int start = from;
                while (start > 0 && shown.get(start - 1).letter.equals(now)) {
                    start--;
                }
                if (start < from) {
                    target = start;
                }
                else if (start > 0) {
                    String before = shown.get(start - 1).letter;
                    target = start - 1;
                    while (target > 0 && shown.get(target - 1).letter.equals(before)) {
                        target--;
                    }
                }
                else {
                    target = 0;
                }
            }
            showLetter(shown.get(target).letter);
        }
        else {
            target = Math.max(0, Math.min(shown.size() - 1, from + direction * columns * 3));
        }
        if (target != from) {
            PocketSounds.play(this, PocketSounds.NAV);
            focusAt(target);
        }
        return true;
    }

    private void showLetter(String letter) {
        letterToast.setText(letter);
        letterToast.animate().cancel();
        letterToast.setScaleX(0.86f);
        letterToast.setScaleY(0.86f);
        letterToast.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).withEndAction(() ->
                letterToast.animate().alpha(0f).setStartDelay(520).setDuration(260).start()).start();
    }

    /** SELECT: the order (A to Z, played lately, newest). */
    @Override
    protected void onSelectButton() {
        sort = (sort + 1) % SORTS.length;
        PocketPrefs.prefs(this).edit().putInt(KEY_SORT, sort).apply();
        PocketSounds.play(this, PocketSounds.NAV);
        PocketMotion.pop(sortView, 0.9f);
        apply(true);
    }

    /** X: search. */
    @Override
    protected boolean onButtonX() {
        if (searchBox.getVisibility() != View.VISIBLE) {
            searchBox.setVisibility(View.VISIBLE);
            searchBox.setAlpha(0f);
            searchBox.animate().alpha(1f).setDuration(160).start();
        }
        PocketSounds.play(this, PocketSounds.OPEN);
        search.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        }
        return true;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        }
    }

    private void closeSearch() {
        hideKeyboard();
        search.setText("");
        searchBox.setVisibility(View.GONE);
        query = "";
        apply(true);
    }

    @Override
    public void onBackPressed() {
        if (isConsolePanelShowing()) {
            super.onBackPressed();
            return;
        }
        if (searchBox.getVisibility() == View.VISIBLE) {
            closeSearch();
            return;
        }
        finish();
        overridePendingTransition(R.anim.pocket_section_in, R.anim.pocket_section_out);
    }

    private void launch(App a) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(a.pkg);
        if (intent == null) {
            Toast.makeText(this, R.string.pocket_apps_cant_open, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.used = System.currentTimeMillis();
            PocketPrefs.prefs(this).edit().putLong(KEY_USED + a.pkg, a.used).apply();
            PocketSounds.play(this, PocketSounds.SELECT);
            com.limelight.pocket.home.HomeStore.record(this, new HomeItem(HomeItem.ANDROID, a.label, a.pkg, "", "", 0));
            startActivity(intent);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.pocket_apps_cant_open, Toast.LENGTH_SHORT).show();
        }
    }

    private void options(final App a) {
        List<StreamMenuView.Item> items = new ArrayList<>();
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_apps_open), () -> {
            hideConsolePanel();
            launch(a);
        }));
        items.add(HomePins.item(this, new HomeItem(HomeItem.ANDROID, a.label, a.pkg, "", "", 0)));
        items.add(StreamMenuView.Item.action(getString(a.inLibrary ? R.string.pocket_drawer_remove_section
                : R.string.pocket_drawer_add_section), () -> {
            if (a.inLibrary) {
                AndroidLibrary.remove(this, a.pkg);
            }
            else {
                Set<String> now = AndroidLibrary.packages(this);
                now.add(a.pkg);
                AndroidLibrary.set(this, now);
            }
            a.inLibrary = !a.inLibrary;
            PocketSounds.play(this, a.inLibrary ? PocketSounds.TOGGLE_ON : PocketSounds.TOGGLE_OFF);
            hideConsolePanel();
            int at = shown.indexOf(a);
            if (at >= 0) {
                tiles.notifyItemChanged(at);
            }
        }));
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_art_change_icon), () -> {
            hideConsolePanel();
            // SteamGridDB: an icon, or a square picture for the whole tile (each by itself)
            com.limelight.pocket.ArtPickerActivity.open(this, AndroidLibrary.UUID, AndroidLibrary.idFor(a.pkg), a.label,
                    com.limelight.pocket.PocketArt.ICON);
        }));
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_apps_info), () -> {
            hideConsolePanel();
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
            } catch (RuntimeException ignored) {
            }
        }));
        items.add(StreamMenuView.Item.confirm(getString(R.string.pocket_apps_uninstall), () -> {
            hideConsolePanel();
            try {
                startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)));
            } catch (RuntimeException ignored) {
            }
        }));
        items.add(StreamMenuView.Item.header(a.pkg));
        PocketSounds.play(this, PocketSounds.OPEN);
        showConsolePanel(a.label, getString(typeLabel(a.type)), null, items);
    }

    private static int typeLabel(int type) {
        switch (type) {
            case AndroidAppsActivity.TYPE_GAME:
                return R.string.pocket_apps_type_game;
            case AndroidAppsActivity.TYPE_EMULATOR:
                return R.string.pocket_apps_type_emulator;
            default:
                return R.string.pocket_apps_type_app;
        }
    }

    // ---- The light behind ------------------------------------------------------------------------

    private void setGlowColors(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.15f);
        hsv[2] = Math.max(0.25f, Math.min(0.62f, hsv[2]));
        int rich = Color.HSVToColor(hsv);
        glowFace.setColors(new int[]{(rich & 0x00FFFFFF) | 0xB3000000, (rich & 0x00FFFFFF) | 0x40000000, 0x00000000});
    }

    private void glowTo(int color) {
        if (color == glowColor) {
            return;
        }
        if (glowAnim != null) {
            glowAnim.cancel();
        }
        if (!PocketPrefs.animationsEnabled(this)) {
            glowColor = color;
            setGlowColors(color);
            return;
        }
        glowAnim = ValueAnimator.ofArgb(glowColor, color);
        glowAnim.setDuration(420);
        glowAnim.addUpdateListener(an -> {
            glowColor = (int) an.getAnimatedValue();
            setGlowColors(glowColor);
        });
        glowAnim.start();
    }

    // ---- Tiles ------------------------------------------------------------------------------------

    private static final class Tile extends RecyclerView.ViewHolder {
        final FrameLayout frame;
        final ImageView art;
        final TextView name;
        final TextView badge;
        String pkg;
        String key;

        Tile(View v, FrameLayout frame, ImageView art, TextView name, TextView badge) {
            super(v);
            this.frame = frame;
            this.art = art;
            this.name = name;
            this.badge = badge;
        }
    }

    /**
     * The focus: a white line just outside the tile, its edge a little soft, with a glow of light
     * around it (no hard ring).
     */
    private static final class GlowRing extends Drawable {
        private final android.graphics.Paint line = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint halo = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();
        private final float out;
        private final float radius;

        GlowRing(float d, float tileRadius) {
            out = 4f * d;
            radius = tileRadius + out;
            line.setStyle(android.graphics.Paint.Style.STROKE);
            line.setStrokeWidth(2.4f * d);
            line.setColor(0xF2FFFFFF);
            halo.setStyle(android.graphics.Paint.Style.STROKE);
            halo.setStrokeWidth(5f * d);
            halo.setColor(0x73FFFFFF);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                line.setMaskFilter(new android.graphics.BlurMaskFilter(0.9f * d, android.graphics.BlurMaskFilter.Blur.NORMAL));
                halo.setMaskFilter(new android.graphics.BlurMaskFilter(9f * d, android.graphics.BlurMaskFilter.Blur.NORMAL));
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            r.set(getBounds());
            r.inset(-out, -out);
            canvas.drawRoundRect(r, radius, radius, halo);
            canvas.drawRoundRect(r, radius, radius, line);
        }

        @Override
        public void setAlpha(int alpha) {
            line.setAlpha(Math.round(0xF2 * alpha / 255f));
            halo.setAlpha(Math.round(0x73 * alpha / 255f));
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private final class Tiles extends RecyclerView.Adapter<Tile> {
        @NonNull
        @Override
        public Tile onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = AppDrawerActivity.this;
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setFocusable(true);
            cell.setClickable(true);
            cell.setClipChildren(false);
            cell.setClipToPadding(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                cell.setDefaultFocusHighlightEnabled(false);
            }
            cell.setPadding(0, dp(6), 0, dp(4));
            cell.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));

            final float radius = dp(14);
            // The frame carries the focus glow (outside the tile); the tile inside is clipped round
            final FrameLayout frame = new FrameLayout(c);
            frame.setClipChildren(false);
            FrameLayout card = new FrameLayout(c);
            GradientDrawable face = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF2A2A31, 0xFF141418});
            face.setCornerRadius(radius);
            card.setBackground(face);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);
            card.setElevation(dp(3));
            ImageView art = new ImageView(c);
            art.setScaleType(ImageView.ScaleType.FIT_XY);
            card.addView(art, new FrameLayout.LayoutParams(-1, -1));
            // A fine light along the top edge (glass)
            View sheen = new View(c);
            sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x26FFFFFF, 0x00FFFFFF}));
            card.addView(sheen, new FrameLayout.LayoutParams(-1, Math.round(tileSize * 0.4f)));
            TextView badge = new TextView(c);
            badge.setText(R.string.pocket_drawer_new);
            badge.setTypeface(PocketFonts.bold());
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
            badge.setLetterSpacing(0.08f);
            badge.setTextColor(0xFF0B0B0D);
            badge.setIncludeFontPadding(false);
            badge.setPadding(dp(5), dp(3), dp(5), dp(3));
            GradientDrawable tag = new GradientDrawable();
            tag.setCornerRadius(dp(4));
            tag.setColor(0xFFFFFFFF);
            badge.setBackground(tag);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            blp.setMargins(dp(7), dp(7), 0, 0);
            card.addView(badge, blp);
            frame.addView(card, new FrameLayout.LayoutParams(-1, -1));
            final Drawable ring = new GlowRing(d, radius);
            cell.addView(frame, new LinearLayout.LayoutParams(tileSize, tileSize));

            TextView name = new TextView(c);
            name.setTypeface(PocketFonts.medium());
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            name.setTextColor(0xFFFFFFFF);
            name.setAlpha(0.7f);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            name.setShadowLayer(6 * d, 0, 0, 0x99000000);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(tileSize + dp(16), -2);
            nlp.topMargin = dp(9);
            cell.addView(name, nlp);

            final Tile tile = new Tile(cell, frame, art, name, badge);
            cell.setOnFocusChangeListener((v, has) -> {
                float sc = has ? 1.08f : 1f;
                if (PocketPrefs.animationsEnabled(AppDrawerActivity.this)) {
                    frame.animate().scaleX(sc).scaleY(sc).translationZ(has ? dp(10) : 0).setDuration(180)
                            .setInterpolator(PocketMotion.DECELERATE).start();
                    tile.name.animate().alpha(has ? 1f : 0.7f).translationY(has ? dp(4) : 0).setDuration(180).start();
                }
                else {
                    frame.setScaleX(sc);
                    frame.setScaleY(sc);
                    tile.name.setAlpha(has ? 1f : 0.7f);
                }
                frame.setForeground(has ? ring : null);
                tile.name.setTypeface(has ? PocketFonts.semibold() : PocketFonts.medium());
                if (has) {
                    focusPos = tile.getBindingAdapterPosition();
                    PocketUi.tick(AppDrawerActivity.this);
                    Integer col = tile.pkg != null ? COLORS.get(tile.pkg) : null;
                    if (col != null) {
                        glowTo(col);
                    }
                }
            });
            cell.setOnClickListener(v -> {
                int pos = tile.getBindingAdapterPosition();
                if (pos >= 0 && pos < shown.size()) {
                    PocketMotion.pop(frame, 0.94f);
                    launch(shown.get(pos));
                }
            });
            cell.setOnLongClickListener(v -> {
                int pos = tile.getBindingAdapterPosition();
                if (pos >= 0 && pos < shown.size()) {
                    options(shown.get(pos));
                }
                return true;
            });
            cell.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        int pos = tile.getBindingAdapterPosition();
                        if (pos >= 0 && pos < shown.size()) {
                            options(shown.get(pos));
                        }
                    }
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP
                        && tile.getBindingAdapterPosition() < columns) {
                    // The top row: up goes to the search field when it's open
                    if (searchBox.getVisibility() == View.VISIBLE) {
                        search.requestFocus();
                    }
                    return true;
                }
                return false;
            });
            return tile;
        }

        @Override
        public void onBindViewHolder(@NonNull final Tile h, int position) {
            final App a = shown.get(position);
            final String key = a.pkg + "|" + com.limelight.pocket.PocketArt.version();
            h.pkg = a.pkg;
            h.key = key;
            h.name.setText(a.label);
            h.itemView.setContentDescription(a.label);
            h.badge.setVisibility(a.installed > 0 && System.currentTimeMillis() - a.installed < NEW_FOR_MS
                    ? View.VISIBLE : View.GONE);
            Bitmap ready = ICONS.get(key);
            if (ready != null) {
                h.art.setImageBitmap(ready);
                return;
            }
            h.art.setImageDrawable(null);
            final String pkg = a.pkg;
            final int size = tileSize;
            iconWork.execute(() -> {
                Bitmap b = render(pkg, size);
                if (b == null) {
                    return;
                }
                int c = average(b);
                ICONS.put(key, b);
                COLORS.put(pkg, c);
                main.post(() -> {
                    if (key.equals(h.key)) {
                        h.art.setImageBitmap(b);
                        if (PocketPrefs.animationsEnabled(AppDrawerActivity.this)) {
                            h.art.setAlpha(0f);
                            h.art.animate().alpha(1f).setDuration(160).start();
                        }
                        if (h.itemView.hasFocus()) {
                            glowTo(c);
                        }
                    }
                });
            });
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }

    /**
     * The whole tile for an app (size x size), like a console's: a square picture picked on
     * SteamGridDB fills it; else the app's own icon, full bleed (its background is the tile, its
     * mark in the middle; no circle); an older (flat) icon, or an icon picked on SteamGridDB, sits
     * on a tile in its own colours.
     */
    private Bitmap render(String pkg, int size) {
        try {
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            int id = AndroidLibrary.idFor(pkg);
            Bitmap square = decode(com.limelight.pocket.PocketArt.file(this, AndroidLibrary.UUID, id,
                    com.limelight.pocket.PocketArt.SQUARE), size);
            if (square != null) {
                float sc = Math.max(size / (float) square.getWidth(), size / (float) square.getHeight());
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setScale(sc, sc);
                m.postTranslate((size - square.getWidth() * sc) / 2f, (size - square.getHeight() * sc) / 2f);
                c.drawBitmap(square, m, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
                return out;
            }
            Bitmap pickedIcon = decode(com.limelight.pocket.PocketArt.file(this, AndroidLibrary.UUID, id,
                    com.limelight.pocket.PocketArt.ICON), size);
            Drawable icon = pickedIcon == null ? getPackageManager().getApplicationIcon(pkg) : null;
            if (icon != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && icon instanceof android.graphics.drawable.AdaptiveIconDrawable) {
                android.graphics.drawable.AdaptiveIconDrawable adaptive = (android.graphics.drawable.AdaptiveIconDrawable) icon;
                // Its layers are drawn a quarter bigger on each side: the part a launcher shows
                // in its circle fills the whole tile instead
                int grow = Math.round(size * 0.25f);
                Drawable back = adaptive.getBackground();
                Drawable front = adaptive.getForeground();
                if (back != null) {
                    back.setBounds(-grow, -grow, size + grow, size + grow);
                    back.draw(c);
                }
                else {
                    c.drawColor(0xFF2A2A31);
                }
                if (front != null) {
                    front.setBounds(-grow, -grow, size + grow, size + grow);
                    front.draw(c);
                }
                return out;
            }
            Bitmap flat = pickedIcon;
            if (flat == null && icon != null) {
                int px = Math.round(size * 0.62f);
                flat = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
                icon.setBounds(0, 0, px, px);
                icon.draw(new Canvas(flat));
            }
            if (flat == null) {
                return null;
            }
            int base = average(flat);
            float[] hsv = new float[3];
            Color.colorToHSV(base, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.1f);
            hsv[2] = Math.max(0.2f, Math.min(0.46f, hsv[2] * 0.6f));
            int deep = Color.HSVToColor(hsv);
            hsv[2] = Math.min(0.7f, hsv[2] * 1.65f);
            int light = Color.HSVToColor(hsv);
            android.graphics.Paint bg = new android.graphics.Paint();
            bg.setShader(new android.graphics.LinearGradient(0, 0, size * 0.6f, size, light, deep,
                    android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0, 0, size, size, bg);
            float s = size * 0.62f;
            float l = (size - s) / 2f;
            c.drawBitmap(flat, null, new android.graphics.RectF(l, l, l + s, l + s),
                    new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.ANTI_ALIAS_FLAG));
            return out;
        } catch (PackageManager.NameNotFoundException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap decode(java.io.File f, int target) {
        if (f == null || !f.exists()) {
            return null;
        }
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= target) {
            sample *= 2;
        }
        o = new android.graphics.BitmapFactory.Options();
        o.inSampleSize = sample;
        return android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** The picture's main colour (its opaque pixels, the most colourful counting more). */
    private static int average(Bitmap b) {
        Bitmap small = Bitmap.createScaledBitmap(b, 12, 12, true);
        float r = 0, g = 0, bl = 0, n = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 12; x++) {
                int p = small.getPixel(x, y);
                if (Color.alpha(p) < 160) {
                    continue;
                }
                Color.colorToHSV(p, hsv);
                float w = 0.25f + hsv[1] * hsv[2];
                r += Color.red(p) * w;
                g += Color.green(p) * w;
                bl += Color.blue(p) * w;
                n += w;
            }
        }
        if (n <= 0) {
            return 0xFF2A2A31;
        }
        return Color.rgb(Math.round(r / n), Math.round(g / n), Math.round(bl / n));
    }
}
