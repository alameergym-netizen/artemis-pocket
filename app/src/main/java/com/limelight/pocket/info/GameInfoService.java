package com.limelight.pocket.info;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.google.gson.Gson;
import com.limelight.pocket.PocketPrefs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Finds out about a game by its name, on its own: Steam's store first (description, screenshots,
 * trailers, release date, studios, genres, Metacritic), Wikipedia when Steam doesn't know it.
 * Text comes in the interface's language when the source has it (Arabic on Steam and Wikipedia),
 * English otherwise. Answers are kept on disk for a few weeks (misses for a few days), so the game
 * page opens instantly the second time and works offline.
 */
public final class GameInfoService {

    public interface Callback {
        /** On the main thread; null when nothing was found (or no connection). */
        void onInfo(GameInfo info);
    }

    private static final long FRESH_MS = TimeUnit.DAYS.toMillis(21);
    private static final long MISS_MS = TimeUnit.DAYS.toMillis(3);
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final String KEY_STEAM_ID = "pocket_info_steam_";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build();
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Gson GSON = new Gson();
    private static final LruCache<String, Entry> MEMORY = new LruCache<>(48);

    /** What is kept on disk (info is null for "nothing found"). */
    static final class Entry {
        long at;
        GameInfo info;
    }

    private GameInfoService() {
    }

    /** Apps that are not games (nothing to look up). */
    public static boolean isLookupable(String name) {
        String n = InfoParsers.normalize(name);
        return !(n.isEmpty() || n.equals("desktop") || n.contains("big picture") || n.equals("steam")
                || n.equals("playnite") || n.contains("virtual display") || n.equals("terminate")
                || n.equals("mstsc") || n.contains("remote desktop"));
    }

    /** Steam's language name and Wikipedia's code for the interface language. */
    static String[] languages() {
        String l = Locale.getDefault().getLanguage();
        switch (l) {
            case "ar": return new String[]{"arabic", "ar"};
            case "fr": return new String[]{"french", "fr"};
            case "de": return new String[]{"german", "de"};
            case "es": return new String[]{"spanish", "es"};
            case "it": return new String[]{"italian", "it"};
            case "pt": return new String[]{"brazilian", "pt"};
            case "ru": return new String[]{"russian", "ru"};
            case "tr": return new String[]{"turkish", "tr"};
            case "pl": return new String[]{"polish", "pl"};
            case "ja": return new String[]{"japanese", "ja"};
            case "ko": return new String[]{"koreana", "ko"};
            case "zh": return new String[]{"schinese", "zh"};
            default: return new String[]{"english", "en"};
        }
    }

    private static String key(String name) {
        return InfoParsers.normalize(name) + "|" + languages()[0];
    }

    /** Already known (in memory), or null. Never touches the disk or the network. */
    public static GameInfo cached(String name) {
        Entry e = MEMORY.get(key(name));
        return e != null ? e.info : null;
    }

    /** The game's info, from memory, disk or the internet. refresh: look it up again. */
    public static void get(Context context, final String name, final boolean refresh, final Callback callback) {
        final Context app = context.getApplicationContext();
        final String key = key(name);
        final long now = System.currentTimeMillis();
        Entry hit = MEMORY.get(key);
        if (!refresh && hit != null && now - hit.at < (hit.info != null ? FRESH_MS : MISS_MS)) {
            callback.onInfo(hit.info);
            return;
        }
        IO.execute(() -> {
            Entry entry = refresh ? null : readDisk(app, key);
            if (entry == null || now - entry.at >= (entry.info != null ? FRESH_MS : MISS_MS)) {
                Entry fresh = new Entry();
                fresh.at = System.currentTimeMillis();
                try {
                    fresh.info = fetch(app, name);
                    writeDisk(app, key, fresh);
                    entry = fresh;
                } catch (IOException e) {
                    // Offline: an old answer is better than none
                    if (entry == null) {
                        entry = fresh;
                        entry.at = 0;
                    }
                }
            }
            final Entry result = entry;
            if (result.at > 0) {
                MEMORY.put(key, result);
            }
            MAIN.post(() -> callback.onInfo(result.info));
        });
    }

    /** The user picked the right Steam game for this name (or 0 to search again by name). */
    public static void setSteamId(Context context, String name, int steamAppId) {
        String k = KEY_STEAM_ID + InfoParsers.normalize(name);
        if (steamAppId > 0) {
            PocketPrefs.prefs(context).edit().putInt(k, steamAppId).apply();
        }
        else {
            PocketPrefs.prefs(context).edit().remove(k).apply();
        }
        MEMORY.remove(key(name));
        File f = file(context.getApplicationContext(), key(name));
        if (f.exists()) {
            f.delete();
        }
    }

