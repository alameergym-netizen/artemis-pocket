package com.limelight.pocket;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.limelight.grid.assets.MemoryAssetLoader;
import com.limelight.utils.CacheHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Art you chose for a game (from SteamGridDB), kept on the handheld per PC and game:
 *  - cover: replaces the PC's box art on the library card,
 *  - background: a wide key-art image behind the library (console style),
 *  - logo: the game's logo shown instead of its name.
 * Also fetches backgrounds and logos by itself for the game you are looking at, when that is on.
 */
public final class PocketArt {

    public static final int COVER = 0;
    public static final int HERO = 1;
    public static final int LOGO = 2;

    private static final String[] FILES = {"cover.jpg", "hero.jpg", "logo.png"};
    private static final int[] MAX_WIDTH = {600, 1920, 800};
    private static final int MAX_DOWNLOAD = 12 * 1024 * 1024;

    public static final String KEY_SGDB_KEY = "pocket_sgdb_key";
    public static final String KEY_AUTO_ART = "pocket_auto_art";
    public static final String KEY_LOGOS = "pocket_logos_for_names";
    private static final String KEY_MATCH = "pocket_sgdb_match_";
    private static final String KEY_TRIED = "pocket_sgdb_tried_";
    private static final long RETRY_AFTER_MS = 3L * 24 * 3600 * 1000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final ExecutorService AUTO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> IN_FLIGHT = new HashSet<>();

