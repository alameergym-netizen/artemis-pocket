package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.limelight.R;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.PocketSounds;

/**
 * The boot sequence (about six seconds, any button skips it), timed to the boot sound:
 * a spark of light appears, splits into two beams that trace the handheld's outline, the body
 * comes into focus as its screen lights up with a shock ring, the wordmark rises letter by
 * letter, a reflection glides across the screen, and then the camera flies into the handheld's
 * screen and lands on the home screen.
 */
public final class BootOverlay extends FrameLayout {

    public interface Listener {
        void onBootFinished();
    }

    // Timeline (seconds), matched to pocket_boot.wav
    private static final float SPARK = 0.35f;
    private static final float TRACE_START = 1.05f;
    private static final float TRACE_END = 2.45f;
    private static final float LIGHT_UP = 2.45f;
    private static final float WORD_START = 2.75f;
    private static final float LINE_START = 3.55f;
    private static final float GLINT = 3.95f;
    private static final float EXIT_AT = 5.25f;
    private static final float ZOOM_SECONDS = 1.0f;
    private static final long MAX_SOUND_WAIT_MS = 600;

    private static final String WORD_1 = "ARTEMIS";
    private static final String WORD_2 = "POCKET";

    private final float d;
    private final int accent;
    private final int logoW;
    private final int logoH;
    private final float logoLift;
    private final Stage stage;
    private final ImageView logo;
    private final String tagline;
    private final Spring logoScale = new Spring(150f, 0.55f, 0.88f);
    private Listener listener;
    private View reveal;

    private final long createdAt;
    private long startNanos = -1;
    private long lastFrameNanos;
    private float t;
    /** When the exit began (timeline seconds), or -1. */
    private float exitAt = -1;
    private boolean zoomExit;
    private float zoomSeconds = ZOOM_SECONDS;
    private boolean finished;
    private boolean running;

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
        int screenW = getResources().getDisplayMetrics().widthPixels;
        logoW = Math.round(Math.min(200 * d, screenW * 0.27f));
        logoH = Math.round(logoW * 30f / 64f);
        logoLift = 34 * d;
        tagline = context.getString(R.string.pocket_boot_tagline);
        setClickable(true);
        setOnClickListener(v -> skip());
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        stage = new Stage(context);
        addView(stage, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        logo = new ImageView(context);
        logo.setImageResource(R.drawable.ic_pocket_logo);
        logo.setColorFilter(accent);
        logo.setScaleType(ImageView.ScaleType.FIT_XY);
        logo.setAlpha(0f);
        addView(logo, new LayoutParams(logoW, logoH, Gravity.CENTER));
        logo.setTranslationY(-logoLift);

        PocketSounds.preload(context);
        PocketSounds.prepareBoot(context);
        createdAt = SystemClock.uptimeMillis();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** The screen underneath; it is revealed through the handheld's screen. */
    public void setReveal(View view) {
        reveal = view;
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

    /** Any button / tap: go straight to the exit. */
    public void skip() {
        if (exitAt >= 0 || finished) {
            return;
        }
        PocketSounds.fadeOutBoot(getContext(), 300);
        if (startNanos < 0 || t < LIGHT_UP + 0.2f) {
            // Nothing lit yet to fly into: just fade away
            fadeExit();
        }
        else {
            zoomSeconds = 0.75f;
            beginZoom();
        }
    }

    private void onFrame(long now) {
        if (startNanos < 0) {
            // Wait (briefly) for the boot sound so picture and sound start together
            boolean ready = !PocketPrefs.uiSounds(getContext()) || PocketSounds.isLoaded(PocketSounds.BOOT)
                    || SystemClock.uptimeMillis() - createdAt > MAX_SOUND_WAIT_MS;
            if (!ready) {
                return;
            }
            startNanos = now;
            lastFrameNanos = now;
            PocketSounds.play(getContext(), PocketSounds.BOOT);
        }
        float dt = Math.min(0.05f, (now - lastFrameNanos) / 1e9f);
        lastFrameNanos = now;
        t = (now - startNanos) / 1e9f;

        // The body comes into focus as the outline completes
        if (t >= LIGHT_UP - 0.05f && logoScale.target != 1f) {
            logoScale.target = 1f;
        }
        logoScale.step(dt);
        float body = ease(seg(t, LIGHT_UP - 0.05f, LIGHT_UP + 0.42f));
        logo.setAlpha(body);
        logo.setScaleX(logoScale.value);
        logo.setScaleY(logoScale.value);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            float blur = 22 * d * (1 - ease(seg(t, LIGHT_UP - 0.05f, LIGHT_UP + 0.55f)));
            logo.setRenderEffect(blur > 0.6f && body > 0 ? RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL) : null);
        }

        if (exitAt < 0 && t >= EXIT_AT) {
            beginZoom();
        }
        if (zoomExit) {
            float z = seg(t, exitAt + 0.15f, exitAt + 0.15f + zoomSeconds);
            float k = zoomCurve(z);
            float max = maxZoom();
            float s = 1 + (max - 1) * k;
            setScaleX(s);
            setScaleY(s);
            if (reveal != null) {
                float r = 0.9f + 0.1f * ease(seg(t, exitAt + 0.1f, exitAt + 0.25f + zoomSeconds));
                reveal.setScaleX(r);
                reveal.setScaleY(r);
            }
            if (z >= 1f) {
                finish();
                return;
            }
        }
        stage.invalidate();
    }

