package com.limelight.pocket.retro;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads an ES-DE Android launch command (the Intent API variables: %EMULATOR_x%, %ACTION%,
 * %DATA%, %EXTRA_x%, %EXTRABOOL_x%, %EXTRAINTEGER_x%, %EXTRAARRAY_x%, %MIMETYPE%, %CATEGORY%,
 * %ACTIVITY_CLEAR_TASK% ... and the ROM variables %ROM%, %ROMSAF%, %ROMPROVIDER%, %BASENAME%,
 * %INJECT%= ...) and works out what to start, the way ES-DE does.
 */
public final class EsdeCommand {
    private EsdeCommand() {}

    public static final int EXTRA_STRING = 0;
    public static final int EXTRA_BOOL = 1;
    public static final int EXTRA_INT = 2;
    public static final int EXTRA_ARRAY = 3;

    /** Injected files bigger than this are skipped (like ES-DE). */
    static final int MAX_INJECT = 4096;

    public static final class Extra {
        public final int type;
        public final String key;
        public final String value;
        public final String[] array;

        Extra(int type, String key, String value, String[] array) {
            this.type = type;
            this.key = key;
            this.value = value;
            this.array = array;
        }

        public boolean bool() {
            return "true".equalsIgnoreCase(value) || "1".equals(value);
        }

        /** The integer, or null when it isn't one. */
        public Integer integer() {
            try {
                return Integer.parseInt(value.trim());
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** The parsed command, its values already worked out. */
    public static final class Spec {
        public String emulator;
        /** %ANDROIDAPP%: "package" or "package/activity". */
        public String androidApp;
        public String action;
        public String category;
        public String data;
        public String mimeType;
        public final List<Extra> extras = new ArrayList<>();
        public boolean clearTask;
        public boolean clearTop;
        public boolean noHistory;
        /** No emulator exists on Android for it. */
        public boolean placeholder;
        /** The data (or an extra) is a FileProvider URI to grant. */
        public boolean usesProvider;
        public boolean usesSaf;

        public Extra extra(String key) {
            for (Extra e : extras) {
                if (e.key.equals(key)) {
                    return e;
                }
            }
            return null;
        }
    }

    /** What the variables stand for, for one game. */
    public interface Env {
        /** The game's absolute path (a file, or a directory read as a file). */
        String rom();

        /** The ROM directory (where the system folders are). */
        String romRoot();

        /** /storage/emulated/0 (the user's). */
        String externalData();

        /** /data/user/0 (the user's). */
        String internalData();

        /** The emulator's package (for %ANDROIDPACKAGE%), or "". */
        String androidPackage();

        /** The game as a Storage Access Framework document URI. */
        String saf();

        /** The game as this app's FileProvider URI (access granted on launch). */
        String provider();

        /** The text of a small file (null when missing, empty or too big). */
        String read(File file);
    }

    /** Env with plain files (the Android one adds the URIs). */
    public abstract static class FileEnv implements Env {
        @Override
        public String read(File file) {
            return readSmall(file);
        }
    }

    public static String readSmall(File file) {
        if (file == null || !file.isFile() || file.length() <= 0 || file.length() > MAX_INJECT) {
            return null;
        }
        byte[] buf = new byte[(int) file.length()];
        try (InputStream in = new FileInputStream(file)) {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            String s = new String(buf, 0, off, StandardCharsets.UTF_8);
            if (s.startsWith("﻿")) {
                s = s.substring(1);
            }
            s = s.trim();
            return s.isEmpty() ? null : s;
        } catch (IOException e) {
            return null;
        }
    }

    // ---- Parsing --------------------------------------------------------------------------------

    /** Splits on spaces outside double quotes (quotes kept). */
    static List<String> tokens(String command) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
                cur.append(ch);
            }
            else if (Character.isWhitespace(ch) && !quoted) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            }
            else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    static String unquote(String v) {
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** The command's emulator rule name only (no game needed). */
    public static String emulatorOf(String command) {
        return EsdeConfig.Command.emulatorOf(command);
    }

    public static Spec resolve(String command, Env env) {
        Spec spec = new Spec();
        for (String token : tokens(command)) {
            if (token.equals("PLACEHOLDER")) {
                spec.placeholder = true;
                continue;
            }
            if (token.startsWith("%EMULATOR_") && token.endsWith("%") && token.indexOf('=') < 0) {
                spec.emulator = token.substring(10, token.length() - 1);
                continue;
            }
            switch (token) {
                case "%ACTIVITY_CLEAR_TASK%":
                    spec.clearTask = true;
                    continue;
                case "%ACTIVITY_CLEAR_TOP%":
                    spec.clearTop = true;
                    continue;
                case "%ACTIVITY_NO_HISTORY%":
                    spec.noHistory = true;
                    continue;
                default:
                    break;
            }
            if (token.startsWith("%ACTION%=")) {
                spec.action = expand(value(token, 9), env, false, spec);
            }
            else if (token.startsWith("%CATEGORY%=")) {
                spec.category = expand(value(token, 11), env, false, spec);
            }
            else if (token.startsWith("%MIMETYPE%=")) {
                spec.mimeType = expand(value(token, 11), env, false, spec);
            }
            else if (token.startsWith("%DATA%=")) {
                spec.data = expand(value(token, 7), env, false, spec);
            }
            else if (token.startsWith("%ANDROIDAPP%=")) {
                spec.androidApp = expand(value(token, 13), env, false, spec);
            }
            else if (token.startsWith("%EXTRABOOL_")) {
                addExtra(spec, EXTRA_BOOL, token, 11, env);
            }
            else if (token.startsWith("%EXTRAINTEGER_")) {
                addExtra(spec, EXTRA_INT, token, 14, env);
            }
            else if (token.startsWith("%EXTRAARRAY_")) {
                addExtra(spec, EXTRA_ARRAY, token, 12, env);
            }
            else if (token.startsWith("%EXTRA_")) {
                addExtra(spec, EXTRA_STRING, token, 7, env);
            }
            // Anything else (%STARTDIR%, %HIDEWINDOW%, the %ROM% after PLACEHOLDER) does nothing here
        }
        return spec;
    }

    private static String value(String token, int from) {
        return unquote(token.substring(from));
    }

    private static void addExtra(Spec spec, int type, String token, int prefix, Env env) {
        int end = token.indexOf("%=", prefix);
        if (end <= prefix) {
            return;
        }
        String key = token.substring(prefix, end);
        String raw = unquote(token.substring(end + 2));
        if (type == EXTRA_ARRAY) {
            List<String> parts = splitArray(raw);
            String[] arr = new String[parts.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = expand(parts.get(i), env, true, spec);
            }
            spec.extras.add(new Extra(type, key, raw, arr));
            return;
        }
        spec.extras.add(new Extra(type, key, expand(raw, env, type == EXTRA_STRING, spec), null));
    }

    /** "a,b\,c" -> [a, "b,c"]. */
    static List<String> splitArray(String raw) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '\\' && i + 1 < raw.length() && raw.charAt(i + 1) == ',') {
                cur.append(',');
                i++;
            }
            else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            }
            else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    // ---- Variables ----------------------------------------------------------------------------

