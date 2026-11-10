package com.limelight.pocket;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import com.limelight.AppView;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.pocket.ui.HeroArt;
import com.limelight.utils.CacheHelper;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A PC's games as this handheld knows them (its cached game list, without asking the PC): the
 * ones played here most recently first, then the rest in the PC's order. Feeds the home screen's
 * "Jump back in" row and finds a nice cover for a PC's card.
 */
public final class RecentGames {

    /** One game of a PC. */
    public static final class Game {
        public final NvApp app;
        public final long lastPlayed;

        Game(NvApp app, long lastPlayed) {
            this.app = app;
            this.lastPlayed = lastPlayed;
        }
    }

    private static final Map<String, List<Game>> CACHE = new HashMap<>();
    private static final Map<String, String> CACHED_FOR = new HashMap<>();

    private RecentGames() {
    }

    /** Forget what was read (a game was just played, the list changed). */
    public static synchronized void invalidate(String uuid) {
        CACHE.remove(uuid);
        CACHED_FOR.remove(uuid);
    }

    /**
     * What the cached list depends on: the game list file and the hidden games. Checking it is
     * cheap, so the list is only read and parsed again when one of them really changed (it used
     * to be re-read every 15 s on the main thread, a small hitch on the home screen).
     */
    private static String stamp(Context context, String uuid) {
        java.io.File f = CacheHelper.openPath(false, context.getCacheDir(), "applist", uuid);
        SharedPreferences hiddenPrefs = context.getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, Context.MODE_PRIVATE);
        Set<String> hidden = hiddenPrefs.getStringSet(uuid, Collections.<String>emptySet());
        // ...and the last game played here (the order is by when they were played)
        int lastId = PocketPrefs.getLastAppId(context, uuid);
        long lastAt = lastId != 0 ? PocketPrefs.getLastPlayed(context, uuid, lastId) : 0;
        return f.lastModified() + ":" + f.length() + ":" + (hidden != null ? hidden.hashCode() + "/" + hidden.size() : "0")
                + ":" + lastId + "@" + lastAt;
    }

    /** The PC's games, most recently played here first (hidden games left out), at most max. */
    public static synchronized List<Game> forPc(Context context, String uuid, int max) {
        if (uuid == null) {
            return Collections.emptyList();
        }
        String stamp = stamp(context, uuid);
        List<Game> all = CACHE.get(uuid);
        if (all == null || !stamp.equals(CACHED_FOR.get(uuid))) {
            all = read(context, uuid);
            CACHE.put(uuid, all);
            CACHED_FOR.put(uuid, stamp);
        }
        return all.size() > max ? new ArrayList<>(all.subList(0, max)) : new ArrayList<>(all);
    }

    private static List<Game> read(Context context, String uuid) {
        List<Game> out = new ArrayList<>();
        List<NvApp> apps;
        try {
            String raw = CacheHelper.readInputStreamToString(
                    CacheHelper.openCacheFileForInput(context.getCacheDir(), "applist", uuid));
            apps = NvHTTP.getAppListByReader(new StringReader(raw));
        } catch (Exception e) {
            return out;
        }
        SharedPreferences hiddenPrefs = context.getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, Context.MODE_PRIVATE);
        Set<String> hidden = hiddenPrefs.getStringSet(uuid, Collections.<String>emptySet());
        for (NvApp app : apps) {
            if (hidden != null && hidden.contains(String.valueOf(app.getAppId()))) {
                continue;
            }
            out.add(new Game(app, PocketPrefs.getLastPlayed(context, uuid, app.getAppId())));
        }
        // Played here, newest first; the others keep the PC's order (a stable sort)
        Collections.sort(out, (l, r) -> Long.compare(r.lastPlayed, l.lastPlayed));
        return out;
    }

    /**
     * A cover worth showing for this PC: the preferred game's (running / last played) if it is
     * real art, otherwise the most recently played game that has real art. Null if none.
     */
    public static Bitmap coverFor(Context context, String uuid, int preferredAppId) {
        Bitmap preferred = HeroArt.load(context, uuid, preferredAppId);
        if (preferred != null && !HeroArt.isPlaceholder(preferred)) {
            return preferred;
        }
        for (Game g : forPc(context, uuid, 12)) {
            if (g.app.getAppId() == preferredAppId) {
                continue;
            }
            Bitmap b = HeroArt.load(context, uuid, g.app.getAppId());
            if (b != null && !HeroArt.isPlaceholder(b)) {
                return b;
            }
        }
        return null;
    }
}
