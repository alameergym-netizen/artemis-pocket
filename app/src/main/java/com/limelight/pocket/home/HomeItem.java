package com.limelight.pocket.home;

import java.util.ArrayList;
import java.util.List;

/**
 * Something on the Home screen: a streamed game (an Apollo app, or a game of the PC's library),
 * a cloud service, a retro game, an Android app or a shortcut another app made (GameNative,
 * GameHub, Winlator ...). Stored as one line of text.
 */
public final class HomeItem {

    public static final String PC = "pc";
    public static final String PC_LIBRARY_GAME = "pclib";
    public static final String PC_GAMES = "pcgames";
    public static final String CLOUD = "cloud";
    public static final String RETRO = "retro";
    public static final String ANDROID = "android";
    public static final String SHORTCUT = "shortcut";
    /** A PC game run on the handheld by GameNative / GameHub Lite (a: which, b: the store, n: its id). */
    public static final String NATIVE = "native";

    public final String kind;
    public final String name;
    /** pc / pclib / pcgames: the PC's uuid; cloud: the service; retro: the system; android, shortcut: the package. */
    public final String a;
    /** pclib: the launch command; pcgames: the host; retro: the game's path; shortcut: its id. */
    public final String b;
    /** pclib, pcgames: the PC's name; shortcut: its intent (a URI), when it was pinned. */
    public final String c;
    /** pc: the Apollo app id; pclib: the game's page id. */
    public final int n;

    public HomeItem(String kind, String name, String a, String b, String c, int n) {
        this.kind = kind;
        this.name = name != null ? name : "";
        this.a = a != null ? a : "";
        this.b = b != null ? b : "";
        this.c = c != null ? c : "";
        this.n = n;
    }

    /** What makes it this item (the same game added twice is one item). */
    public String key() {
        switch (kind) {
            case PC:
            case NATIVE:
                return kind + ":" + a + ":" + n;
            case PC_LIBRARY_GAME:
                return kind + ":" + a + ":" + n + ":" + b;
            case RETRO:
            case SHORTCUT:
                return kind + ":" + a + ":" + b;
            default:
                return kind + ":" + a;
        }
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    public String toLine() {
        return clean(kind) + "\t" + clean(name) + "\t" + clean(a) + "\t" + clean(b) + "\t" + clean(c) + "\t" + n;
    }

    /** The item of a line, or null when the line isn't one. */
    public static HomeItem parse(String line) {
        if (line == null) {
            return null;
        }
        String[] p = line.split("\t", -1);
        if (p.length < 6 || p[0].isEmpty()) {
            return null;
        }
        int n;
        try {
            n = Integer.parseInt(p[5].trim());
        } catch (NumberFormatException e) {
            n = 0;
        }
        return new HomeItem(p[0], p[1], p[2], p[3], p[4], n);
    }

    public static List<HomeItem> parseAll(String text) {
        List<HomeItem> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\n")) {
            HomeItem i = parse(line);
            if (i != null) {
                out.add(i);
            }
        }
        return out;
    }

    public static String joinAll(List<HomeItem> items) {
        StringBuilder b = new StringBuilder();
        for (HomeItem i : items) {
            if (b.length() > 0) {
                b.append('\n');
            }
            b.append(i.toLine());
        }
        return b.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HomeItem && ((HomeItem) o).key().equals(key());
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }
}
