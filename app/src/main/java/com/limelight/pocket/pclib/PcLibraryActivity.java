package com.limelight.pocket.pclib;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.nvstream.http.NvApp;
import com.limelight.pocket.ConsoleActivity;
import com.limelight.pocket.GamePageActivity;
import com.limelight.pocket.PcCompanion;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.PocketSounds;
import com.limelight.pocket.info.GameInfoService;
import com.limelight.pocket.info.InfoParsers;
import com.limelight.pocket.ui.AnchoredLayoutManager;
import com.limelight.pocket.ui.CarouselAdapter;
import com.limelight.pocket.ui.HeroFocus;
import com.limelight.pocket.ui.KeyArtStage;
import com.limelight.pocket.ui.PocketMotion;
import com.limelight.pocket.ui.PocketUi;
import com.limelight.pocket.ui.RemoteImages;
import com.limelight.pocket.ui.StreamMenuView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The PC's own game library, console style: every game Pocket Nexus PC finds on the PC (Steam,
 * Epic, GOG) and, with a linked Steam account, the games owned but not installed there.
 * A plays (through Apollo's entry for the game, else Apollo's Desktop with the PC starting the
 * game), a game that isn't installed can be installed on the PC from here, L1 / R1 filter.
 *
 * It looks and moves like the library (same layout); the stream itself is started by the screen
 * that opened it (it returns the Apollo app to start).
 */
public class PcLibraryActivity extends ConsoleActivity implements CarouselAdapter.Listener {

    public static final int REQUEST = 7302;
    public static final String EXTRA_UUID = "pocket.pclib.uuid";
    public static final String EXTRA_PC_NAME = "pocket.pclib.pcName";
    public static final String EXTRA_HOST = "pocket.pclib.host";
    /** Result: the Apollo app to stream (its id). */
    public static final String RESULT_APP_ID = "pocket.pclib.startAppId";

    private static final long POLL_MS = 4000;
    private static final long ART_SETTLE_MS = 160;

    public static void open(Activity from, String uuid, String pcName, String host) {
        Intent intent = new Intent(from, PcLibraryActivity.class);
        intent.putExtra(EXTRA_UUID, uuid);
        intent.putExtra(EXTRA_PC_NAME, pcName);
        intent.putExtra(EXTRA_HOST, host);
        from.startActivityForResult(intent, REQUEST);
    }

    private String uuid;
    private String pcName;
    private String host;
    private RecyclerView carousel;
    private CarouselAdapter carouselAdapter;
    private final GamesAdapter adapter = new GamesAdapter();
    private List<PcGame> all = new ArrayList<>();
    private int filter = PcGame.FILTER_ALL;
    private int status = -1;
    private boolean loaded;
    private boolean loading;
    private boolean resumed;
    private KeyArtStage keyArt;
    private int heroPos = -1;
    private String heroKey;
    private String focusKey;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Names of games from other stores -> the Steam app id to borrow their art from ("" none). */
    private static final Map<String, String> ART_IDS = new HashMap<>();

    private final Runnable poll = () -> load(false);

    private final Runnable artTask = new Runnable() {
        @Override
        public void run() {
            PcGame g = gameAt(heroPos);
            if (g == null || isFinishing()) {
                return;
            }
            showArt(g);
        }
    };

    @Override
    protected boolean allowsAutoResume() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uuid = getIntent().getStringExtra(EXTRA_UUID);
        pcName = getIntent().getStringExtra(EXTRA_PC_NAME);
        host = getIntent().getStringExtra(EXTRA_HOST);
        if (uuid == null || host == null) {
            finish();
            return;
        }
        if (pcName == null) {
            pcName = "";
        }
        setContentView(R.layout.activity_app_view);

        TextView title = findViewById(R.id.appListText);
        if (title != null) {
            title.setText(pcName.isEmpty() ? getString(R.string.pocket_pclib_button)
                    : getString(R.string.pocket_pclib_title, pcName));
        }
        // The library's own buttons don't belong here
        hide(R.id.profilesButton);
        hide(R.id.pocketPcLibraryButton);

