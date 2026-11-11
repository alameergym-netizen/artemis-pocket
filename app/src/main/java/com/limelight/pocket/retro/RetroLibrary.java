package com.limelight.pocket.retro;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;

import com.limelight.pocket.PocketArt;
import com.limelight.pocket.PocketPrefs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The retro games: where the ROMs are (ES-DE's ROM folder by default), what's in each system
 * folder, what was played last, the favourites and the emulator picked for a system or a game.
 * ES-DE's own settings, gamelists and scraped pictures are used when ES-DE is on the handheld.
 */
public final class RetroLibrary {
    private RetroLibrary() {}

    /** Where the games' art and settings are kept (like a PC's uuid). */
    public static final String UUID = "retro";

    private static final String KEY_ROOT = "pocket_retro_root";
    private static final String KEY_RECENT = "pocket_retro_recent";
    private static final String KEY_FAVS = "pocket_retro_favs";
    private static final String KEY_SYS_EMU = "pocket_retro_emu_sys_";
    private static final String KEY_GAME_EMU = "pocket_retro_emu_game_";
    private static final String KEY_SHOW_EMPTY = "pocket_retro_show_empty";
    private static final String CACHE = "retro_scan.tsv";
    private static final int MAX_RECENT = 40;

    private static EsdeConfig config;

    // ---- ES-DE's files --------------------------------------------------------------------------

    /** ES-DE's systems and emulators (bundled, plus its custom_systems files). Blocking. */
    public static synchronized EsdeConfig config(Context context) {
        if (config != null) {
            return config;
        }
        EsdeConfig c;
        try (InputStream systems = context.getAssets().open("esde/es_systems.xml");
             InputStream rules = context.getAssets().open("esde/es_find_rules.xml")) {
            c = EsdeConfig.parse(systems, rules);
        } catch (IOException e) {
            try {
                c = EsdeConfig.parse(null, null);
            } catch (IOException never) {
                throw new IllegalStateException(never);
            }
        }
        File custom = new File(esdeDir(), "custom_systems");
        File cs = new File(custom, "es_systems.xml");
        File cr = new File(custom, "es_find_rules.xml");
        try (InputStream s = cs.canRead() ? new FileInputStream(cs) : null;
             InputStream r = cr.canRead() ? new FileInputStream(cr) : null) {
            if (s != null || r != null) {
                c.merge(s, r);
            }
        } catch (IOException | RuntimeException ignored) {
            // A broken custom file is left out
        }
        config = c;
        return c;
    }

    /** Reads the custom_systems files again next time. */
    public static synchronized void reloadConfig() {
        config = null;
    }

    /** ES-DE's folder (settings, gamelists, scraped media). */
    public static File esdeDir() {
        return new File(Environment.getExternalStorageDirectory(), "ES-DE");
    }

    private static String esdeSetting(String name) {
        File f = new File(new File(esdeDir(), "settings"), "es_settings.xml");
        if (!f.canRead() || f.length() > 512 * 1024) {
            return null;
        }
        Pattern p = Pattern.compile("<string\\s+name=\"" + Pattern.quote(name) + "\"\\s+value=\"([^\"]*)\"");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                Matcher m = p.matcher(line);
                if (m.find()) {
                    String v = m.group(1).replace("&amp;", "&").trim();
                    return v.isEmpty() ? null : v;
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /** ES-DE's scraped pictures folder. */
    public static File mediaDir() {
        String custom = esdeSetting("MediaDirectory");
        return custom != null ? new File(custom) : new File(esdeDir(), "downloaded_media");
    }

    // ---- Storage access ----------------------------------------------------------------------

    /** True when the app may read the ROM folders (all files access on Android 11 and later). */
    public static boolean hasAccess(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    public static final int REQUEST_ACCESS = 7311;

    /** Asks for it (the system's own screen on Android 11 and later). */
    public static void requestAccess(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (RuntimeException e) {
                try {
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (RuntimeException ignored) {
                }
            }
        }
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activity.requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_ACCESS);
        }
    }

    // ---- The ROM folder --------------------------------------------------------------------

    /** The ROM folder: the one picked, ES-DE's, or a ROMs folder found on a drive (null: none). */
    public static File romRoot(Context context) {
        String picked = PocketPrefs.prefs(context).getString(KEY_ROOT, null);
        if (picked != null) {
            return new File(picked);
        }
        return detectRoot();
    }

    public static boolean rootWasPicked(Context context) {
        return PocketPrefs.prefs(context).getString(KEY_ROOT, null) != null;
    }

    public static File detectRoot() {
        String esde = esdeSetting("ROMDirectory");
        if (esde != null) {
            File f = new File(esde.replace("%ESPATH%", esdeDir().getPath()));
            if (f.isDirectory()) {
                return f;
            }
        }
        File internal = new File(Environment.getExternalStorageDirectory(), "ROMs");
        if (internal.isDirectory()) {
            return internal;
        }
        File[] drives = new File("/storage").listFiles();
        if (drives != null) {
            for (File d : drives) {
                for (String n : new String[] {"ROMs", "roms", "Roms"}) {
                    File f = new File(d, n);
                    if (f.isDirectory()) {
                        return f;
                    }
                }
            }
        }
        return null;
    }

    /** Where ES-DE would put it (to create it). */
    public static File defaultRoot() {
        return new File(Environment.getExternalStorageDirectory(), "ROMs");
    }

    public static void setRoot(Context context, File root) {
        PocketPrefs.prefs(context).edit().putString(KEY_ROOT, root.getAbsolutePath()).apply();
    }

    /** A folder picked with the system's folder picker, as a path ("primary:ROMs" -> /storage/emulated/0/ROMs). */
    public static File pathOfTree(Uri tree) {
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(tree);
        } catch (RuntimeException e) {
            return null;
        }
        int colon = id.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String volume = id.substring(0, colon);
        String rel = id.substring(colon + 1);
        File base = "primary".equalsIgnoreCase(volume) ? Environment.getExternalStorageDirectory()
                : new File("/storage/" + volume);
        return rel.isEmpty() ? base : new File(base, rel);
    }

    /**
     * Makes a folder for every system (ES-DE's names) with a systeminfo.txt saying what goes in
     * it, like ES-DE's "create system directories". Returns how many were made.
     */
    public static int createSystemFolders(Context context, File root) {
        int made = 0;
        if (!root.isDirectory() && !root.mkdirs()) {
            return 0;
        }
        for (EsdeConfig.SystemDef s : config(context).systems()) {
            if (s.absolute) {
                continue;
            }
            File dir = new File(root, s.folder);
            if (!dir.isDirectory()) {
                if (!dir.mkdirs()) {
                    continue;
                }
                made++;
            }
            File info = new File(dir, "systeminfo.txt");
            if (!info.exists()) {
                StringBuilder b = new StringBuilder();
                b.append("System name:\n").append(s.name).append("\n\nFull system name:\n").append(s.fullName)
                        .append("\n\nSupported file extensions:\n").append(android.text.TextUtils.join(" ", s.extensions))
                        .append("\n\nEmulators:\n");
                for (EsdeConfig.Command c : s.commands) {
                    b.append(c.label).append('\n');
                }
                try (Writer w = new OutputStreamWriter(new FileOutputStream(info), StandardCharsets.UTF_8)) {
                    w.write(b.toString());
                } catch (IOException ignored) {
                }
            }
        }
        return made;
    }

    // ---- Scanning ----------------------------------------------------------------------------

    /** What's in the ROM folder. */
    public static final class Library {
        public final File root;
        /** System name -> its games, systems in ES-DE's order. */
        public final Map<String, List<RomScanner.Rom>> games = new LinkedHashMap<>();
        /** ES-DE's emulator picks: by system, and by game key (system/path). */
        final Map<String, String> esdeSystemEmu = new HashMap<>();
        final Map<String, String> esdeGameEmu = new HashMap<>();
        /** ES-DE's favourites (game keys). */
        final Set<String> esdeFavs = new HashSet<>();

        Library(File root) {
            this.root = root;
        }

        public int gameCount() {
            int n = 0;
            for (List<RomScanner.Rom> l : games.values()) {
                n += l.size();
            }
            return n;
        }

        public RomScanner.Rom find(String key) {
            int slash = key.indexOf('/');
            if (slash <= 0) {
                return null;
            }
            List<RomScanner.Rom> l = games.get(key.substring(0, slash));
            if (l == null) {
                return null;
            }
            String rel = key.substring(slash + 1);
            for (RomScanner.Rom r : l) {
                if (r.relPath.equals(rel)) {
                    return r;
                }
            }
            return null;
        }
    }

    /** The system folders under the root, by lower case name (drives may differ in case). */
    private static Map<String, File> folders(File root) {
        Map<String, File> out = new HashMap<>();
        File[] list = root.listFiles();
        if (list != null) {
            for (File f : list) {
                if (f.isDirectory()) {
                    out.put(f.getName().toLowerCase(Locale.ROOT), f);
                }
            }
        }
        return out;
    }

    private static File folderOf(EsdeConfig.SystemDef s, File root, Map<String, File> folders) {
        if (s.absolute) {
            return new File(s.folder);
        }
        File exact = new File(root, s.folder);
        if (exact.isDirectory()) {
            return exact;
        }
        return folders.get(s.folder.toLowerCase(Locale.ROOT));
    }

    /** Looks through every system folder (blocking). */
    public static Library scan(Context context, File root) {
        EsdeConfig cfg = config(context);
        Library lib = new Library(root);
        Map<String, File> folders = folders(root);
        for (EsdeConfig.SystemDef s : cfg.systems()) {
            File dir = folderOf(s, root, folders);
            if (dir == null) {
                continue;
            }
            List<RomScanner.Rom> roms = RomScanner.scan(s, dir);
            if (!roms.isEmpty()) {
                lib.games.put(s.name, roms);
            }
        }
        applyGamelists(lib);
        saveCache(context, lib);
        return lib;
    }

    /** What the last look found (instant, may be out of date), or null. */
    public static Library cached(Context context, File root) {
        File f = new File(context.getFilesDir(), CACHE);
        if (!f.isFile()) {
            return null;
        }
        EsdeConfig cfg = config(context);
        Library lib = null;
        Map<String, File> folders = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length >= 2 && "ROOT".equals(p[0])) {
                    if (!p[1].equals(root.getAbsolutePath())) {
                        return null;
                    }
                    lib = new Library(root);
                    folders = folders(root);
                }
                else if (lib != null && p.length >= 4 && "G".equals(p[0])) {
                    EsdeConfig.SystemDef s = cfg.system(p[1]);
                    if (s == null) {
                        continue;
                    }
                    File dir = folderOf(s, root, folders);
                    if (dir == null) {
                        continue;
                    }
                    List<RomScanner.Rom> list = lib.games.get(s.name);
                    if (list == null) {
                        list = new ArrayList<>();
                        lib.games.put(s.name, list);
                    }
                    list.add(new RomScanner.Rom(s.name, p[2], new File(dir, p[2]), "d".equals(p[3])));
                }
            }
        } catch (IOException e) {
            return null;
        }
        if (lib != null) {
            applyGamelists(lib);
        }
        return lib;
    }

    private static void saveCache(Context context, Library lib) {
        File f = new File(context.getFilesDir(), CACHE);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write("ROOT\t" + lib.root.getAbsolutePath() + "\n");
            for (Map.Entry<String, List<RomScanner.Rom>> e : lib.games.entrySet()) {
                for (RomScanner.Rom r : e.getValue()) {
                    if (r.relPath.indexOf('\t') >= 0 || r.relPath.indexOf('\n') >= 0) {
                        continue;
                    }
                    w.write("G\t" + e.getKey() + "\t" + r.relPath + "\t" + (r.directory ? "d" : "f") + "\n");
                }
            }
        } catch (IOException ignored) {
        }
    }

    /** ES-DE's names, hidden games, favourites and emulator picks. */
    private static void applyGamelists(Library lib) {
        File base = new File(esdeDir(), "gamelists");
        if (!base.isDirectory()) {
            return;
        }
        for (Map.Entry<String, List<RomScanner.Rom>> e : lib.games.entrySet()) {
            File f = new File(new File(base, e.getKey()), "gamelist.xml");
            if (!f.canRead() || f.length() > 32L * 1024 * 1024) {
                continue;
            }
            EsdeGamelist gl;
            try (InputStream in = new FileInputStream(f)) {
                gl = EsdeGamelist.parse(in);
            } catch (IOException | RuntimeException ex) {
                continue;
            }
            if (gl.altEmulator != null) {
                lib.esdeSystemEmu.put(e.getKey(), gl.altEmulator);
            }
            List<RomScanner.Rom> keep = new ArrayList<>();
            for (RomScanner.Rom r : e.getValue()) {
                EsdeGamelist.Game g = gl.games.get(r.relPath);
                if (g != null) {
                    if (g.hidden) {
                        continue;
                    }
                    if (g.name != null && !g.name.isEmpty()) {
                        r.name = g.name;
                    }
                    if (g.favorite) {
                        lib.esdeFavs.add(r.key());
                    }
                    if (g.altEmulator != null) {
                        lib.esdeGameEmu.put(r.key(), g.altEmulator);
                    }
                }
                keep.add(r);
            }
            RomScanner.sortByName(keep);
            e.setValue(keep);
        }
    }

    // ---- Played, favourites ---------------------------------------------------------------

    public static void markPlayed(Context context, RomScanner.Rom rom) {
        List<String> lines = new ArrayList<>();
        lines.add(System.currentTimeMillis() + "\t" + rom.key());
        for (String l : recentLines(context)) {
            if (!l.endsWith("\t" + rom.key()) && lines.size() < MAX_RECENT) {
                lines.add(l);
            }
        }
        PocketPrefs.prefs(context).edit().putString(KEY_RECENT, android.text.TextUtils.join("\n", lines)).apply();
    }

    private static List<String> recentLines(Context context) {
        String s = PocketPrefs.prefs(context).getString(KEY_RECENT, "");
        List<String> out = new ArrayList<>();
        for (String l : s.split("\n")) {
            if (l.indexOf('\t') > 0) {
                out.add(l);
            }
        }
        return out;
    }

    /** When a game was played last from here (0: never). */
    public static long lastPlayed(Context context, String key) {
        for (String l : recentLines(context)) {
            int tab = l.indexOf('\t');
            if (l.substring(tab + 1).equals(key)) {
                try {
                    return Long.parseLong(l.substring(0, tab));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    /** The games played last, newest first (those still in the library). */
    public static List<RomScanner.Rom> recent(Context context, Library lib) {
        List<RomScanner.Rom> out = new ArrayList<>();
        for (String l : recentLines(context)) {
            RomScanner.Rom r = lib.find(l.substring(l.indexOf('\t') + 1));
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    public static boolean isFavorite(Context context, Library lib, RomScanner.Rom rom) {
        Set<String> favs = PocketPrefs.prefs(context).getStringSet(KEY_FAVS, Collections.<String>emptySet());
        if (favs.contains(rom.key())) {
            return true;
        }
        // ES-DE's favourite, unless unmarked here
        return lib.esdeFavs.contains(rom.key()) && !favs.contains("-" + rom.key());
    }

    public static void setFavorite(Context context, RomScanner.Rom rom, boolean on) {
        Set<String> favs = new HashSet<>(PocketPrefs.prefs(context).getStringSet(KEY_FAVS, Collections.<String>emptySet()));
        favs.remove(rom.key());
        favs.remove("-" + rom.key());
        favs.add(on ? rom.key() : "-" + rom.key());
        PocketPrefs.prefs(context).edit().putStringSet(KEY_FAVS, favs).apply();
    }

    public static List<RomScanner.Rom> favorites(Context context, Library lib) {
        List<RomScanner.Rom> out = new ArrayList<>();
        for (List<RomScanner.Rom> l : lib.games.values()) {
            for (RomScanner.Rom r : l) {
                if (isFavorite(context, lib, r)) {
                    out.add(r);
                }
            }
        }
        RomScanner.sortByName(out);
        return out;
    }

    public static boolean showEmptySystems(Context context) {
        return PocketPrefs.prefs(context).getBoolean(KEY_SHOW_EMPTY, false);
    }

    public static void setShowEmptySystems(Context context, boolean on) {
        PocketPrefs.prefs(context).edit().putBoolean(KEY_SHOW_EMPTY, on).apply();
    }

    // ---- Emulators -------------------------------------------------------------------------

    /** The command picked here for the system (null: not picked). */
    public static String systemChoice(Context context, String system) {
        return PocketPrefs.prefs(context).getString(KEY_SYS_EMU + system, null);
    }

    public static void setSystemChoice(Context context, String system, String label) {
        if (label == null) {
            PocketPrefs.prefs(context).edit().remove(KEY_SYS_EMU + system).apply();
        }
        else {
            PocketPrefs.prefs(context).edit().putString(KEY_SYS_EMU + system, label).apply();
        }
    }

    public static String gameChoice(Context context, RomScanner.Rom rom) {
        return PocketPrefs.prefs(context).getString(KEY_GAME_EMU + rom.id(), null);
    }

    public static void setGameChoice(Context context, RomScanner.Rom rom, String label) {
        if (label == null) {
            PocketPrefs.prefs(context).edit().remove(KEY_GAME_EMU + rom.id()).apply();
        }
        else {
            PocketPrefs.prefs(context).edit().putString(KEY_GAME_EMU + rom.id(), label).apply();
        }
    }

    /** The system's emulator: picked here, picked in ES-DE, else the first one installed. */
    public static EsdeConfig.Command systemCommand(Context context, Library lib, EsdeConfig.SystemDef s) {
        EsdeConfig.Command c = s.command(systemChoice(context, s.name));
        if (c == null && lib != null) {
            c = s.command(lib.esdeSystemEmu.get(s.name));
        }
        if (c != null) {
            return c;
        }
        EsdeConfig cfg = config(context);
        for (EsdeConfig.Command cmd : s.commands) {
            if (!cmd.isPlaceholder() && RetroLauncher.installed(context, cfg, cmd)) {
                return cmd;
            }
        }
        return s.commands.isEmpty() ? null : s.commands.get(0);
    }

    /** The game's emulator: its own pick (here, then ES-DE's), else the system's. */
    public static EsdeConfig.Command gameCommand(Context context, Library lib, EsdeConfig.SystemDef s, RomScanner.Rom rom) {
        EsdeConfig.Command c = s.command(gameChoice(context, rom));
        if (c == null && lib != null) {
            c = s.command(lib.esdeGameEmu.get(rom.key()));
        }
        return c != null ? c : systemCommand(context, lib, s);
    }

    // ---- ES-DE's scraped pictures ----------------------------------------------------------

    private static final String[] IMAGE_EXT = {".png", ".jpg", ".jpeg", ".webp"};

    private static File media(File systemMedia, String folder, String relNoExt) {
        for (String ext : IMAGE_EXT) {
            File f = new File(new File(systemMedia, folder), relNoExt + ext);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    /**
     * Takes the cover, background and logo ES-DE downloaded for the game, for what it's still
     * missing here (blocking). True when something was added.
     */
    public static boolean importEsdeMedia(Context context, RomScanner.Rom rom) {
        File sys = new File(mediaDir(), rom.system);
        if (!sys.isDirectory()) {
            return false;
        }
        String rel = rom.relPath;
        int slash = rel.lastIndexOf('/');
        int dot = rel.lastIndexOf('.');
        String relNoExt = dot > slash + 1 ? rel.substring(0, dot) : rel;
        boolean changed = false;
        String[][] kinds = {
                {"covers"},
                {"fanart", "screenshots", "titlescreens"},
                {"marquees"},
        };
        int[] art = {PocketArt.COVER, PocketArt.HERO, PocketArt.LOGO};
        for (int k = 0; k < art.length; k++) {
            if (PocketArt.has(context, UUID, rom.id(), art[k])) {
                continue;
            }
            for (String folder : kinds[k]) {
                File f = media(sys, folder, relNoExt);
                if (f == null || f.length() > 24L * 1024 * 1024) {
                    continue;
                }
                try {
                    PocketArt.save(context, UUID, rom.id(), art[k], readAll(f));
                    changed = true;
                    break;
                } catch (IOException | RuntimeException ignored) {
                }
            }
        }
        return changed;
    }

    private static byte[] readAll(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
        }
        return buf;
    }
}
