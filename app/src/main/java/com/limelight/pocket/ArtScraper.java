package com.limelight.pocket;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.limelight.pocket.info.ExtraParsers;
import com.limelight.pocket.info.GameInfoService;
import com.limelight.pocket.info.Igdb;
import com.limelight.pocket.info.InfoParsers;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds a game's or an app's art by itself (cover, background, logo), source after source, only
 * for what it is still missing: SteamGridDB, then Steam's own art, then IGDB, then (Android apps)
 * Google Play. What it finds is kept like art picked by hand (PocketArt), so a picked one always
 * wins and stays. It also warms up the game's information for its page.
 */
public final class ArtScraper {
    private ArtScraper() {}

    private static final String KEY_DONE = "pocket_scraped_";
    private static final long AGAIN_AFTER_MS = 5L * 24 * 60 * 60 * 1000;
    private static final int MAX_DOWNLOAD = 12 * 1024 * 1024;

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> IN_FLIGHT = new HashSet<>();

    /** What is being looked for. */
    public static final class Item {
        public final String uuid;
        public final int appId;
        public final String name;
        /** Its Steam app when known (0: found by name, exactly). */
        public int steamAppId;
        /** An Android app's package (its Google Play page is a source). */
        public String playPackage;

        public Item(String uuid, int appId, String name) {
            this.uuid = uuid;
            this.appId = appId;
            this.name = name;
        }
    }

    public interface Done {
        /** On the main thread: changed is true when new art was saved. */
        void onDone(boolean changed);
    }

    /** Looks it up in the background (once in a while; force: now). */
    public static void scrape(Context context, final Item item, boolean force, final Done done) {
        final Context app = context.getApplicationContext();
        final String key = KEY_DONE + item.uuid + "_" + item.appId;
        if (!force && System.currentTimeMillis() - PocketPrefs.prefs(app).getLong(key, 0) < AGAIN_AFTER_MS) {
            return;
        }
        synchronized (IN_FLIGHT) {
            if (!IN_FLIGHT.add(key)) {
                return;
            }
        }
        if (item.playPackage != null) {
            GameInfoService.setPlayPackage(app, item.name, item.playPackage);
        }
        WORK.execute(() -> {
            boolean changed = false;
            try {
                changed = scrapeNow(app, item);
            } catch (RuntimeException ignored) {
            } finally {
                synchronized (IN_FLIGHT) {
                    IN_FLIGHT.remove(key);
                }
            }
            PocketPrefs.prefs(app).edit().putLong(key, System.currentTimeMillis()).apply();
            final boolean result = changed;
            if (done != null) {
                MAIN.post(() -> done.onDone(result));
            }
        });
    }

    private static boolean missing(Context app, Item it, int kind) {
        return !PocketArt.has(app, it.uuid, it.appId, kind);
    }

    private static boolean save(Context app, Item it, int kind, String url) {
        if (url == null || !missing(app, it, kind)) {
            return false;
        }
        try {
            PocketArt.save(app, it.uuid, it.appId, kind, SteamGridDb.download(url, MAX_DOWNLOAD));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Blocking: every source in turn, for what is still missing. */
    static boolean scrapeNow(Context app, Item it) {
        boolean changed = false;
        // 1. SteamGridDB: the community's art (needs its key)
        changed |= PocketArt.fillFromSteamGridDb(app, it.uuid, it.appId, it.name, true);
        // 2. Steam: the store's own art (the app, or a game of exactly this name)
        if (missing(app, it, PocketArt.COVER) || missing(app, it, PocketArt.HERO) || missing(app, it, PocketArt.LOGO)) {
            int steam = it.steamAppId > 0 ? it.steamAppId : steamIdByName(it.name);
            if (steam > 0) {
                String base = "https://cdn.cloudflare.steamstatic.com/steam/apps/" + steam + "/";
                changed |= save(app, it, PocketArt.COVER, base + "library_600x900_2x.jpg")
                        || save(app, it, PocketArt.COVER, base + "library_600x900.jpg");
                changed |= save(app, it, PocketArt.HERO, base + "library_hero.jpg");
                changed |= save(app, it, PocketArt.LOGO, base + "logo.png");
            }
        }
        // 3. IGDB: covers and artworks of any game (needs its Twitch app)
        if ((missing(app, it, PocketArt.COVER) || missing(app, it, PocketArt.HERO)) && Igdb.configured(app)) {
            try {
                String[] art = ExtraParsers.igdbArt(Igdb.search(app, it.name), it.name);
                if (art != null) {
                    changed |= save(app, it, PocketArt.COVER, art[0]);
                    changed |= save(app, it, PocketArt.HERO, art[1]);
                }
            } catch (IOException ignored) {
            }
        }
        // 4. Google Play: an Android app's feature graphic as its background
        if (it.playPackage != null && missing(app, it, PocketArt.HERO)) {
            ExtraParsers.PlayApp play = GameInfoService.playApp(it.playPackage);
            if (play != null) {
                changed |= save(app, it, PocketArt.HERO, play.featureGraphic);
                if (missing(app, it, PocketArt.HERO) && !play.screenshots.isEmpty()) {
                    changed |= save(app, it, PocketArt.HERO, play.screenshots.get(0));
                }
            }
        }
        return changed;
    }

    /** A Steam game with exactly this name (ignoring case and marks), else 0. Blocking. */
    static int steamIdByName(String name) {
        if (!GameInfoService.isLookupable(name)) {
            return 0;
        }
        try {
            String json = new String(SteamGridDb.download("https://store.steampowered.com/api/storesearch/?term="
                    + URLEncoder.encode(name, "UTF-8") + "&l=english&cc=US", 2 * 1024 * 1024), StandardCharsets.UTF_8);
            String want = InfoParsers.normalize(name);
            List<InfoParsers.Candidate> found = InfoParsers.parseSteamSearch(json);
            for (InfoParsers.Candidate c : found) {
                if (want.equals(InfoParsers.normalize(c.name))) {
                    return c.id;
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return 0;
    }
}
