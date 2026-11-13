package com.limelight.pocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.pocket.info.GameInfo;
import com.limelight.pocket.info.GameInfoService;
import com.limelight.pocket.info.InfoParsers;
import com.limelight.pocket.ui.AnchoredLayoutManager;
import com.limelight.pocket.ui.HeroArt;
import com.limelight.pocket.ui.PageScrollView;
import com.limelight.pocket.ui.PocketMotion;
import com.limelight.pocket.ui.PocketTheme;
import com.limelight.pocket.ui.PocketUi;
import com.limelight.pocket.ui.RemoteImages;
import com.limelight.pocket.ui.ShimmerLines;
import com.limelight.pocket.ui.Spring;
import com.limelight.pocket.ui.TrailerPlayer;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * A game's page, like a console's game hub: its key art (or a screenshot) full screen with the
 * trailer fading in behind after a moment, its logo, what it is (year, studio, genres, Metacritic),
 * a short description, and big glass buttons (Play / Resume, close the game, trailer, art,
 * favourite). Below: trailers and screenshots (full screen with A), the full description and the
 * details. All of it found online by itself (Steam, then Wikipedia).
 *
 * It opens over the screen it came from: the card the game was picked from grows into the page
 * while the page fades in around it. Playing and closing the game are done by that screen (it
 * holds the PC connection): the page hands the choice back as its result.
 */
public class GamePageActivity extends ConsoleActivity {

    public static final int REQUEST = 7301;
    public static final String EXTRA_UUID = "pocket.page.uuid";
    public static final String EXTRA_APP_ID = "pocket.page.appId";
    public static final String EXTRA_NAME = "pocket.page.name";
    public static final String EXTRA_PC_NAME = "pocket.page.pcName";
    public static final String EXTRA_RUNNING = "pocket.page.running";
    public static final String EXTRA_CAN_QUIT = "pocket.page.canQuit";
    public static final String EXTRA_RECT = "pocket.page.rect";

    public static final String RESULT_ACTION = "pocket.page.action";
    public static final String RESULT_APP_ID = "pocket.page.result.appId";
    public static final String RESULT_CHANGED = "pocket.page.changed";
    public static final String ACTION_PLAY = "play";
    public static final String ACTION_QUIT = "quit";

    /** The card's picture, so the page opens from it without a frame of nothing. */
    private static WeakReference<Bitmap> sharedCover;

