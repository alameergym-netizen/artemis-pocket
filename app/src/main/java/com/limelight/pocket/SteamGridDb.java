package com.limelight.pocket;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * A small client for SteamGridDB (https://www.steamgriddb.com), the community library of game
 * art: covers ("grids"), wide backgrounds ("heroes") and logos. Each person uses their own free
 * API key (steamgriddb.com, Preferences, API). Blocking calls: run them off the main thread.
 */
public final class SteamGridDb {

    private static final String BASE = "https://www.steamgriddb.com/api/v2";

    public static final int COVER = PocketArt.COVER;
    public static final int HERO = PocketArt.HERO;
    public static final int LOGO = PocketArt.LOGO;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    /** A game in SteamGridDB's catalogue. */
    public static final class Game {
        public final int id;
        public final String name;
        public final int year;
        public final boolean verified;

        Game(int id, String name, int year, boolean verified) {
            this.id = id;
            this.name = name;
            this.year = year;
            this.verified = verified;
        }
    }

    /** One piece of art. */
    public static final class Image {
        public final int id;
        public final String url;
        public final String thumb;
        public final String style;
        public final int width;
        public final int height;
        public final String author;

        Image(int id, String url, String thumb, String style, int width, int height, String author) {
            this.id = id;
            this.url = url;
            this.thumb = thumb;
            this.style = style;
            this.width = width;
            this.height = height;
            this.author = author;
        }
    }

    /** The server said no: status 401/403 means the key isn't valid. */
    public static final class ApiException extends IOException {
        public final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public boolean badKey() {
            return status == 401 || status == 403;
        }
    }

    private SteamGridDb() {
    }

    /** True if the text looks like a SteamGridDB API key (32 hex characters). */
    public static boolean looksLikeKey(String text) {
        return text != null && text.trim().matches("[0-9a-fA-F]{32}");
    }

    private static JSONObject get(String key, String path) throws IOException {
        Request request = new Request.Builder()
                .url(BASE + path)
                .header("Authorization", "Bearer " + key.trim())
                .header("User-Agent", "PocketNexus")
                .build();
        try (Response response = CLIENT.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body != null ? body.string() : "";
            if (response.code() == 404) {
                // No art / no game: an empty answer, not an error
                return new JSONObject().put("success", true).put("data", new JSONArray());
            }
            if (!response.isSuccessful()) {
                throw new ApiException(response.code(), "HTTP " + response.code());
            }
            JSONObject json = new JSONObject(text);
            if (!json.optBoolean("success", false)) {
                JSONArray errors = json.optJSONArray("errors");
                throw new ApiException(response.code(), errors != null ? errors.toString() : "error");
            }
            return json;
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Games whose names match term, best match first. */
    public static List<Game> search(String key, String term) throws IOException {
        List<Game> out = new ArrayList<>();
        String clean = term == null ? "" : term.replaceAll("[™®©]", "").trim();
        if (clean.isEmpty()) {
            return out;
        }
        JSONArray data = get(key, "/search/autocomplete/" + Uri.encode(clean)).optJSONArray("data");
        if (data == null) {
            return out;
        }
        for (int i = 0; i < data.length(); i++) {
            JSONObject g = data.optJSONObject(i);
            if (g == null) {
                continue;
            }
            int year = 0;
            long release = g.optLong("release_date", 0);
            if (release > 0) {
                Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                cal.setTimeInMillis(release * 1000L);
                year = cal.get(Calendar.YEAR);
            }
            out.add(new Game(g.optInt("id"), g.optString("name"), year, g.optBoolean("verified")));
        }
        return out;
    }

    /** Art of one kind for a game (50 per page, the community's favourites first). */
    public static List<Image> images(String key, int gameId, int kind, int page) throws IOException {
        String path;
        switch (kind) {
            case HERO:
                path = "/heroes/game/" + gameId + "?types=static&nsfw=false&humor=false";
                break;
            case LOGO:
                path = "/logos/game/" + gameId + "?types=static&nsfw=false&humor=false";
                break;
            case PocketArt.WIDE:
                // Wide capsules, the shape of Steam's library cards
                path = "/grids/game/" + gameId + "?dimensions=920x430,460x215&types=static&nsfw=false&humor=false";
                break;
            case PocketArt.ICON:
                path = "/icons/game/" + gameId + "?types=static&nsfw=false&humor=false";
                break;
            case PocketArt.SQUARE:
                // Square capsules, the shape of a console home screen's tiles
                path = "/grids/game/" + gameId + "?dimensions=512x512,1024x1024&types=static&nsfw=false&humor=false";
                break;
            default:
                // Portrait covers, the shape of the library's cards
                path = "/grids/game/" + gameId + "?dimensions=600x900,342x482,660x930&types=static&nsfw=false&humor=false";
                break;
        }
        path += "&page=" + Math.max(0, page);
        JSONArray data = get(key, path).optJSONArray("data");
        List<Image> out = new ArrayList<>();
        if (data == null) {
            return out;
        }
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String url = o.optString("url", "");
            if (url.isEmpty()) {
                continue;
            }
            JSONObject author = o.optJSONObject("author");
            out.add(new Image(o.optInt("id"), url, o.optString("thumb", url), o.optString("style", ""),
                    o.optInt("width"), o.optInt("height"), author != null ? author.optString("name", "") : ""));
        }
        return out;
    }

    /** Downloads a file (art or a thumbnail), at most max bytes. */
    public static byte[] download(String url, int max) throws IOException {
        Request request = new Request.Builder().url(url).header("User-Agent", "PocketNexus").build();
        try (Response response = CLIENT.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("HTTP " + response.code());
            }
            InputStream in = body.byteStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > max) {
                    throw new IOException("Too large");
                }
            }
            return out.toByteArray();
        }
    }

    /** Lower-case letters and digits only, without "the" and edition words, for comparing names. */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String n = name.toLowerCase(Locale.ROOT)
                .replaceAll("[™®©]", "")
                .replaceAll("\\b(the|edition|remastered|definitive|goty|game of the year)\\b", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        return n.replaceAll("\\s+", " ");
    }

    /**
     * Whether a search result is surely the same game (for picking art without asking): the
     * names match, or one contains the other and is most of it.
     */
    public static boolean sameGame(String appName, String resultName) {
        String a = normalize(appName);
        String b = normalize(resultName);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        String longer = a.length() >= b.length() ? a : b;
        String shorter = a.length() >= b.length() ? b : a;
        return longer.contains(shorter) && shorter.length() >= 0.75f * longer.length();
    }
}
