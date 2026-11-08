package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.limelight.R;
import com.limelight.pocket.PocketAction;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.PocketSounds;

/**
 * The in-stream radial menu: frosted glass over the game itself, a ring of glass slices with
 * line icons, and spring physics on everything (the ring blooms open slice by slice, the chosen
 * slice pops out, a pointer glides after the stick, the centre cross-fades to the choice).
 * Pick with either stick or the D-pad; the host decides what confirms (releasing the held
 * button, or A).
 */
public class RadialMenuView extends FrameLayout {
    private static final float STICK_DEADZONE = 0.45f;

    private final ImageView backdrop;
    private final Ring ring;
    private PocketAction[] slots = new PocketAction[0];
    private String[] labels = new String[0];
    private String centerTop = "";
    private String centerBottom = "";
    private int selected = -1;
    private boolean showing;

    public RadialMenuView(Context context) {
        super(context);
        setVisibility(GONE);
        setFocusable(false);
        setClickable(true);
        setBackgroundColor(0x00000000);

        backdrop = new ImageView(context);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(backdrop, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ring = new Ring(context);
        addView(ring, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public boolean isShowing() {
        return showing;
    }

    /** The frosted game picture behind the ring (from FrostedGlass.snapshot). */
    public void setBackdrop(Bitmap bitmap) {
        if (showing) {
            FrostedGlass.apply(backdrop, bitmap, 0.5f);
        }
    }

    public void show(PocketAction[] slots, String centerTop, String centerBottom) {
        this.slots = slots;
        this.labels = new String[slots.length];
        for (int i = 0; i < slots.length; i++) {
            labels[i] = slots[i] == PocketAction.NONE ? "" : slots[i].shortLabel(getContext());
        }
        this.centerTop = centerTop;
        this.centerBottom = centerBottom;
        this.selected = -1;
        this.showing = true;
        backdrop.setImageDrawable(null);
        animate().cancel();
        setAlpha(1f);
        setScaleX(1f);
        setScaleY(1f);
        setVisibility(VISIBLE);
        ring.start(slots.length);
        PocketSounds.play(getContext(), PocketSounds.RADIAL_OPEN);
    }

    /** Closes without choosing. */
    public void hide() {
        if (!showing) {
            return;
        }
        showing = false;
        PocketSounds.play(getContext(), PocketSounds.CLOSE);
        animate().cancel();
        animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(150).setInterpolator(PocketUi.EASE)
                .withEndAction(this::gone).start();
    }

    /** Closes with the chosen slice flashing and the ring opening out towards the player. */
    public void confirm() {
        if (!showing) {
            return;
        }
        showing = false;
        PocketSounds.play(getContext(), PocketSounds.RADIAL_SELECT);
        ring.flash();
        animate().cancel();
        animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setStartDelay(70).setDuration(190)
                .setInterpolator(PocketUi.EASE).withEndAction(this::gone).start();
    }

    private void gone() {
        setVisibility(GONE);
        setScaleX(1f);
        setScaleY(1f);
        backdrop.setImageDrawable(null);
    }

    /** Selected slot, or -1 when the stick is centred / nothing chosen. */
    public int getSelected() {
        if (selected < 0 || selected >= slots.length || slots[selected] == PocketAction.NONE) {
            return -1;
        }
        return selected;
    }

    public PocketAction getSelectedAction() {
        int s = getSelected();
        return s < 0 ? null : slots[s];
    }

    /** Stick position in -1..1 (y down positive). */
    public void onStick(float x, float y) {
        if (!showing || slots.length == 0) {
            return;
        }
        float mag = (float) Math.hypot(x, y);
        if (mag < STICK_DEADZONE) {
            return; // keep the last choice so a released stick doesn't lose it
        }
        double angle = Math.toDegrees(Math.atan2(y, x)) + 90; // 0 = up, clockwise
        if (angle < 0) {
            angle += 360;
        }
        ring.pointTo((float) angle - 90f);
        float step = 360f / slots.length;
        int slot = (int) Math.floor(((angle + step / 2) % 360) / step);
        select(slot);
    }

    /** D-pad: direction in 45 degree steps (0 = up, 2 = right, 4 = down, 6 = left). */
    public void onDirection(int eighth) {
        if (!showing || slots.length == 0) {
            return;
        }
        int slot = Math.round(eighth * slots.length / 8f) % slots.length;
        select(slot);
        ring.pointTo(-90f + slot * 360f / slots.length);
    }

    public void rotate(int delta) {
        if (!showing || slots.length == 0) {
            return;
        }
        int base = selected < 0 ? 0 : selected;
        int slot = ((base + delta) % slots.length + slots.length) % slots.length;
        select(slot);
        ring.pointTo(-90f + slot * 360f / slots.length);
    }

    private void select(int slot) {
        if (slot != selected) {
            int previous = selected;
            selected = slot;
            ring.onSelectionChanged(previous, slot);
            PocketSounds.play(getContext(), PocketSounds.RADIAL_TICK);
            // A light detent per slice, like a physical dial (follows the system haptics setting)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    // ---- Drawing -------------------------------------------------------------------------

    private final class Ring extends View {
        private final float d;
        private final Paint scrim = new Paint();
        private final Paint vignette = new Paint();
        private final Paint slice = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sliceSelected = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hairline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pointer = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint center = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint big = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF oval = new RectF();
        private final int accent;

        // Motion
        private final Spring open = new Spring(240f, 0.74f, 0f);
        private final Spring pointerAngle = new Spring(420f, 0.86f, -90f);
        private final Spring pointerAlpha = new Spring(300f, 1f, 0f);
        private final Spring centerSwap = new Spring(520f, 1f, 1f);
        private final Spring flash = new Spring(160f, 1f, 0f);
        private Spring[] pops = new Spring[0];
        private Spring[] reveal = new Spring[0];
        private long[] revealAt = new long[0];
        private long lastFrameNanos;
        private int previousSelected = -1;
        private int lastLayoutW, lastLayoutH;

        Ring(Context context) {
            super(context);
            d = getResources().getDisplayMetrics().density;
            float sp = getResources().getDisplayMetrics().scaledDensity;
            accent = PocketPrefs.accentColor(context);
            scrim.setColor(0x52000000);
            slice.setPathEffect(new CornerPathEffect(7 * d));
            sliceSelected.setPathEffect(new CornerPathEffect(7 * d));
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(1.1f * d);
            rim.setStrokeCap(Paint.Cap.ROUND);
            hairline.setStyle(Paint.Style.STROKE);
            hairline.setStrokeWidth(1f * d);
            pointer.setStyle(Paint.Style.STROKE);
            pointer.setStrokeCap(Paint.Cap.ROUND);
            pointer.setStrokeWidth(3f * d);
            pointer.setColor(accent);
            center.setColor(0x9E08080A);
            flashPaint.setPathEffect(new CornerPathEffect(7 * d));
            Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            label.setTypeface(medium);
            label.setTextSize(10.5f * sp);
            label.setTextAlign(Paint.Align.CENTER);
            big.setTypeface(medium);
            big.setTextSize(15.5f * sp);
            big.setColor(0xFFFFFFFF);
            big.setTextAlign(Paint.Align.CENTER);
            small.setTextSize(10.5f * sp);
            small.setColor(0x8CFFFFFF);
            small.setTextAlign(Paint.Align.CENTER);
        }

        void start(int count) {
            open.snap(0f);
            open.target = 1f;
            pops = new Spring[count];
            reveal = new Spring[count];
            revealAt = new long[count];
            long now = System.nanoTime();
            for (int i = 0; i < count; i++) {
                pops[i] = new Spring(560f, 0.58f, 0f);
                reveal[i] = new Spring(320f, 0.8f, 0f);
                // The ring blooms open clockwise from the top
                revealAt[i] = now + i * 22_000_000L;
            }
            pointerAlpha.snap(0f);
            centerSwap.snap(1f);
            flash.snap(0f);
            previousSelected = -1;
            lastFrameNanos = 0;
            if (!PocketPrefs.animationsEnabled(getContext())) {
                open.snap(1f);
                for (int i = 0; i < count; i++) {
                    reveal[i].snap(1f);
                }
            }
            invalidate();
        }

        void pointTo(float degrees) {
            // Shortest way round
            float current = pointerAngle.value;
            float delta = ((degrees - current) % 360f + 540f) % 360f - 180f;
            pointerAngle.target = current + delta;
            if (pointerAlpha.target == 0f) {
                pointerAngle.snap(degrees);
            }
            pointerAlpha.target = 1f;
            invalidate();
        }

        void onSelectionChanged(int previous, int now) {
            previousSelected = previous;
            for (int i = 0; i < pops.length; i++) {
                pops[i].target = i == now ? 1f : 0f;
            }
            centerSwap.snap(0f);
            centerSwap.target = 1f;
            invalidate();
        }

        void flash() {
            flash.snap(1f);
            flash.target = 0f;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            lastLayoutW = w;
            lastLayoutH = h;
            vignette.setShader(new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.62f,
                    new int[]{0x00000000, 0x00000000, 0x8C000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        }

        private boolean stepAll() {
            long now = System.nanoTime();
            float dt = lastFrameNanos == 0 ? 0.016f : Math.min(0.033f, (now - lastFrameNanos) / 1e9f);
            lastFrameNanos = now;
            boolean moving = open.step(dt);
            moving |= pointerAngle.step(dt);
            moving |= pointerAlpha.step(dt);
            moving |= centerSwap.step(dt);
            moving |= flash.step(dt);
            for (int i = 0; i < pops.length; i++) {
                moving |= pops[i].step(dt);
                if (reveal[i].target == 0f && now >= revealAt[i]) {
                    reveal[i].target = 1f;
                }
                moving |= reveal[i].step(dt) || reveal[i].target == 0f;
            }
            return moving;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            boolean moving = stepAll();
            int w = getWidth();
            int h = getHeight();
            canvas.drawRect(0, 0, w, h, scrim);
            canvas.drawRect(0, 0, w, h, vignette);
            int n = slots.length;
            if (n == 0) {
                return;
            }
            float o = Math.max(0f, open.value);
            float cx = w / 2f;
            float cy = h / 2f;
            float baseR = Math.min(w, h) * 0.40f;
            float outerR = baseR * (0.86f + 0.14f * o);
            float innerR = outerR * 0.46f;
            float step = 360f / n;
            float gap = 2.4f;
            int alpha = (int) (255 * Math.min(1f, Math.max(0f, o * 1.4f)));

            // Light behind the chosen slice
            int sel = getSelected();
            if (sel >= 0) {
                double a = Math.toRadians(-90f + sel * step);
                float gx = cx + (float) Math.cos(a) * outerR * 0.78f;
                float gy = cy + (float) Math.sin(a) * outerR * 0.78f;
                float gr = 150 * d * (0.6f + 0.4f * pops[sel].value);
                glow.setShader(new RadialGradient(gx, gy, gr,
                        PocketTheme.withAlpha(accent, (int) (0x38 * Math.min(1f, pops[sel].value) * o)), 0x00000000,
                        Shader.TileMode.CLAMP));
                canvas.drawCircle(gx, gy, gr, glow);
            }

            hairline.setColor(PocketTheme.withAlpha(0xFFFFFFFF, (int) (0x1A * o)));
            canvas.drawCircle(cx, cy, outerR + 11 * d, hairline);

            for (int i = 0; i < n; i++) {
                float r = Math.max(0f, Math.min(1.2f, reveal[i].value));
                float pop = Math.max(0f, pops[i].value);
                float ro = outerR * (0.9f + 0.1f * r) + pop * 10 * d;
                float ri = innerR + pop * 2 * d;
                float start = -90f + i * step - step / 2 + gap / 2;
                float sweep = step - gap;
                buildSlice(cx, cy, ro, ri, start, sweep);
                int sliceAlpha = (int) (alpha * Math.min(1f, r));
                boolean isSel = i == sel;

                // Glass slice: brighter at the top, a little lighter when chosen
                slice.setShader(new LinearGradient(cx, cy - ro, cx, cy + ro,
                        PocketTheme.withAlpha(0xFFFFFFFF, (int) ((0x24 + 0x10 * pop) * sliceAlpha / 255f)),
                        PocketTheme.withAlpha(0xFFFFFFFF, (int) ((0x0C + 0x08 * pop) * sliceAlpha / 255f)),
                        Shader.TileMode.CLAMP));
                canvas.drawPath(path, slice);
                if (pop > 0.01f) {
                    sliceSelected.setShader(new LinearGradient(cx, cy - ro, cx, cy + ro,
                            PocketTheme.withAlpha(accent, (int) (0xF8 * Math.min(1f, pop) * sliceAlpha / 255f)),
                            PocketTheme.withAlpha(darker(accent), (int) (0xEB * Math.min(1f, pop) * sliceAlpha / 255f)),
                            Shader.TileMode.CLAMP));
                    canvas.drawPath(path, sliceSelected);
                }
                if (isSel && flash.value > 0.01f) {
                    flashPaint.setColor(PocketTheme.withAlpha(0xFFFFFFFF, (int) (255 * Math.min(1f, flash.value))));
                    canvas.drawPath(path, flashPaint);
                }

                // Glass rim along the outer edge
                rim.setColor(PocketTheme.withAlpha(0xFFFFFFFF, (int) ((0x30 - 0x18 * Math.min(1f, pop)) * sliceAlpha / 255f)));
                oval.set(cx - ro + 1.5f * d, cy - ro + 1.5f * d, cx + ro - 1.5f * d, cy + ro - 1.5f * d);
                canvas.drawArc(oval, start + 2.5f, sweep - 5f, false, rim);

                if (slots[i] == PocketAction.NONE) {
                    continue;
                }
                double mid = Math.toRadians(-90f + i * step);
                float mr = (ro + ri) / 2f;
                float ix = cx + (float) Math.cos(mid) * mr;
                float iy = cy + (float) Math.sin(mid) * mr;
                float t = Math.min(1f, pop);
                int fg = blend(0xEBFFFFFF, 0xFF0A0A0A, t);
                fg = PocketTheme.withAlpha(fg, (int) ((fg >>> 24) * sliceAlpha / 255f));
                PocketIcons.draw(canvas, slots[i].name(), ix, iy - 9 * d, (24 + 3 * t) * d, fg, icon);
                label.setColor(PocketTheme.withAlpha(blend(0xC7FFFFFF, 0xD9000000, t), (int) (0xFF * sliceAlpha / 255f)));
                drawFitted(canvas, labels[i], ix, iy + 15 * d - (label.ascent() + label.descent()) / 2,
                        (ro - ri) * 0.9f, label);
            }

            // Pointer that glides after the stick
            hairline.setColor(PocketTheme.withAlpha(0xFFFFFFFF, (int) (0x14 * o)));
            float pr = innerR - 9 * d;
            canvas.drawCircle(cx, cy, pr, hairline);
            if (pointerAlpha.value > 0.01f) {
                pointer.setAlpha((int) (255 * Math.min(1f, pointerAlpha.value) * o));
                oval.set(cx - pr, cy - pr, cx + pr, cy + pr);
                canvas.drawArc(oval, pointerAngle.value - 14f, 28f, false, pointer);
            }

            // Centre glass
            float cr = innerR - 18 * d;
            center.setAlpha((int) (0x9E * o));
            canvas.drawCircle(cx, cy, cr, center);
            hairline.setColor(PocketTheme.withAlpha(0xFFFFFFFF, (int) (0x24 * o)));
            canvas.drawCircle(cx, cy, cr, hairline);

            float swap = Math.max(0f, Math.min(1f, centerSwap.value));
            int contentAlpha = (int) (255 * swap * o);
            float lift = (1f - swap) * 6 * d;
            if (sel >= 0) {
                PocketIcons.draw(canvas, slots[sel].name(), cx, cy - 20 * d + lift, 34 * d,
                        PocketTheme.withAlpha(0xFFFFFFFF, contentAlpha), icon);
                big.setAlpha(contentAlpha);
                drawFitted(canvas, labels[sel], cx, cy + 14 * d + lift - (big.ascent() + big.descent()) / 2, cr * 1.7f, big);
                small.setAlpha((int) (0x8C * swap * o));
                canvas.drawText(getContext().getString(R.string.pocket_radial_hint), cx,
                        cy + 34 * d + lift - (small.ascent() + small.descent()) / 2, small);
            }
            else {
                big.setAlpha(contentAlpha);
                float sz = big.getTextSize();
                big.setTextSize(sz * 1.45f);
                canvas.drawText(centerTop, cx, cy - 4 * d - (big.ascent() + big.descent()) / 2 - 6 * d, big);
                big.setTextSize(sz);
                small.setAlpha((int) (0x9E * o));
                canvas.drawText(centerBottom, cx, cy + 18 * d - (small.ascent() + small.descent()) / 2, small);
            }

            if (moving) {
                postInvalidateOnAnimation();
            }
        }

        private void buildSlice(float cx, float cy, float ro, float ri, float start, float sweep) {
            path.reset();
            oval.set(cx - ro, cy - ro, cx + ro, cy + ro);
            path.arcTo(oval, start, sweep, true);
            oval.set(cx - ri, cy - ri, cx + ri, cy + ri);
            path.arcTo(oval, start + sweep, -sweep);
            path.close();
        }

        private void drawFitted(Canvas canvas, String text, float x, float baseline, float maxWidth, Paint paint) {
            if (text == null || text.isEmpty()) {
                return;
            }
            float width = paint.measureText(text);
            if (width <= maxWidth || width == 0) {
                canvas.drawText(text, x, baseline, paint);
                return;
            }
            float original = paint.getTextSize();
            paint.setTextSize(original * maxWidth / width);
            canvas.drawText(text, x, baseline, paint);
            paint.setTextSize(original);
        }
    }

    private static int darker(int color) {
        int r = (int) (((color >> 16) & 0xFF) * 0.9f);
        int g = (int) (((color >> 8) & 0xFF) * 0.9f);
        int b = (int) ((color & 0xFF) * 0.92f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Linear blend of two ARGB colours. */
    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }
}
