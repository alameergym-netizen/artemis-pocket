package com.limelight.pocket.retro;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.LruCache;

import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.SteamGridDb;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pictures and facts for each emulated system, from the Art Book Next theme for ES-DE (by
 * anthonycaccese and its contributors, CC BY-NC-SA 2.0): the system's logo (white), its artwork
 * strips (game art, screenshots, the controller in outline, the circuit board, noir) and what it
 * is (maker, year, kind, colours, a description in many languages). Nothing of it is in the app:
 * each system's files are downloaded the first time it is shown, then kept.
 */
public final class SystemArt {
    private SystemArt() {}

    /** The theme, pinned to one version (so its files never change under the cache). */
    private static final String BASE = "https://raw.githubusercontent.com/anthonycaccese/art-book-next-es-de/"
            + "d772d07109701d9bd7c9fda305bfef6601105ab8/_inc/systems/";

    public static final String ARTWORK = "artwork";
    public static final String SCREENSHOTS = "artwork-screenshots";
    public static final String OUTLINE = "artwork-outline";
    public static final String CIRCUIT = "artwork-circuit";
    public static final String NOIR = "artwork-noir";
    /** The card styles, in the order the options step through them. */
    public static final String[] STYLES = {ARTWORK, SCREENSHOTS, OUTLINE, CIRCUIT, NOIR};

    private static final String KEY_STYLE = "pocket_system_art";
    private static final String KEY_FAILED = "pocket_system_art_failed_";
    private static final String KEY_OFF = "pocket_system_art_off";
    private static final long RETRY_MS = 6L * 60 * 60 * 1000;
    private static final int MAX_BYTES = 3 * 1024 * 1024;

    /** What a system is. */
    public static final class Info {
        public String name;
        public String maker;
        public String year;
        public String kind;
        public String description;
        public int color;
        public int[] palette = new int[0];
    }

    private static final LruCache<String, Bitmap> STRIPS = new LruCache<String, Bitmap>(20 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final LruCache<String, Bitmap> LOGOS = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final LruCache<String, Info> INFOS = new LruCache<>(64);
    private static final Set<String> MISSING = new HashSet<>();

    // ---- Settings -------------------------------------------------------------------------------

    /** The card style (one of STYLES). */
    public static String style(Context c) {
        String s = PocketPrefs.prefs(c).getString(KEY_STYLE, ARTWORK);
        for (String k : STYLES) {
            if (k.equals(s)) {
                return k;
            }
        }
        return ARTWORK;
    }

    public static String nextStyle(Context c) {
        String now = style(c);
        int i = 0;
        for (int k = 0; k < STYLES.length; k++) {
            if (STYLES[k].equals(now)) {
                i = k;
            }
        }
        String next = STYLES[(i + 1) % STYLES.length];
        PocketPrefs.prefs(c).edit().putString(KEY_STYLE, next).apply();
        return next;
    }

    /** Off: the drawn cards only (nothing downloaded). */
    public static boolean enabled(Context c) {
        return !PocketPrefs.prefs(c).getBoolean(KEY_OFF, false);
    }

    public static void setEnabled(Context c, boolean on) {
        PocketPrefs.prefs(c).edit().putBoolean(KEY_OFF, !on).apply();
    }

    /** The theme's name for one of the library's systems (or its collections). */
    public static String themeName(String system) {
        if (system == null) {
            return null;
        }
        switch (system) {
            case "@recent":
                return "auto-lastplayed";
            case "@favorites":
                return "auto-favorites";
            default:
                return system.toLowerCase(Locale.ROOT);
        }
    }

    // ---- Files ----------------------------------------------------------------------------------

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "pocket_systems");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static File file(Context c, String folder, String name, String ext) {
        return new File(dir(c), folder + "__" + name + "." + ext);
    }

    /** True when this file is already here (nothing to wait for). */
    public static boolean has(Context c, String system, String folder) {
        String name = themeName(system);
        return name != null && file(c, folder, name, "logos".equals(folder) ? "svg" : "png").exists();
    }

