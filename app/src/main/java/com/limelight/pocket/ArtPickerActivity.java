package com.limelight.pocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.pocket.ui.AuroraView;
import com.limelight.pocket.ui.PocketMotion;
import com.limelight.pocket.ui.PocketTheme;
import com.limelight.pocket.ui.PocketUi;
import com.limelight.pocket.ui.StreamMenuView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Change a game's art from SteamGridDB, with the controller: covers (the library card),
 * backgrounds (the wide key art behind the library) and logos (shown instead of the name).
 * L1 / R1 switch between them, A uses the focused one, Y goes back to the original, X searches
 * under another name. Asks for the person's free SteamGridDB key the first time.
 */
public class ArtPickerActivity extends ConsoleActivity {

    public static final String EXTRA_UUID = "pocket_art_uuid";
    public static final String EXTRA_APP_ID = "pocket_art_app";
    public static final String EXTRA_NAME = "pocket_art_name";

    private static final String KEY_APPLIED = "pocket_art_applied_";
    private static final int[] TAB_LABELS = {R.string.pocket_art_cover, R.string.pocket_art_background, R.string.pocket_art_logo,
            R.string.pocket_art_wide, R.string.pocket_art_icon};
    private static final int[] SPANS = {6, 2, 3, 3, 6};
    private static final float[] RATIOS = {1.5f, 620f / 1920f, 9f / 16f, 430f / 920f, 1f};

    public static void open(Activity from, String uuid, int appId, String name) {
        Intent i = new Intent(from, ArtPickerActivity.class);
        i.putExtra(EXTRA_UUID, uuid);
        i.putExtra(EXTRA_APP_ID, appId);
        i.putExtra(EXTRA_NAME, name);
        from.startActivity(i);
        from.overridePendingTransition(R.anim.pocket_pop_enter, R.anim.pocket_pop_exit);
    }

    // ---- The SteamGridDB key ------------------------------------------------------------------

