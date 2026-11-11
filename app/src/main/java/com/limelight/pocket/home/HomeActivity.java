package com.limelight.pocket.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.format.DateUtils;
import android.util.LruCache;
import android.view.KeyEvent;
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
import com.limelight.nvstream.http.ComputerDetails;
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
import com.limelight.pocket.pclib.PcGame;
import com.limelight.pocket.pclib.PcLibraryActivity;
import com.limelight.pocket.pclib.SteamAccount;
import com.limelight.pocket.retro.RetroActivity;
import com.limelight.pocket.retro.RomScanner;
import com.limelight.pocket.ui.AnchoredLayoutManager;
import com.limelight.pocket.ui.CardTag;
import com.limelight.pocket.ui.CarouselAdapter;
import com.limelight.pocket.ui.HeroArt;
import com.limelight.pocket.ui.HeroFocus;
import com.limelight.pocket.ui.KeyArtStage;
import com.limelight.pocket.ui.LibraryLayout;
import com.limelight.pocket.ui.PlaceholderArt;
import com.limelight.pocket.ui.PocketFonts;
import com.limelight.pocket.ui.PocketMotion;
import com.limelight.pocket.ui.PocketUi;
import com.limelight.pocket.ui.SectionBar;
import com.limelight.pocket.ui.StreamMenuView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home, like a console's: what you played last and what you pinned, from everywhere (games
 * streamed from a PC or a PC's library, cloud services, retro games, Android apps, GameNative /
 * GameHub games and other apps' shortcuts), each started straight from here. DOWN opens the
 * widgets (clock, now playing, notifications, the PCs, the device, Android widgets).
 */
public class HomeActivity extends ConsoleActivity implements CarouselAdapter.Listener {

    private static final int REQUEST_PC_GAMES = 7401;

    private RecyclerView carousel;
    private final Cards adapter = new Cards();
    private KeyArtStage keyArt;
    private int heroPos = -1;
    private String heroKey;
    private int artVersion = PocketArt.version();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private static final LruCache<String, Bitmap> DRAWN = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private WidgetShelf shelf;
    private String pcGamesUuid;
    private boolean resumedOnce;