    /** How far the view must grow for the handheld's screen to cover the display. */
    private float maxZoom() {
        float sw = stage.screen.width();
        float sh = stage.screen.height();
        if (sw <= 0 || sh <= 0) {
            return 12f;
        }
        float px = stage.screen.centerX();
        float py = stage.screen.centerY();
        float needX = 2 * Math.max(px, getWidth() - px) / sw;
        float needY = 2 * Math.max(py, getHeight() - py) / sh;
        return Math.max(needX, needY) * 1.08f;
    }

    private static float zoomCurve(float x) {
        // Slow push in, then a fast dive through the glass
        return x * x * x * (x * (6 * x - 15) + 10) * 0.35f + 0.65f * x * x * x;
    }

    private void beginZoom() {
        exitAt = t;
        zoomExit = true;
        setPivotX(stage.screen.centerX());
        setPivotY(stage.screen.centerY());
        PocketSounds.play(getContext(), PocketSounds.BOOT_EXIT);
        if (reveal != null) {
            reveal.animate().cancel();
            reveal.setPivotX(reveal.getWidth() / 2f);
            reveal.setPivotY(reveal.getHeight() / 2f);
            reveal.setAlpha(1f);
            reveal.setScaleX(0.9f);
            reveal.setScaleY(0.9f);
        }
    }

    private void fadeExit() {
        exitAt = t;
        animate().cancel();
        animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setDuration(380)
                .setInterpolator(PocketUi.EASE)
                .withEndAction(this::finish).start();
        if (reveal != null) {
            reveal.setPivotX(reveal.getWidth() / 2f);
            reveal.setPivotY(reveal.getHeight() / 2f);
            reveal.setScaleX(0.95f);
            reveal.setScaleY(0.95f);
            reveal.setAlpha(0f);
            reveal.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(560)
                    .setInterpolator(Spring.SOFT).start();
        }
    }

