package com.limelight.pocket.retro;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.limelight.pocket.ui.PocketFonts;
import com.limelight.pocket.ui.PocketTheme;

/**
 * The pop-up for an achievement just earned, the way the consoles do it: a round medal drops in
 * at the top of the screen, opens out into a capsule with the achievement's badge, its name and
 * what it's worth, a sweep of light runs across it, and after a few seconds it folds back into the
 * medal and goes. A mastered game gets a gold one. Drawn by itself (no layout), so it can live in
 * a window over another app's game as well as inside a screen.
 */
public final class TrophyToast extends View {

    /** What it shows. */
    public static final class Card {
        public String kicker;      // "ACHIEVEMENT UNLOCKED"
        public String title;       // the achievement
        public String line;        // "10 pts  ·  Ultra rare 2.1%  ·  13 / 40"
        public String points;      // "+10" (null: none)
        public Bitmap badge;       // its badge (null: the trophy)
        public boolean mastered;   // gold, for the whole game done
        public boolean hardcore;
    }

    public interface Done {
        void done();
    }

    private static final long IN_MS = 380;
    private static final long OPEN_MS = 520;
    private static final long HOLD_MS = 5200;
    private static final long CLOSE_MS = 420;
    private static final long OUT_MS = 320;
    private static final long TOTAL = IN_MS + OPEN_MS + HOLD_MS + CLOSE_MS + OUT_MS;

    private final float d;
    private final Card card;
    private final int accent;
    private final int gold = 0xFFFFC94D;
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint medal = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint medalRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint sweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint kickerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint linePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint pointsPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final RectF tmp = new RectF();
    private final Path trophy = new Path();
    private final Matrix badgeMatrix = new Matrix();
    private BitmapShader badgeShader;
    private float t; // ms into the timeline
    private ValueAnimator clock;

    /** The capsule's size when open, and the medal's (its height). */
    public final int openWidth;
    public final int height;
    /** Room around it for its glow and shadow. */
    public final int margin;

