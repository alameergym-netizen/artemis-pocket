package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.MaskFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.limelight.R;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.PocketSounds;

/**
 * The boot sequence (under four seconds, any button skips it), timed to the boot sound.
 * Out of the dark a soft light gathers; the mark is drawn by light, stroke by stroke (the left
 * pillar rises, the beam crosses, the right pillar rises), coming into focus as it is drawn. On
 * the chord it lands: a flash, a lens flare and a ring of light. The wordmark resolves letter by
 * letter out of a blur, a reflection glides across, and then the whole scene melts away (it blurs
 * and grows as it fades) while the home screen comes into focus underneath.
 */
public final class BootOverlay extends FrameLayout {

    public interface Listener {
        void onBootFinished();
    }

    // Timeline (seconds), matched to pocket_boot.ogg (swell, the chord at 1.25 s, pings, the
    // shimmer at 2.3 s, the exhale from 3 s)
    private static final float GLOW_END = 0.8f;
    private static final float LEFT_START = 0.30f;
    private static final float LEFT_END = 0.78f;
    private static final float BEAM_START = 0.70f;
    private static final float BEAM_END = 1.06f;
    private static final float RIGHT_START = 0.98f;
    private static final float CHORD = 1.25f;
    private static final float FOCUS_START = 0.25f;
    private static final float WORD_START = 1.35f;
    private static final float LINE_START = 2.2f;
    private static final float GLINT = 2.2f;
    private static final float EXIT_AT = 3.0f;
    private static final float EXIT_LEN = 0.75f;
    private static final long MAX_SOUND_WAIT_MS = 600;

    /** Live blur (Android 12+). Not on the emulator: its software GPU dies of full-screen blur. */
    private static final boolean LIVE_BLUR = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            && !com.limelight.pocket.PreviewSeed.isEmulator();

    private static final String WORD_1 = "POCKET";
    private static final String WORD_2 = "NEXUS";

    // The mark, in its 64-unit design: two pillars and the beam between them
    private static final float M_W = 9.5f;
    private static final float M_X0 = 11.5f;
    private static final float M_X1 = 52.5f;
    private static final float M_Y0 = 11f;
    private static final float M_Y1 = 53f;
    private static final float M_GAP = 1.8f;
    private static final float M_BEAM = 15f;
    private static final float M_R = 2.4f;

    private final float d;
    private final int accent;
    private final String tagline;
    private final Backdrop backdrop;
    private final Mark mark;
    private final Word word;
    private Listener listener;
    private Runnable onReveal;
    private View reveal;

    private final long createdAt;
    private long startNanos = -1;
    private float t;
    /** When the exit began (timeline seconds), or -1. */
    private float exitAt = -1;
    private float exitLen = EXIT_LEN;
    private boolean revealFired;
    private boolean finished;
    private boolean running;