    static String baseName(String path) {
        String name = new File(path).getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String parentOf(String path) {
        String p = new File(path).getParent();
        return p != null ? p : "";
    }

    /** The paths and names (no URIs). */
    private static String expandPaths(String v, Env env) {
        String rom = env.rom();
        String s = v;
        if (s.contains("%ROMRAWWIN%")) {
            s = s.replace("%ROMRAWWIN%", rom.replace('/', '\\'));
        }
        s = s.replace("%ROMRAW%", rom)
                .replace("%ROMPATHRAW%", env.romRoot())
                .replace("%GAMEDIRRAW%", parentOf(rom))
                .replace("%BASENAME%", baseName(rom))
                .replace("%EXTERNALDATA%", env.externalData())
                .replace("%INTERNALDATA%", env.internalData())
                .replace("%ANDROIDPACKAGE%", env.androidPackage())
                .replace("%ROM%", rom);
        return s;
    }

    /** Where %INJECT%=path reads from: relative to the game's folder; a folder game reads its own file. */
    public static File injectFile(String path, Env env) {
        String p = unquote(path.trim());
        File f;
        if (p.startsWith("/")) {
            f = new File(p);
        }
        else if (p.startsWith("~")) {
            f = new File(env.externalData() + p.substring(1));
        }
        else {
            f = new File(parentOf(env.rom()), p);
        }
        if (f.isDirectory()) {
            f = new File(f, f.getName());
        }
        return f;
    }

    static String expand(String raw, Env env, boolean collapseSlashes, Spec spec) {
        String v = raw;
        if (v.startsWith("%INJECT%=")) {
            String text = env.read(injectFile(expandPaths(v.substring(9), env), env));
            return text != null ? text : "";
        }
        if (v.equals("%FILEINJECT%")) {
            File rom = new File(env.rom());
            String text = env.read(rom.isDirectory() ? new File(rom, rom.getName()) : rom);
            return text != null ? text : "";
        }
        v = expandPaths(v, env);
        if (collapseSlashes) {
            v = v.replace("\\/", "\u0001").replaceAll("/{2,}", "/").replace("\u0001", "/");
        }
        if (v.contains("%ROMSAF%")) {
            spec.usesSaf = true;
            v = v.replace("%ROMSAF%", env.saf());
        }
        if (v.contains("%ROMPROVIDER%")) {
            spec.usesProvider = true;
            v = v.replace("%ROMPROVIDER%", env.provider());
        }
        return v;
    }
}