    public TrophyToast(Context context, Card card) {
        super(context);
        this.card = card;
        d = context.getResources().getDisplayMetrics().density;
        accent = card.mastered ? gold : PocketTheme.accent(context);
        height = Math.round(66 * d);
        margin = Math.round(22 * d);

        glass.setColor(0xF2121216);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, 1f * d));
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(6 * d);
        shadow.setColor(0x99000000);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            halo.setMaskFilter(new BlurMaskFilter(10 * d, BlurMaskFilter.Blur.NORMAL));
            shadow.setMaskFilter(new BlurMaskFilter(14 * d, BlurMaskFilter.Blur.NORMAL));
        }
        medalRim.setStyle(Paint.Style.STROKE);
        medalRim.setStrokeWidth(2f * d);
        // A white trophy on a dark or coloured medal, a dark one on a pale medal
        float lum = (0.2126f * ((accent >> 16) & 0xFF) + 0.7152f * ((accent >> 8) & 0xFF) + 0.0722f * (accent & 0xFF)) / 255f;
        glyph.setColor(lum > 0.72f ? 0xFF15151A : 0xFFFFFFFF);

        kickerPaint.setTypeface(PocketFonts.semibold());
        kickerPaint.setTextSize(9.5f * d);
        kickerPaint.setLetterSpacing(0.16f);
        kickerPaint.setColor(card.mastered ? gold : lighten(accent));
        titlePaint.setTypeface(PocketFonts.bold());
        titlePaint.setTextSize(15.5f * d);
        titlePaint.setLetterSpacing(-0.01f);
        titlePaint.setColor(0xFFFFFFFF);
        linePaint.setTypeface(PocketFonts.medium());
        linePaint.setTextSize(11f * d);
        linePaint.setColor(0xB8FFFFFF);
        pointsPaint.setTypeface(PocketFonts.bold());
        pointsPaint.setTextSize(13f * d);
        pointsPaint.setTextAlign(Paint.Align.CENTER);

        // As wide as its words need (within limits)
        float text = Math.max(titlePaint.measureText(nn(card.title)),
                Math.max(kickerPaint.measureText(nn(card.kicker)), linePaint.measureText(nn(card.line))));
        float pts = card.points != null ? pointsPaint.measureText(card.points) + 26 * d : 0;
        float w = height + 14 * d + text + 18 * d + pts + 10 * d;
        openWidth = Math.round(Math.max(300 * d, Math.min(470 * d, w)));

        if (card.badge != null) {
            badgeShader = new BitmapShader(card.badge, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            badgePaint.setShader(badgeShader);
        }
        buildTrophy();
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

    private static int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 0xFF) + 70);
        int g = Math.min(255, ((c >> 8) & 0xFF) + 70);
        int b = Math.min(255, (c & 0xFF) + 70);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** The window (or frame) it needs: the open capsule plus room for its glow. */
    public int frameWidth() {
        return openWidth + margin * 2;
    }

    public int frameHeight() {
        return height + margin * 2;
    }

    /** A trophy cup, drawn in a unit box (0..1), scaled when drawn. */
    private void buildTrophy() {
        trophy.reset();
        // Cup
        trophy.moveTo(0.22f, 0.12f);
        trophy.lineTo(0.78f, 0.12f);
        trophy.lineTo(0.76f, 0.40f);
        trophy.cubicTo(0.74f, 0.56f, 0.62f, 0.64f, 0.50f, 0.64f);
        trophy.cubicTo(0.38f, 0.64f, 0.26f, 0.56f, 0.24f, 0.40f);
        trophy.close();
        // Stem and base
        trophy.addRect(0.45f, 0.63f, 0.55f, 0.76f, Path.Direction.CW);
        trophy.addRoundRect(new RectF(0.32f, 0.76f, 0.68f, 0.88f), 0.03f, 0.03f, Path.Direction.CW);
    }

    public void play(final Done done) {
        clock = ValueAnimator.ofFloat(0f, TOTAL);
        clock.setDuration(TOTAL);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        clock.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (done != null) {
                    done.done();
                }
            }
        });
        clock.start();
    }

    public void cancel() {
        if (clock != null) {
            clock.cancel();
        }
    }

    // ---- Easing -----------------------------------------------------------------------------------

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float easeOut(float x) {
        x = clamp(x);
        return 1f - (1f - x) * (1f - x) * (1f - x);
    }

    private static float easeInOut(float x) {
        x = clamp(x);
        return x < 0.5f ? 4f * x * x * x : 1f - (float) Math.pow(-2f * x + 2f, 3) / 2f;
    }

    /** Overshoot (a little bounce past 1). */
    private static float back(float x) {
        x = clamp(x);
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        return 1f + c3 * (float) Math.pow(x - 1, 3) + c1 * (float) Math.pow(x - 1, 2);
    }

    // ---- Drawing ----------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        float h = height;
        float r = h / 2f;
        float cx = getWidth() / 2f;
        float top = margin;

        // Where we are in the story
        float in = clamp(t / IN_MS);
        float open = clamp((t - IN_MS) / OPEN_MS);
        float closeStart = IN_MS + OPEN_MS + HOLD_MS;
        float close = clamp((t - closeStart) / CLOSE_MS);
        float out = clamp((t - closeStart - CLOSE_MS) / OUT_MS);
        float widthF = easeInOut(open) * (1f - easeInOut(close));
        float scale = (t < IN_MS ? back(in) * 0.9f + 0.1f * in : 1f) * (1f - 0.35f * easeOut(out));
        float alpha = clamp(in * 1.6f) * (1f - out);
        if (alpha <= 0f) {
            return;
        }
        float w = h + (openWidth - h) * widthF;
        float left = cx - w / 2f;
        box.set(left, top, left + w, top + h);

        c.save();
        c.scale(scale, scale, cx, top + r);
        int a = Math.round(255 * alpha);

        // Shadow, then the glass, then a tint of the accent from the left
        tmp.set(box);
        tmp.offset(0, 6 * d);
        shadow.setAlpha(Math.round(0x99 * alpha));
        c.drawRoundRect(tmp, r, r, shadow);
        halo.setColor(accent);
        halo.setAlpha(Math.round(0x70 * alpha));
        c.drawRoundRect(box, r, r, halo);
        glass.setAlpha(Math.round(0xF2 * alpha));
        c.drawRoundRect(box, r, r, glass);
        tint.setShader(new LinearGradient(box.left, 0, box.left + Math.max(h, w * 0.7f), 0,
                withAlpha(accent, Math.round(0x40 * alpha)), withAlpha(accent, 0), Shader.TileMode.CLAMP));
        c.drawRoundRect(box, r, r, tint);
        rim.setShader(new LinearGradient(0, box.top, 0, box.bottom,
                withAlpha(0xFFFFFFFF, Math.round(0x4D * alpha)), withAlpha(0xFFFFFFFF, Math.round(0x14 * alpha)),
                Shader.TileMode.CLAMP));
        c.drawRoundRect(box, r, r, rim);

        // The medal at the left end (the whole thing while closed)
        float mcx = box.left + r;
        float mcy = box.top + r;
        float mr = r - 7 * d;
        float showBadge = card.badge != null ? easeOut((open - 0.35f) / 0.5f) * (1f - easeOut(close * 1.6f)) : 0f;
        medal.setShader(new RadialGradient(mcx - mr * 0.3f, mcy - mr * 0.4f, mr * 1.6f,
                new int[]{lighten(accent), accent, darken(accent)}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        medal.setAlpha(a);
        c.drawCircle(mcx, mcy, mr, medal);
        medalRim.setColor(withAlpha(0xFFFFFFFF, Math.round(0x8C * alpha)));
        c.drawCircle(mcx, mcy, mr, medalRim);
        // The trophy in it (rocks in as it lands)
        float wobble = t < IN_MS + 300 ? (float) Math.sin(t / 55f) * 10f * (1f - clamp(t / (IN_MS + 300))) : 0f;
        glyph.setAlpha(Math.round(255 * alpha * (1f - showBadge)));
        c.save();
        c.rotate(wobble, mcx, mcy);
        float gs = mr * 1.25f;
        c.translate(mcx - gs / 2f, mcy - gs / 2f + d);
        c.scale(gs, gs);
        c.drawPath(trophy, glyph);
        c.restore();
        // The achievement's own badge takes the medal's place once it's open
        if (showBadge > 0f && badgeShader != null) {
            float bs = mr * 2f;
            float sx = bs / card.badge.getWidth();
            float sy = bs / card.badge.getHeight();
            badgeMatrix.reset();
            badgeMatrix.setScale(sx, sy);
            badgeMatrix.postTranslate(mcx - mr, mcy - mr);
            badgeShader.setLocalMatrix(badgeMatrix);
            badgePaint.setAlpha(Math.round(255 * alpha * showBadge));
            tmp.set(mcx - mr, mcy - mr, mcx + mr, mcy + mr);
            float rr = mr * 0.32f + (mr - mr * 0.32f) * (1f - showBadge);
            c.drawRoundRect(tmp, rr, rr, badgePaint);
            medalRim.setColor(withAlpha(0xFFFFFFFF, Math.round(0x40 * alpha * showBadge)));
            c.drawRoundRect(tmp, rr, rr, medalRim);
        }

        // The words (they follow the capsule open, and leave first)
        float words = easeOut((open - 0.45f) / 0.55f) * (1f - easeOut(close * 1.8f));
        if (words > 0f) {
            c.save();
            c.clipRect(box.left + h * 0.8f, box.top, box.right - r * 0.4f, box.bottom);
            float tx = box.left + h + 6 * d + (1f - words) * 14 * d;
            float avail = openWidth - h - 30 * d - (card.points != null ? pointsPaint.measureText(card.points) + 26 * d : 0);
            int wa = Math.round(255 * alpha * words);
            kickerPaint.setAlpha(wa);
            titlePaint.setAlpha(wa);
            linePaint.setAlpha(Math.round(0xB8 * alpha * words));
            String kicker = nn(card.kicker);
            String title = TextUtils.ellipsize(nn(card.title), titlePaint, avail, TextUtils.TruncateAt.END).toString();
            String line = TextUtils.ellipsize(nn(card.line), linePaint, avail, TextUtils.TruncateAt.END).toString();
            float y = box.top + 20 * d;
            c.drawText(kicker, tx, y, kickerPaint);
            if (card.hardcore) {
                float kw = kickerPaint.measureText(kicker) + 8 * d;
                pill.setColor(withAlpha(0xFFFF6A5C, Math.round(0xE6 * alpha * words)));
                tmp.set(tx + kw, y - 9 * d, tx + kw + 14 * d, y + 2 * d);
                c.drawRoundRect(tmp, 3 * d, 3 * d, pill);
                Paint hc = new Paint(kickerPaint);
                hc.setColor(0xFFFFFFFF);
                hc.setAlpha(wa);
                hc.setLetterSpacing(0f);
                hc.setTextAlign(Paint.Align.CENTER);
                hc.setTextSize(8f * d);
                c.drawText("HC", tmp.centerX(), y - 0.6f * d, hc);
            }
            c.drawText(title, tx, y + 19 * d, titlePaint);
            c.drawText(line, tx, y + 36 * d, linePaint);
            // What it's worth, in a chip at the right end
            if (card.points != null) {
                float pw = pointsPaint.measureText(card.points) + 18 * d;
                float px = box.right - 14 * d - pw;
                tmp.set(px, box.centerY() - 13 * d, px + pw, box.centerY() + 13 * d);
                pill.setColor(withAlpha(card.mastered ? gold : 0xFFFFFFFF, Math.round(0x29 * alpha * words)));
                c.drawRoundRect(tmp, 13 * d, 13 * d, pill);
                pointsPaint.setColor(card.mastered ? gold : 0xFFFFFFFF);
                pointsPaint.setAlpha(wa);
                c.drawText(card.points, tmp.centerX(), tmp.centerY() + 4.5f * d, pointsPaint);
            }
            c.restore();
        }

        // A sweep of light across, once, as it finishes opening
        float s = clamp((t - IN_MS - OPEN_MS * 0.7f) / 900f);
        if (s > 0f && s < 1f) {
            float sx = box.left - 80 * d + (box.width() + 160 * d) * easeInOut(s);
            sweep.setShader(new LinearGradient(sx - 60 * d, 0, sx + 60 * d, 0,
                    new int[]{0x00FFFFFF, withAlpha(0xFFFFFFFF, Math.round(0x38 * alpha)), 0x00FFFFFF},
                    null, Shader.TileMode.CLAMP));
            c.save();
            c.clipRect(box);
            c.rotate(-14, sx, box.centerY());
            c.drawRect(sx - 60 * d, box.top - h, sx + 60 * d, box.bottom + h, sweep);
            c.restore();
        }
        c.restore();
    }

    private static int withAlpha(int color, int a) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24);
    }

    private static int darken(int c) {
        int r = Math.round(((c >> 16) & 0xFF) * 0.55f);
        int g = Math.round(((c >> 8) & 0xFF) * 0.55f);
        int b = Math.round((c & 0xFF) * 0.55f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