    // Layout (overlay coordinates)
    private float markSize;
    private float unit;
    private float mcx;
    private float mcy;
    private float wordBase;

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) {
                return;
            }
            onFrame(frameTimeNanos);
            if (running) {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    public BootOverlay(Context context) {
        super(context);
        d = getResources().getDisplayMetrics().density;
        accent = PocketTheme.accent(context);
        tagline = context.getString(R.string.pocket_boot_tagline);
        setClickable(true);
        setOnClickListener(v -> skip());
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setClipChildren(false);

        backdrop = new Backdrop(context);
        mark = new Mark(context);
        word = new Word(context);
        LayoutParams full = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        addView(backdrop, full);
        addView(mark, new LayoutParams(full));
        addView(word, new LayoutParams(full));

        PocketSounds.preload(context);
        PocketSounds.prepareBoot(context);
        createdAt = SystemClock.uptimeMillis();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** The screen underneath; it comes into focus as the boot melts away. */
    public void setReveal(View view) {
        reveal = view;
    }

    /** Runs when the home screen starts to appear (so it can build itself as it is revealed). */
    public void setOnReveal(Runnable r) {
        onReveal = r;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        markSize = Math.max(64 * d, Math.min(160 * d, h * 0.24f));
        unit = markSize / (M_Y1 - M_Y0);
        float textSize = word.text.getTextSize();
        float block = markSize + 34 * d + textSize * 0.75f + 40 * d;
        float top = (h - block) / 2f;
        mcx = w / 2f;
        mcy = top + markSize / 2f;
        wordBase = top + markSize + 34 * d + textSize * 0.72f;
        mark.layout();
        backdrop.layout(w, h);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frame);
        if (!finished) {
            PocketSounds.releaseBoot();
            restoreReveal();
        }
        super.onDetachedFromWindow();
    }

    public boolean isFinished() {
        return finished;
    }

    /** Any button / tap: straight to the exit (quicker while the mark is still being drawn). */
    public void skip() {
        if (exitAt >= 0 || finished) {
            return;
        }
        PocketSounds.fadeOutBoot(getContext(), 320);
        if (startNanos >= 0 && t >= CHORD) {
            PocketSounds.play(getContext(), PocketSounds.BOOT_EXIT);
        }
        beginExit(startNanos < 0 || t < CHORD ? 0.5f : 0.62f);
    }

    private void onFrame(long now) {
        if (startNanos < 0) {
            if (exitAt >= 0) {
                // Skipped before it began: just the exit, silently
                startNanos = now;
            }
            else {
                // Wait (briefly) for the boot sound so picture and sound start together
                boolean ready = !PocketPrefs.uiSounds(getContext()) || PocketSounds.isLoaded(PocketSounds.BOOT)
                        || SystemClock.uptimeMillis() - createdAt > MAX_SOUND_WAIT_MS;
                if (!ready) {
                    return;
                }
                startNanos = now;
                PocketSounds.play(getContext(), PocketSounds.BOOT);
            }
        }
        t = (now - startNanos) / 1e9f;

        // The mark comes into focus as it is drawn, and lands on the chord with a small punch
        float focus = ease(seg(t, FOCUS_START, CHORD + 0.1f));
        float punch = (float) Math.sin(Math.PI * seg(t, CHORD, CHORD + 0.42f)) * 0.03f;
        float s = 0.9f + 0.1f * focus + punch;
        mark.setPivotX(mcx);
        mark.setPivotY(mcy);
        mark.setScaleX(s);
        mark.setScaleY(s);
        if (LIVE_BLUR) {
            float blur = 18 * d * (1 - focus);
            mark.setRenderEffect(blur > 0.5f ? RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL) : null);
        }

        if (exitAt < 0 && t >= EXIT_AT) {
            beginExit(1f);
        }
        if (exitAt >= 0) {
            float e = seg(t, exitAt, exitAt + exitLen);
            // The scene melts away: it blurs and grows as it fades...
            float gone = easeInOut(seg(e, 0.05f, 1f));
            setAlpha(1 - gone);
            float grow = 1 + 0.08f * ease(e);
            setPivotX(getWidth() / 2f);
            setPivotY(mcy);
            setScaleX(grow);
            setScaleY(grow);
            // ...while the home screen comes into focus underneath
            if (reveal != null) {
                float r = 1.04f - 0.04f * ease(e);
                reveal.setScaleX(r);
                reveal.setScaleY(r);
            }
            if (LIVE_BLUR) {
                float out = 26 * d * ease(e);
                setRenderEffect(out > 0.5f ? RenderEffect.createBlurEffect(out, out, Shader.TileMode.DECAL) : null);
                if (reveal != null) {
                    float in = 22 * d * (1 - ease(seg(e, 0.1f, 1f)));
                    reveal.setRenderEffect(in > 0.5f ? RenderEffect.createBlurEffect(in, in, Shader.TileMode.CLAMP) : null);
                }
            }
            if (!revealFired && e >= 0.08f) {
                revealFired = true;
                if (onReveal != null) {
                    onReveal.run();
                }
            }
            if (e >= 1f) {
                finish();
                return;
            }
        }
        backdrop.invalidate();
        mark.invalidate();
        word.invalidate();
    }

    private void beginExit(float speed) {
        if (startNanos < 0) {
            t = 0;
        }
        exitAt = t;
        exitLen = EXIT_LEN * speed;
        if (reveal != null) {
            reveal.animate().cancel();
            reveal.setAlpha(1f);
            reveal.setPivotX(reveal.getWidth() / 2f);
            reveal.setPivotY(reveal.getHeight() / 2f);
            reveal.setScaleX(1.04f);
            reveal.setScaleY(1.04f);
            if (LIVE_BLUR) {
                float in = 22 * d;
                reveal.setRenderEffect(RenderEffect.createBlurEffect(in, in, Shader.TileMode.CLAMP));
            }
        }
    }

    private void restoreReveal() {
        if (reveal != null) {
            reveal.animate().cancel();
            reveal.setAlpha(1f);
            reveal.setScaleX(1f);
            reveal.setScaleY(1f);
            if (LIVE_BLUR) {
                reveal.setRenderEffect(null);
            }
        }
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        running = false;
        Choreographer.getInstance().removeFrameCallback(frame);
        restoreReveal();
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) {
            parent.removeView(this);
        }
        if (listener != null) {
            listener.onBootFinished();
        }
    }

    // ---- Easing -------------------------------------------------------------------------

    private static float seg(float t, float a, float b) {
        return t <= a ? 0f : (t >= b ? 1f : (t - a) / (b - a));
    }

    private static float ease(float x) {
        float u = 1 - x;
        return 1 - u * u * u;
    }

    private static float easeInOut(float x) {
        return x < 0.5f ? 4 * x * x * x : 1 - (float) Math.pow(-2 * x + 2, 3) / 2;
    }

    private float mx(float x) {
        return mcx + (x - 32f) * unit;
    }

    private float my(float y) {
        return mcy + (y - 32f) * unit;
    }

    private int tint(int alpha) {
        return PocketTheme.withAlpha(accent, Math.max(0, Math.min(255, alpha)));
    }

    // ---- The light behind the mark ------------------------------------------------------

    private final class Backdrop extends View {
        private final Paint ambient = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bloom = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint flare = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float ambientR;
        private float bloomR;

        Backdrop(Context context) {
            super(context);
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(0xFFFFFFFF);
        }

        void layout(int w, int h) {
            ambientR = Math.max(w, h) * 0.75f + 1;
            bloomR = markSize * 2.4f + 1;
            ambient.setShader(new RadialGradient(mcx, mcy, ambientR,
                    new int[]{tint(0x1C), tint(0x0A), tint(0)}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            bloom.setShader(new RadialGradient(mcx, mcy, bloomR,
                    new int[]{tint(0x66), tint(0x22), tint(0)}, new float[]{0f, 0.38f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(0xFF000000);
            if (startNanos < 0 || bloomR <= 1) {
                return;
            }
            float time = t;
            // 1. Light gathers out of the dark
            float gather = ease(seg(time, 0f, GLOW_END));
            ambient.setAlpha(Math.round(255 * gather));
            canvas.drawCircle(mcx, mcy, ambientR, ambient);

            // 2. The bloom swells with the sound, peaks on the chord, then breathes
            float swell = 0.35f * ease(seg(time, 0.1f, CHORD));
            float peak = 0.65f * (float) Math.exp(-Math.max(0f, time - CHORD) * 3.2f) * (time >= CHORD ? 1f : 0f);
            float rest = time > CHORD ? 0.06f * (float) Math.sin((time - CHORD) * 2.6f) * seg(time, CHORD + 0.5f, CHORD + 1.2f) : 0f;
            float b = Math.max(0f, Math.min(1f, swell + peak + rest));
            bloom.setAlpha(Math.round(255 * b));
            float br = bloomR * (0.85f + 0.15f * ease(seg(time, CHORD, CHORD + 0.8f)));
            canvas.save();
            canvas.scale(br / bloomR, br / bloomR, mcx, mcy);
            canvas.drawCircle(mcx, mcy, bloomR, bloom);
            canvas.restore();

            // 3. On the chord: an anamorphic flare through the mark...
            float f = seg(time, CHORD, CHORD + 0.75f);
            if (f > 0 && f < 1) {
                float k = (1 - f) * (1 - f);
                float half = getWidth() * (0.18f + 0.34f * ease(f));
                flare.setShader(new LinearGradient(mcx - half, 0, mcx + half, 0,
                        new int[]{0x00FFFFFF, tint(Math.round(60 * k)), 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
                canvas.drawRect(mcx - half, mcy - 7 * d, mcx + half, mcy + 7 * d, flare);
                flare.setShader(new LinearGradient(mcx - half, 0, mcx + half, 0,
                        new int[]{0x00FFFFFF, android.graphics.Color.argb(Math.round(230 * k), 255, 255, 255), 0x00FFFFFF},
                        null, Shader.TileMode.CLAMP));
                canvas.drawRect(mcx - half, mcy - 0.7f * d, mcx + half, mcy + 0.7f * d, flare);
                flare.setShader(null);
            }

            // ...and a ring of light that runs outwards
            float rg = seg(time, CHORD, CHORD + 0.8f);
            if (rg > 0 && rg < 1) {
                float sc = 1.1f + 0.42f * ease(rg);
                float hw = (M_X1 - M_X0) * unit / 2f * sc + 10 * d;
                float hh = (M_Y1 - M_Y0) * unit / 2f * sc + 10 * d;
                box.set(mcx - hw, mcy - hh, mcx + hw, mcy + hh);
                ring.setStrokeWidth((2.4f - 1.8f * rg) * d);
                ring.setAlpha(Math.round(130 * (1 - rg) * (1 - rg)));
                float r = Math.min(hw, hh) * 0.46f;
                canvas.drawRoundRect(box, r, r, ring);
            }
        }
    }

    // ---- The mark, drawn by light -------------------------------------------------------

    private final class Mark extends View {
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint beam = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tip = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path left = new Path();
        private final Path right = new Path();
        private final Path beamPath = new Path();
        private final Path all = new Path();
        private final RectF r = new RectF();

        Mark(Context context) {
            super(context);
        }

        void layout() {
            float rr = M_R * unit;
            left.reset();
            r.set(mx(M_X0), my(M_Y0), mx(M_X0 + M_W), my(M_Y1));
            left.addRoundRect(r, rr, rr, Path.Direction.CW);
            right.reset();
            r.set(mx(M_X1 - M_W), my(M_Y0), mx(M_X1), my(M_Y1));
            right.addRoundRect(r, rr, rr, Path.Direction.CW);
            float bx0 = M_X0 + M_W + M_GAP;
            float bx1 = M_X1 - M_W - M_GAP;
            beamPath.reset();
            beamPath.moveTo(mx(bx0), my(M_Y0));
            beamPath.lineTo(mx(bx1), my(M_Y1 - M_BEAM));
            beamPath.lineTo(mx(bx1), my(M_Y1));
            beamPath.lineTo(mx(bx0), my(M_Y0 + M_BEAM));
            beamPath.close();
            all.reset();
            all.addPath(left);
            all.addPath(right);
            all.addPath(beamPath);
            // Pillars: a touch of depth, lit from above
            body.setShader(new LinearGradient(0, my(M_Y0), 0, my(M_Y1),
                    0xFFFFFFFF, 0xFFD6D6DC, Shader.TileMode.CLAMP));
            // The beam fades as it crosses, like the logo
            beam.setShader(new LinearGradient(mx(bx0), my(M_Y0), mx(bx1), my(M_Y1),
                    0xFFFFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (startNanos < 0 || unit <= 0) {
                return;
            }
            float time = t;
            float top = my(M_Y0);
            float bottom = my(M_Y1);
            float h = bottom - top;

            // Left pillar rises
            float lp = easeInOut(seg(time, LEFT_START, LEFT_END));
            if (lp > 0) {
                float edge = bottom - h * lp;
                canvas.save();
                canvas.clipRect(mx(M_X0) - 2, edge, mx(M_X0 + M_W) + 2, bottom + 2);
                canvas.drawPath(left, body);
                canvas.restore();
                drawTip(canvas, mx(M_X0 + M_W / 2f), edge, 1 - seg(time, LEFT_END - 0.05f, LEFT_END + 0.15f));
            }
            // The beam crosses
            float bx0 = mx(M_X0 + M_W + M_GAP);
            float bx1 = mx(M_X1 - M_W - M_GAP);
            float bp = easeInOut(seg(time, BEAM_START, BEAM_END));
            if (bp > 0) {
                float edge = bx0 + (bx1 - bx0) * bp;
                canvas.save();
                canvas.clipRect(bx0 - 2, top - 2, edge, bottom + 2);
                canvas.drawPath(beamPath, beam);
                canvas.restore();
                float cy = top + (M_BEAM * unit) / 2f + (h - M_BEAM * unit) * bp;
                drawTip(canvas, edge, cy, 1 - seg(time, BEAM_END - 0.05f, BEAM_END + 0.15f));
            }
            // Right pillar rises, landing on the chord
            float rp = easeInOut(seg(time, RIGHT_START, CHORD));
            if (rp > 0) {
                float edge = bottom - h * rp;
                canvas.save();
                canvas.clipRect(mx(M_X1 - M_W) - 2, edge, mx(M_X1) + 2, bottom + 2);
                canvas.drawPath(right, body);
                canvas.restore();
                drawTip(canvas, mx(M_X1 - M_W / 2f), edge, 1 - seg(time, CHORD - 0.05f, CHORD + 0.2f));
            }
            // A white flash over the whole mark on the chord
            float flash = time >= CHORD ? (float) Math.exp(-(time - CHORD) * 7f) : 0f;
            if (flash > 0.01f) {
                glint.setShader(null);
                glint.setColor(android.graphics.Color.argb(Math.round(255 * flash), 255, 255, 255));
                canvas.drawPath(all, glint);
            }
            // A reflection glides across
            float g = seg(time, GLINT, GLINT + 0.6f);
            if (g > 0 && g < 1) {
                float span = (M_X1 - M_X0) * unit;
                float band = span * 0.45f;
                float x = mx(M_X0) - band + (span + 2 * band) * easeInOut(g);
                canvas.save();
                canvas.clipPath(all);
                glint.setShader(new LinearGradient(x - band, top, x + band, bottom,
                        new int[]{0x00FFFFFF, 0xE6FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
                canvas.drawRect(mx(M_X0) - 4, top - 4, mx(M_X1) + 4, bottom + 4, glint);
                glint.setShader(null);
                canvas.restore();
            }
        }

        /** The bright point that draws each stroke. */
        private void drawTip(Canvas canvas, float x, float y, float alpha) {
            if (alpha <= 0.01f) {
                return;
            }
            float radius = 16 * d;
            tip.setShader(new RadialGradient(x, y, radius,
                    new int[]{0xFFFFFFFF, tint(0x88), tint(0)}, new float[]{0f, 0.22f, 1f}, Shader.TileMode.CLAMP));
            tip.setAlpha(Math.round(255 * alpha));
            canvas.drawCircle(x, y, radius, tip);
        }
    }

    // ---- The wordmark, resolving out of a blur ------------------------------------------

    private final class Word extends View {
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final MaskFilter[] blurs = new MaskFilter[12];

        Word(Context context) {
            super(context);
            float sp = getResources().getDisplayMetrics().scaledDensity;
            text.setColor(0xFFFFFFFF);
            text.setTextSize(19 * sp);
            text.setTypeface(PocketFonts.semibold());
            small.setColor(0xFFFFFFFF);
            small.setTextSize(10.5f * sp);
            small.setLetterSpacing(0.24f);
            small.setTextAlign(Paint.Align.CENTER);
            small.setTypeface(PocketFonts.medium());
            line.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 1; i < blurs.length; i++) {
                blurs[i] = new BlurMaskFilter(i * 1.2f * d, BlurMaskFilter.Blur.NORMAL);
            }
        }

        private MaskFilter blur(float amount) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                return null;
            }
            int i = Math.round(amount * (blurs.length - 1));
            return i <= 0 ? null : blurs[Math.min(i, blurs.length - 1)];
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float time = t;
            if (startNanos < 0 || time < WORD_START) {
                return;
            }
            String full = WORD_1 + " " + WORD_2;
            float size = text.getTextSize();
            // Letters arrive spaced out and settle closer together
            float spacing = size * (0.62f - 0.24f * ease(seg(time, WORD_START, WORD_START + 1.2f)));
            float total = 0;
            for (int i = 0; i < full.length(); i++) {
                total += text.measureText(full, i, i + 1);
                if (i < full.length() - 1) {
                    total += spacing;
                }
            }
            float x = mcx - total / 2f;
            float glintPos = seg(time, GLINT + 0.15f, GLINT + 0.8f);
            for (int i = 0; i < full.length(); i++) {
                float w = text.measureText(full, i, i + 1);
                if (full.charAt(i) != ' ') {
                    float local = seg(time, WORD_START + i * 0.045f, WORD_START + i * 0.045f + 0.55f);
                    if (local > 0) {
                        boolean second = i > WORD_1.length();
                        float a = ease(local) * (second ? 0.6f : 1f);
                        // The reflection passes over the letters too
                        if (glintPos > 0 && glintPos < 1) {
                            float at = (x + w / 2f - (mcx - total / 2f)) / total;
                            float k = Math.max(0f, 1 - Math.abs(at - glintPos) * 5f);
                            a = Math.min(1f, a + 0.4f * k);
                        }
                        text.setMaskFilter(blur(1 - ease(local)));
                        text.setAlpha(Math.round(255 * a));
                        canvas.drawText(full, i, i + 1, x, wordBase, text);
                    }
                }
                x += w + spacing;
            }
            text.setMaskFilter(null);

            // A thin line of light and the tagline
            float grow = ease(seg(time, LINE_START, LINE_START + 0.55f));
            if (grow > 0) {
                float half = 46 * d * grow;
                float y = wordBase + 17 * d;
                line.setStrokeWidth(1.2f * d);
                line.setShader(new LinearGradient(mcx - half, y, mcx + half, y,
                        new int[]{0x00FFFFFF, tint(200), 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
                canvas.drawLine(mcx - half, y, mcx + half, y, line);
            }
            float tag = ease(seg(time, LINE_START + 0.1f, LINE_START + 0.7f));
            if (tag > 0) {
                small.setMaskFilter(blur(1 - tag));
                small.setAlpha(Math.round(150 * tag));
                canvas.drawText(tagline, mcx, wordBase + 38 * d, small);
                small.setMaskFilter(null);
            }
        }
    }
}
