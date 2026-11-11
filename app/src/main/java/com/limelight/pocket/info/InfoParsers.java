package com.limelight.pocket.info;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Java: reads Steam's and Wikipedia's answers, turns store HTML into text, and decides which
 * search result is the game. No Android in here, so all of it is covered by unit tests.
 */
public final class InfoParsers {

    private InfoParsers() {
    }

    // ---- Steam store search ------------------------------------------------------------------

    /** One result of Steam's store search. */
    public static final class Candidate {
        public final int id;
        public final String name;

        public Candidate(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** storesearch/?term=...: the apps it found (games and the like), in Steam's order. */
    public static List<Candidate> parseSteamSearch(String json) {
        List<Candidate> out = new ArrayList<>();
        JsonObject root = object(json);
        if (root == null) {
            return out;
        }
        JsonArray items = array(root, "items");
        if (items == null) {
            return out;
        }
        for (JsonElement e : items) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject item = e.getAsJsonObject();
            String type = string(item, "type");
            if (type != null && !"app".equals(type) && !"game".equals(type)) {
                continue;
            }
            int id = integer(item, "id");
            String name = string(item, "name");
            if (id > 0 && name != null) {
                out.add(new Candidate(id, name));
            }
        }
        return out;
    }

    /**
     * The result that is surely this game, or null. Exact names (once tidied) win; otherwise a
     * name that contains the other and is most of it ("Control" vs "Control Ultimate Edition"),
     * preferring the shortest such (the base game, not a DLC or soundtrack).
     */
    public static Candidate bestMatch(String appName, List<Candidate> candidates) {
        String wanted = normalize(appName);
        if (wanted.isEmpty() || candidates == null) {
            return null;
        }
        Candidate close = null;
        int closeLen = Integer.MAX_VALUE;
        for (Candidate c : candidates) {
            String n = normalize(c.name);
            if (n.isEmpty() || looksLikeExtra(c.name) && !looksLikeExtra(appName)) {
                continue;
            }
            if (n.equals(wanted)) {
                return c;
            }
            if (similar(wanted, n) && n.length() < closeLen) {
                close = c;
                closeLen = n.length();
            }
        }
        return close;
    }

    /** Soundtracks, DLC, demos, tools: never what a game's name means. */
    static boolean looksLikeExtra(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.contains("soundtrack") || n.contains(" ost") || n.endsWith(" demo") || n.contains("(demo)")
                || n.contains(" dlc") || n.contains("season pass") || n.contains("artbook")
                || n.contains("dedicated server") || n.contains(" sdk") || n.contains("playtest");
    }

    static boolean similar(String a, String b) {
        String longer = a.length() >= b.length() ? a : b;
        String shorter = a.length() >= b.length() ? b : a;
        if (shorter.length() < 3) {
            return false;
        }
        return (longer.startsWith(shorter + " ") || longer.endsWith(" " + shorter) || longer.contains(" " + shorter + " "))
                && shorter.length() >= 0.6f * longer.length();
    }

    /** Lower case, no marks, no edition words, words separated by single spaces. */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        // The marks go first (NFKD would turn the trademark sign into "TM")
        String n = name.replaceAll("[™®©]", " ");
        n = Normalizer.normalize(n, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        n = n.toLowerCase(Locale.ROOT)
                .replace("&", " and ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\b(the|edition|remastered|remaster|definitive|goty|game of the year|complete|ultimate|deluxe|enhanced|director s cut|directors cut)\\b", " ")
                .trim();
        // Roman numerals as digits (Final Fantasy VII = 7)
        n = (" " + n + " ").replace(" ii ", " 2 ").replace(" iii ", " 3 ").replace(" iv ", " 4 ")
                .replace(" v ", " 5 ").replace(" vi ", " 6 ").replace(" vii ", " 7 ").replace(" viii ", " 8 ")
                .replace(" ix ", " 9 ").replace(" x ", " 10 ");
        return n.trim().replaceAll("\\s+", " ");
    }

    // ---- Steam app details -------------------------------------------------------------------