    /** Steam's search results for a name (for "Not this game?"), off the main thread. */
    public interface SearchCallback {
        void onResults(List<InfoParsers.Candidate> results);
    }

    public static void searchSteam(final String term, final SearchCallback callback) {
        IO.execute(() -> {
            List<InfoParsers.Candidate> found;
            try {
                found = InfoParsers.parseSteamSearch(http("https://store.steampowered.com/api/storesearch/?term="
                        + enc(term) + "&l=english&cc=US"));
            } catch (IOException e) {
                found = java.util.Collections.emptyList();
            }
            final List<InfoParsers.Candidate> result = found;
            MAIN.post(() -> callback.onResults(result));
        });
    }

    // ---- Looking it up -----------------------------------------------------------------------

    static GameInfo fetch(Context context, String name) throws IOException {
        if (!isLookupable(name)) {
            return null;
        }
        String[] lang = languages();
        int steamId = PocketPrefs.prefs(context).getInt(KEY_STEAM_ID + InfoParsers.normalize(name), 0);
        if (steamId <= 0) {
            List<InfoParsers.Candidate> found = InfoParsers.parseSteamSearch(http(
                    "https://store.steampowered.com/api/storesearch/?term=" + enc(name) + "&l=english&cc=US"));
            InfoParsers.Candidate best = InfoParsers.bestMatch(name, found);
            steamId = best != null ? best.id : 0;
        }
        GameInfo info = null;
        if (steamId > 0) {
            info = steamDetails(steamId, lang[0]);
            if (info != null && !info.hasText() && !"english".equals(lang[0])) {
                GameInfo english = steamDetails(steamId, "english");
                if (english != null) {
                    info = english;
                }
            }
        }
        if (info == null || !info.hasText()) {
            GameInfo wiki = wikipedia(name, lang[1]);
            if (wiki == null && !"en".equals(lang[1])) {
                wiki = wikipedia(name, "en");
            }
            if (info == null) {
                info = wiki;
            }
            else if (wiki != null) {
                info.about = wiki.about;
                info.shortDescription = wiki.shortDescription;
            }
        }
        if (info != null) {
            info.fetchedAt = System.currentTimeMillis();
        }
        return info;
    }

    private static GameInfo steamDetails(int appId, String language) throws IOException {
        GameInfo info = InfoParsers.parseSteamDetails(http("https://store.steampowered.com/api/appdetails?appids="
                + appId + "&l=" + language + "&cc=US"), appId);
        if (info != null) {
            info.language = language;
        }
        return info;
    }

    private static GameInfo wikipedia(String name, String lang) throws IOException {
        String base = "https://" + lang + ".wikipedia.org";
        List<String> titles = InfoParsers.parseWikiSearch(http(base
                + "/w/api.php?action=query&list=search&format=json&srlimit=5&srsearch=" + enc(name + " video game")));
        String title = InfoParsers.bestWikiTitle(name, titles);
        if (title == null) {
            return null;
        }
        GameInfo info = InfoParsers.parseWikiSummary(http(base + "/api/rest_v1/page/summary/"
                + enc(title.replace(' ', '_')).replace("+", "%20")));
        if (info != null) {
            info.language = lang;
        }
        return info;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String http(String url) throws IOException {
        Request request = new Request.Builder().url(url)
                .header("User-Agent", "ArtemisPocket/1.0 (game info for a game streaming client)")
                .header("Accept", "application/json")
                .build();
        try (Response response = CLIENT.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (response.code() == 404) {
                return "{}";
            }
            if (!response.isSuccessful() || body == null) {
                throw new IOException("HTTP " + response.code());
            }
            byte[] bytes = body.bytes();
            if (bytes.length > MAX_BYTES) {
                throw new IOException("Too big");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    // ---- Disk --------------------------------------------------------------------------------

    private static File file(Context context, String key) {
        return new File(new File(context.getFilesDir(), "pocket_info"), sha1(key) + ".json");
    }

    private static Entry readDisk(Context context, String key) {
        File f = file(context, key);
        if (!f.exists()) {
            return null;
        }
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            return GSON.fromJson(r, Entry.class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void writeDisk(Context context, String key, Entry entry) {
        File f = file(context, key);
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            return;
        }
        File tmp = new File(f.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            GSON.toJson(entry, w);
        } catch (IOException | RuntimeException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
        }
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) {
                b.append(String.format(Locale.ROOT, "%02x", x));
            }
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** Forget everything (settings: "clear game info"). */
    public static void clearAll(Context context) {
        MEMORY.evictAll();
        File dir = new File(context.getFilesDir(), "pocket_info");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }
}