    private static String clipboardText(Context context) {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                return null;
            }
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return null;
            }
            CharSequence text = clip.getItemAt(0).coerceToText(context);
            return text != null ? text.toString().trim() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Gets the key: straight from the clipboard when it holds one (copied in a browser), else a
     * box to type or paste it into. onSaved runs once a key is stored.
     */
    public static void askForKey(final Activity activity, final Runnable onSaved) {
        String clip = clipboardText(activity);
        if (SteamGridDb.looksLikeKey(clip)) {
            PocketArt.setApiKey(activity, clip);
            Toast.makeText(activity, R.string.pocket_art_key_pasted, Toast.LENGTH_SHORT).show();
            if (onSaved != null) {
                onSaved.run();
            }
            return;
        }
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setHint(R.string.pocket_art_key_hint);
        input.setText(PocketArt.apiKey(activity));
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(activity);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.pocket_art_key_title)
                .setMessage(R.string.pocket_art_key_message)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String key = input.getText().toString().trim();
                    PocketArt.setApiKey(activity, key);
                    if (!key.isEmpty() && onSaved != null) {
                        onSaved.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- State --------------------------------------------------------------------------------

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbs = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> thumbCache = new LruCache<String, Bitmap>(14 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private String uuid;
    private int appId;
    private String name;
    private int kind = PocketArt.COVER;
    private int gameId;
    private String gameLabel = "";
    private int token;
    private boolean busy;
    private final List<SteamGridDb.Image> images = new ArrayList<>();

    private ImageView backdrop;
    private TextView title;
    private TextView matchLine;
    private final TextView[] tabs = new TextView[5];
    private RecyclerView grid;
    private GridLayoutManager layout;
    private TextView status;
    private LinearLayout hintBar;
    private final Adapter adapter = new Adapter();

    @Override
    protected boolean allowsAutoResume() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uuid = getIntent().getStringExtra(EXTRA_UUID);
        appId = getIntent().getIntExtra(EXTRA_APP_ID, 0);
        name = getIntent().getStringExtra(EXTRA_NAME);
        if (uuid == null || appId == 0) {
            finish();
            return;
        }
        if (name == null) {
            name = "";
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.addView(new AuroraView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            backdrop.setRenderEffect(RenderEffect.createBlurEffect(30f, 30f, Shader.TileMode.CLAMP));
        }
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(this);
        shade.setBackgroundResource(R.drawable.pocket_hero_scrim);
        root.addView(shade, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(28), dp(14), dp(28), dp(12));
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Header: what you are changing, and the kinds of art
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        header.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView kicker = new TextView(this);
        kicker.setText(R.string.pocket_art_kicker);
        kicker.setAllCaps(true);
        kicker.setLetterSpacing(0.2f);
        kicker.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        kicker.setTextColor(PocketTheme.accent(this));
        kicker.setTypeface(com.limelight.pocket.ui.PocketFonts.medium());
        words.addView(kicker);
        title = new TextView(this);
        title.setText(name);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(com.limelight.pocket.ui.PocketFonts.regular());
        title.setTextColor(ContextCompat.getColor(this, R.color.pocket_text));
        words.addView(title);
        matchLine = new TextView(this);
        matchLine.setSingleLine(true);
        matchLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        matchLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        matchLine.setTextColor(ContextCompat.getColor(this, R.color.pocket_text_dim));
        words.addView(matchLine);

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabRow.setBackgroundResource(R.drawable.pocket_glass_pill);
        tabRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        TextView l1 = glyph("L1");
        tabRow.addView(l1);
        for (int i = 0; i < tabs.length; i++) {
            final int k = i;
            TextView tab = new TextView(this);
            tab.setText(TAB_LABELS[i]);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tab.setTypeface(com.limelight.pocket.ui.PocketFonts.medium());
            tab.setPadding(dp(11), dp(5), dp(11), dp(5));
            tab.setOnClickListener(v -> switchKind(k));
            tabs[i] = tab;
            tabRow.addView(tab);
        }
        tabRow.addView(glyph("R1"));
        header.addView(tabRow);

        // The art
        FrameLayout body = new FrameLayout(this);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(10);
        column.addView(body, bodyLp);
        grid = new RecyclerView(this);
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setPadding(dp(4), dp(8), dp(4), dp(8));
        grid.setItemAnimator(null);
        layout = new GridLayoutManager(this, SPANS[kind]);
        grid.setLayoutManager(layout);
        grid.setAdapter(adapter);
        body.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setLineSpacing(dp(3), 1f);
        status.setTextColor(ContextCompat.getColor(this, R.color.pocket_text_dim));
        status.setPadding(dp(40), 0, dp(40), 0);
        body.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hintBar = new LinearLayout(this);
        hintBar.setOrientation(LinearLayout.HORIZONTAL);
        hintBar.setBackgroundResource(R.drawable.pocket_glass_pill);
        hintBar.setPadding(dp(2), dp(6), dp(14), dp(6));
        hintBar.setId(R.id.pocketHintBar);
        LinearLayout.LayoutParams hintsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintsLp.gravity = Gravity.END;
        hintsLp.topMargin = dp(6);
        column.addView(hintBar, hintsLp);

        setContentView(root);
        updateTabs();
        updateHints();
        showBackdrop();
        start();
    }

    @Override
    protected void onDestroy() {
        token++;
        work.shutdownNow();
        thumbs.shutdownNow();
        super.onDestroy();
    }

    private TextView glyph(String label) {
        TextView g = new TextView(this);
        g.setText(label);
        g.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        g.setTypeface(com.limelight.pocket.ui.PocketFonts.medium());
        g.setTextColor(ContextCompat.getColor(this, R.color.pocket_text_dim));
        g.setPadding(dp(8), 0, dp(8), 0);
        return g;
    }

    private void updateTabs() {
        int accent = PocketTheme.accent(this);
        for (int i = 0; i < tabs.length; i++) {
            boolean on = i == kind;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(100));
            bg.setColor(on ? accent : 0x00000000);
            tabs[i].setBackground(bg);
            tabs[i].setTextColor(on ? 0xFF000000 : ContextCompat.getColor(this, R.color.pocket_text));
        }
    }

    private void updateHints() {
        List<Hint> hints = new ArrayList<>();
        if (!PocketArt.hasKey(this)) {
            hints.add(hintA(this, R.string.pocket_art_paste_key));
            hints.add(hintX(this, R.string.pocket_art_type_key));
        }
        else {
            hints.add(hintA(this, R.string.pocket_art_use));
            hints.add(hintY(this, R.string.pocket_art_original));
            hints.add(hintX(this, R.string.pocket_art_search));
        }
        // Your own picture (works without SteamGridDB too)
        hints.add(hintStart(this, R.string.pocket_art_from_device));
        hints.add(hintB(this, R.string.pocket_hint_back));
        fillHintBar(this, hintBar, hints.toArray(new Hint[0]));
    }

    /** The game's current background (or cover), softly blurred, behind everything. */
    private void showBackdrop() {
        PocketArt.load(this, uuid, appId, PocketArt.HERO, bitmap -> {
            Bitmap b = bitmap != null ? bitmap : com.limelight.pocket.ui.HeroArt.load(this, uuid, appId);
            if (b != null && !isFinishing()) {
                backdrop.setImageBitmap(b);
                backdrop.animate().alpha(0.35f).setDuration(400).start();
            }
        });
    }

    private void setStatus(CharSequence text) {
        status.setText(text);
        status.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    // ---- Loading ------------------------------------------------------------------------------

    private void start() {
        images.clear();
        adapter.notifyDataSetChanged();
        updateHints();
        if (!PocketArt.hasKey(this)) {
            matchLine.setText(R.string.pocket_art_powered);
            setStatus(getString(R.string.pocket_art_need_key));
            return;
        }
        gameId = PocketArt.matchedGame(this, uuid, appId);
        if (gameId == 0) {
            search(name, true);
        }
        else {
            loadImages();
        }
    }

    private void handleError(IOException e) {
        if (e instanceof SteamGridDb.ApiException && ((SteamGridDb.ApiException) e).badKey()) {
            PocketArt.setApiKey(this, "");
            matchLine.setText(R.string.pocket_art_powered);
            setStatus(getString(R.string.pocket_art_bad_key));
            updateHints();
        }
        else {
            setStatus(getString(R.string.pocket_art_offline));
        }
    }

    /** Finds the game on SteamGridDB; quietly picks the sure match, else lets you choose. */
    private void search(final String term, final boolean quiet) {
        final int my = ++token;
        final String key = PocketArt.apiKey(this);
        setStatus(getString(R.string.pocket_art_searching));
        work.execute(() -> {
            try {
                final List<SteamGridDb.Game> games = SteamGridDb.search(key, term);
                main.post(() -> {
                    if (my != token || isFinishing()) {
                        return;
                    }
                    if (games.isEmpty()) {
                        matchLine.setText(R.string.pocket_art_powered);
                        setStatus(getString(R.string.pocket_art_not_found, term));
                        return;
                    }
                    SteamGridDb.Game sure = null;
                    for (SteamGridDb.Game g : games) {
                        if (SteamGridDb.sameGame(name, g.name)) {
                            sure = g;
                            break;
                        }
                    }
                    if (quiet && sure != null) {
                        choose(sure);
                    }
                    else {
                        offerGames(games);
                    }
                });
            } catch (final IOException e) {
                main.post(() -> {
                    if (my == token && !isFinishing()) {
                        handleError(e);
                    }
                });
            }
        });
    }

    private void offerGames(List<SteamGridDb.Game> games) {
        setStatus(getString(R.string.pocket_art_pick_game));
        List<StreamMenuView.Item> items = new ArrayList<>();
        int max = Math.min(games.size(), 12);
        for (int i = 0; i < max; i++) {
            final SteamGridDb.Game g = games.get(i);
            String label = g.name + (g.year > 0 ? "  (" + g.year + ")" : "") + (g.verified ? "  ✓" : "");
            items.add(StreamMenuView.Item.action(label, () -> {
                hideConsolePanel();
                choose(g);
            }));
        }
        showConsolePanel(getString(R.string.pocket_art_pick_game), name, null, items);
    }

    private void choose(SteamGridDb.Game game) {
        gameId = game.id;
        gameLabel = game.name + (game.year > 0 ? " (" + game.year + ")" : "");
        PocketArt.setMatchedGame(this, uuid, appId, game.id);
        loadImages();
    }

    private void loadImages() {
        final int my = ++token;
        final String key = PocketArt.apiKey(this);
        final int k = kind;
        final int game = gameId;
        images.clear();
        adapter.notifyDataSetChanged();
        layout.setSpanCount(SPANS[k]);
        matchLine.setText(gameLabel.isEmpty() ? getString(R.string.pocket_art_powered)
                : getString(R.string.pocket_art_matched, gameLabel));
        setStatus(getString(R.string.pocket_art_loading));
        work.execute(() -> {
            try {
                final List<SteamGridDb.Image> found = SteamGridDb.images(key, game, k, 0);
                main.post(() -> {
                    if (my != token || isFinishing()) {
                        return;
                    }
                    images.clear();
                    images.addAll(found);
                    adapter.notifyDataSetChanged();
                    setStatus(found.isEmpty() ? getString(R.string.pocket_art_none) : null);
                    if (!found.isEmpty()) {
                        grid.post(() -> {
                            PocketMotion.cascade(grid, 40, 25, 0, 14, 12);
                            View first = layout.findViewByPosition(0);
                            if (first != null) {
                                first.requestFocus();
                            }
                        });
                    }
                });
            } catch (final IOException e) {
                main.post(() -> {
                    if (my == token && !isFinishing()) {
                        handleError(e);
                    }
                });
            }
        });
    }

    private void switchKind(int next) {
        if (next < 0 || next >= tabs.length || next == kind) {
            return;
        }
        kind = next;
        PocketUi.tick(this);
        updateTabs();
        PocketMotion.bump(tabs[kind]);
        if (PocketArt.hasKey(this) && gameId != 0) {
            loadImages();
        }
    }

    private int applied() {
        return PocketPrefs.prefs(this).getInt(KEY_APPLIED + uuid + "_" + appId + "_" + kind, 0);
    }

    private void use(final SteamGridDb.Image image) {
        if (busy) {
            return;
        }
        busy = true;
        final int k = kind;
        PocketSounds.play(this, PocketSounds.SELECT);
        Toast.makeText(this, R.string.pocket_art_saving, Toast.LENGTH_SHORT).show();
        work.execute(() -> {
            IOException error = null;
            try {
                PocketArt.apply(this, uuid, appId, k, image);
            } catch (IOException e) {
                error = e;
            }
            final IOException failed = error;
            main.post(() -> {
                busy = false;
                if (isFinishing()) {
                    return;
                }
                if (failed != null) {
                    Toast.makeText(this, R.string.pocket_art_save_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                PocketPrefs.prefs(this).edit().putInt(KEY_APPLIED + uuid + "_" + appId + "_" + k, image.id).apply();
                PocketSounds.play(this, PocketSounds.TOGGLE_ON);
                Toast.makeText(this, R.string.pocket_art_saved, Toast.LENGTH_SHORT).show();
                adapter.notifyDataSetChanged();
                if (k == PocketArt.HERO) {
                    showBackdrop();
                }
            });
        });
    }

    private void resetKind() {
        PocketArt.reset(this, uuid, appId, kind);
        PocketPrefs.prefs(this).edit().remove(KEY_APPLIED + uuid + "_" + appId + "_" + kind).apply();
        PocketSounds.play(this, PocketSounds.TOGGLE_OFF);
        Toast.makeText(this, R.string.pocket_art_reset_done, Toast.LENGTH_SHORT).show();
        adapter.notifyDataSetChanged();
    }

    private void askSearch() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setText(name);
        input.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        box.addView(input);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.pocket_art_search_title)
                .setView(box)
                .setPositiveButton(R.string.pocket_art_search, (d, w) -> {
                    String term = input.getText().toString().trim();
                    if (!term.isEmpty()) {
                        search(term, false);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- Buttons ------------------------------------------------------------------------------

    @Override
    protected boolean onShoulder(int direction) {
        switchKind(kind + (direction > 0 ? 1 : -1));
        return true;
    }

    @Override
    protected boolean onButtonX() {
        if (!PocketArt.hasKey(this)) {
            askForKey(this, this::start);
        }
        else {
            askSearch();
        }
        return true;
    }

    @Override
    protected void onMenuButton() {
        // START: a picture from this device instead (no settings shortcut here)
        pickFromDevice();
    }

    // ---- Your own pictures --------------------------------------------------------------------

    private static final int REQUEST_PICK_IMAGE = 4711;
    /** Biggest picture accepted (an uncompressed photo can be huge; art never needs more). */
    private static final int MAX_IMPORT_BYTES = 48 * 1024 * 1024;

    /** Opens the system's picker for a picture on the device (or a USB drive, cloud storage...). */
    private void pickFromDevice() {
        if (busy) {
            return;
        }
        PocketSounds.play(this, PocketSounds.OPEN);
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        try {
            startActivityForResult(pick, REQUEST_PICK_IMAGE);
        } catch (RuntimeException e) {
            Intent get = new Intent(Intent.ACTION_GET_CONTENT);
            get.addCategory(Intent.CATEGORY_OPENABLE);
            get.setType("image/*");
            try {
                startActivityForResult(get, REQUEST_PICK_IMAGE);
            } catch (RuntimeException e2) {
                Toast.makeText(this, R.string.pocket_art_device_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_IMAGE && resultCode == Activity.RESULT_OK && data != null
                && data.getData() != null) {
            importImage(data.getData());
        }
    }

    /** Scales the chosen picture for this kind of art and keeps it (like SteamGridDB art). */
    private void importImage(final android.net.Uri uri) {
        if (busy) {
            return;
        }
        busy = true;
        final int k = kind;
        Toast.makeText(this, R.string.pocket_art_saving, Toast.LENGTH_SHORT).show();
        work.execute(() -> {
            boolean ok = false;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        bytes.write(buffer, 0, n);
                        if (bytes.size() > MAX_IMPORT_BYTES) {
                            throw new IOException("Too big");
                        }
                    }
                    PocketArt.save(this, uuid, appId, k, bytes.toByteArray());
                    ok = true;
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                ok = false;
            }
            final boolean saved = ok;
            main.post(() -> {
                busy = false;
                if (isFinishing()) {
                    return;
                }
                if (!saved) {
                    Toast.makeText(this, R.string.pocket_art_device_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                // Not one of SteamGridDB's: none of the grid is ticked
                PocketPrefs.prefs(this).edit().remove(KEY_APPLIED + uuid + "_" + appId + "_" + k).apply();
                PocketSounds.play(this, PocketSounds.TOGGLE_ON);
                Toast.makeText(this, R.string.pocket_art_saved, Toast.LENGTH_SHORT).show();
                adapter.notifyDataSetChanged();
                if (k == PocketArt.HERO) {
                    showBackdrop();
                }
            });
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isConsolePanelShowing()) {
            return super.dispatchKeyEvent(event);
        }
        int keyCode = event.getKeyCode();
        boolean firstDown = event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0;
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (firstDown) {
                    pulseHint(hintBar, keyCode);
                    if (!PocketArt.hasKey(this)) {
                        askForKey(this, this::start);
                    }
                    else {
                        View focused = getCurrentFocus();
                        if (focused != null && focused.getTag() instanceof SteamGridDb.Image) {
                            use((SteamGridDb.Image) focused.getTag());
                        }
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (firstDown) {
                    pulseHint(hintBar, keyCode);
                    if (PocketArt.has(this, uuid, appId, kind)) {
                        resetKind();
                    }
                }
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    // ---- The grid -----------------------------------------------------------------------------

    /** A card whose height follows its width (the shape of the kind of art). */
    private static final class AspectCard extends FrameLayout {
        float ratio = 1.5f;

        AspectCard(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            int h = Math.round(w * ratio);
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final AspectCard card;
        final ImageView image;
        final TextView check;

        Holder(AspectCard card, ImageView image, TextView check) {
            super(card);
            this.card = card;
            this.image = image;
            this.check = check;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final AspectCard card = new AspectCard(ArtPickerActivity.this);
            card.setFocusable(true);
            card.setClickable(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(0x1AFFFFFF);
            card.setBackground(bg);
            card.setClipToOutline(true);

            StateListDrawable ring = new StateListDrawable();
            GradientDrawable on = new GradientDrawable();
            on.setCornerRadius(dp(12));
            on.setStroke(dp(3), 0xFFFFFFFF);
            ring.addState(new int[]{android.R.attr.state_focused}, on);
            ring.addState(new int[]{}, new ColorDrawable(0x00000000));
            card.setForeground(ring);

            ImageView image = new ImageView(ArtPickerActivity.this);
            card.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView check = new TextView(ArtPickerActivity.this);
            check.setText("✓");
            check.setGravity(Gravity.CENTER);
            check.setTextColor(0xFF000000);
            check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(PocketTheme.accent(ArtPickerActivity.this));
            check.setBackground(dot);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.END);
            clp.setMargins(0, dp(6), dp(6), 0);
            card.addView(check, clp);

            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            card.setLayoutParams(lp);

            final Holder holder = new Holder(card, image, check);
            card.setOnFocusChangeListener((v, hasFocus) -> {
                float s = hasFocus ? 1.06f : 1f;
                v.animate().scaleX(s).scaleY(s).translationZ(hasFocus ? dp(6) : 0).setDuration(220)
                        .setInterpolator(PocketUi.EASE).start();
                if (hasFocus) {
                    PocketUi.tick(ArtPickerActivity.this);
                }
            });
            card.setOnClickListener(v -> {
                if (v.getTag() instanceof SteamGridDb.Image) {
                    use((SteamGridDb.Image) v.getTag());
                }
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            final SteamGridDb.Image item = images.get(position);
            holder.card.ratio = RATIOS[kind];
            holder.card.setTag(item);
            holder.image.setScaleType(kind == PocketArt.LOGO || kind == PocketArt.ICON ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
            int pad = kind == PocketArt.LOGO ? dp(12) : 0;
            holder.image.setPadding(pad, pad, pad, pad);
            holder.check.setVisibility(item.id == applied() && PocketArt.has(ArtPickerActivity.this, uuid, appId, kind)
                    ? View.VISIBLE : View.GONE);
            holder.image.setTag(item.thumb);
            Bitmap hit = thumbCache.get(item.thumb);
            holder.image.setImageBitmap(hit);
            if (hit == null) {
                final ImageView target = holder.image;
                final int width = kind == PocketArt.HERO || kind == PocketArt.WIDE ? 480 : 240;
                thumbs.execute(() -> {
                    Bitmap bitmap = null;
                    try {
                        byte[] data = SteamGridDb.download(item.thumb, 6 * 1024 * 1024);
                        BitmapFactory.Options bounds = new BitmapFactory.Options();
                        bounds.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        int sample = 1;
                        while (bounds.outWidth / (sample * 2) >= width) {
                            sample *= 2;
                        }
                        opts.inSampleSize = sample;
                        bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                    } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
                    }
                    final Bitmap result = bitmap;
                    main.post(() -> {
                        if (result == null || isFinishing()) {
                            return;
                        }
                        thumbCache.put(item.thumb, result);
                        if (item.thumb.equals(target.getTag())) {
                            target.setAlpha(0f);
                            target.setImageBitmap(result);
                            target.animate().alpha(1f).setDuration(200).start();
                        }
                    });
                });
            }
        }

        @Override
        public int getItemCount() {
            return images.size();
        }
    }
}
