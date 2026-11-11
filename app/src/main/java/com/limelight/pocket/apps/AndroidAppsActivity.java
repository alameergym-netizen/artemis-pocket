package com.limelight.pocket.apps;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.pocket.ArtPickerActivity;
import com.limelight.pocket.ArtScraper;
import com.limelight.pocket.ConsoleActivity;
import com.limelight.pocket.GamePageActivity;
import com.limelight.pocket.PocketArt;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.PocketSounds;
import com.limelight.pocket.info.GameInfoService;
import com.limelight.pocket.ui.AnchoredLayoutManager;
import com.limelight.pocket.ui.CarouselAdapter;
import com.limelight.pocket.ui.HeroArt;
import com.limelight.pocket.ui.HeroFocus;
import com.limelight.pocket.ui.KeyArtStage;
import com.limelight.pocket.ui.PocketMotion;
import com.limelight.pocket.ui.PocketUi;
import com.limelight.pocket.ui.SectionBar;
import com.limelight.pocket.ui.StreamMenuView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Android section: the games and apps picked for it (not every app on the handheld), with
 * their art and pages like the PC's games, and the device's settings. "+ Add apps" picks them
 * (several at once); their art and information are looked up by themselves. L1 / R1 go to the
 * other section, L2 / R2 filter (all, games, emulators, settings).
 */
public class AndroidAppsActivity extends ConsoleActivity implements CarouselAdapter.Listener {

    private static final String KEY_USED = "pocket_apps_used_";

    private static final int FILTER_ALL = 0;
    private static final int FILTER_GAMES = 1;
    private static final int FILTER_EMULATORS = 2;
    private static final int FILTER_SETTINGS = 3;
    private static final int FILTER_COUNT = 4;

    static final int TYPE_APP = 0;
    static final int TYPE_GAME = 1;
    static final int TYPE_EMULATOR = 2;
    static final int TYPE_SETTING = 3;
    private static final String PANEL = "pocket:device_panel";

    public static void open(Activity from) {
        PocketSounds.play(from, PocketSounds.OPEN);
        from.startActivity(new Intent(from, AndroidAppsActivity.class));
    }

    /** One card: an app (package) or a device setting (an Intent action). */
    static final class Entry {
        final String key;
        final String label;
        final int type;
        final String packageName;
        final String settingsAction;
        final int settingsIcon;
        long used;

        Entry(String key, String label, int type, String packageName, String settingsAction, int settingsIcon) {
            this.key = key;
            this.label = label;
            this.type = type;
            this.packageName = packageName;
            this.settingsAction = settingsAction;
            this.settingsIcon = settingsIcon;
        }

        int id() {
            return packageName != null ? AndroidLibrary.idFor(packageName) : 0;
        }
    }