    private void restoreReveal() {
        if (reveal != null) {
            reveal.animate().cancel();
            reveal.setAlpha(1f);
            reveal.setScaleX(1f);
            reveal.setScaleY(1f);
        }
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        running = false;
        Choreographer.getInstance().removeFrameCallback(frame);
        if (zoomExit) {
            restoreReveal();
        }
        else if (reveal != null && reveal.getAlpha() < 1f) {
            reveal.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start();
        }
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

    // ---- The drawn scene ----------------------------------------------------------------

    private final class Stage extends View {
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path right = new Path();
        private final Path left = new Path();
        private final Path piece = new Path();
        private final Path screenPath = new Path();
        private final RectF body = new RectF();
        final RectF screen = new RectF();
        private final RectF tmp = new RectF();
        private final float[] pos = new float[2];
        private PathMeasure rightMeasure;
        private PathMeasure leftMeasure;
        private float cx;
        private float cy;
        private float unit;
        private float wordY;

        Stage(Context context) {
            super(context);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(accent);
            fill.setStyle(Paint.Style.FILL);
            text.setColor(0xFFFFFFFF);
            text.setTextSize(16 * getResources().getDisplayMetrics().scaledDensity);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            small.setColor(0xFFFFFFFF);
            small.setTextSize(10.5f * getResources().getDisplayMetrics().scaledDensity);
            small.setLetterSpacing(0.22f);
            small.setTextAlign(Paint.Align.CENTER);
            line.setStrokeWidth(1.6f * d);
            line.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            cx = w / 2f;
            cy = h / 2f - logoLift;
            unit = logoW / 64f;
            body.set(cx - logoW / 2f, cy - logoH / 2f, cx + logoW / 2f, cy + logoH / 2f);
            screen.set(body.left + 16 * unit, body.top + 6 * unit, body.left + 48 * unit, body.top + 24 * unit);
            screenPath.reset();
            screenPath.addRoundRect(screen, 2.2f * unit, 2.2f * unit, Path.Direction.CW);
            float r = 12 * unit;

            // Two halves of the outline, both starting at the top centre
            right.reset();
            right.moveTo(cx, body.top);
            right.lineTo(body.right - r, body.top);
            tmp.set(body.right - 2 * r, body.top, body.right, body.top + 2 * r);
            right.arcTo(tmp, -90, 90);
            right.lineTo(body.right, body.bottom - r);
            tmp.set(body.right - 2 * r, body.bottom - 2 * r, body.right, body.bottom);
            right.arcTo(tmp, 0, 90);
            right.lineTo(cx, body.bottom);
            left.reset();
            left.moveTo(cx, body.top);
            left.lineTo(body.left + r, body.top);
            tmp.set(body.left, body.top, body.left + 2 * r, body.top + 2 * r);
            left.arcTo(tmp, -90, -90);
            left.lineTo(body.left, body.bottom - r);
            tmp.set(body.left, body.bottom - 2 * r, body.left + 2 * r, body.bottom);
            left.arcTo(tmp, 180, -90);
            left.lineTo(cx, body.bottom);
            rightMeasure = new PathMeasure(right, false);
            leftMeasure = new PathMeasure(left, false);

            glow.setShader(new RadialGradient(cx, cy, Math.max(logoW * 1.7f, 1f),
                    new int[]{accent, PocketTheme.withAlpha(accent, 0x55), PocketTheme.withAlpha(accent, 0)},
                    new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
            wordY = body.bottom + 34 * d + logoLift * 0.5f;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (rightMeasure == null) {
                canvas.drawColor(0xFF000000);
                return;
            }
            float time = t;
            boolean zooming = zoomExit;
            // During the dive the handheld's screen is a window onto the home screen
            int save = canvas.save();
            if (zooming) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    canvas.clipOutPath(screenPath);
                }
                else {
                    //noinspection deprecation
                    canvas.clipPath(screenPath, android.graphics.Region.Op.DIFFERENCE);
                }
            }
            canvas.drawColor(0xFF000000);
            if (startNanos < 0) {
                canvas.restoreToCount(save);
                return;
            }
            float out = exitAt >= 0 ? ease(seg(time, exitAt, exitAt + 0.3f)) : 0f;

            // 1. The bloom of light behind the device (it breathes gently once lit)
            float breathe = time > LIGHT_UP + 0.6f ? 0.04f * (float) Math.sin((time - LIGHT_UP) * 2.4f) : 0f;
            float bloom = 0.10f * ease(seg(time, SPARK, 1.4f))
                    + 0.24f * ease(seg(time, LIGHT_UP, LIGHT_UP + 0.25f)) * (1 - 0.55f * ease(seg(time, LIGHT_UP + 0.25f, 3.6f)))
                    + breathe * ease(seg(time, LIGHT_UP, 3.6f));
            bloom *= 1 - out;
            glow.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, bloom))));
            canvas.drawCircle(cx, cy, logoW * 1.7f, glow);

            // 2. The spark at the top centre, with a thin horizontal flare
            float spark = ease(seg(time, SPARK, SPARK + 0.45f)) * (1 - ease(seg(time, TRACE_START, TRACE_START + 0.5f)));
            if (spark > 0) {
                float sx = cx;
                float sy = body.top;
                float flare = logoW * 0.75f * ease(seg(time, SPARK, SPARK + 0.7f));
                line.setShader(new LinearGradient(sx - flare, sy, sx + flare, sy,
                        new int[]{PocketTheme.withAlpha(accent, 0), PocketTheme.withAlpha(accent, Math.round(170 * spark)),
                                PocketTheme.withAlpha(accent, 0)}, null, Shader.TileMode.CLAMP));
                line.setStrokeWidth(1.2f * d);
                canvas.drawLine(sx - flare, sy, sx + flare, sy, line);
                float radius = (6 + 10 * spark) * d;
                head.setShader(new RadialGradient(sx, sy, radius,
                        new int[]{0xFFFFFFFF, PocketTheme.withAlpha(accent, 0x80), PocketTheme.withAlpha(accent, 0)},
                        new float[]{0f, 0.25f, 1f}, Shader.TileMode.CLAMP));
                head.setAlpha(Math.round(255 * spark));
                canvas.drawCircle(sx, sy, radius, head);
            }

            // 3. Shock ring when the screen lights up
            float shock = seg(time, LIGHT_UP, LIGHT_UP + 0.9f);
            if (shock > 0 && shock < 1) {
                float s = 1 + 0.45f * ease(shock);
                stroke.setStrokeWidth((2.4f - 1.8f * shock) * d);
                stroke.setAlpha(Math.round(150 * (1 - shock) * (1 - shock)));
                tmp.set(cx - body.width() / 2f * s, cy - body.height() / 2f * s,
                        cx + body.width() / 2f * s, cy + body.height() / 2f * s);
                float r = 12 * unit * s;
                canvas.drawRoundRect(tmp, r, r, stroke);
            }
            canvas.restoreToCount(save);

            // 4. The lit screen, seen through the body's screen cut-out
            float on = ease(seg(time, LIGHT_UP, LIGHT_UP + 0.18f));
            float open = zooming ? ease(seg(time, exitAt, exitAt + 0.22f)) : 0f;
            if (on > 0 && open < 1) {
                float level = on * (1 - 0.5f * ease(seg(time, LIGHT_UP + 0.18f, LIGHT_UP + 0.9f))) * (1 - open);
                canvas.save();
                canvas.scale(logoScale.value, logoScale.value, cx, cy);
                fill.setShader(new LinearGradient(screen.left, screen.top, screen.right, screen.bottom,
                        PocketTheme.withAlpha(accent, Math.round(255 * level)),
                        PocketTheme.withAlpha(accent, Math.round(110 * level)), Shader.TileMode.CLAMP));
                canvas.drawRoundRect(screen, 2.2f * unit, 2.2f * unit, fill);
                fill.setShader(null);
                // A white flash on the glass as it turns on
                float flash = on * (1 - seg(time, LIGHT_UP + 0.05f, LIGHT_UP + 0.5f));
                if (flash > 0) {
                    fill.setColor(Color.argb(Math.round(200 * flash), 255, 255, 255));
                    canvas.drawRoundRect(screen, 2.2f * unit, 2.2f * unit, fill);
                }
                // 5. A reflection gliding across the glass
                float glint = seg(time, GLINT, GLINT + 0.75f);
                if (glint > 0 && glint < 1) {
                    canvas.save();
                    canvas.clipPath(screenPath);
                    float band = screen.width() * 0.35f;
                    float x = screen.left - band + (screen.width() + 2 * band) * easeInOut(glint);
                    fill.setShader(new LinearGradient(x - band, screen.top, x + band, screen.bottom,
                            new int[]{0x00FFFFFF, Color.argb(Math.round(130 * (1 - open)), 255, 255, 255), 0x00FFFFFF},
                            null, Shader.TileMode.CLAMP));
                    canvas.drawRect(screen, fill);
                    fill.setShader(null);
                    canvas.restore();
                }
                canvas.restore();
            }

            // 6. The two beams tracing the outline
            float trace = easeInOut(seg(time, TRACE_START, TRACE_END));
            float traceFade = 1 - ease(seg(time, LIGHT_UP, LIGHT_UP + 0.45f));
            if (trace > 0 && traceFade > 0) {
                drawBeam(canvas, rightMeasure, trace, traceFade);
                drawBeam(canvas, leftMeasure, trace, traceFade);
            }

            // 7. Wordmark, letter by letter, then the light line and tagline
            if (out < 1) {
                drawWordmark(canvas, time, 1 - out);
                float grow = ease(seg(time, LINE_START, LINE_START + 0.55f));
                if (grow > 0) {
                    float half = 40 * d * grow;
                    float y = wordY + 16 * d;
                    line.setStrokeWidth(1.6f * d);
                    line.setShader(new LinearGradient(cx - half, y, cx + half, y,
                            new int[]{PocketTheme.withAlpha(accent, 0), PocketTheme.withAlpha(accent, Math.round(255 * (1 - out))),
                                    PocketTheme.withAlpha(accent, 0)}, null, Shader.TileMode.CLAMP));
                    canvas.drawLine(cx - half, y, cx + half, y, line);
                }
                float tag = ease(seg(time, LINE_START + 0.2f, LINE_START + 0.75f)) * (1 - out);
                if (tag > 0) {
                    small.setAlpha(Math.round(150 * tag));
                    canvas.drawText(tagline, cx, wordY + 38 * d - 4 * d * (1 - tag), small);
                }
            }
        }

        private void drawBeam(Canvas canvas, PathMeasure measure, float progress, float fade) {
            float length = measure.getLength();
            float headAt = length * progress;
            piece.reset();
            measure.getSegment(0, headAt, piece, true);
            stroke.setStrokeWidth(1.4f * d);
            stroke.setAlpha(Math.round(70 * fade));
            canvas.drawPath(piece, stroke);
            float tail = Math.min(headAt, length * 0.38f);
            int steps = 18;
            stroke.setStrokeWidth(2.2f * d);
            for (int i = 0; i < steps; i++) {
                float a = headAt - tail + tail * i / steps;
                float b = headAt - tail + tail * (i + 1) / steps;
                float k = (i + 1f) / steps;
                piece.reset();
                measure.getSegment(a, b, piece, true);
                stroke.setAlpha(Math.round(255 * fade * k * k));
                canvas.drawPath(piece, stroke);
            }
            float headAlpha = fade * (1 - seg(progress, 0.92f, 1f));
            if (headAlpha > 0) {
                measure.getPosTan(headAt, pos, null);
                float radius = 9 * d;
                head.setShader(new RadialGradient(pos[0], pos[1], radius,
                        new int[]{0xFFFFFFFF, PocketTheme.withAlpha(accent, 0x99), PocketTheme.withAlpha(accent, 0)},
                        new float[]{0f, 0.3f, 1f}, Shader.TileMode.CLAMP));
                head.setAlpha(Math.round(255 * headAlpha));
                canvas.drawCircle(pos[0], pos[1], radius, head);
            }
        }

        private void drawWordmark(Canvas canvas, float time, float visible) {
            if (time < WORD_START) {
                return;
            }
            String full = WORD_1 + " " + WORD_2;
            float spacingEm = 0.62f - 0.28f * ease(seg(time, WORD_START, WORD_START + 1.1f));
            float spacing = spacingEm * text.getTextSize();
            float total = 0;
            for (int i = 0; i < full.length(); i++) {
                total += text.measureText(full, i, i + 1);
                if (i < full.length() - 1) {
                    total += spacing;
                }
            }
            float x = cx - total / 2f;
            float rise = 12 * d;
            for (int i = 0; i < full.length(); i++) {
                float w = text.measureText(full, i, i + 1);
                if (full.charAt(i) != ' ') {
                    float local = seg(time, WORD_START + i * 0.05f, WORD_START + i * 0.05f + 0.6f);
                    if (local > 0) {
                        boolean second = i > WORD_1.length();
                        float alpha = ease(local) * (second ? 0.62f : 0.95f) * visible;
                        text.setAlpha(Math.round(255 * alpha));
                        float dy = rise * (1 - Spring.SOFT.getInterpolation(local));
                        canvas.drawText(full, i, i + 1, x, wordY + dy, text);
                    }
                }
                x += w + spacing;
            }
        }
    }
}
