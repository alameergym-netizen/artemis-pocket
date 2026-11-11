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
import android.widget.TextView;

import com.limelight.R;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.PocketSounds;

/**
 * The boot sequence (about six seconds, any button skips it), timed to the boot sound:
 * a spark of light appears, splits into two beams that trace the handheld's outline, the body
 * comes into focus as its screen lights up with a shock ring, the wordmark rises letter by
 * letter, a reflection glides across the screen. To finish, the logo and wordmark fly up and dock
 * into the home screen's brand mark, and a wave of light washes out from there to reveal the home
 * screen while it builds itself (the entrance choreography starts with the wave).
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
    // The hand-off, in seconds after the exit begins
    private static final float FLY_END = 0.62f;
    private static final float LAND = 0.58f;
    private static final float WAVE_START = 0.40f;
    private static final float WAVE_END = 1.15f;
    private static final float ENTRANCE_AT = 0.46f;
    private static final long MAX_SOUND_WAIT_MS = 600;

    private static final String WORD_1 = "POCKET";
    private static final String WORD_2 = "NEXUS";

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
    private Runnable onReveal;
    private View reveal;

    private final long createdAt;
    private long startNanos = -1;
    private long lastFrameNanos;
    private float t;
    /** When the exit began (timeline seconds), or -1. */
    private float exitAt = -1;
    private boolean handoff;
    private float exitSpeed = 1f;
    private boolean revealFired;
    private boolean landed;
    // Where the logo and wordmark dock (overlay coordinates)
    private View brandLogo;
    private TextView brandText;
    private float fromX;
    private float fromY;
    private float toX;
    private float toY;
    private float toScale;
    private float textToX;
    private float textToBase;
    private float textToSize;
    private float textToSpacing;
    private float waveMax;
    private final int[] loc = new int[2];
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

    /** The screen underneath; the wave of light reveals it. */
    public void setReveal(View view) {
        reveal = view;
    }

    /** Runs when the home screen starts to appear (so it can build itself as it is revealed). */
    public void setOnReveal(Runnable r) {
        onReveal = r;
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
            showBrand();
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
            // Nothing lit yet to hand off: just fade away
            fadeExit();
        }
        else {
            // Same pace as the natural ending, so the dock stays in time with its sound
            beginHandoff();
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
            beginHandoff();
        }
        if (handoff) {
            float u = (t - exitAt) / exitSpeed;
            float p = flight(u);
            // The logo flies along a gentle curve and shrinks into the brand mark
            float x = bezier(fromX, fromX + (toX - fromX) * 0.1f, toX, p);
            float y = bezier(fromY, fromY + (toY - fromY) * 0.7f, toY, p);
            float s = (logoScale.value + (1f - logoScale.value) * p) * (1f + (toScale - 1f) * p);
            logo.setTranslationX(x - getWidth() / 2f);
            logo.setTranslationY(y - getHeight() / 2f);
            logo.setScaleX(s);
            logo.setScaleY(s);
            logo.setColorFilter(mix(accent, 0xFFFFFFFF, p));
            float copies = brandLogo != null ? 1f - seg(u, LAND + 0.02f, LAND + 0.1f)
                    : 1f - ease(seg(u, 0.3f, FLY_END));
            logo.setAlpha(copies);
            if (!landed && u >= LAND) {
                // The real brand mark takes over underneath the flying copy
                landed = true;
                showBrand();
            }
            if (!revealFired && u >= ENTRANCE_AT) {
                revealFired = true;
                if (onReveal != null) {
                    onReveal.run();
                }
            }
            if (u >= WAVE_END) {
                finish();
                return;
            }
        }
        stage.invalidate();
    }

    private static float flight(float u) {
        return easeInOut(seg(u, 0f, FLY_END));
    }

    private static float bezier(float a, float c, float b, float p) {
        float q = 1 - p;
        return q * q * a + 2 * q * p * c + p * p * b;
    }

    private static int mix(int a, int b, float p) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * p);
        int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * p);
        int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * p);
        return Color.rgb(r, g, bl);
    }

    /** Finds where the logo and wordmark dock on the screen underneath (at rest, before it moves). */
    private void measureDock() {
        getLocationInWindow(loc);
        int ox = loc[0];
        int oy = loc[1];
        fromX = getWidth() / 2f;
        fromY = getHeight() / 2f - logoLift;
        View mark = reveal != null ? reveal.findViewById(R.id.pocketBrandLogo) : null;
        View word = reveal != null ? reveal.findViewById(R.id.pocketBrand) : null;
        if (mark != null && mark.isShown() && mark.getWidth() > 0) {
            mark.getLocationInWindow(loc);
            float bw = mark.getWidth() - mark.getPaddingLeft() - mark.getPaddingRight();
            float bh = mark.getHeight() - mark.getPaddingTop() - mark.getPaddingBottom();
            float drawn = Math.min(bw, bh * 64f / 30f);
            toX = loc[0] - ox + mark.getPaddingLeft() + bw / 2f;
            toY = loc[1] - oy + mark.getPaddingTop() + bh / 2f;
            toScale = drawn / logoW;
            brandLogo = mark;
        }
        else {
            // Nothing to dock into on this screen: rise, shrink and fade out
            toX = getWidth() / 2f;
            toY = 30 * d;
            toScale = 0.2f;
            brandLogo = null;
        }
        if (brandLogo != null && word instanceof TextView && word.isShown() && word.getWidth() > 0) {
            TextView tv = (TextView) word;
            tv.getLocationInWindow(loc);
            textToX = loc[0] - ox + tv.getCompoundPaddingLeft();
            textToBase = loc[1] - oy + tv.getBaseline();
            textToSize = tv.getTextSize();
            textToSpacing = tv.getLetterSpacing();
            brandText = tv;
        }
        else {
            brandText = null;
            textToSize = stage.text.getTextSize() * 0.62f;
            textToSpacing = 0.22f;
            textToX = -1; // centred under the logo
            textToBase = toY + 22 * d;
        }
        float far = 0;
        float[][] corners = {{0, 0}, {getWidth(), 0}, {0, getHeight()}, {getWidth(), getHeight()}};
        for (float[] c : corners) {
            far = Math.max(far, (float) Math.hypot(c[0] - toX, c[1] - toY));
        }
        waveMax = far + 60 * d;
    }

    private void beginHandoff() {
        exitAt = t;
        handoff = true;
        measureDock();
        // Hidden until the flying copies land on them
        if (brandLogo != null) {
            brandLogo.setAlpha(0f);
        }
        if (brandText != null) {
            brandText.setAlpha(0f);
        }
        PocketSounds.play(getContext(), PocketSounds.BOOT_EXIT);
        if (reveal != null) {
            reveal.animate().cancel();
            reveal.setAlpha(1f);
            reveal.setScaleX(1f);
            reveal.setScaleY(1f);
        }
    }

    private void showBrand() {
        if (brandLogo != null) {
            brandLogo.setAlpha(1f);
        }
        if (brandText != null) {
            brandText.setAlpha(1f);
        }
    }

    private void fadeExit() {
        exitAt = t;
        revealFired = true;
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
        showBrand();
        if (handoff) {
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
        private final Paint word = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path hole = new Path();
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
            text.setTypeface(com.limelight.pocket.ui.PocketFonts.medium());
            small.setColor(0xFFFFFFFF);
            small.setTextSize(10.5f * getResources().getDisplayMetrics().scaledDensity);
            small.setLetterSpacing(0.22f);
            small.setTextAlign(Paint.Align.CENTER);
            line.setStrokeWidth(1.6f * d);
            line.setStrokeCap(Paint.Cap.ROUND);
            word.setColor(0xFFFFFFFF);
            word.setTypeface(com.limelight.pocket.ui.PocketFonts.medium());
            wave.setStyle(Paint.Style.FILL);
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
            float u = handoff ? (time - exitAt) / exitSpeed : -1f;
            int save = canvas.save();
            if (handoff && u > WAVE_START) {
                // A wave of light washes out from the brand mark and reveals the home screen
                float wv = seg(u, WAVE_START, WAVE_END);
                float r = waveMax * (1 - (float) Math.pow(1 - wv, 2.4));
                hole.reset();
                hole.setFillType(Path.FillType.EVEN_ODD);
                hole.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
                hole.addCircle(toX, toY, Math.max(r, 0.5f), Path.Direction.CW);
                wave.setShader(null);
                wave.setColor(0xFF000000);
                canvas.drawPath(hole, wave);
                if (r > 2) {
                    float f = Math.min(110 * d, r * 0.6f);
                    wave.setShader(new RadialGradient(toX, toY, r,
                            new int[]{0x00000000, 0x00000000,
                                    PocketTheme.withAlpha(accent, Math.round(64 * (1 - wv))), 0xFF000000},
                            new float[]{0f, (r - f) / r, (r - 0.35f * f) / r, 1f}, Shader.TileMode.CLAMP));
                    canvas.drawCircle(toX, toY, r, wave);
                    wave.setShader(null);
                }
            }
            else {
                canvas.drawColor(0xFF000000);
            }
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
            // The lit screen goes dark as the logo lifts off
            float open = handoff ? ease(seg(u, 0f, 0.14f)) : 0f;
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
            if (handoff) {
                drawFlyingWordmark(canvas, time, u);
                // A soft ping where the logo docks
                float ping = seg(u, LAND, LAND + 0.45f);
                if (brandLogo != null && ping > 0 && ping < 1) {
                    stroke.setStrokeWidth(1.5f * d);
                    stroke.setAlpha(Math.round(150 * (1 - ping) * (1 - ping)));
                    canvas.drawCircle(toX, toY, (10 + 34 * ease(ping)) * d, stroke);
                }
            }
            if (out < 1) {
                if (!handoff) {
                    drawWordmark(canvas, time, 1 - out);
                }
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

        /** The wordmark shrinking into the home screen's brand text (same font, size, spacing). */
        private void drawFlyingWordmark(Canvas canvas, float time, float u) {
            float shown = ease(seg(time, WORD_START, WORD_START + 1.25f));
            float alpha = brandText != null ? 1f - seg(u, LAND + 0.02f, LAND + 0.1f)
                    : 1f - ease(seg(u, 0.25f, FLY_END));
            alpha *= shown;
            if (alpha <= 0.01f) {
                return;
            }
            String first = WORD_1 + " ";
            String full = WORD_1 + " " + WORD_2;
            float size0 = text.getTextSize();
            float em0 = 0.62f - 0.28f * ease(seg(exitAt, WORD_START, WORD_START + 1.1f));
            word.setTextSize(size0);
            word.setLetterSpacing(em0);
            float startX = cx - word.measureText(full) / 2f;

            float p = flight(u);
            word.setTextSize(size0 + (textToSize - size0) * p);
            word.setLetterSpacing(em0 + (textToSpacing - em0) * p);
            float endX = textToX;
            if (endX < 0) {
                word.setTextSize(textToSize);
                word.setLetterSpacing(textToSpacing);
                endX = toX - word.measureText(full) / 2f;
                word.setTextSize(size0 + (textToSize - size0) * p);
                word.setLetterSpacing(em0 + (textToSpacing - em0) * p);
            }
            float x = bezier(startX, startX + (endX - startX) * 0.1f, endX, p);
            float y = bezier(wordY, wordY + (textToBase - wordY) * 0.7f, textToBase, p);
            word.setAlpha(Math.round(255 * alpha * (0.95f + 0.05f * p)));
            canvas.drawText(first, x, y, word);
            float second = x + word.measureText(first);
            word.setAlpha(Math.round(255 * alpha * (0.62f + 0.38f * p)));
            canvas.drawText(WORD_2, second, y, word);
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
