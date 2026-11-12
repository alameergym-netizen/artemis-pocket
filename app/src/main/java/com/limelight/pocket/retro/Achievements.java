package com.limelight.pocket.retro;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.home.HomeItem;
import com.limelight.pocket.info.GameInfo;
import com.limelight.pocket.info.GameInfoService;
import com.limelight.pocket.pclib.SteamAccount;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Achievements, the way the consoles show their trophies: from RetroAchievements for emulated
 * games (the game is recognised by the ROM's hash, like the emulators do, or by its name) and
 * from Steam for PC games (with the Steam account linked in Settings). Each game's list is kept
 * on the handheld, so Home and the game page show progress at once and refresh in the background.
 */
public final class Achievements {
    private Achievements() {}

    // ---- What a game's achievements look like -----------------------------------------------

    public static final class Trophy {
        public String title = "";
        public String description = "";
        public String icon;
        public String iconLocked;
        public boolean earned;
        public boolean hardcore;
        public long earnedAt;
        public int points;
        /** Percent of players who have it (-1: not known). */
        public float rarity = -1f;
        public int order;
    }

    public static final class Set {
        public String source; // "ra" or "steam"
        public String title = "";
        public String platform = "";
        public String icon;
        public int earned;
        public int total;
        public int points;
        public int pointsTotal;
        public long fetchedAt;
        public final List<Trophy> trophies = new ArrayList<>();

        public int percent() {
            return total == 0 ? 0 : Math.round(earned * 100f / total);
        }

        JSONObject toJson() throws org.json.JSONException {
            JSONObject o = new JSONObject();
            o.put("source", source);
            o.put("title", title);
            o.put("platform", platform);
            o.put("icon", icon);
            o.put("earned", earned);
            o.put("total", total);
            o.put("points", points);
            o.put("pointsTotal", pointsTotal);
            o.put("fetchedAt", fetchedAt);
            JSONArray a = new JSONArray();
            for (Trophy t : trophies) {
                JSONObject j = new JSONObject();
                j.put("t", t.title);
                j.put("d", t.description);
                j.put("i", t.icon);
                j.put("l", t.iconLocked);
                j.put("e", t.earned);
                j.put("h", t.hardcore);
                j.put("at", t.earnedAt);
                j.put("p", t.points);
                j.put("r", (double) t.rarity);
                j.put("o", t.order);
                a.put(j);
            }
            o.put("trophies", a);
            return o;
        }

        static Set fromJson(JSONObject o) {
            Set s = new Set();
            s.source = o.optString("source");
            s.title = o.optString("title");
            s.platform = o.optString("platform");
            s.icon = o.optString("icon", null);
            s.earned = o.optInt("earned");
            s.total = o.optInt("total");
            s.points = o.optInt("points");
            s.pointsTotal = o.optInt("pointsTotal");
            s.fetchedAt = o.optLong("fetchedAt");
            JSONArray a = o.optJSONArray("trophies");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject j = a.optJSONObject(i);
                if (j == null) {
                    continue;
                }
                Trophy t = new Trophy();
                t.title = j.optString("t");
                t.description = j.optString("d");
                t.icon = j.optString("i", null);
                t.iconLocked = j.optString("l", null);
                t.earned = j.optBoolean("e");
                t.hardcore = j.optBoolean("h");
                t.earnedAt = j.optLong("at");
                t.points = j.optInt("p");
                t.rarity = (float) j.optDouble("r", -1);
                t.order = j.optInt("o");
                s.trophies.add(t);
            }
            return s;
        }
    }

    /** Which game, where its achievements come from. */
    public static final class Target {
        public final String kind;
        public final String system;
        public final String path;
        public final int steamAppId;
        public final String name;

        private Target(String kind, String system, String path, int steamAppId, String name) {
            this.kind = kind;
            this.system = system;
            this.path = path;
            this.steamAppId = steamAppId;
            this.name = name;
        }

        public static Target retro(String system, String relPath, String name) {
            return new Target("ra", system, relPath, 0, name);
        }

        public static Target steam(int appId, String name) {
            return new Target("steam", null, null, appId, name);
        }

        public String key() {
            return "ra".equals(kind) ? "ra:" + system + "/" + path : "steam:" + steamAppId;
        }
    }

    public interface Done {
        /** On the main thread; null when there are none (or they couldn't be loaded). */
        void done(Set set);
    }

    // ---- The RetroAchievements account -------------------------------------------------------

    private static final String KEY_RA_USER = "pocket_ra_user";
    private static final String KEY_RA_KEY = "pocket_ra_key";
    private static final String KEY_RA_MAP = "pocket_ra_map_";

    public static String raUser(Context c) {
        return PocketPrefs.prefs(c).getString(KEY_RA_USER, "");
    }

    static String raKey(Context c) {
        return PocketPrefs.prefs(c).getString(KEY_RA_KEY, "");
    }

    public static boolean hasRa(Context c) {
        return !raUser(c).isEmpty() && !raKey(c).isEmpty();
    }

    public static void setRa(Context c, String user, String key) {
        PocketPrefs.prefs(c).edit().putString(KEY_RA_USER, user.trim()).putString(KEY_RA_KEY, key.trim()).apply();
        synchronized (MEMORY) {
            MEMORY.clear();
        }
    }

    /** Checks the account with RetroAchievements (blocking): its points, or -1 when it's wrong. */
    public static int checkRa(Context c) {
        try {
            JSONObject o = new JSONObject(get("https://retroachievements.org/API/API_GetUserProfile.php?u="
                    + enc(raUser(c)) + "&y=" + enc(raKey(c))));
            if (o.has("User") || o.has("TotalPoints")) {
                return o.optInt("TotalPoints", 0);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    // ---- Which games have them ---------------------------------------------------------------

    /** Where this Home item's achievements would come from (null: nowhere). */
    public static Target forItem(Context c, HomeItem item) {
        if (item == null) {
            return null;
        }
        if (HomeItem.RETRO.equals(item.kind)) {
            return hasRa(c) && consoleId(item.a) > 0 ? Target.retro(item.a, item.b, item.name) : null;
        }
        if (!SteamAccount.linked(c)) {
            return null;
        }
        if (HomeItem.NATIVE.equals(item.kind) && item.n > 0) {
            return Target.steam(item.n, item.name);
        }
        GameInfo info = GameInfoService.cached(RomScanner.searchName(item.name));
        if (info != null && info.steamAppId > 0) {
            return Target.steam(info.steamAppId, item.name);
        }
        return null;
    }

    /** Where a game page's achievements would come from (null: nowhere). */
    public static Target forPage(Context c, String uuid, int appId, String name, GameInfo info) {
        if (RetroLibrary.UUID.equals(uuid)) {
            if (!hasRa(c)) {
                return null;
            }
            RomScanner.Rom rom = findRom(c, appId);
            return rom != null && consoleId(rom.system) > 0 ? Target.retro(rom.system, rom.relPath, rom.name) : null;
        }
        if (SteamAccount.linked(c) && info != null && info.steamAppId > 0) {
            return Target.steam(info.steamAppId, name);
        }
        return null;
    }

    public static RomScanner.Rom findRom(Context c, int id) {
        try {
            RetroLibrary.Library lib = RetroLibrary.cached(c, RetroLibrary.romRoot(c));
            if (lib == null) {
                return null;
            }
            for (List<RomScanner.Rom> list : lib.games.values()) {
                for (RomScanner.Rom r : list) {
                    if (r.id() == id) {
                        return r;
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    // ---- Cached, and loaded ------------------------------------------------------------------

    private static final Map<String, Set> MEMORY = new HashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long FRESH_MS = 20L * 60 * 1000;

    /** What's kept on the handheld (never the network; may be stale). */
    public static Set cached(Context c, Target t) {
        if (t == null) {
            return null;
        }
        synchronized (MEMORY) {
            Set s = MEMORY.get(t.key());
            if (s != null) {
                return s;
            }
        }
        File f = file(c, t);
        if (!f.exists()) {
            return null;
        }
        try {
            Set s = Set.fromJson(new JSONObject(read(f)));
            synchronized (MEMORY) {
                MEMORY.put(t.key(), s);
            }
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    /** "12 / 40" (or "100%") for Home's chip, from what's kept; null when nothing is known. */
    public static String chip(Context c, HomeItem item) {
        Target t = forItem(c, item);
        Set s = t != null ? cached(c, t) : null;
        if (s == null || s.total == 0) {
            return null;
        }
        return s.earned >= s.total ? "100%" : s.earned + " / " + s.total;
    }

    /** Loads them (from the handheld when fresh, else the internet); done on the main thread. */
    public static void load(Context context, final Target t, final boolean force, final Done done) {
        if (t == null) {
            done.done(null);
            return;
        }
        final Context c = context.getApplicationContext();
        Set known = cached(c, t);
        if (!force && known != null && System.currentTimeMillis() - known.fetchedAt < FRESH_MS) {
            done.done(known);
            return;
        }
        IO.execute(() -> {
            Set s = null;
            try {
                s = "ra".equals(t.kind) ? fetchRa(c, t) : fetchSteam(c, t);
            } catch (Exception ignored) {
            }
            if (s != null) {
                s.fetchedAt = System.currentTimeMillis();
                synchronized (MEMORY) {
                    MEMORY.put(t.key(), s);
                }
                try {
                    write(file(c, t), s.toJson().toString());
                } catch (Exception ignored) {
                }
            }
            final Set result = s != null ? s : known;
            MAIN.post(() -> done.done(result));
        });
    }

    private static File file(Context c, Target t) {
        File dir = new File(c.getFilesDir(), "pocket_trophies");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, Integer.toHexString(t.key().hashCode()) + ".json");
    }

    // ---- RetroAchievements -------------------------------------------------------------------

    static final String RA = "https://retroachievements.org/API/";
    static final String RA_MEDIA = "https://media.retroachievements.org";

    private static Set fetchRa(Context c, Target t) throws Exception {
        int game = raGame(c, t);
        if (game <= 0) {
            return null;
        }
        JSONObject o = new JSONObject(get(RA + "API_GetGameInfoAndUserProgress.php?g=" + game + "&u="
                + enc(raUser(c)) + "&y=" + enc(raKey(c)) + "&a=1"));
        Set s = new Set();
        s.source = "ra";
        s.title = o.optString("Title", t.name);
        s.platform = o.optString("ConsoleName", "");
        String icon = o.optString("ImageIcon", "");
        s.icon = icon.isEmpty() ? null : RA_MEDIA + icon;
        int players = Math.max(o.optInt("NumDistinctPlayers", 0), o.optInt("NumDistinctPlayersCasual", 0));
        JSONObject list = o.optJSONObject("Achievements");
        SimpleDateFormat when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        when.setTimeZone(TimeZone.getTimeZone("UTC"));
        if (list != null) {
            Iterator<String> keys = list.keys();
            while (keys.hasNext()) {
                JSONObject a = list.optJSONObject(keys.next());
                if (a == null) {
                    continue;
                }
                Trophy tr = new Trophy();
                tr.title = a.optString("Title");
                tr.description = a.optString("Description");
                tr.points = a.optInt("Points");
                tr.order = a.optInt("DisplayOrder");
                String badge = a.optString("BadgeName", "");
                if (!badge.isEmpty()) {
                    tr.icon = RA_MEDIA + "/Badge/" + badge + ".png";
                    tr.iconLocked = RA_MEDIA + "/Badge/" + badge + "_lock.png";
                }
                String hard = a.optString("DateEarnedHardcore", "");
                String soft = a.optString("DateEarned", "");
                String date = !hard.isEmpty() ? hard : soft;
                if (!date.isEmpty() && !"null".equals(date)) {
                    tr.earned = true;
                    tr.hardcore = !hard.isEmpty();
                    try {
                        java.util.Date dt = when.parse(date);
                        tr.earnedAt = dt != null ? dt.getTime() : 0;
                    } catch (Exception ignored) {
                    }
                }
                if (players > 0) {
                    tr.rarity = Math.min(100f, a.optInt("NumAwarded", 0) * 100f / players);
                }
                s.total++;
                s.pointsTotal += tr.points;
                if (tr.earned) {
                    s.earned++;
                    s.points += tr.points;
                }
                s.trophies.add(tr);
            }
        }
        sortTrophies(s);
        return s;
    }

    /** RetroAchievements' id for the game (blocking; -1 when it isn't known there). */
    static int raGameId(Context c, Target t) {
        try {
            return raGame(c, t);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Loads them from the internet now and keeps them (blocking; null when that failed). */
    static Set fetchNow(Context c, Target t) {
        try {
            Set s = "ra".equals(t.kind) ? fetchRa(c, t) : fetchSteam(c, t);
            if (s != null) {
                s.fetchedAt = System.currentTimeMillis();
                synchronized (MEMORY) {
                    MEMORY.put(t.key(), s);
                }
                write(file(c, t), s.toJson().toString());
            }
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * One just earned while playing (seen live): kept as earned, so the lists and Home's chip
     * are right straight away. The set as it is now (null when none was kept).
     */
    static Set markEarned(Context c, Target t, String title, long at, boolean hardcore) {
        Set s = cached(c, t);
        if (s == null || title == null) {
            return s;
        }
        synchronized (MEMORY) {
            for (Trophy tr : s.trophies) {
                if (!tr.earned && title.equals(tr.title)) {
                    tr.earned = true;
                    tr.hardcore = hardcore;
                    tr.earnedAt = at;
                    s.earned++;
                    s.points += tr.points;
                    sortTrophies(s);
                    break;
                }
            }
        }
        try {
            write(file(c, t), s.toJson().toString());
        } catch (Exception ignored) {
        }
        return s;
    }

    /** The achievement of this name in the set (null: not there). */
    static Trophy find(Set s, String title) {
        if (s == null || title == null) {
            return null;
        }
        for (Trophy tr : s.trophies) {
            if (title.equals(tr.title)) {
                return tr;
            }
        }
        return null;
    }

    /** RetroAchievements' id for the game (by the ROM's hash, else by its name; cached). */
    private static int raGame(Context c, Target t) throws Exception {
        SharedPreferences p = PocketPrefs.prefs(c);
        String mapKey = KEY_RA_MAP + t.key();
        int known = p.getInt(mapKey, 0);
        if (known > 0) {
            return known;
        }
        int console = consoleId(t.system);
        if (console <= 0) {
            return -1;
        }
        JSONArray games = gameList(c, console);
        if (games == null) {
            return -1;
        }
        File rom = new File(new File(RetroLibrary.romRoot(c), t.system), t.path);
        String hash = null;
        try {
            hash = RomHash.of(rom, t.system);
        } catch (Exception ignored) {
        }
        int found = -1;
        if (hash != null) {
            for (int i = 0; i < games.length() && found < 0; i++) {
                JSONObject g = games.optJSONObject(i);
                JSONArray hashes = g != null ? g.optJSONArray("Hashes") : null;
                for (int h = 0; hashes != null && h < hashes.length(); h++) {
                    if (hash.equalsIgnoreCase(hashes.optString(h))) {
                        found = g.optInt("ID");
                        break;
                    }
                }
            }
        }
        if (found < 0) {
            found = byTitle(games, t.name);
        }
        if (found > 0) {
            p.edit().putInt(mapKey, found).apply();
        }
        return found;
    }

    static String norm(String s) {
        if (s == null) {
            return "";
        }
        String n = s.toLowerCase(Locale.ROOT);
        n = n.replaceAll("\\(.*?\\)|\\[.*?\\]", " ");
        n = n.replace("&", " and ");
        n = n.replaceAll("\\bthe\\b", " ");
        n = n.replaceAll("[^a-z0-9]+", " ").trim();
        return n.replaceAll("\\s+", " ");
    }

    /** The best match for a name among RetroAchievements' games (hacks and subsets only exactly). */
    static int byTitle(JSONArray games, String name) {
        String want = norm(name);
        if (want.isEmpty()) {
            return -1;
        }
        int best = -1;
        int bestScore = 0;
        for (int i = 0; i < games.length(); i++) {
            JSONObject g = games.optJSONObject(i);
            if (g == null) {
                continue;
            }
            String title = g.optString("Title");
            boolean special = title.startsWith("~");
            for (String part : title.split("\\|")) {
                String have = norm(part);
                if (have.isEmpty()) {
                    continue;
                }
                int score;
                if (have.equals(want)) {
                    score = special ? 60 : 100;
                }
                else if (!special && (have.startsWith(want + " ") || want.startsWith(have + " "))) {
                    score = 70 - Math.abs(have.length() - want.length());
                }
                else {
                    continue;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = g.optInt("ID");
                }
            }
        }
        return bestScore >= 40 ? best : -1;
    }

    /** A console's games, with their hashes (kept a week). */
    private static JSONArray gameList(Context c, int console) throws Exception {
        File dir = new File(c.getCacheDir(), "pocket_ra");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, "games_" + console + ".json");
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7L * 24 * 60 * 60 * 1000) {
            return new JSONArray(read(f));
        }
        String json = get(RA + "API_GetGameList.php?i=" + console + "&h=1&f=1&y=" + enc(raKey(c)) + "&z=" + enc(raUser(c)));
        JSONArray a = new JSONArray(json);
        write(f, json);
        return a;
    }

    /** ES-DE's system folders to RetroAchievements' consoles (0: none). */
    public static int consoleId(String system) {
        if (system == null) {
            return 0;
        }
        Integer id = CONSOLES.get(system.toLowerCase(Locale.ROOT));
        return id != null ? id : 0;
    }

    private static final Map<String, Integer> CONSOLES = new HashMap<>();

    static {
        String[][] map = {
                {"genesis", "1"}, {"megadrive", "1"}, {"megadrivejp", "1"}, {"n64", "2"}, {"n64dd", "2"},
                {"snes", "3"}, {"sfc", "3"}, {"snesna", "3"}, {"satellaview", "3"}, {"gb", "4"}, {"gba", "5"},
                {"gbc", "6"}, {"nes", "7"}, {"famicom", "7"}, {"fds", "7"}, {"pcengine", "8"}, {"tg16", "8"},
                {"segacd", "9"}, {"megacd", "9"}, {"megacdjp", "9"}, {"sega32x", "10"}, {"mastersystem", "11"},
                {"psx", "12"}, {"atarilynx", "13"}, {"ngp", "14"}, {"ngpc", "14"}, {"gamegear", "15"},
                {"gc", "16"}, {"gamecube", "16"}, {"atarijaguar", "17"}, {"nds", "18"}, {"wii", "19"},
                {"ps2", "21"}, {"odyssey2", "23"}, {"pokemini", "24"}, {"atari2600", "25"}, {"arcade", "27"},
                {"mame", "27"}, {"fbneo", "27"}, {"neogeo", "27"}, {"virtualboy", "28"}, {"msx", "29"},
                {"msx2", "29"}, {"sg-1000", "33"}, {"sg1000", "33"}, {"saturn", "39"}, {"saturnjp", "39"},
                {"dreamcast", "40"}, {"psp", "41"}, {"3do", "43"}, {"colecovision", "44"},
                {"intellivision", "45"}, {"vectrex", "46"}, {"pcfx", "49"}, {"atari5200", "50"},
                {"atari7800", "51"}, {"wonderswan", "53"}, {"wonderswancolor", "53"}, {"pcenginecd", "76"},
                {"tg-cd", "76"}, {"atarijaguarcd", "77"}, {"dsi", "78"}, {"supervision", "63"},
                {"megaduck", "69"}, {"arduboy", "71"}, {"wasm4", "72"}, {"channelf", "57"},
        };
        for (String[] m : map) {
            CONSOLES.put(m[0], Integer.parseInt(m[1]));
        }
    }

    // ---- Steam ---------------------------------------------------------------------------------

    private static Set fetchSteam(Context c, Target t) throws Exception {
        String key = SteamAccount.apiKey(c);
        String id = SteamAccount.steamId(c);
        String lang = Locale.getDefault().getLanguage().equals("ar") ? "arabic" : "english";
        JSONObject schema = new JSONObject(get("https://api.steampowered.com/ISteamUserStats/GetSchemaForGame/v2/?key="
                + enc(key) + "&appid=" + t.steamAppId + "&l=" + lang));
        JSONObject game = schema.optJSONObject("game");
        JSONObject stats = game != null ? game.optJSONObject("availableGameStats") : null;
        JSONArray defs = stats != null ? stats.optJSONArray("achievements") : null;
        if (defs == null || defs.length() == 0) {
            return null;
        }
        Map<String, JSONObject> mine = new HashMap<>();
        try {
            JSONObject player = new JSONObject(get("https://api.steampowered.com/ISteamUserStats/GetPlayerAchievements/v1/?key="
                    + enc(key) + "&steamid=" + enc(id) + "&appid=" + t.steamAppId));
            JSONArray got = player.optJSONObject("playerstats") != null
                    ? player.optJSONObject("playerstats").optJSONArray("achievements") : null;
            for (int i = 0; got != null && i < got.length(); i++) {
                JSONObject a = got.optJSONObject(i);
                if (a != null) {
                    mine.put(a.optString("apiname"), a);
                }
            }
        } catch (Exception ignored) {
            // A private profile: the list without what's unlocked
        }
        Map<String, Float> rarity = new HashMap<>();
        try {
            JSONObject global = new JSONObject(get("https://api.steampowered.com/ISteamUserStats/GetGlobalAchievementPercentagesForApp/v2/?gameid="
                    + t.steamAppId));
            JSONArray list = global.optJSONObject("achievementpercentages") != null
                    ? global.optJSONObject("achievementpercentages").optJSONArray("achievements") : null;
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject a = list.optJSONObject(i);
                if (a != null) {
                    rarity.put(a.optString("name"), (float) a.optDouble("percent", -1));
                }
            }
        } catch (Exception ignored) {
        }
        Set s = new Set();
        s.source = "steam";
        s.title = game.optString("gameName", t.name);
        s.platform = "Steam";
        for (int i = 0; i < defs.length(); i++) {
            JSONObject a = defs.optJSONObject(i);
            if (a == null) {
                continue;
            }
            Trophy tr = new Trophy();
            String api = a.optString("name");
            tr.title = a.optString("displayName", api);
            tr.description = a.optString("description", "");
            tr.icon = a.optString("icon", null);
            tr.iconLocked = a.optString("icongray", null);
            tr.order = i;
            JSONObject got = mine.get(api);
            if (got != null && got.optInt("achieved") == 1) {
                tr.earned = true;
                tr.earnedAt = got.optLong("unlocktime") * 1000L;
            }
            Float r = rarity.get(api);
            if (r != null) {
                tr.rarity = r;
            }
            s.total++;
            if (tr.earned) {
                s.earned++;
            }
            s.trophies.add(tr);
        }
        sortTrophies(s);
        return s;
    }

    /** Unlocked first (newest first), then the rest in the game's order. */
    private static void sortTrophies(Set s) {
        Collections.sort(s.trophies, (a, b) -> {
            if (a.earned != b.earned) {
                return a.earned ? -1 : 1;
            }
            if (a.earned) {
                return Long.compare(b.earnedAt, a.earnedAt);
            }
            return Integer.compare(a.order, b.order);
        });
    }

    // ---- Plumbing ------------------------------------------------------------------------------

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    static String get(String url) throws IOException {
        Request r = new Request.Builder().url(url).header("User-Agent", "PocketNexus/1.0").build();
        try (Response resp = CLIENT.newCall(r).execute()) {
            ResponseBody body = resp.body();
            if (!resp.isSuccessful() || body == null) {
                throw new IOException("HTTP " + resp.code());
            }
            return body.string();
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    static void write(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    // ---- The ROM's hash, as RetroAchievements computes it ---------------------------------------

    static final class RomHash {
        private RomHash() {}

        private static final long MAX = 64L * 1024 * 1024;

        /** The hash, or null where only the name can tell (disc images, 7z ...). */
        static String of(File rom, String system) throws Exception {
            if (rom == null || !rom.isFile()) {
                return null;
            }
            String sys = system.toLowerCase(Locale.ROOT);
            String name = rom.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (consoleId(sys) == 27) {
                // Arcade: the set's name
                String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
                return md5(base.getBytes(StandardCharsets.UTF_8));
            }
            if (isDisc(sys) || lower.endsWith(".7z") || lower.endsWith(".chd") || lower.endsWith(".iso")
                    || lower.endsWith(".cue") || lower.endsWith(".bin") && isDisc(sys)) {
                return null;
            }
            if (lower.endsWith(".zip")) {
                try (ZipInputStream zip = new ZipInputStream(new FileInputStream(rom))) {
                    ZipEntry e;
                    while ((e = zip.getNextEntry()) != null) {
                        if (!e.isDirectory()) {
                            return hashStream(zip, sys, e.getName().toLowerCase(Locale.ROOT), e.getSize());
                        }
                    }
                }
                return null;
            }
            if (rom.length() > MAX) {
                return null;
            }
            try (InputStream in = new FileInputStream(rom)) {
                return hashStream(in, sys, lower, rom.length());
            }
        }

        private static boolean isDisc(String sys) {
            int id = consoleId(sys);
            return id == 9 || id == 12 || id == 16 || id == 18 || id == 19 || id == 21 || id == 39 || id == 40
                    || id == 41 || id == 43 || id == 76 || id == 77 || id == 78 || id == 49;
        }

        private static String hashStream(InputStream in, String sys, String name, long size) throws Exception {
            byte[] data = readAll(in);
            if (data == null) {
                return null;
            }
            int id = consoleId(sys);
            int skip = 0;
            if (id == 7 && data.length > 16 && ((data[0] == 'N' && data[1] == 'E' && data[2] == 'S' && data[3] == 0x1A)
                    || (data[0] == 'F' && data[1] == 'D' && data[2] == 'S' && data[3] == 0x1A))) {
                skip = 16;
            }
            else if (id == 3 && data.length % 8192 == 512) {
                skip = 512;
            }
            else if (id == 13 && data.length > 64 && data[0] == 'L' && data[1] == 'Y' && data[2] == 'N' && data[3] == 'X') {
                skip = 64;
            }
            else if (id == 51 && data.length > 128 && data[1] == 'A' && data[2] == 'T' && data[3] == 'A' && data[4] == 'R' && data[5] == 'I') {
                skip = 128;
            }
            else if (id == 8 && data.length % 131072 == 512) {
                skip = 512;
            }
            if (id == 2 && data.length >= 4) {
                n64ToBigEndian(data);
            }
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(data, skip, data.length - skip);
            return hex(md.digest());
        }

        /** N64 ROMs come byte-swapped (.v64) or little-endian (.n64): RetroAchievements hashes .z64. */
        private static void n64ToBigEndian(byte[] d) {
            if ((d[0] & 0xFF) == 0x37 && (d[1] & 0xFF) == 0x80) {
                for (int i = 0; i + 1 < d.length; i += 2) {
                    byte t = d[i];
                    d[i] = d[i + 1];
                    d[i + 1] = t;
                }
            }
            else if ((d[0] & 0xFF) == 0x40 && (d[1] & 0xFF) == 0x12) {
                for (int i = 0; i + 3 < d.length; i += 4) {
                    byte a = d[i];
                    byte b = d[i + 1];
                    d[i] = d[i + 3];
                    d[i + 1] = d[i + 2];
                    d[i + 2] = b;
                    d[i + 3] = a;
                }
            }
        }

        private static byte[] readAll(InputStream in) throws IOException {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX) {
                    return null;
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }

        private static String md5(byte[] b) throws Exception {
            return hex(MessageDigest.getInstance("MD5").digest(b));
        }

        private static String hex(byte[] b) {
            StringBuilder sb = new StringBuilder();
            for (byte x : b) {
                sb.append(String.format(Locale.US, "%02x", x));
            }
            return sb.toString();
        }
    }
}