        carousel = findViewById(R.id.pocketAppCarousel);
        carousel.setLayoutManager(new AnchoredLayoutManager(this));
        carousel.setItemAnimator(null);
        carouselAdapter = new CarouselAdapter(adapter, this, 14);
        carousel.setAdapter(carouselAdapter);
        keyArt = KeyArtStage.find(this);
        heroFocus = HeroFocus.install(this, new HeroFocus.Host() {
            @Override
            public boolean heroCanLift() {
                return carousel != null && carousel.hasFocus();
            }

            @Override
            public void heroPlay() {
                PcGame g = gameAt(heroPos);
                if (g != null) {
                    primary(g);
                }
            }
        }, R.id.appFragmentContainer);

        setHints(hintA(this, R.string.pocket_hint_play),
                hintX(this, R.string.pocket_hint_details),
                hintY(this, R.string.pocket_hint_options),
                hintShoulders(this, R.string.pocket_pclib_hint_filter),
                hintStart(this, R.string.pocket_hint_settings));

        List<PcGame> cached = PcLibrary.cached(uuid);
        if (cached != null) {
            status = PcLibrary.OK;
            show(cached);
        }
        else {
            showMessage(getString(R.string.pocket_pclib_loading), null);
        }
        load(false);
    }

    private void hide(int id) {
        View v = findViewById(id);
        if (v != null) {
            v.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (loaded && anyBusy()) {
            handler.removeCallbacks(poll);
            handler.postDelayed(poll, 800);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(poll);
        handler.removeCallbacks(artTask);
    }

    // ---- Loading ----------------------------------------------------------------------------

    private void load(boolean refreshAccount) {
        if (loading) {
            return;
        }
        loading = true;
        PcLibrary.load(this, host, uuid, refreshAccount, result -> {
            loading = false;
            if (isFinishing() || isDestroyed()) {
                return;
            }
            boolean first = !loaded;
            loaded = true;
            status = result.status;
            show(result.games);
            if (first && result.status != PcLibrary.OK && result.games.isEmpty()) {
                explainHelper();
            }
            handler.removeCallbacks(poll);
            if (anyBusy() && resumed) {
                handler.postDelayed(poll, POLL_MS);
            }
        });
    }

    private boolean anyBusy() {
        for (PcGame g : all) {
            if (g.isBusy()) {
                return true;
            }
        }
        return false;
    }

    /** Puts a new list up, keeping the focus on the same game when it is still there. */
    private void show(List<PcGame> games) {
        for (PcGame g : games) {
            if (g.artSteamId.isEmpty()) {
                String known = ART_IDS.get(PcGame.normalize(g.name));
                if (known != null) {
                    g.artSteamId = known;
                }
            }
        }
        all = games;
        String keep = focusKey;
        List<PcGame> shown = PcGame.filter(all, filter);
        adapter.set(shown);
        updateSection();
        if (shown.isEmpty()) {
            heroPos = -1;
            heroKey = null;
            showMessage(emptyMessage(), null);
            return;
        }
        int target = 0;
        if (keep != null) {
            for (int i = 0; i < shown.size(); i++) {
                if (shown.get(i).key().equals(keep)) {
                    target = i;
                    break;
                }
            }
        }
        if (carousel.hasFocus() && heroPos == target) {
            // Same game, new details (an install moving on)
            updateHero(target, false);
            return;
        }
        focusAt(target);
    }

    private String emptyMessage() {
        if (status == PcLibrary.OK || all.size() > 0) {
            return getString(filter == PcGame.FILTER_ALL ? R.string.pocket_pclib_empty : R.string.pocket_pclib_empty_filter);
        }
        return getString(statusMessage());
    }

    private int statusMessage() {
        switch (status) {
            case PcLibrary.NOT_LINKED:
                return R.string.pocket_pclib_not_linked;
            case PcLibrary.OLD_HELPER:
                return R.string.pocket_pclib_old_helper;
            case PcLibrary.NO_HELPER:
                return R.string.pocket_pclib_no_helper;
            default:
                return R.string.pocket_pclib_loading;
        }
    }

    private void focusAt(final int position) {
        carousel.scrollToPosition(position);
        focusWhenLaidOut(position, 0);
    }

    /** The card may not exist until the row has laid out: tries again for a few frames. */
    private void focusWhenLaidOut(final int position, final int attempt) {
        carousel.post(() -> {
            if (isFinishing() || position >= adapter.getCount()) {
                return;
            }
            RecyclerView.ViewHolder holder = carousel.findViewHolderForAdapterPosition(position);
            if (holder != null) {
                holder.itemView.requestFocus();
            }
            else if (attempt < 4) {
                focusWhenLaidOut(position, attempt + 1);
            }
            else {
                updateHero(position, true);
            }
        });
    }

    private PcGame gameAt(int position) {
        return position >= 0 && position < adapter.getCount() ? adapter.getItem(position) : null;
    }

    // ---- The hero ---------------------------------------------------------------------------

    private void updateSection() {
        TextView section = findViewById(R.id.pocketSectionTitle);
        if (section == null) {
            return;
        }
        String label = getString(filterLabel(filter)) + "  ·  " + adapter.getCount();
        if (!label.contentEquals(section.getText())) {
            section.setText(label);
        }
    }

    private static int filterLabel(int filter) {
        switch (filter) {
            case PcGame.FILTER_INSTALLED:
                return R.string.pocket_pclib_filter_installed;
            case PcGame.FILTER_NOT_INSTALLED:
                return R.string.pocket_pclib_filter_not_installed;
            case PcGame.FILTER_STEAM:
                return R.string.pocket_pclib_filter_steam;
            case PcGame.FILTER_OTHER_STORES:
                return R.string.pocket_pclib_filter_other;
            default:
                return R.string.pocket_pclib_filter_all;
        }
    }

    /** No games to tell about: a message in the hero's place. */
    private void showMessage(String title, String sub) {
        TextView t = findViewById(R.id.pocketHeroTitle);
        TextView subtitle = findViewById(R.id.pocketHeroSubtitle);
        TextView play = findViewById(R.id.pocketHeroPlay);
        TextView line = findViewById(R.id.pocketHeroPlaytime);
        ImageView logo = findViewById(R.id.pocketHeroLogo);
        if (t != null) {
            t.setVisibility(View.VISIBLE);
            t.setText(title);
            t.setMaxLines(2);
        }
        if (logo != null) {
            logo.setVisibility(View.GONE);
        }
        if (play != null) {
            play.setVisibility(View.GONE);
        }
        if (subtitle != null) {
            subtitle.setVisibility(sub != null ? View.VISIBLE : View.GONE);
            subtitle.setText(sub);
        }
        if (line != null) {
            line.setVisibility(View.GONE);
        }
        if (keyArt != null) {
            keyArt.show(null);
        }
    }

    private void updateHero(int position, boolean moved) {
        PcGame g = gameAt(position);
        if (g == null) {
            return;
        }
        int dir = heroPos < 0 ? 0 : Integer.signum(position - heroPos);
        TextView title = findViewById(R.id.pocketHeroTitle);
        if (title != null && title.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            dir = -dir;
        }
        boolean newGame = !g.key().equals(heroKey);
        heroPos = position;
        heroKey = g.key();
        focusKey = g.key();

        if (title != null) {
            title.setMaxLines(1);
            if (newGame) {
                PocketMotion.swapText(title, g.name, dir);
            }
        }
        showLogo(g, newGame);

        TextView subtitle = findViewById(R.id.pocketHeroSubtitle);
        if (subtitle != null) {
            String size = PcGame.sizeLabel(g.sizeMb);
            String sub = g.storeLabel() + (size.isEmpty() ? "" : "  ·  " + size);
            subtitle.setVisibility(View.VISIBLE);
            if (!sub.contentEquals(subtitle.getText())) {
                subtitle.setText(sub);
            }
        }
        TextView line = findViewById(R.id.pocketHeroPlaytime);
        if (line != null) {
            String played = PcGame.playtimeLabel(g.playtimeMin);
            String text = played.isEmpty() ? "" : getString(R.string.pocket_pclib_played, played);
            if (!g.isInstalled() && !g.isBusy()) {
                text = text.isEmpty() ? getString(R.string.pocket_pclib_not_on_pc)
                        : text + "  ·  " + getString(R.string.pocket_pclib_not_on_pc);
            }
            line.setText(text);
            line.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        }

        TextView play = findViewById(R.id.pocketHeroPlay);
        if (play != null) {
            String label = primaryLabel(g);
            if (label == null) {
                play.setVisibility(View.GONE);
            }
            else {
                PocketUi.stylePlayPill(play);
                play.setVisibility(View.VISIBLE);
                if (!label.contentEquals(play.getText())) {
                    play.setText(label);
                    if (moved && newGame) {
                        PocketMotion.bump(play);
                    }
                }
            }
        }

        handler.removeCallbacks(artTask);
        if (!newGame) {
            return;
        }
        if (moved && PocketPrefs.animationsEnabled(this)) {
            handler.postDelayed(artTask, ART_SETTLE_MS);
        }
        else {
            artTask.run();
        }
    }

    /** What A does, as the hero's button (null: nothing to do here). */
    private String primaryLabel(PcGame g) {
        if (g.isInstalled()) {
            return "▶  " + getString(R.string.pocket_hero_play);
        }
        if (g.isBusy()) {
            return "⬇  " + getString(R.string.pocket_pclib_installing, Math.max(0, g.progress));
        }
        if (g.installCommand() != null) {
            return "⬇  " + getString(R.string.pocket_pclib_install);
        }
        return null;
    }

    private void showLogo(final PcGame g, boolean newGame) {
        final ImageView logo = findViewById(R.id.pocketHeroLogo);
        final TextView title = findViewById(R.id.pocketHeroTitle);
        if (logo == null || title == null) {
            return;
        }
        if (g.artSteamId.isEmpty()) {
            logo.setVisibility(View.GONE);
            title.setVisibility(View.VISIBLE);
            return;
        }
        final String key = g.key();
        String url = SteamParsers.logoUrl(g.artSteamId);
        int width = Math.round(340 * getResources().getDisplayMetrics().density);
        Bitmap hit = RemoteImages.cached(url, width);
        if (hit != null) {
            logo.setImageBitmap(hit);
            logo.setVisibility(View.VISIBLE);
            title.setVisibility(View.GONE);
            return;
        }
        if (newGame) {
            // The name shows until the logo arrives
            logo.setVisibility(View.GONE);
            title.setVisibility(View.VISIBLE);
        }
        RemoteImages.load(this, url, width, bitmap -> {
            if (!key.equals(heroKey) || isFinishing()) {
                return;
            }
            if (bitmap == null) {
                logo.setVisibility(View.GONE);
                title.setVisibility(View.VISIBLE);
                return;
            }
            logo.setImageBitmap(bitmap);
            if (logo.getVisibility() != View.VISIBLE) {
                logo.setVisibility(View.VISIBLE);
                title.setVisibility(View.GONE);
                if (PocketPrefs.animationsEnabled(this)) {
                    logo.setAlpha(0f);
                    logo.animate().alpha(1f).setDuration(260).start();
                }
            }
        });
    }

    private void showArt(final PcGame g) {
        if (keyArt == null) {
            return;
        }
        if (g.artSteamId.isEmpty()) {
            keyArt.show(null);
            findArtId(g);
            return;
        }
        final String key = g.key();
        int width = getResources().getDisplayMetrics().widthPixels;
        RemoteImages.load(this, SteamParsers.heroUrl(g.artSteamId), width, bitmap -> {
            if (key.equals(heroKey) && !isFinishing()) {
                keyArt.show(bitmap);
            }
        });
    }

    /** A game from another store: Steam often has it too, and then its art can be borrowed. */
    private void findArtId(final PcGame g) {
        final String norm = PcGame.normalize(g.name);
        if (norm.isEmpty() || ART_IDS.containsKey(norm)) {
            return;
        }
        ART_IDS.put(norm, "");
        GameInfoService.searchSteam(g.name, results -> {
            String id = "";
            if (results != null) {
                for (InfoParsers.Candidate c : results) {
                    if (norm.equals(PcGame.normalize(c.name))) {
                        id = String.valueOf(c.id);
                        break;
                    }
                }
            }
            ART_IDS.put(norm, id);
            if (id.isEmpty() || isFinishing()) {
                return;
            }
            for (PcGame other : all) {
                if (other.artSteamId.isEmpty() && norm.equals(PcGame.normalize(other.name))) {
                    other.artSteamId = id;
                }
            }
            adapter.notifyDataSetChanged();
            if (g.key().equals(heroKey)) {
                showLogo(g, false);
                showArt(g);
            }
        });
    }

    // ---- Buttons ----------------------------------------------------------------------------

    @Override
    public void onItemFocused(int position, View itemView) {
        updateHero(position, true);
    }

    @Override
    public void onItemClicked(int position, View itemView) {
        PcGame g = gameAt(position);
        if (g != null) {
            primary(g);
        }
    }

    @Override
    public void onItemOptions(int position, View itemView) {
        final PcGame g = gameAt(position);
        if (g == null) {
            return;
        }
        List<StreamMenuView.Item> items = new ArrayList<>();
        if (g.isInstalled()) {
            items.add(StreamMenuView.Item.action(getString(R.string.pocket_hero_play), () -> primary(g)));
            items.add(StreamMenuView.Item.action(getString(R.string.pocket_pclib_start_on_pc), () -> startOnPcOnly(g)));
        }
        else if (!g.isBusy() && g.installCommand() != null) {
            items.add(StreamMenuView.Item.confirm(getString(R.string.pocket_pclib_install_on, pcName), () -> install(g)));
        }
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_hint_details), () -> openPage(g)));
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_pclib_refresh), () -> {
            showToast(R.string.pocket_pclib_refreshing);
            load(true);
        }));
        items.add(StreamMenuView.Item.action(SteamAccount.linked(this)
                ? getString(R.string.pocket_steam_account_named, SteamAccount.name(this).isEmpty()
                        ? SteamAccount.steamId(this) : SteamAccount.name(this))
                : getString(R.string.pocket_steam_link), () -> askToLink(this, () -> load(true))));
        if (status != PcLibrary.OK) {
            items.add(StreamMenuView.Item.action(getString(R.string.pocket_pclib_helper_help), this::explainHelper));
        }
        PocketSounds.play(this, PocketSounds.OPEN);
        showConsolePanel(g.name, g.storeLabel(), null, items);
    }

    @Override
    protected boolean onButtonX() {
        PcGame g = gameAt(heroPos);
        if (g == null) {
            return false;
        }
        openPage(g);
        return true;
    }

    @Override
    protected boolean onGamePageShortcut() {
        return onButtonX();
    }

    /** L1 / R1: what the row shows (all, installed, not installed, Steam, other stores). */
    @Override
    protected boolean onShoulder(int direction) {
        filter = (filter + direction + PcGame.FILTER_COUNT) % PcGame.FILTER_COUNT;
        PocketSounds.play(this, PocketSounds.NAV);
        TextView section = findViewById(R.id.pocketSectionTitle);
        heroPos = -1;
        heroKey = null;
        show(all);
        if (section != null) {
            PocketMotion.bump(section);
        }
        return true;
    }

    /** L2 / R2: the first / last game. */
    @Override
    protected boolean onTrigger(int direction) {
        if (adapter.getCount() == 0) {
            return false;
        }
        focusAt(direction < 0 ? 0 : adapter.getCount() - 1);
        return true;
    }

    // ---- Playing, installing ------------------------------------------------------------------

    private void primary(PcGame g) {
        if (g.isInstalled()) {
            play(g);
        }
        else if (g.isBusy()) {
            Toast.makeText(this, getString(R.string.pocket_pclib_installing, Math.max(0, g.progress)), Toast.LENGTH_SHORT).show();
        }
        else if (g.installCommand() != null) {
            List<StreamMenuView.Item> items = new ArrayList<>();
            items.add(StreamMenuView.Item.confirm(getString(R.string.pocket_pclib_install_on, pcName), () -> install(g)));
            PocketSounds.play(this, PocketSounds.OPEN);
            showConsolePanel(g.name, getString(R.string.pocket_pclib_install_sub), null, items);
        }
        else {
            Toast.makeText(this, getString(R.string.pocket_pclib_install_elsewhere, g.storeLabel()), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Apollo's entry for the game when it has one; otherwise Apollo's Desktop, and the stream
     * asks the PC to start the game once it's up (so it opens on the streamed screen).
     */
    private void play(PcGame g) {
        NvApp app = PcLibrary.apolloAppFor(this, uuid, g);
        if (app != null) {
            finishWithStart(app.getAppId());
            return;
        }
        if (status != PcLibrary.OK) {
            explainHelper();
            return;
        }
        NvApp desktop = PcLibrary.desktopApp(this, uuid);
        if (desktop == null) {
            List<StreamMenuView.Item> items = new ArrayList<>();
            items.add(StreamMenuView.Item.action(getString(R.string.pocket_pclib_start_on_pc), () -> startOnPcOnly(g)));
            showConsolePanel(g.name, getString(R.string.pocket_pclib_no_desktop), null, items);
            return;
        }
        PcLaunchTicket.arm(uuid, desktop.getAppId(), g.launchCommand(), g.name);
        finishWithStart(desktop.getAppId());
    }

    private void finishWithStart(int appId) {
        PocketSounds.play(this, PocketSounds.SELECT);
        Intent result = new Intent();
        result.putExtra(RESULT_APP_ID, appId);
        setResult(RESULT_OK, result);
        finish();
    }

    private void startOnPcOnly(PcGame g) {
        if (status != PcLibrary.OK) {
            explainHelper();
            return;
        }
        PcLibrary.send(this, host, uuid, g.launchCommand(), reply -> {
            if (reply != null && reply.startsWith("OK")) {
                Toast.makeText(this, getString(R.string.pocket_pclib_started_on_pc, g.name), Toast.LENGTH_SHORT).show();
            }
            else {
                failed(reply);
            }
        });
    }

    private void install(final PcGame g) {
        if (status != PcLibrary.OK) {
            explainHelper();
            return;
        }
        String command = g.installCommand();
        if (command == null) {
            return;
        }
        PcLibrary.send(this, host, uuid, command, reply -> {
            if (reply != null && reply.startsWith("OK")) {
                PocketSounds.play(this, PocketSounds.TOGGLE_ON);
                Toast.makeText(this, R.string.pocket_pclib_install_sent, Toast.LENGTH_LONG).show();
                g.state = PcGame.INSTALLING;
                g.progress = 0;
                adapter.notifyDataSetChanged();
                if (g.key().equals(heroKey)) {
                    updateHero(heroPos, false);
                }
                handler.removeCallbacks(poll);
                handler.postDelayed(poll, POLL_MS);
            }
            else if (reply != null && reply.startsWith("ERR nosteam")) {
                Toast.makeText(this, R.string.pocket_pclib_no_steam, Toast.LENGTH_LONG).show();
            }
            else {
                failed(reply);
            }
        });
    }

    private void failed(String reply) {
        if (reply == null) {
            Toast.makeText(this, R.string.pocket_pclib_no_helper_short, Toast.LENGTH_LONG).show();
        }
        else if (reply.startsWith("ERR auth")) {
            status = PcLibrary.NOT_LINKED;
            explainHelper();
        }
        else if (reply.startsWith("ERR unknown")) {
            Toast.makeText(this, R.string.pocket_pclib_old_helper, Toast.LENGTH_LONG).show();
        }
        else {
            Toast.makeText(this, getString(R.string.pocket_pclib_failed, reply.replaceFirst("^ERR ?", "")),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showToast(int text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    /** Why the PC can't be asked, and what to do. */
    private void explainHelper() {
        List<StreamMenuView.Item> items = new ArrayList<>();
        if (status == PcLibrary.NOT_LINKED) {
            items.add(StreamMenuView.Item.action(getString(R.string.pocket_companion_pin_menu), this::askPin));
        }
        items.add(StreamMenuView.Item.action(getString(R.string.pocket_pclib_try_again), () -> load(false)));
        showConsolePanel(getString(R.string.pocket_pclib_helper_title), getString(statusMessage()), null, items);
    }

    private void askPin() {
        final EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER);
        pin.setHint("000000");
        pin.setTextSize(22);
        pin.setGravity(Gravity.CENTER);
        new AlertDialog.Builder(this)
                .setTitle(R.string.pocket_companion_pin_title)
                .setMessage(R.string.pocket_companion_pin_body)
                .setView(pin)
                .setPositiveButton(android.R.string.ok, (d, w) -> PcCompanion.runAsync(this, host, uuid,
                        "PAIR " + pin.getText().toString().trim(), reply -> {
                            if (reply != null && reply.startsWith("TOKEN")) {
                                Toast.makeText(this, R.string.pocket_companion_linked, Toast.LENGTH_SHORT).show();
                                load(false);
                            }
                            else if (reply == null) {
                                Toast.makeText(this, R.string.pocket_companion_missing, Toast.LENGTH_LONG).show();
                            }
                            else {
                                Toast.makeText(this, reply.startsWith("ERR locked") ? R.string.pocket_companion_locked
                                        : R.string.pocket_companion_wrong_pin, Toast.LENGTH_LONG).show();
                            }
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- The game's page ----------------------------------------------------------------------

    /** Library games have no Apollo id: a stable negative one stands in (art, favourites, info). */
    private static int pageId(PcGame g) {
        return -1 - (g.key().hashCode() & 0x3FFFFFFF);
    }

    private void openPage(PcGame g) {
        if (!g.artSteamId.isEmpty()) {
            try {
                // The page then shows exactly this game's details (only set when it changes:
                // setting it starts the lookup again)
                int steamId = Integer.parseInt(g.artSteamId);
                if (GameInfoService.steamIdFor(this, g.name) != steamId) {
                    GameInfoService.setSteamId(this, g.name, steamId);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        View card = null;
        Bitmap cover = null;
        int pos = adapter.indexOf(g);
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
        GamePageActivity.open(this, card, uuid, pageId(g), g.name, pcName, false, false, cover);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == GamePageActivity.REQUEST && data != null
                && GamePageActivity.ACTION_PLAY.equals(data.getStringExtra(GamePageActivity.RESULT_ACTION))) {
            int id = data.getIntExtra(GamePageActivity.RESULT_APP_ID, 0);
            for (PcGame g : all) {
                if (pageId(g) == id) {
                    primary(g);
                    return;
                }
            }
        }
    }

    // ---- The Steam account --------------------------------------------------------------------

    /** Asks for the Steam account and a Web API key, checks them, links them. */
    public static void askToLink(final Activity activity, final Runnable onLinked) {
        float d = activity.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * d);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText account = new EditText(activity);
        account.setSingleLine(true);
        account.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        account.setHint(R.string.pocket_steam_account_hint);
        account.setText(SteamAccount.steamId(activity));
        final EditText key = new EditText(activity);
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        key.setHint(R.string.pocket_steam_key_hint);
        key.setText(SteamAccount.apiKey(activity));
        box.addView(account);
        box.addView(key);
        AlertDialog.Builder b = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.pocket_steam_link)
                .setMessage(R.string.pocket_steam_link_message)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                    final String a = account.getText().toString();
                    final String k = key.getText().toString();
                    Toast.makeText(activity, R.string.pocket_steam_checking, Toast.LENGTH_SHORT).show();
                    final Handler main = new Handler(Looper.getMainLooper());
                    new Thread(() -> {
                        final int error = SteamAccount.link(activity.getApplicationContext(), a, k);
                        main.post(() -> {
                            if (error == 0) {
                                String name = SteamAccount.name(activity);
                                Toast.makeText(activity, activity.getString(R.string.pocket_steam_linked,
                                        name.isEmpty() ? SteamAccount.steamId(activity) : name), Toast.LENGTH_LONG).show();
                                if (onLinked != null) {
                                    onLinked.run();
                                }
                            }
                            else {
                                Toast.makeText(activity, error, Toast.LENGTH_LONG).show();
                            }
                        });
                    }, "PocketSteamLink").start();
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (SteamAccount.linked(activity)) {
            b.setNeutralButton(R.string.pocket_steam_unlink, (dlg, w) -> {
                SteamAccount.unlink(activity);
                Toast.makeText(activity, R.string.pocket_steam_unlinked, Toast.LENGTH_SHORT).show();
                if (onLinked != null) {
                    onLinked.run();
                }
            });
        }
        b.show();
    }

    // ---- Cards --------------------------------------------------------------------------------

    private final class GamesAdapter extends BaseAdapter {
        private final List<PcGame> games = new ArrayList<>();

        void set(List<PcGame> list) {
            games.clear();
            games.addAll(list);
            notifyDataSetChanged();
        }

        int indexOf(PcGame g) {
            for (int i = 0; i < games.size(); i++) {
                if (games.get(i).key().equals(g.key())) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public int getCount() {
            return games.size();
        }

        @Override
        public PcGame getItem(int position) {
            return games.get(position);
        }

        @Override
        public long getItemId(int position) {
            return games.get(position).key().hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View card = convertView != null ? convertView
                    : LayoutInflater.from(PcLibraryActivity.this).inflate(R.layout.app_grid_item, parent, false);
            final PcGame g = games.get(position);
            final ImageView image = card.findViewById(R.id.grid_image);
            final TextView text = card.findViewById(R.id.grid_text);
            ImageView overlay = card.findViewById(R.id.grid_overlay);
            if (overlay != null) {
                overlay.setVisibility(View.GONE);
            }
            text.setText(g.name);
            card.setContentDescription(g.name);
            badge(card, g);
            // Not on the PC: the cover is dimmed and grey
            boolean onPc = g.isInstalled() || g.isBusy();
            if (onPc) {
                image.clearColorFilter();
                image.setImageAlpha(255);
            }
            else {
                ColorMatrix grey = new ColorMatrix();
                grey.setSaturation(0.15f);
                image.setColorFilter(new ColorMatrixColorFilter(grey));
                image.setImageAlpha(150);
            }
            final String tag = g.key() + "|" + g.artSteamId;
            if (tag.equals(image.getTag()) && image.getDrawable() != null) {
                return card;
            }
            image.setTag(tag);
            image.setImageDrawable(null);
            text.setVisibility(View.VISIBLE);
            if (g.artSteamId.isEmpty()) {
                return card;
            }
            final int width = Math.round(150 * getResources().getDisplayMetrics().density);
            RemoteImages.load(PcLibraryActivity.this, SteamParsers.coverUrl(g.artSteamId), width, bitmap -> {
                if (!tag.equals(image.getTag())) {
                    return;
                }
                if (bitmap != null) {
                    setCover(image, text, bitmap);
                    return;
                }
                // Not every game has the tall cover: the wide header, cropped, still reads well
                RemoteImages.load(PcLibraryActivity.this, SteamParsers.headerUrl(g.artSteamId), width * 2, wide -> {
                    if (tag.equals(image.getTag()) && wide != null) {
                        setCover(image, text, wide);
                    }
                });
            });
            return card;
        }

        private void setCover(ImageView image, TextView text, Bitmap bitmap) {
            image.setImageBitmap(bitmap);
            text.setVisibility(View.GONE);
            if (PocketPrefs.animationsEnabled(PcLibraryActivity.this)) {
                image.setAlpha(0f);
                image.animate().alpha(1f).setDuration(220).start();
            }
        }

        /** The store, and how far an install is (top corner). */
        private void badge(View card, PcGame g) {
            ViewGroup mask = card.findViewById(R.id.grid_mask);
            if (!(mask instanceof RelativeLayout)) {
                return;
            }
            TextView badge = mask.findViewWithTag("pclib_badge");
            if (badge == null) {
                float d = getResources().getDisplayMetrics().density;
                badge = new TextView(PcLibraryActivity.this);
                badge.setTag("pclib_badge");
                badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
                badge.setTextColor(0xFFFFFFFF);
                badge.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
                badge.setBackgroundResource(R.drawable.pocket_badge_bg);
                badge.setPadding(Math.round(7 * d), Math.round(2 * d), Math.round(7 * d), Math.round(2 * d));
                RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                lp.addRule(RelativeLayout.ALIGN_PARENT_START);
                lp.topMargin = Math.round(7 * d);
                lp.setMarginStart(Math.round(7 * d));
                mask.addView(badge, lp);
            }
            String text;
            if (g.isBusy()) {
                text = "⬇ " + Math.max(0, g.progress) + "%";
            }
            else if (!g.isInstalled()) {
                text = "⬇ " + g.storeLabel();
            }
            else {
                text = g.storeLabel();
            }
            badge.setText(text);
        }
    }
}
