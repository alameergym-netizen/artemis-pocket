package com.limelight.pocket.pclib;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reading what the Steam Web API and people type (pure Java, tested). */
public final class SteamParsers {
    private SteamParsers() {}

    private static final Pattern PROFILE = Pattern.compile("steamcommunity\\.com/profiles/(\\d{17})", Pattern.CASE_INSENSITIVE);
    private static final Pattern VANITY = Pattern.compile("steamcommunity\\.com/id/([A-Za-z0-9_-]{2,32})", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_VANITY = Pattern.compile("[A-Za-z0-9_-]{2,32}");
    private static final Pattern API_KEY = Pattern.compile("[0-9A-Fa-f]{32}");

    /**
     * What someone typed for their Steam account: a SteamID64, a profile link, or a custom URL
     * name. Returns the 17-digit id, "vanity:<name>" when it must be looked up first, or null.
     */
    public static String accountInput(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return null;
        }
        Matcher m = PROFILE.matcher(t);
        if (m.find()) {
            return m.group(1);
        }
        m = VANITY.matcher(t);
        if (m.find()) {
            return "vanity:" + m.group(1);
        }
        if (t.length() == 17 && PcGame.isDigits(t.substring(0, 12)) && PcGame.isDigits(t.substring(12))) {
            return t;
        }
        if (t.contains("/") || t.contains(".")) {
            return null;
        }
        return BARE_VANITY.matcher(t).matches() ? "vanity:" + t : null;
    }

    /** A Steam Web API key looks like 32 hex digits. */
    public static boolean looksLikeApiKey(String text) {
        return text != null && API_KEY.matcher(text.trim()).matches();
    }

    private static JsonObject object(String json) {
        try {
            JsonElement e = JsonParser.parseString(json);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonObject child(JsonObject o, String key) {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o != null ? o.get(key) : null;
        if (e == null || !e.isJsonPrimitive()) {
            return "";
        }
        return e.getAsString();
    }

    private static long number(JsonObject o, String key) {
        JsonElement e = o != null ? o.get(key) : null;
        if (e == null || !e.isJsonPrimitive()) {
            return 0;
        }
        try {
            return e.getAsLong();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    /** ISteamUser/ResolveVanityURL: the SteamID64, or null when there's no such name. */
    public static String vanityResult(String json) {
        JsonObject r = child(object(json), "response");
        if (r == null || number(r, "success") != 1) {
            return null;
        }
        String id = text(r, "steamid");
        return id.length() == 17 && PcGame.isDigits(id.substring(0, 12)) ? id : null;
    }

    /**
     * IPlayerService/GetOwnedGames (with include_appinfo): the account's games, as not installed
     * (merging with the PC's games tells which are). Null when the reply is unusable (a private
     * profile answers with an empty response).
     */
    public static List<PcGame> ownedGames(String json) {
        JsonObject r = child(object(json), "response");
        if (r == null) {
            return null;
        }
        JsonElement games = r.get("games");
        if (games == null || !games.isJsonArray()) {
            return r.has("game_count") ? new ArrayList<>() : null;
        }
        List<PcGame> out = new ArrayList<>();
        JsonArray array = games.getAsJsonArray();
        for (JsonElement e : array) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject g = e.getAsJsonObject();
            long appId = number(g, "appid");
            String name = text(g, "name").trim();
            if (appId <= 0 || name.isEmpty()) {
                continue;
            }
            PcGame game = new PcGame(PcGame.STEAM, String.valueOf(appId), name, PcGame.NOT_INSTALLED);
            game.owned = true;
            game.playtimeMin = (int) Math.min(Integer.MAX_VALUE, number(g, "playtime_forever"));
            game.lastPlayed = number(g, "rtime_last_played");
            game.artSteamId = game.id;
            out.add(game);
        }
        return out;
    }

    /** ISteamUser/GetPlayerSummaries: the account's display name ("" when unknown). */
    public static String personaName(String json) {
        JsonObject r = child(object(json), "response");
        JsonElement players = r != null ? r.get("players") : null;
        if (players == null || !players.isJsonArray() || players.getAsJsonArray().size() == 0) {
            return "";
        }
        JsonElement first = players.getAsJsonArray().get(0);
        return first.isJsonObject() ? text(first.getAsJsonObject(), "personaname").trim() : "";
    }

    /** Steam's CDN art for an app: the wide hero, the logo and the 2:3 cover. */
    public static String heroUrl(String appId) {
        return "https://cdn.cloudflare.steamstatic.com/steam/apps/" + appId + "/library_hero.jpg";
    }

    public static String logoUrl(String appId) {
        return "https://cdn.cloudflare.steamstatic.com/steam/apps/" + appId + "/logo.png";
    }

    public static String coverUrl(String appId) {
        return "https://cdn.cloudflare.steamstatic.com/steam/apps/" + appId + "/library_600x900.jpg";
    }

    public static String headerUrl(String appId) {
        return "https://cdn.cloudflare.steamstatic.com/steam/apps/" + appId + "/header.jpg";
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