    /** appdetails?appids=ID: the game's page, or null when Steam has none (or it isn't a game). */
    public static GameInfo parseSteamDetails(String json, int appId) {
        JsonObject root = object(json);
        if (root == null) {
            return null;
        }
        JsonObject entry = root.has(String.valueOf(appId)) && root.get(String.valueOf(appId)).isJsonObject()
                ? root.getAsJsonObject(String.valueOf(appId)) : null;
        if (entry == null || !bool(entry, "success")) {
            return null;
        }
        JsonObject data = entry.has("data") && entry.get("data").isJsonObject() ? entry.getAsJsonObject("data") : null;
        if (data == null) {
            return null;
        }
        GameInfo info = new GameInfo();
        info.source = GameInfo.SOURCE_STEAM;
        info.steamAppId = integer(data, "steam_appid") > 0 ? integer(data, "steam_appid") : appId;
        info.title = string(data, "name");
        info.shortDescription = htmlToText(string(data, "short_description"));
        String about = string(data, "about_the_game");
        if (about == null || about.trim().isEmpty()) {
            about = string(data, "detailed_description");
        }
        info.about = htmlToText(about);
        info.headerImage = string(data, "header_image");
        info.background = string(data, "background_raw") != null ? string(data, "background_raw") : string(data, "background");
        info.website = string(data, "website");
        info.pageUrl = "https://store.steampowered.com/app/" + info.steamAppId + "/";
        info.developers = strings(data, "developers");
        info.publishers = strings(data, "publishers");
        JsonArray genres = array(data, "genres");
        if (genres != null) {
            for (JsonElement g : genres) {
                if (g.isJsonObject()) {
                    String d = string(g.getAsJsonObject(), "description");
                    if (d != null && !d.isEmpty()) {
                        info.genres.add(d);
                    }
                }
            }
        }
        if (data.has("metacritic") && data.get("metacritic").isJsonObject()) {
            info.metacritic = integer(data.getAsJsonObject("metacritic"), "score");
        }
        if (data.has("release_date") && data.get("release_date").isJsonObject()) {
            JsonObject rd = data.getAsJsonObject("release_date");
            info.comingSoon = bool(rd, "coming_soon");
            info.releaseDate = string(rd, "date");
        }
        JsonArray shots = array(data, "screenshots");
        if (shots != null) {
            for (JsonElement e : shots) {
                if (e.isJsonObject()) {
                    String thumb = string(e.getAsJsonObject(), "path_thumbnail");
                    String full = string(e.getAsJsonObject(), "path_full");
                    if (full != null || thumb != null) {
                        info.screenshots.add(new GameInfo.Shot(thumb != null ? thumb : full, full != null ? full : thumb));
                    }
                }
            }
        }
        JsonArray movies = array(data, "movies");
        if (movies != null) {
            for (JsonElement e : movies) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject m = e.getAsJsonObject();
                GameInfo.Movie movie = new GameInfo.Movie();
                movie.name = string(m, "name");
                movie.thumb = string(m, "thumbnail");
                movie.highlight = bool(m, "highlight");
                // Steam streams trailers now (HLS / DASH); older entries had files
                movie.hls = string(m, "hls_h264");
                movie.dash = string(m, "dash_h264");
                if (m.has("mp4") && m.get("mp4").isJsonObject()) {
                    JsonObject mp4 = m.getAsJsonObject("mp4");
                    movie.mp4 = string(mp4, "max") != null ? string(mp4, "max") : string(mp4, "480");
                }
                if (movie.playable() != null) {
                    info.movies.add(movie);
                }
            }
        }
        return info;
    }

    // ---- Wikipedia ---------------------------------------------------------------------------

