package com.limelight.pocket.cloud;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;

import com.limelight.pocket.ui.PocketFonts;

/**
 * Covers and backdrops for the cloud services when SteamGridDB has none (or there's no key):
 * light in the service's colours, its official app's icon when that's installed (or a cloud
 * with a play mark), its name set big. A real cover (SteamGridDB) is used as it is, with the
 * "running" pill on top.
 */
public final class CloudArt {
    private CloudArt() {}

    /** The service's colours: light, main, deep. */
    static int[] palette(CloudService s) {
        switch (s.id) {
            case "xcloud":
                return new int[]{0xFF5DC21E, 0xFF107C10, 0xFF04260A};
            case "gfn":
                return new int[]{0xFFA4E24A, 0xFF5E9A00, 0xFF0E1A02};
            case "boosteroid":
                return new int[]{0xFFA88BFF, 0xFF5B3CC4, 0xFF130A33};
            default:
                return new int[]{lighter(s.color), s.color, darker(s.color, 0.2f)};
        }
    }

    private static int lighter(int c) {
        return Color.rgb(Math.min(255, Color.red(c) + 80), Math.min(255, Color.green(c) + 80), Math.min(255, Color.blue(c) + 80));
    }

    private static int darker(int c, float f) {
        return Color.rgb(Math.round(Color.red(c) * f), Math.round(Color.green(c) * f), Math.round(Color.blue(c) * f));
    }

    private static int alpha(int c, int a) {
        return (c & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24);
    }

