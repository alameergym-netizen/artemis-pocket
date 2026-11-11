package com.limelight.pocket.pclib;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A game of the PC library: found on the PC by Pocket Nexus PC (Steam, Epic, GOG), or owned on
 * the linked Steam account and not installed yet. Pure Java (tested).
 *
 * Wire format (one line per game, from the PC helper's GAMES command):
 *   G \t source \t id \t name \t state \t progress \t sizeMb \t lastPlayed \t steamArtId
 */
public final class PcGame {

    public static final String STEAM = "steam";
    public static final String EPIC = "epic";
    public static final String GOG = "gog";

    public static final String INSTALLED = "installed";
    public static final String INSTALLING = "installing";
    public static final String UPDATING = "updating";
    public static final String NOT_INSTALLED = "notinstalled";

    /** What the library shows (L1 / R1 go through these). */
    public static final int FILTER_ALL = 0;
    public static final int FILTER_INSTALLED = 1;
    public static final int FILTER_NOT_INSTALLED = 2;
    public static final int FILTER_STEAM = 3;
    public static final int FILTER_OTHER_STORES = 4;
    public static final int FILTER_COUNT = 5;

    public final String source;
    public final String id;
    public final String name;
    public String state;
    /** 0-100 while installing / updating, -1 otherwise. */
    public int progress = -1;
    public long sizeMb;
    /** Unix seconds (0: never / unknown). */
    public long lastPlayed;
    /** A Steam app id to borrow art and info from ("" when none is known). */
    public String artSteamId = "";
    /** Minutes played (from the Steam account, 0 when unknown). */
    public int playtimeMin;
    /** On the linked Steam account. */
    public boolean owned;

    public PcGame(String source, String id, String name, String state) {
        this.source = source;
        this.id = id;
        this.name = name;
        this.state = state;
    }

    /** One line of the PC helper's GAMES reply, or null for anything else. */
    public static PcGame parse(String line) {
        if (line == null || !line.startsWith("G\t")) {
            return null;
        }
        String[] p = line.split("\t", -1);
        if (p.length < 5) {
            return null;
        }
        String source = p[1].trim().toLowerCase(Locale.ROOT);
        String id = p[2].trim();
        String name = p[3].trim();
        String state = p[4].trim().toLowerCase(Locale.ROOT);
        if (source.isEmpty() || id.isEmpty() || name.isEmpty() || id.indexOf(' ') >= 0) {
            return null;
        }
        if (!INSTALLED.equals(state) && !INSTALLING.equals(state) && !UPDATING.equals(state)
                && !NOT_INSTALLED.equals(state)) {
            state = INSTALLED;
        }
        PcGame g = new PcGame(source, id, name, state);
        g.progress = p.length > 5 ? clampProgress(parseInt(p[5], -1)) : -1;
        g.sizeMb = p.length > 6 ? Math.max(0, parseLong(p[6], 0)) : 0;
        g.lastPlayed = p.length > 7 ? Math.max(0, parseLong(p[7], 0)) : 0;
        g.artSteamId = STEAM.equals(source) ? id : (p.length > 8 && isDigits(p[8].trim()) ? p[8].trim() : "");
        return g;
    }

    /** All the games of a GAMES reply (lines up to "END"). */
    public static List<PcGame> parseAll(List<String> lines) {
        List<PcGame> out = new ArrayList<>();
        for (String line : lines) {
            PcGame g = parse(line);
            if (g != null) {
                out.add(g);
            }
        }
        return out;
    }

    private static int clampProgress(int v) {
        return v < 0 ? -1 : Math.min(100, v);
    }

    static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static long parseLong(String s, long fallback) {
        try {
            return Long.parseLong(s.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static boolean isDigits(String s) {
        if (s == null || s.isEmpty() || s.length() > 12) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    public String key() {
        return source + ":" + id;
    }

    public boolean isInstalled() {
        return INSTALLED.equals(state);
    }

    public boolean isBusy() {
        return INSTALLING.equals(state) || UPDATING.equals(state);
    }

    public boolean isSteam() {
        return STEAM.equals(source);
    }

    /** What the PC helper is asked to start it with. */
    public String launchCommand() {
        return "LAUNCH " + source + " " + id;
    }

    /** What the PC helper is asked to install it with (Steam only), else null. */
    public String installCommand() {
        return isSteam() && isDigits(id) ? "STEAM INSTALL " + id : null;
    }

    public boolean matches(int filter) {
        switch (filter) {
            case FILTER_INSTALLED:
                return isInstalled() || isBusy();
            case FILTER_NOT_INSTALLED:
                return NOT_INSTALLED.equals(state);
            case FILTER_STEAM:
                return isSteam();
            case FILTER_OTHER_STORES:
                return !isSteam();
            default:
                return true;
        }
    }

    /** "Steam", "Epic Games", "GOG" (store names are the same in every language). */
    public String storeLabel() {
        if (EPIC.equals(source)) {
            return "Epic Games";
        }
        if (GOG.equals(source)) {
            return "GOG";
        }
        return "Steam";
    }

    /** "45.2 GB" / "830 MB", or "" when unknown. */
    public static String sizeLabel(long mb) {
        if (mb <= 0) {
            return "";
        }
        if (mb < 1024) {
            return mb + " MB";
        }
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024f);
    }

    /** "12 h" / "45 min" played, or "" when unknown. */
    public static String playtimeLabel(int minutes) {
        if (minutes <= 0) {
            return "";
        }
        if (minutes < 60) {
            return minutes + " min";
        }
        return (minutes / 60) + " h";
    }

    /**
     * A name reduced to compare names from different places (an Apollo app called "Elden Ring"
     * and Steam's "ELDEN RING™"): lower case, no accents, symbols or spaces.
     */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String n = name.replace("™", "").replace("®", "").replace("©", "");
        n = Normalizer.normalize(n, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || (Character.isLetter(c) && c > 0x24F)) {
                b.append(c);
            }
        }
        return b.toString();
    }

    /**
     * The PC's games plus the Steam account's games that aren't on the PC (not installed).
     * Games on both get the account's playtime.
     */
    public static List<PcGame> merge(List<PcGame> onPc, List<PcGame> owned) {
        List<PcGame> out = new ArrayList<>(onPc);
        Map<String, PcGame> steamOnPc = new HashMap<>();
        for (PcGame g : onPc) {
            if (g.isSteam()) {
                steamOnPc.put(g.id, g);
            }
        }
        if (owned != null) {
            for (PcGame o : owned) {
                PcGame local = steamOnPc.get(o.id);
                if (local != null) {
                    local.owned = true;
                    local.playtimeMin = Math.max(local.playtimeMin, o.playtimeMin);
                    local.lastPlayed = Math.max(local.lastPlayed, o.lastPlayed);
                }
                else {
                    PcGame copy = new PcGame(STEAM, o.id, o.name, NOT_INSTALLED);
                    copy.owned = true;
                    copy.playtimeMin = o.playtimeMin;
                    copy.lastPlayed = o.lastPlayed;
                    copy.artSteamId = o.id;
                    out.add(copy);
                    steamOnPc.put(o.id, copy);
                }
            }
        }
        return out;
    }

    /** On the PC first (installing ones too), then by when they were played, then by name. */
    public static void sort(List<PcGame> games) {
        Collections.sort(games, (a, b) -> {
            int ra = a.isInstalled() || a.isBusy() ? 0 : 1;
            int rb = b.isInstalled() || b.isBusy() ? 0 : 1;
            if (ra != rb) {
                return Integer.compare(ra, rb);
            }
            if (a.lastPlayed != b.lastPlayed) {
                return Long.compare(b.lastPlayed, a.lastPlayed);
            }
            return a.name.compareToIgnoreCase(b.name);
        });
    }

    /** The games that pass a filter (in order). */
    public static List<PcGame> filter(List<PcGame> games, int filter) {
        List<PcGame> out = new ArrayList<>();
        for (PcGame g : games) {
            if (g.matches(filter)) {
                out.add(g);
            }
        }
        return out;
    }
}
