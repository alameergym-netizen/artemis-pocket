package com.limelight.pocket.info;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Review scores for a game from Wikipedia's "Video game reviews" box (the one every game article
 * has: Metacritic, OpenCritic, GameRankings for older games, and the critics: IGN, GameSpot,
 * Famitsu, Edge...). That box covers new games and old ones alike, on every system, with no key,
 * so with Steam's players and IGDB it gets nearly everything a rating. Pure Java (unit tested).
 */
public final class Ratings {
    private Ratings() {
    }

    /** The box's short names and what they stand for (aggregates first). */
    private static final Map<String, String> AGGREGATES = new LinkedHashMap<>();
    private static final Map<String, String> CRITICS = new HashMap<>();

    static {
        AGGREGATES.put("MC", "Metacritic");
        AGGREGATES.put("OC", "OpenCritic");
        AGGREGATES.put("GR", "GameRankings");
        AGGREGATES.put("MM", "MobyGames");

        String[][] critics = {
                {"IGN", "IGN"}, {"GSPOT", "GameSpot"}, {"FAM", "Famitsu"}, {"EDGE", "Edge"},
                {"EGM", "EGM"}, {"EUROG", "Eurogamer"}, {"GI", "Game Informer"}, {"DESTRUCT", "Destructoid"},
                {"POLY", "Polygon"}, {"GRADAR", "GamesRadar+"}, {"PCGUS", "PC Gamer"}, {"PCGUK", "PC Gamer UK"},
                {"NLIFE", "Nintendo Life"}, {"PUSHSQ", "Push Square"}, {"PURESX", "Pure Xbox"}, {"NWR", "Nintendo World Report"},
                {"NP", "Nintendo Power"}, {"ONM", "Official Nintendo Magazine"}, {"OPM", "PlayStation Magazine"},
                {"OPMUK", "PlayStation Magazine UK"}, {"OXM", "Xbox Magazine"}, {"OXMUK", "Xbox Magazine UK"},
                {"GAMEREV", "Game Revolution"}, {"GSPY", "GameSpy"}, {"1UP", "1UP"}, {"ALLGAME", "AllGame"},
                {"CVG", "CVG"}, {"GAMEPRO", "GamePro"}, {"GAMEFAN", "GameFan"}, {"GMASTER", "GamesMaster"},
                {"GTM", "GamesTM"}, {"GT", "GameTrailers"}, {"GB", "Giant Bomb"}, {"HYPER", "Hyper"},
                {"JOYSTIQ", "Joystiq"}, {"GAMEZONE", "GameZone"}, {"VG", "VideoGamer"}, {"XPLAY", "X-Play"},
                {"RPS", "Rock Paper Shotgun"}, {"TA", "TouchArcade"}, {"PCZONE", "PC Zone"}, {"PCPP", "PC PowerPlay"},
                {"PSM", "PlayStation: The Official Magazine"}, {"PSM3", "PSM3"}, {"PLAY", "Play"}, {"CRASH", "Crash"},
                {"ZZAP", "Zzap!64"}, {"YSINCLAIR", "Your Sinclair"}, {"SUSER", "Sinclair User"}, {"AACTION", "Amstrad Action"},
                {"AMTIX", "Amtix!"}, {"G4", "G4"}, {"ADVGAMERS", "Adventure Gamers"}, {"MAXPC", "Maximum PC"},
                {"PCF", "PC Format"}, {"PALGN", "PALGN"}, {"TX", "TeamXbox"}, {"GW", "GameWire"},
                {"NGC", "NGC Magazine"}, {"N64", "N64 Magazine"}, {"DRAGON", "Dragon"}, {"4P", "4Players"},
                {"GAMESTAR", "GameStar"}, {"JEUXVIDEO", "Jeuxvideo.com"}, {"GAMEKULT", "Gamekult"},
                {"HG101", "Hardcore Gaming 101"}, {"USGAMER", "USgamer"}, {"SHACKNEWS", "Shacknews"},
                {"VGC", "Video Games Chronicle"}, {"GAMEINFORMER", "Game Informer"}, {"NEXTGEN", "Next Generation"},
        };
        for (String[] c : critics) {
            CRITICS.put(c[0], c[1]);
        }
    }

