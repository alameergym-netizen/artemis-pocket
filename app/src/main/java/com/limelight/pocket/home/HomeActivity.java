package com.limelight.pocket.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.text.format.DateUtils;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.pocket.ArtPickerActivity;
import com.limelight.pocket.ArtScraper;
import com.limelight.pocket.ConsoleActivity;
import com.limelight.pocket.GamePageActivity;
import com.limelight.pocket.PocketArt;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.PocketSounds;
import com.limelight.pocket.RecentGames;
import com.limelight.pocket.apps.AndroidLibrary;
import com.limelight.pocket.apps.HomeScreen;
import com.limelight.pocket.cloud.CloudService;
import com.limelight.pocket.info.GameInfo;
import com.limelight.pocket.info.GameInfoService;
import com.limelight.pocket.pclib.PcGame;
import com.limelight.pocket.pclib.PcLaunchTicket;
import com.limelight.pocket.pclib.PcLibrary;
import com.limelight.pocket.pclib.PcLibraryActivity;
import com.limelight.pocket.pclib.SteamAccount;
import com.limelight.pocket.retro.RetroActivity;
import com.limelight.pocket.retro.RomScanner;
import com.limelight.pocket.ui.AnchoredLayoutManager;
import com.limelight.pocket.ui.HeroArt;
import com.limelight.pocket.ui.KeyArtStage;
import com.limelight.pocket.ui.PlaceholderArt;
import com.limelight.pocket.ui.PocketFonts;
import com.limelight.pocket.ui.PocketMotion;
import com.limelight.pocket.ui.PocketUi;
import com.limelight.pocket.ui.SectionBar;
import com.limelight.pocket.ui.Spring;
import com.limelight.pocket.ui.StreamMenuView;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.ServerHelper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home, like a console's: only what you put on it (a streamed game, a PC's library game, a cloud
 * service, a retro game, an Android app, a GameNative / GameHub game, another app's shortcut),
 * in your order, as a row of tiles. The focused one fills the screen with its art, its name,
 * where it comes from and Play. Streamed games start right from here: Home keeps an eye on the
 * PCs itself (it wakes a sleeping PC, and asks before quitting a game that's running).
 */
public class HomeActivity extends ConsoleActivity {

    private static final float TILE_DP = 72f;
    private static final float FOCUS_SCALE = 1.1f;
    private static final long CHECK_TIMEOUT_MS = 12000;
    private static final long WAKE_TIMEOUT_MS = 75000;

    private RecyclerView row;
    private final Tiles tiles = new Tiles();
    private final List<HomeStore.Entry> entries = new ArrayList<>();
    private KeyArtStage keyArt;
    private TextView source;
    private TextView title;
    private TextView desc;
    private TextView play;
    private TextView rowLabel;
    private ImageView logo;
    private ImageView info;
    private ImageView more;
    private int heroPos = -1;
    private String heroKey;
    private boolean loadedOnce;
    private int artVersion = PocketArt.version();
    private float d;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> TILE_ART = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private String pcGamesUuid;

    // ---- The PCs (Home starts their games itself) -----------------------------------------------

    private ComputerManagerService.ComputerManagerBinder binder;
    private boolean bound;
    private boolean resumed;
    private final Map<String, ComputerDetails> livePcs = new HashMap<>();
    /** A game waiting for its PC: being checked, or waking up. */
    private HomeItem pending;
    private boolean pendingWake;
    private ComputerDetails wakingPc;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            final ComputerManagerService.ComputerManagerBinder b = (ComputerManagerService.ComputerManagerBinder) service;
            new Thread(() -> {
                b.waitForReady();
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    binder = b;
                    if (resumed) {
                        startPolling();
                    }
                });
            }, "PocketHomeBind").start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
        }
    };

    private final ComputerManagerListener pcListener = details -> main.post(() -> onPcUpdated(details));

    // ---- Life --------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        d = getResources().getDisplayMetrics().density;
        SectionBar.attach(this, SectionBar.HOME);
        row = findViewById(R.id.homeRow);
        source = findViewById(R.id.homeSource);
        title = findViewById(R.id.pocketHeroTitle);
        desc = findViewById(R.id.homeDesc);
        play = findViewById(R.id.pocketHeroPlay);
        rowLabel = findViewById(R.id.homeRowLabel);
        logo = findViewById(R.id.pocketHeroLogo);
        info = findViewById(R.id.homeInfo);
        more = findViewById(R.id.homeMore);
        keyArt = KeyArtStage.find(this);

        row.setLayoutManager(new AnchoredLayoutManager(this));
        row.setItemAnimator(null);
        row.setAdapter(tiles);
        setUpActions();
        setHints(hintA(this, R.string.pocket_hero_play),
                hintX(this, R.string.pocket_hint_details),
                hintY(this, R.string.pocket_hint_options),
                hintShoulders(this, R.string.pocket_retro_hint_sections));
        title.setText(R.string.pocket_home_welcome);
        source.setText("");
        bound = bindService(new Intent(this, ComputerManagerService.class), connection, Context.BIND_AUTO_CREATE);
        // A button pressed after the screen was touched (or right after it opened): the focus
        // goes to the game, never to a stray view
        row.getViewTreeObserver().addOnTouchModeChangeListener(inTouch -> {
            if (!inTouch && !isFinishing() && !isConsolePanelShowing()) {
                View f = getCurrentFocus();
                boolean inside = f != null && (row.hasFocus() || f == play || f == info || f == more);
                if (!inside && !entries.isEmpty()) {
                    focusAt(heroPos >= 0 && heroPos < entries.size() ? heroPos : 0, 0);
                }
            }
        });
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (binder != null) {
            startPolling();
        }
        if (!loadedOnce) {
            return;
        }
        if (artVersion != PocketArt.version()) {
            artVersion = PocketArt.version();
            TILE_ART.evictAll();
        }
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        stopPolling();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelPending();
        work.shutdownNow();
        if (bound) {
            try {
                unbindService(connection);
            } catch (RuntimeException ignored) {
            }
            bound = false;
        }
    }

    private void startPolling() {
        if (binder != null) {
            try {
                binder.startPolling(pcListener);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void stopPolling() {
        if (binder != null) {
            try {
                binder.stopPolling();
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ---- Loading -------------------------------------------------------------------------------

    private void load() {
        work.execute(() -> {
            final List<HomeStore.Entry> list = HomeStore.load(this);
            main.post(() -> {
                if (!isFinishing()) {
                    show(list);
                }
            });
        });
    }

    private void show(List<HomeStore.Entry> list) {
        loadedOnce = true;
        String keep = heroKey;
        entries.clear();
        entries.addAll(list);
        entries.add(null); // "Add"
        tiles.notifyDataSetChanged();
        rowLabel.setText(list.isEmpty() ? getString(R.string.pocket_home_row)
                : getString(R.string.pocket_home_row) + "  ·  " + list.size());
        int target = list.isEmpty() ? 0 : -1;
        for (int i = 0; keep != null && i < entries.size(); i++) {
            HomeStore.Entry e = entries.get(i);
            if ((e == null && "+".equals(keep)) || (e != null && e.item.key().equals(keep))) {
                target = i;
            }
        }
        if (target < 0) {
            target = Math.max(0, Math.min(heroPos, entries.size() - 1));
        }
        heroKey = null;
        View focus = getCurrentFocus();
        boolean onActions = focus != null && (focus == play || focus == info || focus == more);
        if (onActions) {
            updateHero(target);
        }
        else {
            focusAt(target, 0);
        }
    }

    private void focusAt(final int position, final int attempt) {
        if (attempt == 0) {
            row.scrollToPosition(position);
        }
        row.post(() -> {
            if (isFinishing() || position >= entries.size() || isConsolePanelShowing()) {
                return;
            }
            RecyclerView.ViewHolder holder = row.findViewHolderForAdapterPosition(position);
            if (holder != null && holder.itemView.requestFocus()) {
                return;
            }
            if (heroPos != position || heroKey == null) {
                updateHero(position);
            }
            if (attempt < 6) {
                row.postDelayed(() -> focusAt(position, attempt + 1), attempt < 2 ? 16 : 120);
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        View focus = getCurrentFocus();
        boolean inside = focus != null && (row.hasFocus() || focus == play || focus == info || focus == more);
        if (hasFocus && !inside && !entries.isEmpty() && !isConsolePanelShowing()) {
            focusAt(heroPos >= 0 && heroPos < entries.size() ? heroPos : 0, 0);
        }
    }

    private HomeStore.Entry entryAt(int position) {
        return position >= 0 && position < entries.size() ? entries.get(position) : null;
    }

    // ---- Per kind ------------------------------------------------------------------------------

    /** Where its art is kept (uuid, id), or null for drawn cards. */
    static String artUuid(HomeItem i) {
        switch (i.kind) {
            case HomeItem.PC:
            case HomeItem.PC_LIBRARY_GAME:
                return i.a;
            case HomeItem.RETRO:
                return com.limelight.pocket.retro.RetroLibrary.UUID;
            case HomeItem.ANDROID:
                return AndroidLibrary.UUID;
            case HomeItem.SHORTCUT:
                return "shortcut";
            case HomeItem.NATIVE:
                return "native";
            default:
                return null;
        }
    }

    static int artId(HomeItem i) {
        switch (i.kind) {
            case HomeItem.PC:
            case HomeItem.PC_LIBRARY_GAME:
            case HomeItem.NATIVE:
                return i.n;
            case HomeItem.RETRO:
                return RomScanner.idFor(i.a, i.b);
            case HomeItem.ANDROID:
                return AndroidLibrary.idFor(i.a);
            default: {
                int h = i.key().hashCode() & 0x3FFFFFFF;
                return h == 0 ? 1 : h;
            }
        }
    }

    private String displayName(HomeItem i) {
        return PocketPrefs.customName(this, artUuid(i) != null ? artUuid(i) : i.kind, artId(i), i.name);
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Where it comes from, in a few words ("Streaming · DESKTOP", "Android", "Retro · SNES"...). */
    private String where(HomeStore.Entry e) {
        HomeItem i = e.item;
        switch (i.kind) {
            case HomeItem.PC:
                return getString(R.string.pocket_section_streaming) + (e.pcName.isEmpty() ? "" : "  ·  " + e.pcName);
            case HomeItem.PC_LIBRARY_GAME:
                return getString(R.string.pocket_pclib_title_short) + (i.c.isEmpty() ? "" : "  ·  " + i.c);
            case HomeItem.PC_GAMES:
                return getString(R.string.pocket_home_pc_games_sub);
            case HomeItem.CLOUD:
                return getString(R.string.pocket_section_cloud);
            case HomeItem.RETRO:
                return getString(R.string.pocket_section_retro) + "  ·  " + RetroActivity.shortName(i.a, i.a);
            case HomeItem.ANDROID:
                return getString(R.string.pocket_section_android);
            case HomeItem.NATIVE:
                return (HomeLauncher.GAMEHUB_LITE.equals(i.a) ? "GameHub Lite" : "GameNative") + "  ·  "
                        + (i.b.isEmpty() ? "Steam" : i.b.substring(0, 1) + i.b.substring(1).toLowerCase(Locale.ROOT));
            default:
                return getString(R.string.pocket_home_shortcut) + "  ·  " + appLabel(i.a);
        }
    }

    // ---- The hero ------------------------------------------------------------------------------

    private void updateHero(int position) {
        HomeStore.Entry e = entryAt(position);
        int dir = heroPos < 0 ? 0 : Integer.signum(position - heroPos);
        if (title.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            dir = -dir;
        }
        String key = e != null ? e.item.key() : "+";
        boolean changed = !key.equals(heroKey);
        heroPos = position;
        heroKey = key;
        if (e == null) {
            boolean empty = entries.size() <= 1;
            source.setCompoundDrawablesRelative(null, null, null, null);
            source.setText(empty ? getString(R.string.pocket_home_welcome) : "");
            if (changed) {
                PocketMotion.swapText(title, getString(empty ? R.string.pocket_home_empty : R.string.pocket_home_add), dir);
            }
            title.setVisibility(View.VISIBLE);
            logo.setVisibility(View.GONE);
            setDesc(getString(empty ? R.string.pocket_home_empty_sub : R.string.pocket_home_add_sub));
            play.setText(R.string.pocket_home_add_short);
            setPlayIcon(true);
            info.setVisibility(View.GONE);
            more.setVisibility(View.GONE);
            if (keyArt != null) {
                keyArt.show(null);
            }
            return;
        }
        HomeItem item = e.item;
        String name = displayName(item);
        KindBadge badge = new KindBadge(item.kind, KindBadge.SQUARE, d);
        int bs = Math.round(15 * d);
        badge.setBounds(0, 0, bs, bs);
        source.setCompoundDrawablesRelative(badge, null, null, null);
        String line = where(e);
        if (e.lastUsed > 0) {
            line += "  ·  " + DateUtils.getRelativeTimeSpanString(e.lastUsed, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
        }
        source.setText(line);
        if (changed) {
            title.setVisibility(View.VISIBLE);
            logo.setVisibility(View.GONE);
            PocketMotion.swapText(title, name, dir);
            setDesc(null);
            showArt(e);
            loadDescription(item, name);
        }
        play.setText(playLabel(item));
        setPlayIcon(false);
        info.setVisibility(artUuid(item) != null ? View.VISIBLE : View.GONE);
        more.setVisibility(View.VISIBLE);
    }

    private Boolean playIconIsAdd;

    /** Play's icon: ▶, or + on the Add tile. */
    private void setPlayIcon(boolean add) {
        if (playIconIsAdd != null && playIconIsAdd == add) {
            return;
        }
        playIconIsAdd = add;
        Drawable icon = getDrawable(add ? R.drawable.ic_add : R.drawable.ic_pocket_play);
        if (icon != null) {
            icon = icon.mutate();
            if (add) {
                icon.setTint(0xFF0B0B0D);
            }
            int size = Math.round((add ? 14 : 11) * d);
            icon.setBounds(0, 0, size, size);
        }
        play.setCompoundDrawablesRelative(icon, null, null, null);
    }

    private int playLabel(HomeItem item) {
        if (HomeItem.PC_GAMES.equals(item.kind)) {
            return R.string.pocket_apps_open;
        }
        if (HomeItem.PC.equals(item.kind)) {
            ComputerDetails pc = livePcs.get(item.a);
            if (pc != null && pc.state == ComputerDetails.State.ONLINE && pc.runningGameId != 0
                    && pc.runningGameId == item.n) {
                return R.string.pocket_hero_resume;
            }
        }
        return R.string.pocket_hero_play;
    }

    private void setDesc(String text) {
        if (text == null || text.trim().isEmpty()) {
            desc.setVisibility(View.GONE);
            return;
        }
        // Two lines under a one-line name, one under a long name or a logo (the row stays clear)
        boolean tall = title.getVisibility() == View.VISIBLE && title.getLineCount() > 1;
        desc.setMaxLines(tall ? 1 : 2);
        desc.setText(text.trim());
        if (desc.getVisibility() != View.VISIBLE) {
            desc.setVisibility(View.VISIBLE);
            if (PocketPrefs.animationsEnabled(this)) {
                desc.setAlpha(0f);
                desc.animate().alpha(1f).setDuration(220).start();
            }
        }
    }

    /** A line about the game, when one is known (looked up once you rest on it). */
    private void loadDescription(final HomeItem item, final String name) {
        if (artUuid(item) == null || HomeItem.SHORTCUT.equals(item.kind)) {
            return;
        }
        final String search = RomScanner.searchName(name);
        GameInfo known = GameInfoService.cached(search);
        if (known != null) {
            setDesc(summary(known));
            return;
        }
        final String key = item.key();
        main.postDelayed(() -> {
            if (!key.equals(heroKey) || isFinishing()) {
                return;
            }
            GameInfoService.get(this, search, false, found -> {
                if (found != null && key.equals(heroKey) && !isFinishing()) {
                    setDesc(summary(found));
                }
            });
        }, 450);
    }

    private static String summary(GameInfo info) {
        String s = info.shortDescription != null && !info.shortDescription.isEmpty() ? info.shortDescription : info.about;
        if (s == null) {
            return null;
        }
        s = s.replace('\n', ' ').trim();
        return s.length() > 220 ? s.substring(0, 220) : s;
    }

    private void showArt(final HomeStore.Entry e) {
        final String uuid = artUuid(e.item);
        final String key = e.item.key();
        if (uuid == null) {
            if (keyArt != null) {
                keyArt.show(null);
            }
            return;
        }
        final int id = artId(e.item);
        PocketArt.load(this, uuid, id, PocketArt.HERO, bitmap -> {
            if (key.equals(heroKey) && keyArt != null) {
                keyArt.show(bitmap);
            }
        });
        PocketArt.load(this, uuid, id, PocketArt.LOGO, bitmap -> {
            if (key.equals(heroKey) && bitmap != null) {
                logo.setImageBitmap(bitmap);
                logo.setVisibility(View.VISIBLE);
                title.setVisibility(View.GONE);
            }
        });
        // Art for what has none yet
        if (!PocketArt.has(this, uuid, id, PocketArt.HERO)) {
            ArtScraper.Item item = new ArtScraper.Item(uuid, id, RomScanner.searchName(displayName(e.item)));
            if (HomeItem.NATIVE.equals(e.item.kind)) {
                item.steamAppId = e.item.n;
            }
            else if (HomeItem.ANDROID.equals(e.item.kind)) {
                item.playPackage = e.item.a;
            }
            ArtScraper.scrape(this, item, false, changed -> {
                if (changed && !isFinishing()) {
                    refreshArt(key);
                }
            });
        }
    }

    private void refreshArt(String key) {
        TILE_ART.remove(key);
        tiles.notifyDataSetChanged();
        if (key.equals(heroKey)) {
            heroKey = null;
            updateHero(heroPos);
        }
    }

    // ---- Play, Details, More -------------------------------------------------------------------

    private void setUpActions() {
        PocketUi.stylePlayPill(play);
        play.setFocusable(true);
        // Focused, Play wears a white ring a little apart from it
        Drawable face = play.getBackground();
        if (face instanceof GradientDrawable && face.getConstantState() != null) {
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(100 * d);
            ring.setColor(0x00000000);
            ring.setStroke(Math.round(2 * d), 0xFFFFFFFF);
            LayerDrawable focused = new LayerDrawable(new Drawable[]{face.getConstantState().newDrawable().mutate(), ring});
            int out = -Math.round(4.5f * d);
            focused.setLayerInset(1, out, out, out, out);
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_focused}, focused);
            states.addState(new int[]{}, face);
            play.setBackground(states);
        }
        setPlayIcon(false);
        play.setOnClickListener(v -> {
            PocketSounds.play(this, PocketSounds.SELECT);
            activate(heroPos);
        });
        info.setOnClickListener(v -> onButtonX());
        more.setOnClickListener(v -> showOptions(heroPos));
        View.OnFocusChangeListener grow = (v, has) -> {
            if (has) {
                PocketUi.tick(this);
            }
            float s = has ? 1.06f : 1f;
            if (PocketPrefs.animationsEnabled(this)) {
                v.animate().scaleX(s).scaleY(s).setDuration(Spring.UI_DURATION_MS).setInterpolator(Spring.UI_CURVE).start();
            }
            else {
                v.setScaleX(s);
                v.setScaleY(s);
            }
        };
        View.OnKeyListener down = (v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_Y || keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    showOptions(heroPos);
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.getAction() == KeyEvent.ACTION_DOWN) {
                focusAt(Math.max(0, heroPos), 0);
                return true;
            }
            if ((keyCode == KeyEvent.KEYCODE_DPAD_UP) && event.getAction() == KeyEvent.ACTION_DOWN) {
                return true; // nothing above the buttons but the tabs (L1 / R1)
            }
            return false;
        };
        for (View v : new View[]{play, info, more}) {
            v.setOnFocusChangeListener(grow);
            v.setOnKeyListener(down);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.setDefaultFocusHighlightEnabled(false);
            }
        }
    }

    // ---- Doing things --------------------------------------------------------------------------

    private void activate(int position) {
        if (position < 0 || position >= entries.size()) {
            return;
        }
        HomeStore.Entry e = entryAt(position);
        if (e == null) {
            showAddPanel();
            return;
        }
        HomeItem item = e.item;
        switch (item.kind) {
            case HomeItem.PC_GAMES:
                openPcGames(item.a, item.b, item.c);
                return;
            case HomeItem.PC:
            case HomeItem.PC_LIBRARY_GAME:
                launchOnPc(item, true);
                return;
            default:
                HomeLauncher.launch(this, item);
        }
    }

    private void openPcGames(String uuid, String host, String pcName) {
        ComputerDetails pc = livePcs.get(uuid);
        if (pc != null && pc.state == ComputerDetails.State.ONLINE) {
            host = hostOf(this, pc);
        }
        if (host == null || host.isEmpty()) {
            Toast.makeText(this, R.string.pair_pc_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        pcGamesUuid = uuid;
        PocketSounds.play(this, PocketSounds.OPEN);
        PcLibraryActivity.open(this, uuid, pcName, host);
    }

    @Override
    protected boolean onButtonX() {
        HomeStore.Entry e = entryAt(heroPos);
        if (e == null || artUuid(e.item) == null) {
            return false;
        }
        openPage(e);
        return true;
    }

    @Override
    protected boolean onGamePageShortcut() {
        return onButtonX();
    }

    private void openPage(HomeStore.Entry e) {
        View card = null;
        Bitmap cover = null;
        RecyclerView.ViewHolder holder = row.findViewHolderForAdapterPosition(heroPos);
        if (holder != null) {
            ImageView image = holder.itemView.findViewById(R.id.grid_image);
            if (image != null && image.getDrawable() instanceof BitmapDrawable) {
                cover = ((BitmapDrawable) image.getDrawable()).getBitmap();
                card = image;
            }
        }
        GamePageActivity.open(this, card, artUuid(e.item), artId(e.item), RomScanner.searchName(displayName(e.item)),
                where(e), false, false, cover);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PcLibraryActivity.REQUEST) {
            String uuid = pcGamesUuid;
            pcGamesUuid = null;
            if (resultCode == RESULT_OK && data != null && uuid != null) {
                int appId = data.getIntExtra(PcLibraryActivity.RESULT_APP_ID, 0);
                if (appId != 0) {
                    // The PC library chose what to stream (a game, or Desktop with the game to start)
                    launchOnPc(new HomeItem(HomeItem.PC, "", uuid, "", "", appId), false);
                }
            }
            return;
        }
        if (requestCode == GamePageActivity.REQUEST && data != null
                && GamePageActivity.ACTION_PLAY.equals(data.getStringExtra(GamePageActivity.RESULT_ACTION))) {
            activate(heroPos);
        }
    }

    // ---- Starting a game on a PC ---------------------------------------------------------------

    /**
     * Starts a streamed game: right away when its PC is known to be on, after a check when it
     * isn't known yet, after waking it (Wake-on-LAN) when it's asleep.
     */
    private void launchOnPc(HomeItem item, boolean record) {
        if (pending != null) {
            return; // already on its way
        }
        PocketSounds.play(this, PocketSounds.SELECT);
        ComputerDetails pc = binder != null ? binder.getComputer(item.a) : null;
        if (pc == null) {
            pc = livePcs.get(item.a);
        }
        if (record) {
            HomeStore.record(this, item);
        }
        if (pc != null && pc.state == ComputerDetails.State.ONLINE) {
            startOn(pc, item);
            return;
        }
        pending = item;
        pendingWake = false;
        if (pc != null && pc.state == ComputerDetails.State.OFFLINE) {
            wakeOrGiveUp(pc);
            return;
        }
        // Not known yet (Home just opened): one quick check
        String pcName = pc != null ? pc.name : item.c;
        showBlockingCard(getString(R.string.pocket_home_connecting), pcName,
                getString(R.string.pocket_home_connecting_sub), CHECK_TIMEOUT_MS, this::cancelPending);
        main.postDelayed(checkTimeout, CHECK_TIMEOUT_MS);
        if (binder != null) {
            binder.invalidateStateForComputer(item.a);
        }
    }

    private final Runnable checkTimeout = () -> {
        if (pending == null || pendingWake) {
            return;
        }
        ComputerDetails pc = binder != null ? binder.getComputer(pending.a) : livePcs.get(pending.a);
        if (pc != null) {
            wakeOrGiveUp(pc);
        }
        else {
            cancelPending();
            hideBlockingCard();
            Toast.makeText(this, R.string.pair_pc_offline, Toast.LENGTH_LONG).show();
        }
    };

    private void wakeOrGiveUp(ComputerDetails pc) {
        main.removeCallbacks(checkTimeout);
        if (pc.macAddress != null && pc.pairState == PairingManager.PairState.PAIRED) {
            pendingWake = true;
            wakingPc = pc;
            main.removeCallbacks(wakeResend);
            main.post(wakeResend);
            main.removeCallbacks(wakeTimeout);
            main.postDelayed(wakeTimeout, WAKE_TIMEOUT_MS);
            showBlockingCard(getString(R.string.pocket_wake_caption), pc.name, getString(R.string.pocket_wake_sub),
                    WAKE_TIMEOUT_MS, this::cancelPending);
            return;
        }
        cancelPending();
        hideBlockingCard();
        Toast.makeText(this, R.string.pair_pc_offline, Toast.LENGTH_LONG).show();
    }

    private final Runnable wakeResend = new Runnable() {
        @Override
        public void run() {
            final ComputerDetails pc = wakingPc;
            if (pc == null || !pendingWake) {
                return;
            }
            new Thread(() -> {
                try {
                    WakeOnLanSender.sendWolPacket(pc);
                } catch (IOException ignored) {
                }
            }, "PocketHomeWol").start();
            main.postDelayed(this, 8000);
        }
    };

    private final Runnable wakeTimeout = () -> {
        if (pending != null && pendingWake) {
            cancelPending();
            hideBlockingCard();
            Toast.makeText(this, R.string.pocket_wake_failed, Toast.LENGTH_LONG).show();
        }
    };

    private void cancelPending() {
        pending = null;
        pendingWake = false;
        wakingPc = null;
        main.removeCallbacks(checkTimeout);
        main.removeCallbacks(wakeResend);
        main.removeCallbacks(wakeTimeout);
    }

    private void onPcUpdated(ComputerDetails details) {
        if (details == null || details.uuid == null || isFinishing()) {
            return;
        }
        livePcs.put(details.uuid, details);
        if (pending != null && pending.a.equals(details.uuid)) {
            if (details.state == ComputerDetails.State.ONLINE) {
                HomeItem item = pending;
                cancelPending();
                hideBlockingCard();
                startOn(details, item);
            }
            else if (details.state == ComputerDetails.State.OFFLINE && !pendingWake) {
                wakeOrGiveUp(details);
            }
        }
        // Play / Resume follows what the PC is running
        HomeStore.Entry e = entryAt(heroPos);
        if (e != null && details.uuid.equals(e.item.a) && HomeItem.PC.equals(e.item.kind)) {
            play.setText(playLabel(e.item));
        }
    }

    /** The PC is on: start (or resume) it, asking first if another game is running. */
    private void startOn(final ComputerDetails pc, final HomeItem item) {
        if (pc.pairState != PairingManager.PairState.PAIRED) {
            Toast.makeText(this, R.string.pocket_home_need_pair, Toast.LENGTH_LONG).show();
            SectionBar.go(this, SectionBar.HOME, SectionBar.STREAMING);
            return;
        }
        final NvApp app;
        if (HomeItem.PC_LIBRARY_GAME.equals(item.kind)) {
            NvApp own = PcLibrary.apolloAppFor(this, pc.uuid, item.name);
            if (own != null) {
                app = own;
            }
            else {
                NvApp desktop = PcLibrary.desktopApp(this, pc.uuid);
                if (desktop == null) {
                    Toast.makeText(this, R.string.pocket_pclib_no_desktop, Toast.LENGTH_LONG).show();
                    return;
                }
                // The stream opens on the PC's desktop and asks the PC to start the game
                PcLaunchTicket.arm(pc.uuid, desktop.getAppId(), item.b, item.name, item.n);
                app = desktop;
            }
        }
        else {
            app = appOf(pc, item.n, item.name);
        }
        if (pc.runningGameId != 0 && pc.runningGameId != app.getAppId()) {
            askSwitch(pc, app);
            return;
        }
        start(pc, app);
    }

    /** The PC's app with this id (from its list), or one made from what Home knows of it. */
    private NvApp appOf(ComputerDetails pc, int appId, String name) {
        for (RecentGames.Game g : RecentGames.forPc(this, pc.uuid, 100000)) {
            if (g.app.getAppId() == appId) {
                return g.app;
            }
        }
        return new NvApp(name != null && !name.isEmpty() ? name : "App " + appId, null, appId, false);
    }

    private void askSwitch(final ComputerDetails pc, final NvApp app) {
        final NvApp running = appOf(pc, pc.runningGameId, null);
        List<StreamMenuView.Item> items = new ArrayList<>();
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_home_running_resume, running.getAppName()), () -> {
            hideConsolePanel();
            start(pc, running);
        }));
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_home_running_switch, app.getAppName()), () -> {
            hideConsolePanel();
            if (binder == null) {
                return;
            }
            ServerHelper.doQuit(this, pc, running, binder, () -> main.post(() -> {
                if (!isFinishing()) {
                    pc.runningGameId = 0;
                    start(pc, app);
                }
            }));
        }));
        items.add(StreamMenuView.Item.action(getString(android.R.string.cancel), this::hideConsolePanel));
        showConsolePanel(getString(R.string.pocket_home_running_title, running.getAppName()), pc.name, null, items);
    }

    private void start(ComputerDetails pc, NvApp app) {
        if (binder == null) {
            Toast.makeText(this, R.string.pair_pc_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        RecentGames.invalidate(pc.uuid);
        boolean vd = PreferenceConfiguration.readPreferences(this).useVirtualDisplay;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ServerHelper.doStart(this, app, pc, binder, vd);
        }
        else {
            startActivity(ServerHelper.createStartIntent(this, app, pc, binder, vd));
        }
    }

    static String hostOf(Context context, ComputerDetails pc) {
        String usb = com.limelight.pocket.UsbLink.getUsbAddressFor(context, pc.uuid);
        if (usb != null) {
            return usb;
        }
        ComputerDetails.AddressTuple[] order = {pc.activeAddress, pc.localAddress, pc.manualAddress, pc.remoteAddress};
        for (ComputerDetails.AddressTuple t : order) {
            if (t != null && t.address != null && !t.address.isEmpty()) {
                return t.address;
            }
        }
        return "";
    }

    // ---- Options (Y) -----------------------------------------------------------------------------

    private void showOptions(int position) {
        final HomeStore.Entry e = entryAt(position);
        if (e == null) {
            showAddPanel();
            return;
        }
        final HomeItem item = e.item;
        List<StreamMenuView.Item> items = new ArrayList<>();
        items.add(StreamMenuView.Item.action(getString(playLabel(item)), () -> {
            hideConsolePanel();
            activate(position);
        }));
        final String uuid = artUuid(item);
        if (uuid != null) {
            items.add(StreamMenuView.Item.action(getString(R.string.pocket_hint_details), () -> {
                hideConsolePanel();
                openPage(e);
            }));
            items.add(StreamMenuView.Item.action(getString(R.string.pocket_rename), () -> {
                hideConsolePanel();
                rename(uuid, artId(item), displayName(item), item.name);
            }));
            items.add(StreamMenuView.Item.action(getString(R.string.pocket_art_change), () -> {
                hideConsolePanel();
                ArtPickerActivity.open(this, uuid, artId(item), RomScanner.searchName(displayName(item)));
            }));
        }
        if (position > 0) {
            items.add(StreamMenuView.Item.action(getString(R.string.pocket_home_move_left), () -> {
                hideConsolePanel();
                if (HomeStore.move(this, item, -1)) {
                    heroKey = item.key();
                    load();
                }
            }));
        }
        if (position < entries.size() - 2) {
            items.add(StreamMenuView.Item.action(getString(R.string.pocket_home_move_right), () -> {
                hideConsolePanel();
                if (HomeStore.move(this, item, 1)) {
                    heroKey = item.key();
                    load();
                }
            }));
        }
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_home_unpin), () -> {
            HomeStore.unpin(this, item);
            PocketSounds.play(this, PocketSounds.TOGGLE_OFF);
            Toast.makeText(this, getString(R.string.pocket_home_unpinned_toast, displayName(item)), Toast.LENGTH_SHORT).show();
            hideConsolePanel();
            heroKey = null;
            load();
        }));
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_home_open_section), () -> {
            hideConsolePanel();
            openSection(item);
        }));
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_home_add_more), this::showAddPanel));
        PocketSounds.play(this, PocketSounds.OPEN);
        showConsolePanel(displayName(item), where(e), null, items);
    }

    /** Renames it everywhere it shows (and looks its details up by the new name). */
    private void rename(final String uuid, final int id, String current, final String original) {
        final android.widget.EditText field = new android.widget.EditText(this);
        field.setSingleLine(true);
        field.setText(current);
        field.setSelectAllOnFocus(true);
        new android.app.AlertDialog.Builder(this, R.style.PocketDialog)
                .setTitle(R.string.pocket_rename)
                .setView(field)
                .setPositiveButton(android.R.string.ok, (dialog, w) -> {
                    String name = field.getText().toString().trim();
                    PocketPrefs.setCustomName(this, uuid, id, name.equals(original) ? null : name);
                    heroKey = null;
                    TILE_ART.evictAll();
                    tiles.notifyDataSetChanged();
                    updateHero(heroPos);
                })
                .setNeutralButton(R.string.pocket_rename_reset, (dialog, w) -> {
                    PocketPrefs.setCustomName(this, uuid, id, null);
                    heroKey = null;
                    tiles.notifyDataSetChanged();
                    updateHero(heroPos);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openSection(HomeItem item) {
        switch (item.kind) {
            case HomeItem.CLOUD:
                SectionBar.go(this, SectionBar.HOME, SectionBar.CLOUD);
                break;
            case HomeItem.RETRO:
                SectionBar.go(this, SectionBar.HOME, SectionBar.RETRO);
                break;
            case HomeItem.ANDROID:
            case HomeItem.SHORTCUT:
            case HomeItem.NATIVE:
                SectionBar.go(this, SectionBar.HOME, SectionBar.ANDROID);
                break;
            default:
                SectionBar.go(this, SectionBar.HOME, SectionBar.STREAMING);
                break;
        }
    }

    // ---- Adding to Home ------------------------------------------------------------------------

    private void showAddPanel() {
        List<StreamMenuView.Item> items = new ArrayList<>();
        items.add(StreamMenuView.Item.submenu(getString(R.string.pocket_home_add_streamed), this::pushPcPage));
        items.add(StreamMenuView.Item.submenu(getString(R.string.pocket_home_add_cloud), () -> {
            List<StreamMenuView.Item> page = new ArrayList<>();
            for (final CloudService s : CloudService.ALL) {
                page.add(StreamMenuView.Item.action(getString(s.name), () ->
                        pinAndShow(new HomeItem(HomeItem.CLOUD, getString(s.name), s.id, "", "", 0))));
            }
            pushConsolePage(getString(R.string.pocket_home_add_cloud), page);
        }));
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_home_add_retro), () -> {
            hideConsolePanel();
            Toast.makeText(this, R.string.pocket_home_add_from_section, Toast.LENGTH_LONG).show();
            SectionBar.go(this, SectionBar.HOME, SectionBar.RETRO);
        }));
        items.add(StreamMenuView.Item.submenu(getString(R.string.pocket_home_add_android), this::pushAndroidPage));
        items.add(StreamMenuView.Item.submenu(getString(R.string.pocket_home_add_native), this::pushNativePage));
        items.add(StreamMenuView.Item.submenu(getString(R.string.pocket_home_add_shortcut), this::pushShortcutPage));
        PocketSounds.play(this, PocketSounds.OPEN);
        showConsolePanel(getString(R.string.pocket_home_add), getString(R.string.pocket_home_add_sub), null, items);
    }

    private void pinAndShow(HomeItem item) {
        HomeStore.pin(this, item);
        PocketSounds.play(this, PocketSounds.TOGGLE_ON);
        Toast.makeText(this, getString(R.string.pocket_home_pinned_toast, item.name), Toast.LENGTH_SHORT).show();
        hideConsolePanel();
        heroKey = item.key();
        load();
    }

    /** The PCs: each one's library (Steam, Epic...) and its streamable apps. */
    private void pushPcPage() {
        work.execute(() -> {
            final List<ComputerDetails> pcs = HomeStore.computers(this);
            main.post(() -> {
                List<StreamMenuView.Item> page = new ArrayList<>();
                for (final ComputerDetails saved : pcs) {
                    if (saved.uuid == null) {
                        continue;
                    }
                    page.add(StreamMenuView.Item.submenu(getString(R.string.pocket_home_pc_apps, saved.name),
                            () -> pushPcApps(saved)));
                    page.add(StreamMenuView.Item.action(getString(R.string.pocket_home_pc_games, saved.name), () -> {
                        ComputerDetails live = livePcs.get(saved.uuid);
                        String host = hostOf(this, live != null ? live : saved);
                        pinAndShow(new HomeItem(HomeItem.PC_GAMES, getString(R.string.pocket_home_pc_games, saved.name),
                                saved.uuid, host, saved.name, 0));
                    }));
                }
                if (page.isEmpty()) {
                    page.add(StreamMenuView.Item.note(getString(R.string.pocket_home_no_pcs)));
                }
                page.add(StreamMenuView.Item.note(getString(R.string.pocket_home_add_from_section_pc)));
                pushConsolePage(getString(R.string.pocket_home_add_streamed), page);
            });
        });
    }

    /** A PC's apps: from what's known, or asked from the PC right now (it must be on). */
    private void pushPcApps(final ComputerDetails saved) {
        List<RecentGames.Game> games = RecentGames.forPc(this, saved.uuid, 500);
        if (!games.isEmpty()) {
            showPcApps(saved, games);
            return;
        }
        final ComputerDetails live = binder != null ? binder.getComputer(saved.uuid) : null;
        if (live == null || live.state != ComputerDetails.State.ONLINE || live.pairState != PairingManager.PairState.PAIRED
                || binder == null) {
            List<StreamMenuView.Item> page = new ArrayList<>();
            page.add(StreamMenuView.Item.note(getString(R.string.pocket_home_pc_list_offline)));
            pushConsolePage(saved.name, page);
            return;
        }
        Toast.makeText(this, R.string.pocket_home_loading_list, Toast.LENGTH_SHORT).show();
        final String uniqueId = binder.getUniqueId();
        work.execute(() -> {
            boolean ok = false;
            try {
                NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(live), live.httpsPort, uniqueId,
                        live.serverCert, PlatformBinding.getCryptoProvider(this));
                String raw = http.getAppListRaw();
                if (raw != null && !NvHTTP.getAppListByReader(new StringReader(raw)).isEmpty()) {
                    try (OutputStream out = CacheHelper.openCacheFileForOutput(getCacheDir(), "applist", live.uuid)) {
                        CacheHelper.writeStringToOutputStream(out, raw);
                    }
                    RecentGames.invalidate(live.uuid);
                    ok = true;
                }
            } catch (Exception ignored) {
            }
            final boolean got = ok;
            main.post(() -> {
                if (isFinishing() || !isConsolePanelShowing()) {
                    return;
                }
                if (!got) {
                    Toast.makeText(this, R.string.pocket_home_list_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                showPcApps(saved, RecentGames.forPc(this, saved.uuid, 500));
            });
        });
    }

    private void showPcApps(final ComputerDetails pc, List<RecentGames.Game> games) {
        List<StreamMenuView.Item> page = new ArrayList<>();
        for (final RecentGames.Game g : games) {
            final HomeItem item = new HomeItem(HomeItem.PC, g.app.getAppName(), pc.uuid, "", pc.name, g.app.getAppId());
            boolean on = HomeStore.isPinned(this, item);
            page.add(StreamMenuView.Item.action(g.app.getAppName(), () -> pinAndShow(item))
                    .withValue(on ? getString(R.string.pocket_home_pinned) : null));
        }
        if (page.isEmpty()) {
            page.add(StreamMenuView.Item.note(getString(R.string.pocket_home_pc_no_list)));
        }
        pushConsolePage(pc.name, page);
    }

    private void pushAndroidPage() {
        work.execute(() -> {
            final List<AndroidLibrary.InstalledApp> apps = AndroidLibrary.installed(this);
            java.util.Collections.sort(apps, (x, y) -> x.label.compareToIgnoreCase(y.label));
            main.post(() -> {
                List<StreamMenuView.Item> page = new ArrayList<>();
                for (final AndroidLibrary.InstalledApp app : apps) {
                    page.add(StreamMenuView.Item.action(app.label, () ->
                            pinAndShow(new HomeItem(HomeItem.ANDROID, app.label, app.packageName, "", "", 0))));
                }
                pushConsolePage(getString(R.string.pocket_home_add_android), page);
            });
        });
    }

    private void pushNativePage() {
        List<StreamMenuView.Item> page = new ArrayList<>();
        page.add(StreamMenuView.Item.submenu("GameNative", () -> pushOwnedPage(HomeLauncher.GAMENATIVE)));
        page.add(StreamMenuView.Item.submenu("GameHub Lite", () -> pushOwnedPage(HomeLauncher.GAMEHUB_LITE)));
        page.add(StreamMenuView.Item.note(getString(R.string.pocket_home_native_tip)));
        pushConsolePage(getString(R.string.pocket_home_add_native), page);
    }

    /** The linked Steam account's games, to start in GameNative / GameHub Lite. */
    private void pushOwnedPage(final String launcher) {
        if (!SteamAccount.linked(this)) {
            List<StreamMenuView.Item> page = new ArrayList<>();
            page.add(StreamMenuView.Item.action(getString(R.string.pocket_steam_link),
                    () -> PcLibraryActivity.askToLink(this, null)));
            page.add(StreamMenuView.Item.note(getString(R.string.pocket_home_native_link)));
            pushConsolePage(launcher.equals(HomeLauncher.GAMEHUB_LITE) ? "GameHub Lite" : "GameNative", page);
            return;
        }
        work.execute(() -> {
            final List<PcGame> owned = SteamAccount.owned(this, false);
            java.util.Collections.sort(owned, (x, y) -> x.name.compareToIgnoreCase(y.name));
            main.post(() -> {
                List<StreamMenuView.Item> page = new ArrayList<>();
                for (final PcGame g : owned) {
                    final int id;
                    try {
                        id = Integer.parseInt(g.id);
                    } catch (NumberFormatException ex) {
                        continue;
                    }
                    page.add(StreamMenuView.Item.action(g.name, () ->
                            pinAndShow(new HomeItem(HomeItem.NATIVE, g.name, launcher, "STEAM", "", id))));
                }
                if (page.isEmpty()) {
                    page.add(StreamMenuView.Item.note(getString(R.string.pocket_home_native_none)));
                }
                pushConsolePage(launcher.equals(HomeLauncher.GAMEHUB_LITE) ? "GameHub Lite" : "GameNative", page);
            });
        });
    }

    /** Shortcuts the apps publish (Winlator, GameHub ...): only the home app may open them. */
    private void pushShortcutPage() {
        List<StreamMenuView.Item> page = new ArrayList<>();
        if (!HomeLauncher.canUseShortcuts(this) || Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            page.add(StreamMenuView.Item.action(getString(R.string.pocket_set_home), () -> HomeScreen.openChooser(this)));
            page.add(StreamMenuView.Item.note(getString(R.string.pocket_home_shortcuts_need_home)));
            pushConsolePage(getString(R.string.pocket_home_add_shortcut), page);
            return;
        }
        List<ShortcutInfo> found = new ArrayList<>();
        try {
            LauncherApps la = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
            List<ShortcutInfo> all = la.getShortcuts(q, Process.myUserHandle());
            if (all != null) {
                found.addAll(all);
            }
        } catch (RuntimeException ignored) {
        }
        for (final ShortcutInfo si : found) {
            if (si.getPackage().equals(getPackageName())) {
                continue;
            }
            final CharSequence label = si.getShortLabel() != null ? si.getShortLabel() : si.getId();
            final String app = appLabel(si.getPackage());
            page.add(StreamMenuView.Item.action(label + "  ·  " + app, () -> {
                HomeItem item = new HomeItem(HomeItem.SHORTCUT, label.toString(), si.getPackage(), si.getId(), "", 0);
                ShortcutIcons.save(this, item, si);
                pinAndShow(item);
            }));
        }
        if (page.isEmpty()) {
            page.add(StreamMenuView.Item.note(getString(R.string.pocket_home_no_shortcuts)));
        }
        page.add(StreamMenuView.Item.note(getString(R.string.pocket_home_shortcut_tip)));
        pushConsolePage(getString(R.string.pocket_home_add_shortcut), page);
    }

    // ---- Keys ----------------------------------------------------------------------------------

    @Override
    protected boolean onShoulder(int direction) {
        return SectionBar.step(this, SectionBar.HOME, direction);
    }

    @Override
    public void onBackPressed() {
        if (isConsolePanelShowing() || isBlockingCardShowing()) {
            super.onBackPressed();
            return;
        }
        View focus = getCurrentFocus();
        if (focus == play || focus == info || focus == more) {
            focusAt(Math.max(0, heroPos), 0);
            return;
        }
        if (HomeScreen.isDefault(this)) {
            // The device's home: there's nothing behind it
            if (heroPos > 0) {
                focusAt(0, 0);
            }
            return;
        }
        // Leaving Home leaves Pocket Nexus
        finishAffinity();
    }

    // ---- The tiles -----------------------------------------------------------------------------

    private static final class TileHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final ImageView badge;
        final TextView label;
        final View ring;
        String boundKey;

        TileHolder(View v) {
            super(v);
            image = v.findViewById(R.id.grid_image);
            badge = v.findViewById(R.id.homeTileBadge);
            label = v.findViewById(R.id.homeTileLabel);
            ring = v.findViewById(R.id.homeTileRing);
        }
    }

    private final class Tiles extends RecyclerView.Adapter<TileHolder> {

        @NonNull
        @Override
        public TileHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(HomeActivity.this).inflate(R.layout.home_tile, parent, false);
            final TileHolder h = new TileHolder(v);
            final float radius = 15 * d;
            ViewOutlineProvider rounded = new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            };
            h.image.setOutlineProvider(rounded);
            h.image.setClipToOutline(true);
            h.label.setOutlineProvider(rounded);
            h.label.setClipToOutline(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.setDefaultFocusHighlightEnabled(false);
            }
            v.setNextFocusUpId(R.id.pocketHeroPlay);
            v.setOnClickListener(view -> {
                int pos = h.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    press(view);
                    PocketSounds.play(HomeActivity.this, PocketSounds.SELECT);
                    activate(pos);
                }
            });
            v.setOnLongClickListener(view -> {
                int pos = h.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    showOptions(pos);
                }
                return true;
            });
            v.setOnKeyListener((view, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_BUTTON_Y || keyCode == KeyEvent.KEYCODE_MENU) {
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        int pos = h.getBindingAdapterPosition();
                        if (pos != RecyclerView.NO_POSITION) {
                            showOptions(pos);
                        }
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (play.getVisibility() == View.VISIBLE) {
                        play.requestFocus();
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.getAction() == KeyEvent.ACTION_DOWN) {
                    return true; // nothing under the row
                }
                return false;
            });
            v.setOnFocusChangeListener((view, has) -> {
                focusLook(h, has, true);
                if (has) {
                    int pos = h.getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        if (pos != heroPos) {
                            PocketUi.tick(HomeActivity.this);
                        }
                        updateHero(pos);
                    }
                }
            });
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull TileHolder h, int position) {
            HomeStore.Entry e = entries.get(position);
            focusLook(h, h.itemView.hasFocus(), false);
            if (e == null) {
                h.boundKey = "+";
                h.itemView.setContentDescription(getString(R.string.pocket_home_add));
                h.image.setVisibility(View.INVISIBLE);
                h.badge.setVisibility(View.GONE);
                h.label.setVisibility(View.VISIBLE);
                h.label.setText(R.string.pocket_home_add_short);
                h.label.setTypeface(PocketFonts.medium());
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(15 * d);
                bg.setColor(0x9E1C1C1F);
                bg.setStroke(Math.max(1, Math.round(0.75f * d)), 0x33FFFFFF);
                h.label.setBackground(bg);
                Drawable plus = getDrawable(R.drawable.ic_add);
                if (plus != null) {
                    plus = plus.mutate();
                    plus.setTint(0xFFFFFFFF);
                    int s = Math.round(20 * d);
                    plus.setBounds(0, 0, s, s);
                }
                h.label.setCompoundDrawables(null, plus, null, null);
                // The plus and its word centred together
                int iconPx = plus != null ? plus.getBounds().height() : 0;
                int group = iconPx + h.label.getCompoundDrawablePadding() + h.label.getLineHeight();
                h.label.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
                h.label.setPadding(0, Math.max(0, (Math.round(TILE_DP * d) - group) / 2), 0, 0);
                return;
            }
            final HomeItem item = e.item;
            h.label.setVisibility(View.GONE);
            h.image.setVisibility(View.VISIBLE);
            // Where it's from is shown with the name above; the tile is just the game
            h.badge.setVisibility(View.GONE);
            h.itemView.setContentDescription(displayName(item));
            final String key = item.key();
            h.boundKey = key;
            Bitmap cached = TILE_ART.get(key);
            if (cached != null) {
                h.image.setImageBitmap(cached);
                return;
            }
            h.image.setImageDrawable(null);
            final String name = displayName(item);
            work.execute(() -> {
                Bitmap art = tileArt(item, name);
                if (art != null) {
                    TILE_ART.put(key, art);
                }
                main.post(() -> {
                    if (key.equals(h.boundKey) && art != null) {
                        boolean fresh = h.image.getDrawable() == null;
                        h.image.setImageBitmap(art);
                        if (fresh && PocketPrefs.animationsEnabled(HomeActivity.this)) {
                            h.image.setAlpha(0f);
                            h.image.animate().alpha(1f).setDuration(180).start();
                        }
                    }
                });
                // A square capsule from SteamGridDB, when there's a key and none yet
                String uuid = artUuid(item);
                if (uuid != null && PocketArt.hasKey(HomeActivity.this)
                        && PocketArt.fetchOne(HomeActivity.this, uuid, artId(item), RomScanner.searchName(name), PocketArt.SQUARE)) {
                    main.post(() -> {
                        if (!isFinishing()) {
                            TILE_ART.remove(key);
                            tiles.notifyDataSetChanged();
                        }
                    });
                }
            });
        }

        @Override
        public void onViewRecycled(@NonNull TileHolder h) {
            h.boundKey = null;
            h.itemView.animate().cancel();
            focusLook(h, false, false);
            super.onViewRecycled(h);
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }
    }

    private void focusLook(TileHolder h, boolean focused, boolean animate) {
        float s = focused ? FOCUS_SCALE : 1f;
        float z = focused ? 8 * d : 0f;
        if (animate && PocketPrefs.animationsEnabled(this)) {
            h.itemView.animate().scaleX(s).scaleY(s).translationZ(z).setDuration(Spring.UI_DURATION_MS)
                    .setInterpolator(Spring.UI_CURVE).start();
            h.ring.animate().alpha(focused ? 1f : 0f).setDuration(focused ? 160 : 120).start();
        }
        else {
            h.itemView.animate().cancel();
            h.itemView.setScaleX(s);
            h.itemView.setScaleY(s);
            h.itemView.setTranslationZ(z);
            h.ring.setAlpha(focused ? 1f : 0f);
        }
    }

    private void press(final View v) {
        if (!PocketPrefs.animationsEnabled(this)) {
            return;
        }
        final float base = v.getScaleX();
        v.animate().scaleX(base * 0.94f).scaleY(base * 0.94f).setDuration(70).setInterpolator(PocketUi.EASE)
                .withEndAction(() -> v.animate().scaleX(base).scaleY(base).setDuration(320).setInterpolator(Spring.POP).start())
                .start();
    }

    // ---- Tile art (blocking: worker threads only) --------------------------------------------

    /**
     * A square picture for a tile: SteamGridDB's square capsule, else the cover (cropped around
     * its upper middle, where the title and hero usually are), else the background, else the
     * app's icon on its own colour, else a drawn card.
     */
    private Bitmap tileArt(HomeItem item, String name) {
        int size = Math.round(TILE_DP * d * 1.15f);
        String uuid = artUuid(item);
        if (uuid != null) {
            int id = artId(item);
            Bitmap sq = decode(PocketArt.file(this, uuid, id, PocketArt.SQUARE), size);
            if (sq != null) {
                return crop(sq, size, 0.5f);
            }
            // An Android app with no art of its own: its icon (never a lettered card)
            boolean appOnly = HomeItem.ANDROID.equals(item.kind) && !PocketArt.has(this, uuid, id, PocketArt.COVER)
                    && !PocketArt.has(this, uuid, id, PocketArt.HERO);
            if (!HomeItem.SHORTCUT.equals(item.kind) && !appOnly) {
                Bitmap cover = null;
                try {
                    cover = HeroArt.load(this, uuid, id);
                } catch (RuntimeException ignored) {
                }
                if (cover != null && !HeroArt.isPlaceholder(cover)
                        && !(HomeItem.ANDROID.equals(item.kind) && cover.getWidth() == cover.getHeight() && cover.getWidth() <= 256)) {
                    return crop(cover, size, 0.38f);
                }
                Bitmap hero = decode(PocketArt.file(this, uuid, id, PocketArt.HERO), size * 2);
                if (hero != null) {
                    return crop(hero, size, 0.42f);
                }
            }
        }
        Bitmap icon = iconOf(item);
        if (icon != null) {
            return iconTile(icon, size);
        }
        if (HomeItem.CLOUD.equals(item.kind)) {
            CloudService s = CloudService.byId(item.a);
            return nameTile(name, s != null ? s.color : 0xFF2B6CB0, size);
        }
        if (HomeItem.PC_GAMES.equals(item.kind)) {
            return nameTile(name, 0xFF3A3F55, size);
        }
        Bitmap placeholder = PlaceholderArt.render(RomScanner.searchName(name), size);
        return placeholder != null ? crop(placeholder, size, 0.4f) : nameTile(name, 0xFF2E2F3A, size);
    }

    private static Bitmap decode(java.io.File f, int target) {
        if (f == null || !f.exists()) {
            return null;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= target) {
            sample *= 2;
        }
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        try {
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** A square of side size from the picture (centred across, at yFocus down). */
    private static Bitmap crop(Bitmap src, int size, float yFocus) {
        src = software(src);
        if (src == null) {
            return null;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int side = Math.min(w, h);
        int left = (w - side) / 2;
        int top = Math.round(Math.max(0, Math.min(h - side, h * yFocus - side / 2f)));
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        c.drawBitmap(src, new Rect(left, top, left + side, top + side), new RectF(0, 0, size, size), p);
        return out;
    }

    /** A copy software canvases can draw (art is kept as hardware bitmaps on Android 8+). */
    static Bitmap software(Bitmap b) {
        if (b != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && b.getConfig() == Bitmap.Config.HARDWARE) {
            try {
                return b.copy(Bitmap.Config.ARGB_8888, false);
            } catch (RuntimeException | OutOfMemoryError e) {
                return null;
            }
        }
        return b;
    }

    /** The app's icon, big, on a deep version of its own colour (like a console's app tiles). */
    private static Bitmap iconTile(Bitmap icon, int size) {
        icon = software(icon);
        if (icon == null) {
            return null;
        }
        int base = averageColor(icon);
        float[] hsv = new float[3];
        Color.colorToHSV(base, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.18f, Math.min(0.42f, hsv[2] * 0.55f));
        int deep = Color.HSVToColor(hsv);
        hsv[2] = Math.min(0.62f, hsv[2] * 1.6f);
        int light = Color.HSVToColor(hsv);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, size * 0.6f, size, light, deep, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, size, size, bg);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setShader(new RadialGradient(size * 0.5f, size * 0.42f, size * 0.6f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, size, size, glow);
        float s = size * 0.56f;
        float l = (size - s) / 2f;
        float t = (size - s) / 2f;
        c.drawBitmap(icon, null, new RectF(l, t, l + s, t + s), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return out;
    }

    private static int averageColor(Bitmap b) {
        Bitmap one = Bitmap.createScaledBitmap(b, 6, 6, true);
        long r = 0;
        long g = 0;
        long bl = 0;
        long n = 0;
        for (int x = 0; x < 6; x++) {
            for (int y = 0; y < 6; y++) {
                int p = one.getPixel(x, y);
                if (Color.alpha(p) < 128) {
                    continue;
                }
                r += Color.red(p);
                g += Color.green(p);
                bl += Color.blue(p);
                n++;
            }
        }
        if (n == 0) {
            return 0xFF2E2F3A;
        }
        return Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
    }

    /** A tile with its name on a colour (cloud services, a PC's library). */
    private Bitmap nameTile(String name, int color, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        int dark = Color.rgb(Math.round(Color.red(color) * 0.3f), Math.round(Color.green(color) * 0.3f),
                Math.round(Color.blue(color) * 0.3f));
        bg.setShader(new LinearGradient(0, 0, size * 0.5f, size, color, dark, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, size, size, bg);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(0xFFFFFFFF);
        t.setTypeface(PocketFonts.bold());
        t.setTextAlign(Paint.Align.CENTER);
        float textSize = size * 0.13f;
        t.setTextSize(textSize);
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : name.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (t.measureText(next) > size * 0.82f && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            }
            else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        int count = Math.min(3, lines.size());
        float y = size / 2f - (count - 1) * textSize * 0.6f + textSize * 0.35f;
        for (int i = 0; i < count; i++) {
            c.drawText(lines.get(i), size / 2f, y, t);
            y += textSize * 1.2f;
        }
        return out;
    }

    private Bitmap iconOf(HomeItem item) {
        try {
            Drawable dr = null;
            if (HomeItem.SHORTCUT.equals(item.kind)) {
                Bitmap saved = ShortcutIcons.load(this, item);
                if (saved != null) {
                    return saved;
                }
            }
            if (HomeItem.ANDROID.equals(item.kind) || HomeItem.SHORTCUT.equals(item.kind)) {
                dr = getPackageManager().getApplicationIcon(item.a);
            }
            if (dr == null) {
                return null;
            }
            int s = Math.max(144, dr.getIntrinsicWidth());
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            dr.setBounds(0, 0, s, s);
            dr.draw(c);
            return b;
        } catch (Exception e) {
            return null;
        }
    }
}