    /** w/api.php?action=query&list=search: the titles found, best first. */
    public static List<String> parseWikiSearch(String json) {
        List<String> out = new ArrayList<>();
        JsonObject root = object(json);
        if (root == null || !root.has("query") || !root.get("query").isJsonObject()) {
            return out;
        }
        JsonArray results = array(root.getAsJsonObject("query"), "search");
        if (results == null) {
            return out;
        }
        for (JsonElement e : results) {
            if (e.isJsonObject()) {
                String t = string(e.getAsJsonObject(), "title");
                if (t != null) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static final Pattern QUALIFIED = Pattern.compile("^(.*?)\\s*\\(([^)]*)\\)\\s*$");

    /**
     * The search result that is the game itself, or null: its name with a "(video game)" kind of
     * qualifier first, then its bare name, then a close name. Disambiguation pages and namesakes
     * ("(film)", "(album)") never count.
     */
    public static String bestWikiTitle(String appName, List<String> titles) {
        String wanted = normalize(appName);
        if (wanted.isEmpty() || titles == null) {
            return null;
        }
        String bare = null;
        String close = null;
        for (String t : titles) {
            Matcher m = QUALIFIED.matcher(t);
            boolean hasQualifier = m.matches();
            String base = hasQualifier ? m.group(1) : t;
            String qualifier = hasQualifier ? m.group(2).toLowerCase(Locale.ROOT) : "";
            if (qualifier.contains("disambiguation") || qualifier.contains("توضيح")) {
                continue;
            }
            boolean aboutGame = qualifier.contains("game") || qualifier.contains("لعبة")
                    || qualifier.contains("jeu") || qualifier.contains("spiel") || qualifier.contains("videojuego");
            String n = normalize(base);
            if (n.isEmpty()) {
                continue;
            }
            if (n.equals(wanted)) {
                if (aboutGame) {
                    return t;
                }
                if (qualifier.isEmpty() && bare == null) {
                    bare = t;
                }
            }
            else if (close == null && (qualifier.isEmpty() || aboutGame) && similar(wanted, n)) {
                close = t;
            }
        }
        return bare != null ? bare : close;
    }

    /** api/rest_v1/page/summary/TITLE: the article's lead, or null for a disambiguation page. */
    public static GameInfo parseWikiSummary(String json) {
        JsonObject root = object(json);
        if (root == null) {
            return null;
        }
        if ("disambiguation".equals(string(root, "type"))) {
            return null;
        }
        String extract = string(root, "extract");
        if (extract == null || extract.trim().isEmpty()) {
            return null;
        }
        GameInfo info = new GameInfo();
        info.source = GameInfo.SOURCE_WIKIPEDIA;
        info.title = string(root, "title");
        info.about = extract.trim();
        String description = string(root, "description");
        info.shortDescription = firstSentence(info.about);
        if (description != null && info.shortDescription == null) {
            info.shortDescription = description;
        }
        if (root.has("content_urls") && root.get("content_urls").isJsonObject()) {
            JsonObject urls = root.getAsJsonObject("content_urls");
            if (urls.has("desktop") && urls.get("desktop").isJsonObject()) {
                info.pageUrl = string(urls.getAsJsonObject("desktop"), "page");
            }
        }
        if (root.has("originalimage") && root.get("originalimage").isJsonObject()) {
            info.headerImage = string(root.getAsJsonObject("originalimage"), "source");
        }
        return info;
    }

    static String firstSentence(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = Pattern.compile("^(.{20,280}?[.!?؟。])(\\s|$)", Pattern.DOTALL).matcher(text.trim());
        return m.find() ? m.group(1).trim() : (text.length() <= 280 ? text.trim() : text.substring(0, 277).trim() + "…");
    }

    // ---- HTML to text ------------------------------------------------------------------------

    /**
     * Store descriptions are HTML: paragraphs and line breaks become blank lines / new lines,
     * list items become "• ", headings stay as lines, images and everything else go, entities
     * are decoded and the spacing tidied.
     */
    public static String htmlToText(String html) {
        if (html == null) {
            return null;
        }
        String s = html;
        s = s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)<li[^>]*>", "\n• ");
        s = s.replaceAll("(?i)</(p|div|h[1-6]|ul|ol|li|tr)>", "\n\n");
        s = s.replaceAll("(?i)<(p|div|h[1-6]|ul|ol|tr)[^>]*>", "\n\n");
        s = s.replaceAll("(?s)<[^>]+>", "");
        s = decodeEntities(s);
        s = s.replace(' ', ' ');
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        s = s.replaceAll(" *\n *", "\n");
        // A bullet line and the next one stay together; otherwise at most one blank line
        s = s.replaceAll("\n{3,}", "\n\n");
        // Items of one list stay together (a paragraph before them keeps its blank line)
        String before;
        do {
            before = s;
            s = s.replaceAll("(• [^\n]*)\n\n(?=• )", "$1\n");
        } while (!s.equals(before));
        return s.trim();
    }

    static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        Matcher m = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String e = m.group(1);
            String r;
            if (e.startsWith("#x") || e.startsWith("#X")) {
                r = codePoint(e.substring(2), 16);
            }
            else if (e.startsWith("#")) {
                r = codePoint(e.substring(1), 10);
            }
            else {
                switch (e) {
                    case "amp": r = "&"; break;
                    case "lt": r = "<"; break;
                    case "gt": r = ">"; break;
                    case "quot": r = "\""; break;
                    case "apos": r = "'"; break;
                    case "nbsp": r = " "; break;
                    case "ndash": r = "–"; break;
                    case "mdash": r = "—"; break;
                    case "hellip": r = "…"; break;
                    case "trade": r = "™"; break;
                    case "reg": r = "®"; break;
                    case "copy": r = "©"; break;
                    case "rsquo": r = "’"; break;
                    case "lsquo": r = "‘"; break;
                    case "rdquo": r = "”"; break;
                    case "ldquo": r = "“"; break;
                    default: r = m.group(); break;
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String codePoint(String digits, int radix) {
        try {
            int cp = Integer.parseInt(digits, radix);
            return new String(Character.toChars(cp));
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ---- JSON helpers ------------------------------------------------------------------------

    private static JsonObject object(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonElement e = JsonParser.parseString(json);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonArray array(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : null;
    }

    private static String string(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonPrimitive()) {
            return null;
        }
        String s = o.get(key).getAsString();
        return s.isEmpty() ? null : s;
    }

    private static int integer(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : 0;
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

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        JsonArray a = array(o, key);
        if (a != null) {
            for (JsonElement e : a) {
                if (e.isJsonPrimitive() && !e.getAsString().trim().isEmpty()) {
                    out.add(e.getAsString().trim());
                }
            }
        }
        return out;
    }
}
