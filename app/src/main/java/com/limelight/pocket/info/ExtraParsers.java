package com.limelight.pocket.info;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Java: reads IGDB's answers and Google Play's app pages into what the game page shows,
 * and picks image sizes. No Android in here (unit tested).
 */
public final class ExtraParsers {
    private ExtraParsers() {
    }

    // ---- IGDB ------------------------------------------------------------------------------

    /** The fields asked of IGDB for a game. */
    public static final String IGDB_FIELDS = "fields name,summary,storyline,first_release_date,genres.name,"
            + "involved_companies.developer,involved_companies.publisher,involved_companies.company.name,"
            + "screenshots.image_id,artworks.image_id,videos.video_id,videos.name,cover.image_id,"
            + "aggregated_rating,url,category,version_parent;";

    /** The IGDB query for a name (quotes in it can't break out of the search string). */
    public static String igdbQuery(String name) {
        String clean = name == null ? "" : name.replace("\\", " ").replace("\"", " ").trim();
        return "search \"" + clean + "\"; " + IGDB_FIELDS + " limit 10;";
    }

    public static String igdbImage(String imageId, String size) {
        return "https://images.igdb.com/igdb/image/upload/t_" + size + "/" + imageId + ".jpg";
    }

    /** The game among IGDB's results that is this name (the same name, ignoring case and marks), or null. */
    public static JsonObject igdbBest(String json, String name) {
        JsonArray games = array(json);
        if (games == null) {
            return null;
        }
        String want = InfoParsers.normalize(name);
        JsonObject loose = null;
        for (JsonElement e : games) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject g = e.getAsJsonObject();
            String n = InfoParsers.normalize(string(g, "name"));
            if (n.isEmpty()) {
                continue;
            }
            // Editions and ports point at their main game: the main game first
            boolean main = !g.has("version_parent");
            if (n.equals(want) && main) {
                return g;
            }
            if (loose == null && n.equals(want)) {
                loose = g;
            }
        }
        return loose;
    }

    /** One IGDB game as the page's information, or null. */
    public static GameInfo parseIgdb(String json, String name) {
        JsonObject g = igdbBest(json, name);
        if (g == null) {
            return null;
        }
        GameInfo info = new GameInfo();
        info.source = GameInfo.SOURCE_IGDB;
        info.title = string(g, "name");
        String summary = string(g, "summary");
        String story = string(g, "storyline");
        info.shortDescription = summary != null ? firstSentences(summary, 240) : null;
        info.about = summary != null ? summary + (story != null ? "\n\n" + story : "") : story;
        long released = number(g, "first_release_date");
        if (released > 0) {
            SimpleDateFormat f = new SimpleDateFormat("d MMM, yyyy", Locale.ENGLISH);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            info.releaseDate = f.format(new Date(released * 1000));
        }
        for (JsonObject genre : objects(g, "genres")) {
            String n = string(genre, "name");
            if (n != null) {
                info.genres.add(n);
            }
        }
        for (JsonObject c : objects(g, "involved_companies")) {
            JsonObject company = c.has("company") && c.get("company").isJsonObject() ? c.getAsJsonObject("company") : null;
            String n = company != null ? string(company, "name") : null;
            if (n == null) {
                continue;
            }
            if (bool(c, "developer") && !info.developers.contains(n)) {
                info.developers.add(n);
            }
            if (bool(c, "publisher") && !info.publishers.contains(n)) {
                info.publishers.add(n);
            }
        }
        double rating = decimal(g, "aggregated_rating");
        info.metacritic = rating > 0 ? (int) Math.round(rating) : 0;
        info.pageUrl = string(g, "url");
        for (JsonObject v : objects(g, "videos")) {
            String id = string(v, "video_id");
            if (id == null || !id.matches("[A-Za-z0-9_-]{6,20}")) {
                continue;
            }
            GameInfo.Movie m = new GameInfo.Movie();
            m.youtube = id;
            m.name = string(v, "name");
            m.thumb = "https://img.youtube.com/vi/" + id + "/hqdefault.jpg";
            m.highlight = info.movies.isEmpty();
            info.movies.add(m);
        }
        for (JsonObject s : objects(g, "screenshots")) {
            String id = string(s, "image_id");
            if (id != null) {
                info.screenshots.add(new GameInfo.Shot(igdbImage(id, "screenshot_med"), igdbImage(id, "1080p")));
            }
        }
        for (JsonObject a : objects(g, "artworks")) {
            String id = string(a, "image_id");
            if (id != null) {
                if (info.background == null) {
                    info.background = igdbImage(id, "1080p");
                }
                info.screenshots.add(new GameInfo.Shot(igdbImage(id, "screenshot_med"), igdbImage(id, "1080p")));
            }
        }
        JsonObject cover = g.has("cover") && g.get("cover").isJsonObject() ? g.getAsJsonObject("cover") : null;
        String coverId = cover != null ? string(cover, "image_id") : null;
        if (coverId != null) {
            info.headerImage = igdbImage(coverId, "cover_big_2x");
        }
        return info;
    }

    /** The cover and the first artwork of the IGDB game ({cover, artwork}, either may be null). */
    public static String[] igdbArt(String json, String name) {
        JsonObject g = igdbBest(json, name);
        if (g == null) {
            return null;
        }
        String cover = null;
        String art = null;
        JsonObject c = g.has("cover") && g.get("cover").isJsonObject() ? g.getAsJsonObject("cover") : null;
        if (c != null && string(c, "image_id") != null) {
            cover = igdbImage(string(c, "image_id"), "cover_big_2x");
        }
        for (JsonObject a : objects(g, "artworks")) {
            if (string(a, "image_id") != null) {
                art = igdbImage(string(a, "image_id"), "1080p");
                break;
            }
        }
        if (art == null) {
            for (JsonObject s : objects(g, "screenshots")) {
                if (string(s, "image_id") != null) {
                    art = igdbImage(string(s, "image_id"), "1080p");
                    break;
                }
            }
        }
        return new String[]{cover, art};
    }

    /** Twitch's token answer: {token, seconds it lasts}, or null. */
    public static String[] twitchToken(String json) {
        JsonObject o = object(json);
        if (o == null || string(o, "access_token") == null) {
            return null;
        }
        return new String[]{string(o, "access_token"), String.valueOf(number(o, "expires_in"))};
    }

    // ---- Google Play -----------------------------------------------------------------------

    private static final Pattern LD_JSON = Pattern.compile(
            "<script[^>]*type=\"application/ld\\+json\"[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern IMG = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z-]+)=\"([^\"]*)\"");
    private static final Pattern DESCRIPTION = Pattern.compile(
            "<div[^>]*data-g-id=\"description\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern META = Pattern.compile(
            "<meta\\s+(?:property|name)=\"(og:title|og:image|description|og:description)\"\\s+content=\"([^\"]*)\"",
            Pattern.CASE_INSENSITIVE);

    /** What a Google Play app page says: name, description, developer, icon, feature graphic, screenshots. */
    public static final class PlayApp {
        public String name;
        public String description;
        public String developer;
        public String category;
        public String icon;
        public String featureGraphic;
        public List<String> screenshots = new ArrayList<>();

        public boolean isGame() {
            return category != null && category.toUpperCase(Locale.ROOT).startsWith("GAME");
        }
    }

    /** A Play image at about this width (Play serves any size: "=w1280"). */
    public static String playSized(String url, int width) {
        if (url == null) {
            return null;
        }
        int eq = url.lastIndexOf('=');
        String base = eq > url.indexOf("googleusercontent.com") && eq > 0 ? url.substring(0, eq) : url;
        return base + "=w" + width;
    }

    public static PlayApp parsePlay(String html) {
        if (html == null || html.isEmpty()) {
            return null;
        }
        PlayApp app = new PlayApp();
        Matcher ld = LD_JSON.matcher(html);
        while (ld.find()) {
            JsonObject o = object(unescapeHtml(ld.group(1)).trim());
            if (o == null || !"SoftwareApplication".equals(string(o, "@type"))) {
                continue;
            }
            app.name = string(o, "name");
            app.description = string(o, "description");
            app.category = string(o, "applicationCategory");
            app.icon = string(o, "image");
            if (o.has("author") && o.get("author").isJsonObject()) {
                app.developer = string(o.getAsJsonObject("author"), "name");
            }
            break;
        }
        Matcher meta = META.matcher(html);
        while (meta.find()) {
            String key = meta.group(1).toLowerCase(Locale.ROOT);
            String value = unescapeHtml(meta.group(2));
            if (key.equals("og:title") && app.name == null) {
                app.name = value.replaceAll("\\s*[-\u2013]\\s*Apps on Google Play\\s*$", "").trim();
            }
            else if ((key.equals("description") || key.equals("og:description")) && app.description == null) {
                app.description = value;
            }
            else if (key.equals("og:image") && app.icon == null) {
                app.icon = value;
            }
        }
        Matcher d = DESCRIPTION.matcher(html);
        if (d.find()) {
            String text = InfoParsers.htmlToText(d.group(1));
            if (text != null && text.length() > (app.description != null ? app.description.length() : 0)) {
                app.description = text;
            }
        }
        Matcher img = IMG.matcher(html);
        while (img.find()) {
            String tag = img.group();
            String src = null;
            String alt = "";
            Matcher a = ATTR.matcher(tag);
            while (a.find()) {
                String k = a.group(1).toLowerCase(Locale.ROOT);
                if (k.equals("src")) {
                    src = unescapeHtml(a.group(2));
                }
                else if (k.equals("alt")) {
                    alt = unescapeHtml(a.group(2)).toLowerCase(Locale.ROOT);
                }
            }
            if (src == null || !src.contains("googleusercontent.com")) {
                continue;
            }
            if (alt.contains("screenshot")) {
                String big = playSized(src, 1600);
                if (!app.screenshots.contains(big) && app.screenshots.size() < 16) {
                    app.screenshots.add(big);
                }
            }
            else if (alt.contains("cover art") || alt.contains("feature graphic")) {
                if (app.featureGraphic == null) {
                    app.featureGraphic = playSized(src, 1920);
                }
            }
            else if (alt.contains("icon image") && app.icon == null) {
                app.icon = src;
            }
        }
        if (app.name == null && app.description == null && app.screenshots.isEmpty()) {
            return null;
        }
        return app;
    }

    /** A Play app as the page's information. */
    public static GameInfo playInfo(PlayApp app) {
        GameInfo info = new GameInfo();
        info.source = GameInfo.SOURCE_PLAY;
        info.title = app.name;
        info.about = app.description;
        info.shortDescription = app.description != null ? firstSentences(app.description, 240) : null;
        if (app.developer != null) {
            info.developers.add(app.developer);
        }
        for (String s : app.screenshots) {
            info.screenshots.add(new GameInfo.Shot(playSized(s, 640), s));
        }
        info.background = app.featureGraphic;
        info.headerImage = app.featureGraphic;
        return info;
    }

    // ---- Helpers ---------------------------------------------------------------------------

    static String firstSentences(String text, int max) {
        String t = text.replace('\n', ' ').trim();
        if (t.length() <= max) {
            return t;
        }
        int cut = t.lastIndexOf(". ", max);
        return cut > 40 ? t.substring(0, cut + 1) : t.substring(0, max).trim() + "\u2026";
    }

    static String unescapeHtml(String s) {
        return s.replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&");
    }

    private static JsonObject object(String json) {
        try {
            JsonElement e = JsonParser.parseString(json);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonArray array(String json) {
        try {
            JsonElement e = JsonParser.parseString(json);
            return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<JsonObject> objects(JsonObject o, String key) {
        List<JsonObject> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray(key)) {
                if (e.isJsonObject()) {
                    out.add(e.getAsJsonObject());
                }
            }
        }
        return out;
    }

    private static String string(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) {
            return null;
        }
        String s = o.get(key).getAsString().trim();
        return s.isEmpty() ? null : s;
    }

    private static long number(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static double decimal(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static boolean bool(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- YouTube (a trailer for any game, when no store has one) -------------------------------

    private static final java.util.regex.Pattern YT_VIDEO = java.util.regex.Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"");

    /** The first video of a YouTube results page, or null. */
    public static String youtubeFirst(String html) {
        if (html == null) {
            return null;
        }
        java.util.regex.Matcher m = YT_VIDEO.matcher(html);
        return m.find() ? m.group(1) : null;
    }
}