    /** The file, downloaded when it isn't here yet (null when the theme has none). Blocking. */
    private static File fetch(Context c, String folder, String name, String ext) {
        if (name == null || !enabled(c)) {
            return null;
        }
        File f = file(c, folder, name, ext);
        if (f.exists()) {
            return f;
        }
        String id = folder + "/" + name + "." + ext;
        synchronized (MISSING) {
            if (MISSING.contains(id)) {
                return null;
            }
        }
        SharedPreferences p = PocketPrefs.prefs(c);
        if (System.currentTimeMillis() - p.getLong(KEY_FAILED + id, 0) < RETRY_MS) {
            return null;
        }
        try {
            byte[] data = SteamGridDb.download(BASE + id, MAX_BYTES);
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
            if (!tmp.renameTo(f)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return null;
            }
            return f;
        } catch (IOException | RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                synchronized (MISSING) {
                    MISSING.add(id);
                }
            }
            p.edit().putLong(KEY_FAILED + id, System.currentTimeMillis()).apply();
            return null;
        }
    }

    // ---- Pictures -------------------------------------------------------------------------------

    /** One of the system's artwork strips (454 x 1080, slanted edges). Blocking. */
    public static Bitmap strip(Context c, String system, String folder) {
        String name = themeName(system);
        if (name == null) {
            return null;
        }
        String key = folder + "/" + name;
        Bitmap hit = STRIPS.get(key);
        if (hit != null) {
            return hit;
        }
        File f = fetch(c, folder, name, "png");
        if (f == null) {
            return null;
        }
        try {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) {
                STRIPS.put(key, b);
            }
            return b;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** The system's logo, white, fitted inside maxW x maxH pixels (null: none). Blocking. */
    public static Bitmap logo(Context c, String system, int maxW, int maxH) {
        String name = themeName(system);
        if (name == null || maxW <= 0 || maxH <= 0) {
            return null;
        }
        String key = name + "@" + maxW + "x" + maxH;
        Bitmap hit = LOGOS.get(key);
        if (hit != null) {
            return hit;
        }
        File f = fetch(c, "logos", name, "svg");
        if (f == null) {
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            com.caverock.androidsvg.SVG svg = com.caverock.androidsvg.SVG.getFromInputStream(in);
            RectF box = svg.getDocumentViewBox();
            float w = box != null ? box.width() : svg.getDocumentWidth();
            float h = box != null ? box.height() : svg.getDocumentHeight();
            if (!(w > 0) || !(h > 0)) {
                return null;
            }
            float scale = Math.min(maxW / w, maxH / h);
            int bw = Math.max(1, Math.round(w * scale));
            int bh = Math.max(1, Math.round(h * scale));
            svg.setDocumentWidth(bw);
            svg.setDocumentHeight(bh);
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            svg.renderToCanvas(new Canvas(b), new RectF(0, 0, bw, bh));
            LOGOS.put(key, b);
            return b;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    // ---- Facts ----------------------------------------------------------------------------------

    private static final Pattern LANG = Pattern.compile("<language name=\"([a-zA-Z_]+)\">(.*?)</language>", Pattern.DOTALL);

    /** What the system is, in the handheld's language when the theme has it. Blocking. */
    public static Info info(Context c, String system) {
        String name = themeName(system);
        if (name == null) {
            return null;
        }
        Info hit = INFOS.get(name);
        if (hit != null) {
            return hit;
        }
        File f = fetch(c, "_metadata-global", name, "xml");
        if (f == null) {
            return null;
        }
        String xml;
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            xml = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        String lang = Locale.getDefault().getLanguage();
        String local = null;
        Matcher m = LANG.matcher(xml);
        while (m.find()) {
            if (m.group(1).toLowerCase(Locale.ROOT).startsWith(lang + "_")) {
                local = m.group(2);
                break;
            }
        }
        String global = LANG.matcher(xml).replaceAll("");
        Info i = new Info();
        i.name = tag(global, "systemName");
        i.maker = tag(global, "systemManufacturer");
        i.year = tag(global, "systemReleaseYear");
        i.kind = pick(tag(local, "systemHardwareType"), tag(global, "systemHardwareType"));
        i.description = pick(tag(local, "systemDescription"), tag(global, "systemDescription"));
        i.color = color(tag(global, "systemColor"), 0);
        int[] pal = new int[4];
        int count = 0;
        for (int k = 1; k <= 4; k++) {
            int col = color(tag(global, "systemColorPalette" + k), 0);
            if (col != 0) {
                pal[count++] = col;
            }
        }
        i.palette = java.util.Arrays.copyOf(pal, count);
        INFOS.put(name, i);
        return i;
    }

    /** What's known of the system already (null until info() has run once). */
    public static Info cachedInfo(String system) {
        String name = themeName(system);
        return name != null ? INFOS.get(name) : null;
    }

    private static String pick(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    private static String tag(String xml, String tag) {
        if (xml == null) {
            return null;
        }
        Matcher m = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL).matcher(xml);
        if (!m.find()) {
            return null;
        }
        String v = m.group(1).trim();
        return v.replace("&amp;", "&").replace("&apos;", "'").replace("&quot;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">");
    }

    private static int color(String hex, int fallback) {
        if (hex == null || !hex.matches("[0-9a-fA-F]{6}")) {
            return fallback;
        }
        return 0xFF000000 | Integer.parseInt(hex, 16);
    }

    // ---- Art picked by hand (SteamGridDB) ------------------------------------------------------

    /** Where art picked for a console is kept (like a PC's games, under a made-up PC). */
    public static final String PICK_UUID = "systems";

    public static int pickId(String system) {
        int h = ("sys:" + system).hashCode() & 0x3FFFFFFF;
        return h == 0 ? 1 : h;
    }

    /** The art of this kind picked for the console (null: none), about target pixels across. Blocking. */
    public static Bitmap picked(Context c, String system, int kind, int target) {
        File f = com.limelight.pocket.PocketArt.file(c, PICK_UUID, pickId(system), kind);
        if (f == null || !f.exists()) {
            return null;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= target) {
            sample *= 2;
        }
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        try {
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** A picked cover as the console's card: the picture, cropped to the card, with the count. */
    public static Bitmap pictureCard(Bitmap src, String count, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        Matrix mx = new Matrix();
        mx.setScale(scale, scale);
        mx.postTranslate((w - src.getWidth() * scale) / 2f, (h - src.getHeight() * scale) / 2f);
        cv.drawBitmap(src, mx, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        if (count != null) {
            Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);
            cp.setTextSize(h * 0.058f);
            cp.setTypeface(com.limelight.pocket.ui.PocketFonts.semibold());
            float tw = cp.measureText(count);
            float pad = h * 0.03f;
            RectF pill = new RectF(w - tw - pad * 4, pad * 2, w - pad * 2, pad * 2 + cp.getTextSize() + pad * 1.6f);
            Paint pb = new Paint(Paint.ANTI_ALIAS_FLAG);
            pb.setColor(0x8C000000);
            cv.drawRoundRect(pill, pad * 1.4f, pad * 1.4f, pb);
            cp.setColor(0xFFFFFFFF);
            cv.drawText(count, pill.left + pad, pill.bottom - pad * 1.1f, cp);
        }
        return out;
    }

    // ---- Composed pictures ----------------------------------------------------------------------

    /**
     * The system's card: its artwork filling a card of its colour, darkened at the bottom where its
     * white logo sits, with the number of games in a pill (null when there's no artwork). Blocking.
     */
    public static Bitmap card(Context c, String system, String style, int fallbackColor, String count, int w, int h) {
        Bitmap strip = strip(c, system, style);
        if (strip == null && !ARTWORK.equals(style)) {
            strip = strip(c, system, ARTWORK);
        }
        if (strip == null) {
            return null;
        }
        Info info = info(c, system);
        int color = info != null && info.color != 0 ? info.color : fallbackColor;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, w * 0.4f, h, darker(color, 0.8f), darker(color, 0.22f), Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, bg);

        // The strip, a little wider than the card (its slanted edges mostly fall outside)
        float scale = w * 1.3f / strip.getWidth();
        float sh = strip.getHeight() * scale;
        float top = Math.max(h - sh, Math.min(0, h * 0.44f - sh * 0.44f));
        Matrix mx = new Matrix();
        mx.setScale(scale, scale);
        mx.postTranslate((w - strip.getWidth() * scale) / 2f, top);
        cv.drawBitmap(strip, mx, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));

        // Light from the top, depth at the bottom (where the logo goes)
        Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        shade.setShader(new LinearGradient(0, h * 0.38f, 0, h,
                new int[]{0x00000000, 0x8C000000, 0xE6000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, shade);
        Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tint.setShader(new LinearGradient(0, h * 0.55f, 0, h, 0x00000000, withAlpha(color, 0x55), Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, tint);
        Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        sheen.setShader(new RadialGradient(w * 0.85f, h * 0.02f, w * 0.95f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, sheen);

        Bitmap logo = logo(c, system, Math.round(w * 0.78f), Math.round(h * 0.15f));
        if (logo != null) {
            float lx = (w - logo.getWidth()) / 2f;
            float ly = h * 0.84f - logo.getHeight() / 2f;
            Paint glow = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            glow.setShadowLayer(h * 0.03f, 0, h * 0.006f, 0x99000000);
            cv.drawBitmap(logo, lx, ly, glow);
        }
        else {
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(0xFFFFFFFF);
            t.setTypeface(com.limelight.pocket.ui.PocketFonts.bold());
            t.setTextAlign(Paint.Align.CENTER);
            t.setTextSize(h * 0.075f);
            String label = info != null && info.name != null ? info.name : system;
            while (t.measureText(label) > w * 0.86f && t.getTextSize() > h * 0.04f) {
                t.setTextSize(t.getTextSize() * 0.92f);
            }
            cv.drawText(label, w / 2f, h * 0.87f, t);
        }
        if (count != null) {
            Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);
            cp.setTextSize(h * 0.058f);
            cp.setTypeface(com.limelight.pocket.ui.PocketFonts.semibold());
            float tw = cp.measureText(count);
            float pad = h * 0.03f;
            RectF pill = new RectF(w - tw - pad * 4, pad * 2, w - pad * 2, pad * 2 + cp.getTextSize() + pad * 1.6f);
            Paint pb = new Paint(Paint.ANTI_ALIAS_FLAG);
            pb.setColor(0x73000000);
            cv.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, pb);
            cp.setColor(0xFFFFFFFF);
            cv.drawText(count, pill.left + pad, pill.bottom - pad * 1.1f, cp);
        }
        return out;
    }

    /** A game's card while it has no cover: its name on its console's art, the console's logo under it. Blocking. */
    public static Bitmap gameCard(Context c, String system, String title, int fallbackColor, int w, int h) {
        Bitmap strip = strip(c, system, SCREENSHOTS);
        if (strip == null) {
            strip = strip(c, system, ARTWORK);
        }
        if (strip == null) {
            return null;
        }
        Info info = info(c, system);
        int color = info != null && info.color != 0 ? info.color : fallbackColor;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.drawColor(darker(color, 0.25f));
        float scale = w * 1.3f / strip.getWidth();
        float sh = strip.getHeight() * scale;
        Matrix mx = new Matrix();
        mx.setScale(scale, scale);
        mx.postTranslate((w - strip.getWidth() * scale) / 2f, Math.max(h - sh, Math.min(0, h * 0.5f - sh * 0.5f)));
        Paint art = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        art.setAlpha(0x8C);
        cv.drawBitmap(strip, mx, art);
        Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        shade.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x66000000, 0x99000000, 0xE6000000},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, shade);
        Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tint.setShader(new LinearGradient(0, 0, w, h, withAlpha(color, 0x55), 0x00000000, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, tint);

        // The name: big, left aligned, up to four lines
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(0xFFFFFFFF);
        t.setTypeface(com.limelight.pocket.ui.PocketFonts.bold());
        t.setLetterSpacing(-0.01f);
        float size = h * 0.085f;
        t.setTextSize(size);
        float maxW = w * 0.84f;
        java.util.List<String> lines = wrap(title, t, maxW);
        while ((lines.size() > 4 || widest(lines, t) > maxW) && size > h * 0.045f) {
            size *= 0.9f;
            t.setTextSize(size);
            lines = wrap(title, t, maxW);
        }
        float y = h * 0.16f + size;
        for (int i = 0; i < lines.size() && i < 4; i++) {
            String l = lines.get(i);
            if (i == 3 && lines.size() > 4) {
                l = l + "…";
            }
            cv.drawText(l, w * 0.08f, y, t);
            y += size * 1.12f;
        }
        Bitmap logo = logo(c, system, Math.round(w * 0.56f), Math.round(h * 0.085f));
        if (logo != null) {
            Paint lp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            lp.setAlpha(0xE6);
            cv.drawBitmap(logo, w * 0.08f, h * 0.9f - logo.getHeight(), lp);
        }
        return out;
    }

    private static java.util.List<String> wrap(String text, Paint p, float maxW) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (p.measureText(next) > maxW && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            }
            else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static float widest(java.util.List<String> lines, Paint p) {
        float m = 0;
        for (String l : lines) {
            m = Math.max(m, p.measureText(l));
        }
        return m;
    }

    /**
     * The system's backdrop (w x h): its colours glowing on the left behind the text, and three of
     * its strips standing side by side on the right (the theme's own look). Blocking.
     */
    public static Bitmap backdrop(Context c, String system, int fallbackColor, int w, int h) {
        Bitmap first = strip(c, system, SCREENSHOTS);
        Bitmap second = strip(c, system, ARTWORK);
        Bitmap third = strip(c, system, NOIR);
        if (third == null) {
            third = strip(c, system, CIRCUIT);
        }
        if (first == null && second == null) {
            return null;
        }
        Info info = info(c, system);
        int color = info != null && info.color != 0 ? info.color : fallbackColor;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.drawColor(0xFF000000);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setShader(new RadialGradient(w * 0.08f, h * 0.2f, w * 0.75f,
                new int[]{withAlpha(darker(color, 0.9f), 0xB0), withAlpha(darker(color, 0.5f), 0x40), 0x00000000},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, glow);
        if (info != null && info.palette.length >= 2) {
            Paint second2 = new Paint(Paint.ANTI_ALIAS_FLAG);
            second2.setShader(new RadialGradient(w * 0.45f, h * 1.05f, w * 0.5f,
                    withAlpha(info.palette[1], 0x38), 0x00000000, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, second2);
        }
        Bitmap[] strips = {third, second, first};
        float sh = h * 1.02f;
        float x = w;
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        for (Bitmap s : strips) {
            if (s == null) {
                continue;
            }
            float scale = sh / s.getHeight();
            float sw = s.getWidth() * scale;
            // Neighbours overlap on their slanted edges, like the theme's
            x -= sw * 0.84f;
            Rect src = new Rect(0, 0, s.getWidth(), s.getHeight());
            RectF dst = new RectF(x, (h - sh) / 2f, x + sw, (h + sh) / 2f);
            cv.drawBitmap(s, src, dst, p);
        }
        // The strips fade into the colours on their left
        Paint glowBack = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowBack.setShader(new LinearGradient(x - 1, 0, x + w * 0.2f, 0, 0x99000000, 0x00000000, Shader.TileMode.CLAMP));
        cv.drawRect(x - 1, 0, x + w * 0.2f, h, glowBack);
        return out;
    }

    private static int darker(int color, float f) {
        return Color.rgb(Math.round(Color.red(color) * f), Math.round(Color.green(color) * f), Math.round(Color.blue(color) * f));
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
