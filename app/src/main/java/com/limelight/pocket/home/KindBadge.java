package com.limelight.pocket.home;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/**
 * Where a Home item comes from, as a small drawn glyph: Android, a streamed PC game, a PC's
 * library, the cloud, retro, GameNative / GameHub, another app's shortcut. Two looks: a dark
 * round badge with the glyph in colour (on a tile) and a coloured rounded square with a dark
 * glyph (in the hero, before the words).
 */
public final class KindBadge extends Drawable {

    public static final int ROUND = 0;
    public static final int SQUARE = 1;

    private final String kind;
    private final int look;
    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();

    public KindBadge(String kind, int look, float density) {
        this.kind = kind;
        this.look = look;
        this.d = density;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, 0.6f * density));
        edge.setColor(0x38FFFFFF);
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setStrokeJoin(Paint.Join.ROUND);
    }

    /** The colour of each kind. */
    public static int color(String kind) {
        switch (kind) {
            case HomeItem.ANDROID:
                return 0xFF3DDC84;
            case HomeItem.PC:
                return 0xFF60A5FA;
            case HomeItem.PC_LIBRARY_GAME:
            case HomeItem.PC_GAMES:
                return 0xFFFBBF24;
            case HomeItem.CLOUD:
                return 0xFFA78BFA;
            case HomeItem.RETRO:
                return 0xFFF472B6;
            case HomeItem.NATIVE:
                return 0xFF34D399;
            default:
                return 0xFFE5E7EB;
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return Math.round(16 * d);
    }

    @Override
    public int getIntrinsicHeight() {
        return Math.round(16 * d);
    }

    @Override
    public void draw(@NonNull Canvas c) {
        Rect b = getBounds();
        float w = b.width();
        float h = b.height();
        float s = Math.min(w, h);
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        int color = color(kind);
        if (look == ROUND) {
            fill.setColor(0xA6000000);
            c.drawCircle(cx, cy, s / 2f, fill);
            c.drawCircle(cx, cy, s / 2f - edge.getStrokeWidth() / 2f, edge);
            glyph.setColor(color);
        }
        else {
            fill.setColor(color);
            r.set(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f);
            c.drawRoundRect(r, s * 0.3f, s * 0.3f, fill);
            glyph.setColor(0xE6000000);
        }
        drawGlyph(c, cx, cy, s * 0.56f);
    }

    /** The glyph, in a box of side g centred on (cx, cy). */
    private void drawGlyph(Canvas c, float cx, float cy, float g) {
        float l = cx - g / 2f;
        float t = cy - g / 2f;
        float u = g / 24f; // one unit of a 24-unit icon grid
        switch (kind) {
            case HomeItem.ANDROID: {
                glyph.setStyle(Paint.Style.FILL);
                // The robot's head: a dome, two eyes cut out, two antennae
                r.set(l + 1 * u, t + 7 * u, l + 23 * u, t + 29 * u);
                path.reset();
                path.arcTo(r, 180, 180);
                path.close();
                c.drawPath(path, glyph);
                glyph.setStyle(Paint.Style.STROKE);
                glyph.setStrokeWidth(1.8f * u);
                c.drawLine(l + 6 * u, t + 4 * u, l + 8 * u, t + 8 * u, glyph);
                c.drawLine(l + 18 * u, t + 4 * u, l + 16 * u, t + 8 * u, glyph);
                int eye = look == ROUND ? 0xFF000000 : color(kind);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(eye);
                c.drawCircle(l + 7.5f * u, t + 13.5f * u, 1.5f * u, p);
                c.drawCircle(l + 16.5f * u, t + 13.5f * u, 1.5f * u, p);
                break;
            }
            case HomeItem.PC: {
                glyph.setStyle(Paint.Style.STROKE);
                glyph.setStrokeWidth(2.2f * u);
                r.set(l + 2 * u, t + 3.5f * u, l + 22 * u, t + 16.5f * u);
                c.drawRoundRect(r, 2.2f * u, 2.2f * u, glyph);
                c.drawLine(l + 8 * u, t + 21 * u, l + 16 * u, t + 21 * u, glyph);
                c.drawLine(l + 12 * u, t + 16.5f * u, l + 12 * u, t + 21 * u, glyph);
                break;
            }
            case HomeItem.PC_LIBRARY_GAME:
            case HomeItem.PC_GAMES: {
                // A library: four tiles
                glyph.setStyle(Paint.Style.FILL);
                float k = 9.5f * u;
                float gap = 3 * u;
                float x0 = cx - k - gap / 2f;
                float y0 = cy - k - gap / 2f;
                for (int i = 0; i < 4; i++) {
                    float x = x0 + (i % 2) * (k + gap);
                    float y = y0 + (i / 2) * (k + gap);
                    r.set(x, y, x + k, y + k);
                    c.drawRoundRect(r, 2.2f * u, 2.2f * u, glyph);
                }
                break;
            }
            case HomeItem.CLOUD: {
                glyph.setStyle(Paint.Style.FILL);
                c.drawCircle(l + 9 * u, t + 13 * u, 5.5f * u, glyph);
                c.drawCircle(l + 15 * u, t + 11 * u, 6.5f * u, glyph);
                r.set(l + 3 * u, t + 13 * u, l + 21.5f * u, t + 19.5f * u);
                c.drawRoundRect(r, 3.2f * u, 3.2f * u, glyph);
                break;
            }
            case HomeItem.RETRO: {
                // A pad: body, d-pad and two buttons
                glyph.setStyle(Paint.Style.FILL);
                r.set(l + 1 * u, t + 6 * u, l + 23 * u, t + 19 * u);
                c.drawRoundRect(r, 6.5f * u, 6.5f * u, glyph);
                Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
                cut.setColor(look == ROUND ? 0xFF000000 : color(kind));
                cut.setStrokeWidth(2f * u);
                cut.setStrokeCap(Paint.Cap.ROUND);
                c.drawLine(l + 5 * u, t + 12.5f * u, l + 10 * u, t + 12.5f * u, cut);
                c.drawLine(l + 7.5f * u, t + 10 * u, l + 7.5f * u, t + 15 * u, cut);
                c.drawCircle(l + 16 * u, t + 11 * u, 1.4f * u, cut);
                c.drawCircle(l + 18.5f * u, t + 14 * u, 1.4f * u, cut);
                break;
            }
            case HomeItem.NATIVE: {
                // A window (a PC game running on the handheld)
                glyph.setStyle(Paint.Style.STROKE);
                glyph.setStrokeWidth(2.2f * u);
                r.set(l + 2.5f * u, t + 4 * u, l + 21.5f * u, t + 20 * u);
                c.drawRoundRect(r, 2.5f * u, 2.5f * u, glyph);
                c.drawLine(l + 2.5f * u, t + 9 * u, l + 21.5f * u, t + 9 * u, glyph);
                break;
            }
            default: {
                // A shortcut: an arrow out to the corner
                glyph.setStyle(Paint.Style.STROKE);
                glyph.setStrokeWidth(2.4f * u);
                c.drawLine(l + 6 * u, t + 18 * u, l + 18 * u, t + 6 * u, glyph);
                c.drawLine(l + 9 * u, t + 6 * u, l + 18 * u, t + 6 * u, glyph);
                c.drawLine(l + 18 * u, t + 6 * u, l + 18 * u, t + 15 * u, glyph);
                break;
            }
        }
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        glyph.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