    private RecyclerView carousel;
    private final Cards adapter = new Cards();
    private List<Entry> all = new ArrayList<>();
    private int filter = FILTER_ALL;
    private int heroPos = -1;
    private String heroKey;
    private KeyArtStage keyArt;
    private int artVersion = PocketArt.version();
    private boolean pickerShown;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private static final LruCache<String, Bitmap> ICONS = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    @Override
    protected boolean allowsAutoResume() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_view);
        TextView title = findViewById(R.id.appListText);
        if (title != null) {
            title.setText(R.string.pocket_apps_title);
        }
        SectionBar.attach(this, SectionBar.ANDROID);
        hide(R.id.profilesButton);
        TextView add = findViewById(R.id.pocketPcLibraryButton);
        if (add != null) {
            add.setText(R.string.pocket_apps_add_short);
            add.setOnClickListener(v -> AppPickerActivity.open(this));
        }

        carousel = findViewById(R.id.pocketAppCarousel);
        carousel.setLayoutManager(new AnchoredLayoutManager(this));
        carousel.setItemAnimator(null);
        carousel.setAdapter(new CarouselAdapter(adapter, this, 14));
        keyArt = KeyArtStage.find(this);
        heroFocus = HeroFocus.install(this, new HeroFocus.Host() {
            @Override
            public boolean heroCanLift() {
                return carousel != null && carousel.hasFocus();
            }

            @Override
            public void heroPlay() {
                Entry e = entryAt(heroPos);
                if (e != null) {
                    open(e);
                }
            }
        }, R.id.appFragmentContainer);

        heroIdle(getString(R.string.pocket_apps_loading));
        setHints(hintA(this, R.string.pocket_apps_open),
                hintX(this, R.string.pocket_hint_details),
                hintY(this, R.string.pocket_hint_options),
                new Hint("L2 R2", 0xFFFFFFFF, getString(R.string.pocket_pclib_hint_filter)),
                hintStart(this, R.string.pocket_hint_settings));
        load();
    }

    private void hide(int id) {
        View v = findViewById(id);
        if (v != null) {
            v.setVisibility(View.GONE);
        }
    }

    /** The hero with just a line of text (no buttons, no chips). */
    private void heroIdle(String text) {
        TextView t = findViewById(R.id.pocketHeroTitle);
        if (t != null) {
            t.setVisibility(View.VISIBLE);
            t.setText(text);
        }
        hide(R.id.pocketHeroPlay);
        hide(R.id.pocketHeroSubtitle);
        hide(R.id.pocketHeroPlaytime);
        hide(R.id.pocketHeroLogo);
        if (keyArt != null) {
            keyArt.show(null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (artVersion != PocketArt.version()) {
            artVersion = PocketArt.version();
            refreshArt();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        work.shutdownNow();
    }

    // ---- What's in the library ----------------------------------------------------------------

    private void load() {
        final Set<String> library = AndroidLibrary.packages(this);
        work.execute(() -> {
            List<Entry> apps = new ArrayList<>();
            for (AndroidLibrary.InstalledApp a : AndroidLibrary.installed(this)) {
                if (!library.contains(a.packageName)) {
                    continue;
                }
                Entry e = new Entry("app:" + a.packageName, a.label, a.type, a.packageName, null, 0);
                e.used = PocketPrefs.prefs(this).getLong(KEY_USED + a.packageName, 0);
                apps.add(e);
            }
            // Played from here last first, then games, emulators, apps, each by name
            Collections.sort(apps, (a, b) -> {
                if (a.used != b.used) {
                    return Long.compare(b.used, a.used);
                }
                int ra = rank(a.type);
                int rb = rank(b.type);
                if (ra != rb) {
                    return Integer.compare(ra, rb);
                }
                return a.label.compareToIgnoreCase(b.label);
            });
            apps.addAll(settingsEntries());
            main.post(() -> {
                if (isFinishing()) {
                    return;
                }
                all = apps;
                show();
                if (!AndroidLibrary.isSetUp(this) && !pickerShown) {
                    // The first time: pick the games and apps that belong here
                    pickerShown = true;
                    AppPickerActivity.open(this);
                }
            });
        });
    }

    private static int rank(int type) {
        return type == TYPE_GAME ? 0 : (type == TYPE_EMULATOR ? 1 : 2);
    }

    /** Emulators, by their package or name (they rarely say "game" about themselves). */
    private static final String[] EMULATOR_PACKAGES = {
            "com.retroarch", "org.dolphinemu", "org.ppsspp", "xyz.aethersx2", "com.github.stenzek",
            "org.citra", "io.github.lime3ds", "org.azahar", "io.github.azahar", "org.yuzu", "dev.eden",
            "org.sudachi", "org.citron", "com.winlator", "org.vita3k", "skyline.emu", "org.ryujinx",
            "me.magnum.melonds", "com.dsemu.drastic", "com.fastemulator", "io.recompiled.redream",
            "com.flycast", "com.epsxe", "org.mupen64plusae", "com.swordfish.lemuroid", "org.es_de",
            "com.explusalpha", "org.scummvm", "app.gamenative", "com.xiaoji",
            "com.mobilegame", "info.cemu", "com.seleuco.mame4droid", "org.devmiyax.yabasanshiro",
            "com.reicast", "com.virtualapplications.play", "io.github.mandarine3ds", "org.panda3ds",
    };

    static int typeOf(String pkg, CharSequence label, ApplicationInfo ai) {
        String p = pkg.toLowerCase(Locale.ROOT);
        String l = label != null ? label.toString().toLowerCase(Locale.ROOT) : "";
        for (String e : EMULATOR_PACKAGES) {
            if (p.startsWith(e)) {
                return TYPE_EMULATOR;
            }
        }
        if (l.contains("emulator") || l.contains("emu ") || l.endsWith("emu") || p.contains(".emu") || p.endsWith("emu")) {
            return TYPE_EMULATOR;
        }
        if (ai != null) {
            @SuppressWarnings("deprecation")
            boolean flaggedGame = (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
            boolean categoryGame = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ai.category == ApplicationInfo.CATEGORY_GAME;
            if (flaggedGame || categoryGame) {
                return TYPE_GAME;
            }
        }
        return TYPE_APP;
    }

    /** The device's settings, reachable with the controller. */
    private List<Entry> settingsEntries() {
        List<Entry> s = new ArrayList<>();
        // Pocket Nexus's own quick panel first (volume, brightness, notifications ...)
        s.add(new Entry("set:panel", getString(R.string.pocket_device_title), TYPE_SETTING, null,
                PANEL, android.R.drawable.ic_menu_manage));
        s.add(new Entry("set:wifi", getString(R.string.pocket_apps_set_wifi), TYPE_SETTING, null,
                Settings.ACTION_WIFI_SETTINGS, android.R.drawable.ic_menu_share));
        s.add(new Entry("set:bt", getString(R.string.pocket_apps_set_bluetooth), TYPE_SETTING, null,
                Settings.ACTION_BLUETOOTH_SETTINGS, android.R.drawable.stat_sys_data_bluetooth));
        s.add(new Entry("set:display", getString(R.string.pocket_apps_set_display), TYPE_SETTING, null,
                Settings.ACTION_DISPLAY_SETTINGS, android.R.drawable.ic_menu_view));
        s.add(new Entry("set:sound", getString(R.string.pocket_apps_set_sound), TYPE_SETTING, null,
                Settings.ACTION_SOUND_SETTINGS, android.R.drawable.ic_lock_silent_mode_off));
        s.add(new Entry("set:battery", getString(R.string.pocket_apps_set_battery), TYPE_SETTING, null,
                Intent.ACTION_POWER_USAGE_SUMMARY, android.R.drawable.ic_lock_idle_low_battery));
        s.add(new Entry("set:storage", getString(R.string.pocket_apps_set_storage), TYPE_SETTING, null,
                Settings.ACTION_INTERNAL_STORAGE_SETTINGS, android.R.drawable.ic_menu_save));
        s.add(new Entry("set:apps", getString(R.string.pocket_apps_set_apps), TYPE_SETTING, null,
                Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, android.R.drawable.ic_menu_manage));
        s.add(new Entry("set:home", getString(R.string.pocket_apps_set_home), TYPE_SETTING, null,
                Settings.ACTION_HOME_SETTINGS, android.R.drawable.ic_menu_myplaces));
        s.add(new Entry("set:all", getString(R.string.pocket_apps_set_all), TYPE_SETTING, null,
                Settings.ACTION_SETTINGS, android.R.drawable.ic_menu_preferences));
        return s;
    }

    private boolean matches(Entry e) {
        switch (filter) {
            case FILTER_GAMES:
                return e.type == TYPE_GAME;
            case FILTER_EMULATORS:
                return e.type == TYPE_EMULATOR;
            case FILTER_SETTINGS:
                return e.type == TYPE_SETTING;
            default:
                // Everything picked for the library (the settings have their own filter)
                return e.type != TYPE_SETTING;
        }
    }

    private void show() {
        List<Entry> shown = new ArrayList<>();
        for (Entry e : all) {
            if (matches(e)) {
                shown.add(e);
            }
        }
        String keep = heroKey;
        adapter.set(shown);
        updateSection();
        if (shown.isEmpty()) {
            heroPos = -1;
            heroKey = null;
            heroIdle(getString(filter == FILTER_ALL ? R.string.pocket_apps_none_yet : R.string.pocket_apps_empty));
            return;
        }
        int target = 0;
        for (int i = 0; keep != null && i < shown.size(); i++) {
            if (shown.get(i).key.equals(keep)) {
                target = i;
            }
        }
        heroPos = -1;
        heroKey = null;
        focusAt(target, 0);
    }

    private void focusAt(final int position, final int attempt) {
        if (attempt == 0) {
            carousel.scrollToPosition(position);
        }
        carousel.post(() -> {
            if (isFinishing() || position >= adapter.getCount()) {
                return;
            }
            RecyclerView.ViewHolder holder = carousel.findViewHolderForAdapterPosition(position);
            if (holder != null && holder.itemView.requestFocus()) {
                return;
            }
            if (attempt < 6) {
                carousel.postDelayed(() -> focusAt(position, attempt + 1), attempt < 2 ? 16 : 120);
            }
            if (heroPos != position) {
                updateHero(position);
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && carousel != null && !carousel.hasFocus() && adapter.getCount() > 0 && !isConsolePanelShowing()) {
            focusAt(heroPos >= 0 && heroPos < adapter.getCount() ? heroPos : 0, 0);
        }
    }

    private Entry entryAt(int position) {
        return position >= 0 && position < adapter.getCount() ? adapter.getItem(position) : null;
    }

    private void updateSection() {
        TextView section = findViewById(R.id.pocketSectionTitle);
        if (section != null) {
            section.setText(getString(filterLabel(filter)) + "  \u00b7  " + adapter.getCount());
        }
    }

    private static int filterLabel(int filter) {
        switch (filter) {
            case FILTER_GAMES:
                return R.string.pocket_apps_filter_games;
            case FILTER_EMULATORS:
                return R.string.pocket_apps_filter_emulators;
            case FILTER_SETTINGS:
                return R.string.pocket_apps_filter_settings;
            default:
                return R.string.pocket_apps_filter_all;
        }
    }

    // ---- The hero -------------------------------------------------------------------------------

    private void updateHero(int position) {
        Entry e = entryAt(position);
        if (e == null) {
            return;
        }
        TextView title = findViewById(R.id.pocketHeroTitle);
        int dir = heroPos < 0 ? 0 : Integer.signum(position - heroPos);
        if (title != null && title.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            dir = -dir;
        }
        boolean changed = !e.key.equals(heroKey);
        heroPos = position;
        heroKey = e.key;
        if (title != null && changed) {
            title.setVisibility(View.VISIBLE);
            PocketMotion.swapText(title, e.label, dir);
        }
        TextView subtitle = findViewById(R.id.pocketHeroSubtitle);
        if (subtitle != null) {
            subtitle.setVisibility(View.VISIBLE);
            subtitle.setText(typeLabel(e.type));
        }
        hide(R.id.pocketHeroPlaytime);
        TextView play = findViewById(R.id.pocketHeroPlay);
        if (play != null) {
            PocketUi.stylePlayPill(play);
            play.setVisibility(View.VISIBLE);
            String label = "\u25B6  " + getString(e.type == TYPE_GAME || e.type == TYPE_EMULATOR
                    ? R.string.pocket_hero_play : R.string.pocket_apps_open);
            if (!label.contentEquals(play.getText())) {
                play.setText(label);
            }
        }
        if (changed) {
            showArt(e);
        }
    }

    /** Its logo and background, picked or found (none for settings). */
    private void showArt(final Entry e) {
        final ImageView logo = findViewById(R.id.pocketHeroLogo);
        final TextView title = findViewById(R.id.pocketHeroTitle);
        if (logo != null) {
            logo.setVisibility(View.GONE);
        }
        if (e.packageName == null) {
            if (keyArt != null) {
                keyArt.show(null);
            }
            return;
        }
        final String key = e.key;
        PocketArt.load(this, AndroidLibrary.UUID, e.id(), PocketArt.HERO, bitmap -> {
            if (key.equals(heroKey) && keyArt != null) {
                keyArt.show(bitmap);
            }
        });
        PocketArt.load(this, AndroidLibrary.UUID, e.id(), PocketArt.LOGO, bitmap -> {
            if (key.equals(heroKey) && bitmap != null && logo != null && title != null) {
                logo.setImageBitmap(bitmap);
                logo.setVisibility(View.VISIBLE);
                title.setVisibility(View.GONE);
            }
        });
        if (!PocketArt.has(this, AndroidLibrary.UUID, e.id(), PocketArt.COVER)
                || !PocketArt.has(this, AndroidLibrary.UUID, e.id(), PocketArt.HERO)) {
            scrape(e, false);
        }
    }

    private int typeLabel(int type) {
        switch (type) {
            case TYPE_GAME:
                return R.string.pocket_apps_type_game;
            case TYPE_EMULATOR:
                return R.string.pocket_apps_type_emulator;
            case TYPE_SETTING:
                return R.string.pocket_apps_type_setting;
            default:
                return R.string.pocket_apps_type_app;
        }
    }

    // ---- Art and information ------------------------------------------------------------------

    private ArtScraper.Item itemFor(Entry e) {
        ArtScraper.Item item = new ArtScraper.Item(AndroidLibrary.UUID, e.id(), e.label);
        item.playPackage = e.packageName;
        return item;
    }

    /** SteamGridDB, Steam, IGDB and Google Play, for what an app is missing (in the background). */
    private void scrape(final Entry e, final boolean force) {
        if (e.packageName == null) {
            return;
        }
        ArtScraper.scrape(this, itemFor(e), force, changed -> {
            if (isFinishing()) {
                return;
            }
            if (changed) {
                refreshArt();
            }
            else if (force) {
                Toast.makeText(this, R.string.pocket_art_found_nothing, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void refreshArt() {
        adapter.notifyDataSetChanged();
        if (heroPos >= 0) {
            heroKey = null;
            updateHero(heroPos);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == AppPickerActivity.REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                String[] added = data.getStringArrayExtra(AppPickerActivity.RESULT_ADDED);
                if (added != null && added.length > 0) {
                    Toast.makeText(this, getString(R.string.pocket_apps_added, added.length), Toast.LENGTH_LONG).show();
                    PackageManager pm = getPackageManager();
                    for (String pkg : added) {
                        // Art and information for each new one, by itself
                        String label = pkg;
                        try {
                            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                        } catch (PackageManager.NameNotFoundException ignored) {
                        }
                        Entry e = new Entry("app:" + pkg, label, TYPE_APP, pkg, null, 0);
                        scrape(e, true);
                    }
                }
            }
            load();
            return;
        }
        if (requestCode == GamePageActivity.REQUEST && data != null
                && GamePageActivity.ACTION_PLAY.equals(data.getStringExtra(GamePageActivity.RESULT_ACTION))) {
            int id = data.getIntExtra(GamePageActivity.RESULT_APP_ID, 0);
            for (Entry e : all) {
                if (e.packageName != null && e.id() == id) {
                    open(e);
                    return;
                }
            }
        }
    }

    private void openPage(Entry e) {
        if (e == null || e.packageName == null) {
            return;
        }
        GameInfoService.setPlayPackage(this, e.label, e.packageName);
        View card = null;
        Bitmap cover = null;
        int pos = heroPos;
        RecyclerView.LayoutManager lm = carousel.getLayoutManager();
        View frame = lm != null && pos >= 0 ? lm.findViewByPosition(pos) : null;
        if (frame instanceof ViewGroup && ((ViewGroup) frame).getChildCount() > 0) {
            card = ((ViewGroup) frame).getChildAt(0);
            ImageView image = card.findViewById(R.id.grid_image);
            if (image != null && image.getDrawable() instanceof BitmapDrawable) {
                cover = ((BitmapDrawable) image.getDrawable()).getBitmap();
                card = image;
            }
        }
        GamePageActivity.open(this, card, AndroidLibrary.UUID, e.id(), e.label, getString(R.string.pocket_section_android),
                false, false, cover);
    }

    // ---- Buttons ----------------------------------------------------------------------------

    @Override
    public void onItemFocused(int position, View itemView) {
        updateHero(position);
    }

    @Override
    public void onItemClicked(int position, View itemView) {
        Entry e = entryAt(position);
        if (e != null) {
            open(e);
        }
    }

    private void open(Entry e) {
        try {
            if (e.type == TYPE_SETTING) {
                if (PANEL.equals(e.settingsAction)) {
                    com.limelight.pocket.device.DevicePanel.show(this);
                    return;
                }
                startActivity(new Intent(e.settingsAction));
                return;
            }
            Intent launch = getPackageManager().getLaunchIntentForPackage(e.packageName);
            if (launch == null) {
                Toast.makeText(this, R.string.pocket_apps_cant_open, Toast.LENGTH_SHORT).show();
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PocketPrefs.prefs(this).edit().putLong(KEY_USED + e.packageName, System.currentTimeMillis()).apply();
            e.used = System.currentTimeMillis();
            PocketSounds.play(this, PocketSounds.SELECT);
            startActivity(launch);
        } catch (RuntimeException ex) {
            Toast.makeText(this, R.string.pocket_apps_cant_open, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected boolean onButtonX() {
        Entry e = entryAt(heroPos);
        if (e == null || e.packageName == null) {
            return false;
        }
        openPage(e);
        return true;
    }

    @Override
    protected boolean onGamePageShortcut() {
        return onButtonX();
    }

    @Override
    public void onItemOptions(int position, View itemView) {
        final Entry e = entryAt(position);
        if (e == null || e.type == TYPE_SETTING) {
            return;
        }
        List<StreamMenuView.Item> items = new ArrayList<>();
        items.add(StreamMenuView.Item.action(getString(e.type == TYPE_APP ? R.string.pocket_apps_open
                : R.string.pocket_hero_play), () -> open(e)));
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_hint_details), () -> {
            hideConsolePanel();
            openPage(e);
        }));
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_art_change), () -> {
            hideConsolePanel();
            ArtPickerActivity.open(this, AndroidLibrary.UUID, e.id(), e.label);
        }));
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_art_find_again), () -> {
            Toast.makeText(this, R.string.pocket_art_finding, Toast.LENGTH_SHORT).show();
            scrape(e, true);
        }));
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_apps_add), () -> {
            hideConsolePanel();
            AppPickerActivity.open(this);
        }));
        items.add(StreamMenuView.Item.confirm(getString(R.string.pocket_apps_remove), () -> {
            AndroidLibrary.remove(this, e.packageName);
            load();
        }));
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_apps_info), () -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + e.packageName)));
            } catch (RuntimeException ignored) {
            }
        }));
        items.add(StreamMenuView.Item.confirm(getString(R.string.pocket_apps_uninstall), () -> {
            try {
                startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.packageName)));
            } catch (RuntimeException ignored) {
            }
        }));
        PocketSounds.play(this, PocketSounds.OPEN);
        showConsolePanel(e.label, getString(typeLabel(e.type)), null, items);
    }

    /** L1 / R1: the other section. */
    @Override
    protected boolean onShoulder(int direction) {
        return SectionBar.step(this, SectionBar.ANDROID, direction);
    }

    /** L2 / R2: what the row shows (all, games, emulators, the device's settings). */
    @Override
    protected boolean onTrigger(int direction) {
        filter = (filter + direction + FILTER_COUNT) % FILTER_COUNT;
        PocketSounds.play(this, PocketSounds.NAV);
        show();
        TextView section = findViewById(R.id.pocketSectionTitle);
        if (section != null) {
            PocketMotion.bump(section);
        }
        return true;
    }

    // ---- Cards ------------------------------------------------------------------------------

    /** The app's icon on a card of its own colour. */
    private Bitmap cardFor(Entry e, int w, int h) {
        Bitmap cached = ICONS.get(e.key);
        if (cached != null) {
            return cached;
        }
        Drawable icon;
        try {
            icon = e.type == TYPE_SETTING ? getDrawable(e.settingsIcon) : getPackageManager().getApplicationIcon(e.packageName);
        } catch (PackageManager.NameNotFoundException | RuntimeException ex) {
            return null;
        }
        if (icon == null) {
            return null;
        }
        Bitmap card = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(card);
        int size = Math.round(w * 0.52f);
        // The icon's own colour, as a soft gradient behind it
        Bitmap small = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(small);
        icon.setBounds(0, 0, 8, 8);
        icon.draw(sc);
        int r = 0, g = 0, b = 0, n = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int px = small.getPixel(x, y);
                if (Color.alpha(px) > 128) {
                    r += Color.red(px);
                    g += Color.green(px);
                    b += Color.blue(px);
                    n++;
                }
            }
        }
        small.recycle();
        int base = n > 0 ? Color.rgb(r / n, g / n, b / n) : 0xFF3A3A44;
        if (e.type == TYPE_SETTING) {
            base = 0xFF2E3440;
        }
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, 0, h, darker(base, 0.75f), darker(base, 0.32f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);
        int left = (w - size) / 2;
        int top = Math.round(h * 0.30f);
        icon.setBounds(left, top, left + size, top + size);
        if (e.type == TYPE_SETTING) {
            icon.setTint(0xFFFFFFFF);
        }
        icon.draw(c);
        ICONS.put(e.key, card);
        return card;
    }

    private static int darker(int color, float f) {
        return Color.rgb(Math.round(Color.red(color) * f), Math.round(Color.green(color) * f), Math.round(Color.blue(color) * f));
    }

    private final class Cards extends BaseAdapter {
        private final List<Entry> entries = new ArrayList<>();

        void set(List<Entry> list) {
            entries.clear();
            entries.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Entry getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return entries.get(position).key.hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View card = convertView != null ? convertView
                    : LayoutInflater.from(AndroidAppsActivity.this).inflate(R.layout.app_grid_item, parent, false);
            final Entry e = entries.get(position);
            final ImageView image = card.findViewById(R.id.grid_image);
            final TextView text = card.findViewById(R.id.grid_text);
            View overlay = card.findViewById(R.id.grid_overlay);
            if (overlay != null) {
                overlay.setVisibility(View.GONE);
            }
            text.setText(e.label);
            card.setContentDescription(e.label);
            final String tag = e.key + "|" + PocketArt.version();
            if (tag.equals(image.getTag()) && image.getDrawable() != null) {
                return card;
            }
            image.setTag(tag);
            // A cover picked or found for it first; its icon on a card of its colour otherwise
            Bitmap own = e.packageName != null ? HeroArt.cachedCard(AndroidAppsActivity.this, AndroidLibrary.UUID, e.id(), e.label) : null;
            if (own != null) {
                image.setImageBitmap(own);
                text.setVisibility(View.GONE);
                return card;
            }
            text.setVisibility(View.VISIBLE);
            Bitmap icon = ICONS.get(e.key);
            image.setImageBitmap(icon);
            final float d = getResources().getDisplayMetrics().density;
            final int w = Math.round(108 * d);
            final int h = Math.round(162 * d);
            work.execute(() -> {
                final Bitmap cover = e.packageName != null ? HeroArt.loadCard(AndroidAppsActivity.this, AndroidLibrary.UUID, e.id(), e.label) : null;
                final Bitmap made = cover != null ? cover : cardFor(e, w, h);
                main.post(() -> {
                    if (made != null && tag.equals(image.getTag())) {
                        boolean fresh = image.getDrawable() == null;
                        image.setImageBitmap(made);
                        text.setVisibility(cover != null ? View.GONE : View.VISIBLE);
                        if (fresh && PocketPrefs.animationsEnabled(AndroidAppsActivity.this)) {
                            image.setAlpha(0f);
                            image.animate().alpha(1f).setDuration(200).start();
                        }
                    }
                });
            });
            return card;
        }
    }
}