    private static final Pattern START = Pattern.compile("\\{\\{\\s*(video\\s*game\\s*reviews|vg\\s*reviews)\\s*[|}]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern REF_SELF = Pattern.compile("<ref[^>]*/\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern REF = Pattern.compile("<ref[^>]*>.*?</ref\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NOTE_TPL = Pattern.compile("\\{\\{\\s*(efn|refn|sfn|r|cn|citation needed|dubious)\\s*(\\|[^{}]*)?\\}\\}",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RATING_TPL = Pattern.compile(
            "\\{\\{\\s*rating\\s*\\|\\s*([0-9.]+)\\s*\\|\\s*([0-9.]+)[^{}]*\\}\\}", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK = Pattern.compile("\\[\\[(?:[^\\]|]*\\|)?([^\\]]*)\\]\\]");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern FRACTION = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:/|out of)\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern PERCENT = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*%");
    private static final Pattern GRADE = Pattern.compile("(?<![A-Za-z])([A-F])([+\\-−–]?)(?![A-Za-z])");
    private static final Pattern STARS = Pattern.compile("([★✯✰⭐]+)");

    /** The ratings in an article's wikitext (empty when it has no reviews box). */
    public static List<GameInfo.Rating> fromWikitext(String wikitext) {
        List<GameInfo.Rating> out = new ArrayList<>();
        String body = templateBody(wikitext);
        if (body == null) {
            return out;
        }
        Map<String, String> params = params(body);
        // The aggregates first (Metacritic, OpenCritic...), each once (the first platform given)
        for (Map.Entry<String, String> a : AGGREGATES.entrySet()) {
            String raw = first(params, a.getKey());
            GameInfo.Rating r = raw != null ? score(a.getValue(), raw, true) : null;
            if (r != null) {
                r.note = "critics";
                out.add(r);
            }
        }
        // Aggregates named in the box itself (agg1 / agg1Score)
        for (int i = 1; i <= 6; i++) {
            String name = params.get("AGG" + i);
            String raw = params.get("AGG" + i + "SCORE");
            if (name != null && raw != null) {
                GameInfo.Rating r = score(clean(name), raw, true);
                if (r != null && !has(out, r.source)) {
                    out.add(r);
                }
            }
        }
        // The critics, in the box's order
        for (Map.Entry<String, String> p : params.entrySet()) {
            String code = base(p.getKey());
            String name = CRITICS.get(code);
            if (name == null || has(out, name)) {
                continue;
            }
            GameInfo.Rating r = score(name, p.getValue(), false);
            if (r != null) {
                out.add(r);
            }
        }
        // Critics named in the box itself (rev1 / rev1Score)
        for (int i = 1; i <= 20; i++) {
            String name = params.get("REV" + i);
            String raw = params.get("REV" + i + "SCORE");
            if (name == null || raw == null) {
                continue;
            }
            String n = clean(name);
            GameInfo.Rating r = n.isEmpty() || has(out, n) ? null : score(n, raw, false);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    private static boolean has(List<GameInfo.Rating> list, String source) {
        for (GameInfo.Rating r : list) {
            if (r.source.equalsIgnoreCase(source)) {
                return true;
            }
        }
        return false;
    }

    /** "MC_PS4" -> "MC". */
    private static String base(String key) {
        int u = key.indexOf('_');
        return u > 0 ? key.substring(0, u) : key;
    }

    /** The value for a code, or its first platform's ("MC", else "MC_PC", "MC_PS4"...). */
    private static String first(Map<String, String> params, String code) {
        String v = params.get(code);
        if (v != null && !clean(v).isEmpty()) {
            return v;
        }
        for (Map.Entry<String, String> p : params.entrySet()) {
            if (p.getKey().startsWith(code + "_") && !clean(p.getValue()).isEmpty()) {
                return p.getValue();
            }
        }
        return null;
    }

    /** What's inside {{Video game reviews ... }} (null: no such box). */
    static String templateBody(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = START.matcher(text);
        if (!m.find()) {
            return null;
        }
        int start = m.start() + 2;
        int depth = 1;
        int i = start;
        while (i < text.length() - 1) {
            char c = text.charAt(i);
            char n = text.charAt(i + 1);
            if (c == '{' && n == '{') {
                depth++;
                i += 2;
                continue;
            }
            if (c == '}' && n == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i);
                }
                i += 2;
                continue;
            }
            i++;
        }
        return null;
    }

    /** The box's "| KEY = value" pairs (keys upper case; nested {{ }} and [[ ]] kept whole). */
    static Map<String, String> params(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> parts = new ArrayList<>();
        int depthCurly = 0;
        int depthSquare = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            char n = i + 1 < body.length() ? body.charAt(i + 1) : 0;
            if (c == '{' && n == '{') {
                depthCurly++;
                cur.append("{{");
                i++;
                continue;
            }
            if (c == '}' && n == '}' && depthCurly > 0) {
                depthCurly--;
                cur.append("}}");
                i++;
                continue;
            }
            if (c == '[' && n == '[') {
                depthSquare++;
                cur.append("[[");
                i++;
                continue;
            }
            if (c == ']' && n == ']' && depthSquare > 0) {
                depthSquare--;
                cur.append("]]");
                i++;
                continue;
            }
            if (c == '|' && depthCurly == 0 && depthSquare == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        parts.add(cur.toString());
        for (String p : parts) {
            int eq = p.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = p.substring(0, eq).trim().toUpperCase(Locale.ROOT).replace(" ", "");
            String value = p.substring(eq + 1).trim();
            if (!key.isEmpty() && !value.isEmpty() && !out.containsKey(key)) {
                out.put(key, value);
            }
        }
        return out;
    }

    /** A value with its references, notes, links and markup taken out. */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String s = COMMENT.matcher(raw).replaceAll("");
        s = REF.matcher(s).replaceAll("");
        s = REF_SELF.matcher(s).replaceAll("");
        s = NOTE_TPL.matcher(s).replaceAll("");
        s = RATING_TPL.matcher(s).replaceAll("$1/$2");
        s = LINK.matcher(s).replaceAll("$1");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = TAG.matcher(s).replaceAll("");
        s = s.replace("'''", "").replace("''", "").replace("&nbsp;", " ").replace(" ", " ");
        // Leftover templates ({{sort|...}}, {{nowrap|...}}): their last part
        s = s.replaceAll("\\{\\{[^{}|]*\\|([^{}]*)\\}\\}", "$1");
        return s.trim();
    }

    /** One score from a box value (the first one, when it lists several platforms). Null: none. */
    static GameInfo.Rating score(String source, String raw, boolean aggregate) {
        String s = clean(raw);
        if (s.isEmpty()) {
            return null;
        }
        Matcher f = FRACTION.matcher(s);
        if (f.find()) {
            double a = number(f.group(1));
            double b = number(f.group(2));
            if (b > 0 && a >= 0 && a <= b) {
                String value = trim(a) + "/" + trim(b);
                // Aggregates are written "85/100": just "85"
                if (aggregate && b == 100) {
                    value = trim(a);
                }
                return new GameInfo.Rating(source, value, (int) Math.round(a * 100 / b), null, aggregate, false);
            }
        }
        Matcher p = PERCENT.matcher(s);
        if (p.find()) {
            double a = number(p.group(1));
            if (a >= 0 && a <= 100) {
                return new GameInfo.Rating(source, trim(a) + "%", (int) Math.round(a), null, aggregate, false);
            }
        }
        Matcher st = STARS.matcher(s);
        if (st.find()) {
            int n = st.group(1).length();
            if (n <= 5) {
                return new GameInfo.Rating(source, n + "/5", n * 20, null, aggregate, false);
            }
        }
        Matcher g = GRADE.matcher(s);
        if (g.find() && s.length() <= 6) {
            String grade = g.group(1) + (g.group(2).isEmpty() ? "" : (g.group(2).equals("+") ? "+" : "−"));
            return new GameInfo.Rating(source, grade, gradeScore(g.group(1).charAt(0), g.group(2)), null, aggregate, false);
        }
        // A bare number out of 10 or 100 ("8.5", "92")
        Matcher num = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\b").matcher(s);
        if (num.find()) {
            double a = number(num.group(1));
            if (aggregate && a <= 100) {
                return new GameInfo.Rating(source, trim(a), (int) Math.round(a), null, true, false);
            }
            if (a <= 10) {
                return new GameInfo.Rating(source, trim(a) + "/10", (int) Math.round(a * 10), null, false, false);
            }
        }
        return null;
    }

    private static int gradeScore(char letter, String sign) {
        int base;
        switch (letter) {
            case 'A':
                base = 95;
                break;
            case 'B':
                base = 85;
                break;
            case 'C':
                base = 75;
                break;
            case 'D':
                base = 65;
                break;
            default:
                base = 45;
                break;
        }
        if ("+".equals(sign)) {
            return Math.min(100, base + 4);
        }
        if (!sign.isEmpty()) {
            return base - 4;
        }
        return base;
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String trim(double v) {
        if (Math.abs(v - Math.round(v)) < 0.001) {
            return String.valueOf(Math.round(v));
        }
        return String.valueOf(Math.round(v * 10) / 10.0);
    }

    /** A colour for a score out of 100, the way Metacritic does it (green, yellow, red). */
    public static int color(int score) {
        if (score < 0) {
            return 0xFF8A8F98;
        }
        return score >= 75 ? 0xFF4CC864 : score >= 50 ? 0xFFF5C542 : 0xFFF2545B;
    }

    /** Steam's words for its players' score ("Very Positive") from the percentage and the count. */
    public static String steamWords(int percent, int count) {
        if (count <= 0) {
            return null;
        }
        if (percent >= 95 && count >= 500) {
            return "Overwhelmingly Positive";
        }
        if (percent >= 80) {
            return count >= 50 ? "Very Positive" : "Positive";
        }
        if (percent >= 70) {
            return "Mostly Positive";
        }
        if (percent >= 40) {
            return "Mixed";
        }
        if (percent >= 20) {
            return "Mostly Negative";
        }
        return count >= 500 && percent < 20 ? "Overwhelmingly Negative" : "Negative";
    }
}
