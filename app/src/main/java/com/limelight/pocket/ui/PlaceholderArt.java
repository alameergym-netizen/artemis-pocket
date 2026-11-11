package com.limelight.pocket.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.LruCache;

import java.util.Locale;

/**
 * Cover art for apps that only have a plain grey placeholder (Desktop, Steam Big Picture, tools):
 * a deep two-tone gradient (its hue chosen from the name, so each app keeps its own colour), a
 * soft light in one corner, a clean glyph and the name. 2:3, like real covers.
 */
public final class PlaceholderArt {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(16);

    private PlaceholderArt() {
    }

    private static String kind(String n) {
        if (n.contains("desktop") || n.contains("bureau") || n.contains("escritorio")) {
            return "desktop";
        }
        if (n.contains("big picture") || n.contains("steam") || n.contains("playnite")
                || n.contains("launcher") || n.contains("xbox")) {
            return "gamepad";
        }
        return "letter";
    }

    public static Bitmap render(String name, int width) {
        String label = name == null || name.trim().isEmpty() ? "?" : name.trim();
        String key = label + "@" + width;
        synchronized (CACHE) {
            Bitmap hit = CACHE.get(key);
            if (hit != null && !hit.isRecycled()) {
                return hit;
            }
        }
        int w = width;
        int h = Math.round(width * 1.5f);
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        String n = label.toLowerCase(Locale.ROOT);
        String kind = kind(n);

        // A hue of its own (Desktop gets a calm steel blue)
        float hue = "desktop".equals(kind) ? 214f : ((label.hashCode() & 0x7fffffff) % 360);
        int top = Color.HSVToColor(new float[]{hue, 0.55f, 0.36f});
        int bottom = Color.HSVToColor(new float[]{(hue + 24f) % 360f, 0.70f, 0.11f});
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, w * 0.35f, h, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        // Soft light from the top right
        p.setShader(new RadialGradient(w * 0.85f, h * 0.08f, w * 0.9f,
                new int[]{0x38FFFFFF, 0x0DFFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // The glyph
        float cx = w / 2f;
        float cy = h * 0.40f;
        float s = w * 0.36f;
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(0xF2FFFFFF);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(w * 0.028f);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        if ("desktop".equals(kind)) {
            RectF screen = new RectF(cx - s * 0.62f, cy - s * 0.42f, cx + s * 0.62f, cy + s * 0.34f);
            c.drawRoundRect(screen, s * 0.08f, s * 0.08f, line);
            c.drawLine(cx, cy + s * 0.34f, cx, cy + s * 0.52f, line);
            c.drawLine(cx - s * 0.28f, cy + s * 0.54f, cx + s * 0.28f, cy + s * 0.54f, line);
        }
        else if ("gamepad".equals(kind)) {
            RectF body = new RectF(cx - s * 0.66f, cy - s * 0.30f, cx + s * 0.66f, cy + s * 0.34f);
            c.drawRoundRect(body, s * 0.30f, s * 0.30f, line);
            c.drawLine(cx - s * 0.40f, cy + s * 0.02f, cx - s * 0.16f, cy + s * 0.02f, line);
            c.drawLine(cx - s * 0.28f, cy - s * 0.10f, cx - s * 0.28f, cy + s * 0.14f, line);
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(0xF2FFFFFF);
            c.drawCircle(cx + s * 0.26f, cy - s * 0.04f, s * 0.05f, dot);
            c.drawCircle(cx + s * 0.40f, cy + s * 0.08f, s * 0.05f, dot);
        }
        else {
            TextPaint big = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            big.setColor(0xF2FFFFFF);
            big.setTypeface(com.limelight.pocket.ui.PocketFonts.regular());
            big.setTextSize(w * 0.42f);
            big.setTextAlign(Paint.Align.CENTER);
            String letter = label.substring(0, 1).toUpperCase(Locale.ROOT);
            c.drawText(letter, cx, cy - (big.descent() + big.ascent()) / 2f, big);
        }

        // The name
        TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xFFFFFFFF);
        text.setTypeface(com.limelight.pocket.ui.PocketFonts.medium());
        text.setTextSize(w * 0.088f);
        text.setTextAlign(Paint.Align.CENTER);
        CharSequence fitted = TextUtils.ellipsize(label, text, w * 0.84f, TextUtils.TruncateAt.END);
        c.drawText(fitted, 0, fitted.length(), cx, h * 0.80f, text);
        Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
        rule.setColor(0x66FFFFFF);
        c.drawRoundRect(new RectF(cx - w * 0.06f, h * 0.84f, cx + w * 0.06f, h * 0.84f + w * 0.012f),
                w * 0.01f, w * 0.01f, rule);

        synchronized (CACHE) {
            CACHE.put(key, bitmap);
        }
        return bitmap;
    }
}
