package com.limelight.pocket.retro;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the games in a system's ROM folder like ES-DE: every file with one of the system's
 * extensions, in sub folders too, and folders named like a game file ("Game.ps3") read as one
 * game. A folder with a noload.txt is skipped. On top of ES-DE, the files a .m3u playlist or a
 * .cue / .gdi sheet points at are hidden, so a multi-disc or multi-track game shows once.
 */
public final class RomScanner {
    private RomScanner() {}

    static final int MAX_DEPTH = 4;
    static final int MAX_GAMES = 30000;
    private static final long MAX_SHEET = 64 * 1024;

    /** Folders that hold scraped pictures and the like, not games. */
    private static final Set<String> MEDIA_FOLDERS = new HashSet<>(Arrays.asList(
            "media", "images", "videos", "manuals", "screenshots", "covers", "marquees", "snaps",
            "boxart", "box2dfront", "artwork", "wheel", "fanart", "titlescreens", "miximages",
            "downloaded_media", "bios", "saves", "states", "savestates"));

    public static final class Rom {
        public final String system;
        /** Under the system folder, with forward slashes. */
        public final String relPath;
        public final File file;
        public final boolean directory;
        /** Shown name (cleaned file name, or ES-DE's gamelist name). */
        public String name;

        public Rom(String system, String relPath, File file, boolean directory) {
            this.system = system;
            this.relPath = relPath;
            this.file = file;
            this.directory = directory;
            this.name = cleanName(file.getName(), directory);
        }

        /** A stable id for the game's art and settings. */
        public int id() {
            return idFor(system, relPath);
        }

        public String key() {
            return system + "/" + relPath;
        }
    }

    public static int idFor(String system, String relPath) {
        int h = (system + "/" + relPath).hashCode() & 0x3FFFFFFF;
        return h == 0 ? 1 : h;
    }

    /** The system's games, sorted by name. */
    public static List<Rom> scan(EsdeConfig.SystemDef system, File folder) {
        List<Rom> out = new ArrayList<>();
        if (folder != null && folder.isDirectory()) {
            walk(system, folder, "", 0, out);
        }
        sortByName(out);
        return out;
    }

    public static void sortByName(List<Rom> roms) {
        Collections.sort(roms, (a, b) -> {
            int c = sortKey(a.name).compareTo(sortKey(b.name));
            return c != 0 ? c : a.relPath.compareToIgnoreCase(b.relPath);
        });
    }