    /** The last item, "+": adds something to Home. */
    private static final HomeStore.Entry ADD = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_view);
        SectionBar.attach(this, SectionBar.HOME);
        hide(R.id.profilesButton);
        hide(R.id.pocketPcLibraryButton);
        carousel = findViewById(R.id.pocketAppCarousel);
        carousel.setLayoutManager(new AnchoredLayoutManager(this));
        carousel.setItemAnimator(null);
        carousel.setAdapter(new CarouselAdapter(adapter, this, 14));
        LibraryLayout.apply(this, carousel);
        keyArt = KeyArtStage.find(this);
        heroFocus = HeroFocus.install(this, new HeroFocus.Host() {
            @Override
            public boolean heroCanLift() {
                return carousel != null && carousel.hasFocus() && !LibraryLayout.isVertical(HomeActivity.this)
                        && entryAt(heroPos) != null;
            }

            @Override
            public void heroPlay() {
                activate(heroPos);
            }
        }, R.id.appFragmentContainer);
        shelf = new WidgetShelf(this, () -> {
            if (carousel != null) {
                focusAt(Math.max(0, heroPos), 0);
            }
        });
        updateHints();
        TextView section = findViewById(R.id.pocketSectionTitle);
        if (section != null) {
            section.setText(R.string.pocket_home_row);
        }
        heroIdle(getString(R.string.pocket_home_welcome), getString(R.string.pocket_home_welcome_sub));
        load();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (shelf != null) {
            shelf.onStart();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (shelf != null) {
            shelf.onStop();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!resumedOnce) {
            resumedOnce = true;
            return;
        }
        if (artVersion != PocketArt.version()) {
            artVersion = PocketArt.version();
            DRAWN.evictAll();
        }
        load();
        if (shelf != null) {
            shelf.refresh();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        work.shutdownNow();
        if (shelf != null) {
            shelf.onDestroy();
        }
    }

    private void hide(int id) {
        View v = findViewById(id);
        if (v != null) {
            v.setVisibility(View.GONE);
        }
    }

    private void updateHints() {
        setHints(hintA(this, R.string.pocket_hero_play),
                hintX(this, R.string.pocket_hint_details),
                hintY(this, R.string.pocket_hint_options),
                new Hint("▼", 0xFFFFFFFF, getString(R.string.pocket_home_widgets)),
                hintShoulders(this, R.string.pocket_retro_hint_sections));
    }

    private void heroIdle(String text, String sub) {
        TextView t = findViewById(R.id.pocketHeroTitle);
        if (t != null) {
            t.setVisibility(View.VISIBLE);
            t.setText(text);
        }
        TextView s = findViewById(R.id.pocketHeroSubtitle);
        if (s != null) {
            s.setVisibility(sub != null ? View.VISIBLE : View.GONE);
            s.setText(sub);
        }
        hide(R.id.pocketHeroPlay);
        hide(R.id.pocketHeroPlaytime);
        hide(R.id.pocketHeroLogo);
        if (keyArt != null) {
            keyArt.show(null);
        }
    }

    // ---- Loading ------------------------------------------------------------------------------

    private void load() {
        work.execute(() -> {
            final List<HomeStore.Entry> entries = HomeStore.load(this);
            main.post(() -> {
                if (!isFinishing()) {
                    show(entries);
                }
            });
        });
    }

    private void show(List<HomeStore.Entry> entries) {
        String keep = heroKey;
        List<HomeStore.Entry> list = new ArrayList<>(entries);
        list.add(ADD);
        adapter.set(list);
        int target = 0;
        for (int i = 0; keep != null && i < list.size(); i++) {
            HomeStore.Entry e = list.get(i);
            if (e != null && e.item.key().equals(keep)) {
                target = i;
            }
        }
        if (entries.isEmpty()) {
            heroIdle(getString(R.string.pocket_home_empty), getString(R.string.pocket_home_empty_sub));
        }
        heroPos = -1;
        heroKey = null;
        if (shelf == null || !shelf.isOpen()) {
            focusAt(target, 0);
        }
    }

    private void focusAt(final int position, final int attempt) {
        if (attempt == 0) {
            carousel.scrollToPosition(position);
        }
        carousel.post(() -> {
            if (isFinishing() || position >= adapter.getCount() || (shelf != null && shelf.isOpen())) {
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
        if (hasFocus && carousel != null && !carousel.hasFocus() && adapter.getCount() > 0 && !isConsolePanelShowing()
                && (shelf == null || !shelf.isOpen())) {
            focusAt(heroPos >= 0 && heroPos < adapter.getCount() ? heroPos : 0, 0);
        }
    }

    private HomeStore.Entry entryAt(int position) {
        return position >= 0 && position < adapter.getCount() ? adapter.getItem(position) : null;
    }

    // ---- Per kind -------------------------------------------------------------------------------

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

    private String tagLabel(HomeStore.Entry e) {
        HomeItem i = e.item;
        switch (i.kind) {
            case HomeItem.PC:
                return getString(R.string.pocket_home_tag_stream);
            case HomeItem.PC_LIBRARY_GAME:
            case HomeItem.PC_GAMES:
                return "PC";
            case HomeItem.CLOUD:
                return getString(R.string.pocket_section_cloud);
            case HomeItem.RETRO:
                return RetroActivity.shortName(i.a, i.a);
            case HomeItem.ANDROID:
                return getString(R.string.pocket_section_android);
            case HomeItem.NATIVE:
                return HomeLauncher.GAMEHUB_LITE.equals(i.a) ? "GameHub" : "GameNative";
            default:
                return appLabel(i.a);
        }
    }

    private int tagColor(HomeItem i) {
        switch (i.kind) {
            case HomeItem.PC:
                return CardTag.CLOUD;
            case HomeItem.PC_LIBRARY_GAME:
            case HomeItem.PC_GAMES:
                return CardTag.PC;
            case HomeItem.CLOUD:
                return CardTag.CLOUD;
            case HomeItem.RETRO:
                return CardTag.RETRO;
            case HomeItem.ANDROID:
                return CardTag.ANDROID;
            case HomeItem.NATIVE:
                return CardTag.STEAM;
            default:
                return CardTag.SHORTCUT;
        }
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private String subtitle(HomeStore.Entry e) {
        HomeItem i = e.item;
        String where;
        switch (i.kind) {
            case HomeItem.PC:
                where = getString(R.string.pocket_section_streaming) + (e.pcName.isEmpty() ? "" : "  ·  " + e.pcName);
                break;
            case HomeItem.PC_LIBRARY_GAME:
                where = getString(R.string.pocket_pclib_title_short) + (i.c.isEmpty() ? "" : "  ·  " + i.c);
                break;
            case HomeItem.PC_GAMES:
                where = getString(R.string.pocket_home_pc_games_sub);
                break;
            case HomeItem.CLOUD:
                where = getString(R.string.pocket_section_cloud);
                break;
            case HomeItem.RETRO:
                where = getString(R.string.pocket_section_retro) + "  ·  " + RetroActivity.shortName(i.a, i.a);
                break;
            case HomeItem.ANDROID:
                where = getString(R.string.pocket_section_android);
                break;
            case HomeItem.NATIVE:
                where = tagLabel(e) + "  ·  " + (i.b.isEmpty() ? "Steam" : i.b.substring(0, 1) + i.b.substring(1).toLowerCase(Locale.ROOT));
                break;
            default:
                where = getString(R.string.pocket_home_shortcut) + "  ·  " + appLabel(i.a);
                break;
        }
        return where;
    }

    // ---- The hero -------------------------------------------------------------------------------

    private void updateHero(int position) {
        HomeStore.Entry e = entryAt(position);
        TextView title = findViewById(R.id.pocketHeroTitle);
        TextView sub = findViewById(R.id.pocketHeroSubtitle);
        TextView play = findViewById(R.id.pocketHeroPlay);
        TextView when = findViewById(R.id.pocketHeroPlaytime);
        int dir = heroPos < 0 ? 0 : Integer.signum(position - heroPos);
        if (title != null && title.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            dir = -dir;
        }
        String key = e != null ? e.item.key() : "+";
        boolean changed = !key.equals(heroKey);
        heroPos = position;
        heroKey = key;
        if (e == null) {
            if (title != null && changed) {
                title.setVisibility(View.VISIBLE);
                PocketMotion.swapText(title, getString(R.string.pocket_home_add), dir);
            }
            hide(R.id.pocketHeroLogo);
            if (sub != null) {
                sub.setVisibility(View.VISIBLE);
                sub.setText(R.string.pocket_home_add_sub);
            }
            if (play != null) {
                play.setVisibility(View.GONE);
            }
            if (when != null) {
                when.setVisibility(View.GONE);
            }
            if (keyArt != null) {
                keyArt.show(null);
            }
            return;
        }
        String name = displayName(e.item);
        if (title != null && changed) {
            title.setVisibility(View.VISIBLE);
            PocketMotion.swapText(title, name, dir);
        }
        if (sub != null) {
            sub.setVisibility(View.VISIBLE);
            sub.setText(subtitle(e));
        }
        if (play != null) {
            PocketUi.stylePlayPill(play);
            play.setVisibility(View.VISIBLE);
            int label = HomeItem.PC_GAMES.equals(e.item.kind) ? R.string.pocket_apps_open : R.string.pocket_hero_play;
            play.setText("▶  " + getString(label));
        }
        if (when != null) {
            if (e.lastUsed > 0) {
                when.setVisibility(View.VISIBLE);
                when.setText(DateUtils.getRelativeTimeSpanString(e.lastUsed, System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE));
            }
            else {
                when.setVisibility(e.pinned ? View.VISIBLE : View.GONE);
                when.setText(R.string.pocket_home_pinned);
            }
        }
        if (changed) {
            showArt(e);
        }
    }

    private void showArt(final HomeStore.Entry e) {
        final ImageView logo = findViewById(R.id.pocketHeroLogo);
        final TextView title = findViewById(R.id.pocketHeroTitle);
        if (logo != null) {
            logo.setVisibility(View.GONE);
        }
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
            if (key.equals(heroKey) && bitmap != null && logo != null && title != null) {
                logo.setImageBitmap(bitmap);
                logo.setVisibility(View.VISIBLE);
                title.setVisibility(View.GONE);
            }
        });
        // Art for what has none yet (streamed games get theirs on their PC's screens)
        if (!HomeItem.PC.equals(e.item.kind) && !PocketArt.has(this, uuid, id, PocketArt.HERO)) {
            ArtScraper.Item item = new ArtScraper.Item(uuid, id, RomScanner.searchName(displayName(e.item)));
            if (HomeItem.NATIVE.equals(e.item.kind)) {
                item.steamAppId = e.item.n;
            }
            else if (HomeItem.ANDROID.equals(e.item.kind)) {
                item.playPackage = e.item.a;
            }
            ArtScraper.scrape(this, item, false, changed -> {
                if (changed && !isFinishing()) {
                    refreshArt();
                }
            });
        }
    }

    private void refreshArt() {
        DRAWN.evictAll();
        adapter.notifyDataSetChanged();
        if (heroPos >= 0) {
            heroKey = null;
            updateHero(heroPos);
        }
    }

    // ---- Buttons ----------------------------------------------------------------------------

    @Override
    public void onItemFocused(int position, View itemView) {
        updateHero(position);
    }

    @Override
    public void onItemClicked(int position, View itemView) {
        activate(position);
    }

    private void activate(int position) {
        if (position < 0 || position >= adapter.getCount()) {
            return;
        }
        HomeStore.Entry e = entryAt(position);
        if (e == null) {
            showAddPanel();
            return;
        }
        if (HomeItem.PC_GAMES.equals(e.item.kind)) {
            openPcGames(e.item.a, e.item.b, e.item.c);
            return;
        }
        HomeLauncher.launch(this, e.item);
    }

    private void openPcGames(String uuid, String host, String pcName) {
        if (host == null || host.isEmpty()) {
            Toast.makeText(this, R.string.pair_pc_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        pcGamesUuid = uuid;
        PocketSounds.play(this, PocketSounds.OPEN);
        PcLibraryActivity.open(this, uuid, pcName, host);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (shelf != null && shelf.onActivityResult(requestCode, resultCode, data)) {
            return;
        }
        if (requestCode == PcLibraryActivity.REQUEST) {
            String uuid = pcGamesUuid;
            pcGamesUuid = null;
            if (resultCode == RESULT_OK && data != null && uuid != null) {
                int appId = data.getIntExtra(PcLibraryActivity.RESULT_APP_ID, 0);
                if (appId != 0) {
                    HomeLauncher.startOnPc(this, uuid, appId);
                }
            }
            return;
        }
        if (requestCode == GamePageActivity.REQUEST && data != null
                && GamePageActivity.ACTION_PLAY.equals(data.getStringExtra(GamePageActivity.RESULT_ACTION))) {
            activate(heroPos);
        }
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
        RecyclerView.LayoutManager lm = carousel.getLayoutManager();
        View frame = lm != null && heroPos >= 0 ? lm.findViewByPosition(heroPos) : null;
        if (frame instanceof ViewGroup && ((ViewGroup) frame).getChildCount() > 0) {
            card = ((ViewGroup) frame).getChildAt(0);
            ImageView image = card.findViewById(R.id.grid_image);
            if (image != null && image.getDrawable() instanceof BitmapDrawable) {
                cover = ((BitmapDrawable) image.getDrawable()).getBitmap();
                card = image;
            }
        }
        GamePageActivity.open(this, card, artUuid(e.item), artId(e.item), RomScanner.searchName(displayName(e.item)),
                subtitle(e), false, false, cover);
    }

    @Override
    public void onItemOptions(int position, View itemView) {
        final HomeStore.Entry e = entryAt(position);
        List<StreamMenuView.Item> items = new ArrayList<>();
        if (e == null) {
            showAddPanel();
            return;
        }
        final HomeItem item = e.item;
        items.add(StreamMenuView.Item.action(getString(HomeItem.PC_GAMES.equals(item.kind)
                ? R.string.pocket_apps_open : R.string.pocket_hero_play), () -> {
            hideConsolePanel();
            activate(position);
        }));
        if (e.pinned) {
            items.add(StreamMenuView.Item.action(getString(R.string.pocket_home_unpin), () -> {
                HomeStore.unpin(this, item);
                hideConsolePanel();
                load();
            }));
        }
        else {
            items.add(StreamMenuView.Item.action(getString(R.string.pocket_home_pin), () -> {
                HomeStore.pin(this, item);
                PocketSounds.play(this, PocketSounds.TOGGLE_ON);
                hideConsolePanel();
                load();
            }));
        }
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_home_forget), () -> {
            HomeStore.forget(this, item);
            PocketSounds.play(this, PocketSounds.TOGGLE_OFF);
            hideConsolePanel();
            heroKey = null;
            load();
        }));
        final String uuid = artUuid(item);
        if (uuid != null) {
            items.add(StreamMenuView.Item.action(getString(R.string.pocket_hint_details), () -> {
                hideConsolePanel();
                openPage(e);
            }));
            items.add(StreamMenuView.Item.action(getString(R.string.pocket_art_change), () -> {
                hideConsolePanel();
                ArtPickerActivity.open(this, uuid, artId(item), RomScanner.searchName(displayName(item)));
            }));
            items.add(StreamMenuView.Item.action(getString(R.string.pocket_rename), () -> {
                hideConsolePanel();
                rename(uuid, artId(item), displayName(item), item.name);
            }));
        }
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_home_open_section), () -> {
            hideConsolePanel();
            openSection(item);
        }));
        addHomeSettings(items);
        PocketSounds.play(this, PocketSounds.OPEN);
        showConsolePanel(displayName(item), subtitle(e), null, items);
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
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = field.getText().toString().trim();
                    PocketPrefs.setCustomName(this, uuid, id, name.equals(original) ? null : name);
                    heroKey = null;
                    DRAWN.evictAll();
                    adapter.notifyDataSetChanged();
                    updateHero(heroPos);
                })
                .setNeutralButton(R.string.pocket_rename_reset, (d, w) -> {
                    PocketPrefs.setCustomName(this, uuid, id, null);
                    heroKey = null;
                    adapter.notifyDataSetChanged();
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

    private void addHomeSettings(List<StreamMenuView.Item> items) {
        items.add(StreamMenuView.Item.header(getString(R.string.pocket_section_home)));
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_home_add), this::showAddPanel));
        final boolean recent = HomeStore.showRecent(this);
        items.add(StreamMenuView.Item.value(getString(R.string.pocket_home_show_recent),
                getString(recent ? R.string.pocket_on : R.string.pocket_off), direction -> {
                    boolean now = !HomeStore.showRecent(this);
                    HomeStore.setShowRecent(this, now);
                    load();
                    return getString(now ? R.string.pocket_on : R.string.pocket_off);
                }));
        LibraryLayout.addSettings(this, items);
    }

    // ---- Adding to Home -------------------------------------------------------------------------

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
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_home_add_widget), () -> {
            hideConsolePanel();
            shelf.open();
            shelf.showAddPanel();
        }));
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

    private void pushPcPage() {
        work.execute(() -> {
            final List<ComputerDetails> pcs = HomeStore.computers(this);
            main.post(() -> {
                List<StreamMenuView.Item> page = new ArrayList<>();
                for (final ComputerDetails pc : pcs) {
                    if (pc.uuid == null) {
                        continue;
                    }
                    final String host = hostOf(this, pc);
                    page.add(StreamMenuView.Item.action(getString(R.string.pocket_home_pc_games, pc.name), () ->
                            pinAndShow(new HomeItem(HomeItem.PC_GAMES, getString(R.string.pocket_home_pc_games, pc.name),
                                    pc.uuid, host, pc.name, 0))));
                    page.add(StreamMenuView.Item.submenu(getString(R.string.pocket_home_pc_apps, pc.name), () -> {
                        List<StreamMenuView.Item> games = new ArrayList<>();
                        for (final RecentGames.Game g : RecentGames.forPc(this, pc.uuid, 500)) {
                            games.add(StreamMenuView.Item.action(g.app.getAppName(), () ->
                                    pinAndShow(new HomeItem(HomeItem.PC, g.app.getAppName(), pc.uuid, "", "", g.app.getAppId()))));
                        }
                        if (games.isEmpty()) {
                            games.add(StreamMenuView.Item.header(getString(R.string.pocket_home_pc_no_list)));
                        }
                        pushConsolePage(pc.name, games);
                    }));
                }
                if (page.isEmpty()) {
                    page.add(StreamMenuView.Item.header(getString(R.string.pocket_home_no_pcs)));
                }
                page.add(StreamMenuView.Item.header(getString(R.string.pocket_home_add_from_section_pc)));
                pushConsolePage(getString(R.string.pocket_home_add_streamed), page);
            });
        });
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
        page.add(StreamMenuView.Item.header(getString(R.string.pocket_home_native_tip)));
        pushConsolePage(getString(R.string.pocket_home_add_native), page);
    }

    /** The linked Steam account's games, to start in GameNative / GameHub Lite. */
    private void pushOwnedPage(final String launcher) {
        if (!SteamAccount.linked(this)) {
            List<StreamMenuView.Item> page = new ArrayList<>();
            page.add(StreamMenuView.Item.action(getString(R.string.pocket_steam_link),
                    () -> PcLibraryActivity.askToLink(this, null)));
            page.add(StreamMenuView.Item.header(getString(R.string.pocket_home_native_link)));
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
                    page.add(StreamMenuView.Item.header(getString(R.string.pocket_home_native_none)));
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
            page.add(StreamMenuView.Item.header(getString(R.string.pocket_home_shortcuts_need_home)));
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
            page.add(StreamMenuView.Item.header(getString(R.string.pocket_home_no_shortcuts)));
        }
        page.add(StreamMenuView.Item.header(getString(R.string.pocket_home_shortcut_tip)));
        pushConsolePage(getString(R.string.pocket_home_add_shortcut), page);
    }

    // ---- Keys -------------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (shelf != null && shelf.isOpen() && !isConsolePanelShowing()) {
            if (shelf.handleKey(event)) {
                return true;
            }
        }
        else if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0 && carousel != null && carousel.hasFocus() && !isConsolePanelShowing()
                && (heroFocus == null || !heroFocus.isActive()) && !LibraryLayout.isVertical(this)) {
            // DOWN from the row: the widgets come up
            shelf.open();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected boolean onShoulder(int direction) {
        return SectionBar.step(this, SectionBar.HOME, direction);
    }

    @Override
    public void onBackPressed() {
        if (shelf != null && shelf.isOpen()) {
            shelf.close();
            return;
        }
        if (isConsolePanelShowing()) {
            super.onBackPressed();
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

    // ---- Cards ------------------------------------------------------------------------------

    private static int darker(int color, float f) {
        return Color.rgb(Math.round(Color.red(color) * f), Math.round(Color.green(color) * f), Math.round(Color.blue(color) * f));
    }

    /** A card for what has no art: its name (and icon) on a colour. */
    private Bitmap drawCard(String key, String big, Bitmap icon, int color, int w, int h) {
        Bitmap cached = DRAWN.get(key);
        if (cached != null) {
            return cached;
        }
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, w * 0.4f, h, color, darker(color, 0.22f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setShader(new RadialGradient(w * 0.5f, h * 0.36f, w * 0.8f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, glow);
        float textTop;
        if (icon != null) {
            float size = w * 0.5f;
            float left = (w - size) / 2f;
            float top = h * 0.2f;
            c.drawBitmap(icon, null, new android.graphics.RectF(left, top, left + size, top + size),
                    new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
            textTop = top + size + h * 0.1f;
        }
        else {
            textTop = h * 0.42f;
        }
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(0xFFFFFFFF);
        t.setTypeface(icon != null ? PocketFonts.semibold() : PocketFonts.bold());
        t.setTextAlign(Paint.Align.CENTER);
        float size = icon != null ? h * 0.068f : h * 0.1f;
        t.setTextSize(size);
        String[] words = big.split(" ");
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (t.measureText(next) > w * 0.84f && line.length() > 0) {
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
        float y = textTop + size;
        for (int i = 0; i < lines.size() && i < 3; i++) {
            c.drawText(lines.get(i), w / 2f, y, t);
            y += size * 1.2f;
        }
        DRAWN.put(key, b);
        return b;
    }

    private Bitmap iconOf(HomeItem item) {
        try {
            Drawable d = null;
            if (HomeItem.SHORTCUT.equals(item.kind)) {
                Bitmap saved = ShortcutIcons.load(this, item);
                if (saved != null) {
                    return saved;
                }
            }
            if (HomeItem.ANDROID.equals(item.kind) || HomeItem.SHORTCUT.equals(item.kind)) {
                d = getPackageManager().getApplicationIcon(item.a);
            }
            if (d == null) {
                return null;
            }
            int s = Math.max(96, d.getIntrinsicWidth());
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, s, s);
            d.draw(c);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private int colorOf(HomeItem item) {
        switch (item.kind) {
            case HomeItem.CLOUD: {
                CloudService s = CloudService.byId(item.a);
                return s != null ? s.color : 0xFF2B6CB0;
            }
            case HomeItem.PC_GAMES:
                return 0xFF3A3F55;
            case HomeItem.ANDROID:
                return 0xFF1F5E3A;
            case HomeItem.SHORTCUT:
                return 0xFF5A4A1F;
            default:
                return 0xFF2E2F3A;
        }
    }

    private final class Cards extends BaseAdapter {
        private final List<HomeStore.Entry> entries = new ArrayList<>();

        void set(List<HomeStore.Entry> list) {
            entries.clear();
            entries.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public HomeStore.Entry getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            HomeStore.Entry e = entries.get(position);
            return e != null ? e.item.key().hashCode() : -1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View card = convertView != null ? convertView
                    : LayoutInflater.from(HomeActivity.this).inflate(R.layout.app_grid_item, parent, false);
            LibraryLayout.sizeCard(HomeActivity.this, card);
            final HomeStore.Entry e = entries.get(position);
            final ImageView image = card.findViewById(R.id.grid_image);
            final TextView text = card.findViewById(R.id.grid_text);
            View overlay = card.findViewById(R.id.grid_overlay);
            if (overlay != null) {
                overlay.setVisibility(View.GONE);
            }
            ViewGroup mask = card.findViewById(R.id.grid_mask);
            text.setVisibility(View.GONE);
            final float d = getResources().getDisplayMetrics().density;
            final int w = Math.round(LibraryLayout.cardWidthDp(HomeActivity.this) * d);
            final int h = Math.round(LibraryLayout.cardHeightDp(HomeActivity.this) * d);
            if (e == null) {
                CardTag.clear(mask);
                card.setContentDescription(getString(R.string.pocket_home_add));
                image.setTag("+");
                image.setImageBitmap(drawCard("+|" + w + "x" + h, "+  " + getString(R.string.pocket_home_add_short), null,
                        0xFF26262E, w, h));
                return card;
            }
            final HomeItem item = e.item;
            final String name = displayName(item);
            card.setContentDescription(name);
            CardTag.apply(mask, tagLabel(e), tagColor(item), null);
            final String tag = item.key() + "|" + PocketArt.version() + "|" + LibraryLayout.cardStyle(HomeActivity.this);
            if (tag.equals(image.getTag()) && image.getDrawable() != null) {
                return card;
            }
            image.setTag(tag);
            final String uuid = artUuid(item);
            if (uuid == null) {
                image.setImageBitmap(drawCard(item.key() + "|" + w + "x" + h, name, null, colorOf(item), w, h));
                return card;
            }
            final int id = artId(item);
            Bitmap own = HeroArt.cachedCard(HomeActivity.this, uuid, id, name);
            if (own != null) {
                image.setImageBitmap(own);
                return card;
            }
            image.setImageBitmap(null);
            work.execute(() -> {
                Bitmap cover = HeroArt.loadCard(HomeActivity.this, uuid, id, name);
                Bitmap made = cover;
                if (made == null) {
                    Bitmap icon = iconOf(item);
                    made = icon != null ? drawCard(item.key() + "|" + w + "x" + h, name, icon, colorOf(item), w, h)
                            : PlaceholderArt.render(RomScanner.searchName(name), 300);
                }
                final Bitmap shown = made;
                main.post(() -> {
                    if (shown != null && tag.equals(image.getTag())) {
                        boolean fresh = image.getDrawable() == null;
                        image.setImageBitmap(shown);
                        if (fresh && PocketPrefs.animationsEnabled(HomeActivity.this)) {
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