    // Room for a library's worth of backgrounds (each ~4.7 MB): with only three, moving through
    // the games kept decoding and uploading them again
    private static final LruCache<String, Bitmap> BITMAPS = new LruCache<String, Bitmap>(56 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static volatile int version;

    public interface BitmapCallback {
        void onBitmap(Bitmap bitmap);
    }

    private PocketArt() {
    }

    // ---- Settings -------------------------------------------------------------------------------

    public static String apiKey(Context context) {
        return PocketPrefs.prefs(context).getString(KEY_SGDB_KEY, "");
    }

    public static boolean hasKey(Context context) {
        return !apiKey(context).trim().isEmpty();
    }

    public static void setApiKey(Context context, String key) {
        PocketPrefs.prefs(context).edit().putString(KEY_SGDB_KEY, key == null ? "" : key.trim()).apply();
    }

    /** Fetch backgrounds and logos (and covers for games without one) by itself. */
    public static boolean autoArt(Context context) {
        return PocketPrefs.prefs(context).getBoolean(KEY_AUTO_ART, true);
    }

    /** Show a game's logo in place of its name (library, launch screen). */
    public static boolean logosForNames(Context context) {
        return PocketPrefs.prefs(context).getBoolean(KEY_LOGOS, true);
    }

    /** Changes every time some art is saved or reset (screens refresh when it moved on). */
    public static int version() {
        return version;
    }

    // ---- Files ----------------------------------------------------------------------------------

    public static File file(Context context, String uuid, int appId, int kind) {
        return CacheHelper.openPath(false, context.getFilesDir(), "pocket_art", uuid, String.valueOf(appId), FILES[kind]);
    }

    public static boolean has(Context context, String uuid, int appId, int kind) {
        return uuid != null && file(context, uuid, appId, kind).exists();
    }

    /** The SteamGridDB game this app was matched with (0: none yet). */
    public static int matchedGame(Context context, String uuid, int appId) {
        return PocketPrefs.prefs(context).getInt(KEY_MATCH + uuid + "_" + appId, 0);
    }

    public static void setMatchedGame(Context context, String uuid, int appId, int gameId) {
        PocketPrefs.prefs(context).edit().putInt(KEY_MATCH + uuid + "_" + appId, gameId).apply();
    }

    /** Scales the downloaded art to what the screen needs and keeps it. Blocking. */
    public static void save(Context context, String uuid, int appId, int kind, byte[] data) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Not an image");
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH[kind]) {
            sample *= 2;
        }
        opts.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (bitmap == null) {
            throw new IOException("Not an image");
        }
        if (bitmap.getWidth() > MAX_WIDTH[kind]) {
            int h = Math.round(bitmap.getHeight() * (MAX_WIDTH[kind] / (float) bitmap.getWidth()));
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, MAX_WIDTH[kind], Math.max(1, h), true);
            if (scaled != bitmap) {
                bitmap.recycle();
                bitmap = scaled;
            }
        }
        File target = file(context, uuid, appId, kind);
        File dir = target.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("Can't create " + dir);
        }
        File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            if (kind == LOGO) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            else {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
        }
        bitmap.recycle();
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("Can't save");
        }
        changed(uuid, appId);
    }

    /** Back to the PC's own art (or none) for this kind. */
    public static void reset(Context context, String uuid, int appId, int kind) {
        File f = file(context, uuid, appId, kind);
        if (f.exists()) {
            f.delete();
        }
        changed(uuid, appId);
    }

    /** Removes every chosen or fetched piece of art (and what was matched). */
    public static void clearAll(Context context) {
        deleteTree(new File(context.getFilesDir(), "pocket_art"));
        SharedPreferences prefs = PocketPrefs.prefs(context);
        SharedPreferences.Editor e = prefs.edit();
        for (String k : prefs.getAll().keySet()) {
            if (k.startsWith("pocket_sgdb_match_") || k.startsWith("pocket_sgdb_tried_") || k.startsWith("pocket_art_applied_")) {
                e.remove(k);
            }
        }
        e.apply();
        new MemoryAssetLoader().clearCache();
        synchronized (BITMAPS) {
            BITMAPS.evictAll();
        }
        version++;
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteTree(c);
            }
        }
        f.delete();
    }

    private static void changed(String uuid, int appId) {
        MemoryAssetLoader.evict(uuid, appId);
        synchronized (BITMAPS) {
            BITMAPS.evictAll();
        }
        version++;
    }

    // ---- Loading for display --------------------------------------------------------------------

    private static String cacheKey(File f) {
        return f.getPath() + "@" + f.lastModified();
    }

    /** The art if it is already in memory (no flicker when moving back to a game). */
    public static Bitmap cached(Context context, String uuid, int appId, int kind) {
        if (uuid == null) {
            return null;
        }
        File f = file(context, uuid, appId, kind);
        if (!f.exists()) {
            return null;
        }
        synchronized (BITMAPS) {
            return BITMAPS.get(cacheKey(f));
        }
    }

    private static final java.util.Map<Bitmap, Boolean> DARK_LOGOS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Bitmap, Boolean>());

    /** True for a logo too dark to read on a dark screen (known for loaded logos, so it's free). */
    public static boolean isDarkLogo(Bitmap logo) {
        if (logo == null || logo.isRecycled()) {
            return false;
        }
        Boolean known = DARK_LOGOS.get(logo);
        if (known != null) {
            return known;
        }
        boolean dark = false;
        try {
            Bitmap source = logo;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                    && logo.getConfig() == Bitmap.Config.HARDWARE) {
                source = logo.copy(Bitmap.Config.ARGB_8888, false);
            }
            if (source != null) {
                int w = 48;
                int h = Math.max(1, 48 * source.getHeight() / Math.max(1, source.getWidth()));
                Bitmap small = Bitmap.createScaledBitmap(source, w, h, true);
                int[] pixels = new int[small.getWidth() * small.getHeight()];
                small.getPixels(pixels, 0, small.getWidth(), 0, 0, small.getWidth(), small.getHeight());
                double sum = 0;
                int n = 0;
                for (int c : pixels) {
                    if (android.graphics.Color.alpha(c) > 160) {
                        sum += (0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c)
                                + 0.114 * android.graphics.Color.blue(c)) / 255.0;
                        n++;
                    }
                }
                dark = n == 0 || sum / n < 0.22;
                if (small != source) {
                    small.recycle();
                }
                if (source != logo) {
                    source.recycle();
                }
            }
        } catch (RuntimeException ignored) {
        }
        DARK_LOGOS.put(logo, dark);
        return dark;
    }

    /** Loads the art off the main thread; callback on the main thread (null when there is none). */
    public static void load(Context context, final String uuid, final int appId, final int kind, final BitmapCallback callback) {
        final Context app = context.getApplicationContext();
        Bitmap hit = cached(app, uuid, appId, kind);
        if (hit != null || uuid == null) {
            callback.onBitmap(hit);
            return;
        }
        IO.execute(() -> {
            Bitmap bitmap = null;
            File f = file(app, uuid, appId, kind);
            if (f.exists()) {
                String key = cacheKey(f);
                synchronized (BITMAPS) {
                    // Asked again while it was queued (moving back and forth)
                    bitmap = BITMAPS.get(key);
                }
                if (bitmap == null) {
                    try {
                        Bitmap raw = BitmapFactory.decodeFile(f.getPath());
                        if (raw != null) {
                            boolean dark = kind == LOGO && isDarkLogo(raw);
                            // Colours worked out and uploaded to the GPU here, not mid-animation
                            bitmap = com.limelight.pocket.ui.HeroArt.prepare(raw, true);
                            if (kind == LOGO) {
                                DARK_LOGOS.put(bitmap, dark);
                            }
                            synchronized (BITMAPS) {
                                BITMAPS.put(key, bitmap);
                            }
                        }
                    } catch (OutOfMemoryError ignored) {
                    }
                }
            }
            final Bitmap result = bitmap;
            MAIN.post(() -> callback.onBitmap(result));
        });
    }

    // ---- Fetching by itself ---------------------------------------------------------------------

    private static boolean neverAuto(String name) {
        String n = SteamGridDb.normalize(name);
        return n.isEmpty() || n.equals("desktop") || n.contains("big picture") || n.equals("steam")
                || n.equals("playnite") || n.contains("virtual display") || n.equals("terminate");
    }

    /**
     * For the game in focus: if it has no background / logo (or no real cover when needsCover),
     * look it up on SteamGridDB once (only when the name surely matches) and keep the community's
     * favourites. onChanged runs on the main thread if something new arrived.
     */
    public static void autoFetch(Context context, final String uuid, final int appId, final String name,
                                 final boolean needsCover, final Runnable onChanged) {
        final Context app = context.getApplicationContext();
        if (uuid == null || !hasKey(app) || !autoArt(app) || neverAuto(name)) {
            return;
        }
        if (has(app, uuid, appId, HERO) && has(app, uuid, appId, LOGO)
                && (!needsCover || has(app, uuid, appId, COVER))) {
            return;
        }
        final SharedPreferences prefs = PocketPrefs.prefs(app);
        if (System.currentTimeMillis() - prefs.getLong(KEY_TRIED + uuid + "_" + appId, 0) < RETRY_AFTER_MS) {
            return;
        }
        final String flight = uuid + "_" + appId;
        synchronized (IN_FLIGHT) {
            if (!IN_FLIGHT.add(flight)) {
                return;
            }
        }
        final String key = apiKey(app);
        AUTO.execute(() -> {
            boolean got = false;
            try {
                got = fetchMissing(app, key, uuid, appId, name, needsCover);
            } catch (IOException | RuntimeException ignored) {
                // Offline or a bad key: try again another time
            } finally {
                synchronized (IN_FLIGHT) {
                    IN_FLIGHT.remove(flight);
                }
            }
            if (got && onChanged != null) {
                MAIN.post(onChanged);
            }
        });
    }

    /**
     * Finds the game (only a sure match) and keeps the community's favourite background, logo
     * and, when asked, cover for whatever is missing. Blocking. True if something was saved.
     */
    private static boolean fetchMissing(Context app, String key, String uuid, int appId, String name,
                                        boolean needsCover) throws IOException {
        boolean wantHero = !has(app, uuid, appId, HERO);
        boolean wantLogo = !has(app, uuid, appId, LOGO);
        boolean wantCover = needsCover && !has(app, uuid, appId, COVER);
        if (!wantHero && !wantLogo && !wantCover) {
            return false;
        }
        boolean got = false;
        int gameId = matchedGame(app, uuid, appId);
        if (gameId == 0) {
            for (SteamGridDb.Game g : SteamGridDb.search(key, name)) {
                if (SteamGridDb.sameGame(name, g.name)) {
                    gameId = g.id;
                    break;
                }
            }
            if (gameId != 0) {
                setMatchedGame(app, uuid, appId, gameId);
            }
        }
        // Reached SteamGridDB: don't ask again for a while, found or not
        PocketPrefs.prefs(app).edit().putLong(KEY_TRIED + uuid + "_" + appId, System.currentTimeMillis()).apply();
        if (gameId == 0) {
            return false;
        }
        if (wantHero) {
            got |= fetchFirst(app, key, uuid, appId, gameId, HERO);
        }
        if (wantLogo) {
            got |= fetchFirst(app, key, uuid, appId, gameId, LOGO);
        }
        if (wantCover) {
            got |= fetchFirst(app, key, uuid, appId, gameId, COVER);
        }
        return got;
    }

    /**
     * SteamGridDB (sure matches only) for whatever this item is missing. Blocking; true when
     * something new was saved. Used by the scraper, which tries the other sources after it.
     */
    public static boolean fillFromSteamGridDb(Context context, String uuid, int appId, String name, boolean needsCover) {
        Context app = context.getApplicationContext();
        if (uuid == null || !hasKey(app) || neverAuto(name)) {
            return false;
        }
        try {
            return fetchMissing(app, apiKey(app), uuid, appId, name, needsCover);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public interface CountCallback {
        void done(int changed, boolean badKey);
    }

    /** Fills in missing art for a whole library, one game after another (in the background). */
    public static void fetchAll(Context context, final String uuid, final int[] appIds, final String[] names,
                                final CountCallback done) {
        final Context app = context.getApplicationContext();
        if (uuid == null || !hasKey(app)) {
            done.done(0, false);
            return;
        }
        final String key = apiKey(app);
        AUTO.execute(() -> {
            int changed = 0;
            boolean badKey = false;
            for (int i = 0; i < appIds.length; i++) {
                if (neverAuto(names[i])) {
                    continue;
                }
                Bitmap cover = com.limelight.pocket.ui.HeroArt.load(app, uuid, appIds[i]);
                boolean needsCover = cover != null && com.limelight.pocket.ui.HeroArt.isPlaceholder(cover);
                try {
                    if (fetchMissing(app, key, uuid, appIds[i], names[i], needsCover)) {
                        changed++;
                    }
                } catch (SteamGridDb.ApiException e) {
                    if (e.badKey()) {
                        badKey = true;
                        break;
                    }
                } catch (IOException | RuntimeException ignored) {
                    // Skip this one
                }
            }
            final int n = changed;
            final boolean bad = badKey;
            MAIN.post(() -> done.done(n, bad));
        });
    }

    private static boolean fetchFirst(Context app, String key, String uuid, int appId, int gameId, int kind) throws IOException {
        List<SteamGridDb.Image> images = SteamGridDb.images(key, gameId, kind, 0);
        SteamGridDb.Image pick = null;
        for (SteamGridDb.Image i : images) {
            // Logos: the official one if there is one; otherwise the favourite
            if (kind != LOGO || "official".equals(i.style)) {
                pick = i;
                break;
            }
        }
        if (pick == null && !images.isEmpty()) {
            pick = images.get(0);
        }
        if (pick == null) {
            return false;
        }
        save(app, uuid, appId, kind, SteamGridDb.download(pick.url, MAX_DOWNLOAD));
        return true;
    }

    /** Downloads and keeps one picked image. Blocking. */
    public static void apply(Context context, String uuid, int appId, int kind, SteamGridDb.Image image) throws IOException {
        save(context.getApplicationContext(), uuid, appId, kind, SteamGridDb.download(image.url, MAX_DOWNLOAD));
    }
}
