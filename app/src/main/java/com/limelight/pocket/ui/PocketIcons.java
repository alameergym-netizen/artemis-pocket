package com.limelight.pocket.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/**
 * Artemis Pocket line icons (24x24 grid, 1.8 stroke, round caps), drawn straight onto a Canvas so
 * they stay sharp at any size. Generated from the same spec that was previewed while designing
 * the radial menu (no image files).
 */
public final class PocketIcons {
    private PocketIcons() {}

    private static final Path PATH = new Path();
    private static final RectF RECT = new RectF();

    /** Draws icon `name` centred at (cx, cy), size px wide. Unknown names draw a dot. */
    public static void draw(Canvas c, String name, float cx, float cy, float size, int color, Paint p) {
        float k = size / 24f;
        c.save();
        c.translate(cx - size / 2f, cy - size / 2f);
        c.scale(k, k);
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStrokeWidth(1.8f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setShader(null);
        switch (name == null ? "" : name) {
            case "GUIDE":
                circle(c, p, 12f, 12f, 9f, false);
                circle(c, p, 12f, 12f, 3.4f, true);
                break;
            case "GAME_BAR":
                rect(c, p, 4f, 4f, 10.5f, 10.5f, 1.8f, false);
                rect(c, p, 13.5f, 4f, 20f, 10.5f, 1.8f, false);
                rect(c, p, 4f, 13.5f, 10.5f, 20f, 1.8f, false);
                rect(c, p, 13.5f, 13.5f, 20f, 20f, 1.8f, false);
                break;
            case "RADIAL":
                circle(c, p, 12f, 12f, 9f, false);
                circle(c, p, 12f, 12f, 3f, false);
                c.drawLine(12f, 3f, 12f, 6.5f, stroke(p));
                c.drawLine(12f, 17.5f, 12f, 21f, stroke(p));
                c.drawLine(3f, 12f, 6.5f, 12f, stroke(p));
                c.drawLine(17.5f, 12f, 21f, 12f, stroke(p));
                break;
            case "QUICK_MENU":
                c.drawLine(5f, 7f, 19f, 7f, stroke(p));
                c.drawLine(5f, 12f, 19f, 12f, stroke(p));
                c.drawLine(5f, 17f, 19f, 17f, stroke(p));
                break;
            case "VIEW":
                rect(c, p, 3.5f, 8f, 14.5f, 19f, 2f, false);
                poly(c, p, false, false, new float[]{9.5f, 8f, 9.5f, 5f, 20.5f, 5f, 20.5f, 16f, 14.5f, 16f});
                break;
            case "START":
                c.drawLine(7f, 7.5f, 17f, 7.5f, stroke(p));
                c.drawLine(7f, 12f, 17f, 12f, stroke(p));
                c.drawLine(7f, 16.5f, 17f, 16.5f, stroke(p));
                circle(c, p, 4.5f, 7.5f, 0.6f, true);
                circle(c, p, 4.5f, 12f, 0.6f, true);
                circle(c, p, 4.5f, 16.5f, 0.6f, true);
                break;
            case "ESC":
                poly(c, p, false, false, new float[]{13f, 5f, 5f, 5f, 5f, 19f, 13f, 19f});
                c.drawLine(9.5f, 12f, 20f, 12f, stroke(p));
                poly(c, p, false, false, new float[]{16.5f, 8.5f, 20f, 12f, 16.5f, 15.5f});
                break;
            case "ALT_TAB":
                rect(c, p, 3f, 8f, 15f, 19f, 2f, false);
                poly(c, p, false, false, new float[]{8f, 8f, 8f, 5f, 21f, 5f, 21f, 15f, 15f, 15f});
                break;
            case "WIN":
                rect(c, p, 3.5f, 4.5f, 20.5f, 19.5f, 2.5f, false);
                c.drawLine(3.5f, 9f, 20.5f, 9f, stroke(p));
                circle(c, p, 6.5f, 6.8f, 0.5f, true);
                circle(c, p, 8.7f, 6.8f, 0.5f, true);
                break;
            case "ALT_F4":
                rect(c, p, 3.5f, 4.5f, 20.5f, 19.5f, 2.5f, false);
                c.drawLine(9f, 9.5f, 15f, 15.5f, stroke(p));
                c.drawLine(15f, 9.5f, 9f, 15.5f, stroke(p));
                break;
            case "ALT_ENTER":
                poly(c, p, false, false, new float[]{4f, 9f, 4f, 4f, 9f, 4f});
                poly(c, p, false, false, new float[]{15f, 4f, 20f, 4f, 20f, 9f});
                poly(c, p, false, false, new float[]{20f, 15f, 20f, 20f, 15f, 20f});
                poly(c, p, false, false, new float[]{9f, 20f, 4f, 20f, 4f, 15f});
                break;
            case "TASK_MANAGER":
                poly(c, p, false, false, new float[]{3f, 13f, 7f, 13f, 10f, 6f, 14f, 18f, 17f, 11f, 21f, 11f});
                break;
            case "DESKTOP":
                rect(c, p, 3f, 4f, 21f, 16f, 2f, false);
                c.drawLine(12f, 16f, 12f, 20f, stroke(p));
                c.drawLine(8f, 20f, 16f, 20f, stroke(p));
                break;
            case "SCREENSHOT":
                rect(c, p, 3f, 7f, 21f, 19.5f, 3f, false);
                circle(c, p, 12f, 13.2f, 3.6f, false);
                poly(c, p, false, false, new float[]{8f, 7f, 9.6f, 4.5f, 14.4f, 4.5f, 16f, 7f});
                break;
            case "RECORD":
                circle(c, p, 12f, 12f, 8.5f, false);
                circle(c, p, 12f, 12f, 4.2f, true);
                break;
            case "PLAY_PAUSE":
                poly(c, p, true, true, new float[]{4.5f, 5.5f, 12.5f, 12f, 4.5f, 18.5f});
                c.drawLine(16f, 6f, 16f, 18f, stroke(p));
                c.drawLine(20f, 6f, 20f, 18f, stroke(p));
                break;
            case "VOLUME_UP":
                poly(c, p, true, false, new float[]{3f, 9.5f, 7f, 9.5f, 12f, 5f, 12f, 19f, 7f, 14.5f, 3f, 14.5f});
                arc(c, p, 12f, 12f, 4.5f, -45f, 90f);
                arc(c, p, 12f, 12f, 8.2f, -50f, 100f);
                break;
            case "VOLUME_DOWN":
                poly(c, p, true, false, new float[]{3f, 9.5f, 7f, 9.5f, 12f, 5f, 12f, 19f, 7f, 14.5f, 3f, 14.5f});
                arc(c, p, 12f, 12f, 4.5f, -45f, 90f);
                break;
            case "MUTE":
                poly(c, p, true, false, new float[]{3f, 9.5f, 7f, 9.5f, 12f, 5f, 12f, 19f, 7f, 14.5f, 3f, 14.5f});
                c.drawLine(16f, 9.5f, 21f, 14.5f, stroke(p));
                c.drawLine(21f, 9.5f, 16f, 14.5f, stroke(p));
                break;
            case "STATS":
                c.drawLine(6f, 19f, 6f, 13f, stroke(p));
                c.drawLine(12f, 19f, 12f, 5.5f, stroke(p));
                c.drawLine(18f, 19f, 18f, 9.5f, stroke(p));
                break;
            case "KEYBOARD":
                rect(c, p, 2.5f, 6f, 21.5f, 18f, 2.5f, false);
                c.drawLine(6f, 10f, 6.01f, 10f, stroke(p));
                c.drawLine(9.3f, 10f, 9.31f, 10f, stroke(p));
                c.drawLine(12.6f, 10f, 12.61f, 10f, stroke(p));
                c.drawLine(15.9f, 10f, 15.91f, 10f, stroke(p));
                c.drawLine(19f, 10f, 19.01f, 10f, stroke(p));
                c.drawLine(8f, 14.5f, 16f, 14.5f, stroke(p));
                break;
            case "TOUCH_KEYBOARD":
                // A keyboard with a fingertip tapping a key
                rect(c, p, 2.5f, 4.5f, 21.5f, 15f, 2.5f, false);
                c.drawLine(6f, 8.3f, 6.01f, 8.3f, stroke(p));
                c.drawLine(10f, 8.3f, 10.01f, 8.3f, stroke(p));
                c.drawLine(14f, 8.3f, 14.01f, 8.3f, stroke(p));
                c.drawLine(18f, 8.3f, 18.01f, 8.3f, stroke(p));
                c.drawLine(8f, 11.6f, 13f, 11.6f, stroke(p));
                circle(c, p, 17f, 18.6f, 2.6f, true);
                break;
            case "MOUSE":
                rect(c, p, 6.5f, 3f, 17.5f, 21f, 5.5f, false);
                c.drawLine(12f, 3f, 12f, 9f, stroke(p));
                break;
            case "ANDROID_HOME":
                poly(c, p, false, false, new float[]{3.5f, 11f, 12f, 3.8f, 20.5f, 11f});
                poly(c, p, false, false, new float[]{6f, 9f, 6f, 20f, 18f, 20f, 18f, 9f});
                rect(c, p, 10f, 14f, 14f, 20f, 1f, false);
                break;
            case "DISCONNECT":
                rect(c, p, 6f, 8f, 18f, 14.5f, 2.2f, false);
                c.drawLine(9.5f, 4f, 9.5f, 8f, stroke(p));
                c.drawLine(14.5f, 4f, 14.5f, 8f, stroke(p));
                c.drawLine(12f, 14.5f, 12f, 20.5f, stroke(p));
                break;
            case "QUIT":
                arc(c, p, 12f, 13f, 8f, -60f, 300f);
                c.drawLine(12f, 3f, 12f, 12f, stroke(p));
                break;
            case "NONE":
                circle(c, p, 12f, 12f, 1.6f, true);
                break;
            case "DEFAULT":
                circle(c, p, 12f, 12f, 1.6f, true);
                break;
            case "SUN":
                circle(c, p, 12f, 12f, 4f, false);
                c.drawLine(18.5f, 12f, 20.8f, 12f, stroke(p));
                c.drawLine(16.596f, 16.596f, 18.223f, 18.223f, stroke(p));
                c.drawLine(12f, 18.5f, 12f, 20.8f, stroke(p));
                c.drawLine(7.404f, 16.596f, 5.777f, 18.223f, stroke(p));
                c.drawLine(5.5f, 12f, 3.2f, 12f, stroke(p));
                c.drawLine(7.404f, 7.404f, 5.777f, 5.777f, stroke(p));
                c.drawLine(12f, 5.5f, 12f, 3.2f, stroke(p));
                c.drawLine(16.596f, 7.404f, 18.223f, 5.777f, stroke(p));
                break;
            case "MIC":
                rect(c, p, 9f, 3f, 15f, 15f, 3f, false);
                arc(c, p, 12f, 11f, 6.5f, 0f, 180f);
                c.drawLine(12f, 17.5f, 12f, 21f, stroke(p));
                c.drawLine(8.5f, 21f, 15.5f, 21f, stroke(p));
                break;
            case "WIFI":
                arc(c, p, 12f, 20f, 15f, -135f, 90f);
                arc(c, p, 12f, 20f, 10f, -135f, 90f);
                arc(c, p, 12f, 20f, 5f, -135f, 90f);
                circle(c, p, 12f, 19.5f, 1.2f, true);
                break;
            case "SPEED":
                arc(c, p, 12f, 14f, 9f, -210f, 240f);
                c.drawLine(12f, 14f, 16.5f, 9f, stroke(p));
                circle(c, p, 12f, 14f, 1.4f, true);
                break;
            case "SPARKLE":
                poly(c, p, true, false, new float[]{12f, 3f, 13.8f, 10.2f, 21f, 12f, 13.8f, 13.8f, 12f, 21f, 10.2f, 13.8f, 3f, 12f, 10.2f, 10.2f});
                c.drawLine(19f, 3.5f, 19f, 6.5f, stroke(p));
                c.drawLine(17.5f, 5f, 20.5f, 5f, stroke(p));
                break;
            case "CLIPBOARD":
                rect(c, p, 5f, 5f, 19f, 21f, 2.5f, false);
                rect(c, p, 9f, 3f, 15f, 7f, 1.5f, false);
                c.drawLine(9f, 12f, 15f, 12f, stroke(p));
                c.drawLine(9f, 16f, 13f, 16f, stroke(p));
                break;
            case "TERMINAL":
                rect(c, p, 3f, 4.5f, 21f, 19.5f, 2.5f, false);
                poly(c, p, false, false, new float[]{7f, 9.5f, 10f, 12f, 7f, 14.5f});
                c.drawLine(12.5f, 15f, 17f, 15f, stroke(p));
                break;
            case "GAMEPAD":
                poly(c, p, true, false, new float[]{7f, 7f, 17f, 7f, 21f, 15.5f, 19f, 18.5f, 15.5f, 15f, 8.5f, 15f, 5f, 18.5f, 3f, 15.5f});
                c.drawLine(8f, 10f, 8f, 13f, stroke(p));
                c.drawLine(6.5f, 11.5f, 9.5f, 11.5f, stroke(p));
                circle(c, p, 15.5f, 10.5f, 0.9f, true);
                circle(c, p, 17.5f, 12.5f, 0.9f, true);
                break;
            case "LIST":
                c.drawLine(9f, 7f, 20f, 7f, stroke(p));
                c.drawLine(9f, 12f, 20f, 12f, stroke(p));
                c.drawLine(9f, 17f, 20f, 17f, stroke(p));
                circle(c, p, 5f, 7f, 1f, true);
                circle(c, p, 5f, 12f, 1f, true);
                circle(c, p, 5f, 17f, 1f, true);
                break;
            case "PLAY":
                poly(c, p, true, false, new float[]{7f, 4.5f, 19f, 12f, 7f, 19.5f});
                break;
            case "SLIDERS":
                c.drawLine(4f, 7f, 20f, 7f, stroke(p));
                c.drawLine(4f, 17f, 20f, 17f, stroke(p));
                circle(c, p, 9f, 7f, 2.3f, true);
                circle(c, p, 15f, 17f, 2.3f, true);
                c.drawLine(4f, 12f, 20f, 12f, stroke(p));
                circle(c, p, 17f, 12f, 2.3f, true);
                break;
            case "LOCK":
                rect(c, p, 5f, 10.5f, 19f, 20.5f, 2.5f, false);
                poly(c, p, false, false, new float[]{8f, 10.5f, 8f, 7.5f, 9.2f, 4.8f, 12f, 3.6f, 14.8f, 4.8f, 16f, 7.5f, 16f, 10.5f});
                c.drawLine(12f, 14.5f, 12f, 16.5f, stroke(p));
                break;
            case "MOON":
                poly(c, p, true, false, new float[]{15f, 3.5f, 10.5f, 4.8f, 7.2f, 8.2f, 6.3f, 12.6f, 7.6f, 16.6f, 11f, 19.6f, 15.4f, 20.4f, 19.6f, 18.6f, 21f, 16f, 16.8f, 15.8f, 13.4f, 13.6f, 11.6f, 10f, 12.2f, 6.2f});
                break;
            case "RESTART":
                arc(c, p, 12f, 12f, 8f, -60f, 300f);
                poly(c, p, false, false, new float[]{16f, 2.6f, 16f, 7.1f, 20.5f, 7.1f});
                break;
            case "PULSE":
                poly(c, p, false, false, new float[]{3f, 13f, 7f, 13f, 10f, 6f, 14f, 18f, 17f, 11f, 21f, 11f});
                break;
            case "STAR":
                poly(c, p, true, false, new float[]{12f, 3.5f, 14.5f, 9f, 20.5f, 9.5f, 16f, 13.5f, 17.4f, 19.5f, 12f, 16.4f, 6.6f, 19.5f, 8f, 13.5f, 3.5f, 9.5f, 9.5f, 9f});
                break;
            case "INFO":
                circle(c, p, 12f, 12f, 9f, false);
                c.drawLine(12f, 11f, 12f, 16.5f, stroke(p));
                circle(c, p, 12f, 7.6f, 0.9f, true);
                break;
            case "SWAP":
                poly(c, p, false, false, new float[]{16f, 3.5f, 20f, 7.5f, 16f, 11.5f});
                c.drawLine(20f, 7.5f, 6f, 7.5f, stroke(p));
                poly(c, p, false, false, new float[]{8f, 12.5f, 4f, 16.5f, 8f, 20.5f});
                c.drawLine(4f, 16.5f, 18f, 16.5f, stroke(p));
                break;
            default:
                circle(c, p, 12f, 12f, 1.6f, true);
                break;
        }
        c.restore();
    }

    public static boolean has(String name) {
        switch (name == null ? "" : name) {
            case "GUIDE":
            case "GAME_BAR":
            case "RADIAL":
            case "QUICK_MENU":
            case "VIEW":
            case "START":
            case "ESC":
            case "ALT_TAB":
            case "WIN":
            case "ALT_F4":
            case "ALT_ENTER":
            case "TASK_MANAGER":
            case "DESKTOP":
            case "SCREENSHOT":
            case "RECORD":
            case "PLAY_PAUSE":
            case "VOLUME_UP":
            case "VOLUME_DOWN":
            case "MUTE":
            case "STATS":
            case "KEYBOARD":
            case "TOUCH_KEYBOARD":
            case "MOUSE":
            case "ANDROID_HOME":
            case "DISCONNECT":
            case "QUIT":
            case "NONE":
            case "DEFAULT":
            case "SUN":
            case "MIC":
            case "WIFI":
            case "SPEED":
            case "SPARKLE":
            case "CLIPBOARD":
            case "TERMINAL":
            case "GAMEPAD":
            case "LIST":
            case "PLAY":
            case "SLIDERS":
            case "LOCK":
            case "MOON":
            case "RESTART":
            case "PULSE":
            case "STAR":
            case "INFO":
            case "SWAP":
                return true;
            default:
                return false;
        }
    }

    private static Paint stroke(Paint p) {
        p.setStyle(Paint.Style.STROKE);
        return p;
    }

    private static void circle(Canvas c, Paint p, float x, float y, float r, boolean fill) {
        p.setStyle(fill ? Paint.Style.FILL : Paint.Style.STROKE);
        c.drawCircle(x, y, r, p);
    }

    private static void rect(Canvas c, Paint p, float l, float t, float r, float b, float radius, boolean fill) {
        p.setStyle(fill ? Paint.Style.FILL : Paint.Style.STROKE);
        RECT.set(l, t, r, b);
        c.drawRoundRect(RECT, radius, radius, p);
    }

    private static void poly(Canvas c, Paint p, boolean closed, boolean fill, float[] pts) {
        PATH.reset();
        PATH.moveTo(pts[0], pts[1]);
        for (int i = 2; i + 1 < pts.length; i += 2) {
            PATH.lineTo(pts[i], pts[i + 1]);
        }
        if (closed) {
            PATH.close();
        }
        p.setStyle(fill ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
        c.drawPath(PATH, p);
    }

    private static void arc(Canvas c, Paint p, float x, float y, float r, float start, float sweep) {
        p.setStyle(Paint.Style.STROKE);
        RECT.set(x - r, y - r, x + r, y + r);
        c.drawArc(RECT, start, sweep, false, p);
    }

    /** The icon as a Drawable (for rows, buttons). */
    public static Drawable drawable(final String name, final int sizePx, final int color) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private int tint = color;

            @Override
            public void draw(@NonNull Canvas canvas) {
                PocketIcons.draw(canvas, name, getBounds().exactCenterX(), getBounds().exactCenterY(),
                        Math.min(getBounds().width(), getBounds().height()), tint, paint);
            }

            @Override
            public void setAlpha(int alpha) {
                tint = (tint & 0x00FFFFFF) | (alpha << 24);
                invalidateSelf();
            }

            @Override
            public void setColorFilter(ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }

            @Override
            public int getIntrinsicWidth() {
                return sizePx;
            }

            @Override
            public int getIntrinsicHeight() {
                return sizePx;
            }
        };
    }
}