    /** Opens a game's page from a card (the card grows into the page). */
    public static void open(Activity from, View card, String uuid, int appId, String name, String pcName,
                            boolean running, boolean canQuit, Bitmap cover) {
        Intent intent = new Intent(from, GamePageActivity.class);
        intent.putExtra(EXTRA_UUID, uuid);
        intent.putExtra(EXTRA_APP_ID, appId);
        intent.putExtra(EXTRA_NAME, name);
        intent.putExtra(EXTRA_PC_NAME, pcName);
        intent.putExtra(EXTRA_RUNNING, running);
        intent.putExtra(EXTRA_CAN_QUIT, canQuit);
        if (card != null && card.isAttachedToWindow()) {
            // Where the card really is on screen (its zoom when focused included)
            Rect onScreen = new Rect();
            if (card.getGlobalVisibleRect(onScreen) && onScreen.width() > 0 && onScreen.height() > 0) {
                intent.putExtra(EXTRA_RECT, onScreen);
            }
        }
        sharedCover = cover != null ? new WeakReference<>(cover) : null;
        PocketSounds.play(from, PocketSounds.OPEN);
        from.startActivityForResult(intent, REQUEST);
        from.overridePendingTransition(0, 0);
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    private String uuid;
    private int appId;
    private String name;
    private String pcName;
    private boolean running;
    private boolean canQuit;
    private boolean changed;
    private boolean closing;
    private GameInfo info;
    private boolean infoLoaded;

    private View backdrop;
    private ImageView art;
    private ImageView blur;
    private TextureView trailerView;
    private View scrimDeep;
    private PageScrollView scroll;
    private View content;
    private LinearLayout hero;
    private TextView eyebrow;
    private ImageView logo;
    private TextView title;
    private TextView metacritic;
    private TextView metaLine;
    private TextView shortText;
    private ShimmerLines skeleton;
    private TextView play;
    private TextView quit;
    private TextView trailerButton;
    private TextView artButton;
    private TextView favButton;
    private TextView trophyButton;
    private TextView homeButton;
    private com.limelight.pocket.home.HomeItem homeItem;
    private TextView more;
    private TextView mediaLabel;
    private RecyclerView media;
    private View infoRow;
    private View about;
    private TextView aboutText;
    private TextView source;
    private View details;
    private LinearLayout detailRows;
    private View viewer;
    private ImageView viewerImage;
    private TextureView viewerVideo;
    private View viewerSpinner;
    private TextView viewerCaption;
    private TextView viewerCount;

    private TrailerPlayer backgroundTrailer;
    private TrailerPlayer viewerTrailer;
    private boolean backgroundShowing;
    private final Runnable startBackgroundTrailer = this::startBackgroundTrailer;
    private Bitmap artShown;

    private final List<MediaItem> items = new ArrayList<>();
    private MediaAdapter mediaAdapter;
    private int viewerIndex = -1;

    /** One card of the media row: a trailer or a screenshot. */
    private static final class MediaItem {
        final GameInfo.Movie movie;
        final GameInfo.Shot shot;

        MediaItem(GameInfo.Movie movie, GameInfo.Shot shot) {
            this.movie = movie;
            this.shot = shot;
        }

        String thumb() {
            return movie != null ? movie.thumb : shot.thumb;
        }
    }

    // ---- Lifecycle ----------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        uuid = intent.getStringExtra(EXTRA_UUID);
        appId = intent.getIntExtra(EXTRA_APP_ID, 0);
        name = intent.getStringExtra(EXTRA_NAME);
        pcName = intent.getStringExtra(EXTRA_PC_NAME);
        running = intent.getBooleanExtra(EXTRA_RUNNING, false);
        canQuit = intent.getBooleanExtra(EXTRA_CAN_QUIT, false);
        if (uuid == null || name == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_game_page);
        findViews();
        buildSections();
        backgroundTrailer = new TrailerPlayer(this, trailerView);
        viewerTrailer = new TrailerPlayer(this, viewerVideo);

        // The hero fills the screen; the rest waits below it
        final View root = findViewById(R.id.pocketRoot);
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                int h = root.getHeight();
                if (h > 0 && hero.getMinimumHeight() != h - dp(18)) {
                    hero.setMinimumHeight(h - dp(18));
                }
            }
        });

        more.setText("▾  " + getString(R.string.pocket_page_more));
        setupButtons();
        setupMedia();
        setupScroll();
        showIdentity();
        showArt();
        updateHints();

        skeleton.setVisibility(View.VISIBLE);
        shortText.setAlpha(0f);
        GameInfoService.get(this, name, false, this::onInfo);

        playPageEntrance();
    }

    @Override
    protected boolean allowsAutoResume() {
        return false;
    }

    @Override
    protected void playEntrance() {
        // The page has its own entrance (it grows out of the card)
    }

    @Override
    protected void playEntrance(boolean fromBoot) {
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (infoLoaded && viewerIndex < 0) {
            scheduleBackgroundTrailer(1600);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(startBackgroundTrailer);
        stopBackgroundTrailer(false);
        if (viewerTrailer != null) {
            viewerTrailer.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        if (backgroundTrailer != null) {
            backgroundTrailer.release();
        }
        if (viewerTrailer != null) {
            viewerTrailer.release();
        }
    }

    private void findViews() {
        backdrop = findViewById(R.id.pocketBackdrop);
        art = findViewById(R.id.pageArt);
        blur = findViewById(R.id.pageBlur);
        trailerView = findViewById(R.id.pageTrailer);
        scrimDeep = findViewById(R.id.pageScrimDeep);
        scroll = findViewById(R.id.pageScroll);
        content = findViewById(R.id.pageContent);
        hero = findViewById(R.id.pageHero);
        eyebrow = findViewById(R.id.pageEyebrow);
        logo = findViewById(R.id.pageLogo);
        title = findViewById(R.id.pageTitle);
        metacritic = findViewById(R.id.pageMetacritic);
        metaLine = findViewById(R.id.pageMetaLine);
        shortText = findViewById(R.id.pageShort);
        skeleton = findViewById(R.id.pageSkeleton);
        play = findViewById(R.id.pagePlay);
        quit = findViewById(R.id.pageQuit);
        trailerButton = findViewById(R.id.pageTrailerButton);
        artButton = findViewById(R.id.pageArtButton);
        favButton = findViewById(R.id.pageFavButton);
        trophyButton = findViewById(R.id.pageTrophyButton);
        homeButton = findViewById(R.id.pageHomeButton);
        more = findViewById(R.id.pageMore);
        mediaLabel = findViewById(R.id.pageMediaLabel);
        media = findViewById(R.id.pageMedia);
        infoRow = findViewById(R.id.pageInfoRow);
        about = findViewById(R.id.pageAbout);
        aboutText = findViewById(R.id.pageAboutText);
        source = findViewById(R.id.pageSource);
        details = findViewById(R.id.pageDetails);
        detailRows = findViewById(R.id.pageDetailRows);
        viewer = findViewById(R.id.pageViewer);
        viewerImage = findViewById(R.id.viewerImage);
        viewerVideo = findViewById(R.id.viewerVideo);
        viewerSpinner = findViewById(R.id.viewerSpinner);
        viewerCaption = findViewById(R.id.viewerCaption);
        viewerCount = findViewById(R.id.viewerCount);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // ---- Who it is ----------------------------------------------------------------------------

    private void showIdentity() {
        title.setText(name);
        StringBuilder top = new StringBuilder();
        if (pcName != null) {
            top.append(pcName);
        }
        if (running) {
            if (top.length() > 0) {
                top.append("  ·  ");
            }
            top.append(getString(R.string.pocket_now_playing));
        }
        eyebrow.setText(top);
        eyebrow.setVisibility(top.length() == 0 ? View.GONE : View.VISIBLE);
        metaLine.setText(playtimeLine());
        // The logo in place of the name, when there is a readable one
        PocketArt.load(this, uuid, appId, PocketArt.LOGO, bitmap -> {
            if (isFinishing() || bitmap == null || !PocketArt.logosForNames(this) || PocketArt.isDarkLogo(bitmap)) {
                return;
            }
            logo.setImageBitmap(bitmap);
            logo.setContentDescription(name);
            logo.setVisibility(View.VISIBLE);
            title.setVisibility(View.GONE);
            if (PocketPrefs.animationsEnabled(this) && hero.getAlpha() > 0f) {
                logo.setAlpha(0f);
                logo.animate().alpha(1f).setDuration(320).setInterpolator(PocketMotion.DECELERATE).start();
            }
        });
    }

    private String playtimeLine() {
        long played = PocketPrefs.getPlaytime(this, uuid, appId);
        long last = PocketPrefs.getLastPlayed(this, uuid, appId);
        StringBuilder line = new StringBuilder();
        if (played >= 60) {
            long hours = played / 3600;
            long minutes = (played % 3600) / 60;
            line.append(getString(R.string.pocket_playtime, hours > 0
                    ? getString(R.string.pocket_playtime_hm, hours, minutes)
                    : getString(R.string.pocket_playtime_m, Math.max(1, minutes))));
        }
        if (last > 0) {
            if (line.length() > 0) {
                line.append("  ·  ");
            }
            line.append(android.text.format.DateUtils.getRelativeTimeSpanString(last,
                    System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS));
        }
        return line.toString();
    }

    // ---- Art ----------------------------------------------------------------------------------

    /** Key art if there is some; the cover (blurred) meanwhile; a screenshot once info arrives. */
    private void showArt() {
        Bitmap cover = sharedCover != null ? sharedCover.get() : null;
        if (cover == null || cover.isRecycled()) {
            cover = HeroArt.cachedCard(this, uuid, appId, name);
        }
        if (cover != null) {
            setBlur(cover);
        }
        else {
            HeroArt.loadCardAsync(this, uuid, appId, name, bitmap -> {
                if (!isFinishing() && bitmap != null) {
                    setBlur(bitmap);
                }
            });
        }
        PocketArt.load(this, uuid, appId, PocketArt.HERO, bitmap -> {
            if (!isFinishing() && bitmap != null) {
                setArt(bitmap);
            }
        });
    }

    private void setBlur(Bitmap cover) {
        blur.setImageBitmap(cover);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            float r = 46 * getResources().getDisplayMetrics().density;
            blur.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
            blur.animate().alpha(0.55f).setDuration(420).start();
        }
        else {
            blur.animate().alpha(0.25f).setDuration(420).start();
        }
    }

    private void setArt(Bitmap bitmap) {
        if (bitmap == artShown) {
            return;
        }
        boolean first = artShown == null;
        artShown = bitmap;
        art.setImageBitmap(bitmap);
        art.setPivotX(art.getWidth() * 0.7f);
        art.setPivotY(0f);
        if (PocketPrefs.animationsEnabled(this)) {
            art.setScaleX(1.08f);
            art.setScaleY(1.08f);
            art.setAlpha(first ? 0f : art.getAlpha());
            art.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(1100)
                    .setInterpolator(PocketMotion.DECELERATE).start();
        }
        else {
            art.setAlpha(1f);
        }
    }

    // ---- Info ---------------------------------------------------------------------------------

    private void onInfo(GameInfo found) {
        if (isFinishing()) {
            return;
        }
        info = found;
        infoLoaded = true;
        // Steam's achievements need the game's Steam id, which comes with its info
        if (trophyButton != null && trophyButton.getVisibility() != View.VISIBLE) {
            updateTrophyButton();
        }
        skeleton.animate().alpha(0f).setDuration(220).withEndAction(() -> skeleton.setVisibility(View.GONE)).start();
        shortText.animate().alpha(1f).setDuration(320).setStartDelay(80).start();
        if (found == null) {
            shortText.setText(GameInfoService.isLookupable(name) ? getString(R.string.pocket_page_no_info) : "");
            shortText.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.pocket_text_dim));
            mediaLabel.setVisibility(View.GONE);
            media.setVisibility(View.GONE);
            aboutText.setText(R.string.pocket_page_no_info_long);
            source.setText("");
            fillDetails(null);
            updateHints();
            return;
        }
        shortText.setText(found.shortDescription != null ? found.shortDescription : firstLine(found.about));
        // What it is, on one line: year, studio, genres
        List<String> parts = new ArrayList<>();
        String year = found.releaseYear();
        if (found.comingSoon) {
            parts.add(getString(R.string.pocket_page_coming_soon));
        }
        else if (year != null) {
            parts.add(year);
        }
        if (!found.developers.isEmpty()) {
            parts.add(found.developers.get(0));
        }
        for (int i = 0; i < Math.min(2, found.genres.size()); i++) {
            parts.add(found.genres.get(i));
        }
        String playtime = playtimeLine();
        if (!playtime.isEmpty()) {
            parts.add(playtime);
        }
        String line = TextUtils.join("  ·  ", parts);
        PocketMotion.swapText(metaLine, line, 0);
        fillRatings(found);
        if (found.metacritic > 0 && (found.ratings == null || found.ratings.isEmpty())) {
            metacritic.setText(String.valueOf(found.metacritic));
            GradientDrawable chip = new GradientDrawable();
            chip.setCornerRadius(dp(4));
            chip.setColor(found.metacritic >= 75 ? 0xFF66CC33 : found.metacritic >= 50 ? 0xFFFFCC33 : 0xFFFF4B4B);
            metacritic.setBackground(chip);
            metacritic.setVisibility(View.VISIBLE);
            PocketMotion.bump(metacritic);
        }
        aboutText.setText(found.about != null && !found.about.isEmpty() ? found.about
                : (found.shortDescription != null ? found.shortDescription : ""));
        source.setText(GameInfo.SOURCE_STEAM.equals(found.source) ? R.string.pocket_page_source_steam
                : GameInfo.SOURCE_IGDB.equals(found.source) ? R.string.pocket_page_source_igdb
                : GameInfo.SOURCE_PLAY.equals(found.source) ? R.string.pocket_page_source_play
                : R.string.pocket_page_source_wiki);
        fillDetails(found);

        items.clear();
        for (GameInfo.Movie m : found.movies) {
            items.add(new MediaItem(m, null));
        }
        for (GameInfo.Shot s : found.screenshots) {
            items.add(new MediaItem(null, s));
        }
        mediaAdapter.notifyDataSetChanged();
        boolean hasMedia = !items.isEmpty();
        mediaLabel.setVisibility(hasMedia ? View.VISIBLE : View.GONE);
        media.setVisibility(hasMedia ? View.VISIBLE : View.GONE);
        trailerButton.setVisibility(found.mainTrailer() != null || found.youtubeTrailer() != null
                ? View.VISIBLE : View.GONE);
        if (trailerButton.getVisibility() == View.VISIBLE) {
            PocketMotion.arrive(trailerButton, 0, -10, 0, 1f);
        }

        // No key art of its own: a screenshot, sharp, does the job (every game gets a backdrop)
        if (artShown == null && !found.screenshots.isEmpty()) {
            int w = getResources().getDisplayMetrics().widthPixels;
            RemoteImages.load(this, found.screenshots.get(0).full, w, bitmap -> {
                if (!isFinishing() && bitmap != null && artShown == null) {
                    setArt(bitmap);
                }
            });
        }
        else if (artShown == null && found.background != null) {
            RemoteImages.load(this, found.background, getResources().getDisplayMetrics().widthPixels, bitmap -> {
                if (!isFinishing() && bitmap != null && artShown == null) {
                    setArt(bitmap);
                }
            });
        }
        updateHints();
        scheduleBackgroundTrailer(2200);
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int nl = text.indexOf('\n');
        return nl > 0 ? text.substring(0, nl) : text;
    }

    private void fillDetails(GameInfo found) {
        detailRows.removeAllViews();
        if (found != null) {
            addDetail(R.string.pocket_page_release, found.comingSoon ? getString(R.string.pocket_page_coming_soon) : found.releaseDate);
            addDetail(R.string.pocket_page_developer, TextUtils.join(", ", found.developers));
            addDetail(R.string.pocket_page_publisher, TextUtils.join(", ", found.publishers));
            addDetail(R.string.pocket_page_genres, TextUtils.join(", ", found.genres));
            if (found.metacritic > 0) {
                addDetail(R.string.pocket_page_metacritic, String.valueOf(found.metacritic));
            }
        }
        long played = PocketPrefs.getPlaytime(this, uuid, appId);
        if (played >= 60) {
            long hours = played / 3600;
            long minutes = (played % 3600) / 60;
            addDetail(R.string.pocket_page_playtime, hours > 0
                    ? getString(R.string.pocket_playtime_hm, hours, minutes)
                    : getString(R.string.pocket_playtime_m, Math.max(1, minutes)));
        }
        long last = PocketPrefs.getLastPlayed(this, uuid, appId);
        addDetail(R.string.pocket_page_last_played, last > 0
                ? android.text.format.DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(),
                android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
                : getString(R.string.pocket_page_never));
        if (pcName != null) {
            addDetail(R.string.pocket_page_pc, pcName);
        }
    }

    private void addDetail(int label, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, 0);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.pocket_text_faint));
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.pocket_text));
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        v.setPadding(0, dp(1), 0, 0);
        row.addView(l);
        row.addView(v);
        detailRows.addView(row);
    }

    // ---- Buttons ------------------------------------------------------------------------------

    private void setupButtons() {
        int accent = PocketTheme.accent(this);
        boolean darkText = luminance(accent) > 0.6f;
        play.setText("▶  " + getString(running ? R.string.pocket_hero_resume : R.string.pocket_hero_play));
        play.setTextColor(darkText ? 0xFF000000 : 0xFFFFFFFF);
        // The accent is the button's own colour, under its text; focus adds a bright ring on top
        com.limelight.pocket.ui.LiveGlass.setFace(play, PocketTheme.withAlpha(accent, 0xF2),
                PocketTheme.withAlpha(accent, 0xC8));
        if (!darkText) {
            play.setShadowLayer(dp(3), 0f, dp(1) / 2f, 0x66000000);
        }
        play.setForeground(buttonFace(accent, true));
        quit.setText("■  " + getString(R.string.pocket_page_close));
        quit.setVisibility(running && canQuit ? View.VISIBLE : View.GONE);
        trailerButton.setText("▷  " + getString(R.string.pocket_page_trailer));
        artButton.setText(R.string.pocket_page_art);
        updateFavourite();
        for (TextView b : new TextView[]{play, quit, trailerButton, artButton, favButton, trophyButton, homeButton}) {
            if (b != play) {
                b.setForeground(buttonFace(accent, false));
            }
            b.setOnFocusChangeListener((v, has) -> {
                float s = has ? 1.06f : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(Spring.UI_DURATION_MS)
                        .setInterpolator(Spring.UI_CURVE).start();
                if (has) {
                    PocketUi.tick(this);
                    glideToSection(0);
                }
            });
        }
        play.setOnClickListener(v -> finishWith(ACTION_PLAY));
        quit.setOnClickListener(v -> finishWith(ACTION_QUIT));
        trailerButton.setOnClickListener(v -> openTrailer());
        artButton.setOnClickListener(v -> {
            PocketSounds.play(this, PocketSounds.SELECT);
            changed = true;
            Intent pick = new Intent(this, ArtPickerActivity.class);
            pick.putExtra(ArtPickerActivity.EXTRA_UUID, uuid);
            pick.putExtra(ArtPickerActivity.EXTRA_APP_ID, appId);
            pick.putExtra(ArtPickerActivity.EXTRA_NAME, name);
            startActivity(pick);
        });
        favButton.setOnClickListener(v -> {
            boolean now = !PocketPrefs.isFavorite(this, uuid, appId);
            PocketPrefs.setFavorite(this, uuid, appId, now);
            changed = true;
            PocketSounds.play(this, now ? PocketSounds.TOGGLE_ON : PocketSounds.TOGGLE_OFF);
            updateFavourite();
            PocketMotion.pop(favButton, 0.86f);
        });
        homeButton.setOnClickListener(v -> {
            if (homeItem == null) {
                return;
            }
            boolean pinned = com.limelight.pocket.home.HomeStore.isPinned(this, homeItem);
            if (pinned) {
                com.limelight.pocket.home.HomeStore.unpin(this, homeItem);
            }
            else {
                com.limelight.pocket.home.HomeStore.pin(this, homeItem);
            }
            PocketSounds.play(this, pinned ? PocketSounds.TOGGLE_OFF : PocketSounds.TOGGLE_ON);
            updateHomeButton();
            PocketMotion.pop(homeButton, 0.9f);
        });
        trophyButton.setOnClickListener(v -> {
            com.limelight.pocket.retro.Achievements.Target t =
                    com.limelight.pocket.retro.Achievements.forPage(this, uuid, appId, name, info);
            if (t != null) {
                com.limelight.pocket.retro.AchievementsActivity.open(this, t, uuid, appId);
            }
        });
        findHomeItem();
        updateTrophyButton();
        play.post(() -> play.requestFocus());
    }

    /** What this page's game is on Home (to add it there right from here), found off the main thread. */
    private void findHomeItem() {
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            com.limelight.pocket.home.HomeItem found = null;
            try {
                if (com.limelight.pocket.retro.RetroLibrary.UUID.equals(uuid)) {
                    com.limelight.pocket.retro.RomScanner.Rom rom = com.limelight.pocket.retro.Achievements.findRom(app, appId);
                    if (rom != null) {
                        found = new com.limelight.pocket.home.HomeItem(com.limelight.pocket.home.HomeItem.RETRO,
                                com.limelight.pocket.retro.RomScanner.searchName(rom.name), rom.system, rom.relPath, "", 0);
                    }
                }
                else if (com.limelight.pocket.apps.AndroidLibrary.UUID.equals(uuid)) {
                    android.content.pm.PackageManager pm = app.getPackageManager();
                    for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
                        if (com.limelight.pocket.apps.AndroidLibrary.idFor(ai.packageName) == appId) {
                            found = new com.limelight.pocket.home.HomeItem(com.limelight.pocket.home.HomeItem.ANDROID,
                                    name, ai.packageName, "", "", 0);
                            break;
                        }
                    }
                }
                else if (uuid != null && uuid.length() >= 32 && uuid.indexOf(':') < 0) {
                    found = new com.limelight.pocket.home.HomeItem(com.limelight.pocket.home.HomeItem.PC,
                            name, uuid, "", "", appId);
                }
            } catch (RuntimeException ignored) {
            }
            final com.limelight.pocket.home.HomeItem item = found;
            main.post(() -> {
                if (!isFinishing()) {
                    homeItem = item;
                    updateHomeButton();
                }
            });
        }, "PocketPageHome").start();
    }

    private void updateHomeButton() {
        if (homeItem == null) {
            homeButton.setVisibility(View.GONE);
            return;
        }
        boolean pinned = com.limelight.pocket.home.HomeStore.isPinned(this, homeItem);
        homeButton.setText(pinned ? "\u2713  " + getString(R.string.pocket_page_on_home) : "+  " + getString(R.string.pocket_page_add_home));
        homeButton.setVisibility(View.VISIBLE);
    }

    /** Trophies: their progress on the button (and up to date in the background). */
    private void updateTrophyButton() {
        final com.limelight.pocket.retro.Achievements.Target t =
                com.limelight.pocket.retro.Achievements.forPage(this, uuid, appId, name, info);
        if (t == null) {
            trophyButton.setVisibility(View.GONE);
            return;
        }
        com.limelight.pocket.retro.Achievements.Set known = com.limelight.pocket.retro.Achievements.cached(this, t);
        showTrophies(known);
        com.limelight.pocket.retro.Achievements.load(this, t, false, set -> {
            if (!isFinishing()) {
                showTrophies(set);
            }
        });
    }

    private void showTrophies(com.limelight.pocket.retro.Achievements.Set set) {
        fillTrophies(set);
        String label = getString(R.string.pocket_trophy_title);
        if (set != null && set.total > 0) {
            label = set.earned + " / " + set.total;
        }
        android.graphics.drawable.Drawable cup = getDrawable(R.drawable.ic_pocket_trophy);
        if (cup != null) {
            cup = cup.mutate();
            cup.setTint(0xFFFFD27A);
            int s = dp(15);
            cup.setBounds(0, 0, s, s);
            trophyButton.setCompoundDrawablesRelative(cup, null, null, null);
            trophyButton.setCompoundDrawablePadding(dp(7));
        }
        trophyButton.setText(label);
        trophyButton.setVisibility(View.VISIBLE);
    }

    private void updateFavourite() {
        boolean fav = PocketPrefs.isFavorite(this, uuid, appId);
        favButton.setText(fav ? "★" : "☆");
        favButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        favButton.setTextColor(fav ? 0xFFFFD54F : androidx.core.content.ContextCompat.getColor(this, R.color.pocket_text));
        favButton.setContentDescription(getString(R.string.pocket_page_favorite));
    }

    private static float luminance(int color) {
        return (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f;
    }

    /**
     * What a glass button wears over the glass: a bright ring when focused (with a faint light on
     * the plain ones). Never a fill over the text: Play's accent is its background.
     */
    private StateListDrawable buttonFace(int accent, boolean primary) {
        StateListDrawable face = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(100));
        focused.setColor(primary ? 0x00000000 : 0x14FFFFFF);
        focused.setStroke(dp(2), 0xF2FFFFFF);
        face.addState(new int[]{android.R.attr.state_focused}, focused);
        face.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0x00000000));
        return face;
    }

    private void finishWith(String action) {
        PocketSounds.play(this, PocketSounds.SELECT);
        Intent result = new Intent();
        result.putExtra(RESULT_ACTION, action);
        result.putExtra(RESULT_APP_ID, appId);
        result.putExtra(RESULT_CHANGED, changed);
        setResult(RESULT_OK, result);
        isResultSet = true;
        close(ACTION_PLAY.equals(action));
    }

    // ---- Media row ----------------------------------------------------------------------------

    private void setupMedia() {
        media.setLayoutManager(new AnchoredLayoutManager(this));
        media.setItemAnimator(null);
        if (media instanceof com.limelight.pocket.ui.CarouselRow) {
            // Cards scrolled past the start dissolve there instead of being cut
            media.setClipChildren(false);
        }
        mediaAdapter = new MediaAdapter();
        media.setAdapter(mediaAdapter);
    }

    private final class MediaAdapter extends RecyclerView.Adapter<MediaHolder> {
        @NonNull
        @Override
        public MediaHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout frame = new FrameLayout(GamePageActivity.this);
            frame.setPadding(dp(6), dp(6), dp(6), dp(6));
            frame.setClipChildren(false);
            frame.setClipToPadding(false);
            frame.setFocusable(true);
            frame.setClickable(true);

            FrameLayout card = new FrameLayout(GamePageActivity.this);
            card.setBackgroundResource(R.drawable.pocket_media_card_bg);
            card.setClipToOutline(true);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
                }
            });
            ImageView image = new ImageView(GamePageActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // Trailers: a play glyph and their name over a dark foot
            View foot = new View(GamePageActivity.this);
            GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0xCC000000, 0x00000000});
            foot.setBackground(shade);
            card.addView(foot, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), Gravity.BOTTOM));
            TextView glyph = new TextView(GamePageActivity.this);
            glyph.setText("▶");
            glyph.setGravity(Gravity.CENTER);
            glyph.setTextColor(0xFFFFFFFF);
            glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            GradientDrawable disc = new GradientDrawable();
            disc.setShape(GradientDrawable.OVAL);
            disc.setColor(0x8C000000);
            disc.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
            glyph.setBackground(disc);
            glyph.setPadding(dp(3), 0, 0, 0);
            card.addView(glyph, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
            TextView label = new TextView(GamePageActivity.this);
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(dp(10), 0, dp(10), dp(8));
            card.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(dp(12));
            ring.setStroke(dp(2.5f), 0xFFFFFFFF);
            StateListDrawable focusRing = new StateListDrawable();
            focusRing.addState(new int[]{android.R.attr.state_focused}, ring);
            frame.setForeground(focusRing);

            frame.addView(card, new FrameLayout.LayoutParams(dp(208), dp(117)));
            final MediaHolder holder = new MediaHolder(frame, image, glyph, foot, label);
            frame.setOnFocusChangeListener((v, has) -> {
                float s = has ? 1.07f : 1f;
                v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(6) : 0).setDuration(Spring.UI_DURATION_MS)
                        .setInterpolator(Spring.UI_CURVE).start();
                if (has) {
                    PocketUi.tick(GamePageActivity.this);
                    glideToSection(1);
                }
            });
            frame.setOnClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    openViewer(pos);
                }
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull MediaHolder holder, int position) {
            MediaItem item = items.get(position);
            boolean movie = item.movie != null;
            holder.glyph.setVisibility(movie ? View.VISIBLE : View.GONE);
            holder.foot.setVisibility(movie ? View.VISIBLE : View.GONE);
            holder.label.setVisibility(movie ? View.VISIBLE : View.GONE);
            holder.label.setText(movie && item.movie.name != null ? item.movie.name : "");
            holder.itemView.setContentDescription(movie ? item.movie.name : getString(R.string.pocket_page_screenshot));
            final String url = item.thumb();
            holder.image.setTag(url);
            Bitmap hit = RemoteImages.cached(url, dp(208));
            holder.image.setImageBitmap(hit);
            if (hit == null) {
                RemoteImages.load(GamePageActivity.this, url, dp(208), bitmap -> {
                    if (url != null && url.equals(holder.image.getTag()) && bitmap != null) {
                        holder.image.setImageBitmap(bitmap);
                        holder.image.setAlpha(0f);
                        holder.image.animate().alpha(1f).setDuration(220).start();
                    }
                });
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static final class MediaHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView glyph;
        final View foot;
        final TextView label;

        MediaHolder(View itemView, ImageView image, TextView glyph, View foot, TextView label) {
            super(itemView);
            this.image = image;
            this.glyph = glyph;
            this.foot = foot;
            this.label = label;
        }
    }

    // ---- Scrolling ----------------------------------------------------------------------------

    private void setupScroll() {
        scroll.setOnScrolled(y -> {
            // The art drifts slower than the page and the page darkens as you go down
            int h = Math.max(1, scroll.getHeight());
            float t = Math.min(1f, y / (float) h);
            art.setTranslationY(-y * 0.35f);
            trailerView.setTranslationY(-y * 0.35f);
            blur.setTranslationY(-y * 0.2f);
            scrimDeep.setAlpha(Math.min(0.9f, t * 1.4f));
        });
        about.setOnFocusChangeListener((v, has) -> {
            if (has) {
                PocketUi.tick(this);
                glideToSection(2);
            }
        });
        details.setOnFocusChangeListener((v, has) -> {
            if (has) {
                PocketUi.tick(this);
                glideToSection(2);
            }
        });
        about.setForeground(panelRing());
        details.setForeground(panelRing());
    }

    private StateListDrawable panelRing() {
        // The same soft glow as every focused thing (no hard outline)
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, com.limelight.pocket.ui.SoftRing.forTheme(this, dp(22), false));
        s.setEnterFadeDuration(160);
        s.setExitFadeDuration(120);
        return s;
    }

    // ---- Achievements and ratings, as sections of the page ------------------------------------

    private LinearLayout ratingsRow;
    private LinearLayout trophyPanel;
    private TextView trophyCount;
    private TextView trophyLast;
    private ProgressLine trophyBar;
    private LinearLayout trophyBadges;
    private LinearLayout ratingsPanel;
    private LinearLayout ratingsGrid;

    /** The hero's ratings line, and the Achievements and Ratings panels above About. */
    private void buildSections() {
        ViewGroup heroGroup = (ViewGroup) hero;
        View metaRow = findViewById(R.id.pageMeta);
        ratingsRow = new LinearLayout(this);
        ratingsRow.setOrientation(LinearLayout.HORIZONTAL);
        ratingsRow.setGravity(Gravity.CENTER_VERTICAL);
        ratingsRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(10);
        heroGroup.addView(ratingsRow, Math.max(0, heroGroup.indexOfChild(metaRow) + 1), rl);

        ViewGroup page = (ViewGroup) content;
        page.setClipChildren(false);
        int at = page.indexOfChild(infoRow);

        // Achievements: how far along, a bar, the latest badges; A opens them all
        trophyPanel = panel();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(sectionTitle(R.string.pocket_page_achievements));
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        trophyCount = new TextView(this);
        trophyCount.setTypeface(com.limelight.pocket.ui.PocketFonts.semibold());
        trophyCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        trophyCount.setTextColor(0xFFFFFFFF);
        head.addView(trophyCount);
        trophyPanel.addView(head);
        trophyBar = new ProgressLine(this);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        bl.topMargin = dp(12);
        trophyPanel.addView(trophyBar, bl);
        trophyBadges = new LinearLayout(this);
        trophyBadges.setOrientation(LinearLayout.HORIZONTAL);
        trophyBadges.setClipChildren(false);
        LinearLayout.LayoutParams tbl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        tbl.topMargin = dp(14);
        trophyPanel.addView(trophyBadges, tbl);
        trophyLast = new TextView(this);
        trophyLast.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        trophyLast.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.pocket_text_dim));
        trophyLast.setMaxLines(1);
        trophyLast.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.topMargin = dp(10);
        trophyPanel.addView(trophyLast, ll);
        trophyPanel.setOnClickListener(v -> {
            com.limelight.pocket.retro.Achievements.Target t =
                    com.limelight.pocket.retro.Achievements.forPage(this, uuid, appId, name, info);
            if (t != null) {
                com.limelight.pocket.retro.AchievementsActivity.open(this, t, uuid, appId);
            }
        });
        page.addView(trophyPanel, at, panelParams());

        // Ratings: every source that rates it, as tiles
        ratingsPanel = panel();
        LinearLayout rhead = new LinearLayout(this);
        rhead.setOrientation(LinearLayout.HORIZONTAL);
        rhead.setGravity(Gravity.CENTER_VERTICAL);
        rhead.addView(sectionTitle(R.string.pocket_page_ratings));
        ratingsPanel.addView(rhead);
        ratingsGrid = new LinearLayout(this);
        ratingsGrid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        gl.topMargin = dp(6);
        ratingsPanel.addView(ratingsGrid, gl);
        page.addView(ratingsPanel, at + 1, panelParams());

        for (final View p : new View[]{trophyPanel, ratingsPanel}) {
            p.setOnFocusChangeListener((v, has) -> {
                if (has) {
                    PocketUi.tick(this);
                    scroll.glideTo(Math.max(0, p.getTop() - dp(22)));
                }
            });
        }
    }

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(20), dp(18), dp(20), dp(18));
        p.setFocusable(true);
        p.setClickable(true);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x991C1C22, 0xB3141418});
        bg.setCornerRadius(dp(22));
        bg.setStroke(Math.max(1, dp(0.8f)), 0x1FFFFFFF);
        p.setBackground(bg);
        makeGlass(p);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            p.setForeground(panelRing());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            p.setDefaultFocusHighlightEnabled(false);
        }
        p.setVisibility(View.GONE);
        return p;
    }

    private LinearLayout.LayoutParams panelParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(40));
        lp.setMarginEnd(dp(40));
        lp.topMargin = dp(14);
        return lp;
    }

    private TextView sectionTitle(int text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setAllCaps(true);
        t.setTypeface(com.limelight.pocket.ui.PocketFonts.medium());
        t.setLetterSpacing(0.2f);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        t.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.pocket_text_dim));
        return t;
    }

    /** The achievements panel: how far along, the bar, the latest badges, the last one earned. */
    private void fillTrophies(com.limelight.pocket.retro.Achievements.Set set) {
        if (trophyPanel == null) {
            return;
        }
        if (set == null || set.total <= 0) {
            trophyPanel.setVisibility(View.GONE);
            return;
        }
        boolean was = trophyPanel.getVisibility() == View.VISIBLE;
        trophyPanel.setVisibility(View.VISIBLE);
        int pct = Math.round(set.earned * 100f / set.total);
        boolean done = set.earned >= set.total;
        trophyCount.setText(getString(R.string.pocket_trophy_count_pct, set.earned, set.total, pct));
        trophyCount.setTextColor(done ? 0xFFFFC94D : 0xFFFFFFFF);
        trophyBar.setColors(done ? 0xFFFFE08A : 0xFF6FB8FF, done ? 0xFFFFC94D : 0xFFC08CFF);
        trophyBar.setFraction(set.earned / (float) set.total, !was);
        trophyBadges.removeAllViews();
        int shown = 0;
        com.limelight.pocket.retro.Achievements.Trophy last = null;
        for (final com.limelight.pocket.retro.Achievements.Trophy t : set.trophies) {
            if (t.earned && t.earnedAt > 0 && (last == null || t.earnedAt > last.earnedAt)) {
                last = t;
            }
            if (shown >= 12) {
                continue;
            }
            final ImageView badge = new ImageView(this);
            badge.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable bb = new GradientDrawable();
            bb.setColor(0xFF24242A);
            bb.setCornerRadius(dp(10));
            badge.setBackground(bb);
            badge.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
                }
            });
            badge.setClipToOutline(true);
            if (!t.earned) {
                android.graphics.ColorMatrix m = new android.graphics.ColorMatrix();
                m.setSaturation(0f);
                badge.setColorFilter(new android.graphics.ColorMatrixColorFilter(m));
                badge.setAlpha(0.45f);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            lp.setMarginEnd(dp(8));
            trophyBadges.addView(badge, lp);
            String url = t.earned ? t.icon : (t.iconLocked != null ? t.iconLocked : t.icon);
            if (url != null) {
                RemoteImages.load(this, url, dp(44), b -> {
                    if (b != null && !isFinishing()) {
                        badge.setImageBitmap(b);
                    }
                });
            }
            if (!was && PocketPrefs.animationsEnabled(this)) {
                badge.setAlpha(0f);
                badge.setTranslationY(dp(6));
                badge.animate().alpha(t.earned ? 1f : 0.45f).translationY(0).setStartDelay(60L + shown * 35L)
                        .setDuration(260).setInterpolator(PocketMotion.DECELERATE).start();
            }
            shown++;
        }
        if (last != null) {
            trophyLast.setText(getString(R.string.pocket_trophy_last, last.title,
                    android.text.format.DateUtils.getRelativeTimeSpanString(last.earnedAt, System.currentTimeMillis(),
                            android.text.format.DateUtils.MINUTE_IN_MILLIS)));
            trophyLast.setVisibility(View.VISIBLE);
        }
        else {
            trophyLast.setVisibility(View.GONE);
        }
    }

    /** The hero's line of ratings (critics, players, one critic) and the Ratings panel (all of them). */
    private void fillRatings(GameInfo found) {
        if (ratingsRow == null) {
            return;
        }
        ratingsRow.removeAllViews();
        ratingsGrid.removeAllViews();
        List<GameInfo.Rating> all = found != null && found.ratings != null ? found.ratings : new ArrayList<>();
        if (all.isEmpty()) {
            ratingsRow.setVisibility(View.GONE);
            ratingsPanel.setVisibility(View.GONE);
            return;
        }
        // The hero: the critics' score, the players', and the best known critic
        List<GameInfo.Rating> top = new ArrayList<>();
        GameInfo.Rating critics = found.criticScore();
        GameInfo.Rating players = found.playerScore();
        if (critics != null) {
            top.add(critics);
        }
        if (players != null) {
            top.add(players);
        }
        for (GameInfo.Rating r : all) {
            if (top.size() >= 3) {
                break;
            }
            if (!r.aggregate && r.score >= 0) {
                top.add(r);
                break;
            }
        }
        for (GameInfo.Rating r : top) {
            ratingsRow.addView(ratingPill(r));
        }
        ratingsRow.setVisibility(top.isEmpty() ? View.GONE : View.VISIBLE);
        if (!top.isEmpty() && PocketPrefs.animationsEnabled(this)) {
            ratingsRow.setAlpha(0f);
            ratingsRow.setTranslationY(dp(4));
            ratingsRow.animate().alpha(1f).translationY(0).setDuration(320).setStartDelay(60)
                    .setInterpolator(PocketMotion.DECELERATE).start();
        }
        // The panel: three to a row
        LinearLayout row = null;
        int i = 0;
        for (GameInfo.Rating r : all) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(10);
                ratingsGrid.addView(row, lp);
            }
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(ratingTile(r), tl);
            i++;
        }
        while (row != null && i % 3 != 0) {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            i++;
        }
        ratingsPanel.setVisibility(View.VISIBLE);
    }

    /** A score in its colour (green / yellow / red), then whose it is. */
    private View ratingPill(GameInfo.Rating r) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(3), dp(3), dp(10), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(7));
        bg.setColor(0x33FFFFFF);
        chip.setBackground(bg);
        TextView box = new TextView(this);
        box.setText(r.value);
        box.setTypeface(com.limelight.pocket.ui.PocketFonts.bold());
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        box.setIncludeFontPadding(false);
        box.setGravity(Gravity.CENTER);
        int color = com.limelight.pocket.info.Ratings.color(r.score);
        box.setTextColor(r.score >= 50 && r.score < 75 ? 0xFF1A1A1A : 0xFFFFFFFF);
        GradientDrawable sq = new GradientDrawable();
        sq.setCornerRadius(dp(5));
        sq.setColor(color);
        box.setBackground(sq);
        box.setPadding(dp(6), dp(4), dp(6), dp(4));
        chip.addView(box);
        TextView label = new TextView(this);
        label.setText(r.source);
        label.setTypeface(com.limelight.pocket.ui.PocketFonts.medium());
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        label.setTextColor(0xF2FFFFFF);
        label.setIncludeFontPadding(false);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.setMarginStart(dp(7));
        chip.addView(label, llp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        chip.setLayoutParams(lp);
        return chip;
    }

    /** One rating in the panel: the score big in its colour, who gave it, and a word more. */
    private View ratingTile(GameInfo.Rating r) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(0, 0, dp(10), 0);
        TextView box = new TextView(this);
        String value = r.value;
        int slash = value.indexOf('/');
        if (slash > 0) {
            // "9.4/10": the score big, "/10" small
            android.text.SpannableString sp = new android.text.SpannableString(value);
            sp.setSpan(new android.text.style.RelativeSizeSpan(0.62f), slash, value.length(), 0);
            box.setText(sp);
        }
        else {
            box.setText(value);
        }
        box.setTypeface(com.limelight.pocket.ui.PocketFonts.bold());
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, value.length() > 4 ? 13f : 15f);
        box.setIncludeFontPadding(false);
        box.setGravity(Gravity.CENTER);
        int color = com.limelight.pocket.info.Ratings.color(r.score);
        box.setTextColor(color);
        GradientDrawable sq = new GradientDrawable();
        sq.setCornerRadius(dp(11));
        sq.setColor((color & 0x00FFFFFF) | 0x24000000);
        sq.setStroke(Math.max(1, dp(1f)), (color & 0x00FFFFFF) | 0x73000000);
        box.setBackground(sq);
        tile.addView(box, new LinearLayout.LayoutParams(dp(56), dp(44)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView src = new TextView(this);
        src.setText(r.source);
        src.setTypeface(com.limelight.pocket.ui.PocketFonts.semibold());
        src.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        src.setTextColor(0xFFFFFFFF);
        src.setMaxLines(1);
        src.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(src);
        String note = r.note;
        if ("critics".equals(note)) {
            note = getString(R.string.pocket_rating_critics);
        }
        else if (note == null && !r.aggregate) {
            note = getString(R.string.pocket_rating_review);
        }
        if (note != null) {
            TextView n = new TextView(this);
            n.setText(note);
            n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            n.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.pocket_text_dim));
            n.setMaxLines(2);
            n.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            nl.topMargin = dp(2);
            words.addView(n, nl);
        }
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wl.setMarginStart(dp(10));
        tile.addView(words, wl);
        return tile;
    }

    /** A thin bar that fills (with a soft glow), for how far along the achievements are. */
    private static final class ProgressLine extends View {
        private final android.graphics.Paint track = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();
        private int from = 0xFF6FB8FF;
        private int to = 0xFFC08CFF;
        private float shown;

        ProgressLine(android.content.Context c) {
            super(c);
            track.setColor(0x24FFFFFF);
        }

        void setColors(int a, int b) {
            from = a;
            to = b;
            invalidate();
        }

        void setFraction(float f, boolean animate) {
            final float target = Math.max(0f, Math.min(1f, f));
            if (animate && PocketPrefs.animationsEnabled(getContext())) {
                android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, target);
                va.setDuration(900);
                va.setStartDelay(150);
                va.setInterpolator(PocketMotion.DECELERATE);
                va.addUpdateListener(an -> {
                    shown = (float) an.getAnimatedValue();
                    invalidate();
                });
                va.start();
            }
            else {
                shown = target;
                invalidate();
            }
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float h = getHeight();
            r.set(0, 0, getWidth(), h);
            c.drawRoundRect(r, h / 2f, h / 2f, track);
            if (shown <= 0f) {
                return;
            }
            r.set(0, 0, Math.max(h, getWidth() * shown), h);
            fill.setShader(new android.graphics.LinearGradient(0, 0, r.right, 0, from, to, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, h / 2f, h / 2f, fill);
        }
    }

    /** 0: the hero, 1: the media row, 2: about and details. */
    private void glideToSection(int section) {
        int y;
        if (section == 0) {
            y = 0;
        }
        else if (section == 1) {
            y = mediaLabel.getTop() - dp(26);
        }
        else {
            int target = infoRow.getTop() - dp(22);
            // Already further down inside a long description: stay there
            if (scroll.targetScroll() > target) {
                return;
            }
            y = target;
        }
        scroll.glideTo(y);
    }

    /** Down / up inside the description scroll through it before the focus moves on. */
    private boolean scrollWithin(View panel, boolean down) {
        int top = infoRow.getTop() + panel.getTop();
        int bottom = infoRow.getTop() + panel.getBottom();
        int viewTop = scroll.targetScroll();
        int viewBottom = viewTop + scroll.getHeight();
        int step = dp(150);
        if (down && bottom > viewBottom - dp(24)) {
            scroll.glideBy(step);
            return true;
        }
        if (!down && top < viewTop + dp(20)) {
            scroll.glideBy(-Math.min(step, viewTop + dp(20) - top + dp(2)));
            return true;
        }
        return false;
    }

    // ---- Background trailer -------------------------------------------------------------------

    private void scheduleBackgroundTrailer(long delay) {
        main.removeCallbacks(startBackgroundTrailer);
        if (info != null && info.mainTrailer() != null && PocketPrefs.trailerAutoplay(this)
                && PocketPrefs.animationsEnabled(this)) {
            main.postDelayed(startBackgroundTrailer, delay);
        }
    }

    private void startBackgroundTrailer() {
        if (isFinishing() || closing || viewerIndex >= 0 || info == null || backgroundShowing) {
            return;
        }
        GameInfo.Movie movie = info.mainTrailer();
        if (movie == null) {
            return;
        }
        backgroundShowing = true;
        backgroundTrailer.play(movie.playable(), true, true, new TrailerPlayer.Listener() {
            @Override
            public void onFirstFrame() {
                if (!isFinishing() && backgroundShowing) {
                    trailerView.animate().alpha(1f).setDuration(1200).setInterpolator(PocketMotion.DECELERATE).start();
                }
            }

            @Override
            public void onError() {
                stopBackgroundTrailer(true);
            }
        });
    }

    private void stopBackgroundTrailer(boolean animate) {
        if (!backgroundShowing) {
            return;
        }
        backgroundShowing = false;
        if (animate) {
            trailerView.animate().alpha(0f).setDuration(380).withEndAction(() -> {
                if (!backgroundShowing) {
                    backgroundTrailer.release();
                }
            }).start();
        }
        else {
            trailerView.animate().cancel();
            trailerView.setAlpha(0f);
            backgroundTrailer.release();
        }
    }

    // ---- Full screen viewer -------------------------------------------------------------------

    /** A trailer that only exists on YouTube (IGDB's): it opens in YouTube. */
    private void openYouTube(GameInfo.Movie movie) {
        PocketSounds.play(this, PocketSounds.SELECT);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://www.youtube.com/watch?v=" + movie.youtube)));
        } catch (RuntimeException e) {
            android.widget.Toast.makeText(this, R.string.pocket_page_no_youtube, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void openTrailer() {
        if (info != null && info.mainTrailer() == null && info.youtubeTrailer() != null) {
            openYouTube(info.youtubeTrailer());
            return;
        }
        if (info == null || info.mainTrailer() == null) {
            return;
        }
        int index = items.indexOf(findMovieItem(info.mainTrailer()));
        openViewer(Math.max(0, index));
    }

    private MediaItem findMovieItem(GameInfo.Movie movie) {
        for (MediaItem item : items) {
            if (item.movie == movie) {
                return item;
            }
        }
        return null;
    }

    private void openViewer(int index) {
        if (index < 0 || index >= items.size()) {
            return;
        }
        GameInfo.Movie movie = items.get(index).movie;
        if (movie != null && movie.youtube != null && movie.playable() == null) {
            openYouTube(movie);
            return;
        }
        PocketSounds.play(this, PocketSounds.OPEN);
        main.removeCallbacks(startBackgroundTrailer);
        stopBackgroundTrailer(true);
        boolean wasOpen = viewerIndex >= 0;
        viewerIndex = index;
        viewer.setVisibility(View.VISIBLE);
        if (!wasOpen) {
            viewer.setAlpha(0f);
            viewer.setScaleX(0.96f);
            viewer.setScaleY(0.96f);
            viewer.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Spring.UI_DURATION_MS)
                    .setInterpolator(Spring.UI_CURVE).start();
        }
        showViewerItem(0);
        updateHints();
    }

    private void showViewerItem(int direction) {
        final MediaItem item = items.get(viewerIndex);
        viewerCount.setText((viewerIndex + 1) + " / " + items.size());
        viewerTrailer.release();
        viewerVideo.animate().cancel();
        viewerVideo.setAlpha(0f);
        if (direction != 0 && PocketPrefs.animationsEnabled(this)) {
            viewerImage.setTranslationX(dp(26) * direction);
            viewerImage.setAlpha(0.3f);
            viewerImage.animate().translationX(0).alpha(1f).setDuration(Spring.UI_DURATION_MS)
                    .setInterpolator(Spring.UI_CURVE).start();
        }
        if (item.movie != null && item.movie.playable() == null) {
            // On YouTube only: its picture here, X opens it there
            viewerCaption.setText(getString(R.string.pocket_page_youtube_caption,
                    item.movie.name != null ? item.movie.name : getString(R.string.pocket_page_trailer)));
            viewerVideo.setVisibility(View.GONE);
            viewerSpinner.setVisibility(View.GONE);
            RemoteImages.load(this, item.movie.thumb, dp(208), bitmap -> {
                if (viewerIndex >= 0 && items.get(viewerIndex) == item) {
                    viewerImage.setImageBitmap(bitmap);
                }
            });
        }
        else if (item.movie != null) {
            viewerCaption.setText(item.movie.name != null ? item.movie.name : getString(R.string.pocket_page_trailer));
            viewerVideo.setVisibility(View.VISIBLE);
            viewerSpinner.setVisibility(View.VISIBLE);
            // Its thumbnail until the video starts
            RemoteImages.load(this, item.movie.thumb, dp(208), bitmap -> {
                if (viewerIndex >= 0 && items.get(viewerIndex) == item) {
                    viewerImage.setImageBitmap(bitmap);
                }
            });
            viewerTrailer.play(item.movie.playable(), false, false, new TrailerPlayer.Listener() {
                @Override
                public void onFirstFrame() {
                    viewerSpinner.setVisibility(View.GONE);
                    viewerVideo.animate().alpha(1f).setDuration(260).start();
                }

                @Override
                public void onError() {
                    viewerSpinner.setVisibility(View.GONE);
                    Toast.makeText(GamePageActivity.this, R.string.pocket_page_trailer_failed, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onEnded() {
                    // On to the next thing, like a gallery
                    if (viewerIndex >= 0 && viewerIndex + 1 < items.size()) {
                        viewerIndex++;
                        showViewerItem(1);
                    }
                }
            });
        }
        else {
            viewerCaption.setText(getString(R.string.pocket_page_screenshot));
            viewerVideo.setVisibility(View.GONE);
            viewerSpinner.setVisibility(View.VISIBLE);
            final int w = getResources().getDisplayMetrics().widthPixels;
            Bitmap small = RemoteImages.cached(item.shot.thumb, dp(208));
            Bitmap full = RemoteImages.cached(item.shot.full, w);
            viewerImage.setImageBitmap(full != null ? full : small);
            if (full != null) {
                viewerSpinner.setVisibility(View.GONE);
            }
            else {
                RemoteImages.load(this, item.shot.full, w, bitmap -> {
                    if (viewerIndex >= 0 && items.get(viewerIndex) == item) {
                        viewerSpinner.setVisibility(View.GONE);
                        if (bitmap != null) {
                            viewerImage.setImageBitmap(bitmap);
                        }
                    }
                });
            }
        }
    }

    private void closeViewer() {
        if (viewerIndex < 0) {
            return;
        }
        PocketSounds.play(this, PocketSounds.BACK);
        int index = viewerIndex;
        viewerIndex = -1;
        viewerTrailer.release();
        viewer.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).setDuration(220)
                .withEndAction(() -> {
                    if (viewerIndex < 0) {
                        viewer.setVisibility(View.GONE);
                        viewerImage.setImageDrawable(null);
                    }
                }).start();
        // Back on the card of what was being looked at
        if (media.getLayoutManager() != null) {
            View card = media.getLayoutManager().findViewByPosition(index);
            if (card != null) {
                card.requestFocus();
            }
            else {
                media.scrollToPosition(index);
            }
        }
        updateHints();
        scheduleBackgroundTrailer(1800);
    }

    // ---- "Not this game?" ---------------------------------------------------------------------

    private void askWhichGame() {
        // The details picker: the same name on Steam, IGDB and Wikipedia, with pictures
        com.limelight.pocket.info.InfoPickerActivity.open(this, name);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == com.limelight.pocket.info.InfoPickerActivity.REQUEST && resultCode == RESULT_OK) {
            reloadInfo();
        }
    }

    /** The details again, from what was just picked. */
    private void reloadInfo() {
        stopBackgroundTrailer(true);
        infoLoaded = false;
        skeleton.setAlpha(1f);
        skeleton.setVisibility(View.VISIBLE);
        shortText.setAlpha(0f);
        GameInfoService.get(this, name, true, this::onInfo);
    }

    private void useSteamGame(int steamId) {
        PocketSounds.play(this, PocketSounds.SELECT);
        GameInfoService.setSteamId(this, name, steamId);
        stopBackgroundTrailer(true);
        infoLoaded = false;
        skeleton.setAlpha(1f);
        skeleton.setVisibility(View.VISIBLE);
        shortText.setAlpha(0f);
        GameInfoService.get(this, name, true, this::onInfo);
    }

    // ---- Opening and closing ------------------------------------------------------------------

    private void playPageEntrance() {
        final boolean animate = PocketPrefs.animationsEnabled(this);
        final View root = findViewById(R.id.pocketRoot);
        final View hints = findViewById(R.id.pocketHintBar);
        if (!animate) {
            backdrop.setAlpha(1f);
            return;
        }
        content.setAlpha(0f);
        hints.setAlpha(0f);
        // The card it was opened from grows into the page
        Rect from = getIntent().getParcelableExtra(EXTRA_RECT);
        Bitmap cover = sharedCover != null ? sharedCover.get() : null;
        final ImageView flyer;
        if (from != null && cover != null && !cover.isRecycled() && root instanceof FrameLayout) {
            flyer = new ImageView(this);
            flyer.setImageBitmap(cover);
            flyer.setScaleType(ImageView.ScaleType.CENTER_CROP);
            flyer.setClipToOutline(true);
            final float radius = dp(12);
            flyer.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(from.width(), from.height());
            lp.leftMargin = from.left;
            lp.topMargin = from.top;
            ((FrameLayout) root).addView(flyer, lp);
        }
        else {
            flyer = null;
        }
        root.post(() -> {
            backdrop.animate().alpha(1f).setDuration(420).setInterpolator(PocketMotion.DECELERATE).start();
            if (flyer != null) {
                // Towards the middle of the screen, growing and dissolving into the page
                float dx = root.getWidth() * 0.62f - (flyer.getLeft() + flyer.getWidth() / 2f);
                float dy = root.getHeight() * 0.42f - (flyer.getTop() + flyer.getHeight() / 2f);
                flyer.animate().translationX(dx * 0.55f).translationY(dy * 0.55f).scaleX(2.1f).scaleY(2.1f)
                        .alpha(0f).setDuration(520).setInterpolator(Spring.UI_CURVE)
                        .withEndAction(() -> ((ViewGroup) root).removeView(flyer)).start();
            }
            content.setAlpha(1f);
            PocketMotion.cascade(hero, 120, 45, 0, 20, 7);
            hints.animate().alpha(1f).setStartDelay(260).setDuration(320).start();
        });
    }

    private void close(boolean quick) {
        if (closing) {
            return;
        }
        closing = true;
        main.removeCallbacks(startBackgroundTrailer);
        if (!PocketPrefs.animationsEnabled(this)) {
            finishNow();
            return;
        }
        View hints = findViewById(R.id.pocketHintBar);
        content.animate().alpha(0f).translationY(dp(14)).setDuration(quick ? 120 : 180)
                .setInterpolator(PocketUi.EASE).start();
        hints.animate().alpha(0f).setDuration(140).start();
        trailerView.animate().alpha(0f).setDuration(160).start();
        backdrop.animate().alpha(0f).setStartDelay(quick ? 40 : 70).setDuration(quick ? 160 : 260)
                .withEndAction(this::finishNow).start();
    }

    private void finishNow() {
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public void finish() {
        if (changed && !isResultSet) {
            Intent result = new Intent();
            result.putExtra(RESULT_CHANGED, true);
            result.putExtra(RESULT_APP_ID, appId);
            setResult(RESULT_OK, result);
        }
        super.finish();
    }

    private boolean isResultSet;

    @Override
    public void onBackPressed() {
        if (viewerIndex >= 0) {
            closeViewer();
            return;
        }
        close(false);
    }

    // ---- Buttons ------------------------------------------------------------------------------

    private void updateHints() {
        List<Hint> hints = new ArrayList<>();
        if (viewerIndex >= 0) {
            hints.add(hintA(this, R.string.pocket_viewer_pause));
            hints.add(hintShoulders(this, R.string.pocket_viewer_browse));
            hints.add(hintB(this, R.string.pocket_viewer_close));
        }
        else {
            hints.add(hintA(this, R.string.pocket_page_select));
            if (info != null && info.mainTrailer() != null) {
                hints.add(hintX(this, R.string.pocket_page_trailer));
            }
            if (GameInfoService.isLookupable(name)) {
                hints.add(hintY(this, R.string.pocket_page_wrong));
            }
            hints.add(hintB(this, R.string.pocket_hint_back));
        }
        setHints(hints.toArray(new Hint[0]));
    }

    @Override
    protected boolean onButtonX() {
        if (viewerIndex < 0) {
            openTrailer();
        }
        else if (viewerIndex < items.size() && items.get(viewerIndex).movie != null
                && items.get(viewerIndex).movie.youtube != null && items.get(viewerIndex).movie.playable() == null) {
            openYouTube(items.get(viewerIndex).movie);
        }
        return true;
    }

    @Override
    protected boolean onShoulder(int direction) {
        if (viewerIndex >= 0) {
            stepViewer(direction);
            return true;
        }
        return false;
    }

    private void stepViewer(int direction) {
        int next = viewerIndex + direction;
        if (next < 0 || next >= items.size()) {
            return;
        }
        PocketUi.tick(this);
        viewerIndex = next;
        showViewerItem(direction);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        if (closing) {
            return true;
        }
        if (viewerIndex >= 0) {
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) {
                        int dir = key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                        if (getWindow().getDecorView().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                            dir = -dir;
                        }
                        stepViewer(dir);
                    }
                    return true;
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (up && items.get(viewerIndex).movie != null) {
                        viewerTrailer.togglePause();
                    }
                    return true;
                case KeyEvent.KEYCODE_BUTTON_B:
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    if (up) {
                        closeViewer();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && items.get(viewerIndex).movie != null) {
                        viewerTrailer.seekBy(key == KeyEvent.KEYCODE_DPAD_UP ? 10_000 : -10_000);
                    }
                    return true;
                case KeyEvent.KEYCODE_BUTTON_L1:
                case KeyEvent.KEYCODE_BUTTON_R1:
                    return super.dispatchKeyEvent(event);
                default:
                    return true;
            }
        }
        if (key == KeyEvent.KEYCODE_BUTTON_Y) {
            if (up) {
                askWhichGame();
            }
            return true;
        }
        if (down && (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP)) {
            View focused = getCurrentFocus();
            if ((focused == about || focused == details) && scrollWithin(focused, key == KeyEvent.KEYCODE_DPAD_DOWN)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