    /** Soft light in the service's colours (a mesh of glows on its deep colour). */
    private static void light(Canvas c, CloudService s, int w, int h) {
        int[] p = palette(s);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, w * 0.35f, h, darker(p[1], 0.62f), p[2], Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);
        float big = Math.max(w, h);
        glow(c, w * 0.9f, h * 0.05f, big * 0.75f, alpha(p[0], 0x8C));
        glow(c, w * 0.05f, h * 0.55f, big * 0.6f, alpha(p[1], 0x99));
        glow(c, w * 0.7f, h * 1.0f, big * 0.55f, alpha(darker(p[1], 0.7f), 0xB3));
        // Streams of light (a signal, going far), very faint
        Paint streak = new Paint(Paint.ANTI_ALIAS_FLAG);
        streak.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 5; i++) {
            float y = h * (0.12f + i * 0.17f);
            float x0 = -w * 0.2f + i * w * 0.07f;
            float x1 = x0 + w * (0.7f + 0.1f * (i % 2));
            streak.setStrokeWidth(Math.max(1f, big * (0.004f + 0.002f * (i % 3))));
            streak.setShader(new LinearGradient(x0, 0, x1, 0, new int[]{0x00FFFFFF, 0x24FFFFFF, 0x00FFFFFF},
                    null, Shader.TileMode.CLAMP));
            c.save();
            c.rotate(-18, w / 2f, h / 2f);
            c.drawLine(x0, y, x1, y, streak);
            c.restore();
        }
    }

    private static void glow(Canvas c, float x, float y, float r, int color) {
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setShader(new RadialGradient(x, y, r, new int[]{color, alpha(color, (color >>> 24) / 3), alpha(color, 0)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, g);
    }

    /** The official app's icon (when it's installed), else null. */
    static Bitmap officialIcon(Context ctx, CloudService s, int size) {
        String pkg = s.installedOfficial(ctx);
        if (pkg == null) {
            return null;
        }
        try {
            Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, size, size);
            d.draw(new Canvas(b));
            return b;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /** A cloud with a play mark in it (drawn, the app's own sign). */
    private static void cloudMark(Canvas c, float cx, float cy, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setShadowLayer(size * 0.12f, 0, size * 0.04f, 0x55000000);
        float u = size / 10f;
        Path cloud = new Path();
        cloud.addCircle(cx - 2.2f * u, cy + 0.6f * u, 2.3f * u, Path.Direction.CW);
        cloud.addCircle(cx + 0.6f * u, cy - 1.1f * u, 3.2f * u, Path.Direction.CW);
        cloud.addCircle(cx + 3.3f * u, cy + 0.9f * u, 2.0f * u, Path.Direction.CW);
        cloud.addRoundRect(new RectF(cx - 4.5f * u, cy + 0.4f * u, cx + 5.3f * u, cy + 2.9f * u), 1.25f * u, 1.25f * u,
                Path.Direction.CW);
        c.drawPath(cloud, p);
        Paint play = new Paint(Paint.ANTI_ALIAS_FLAG);
        play.setColor(color);
        Path tri = new Path();
        tri.moveTo(cx - 0.6f * u, cy - 1.0f * u);
        tri.lineTo(cx + 1.9f * u, cy + 0.6f * u);
        tri.lineTo(cx - 0.6f * u, cy + 2.2f * u);
        tri.close();
        c.drawPath(tri, play);
    }

    /** The service's card (w x h); cover: a real one (SteamGridDB) to use instead, or null. */
    public static Bitmap card(Context ctx, CloudService s, String name, boolean running, Bitmap cover, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        int[] p = palette(s);
        if (cover != null) {
            cover = software(cover);
        }
        if (cover != null) {
            drawCover(c, cover, w, h);
            Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
            shade.setShader(new LinearGradient(0, h * 0.7f, 0, h, 0x00000000, 0x66000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, shade);
        }
        else {
            light(c, s, w, h);
            Bitmap icon = officialIcon(ctx, s, Math.round(w * 0.42f));
            float cy = h * 0.36f;
            if (icon != null) {
                Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
                ip.setShadowLayer(w * 0.06f, 0, w * 0.02f, 0x66000000);
                c.drawBitmap(icon, (w - icon.getWidth()) / 2f, cy - icon.getHeight() / 2f, ip);
            }
            else {
                cloudMark(c, w / 2f, cy, w * 0.46f, p[1]);
            }
            // The name, big and left aligned, over a small label
            Paint eyebrow = new Paint(Paint.ANTI_ALIAS_FLAG);
            eyebrow.setColor(0xB3FFFFFF);
            eyebrow.setTypeface(PocketFonts.semibold());
            eyebrow.setTextSize(h * 0.04f);
            eyebrow.setLetterSpacing(0.18f);
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(0xFFFFFFFF);
            t.setTypeface(PocketFonts.bold());
            t.setLetterSpacing(-0.02f);
            float size = h * 0.085f;
            t.setTextSize(size);
            String[] words = name.split(" ");
            float maxW = w * 0.84f;
            for (String word : words) {
                while (t.measureText(word) > maxW && size > h * 0.04f) {
                    size *= 0.92f;
                    t.setTextSize(size);
                }
            }
            // Words onto lines (two at most)
            java.util.List<String> lines = new java.util.ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                String next = line.length() == 0 ? word : line + " " + word;
                if (t.measureText(next) > maxW && line.length() > 0) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                }
                else {
                    line = new StringBuilder(next);
                }
            }
            lines.add(line.toString());
            float x = w * 0.08f;
            float y = h * 0.92f - (lines.size() - 1) * size * 1.05f;
            c.drawText(ctx.getString(com.limelight.R.string.pocket_section_cloud).toUpperCase(java.util.Locale.getDefault()),
                    x, y - size * 1.15f, eyebrow);
            for (String l : lines) {
                c.drawText(l, x, y, t);
                y += size * 1.05f;
            }
        }
        // A thin bright edge at the top (glass)
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setShader(new LinearGradient(0, 0, 0, h * 0.25f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.25f, edge);
        if (running) {
            Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
            pill.setColor(0x99000000);
            float ph = h * 0.075f;
            float pw = w * 0.16f;
            RectF r = new RectF(w - pw - w * 0.06f, h * 0.04f, w - w * 0.06f, h * 0.04f + ph);
            c.drawRoundRect(r, ph / 2, ph / 2, pill);
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(0xFF4CFF8F);
            dot.setShadowLayer(ph * 0.4f, 0, 0, 0xCC4CFF8F);
            c.drawCircle(r.centerX(), r.centerY(), ph * 0.22f, dot);
        }
        return out;
    }

    /** A wide backdrop for the hero (when SteamGridDB has no background for it). */
    public static Bitmap backdrop(Context ctx, CloudService s, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        light(c, s, w, h);
        Bitmap icon = officialIcon(ctx, s, Math.round(h * 0.42f));
        int[] p = palette(s);
        if (icon != null) {
            Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            ip.setAlpha(0xD9);
            ip.setShadowLayer(h * 0.05f, 0, h * 0.015f, 0x66000000);
            c.drawBitmap(icon, w * 0.72f - icon.getWidth() / 2f, h * 0.36f - icon.getHeight() / 2f, ip);
        }
        else {
            cloudMark(c, w * 0.72f, h * 0.36f, h * 0.5f, p[1]);
        }
        return out;
    }

    private static void drawCover(Canvas c, Bitmap src, int w, int h) {
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((w - src.getWidth() * scale) / 2f, (h - src.getHeight() * scale) / 2f);
        c.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
    }

    private static Bitmap software(Bitmap b) {
        if (b != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && b.getConfig() == Bitmap.Config.HARDWARE) {
            try {
                return b.copy(Bitmap.Config.ARGB_8888, false);
            } catch (RuntimeException | OutOfMemoryError e) {
                return null;
            }
        }
        return b;
    }
}