    static String sortKey(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith("the ")) {
            n = n.substring(4);
        }
        return n;
    }

    private static void walk(EsdeConfig.SystemDef system, File dir, String rel, int depth, List<Rom> out) {
        if (out.size() >= MAX_GAMES || new File(dir, "noload.txt").exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        List<File> games = new ArrayList<>();
        List<File> folders = new ArrayList<>();
        boolean onlyNoExtension = system.extensions.size() == 1 && system.extensions.contains(".");
        for (File f : children) {
            String name = f.getName();
            if (name.startsWith(".")) {
                continue;
            }
            if (f.isDirectory()) {
                String ext = EsdeConfig.extensionOf(name);
                if (!onlyNoExtension && !ext.equals(".") && system.extensions.contains(ext)) {
                    games.add(f); // a folder read as a game file
                }
                else if (depth < MAX_DEPTH && !MEDIA_FOLDERS.contains(name.toLowerCase(Locale.ROOT))) {
                    folders.add(f);
                }
            }
            else if (system.accepts(name) && !name.equalsIgnoreCase("noload.txt")
                    && !name.equalsIgnoreCase("systeminfo.txt")) {
                games.add(f);
            }
        }
        Set<String> hidden = referencedFiles(games);
        for (File g : games) {
            if (hidden.contains(g.getName().toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.add(new Rom(system.name, rel + g.getName(), g, g.isDirectory()));
        }
        for (File f : folders) {
            walk(system, f, rel + f.getName() + "/", depth + 1, out);
        }
    }

    private static final Pattern SHEET_FILE = Pattern.compile("^\\s*FILE\\s+\"?([^\"]+?)\"?\\s+\\S+\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern GDI_TRACK = Pattern.compile("^\\s*\\d+\\s+\\d+\\s+\\d+\\s+\\d+\\s+(\"[^\"]+\"|\\S+)");

    /** The names (lower case) that a folder's .m3u, .cue and .gdi files point at. */
    static Set<String> referencedFiles(List<File> games) {
        Set<String> names = new HashSet<>();
        for (File g : games) {
            String ext = EsdeConfig.extensionOf(g.getName());
            if (g.isDirectory() || !(ext.equals(".m3u") || ext.equals(".cue") || ext.equals(".gdi"))
                    || g.length() > MAX_SHEET) {
                continue;
            }
            for (String line : lines(g)) {
                String ref = null;
                if (ext.equals(".m3u")) {
                    if (!line.startsWith("#")) {
                        ref = line;
                    }
                }
                else if (ext.equals(".cue")) {
                    Matcher m = SHEET_FILE.matcher(line);
                    if (m.matches()) {
                        ref = m.group(1);
                    }
                }
                else {
                    // .gdi: "1 0 4 2352 track01.raw 0" (names may be quoted)
                    Matcher m = GDI_TRACK.matcher(line);
                    if (m.find()) {
                        ref = m.group(1).replace("\"", "");
                    }
                }
                if (ref != null) {
                    ref = ref.trim().replace('\\', '/');
                    if (ref.isEmpty() || ref.contains("/")) {
                        continue; // only files right next to it
                    }
                    names.add(ref.toLowerCase(Locale.ROOT));
                }
            }
        }
        // A playlist or sheet never hides itself
        for (File g : games) {
            String ext = EsdeConfig.extensionOf(g.getName());
            if (ext.equals(".m3u")) {
                names.remove(g.getName().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static List<String> lines(File f) {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null && out.size() < 2000) {
                line = line.replace("﻿", "").trim();
                if (!line.isEmpty()) {
                    out.add(line);
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    // ---- Names ----------------------------------------------------------------------------------

    private static final Pattern DISC = Pattern.compile(
            "[(\\[]\\s*(?:disc|disk|cd)\\s*([0-9]+|[a-d])(?:\\s*of\\s*[0-9]+)?\\s*[)\\]]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TAGS = Pattern.compile("\\s*[(\\[][^)\\]]*[)\\]]");

    /**
     * "Legend of Zelda, The - A Link to the Past (USA) [!].sfc" -> "The Legend of Zelda - A Link
     * to the Past"; the disc number is kept ("Final Fantasy VII · Disc 2").
     */
    public static String cleanName(String fileName, boolean directory) {
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0 && base.length() - dot <= 6 && base.substring(dot + 1).matches("[A-Za-z0-9]+")) {
            base = base.substring(0, dot);
        }
        String disc = null;
        Matcher dm = DISC.matcher(base);
        if (dm.find()) {
            disc = dm.group(1).toUpperCase(Locale.ROOT);
        }
        String n = TAGS.matcher(base).replaceAll("");
        n = n.replace('_', ' ').replaceAll("\\s{2,}", " ").trim();
        while (n.endsWith("-") || n.endsWith(",")) {
            n = n.substring(0, n.length() - 1).trim();
        }
        // "Name, The - Subtitle" -> "The Name - Subtitle"
        n = moveArticle(n);
        if (n.isEmpty()) {
            n = base.trim();
        }
        return disc != null ? n + " · Disc " + disc : n;
    }

    private static String moveArticle(String n) {
        String[] parts = n.split(" - ", 2);
        String head = parts[0];
        for (String article : new String[] {"The", "A", "An"}) {
            String tail = ", " + article;
            if (head.endsWith(tail)) {
                head = article + " " + head.substring(0, head.length() - tail.length());
                break;
            }
        }
        return parts.length > 1 ? head + " - " + parts[1] : head;
    }

    /** The name to look a game up with (no disc number). */
    public static String searchName(String name) {
        int i = name.indexOf(" · Disc ");
        return i > 0 ? name.substring(0, i) : name;
    }
}
