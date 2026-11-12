package com.limelight.pocket.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Real glass for the console screens (Android 12+): panels show a live, blurred copy of whatever
 * is behind them (the backdrop art and lights). Two materials: Frost (the default: acrylic, like
 * Windows 11's Start menu: a deep blur under a dark smoky tint, a fine grain and a hairline edge)
 * and Glass (a lighter blur, more colour, a top sheen and a bright rim).
 *
 * The backdrop is recorded each frame into a small RenderNode (a quarter of the size, so the
 * blur costs next to nothing) and every glass panel draws that one node through its own
 * rounded window. On older Android the panels fall back to plain translucent glass.
 */
public final class LiveGlass {
    private LiveGlass() {}

    /** Glass looks: a light chip on dark, or a deeper panel for bigger surfaces. */
    public static final int STYLE_PILL = 0;
    public static final int STYLE_PANEL = 1;
    /**
     * A blur that fades in (progressive blur, like visionOS / iOS): clear at the top of the view,
     * fully frosted at the bottom, no edge and no tint. Under a shelf of games or a menu.
     */
    public static final int STYLE_FADE = 2;
    /** The same, the other way up: frosted at the top, clear at the bottom (over a list's top edge). */
    public static final int STYLE_FADE_UP = 3;

    static boolean fades(int style) {
        return style == STYLE_FADE || style == STYLE_FADE_UP;
    }

    private static final float SCALE = 0.25f;

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /** The layer behind the glass. It must not contain any of the glass panels. */
    public static final class Backdrop {
        final View source;
        final List<View> hosts = new ArrayList<>();
        final List<GlassDrawable> glasses = new ArrayList<>();
        Object node; // RenderNode (API 29+), kept as Object so this class loads anywhere
        boolean ready;
        boolean failed;
        private final int[] srcLoc = new int[2];
        private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
            record();
            return true;
        };

        final boolean frost;

        public Backdrop(View source, float blurDp) {
            this(source, blurDp, false);
        }

        public Backdrop(View source, float blurDp, boolean frost) {
            this.source = source;
            this.frost = frost;
            if (!supported()) {
                return;
            }
            float px = blurDp * source.getResources().getDisplayMetrics().density * SCALE;
            node = Api31.createNode(px, frost);
            source.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(@NonNull View v) {
                    v.getViewTreeObserver().addOnPreDrawListener(preDraw);
                }

                @Override
                public void onViewDetachedFromWindow(@NonNull View v) {
                    v.getViewTreeObserver().removeOnPreDrawListener(preDraw);
                }
            });
            if (source.isAttachedToWindow()) {
                source.getViewTreeObserver().addOnPreDrawListener(preDraw);
            }
        }

        private void record() {
            if (node == null || failed) {
                return;
            }
            int w = source.getWidth();
            int h = source.getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            try {
                Api31.record(node, source, w, h, SCALE);
                ready = true;
            } catch (RuntimeException e) {
                // Never let glass break a screen: fall back to plain glass
                failed = true;
                ready = false;
            }
            // Panels that moved need to look through a different part of the backdrop
            for (int i = 0; i < glasses.size(); i++) {
                GlassDrawable glass = glasses.get(i);
                if (glass.moved()) {
                    // Also when the glass is wrapped by another background (a focus glider)
                    glass.host.invalidate();
                    glass.invalidateSelf();
                }
            }
        }

        int[] sourceLocation() {
            source.getLocationInWindow(srcLoc);
            return srcLoc;
        }
    }

    /** Turns the view's background into live glass over the backdrop. */
    public static void attach(View host, Backdrop backdrop, int style) {
        if (host == null) {
            return;
        }
        GlassDrawable glass = new GlassDrawable(host, backdrop, style);
        host.setBackground(glass);
        host.setClipToOutline(!fades(style));
        if (backdrop != null && !backdrop.hosts.contains(host)) {
            backdrop.hosts.add(host);
            backdrop.glasses.add(glass);
        }
    }

    /**
     * Colours this view's glass (Play wears the accent): the colour is part of the background, so
     * the text always sits on top of it. Works before or after the view becomes glass.
     */
    public static void setFace(View host, int top, int bottom) {
        if (host == null) {
            return;
        }
        host.setTag(com.limelight.R.id.pocket_glass_face, new int[]{top, bottom});
        Drawable bg = host.getBackground();
        if (bg instanceof GlassDrawable) {
            ((GlassDrawable) bg).setFace(top, bottom);
            return;
        }
        float d = host.getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable face = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        face.setCornerRadius(100 * d);
        face.setStroke(Math.max(1, Math.round(d)), 0x59FFFFFF);
        host.setBackground(face);
    }

    /** Finds views tagged "glass" / "glass_panel" under root and turns them into glass. */
    public static void attachTagged(View root, Backdrop backdrop) {
        if (root == null) {
            return;
        }
        Object tag = root.getTag();
        if ("glass".equals(tag)) {
            attach(root, backdrop, STYLE_PILL);
        }
        else if ("glass_panel".equals(tag)) {
            attach(root, backdrop, STYLE_PANEL);
        }
        else if ("glass_fade".equals(tag)) {
            if (backdrop != null) {
                attach(root, backdrop, STYLE_FADE);
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                attachTagged(group.getChildAt(i), backdrop);
            }
        }
    }

    // ---- The glass surface --------------------------------------------------------------

    private static android.graphics.BitmapShader grain;

    /** A fine, static grain (frosted glass isn't perfectly smooth). */
    static synchronized android.graphics.BitmapShader grain() {
        if (grain == null) {
            int n = 96;
            int[] px = new int[n * n];
            java.util.Random r = new java.util.Random(0x5EED);
            for (int i = 0; i < px.length; i++) {
                int v = r.nextInt(256);
                int a = 10 + r.nextInt(14);
                px[i] = (a << 24) | (v << 16) | (v << 8) | v;
            }
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888);
            grain = new android.graphics.BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        }
        return grain;
    }

    static final class GlassDrawable extends Drawable {
        final View host;
        private final Backdrop backdrop;
        private final int style;
        private final float d;
        private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint noise = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fadeMask = new Paint();
        private final boolean frost;
        private int veilBase = 0x47;
        private int faceTop;
        private int faceBottom;
        private boolean hasFace;
        private final RectF rect = new RectF();
        private final RectF rimRect = new RectF();
        private final int[] hostLoc = new int[2];
        private float radius;
        private int lastDx = Integer.MIN_VALUE;
        private int lastDy = Integer.MIN_VALUE;
        private int alpha = 255;

        GlassDrawable(View host, Backdrop backdrop, int style) {
            this.host = host;
            this.backdrop = backdrop;
            this.style = style;
            this.d = host.getResources().getDisplayMetrics().density;
            this.frost = backdrop != null ? backdrop.frost : com.limelight.pocket.PocketPrefs.frost(host.getContext());
            noise.setShader(grain());
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1f, d));
            Object f = host.getTag(com.limelight.R.id.pocket_glass_face);
            if (f instanceof int[] && ((int[]) f).length == 2) {
                setFace(((int[]) f)[0], ((int[]) f)[1]);
            }
        }

        /** A colour over the blur, under the text (top to bottom). */
        void setFace(int top, int bottom) {
            hasFace = true;
            faceTop = top;
            faceBottom = bottom;
            if (!rect.isEmpty()) {
                face.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, top, bottom, Shader.TileMode.CLAMP));
            }
            invalidateSelf();
        }

        boolean moved() {
            int[] src = backdrop.sourceLocation();
            host.getLocationInWindow(hostLoc);
            int dx = hostLoc[0] - src[0];
            int dy = hostLoc[1] - src[1];
            return dx != lastDx || dy != lastDy;
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            super.onBoundsChange(bounds);
            rect.set(bounds);
            float h = bounds.height();
            if (fades(style)) {
                // Clear at the top, frosted from 55% down (a smooth ease, no visible start); or
                // the other way up
                boolean up = style == STYLE_FADE_UP;
                fadeMask.setShader(new LinearGradient(0, up ? rect.bottom : rect.top, 0, up ? rect.top : rect.bottom,
                        new int[]{0x00000000, 0x2E000000, 0x9E000000, 0xFF000000, 0xFF000000},
                        new float[]{0f, 0.18f, 0.38f, 0.58f, 1f}, Shader.TileMode.CLAMP));
                fadeMask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
                return;
            }
            radius = style == STYLE_PILL ? h / 2f : Math.min((frost ? 14 : 22) * d, h / 2f);
            float inset = rim.getStrokeWidth() / 2f;
            rimRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset);
            boolean live = backdrop != null && supported();
            if (frost) {
                // Acrylic (like Windows 11's Start menu): a deep blur under a dark smoky tint,
                // the faintest lift of light, a fine grain and a hairline edge. No milkiness.
                veilBase = live ? 0xB8 : 0xE6;
                veil.setColor(live ? 0xB81C1C1F : 0xE61C1C1F);
                veil.setAlpha(Math.round(veilBase * alpha / 255f));
                tint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                        0x0DFFFFFF, 0x05FFFFFF, Shader.TileMode.CLAMP));
                sheen.setShader(null);
                sheen.setColor(0x00000000);
                rim.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                        new int[]{0x30FFFFFF, 0x17FFFFFF, 0x12FFFFFF}, new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
                noise.setAlpha(Math.round(alpha * 0.4f));
                if (hasFace) {
                    face.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, faceTop, faceBottom, Shader.TileMode.CLAMP));
                }
                return;
            }
            // Body: darker when there's no real blur, so text stays readable on anything
            tint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                    live ? (style == STYLE_PILL ? 0x26FFFFFF : 0x1AFFFFFF) : 0x2EFFFFFF,
                    live ? (style == STYLE_PILL ? 0x14FFFFFF : 0x0AFFFFFF) : 0x1AFFFFFF,
                    Shader.TileMode.CLAMP));
            sheen.setShader(new LinearGradient(0, rect.top, 0, rect.top + h * 0.6f,
                    0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            // The rim catches light on top and a softer reflection along the bottom
            rim.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                    new int[]{0x8CFFFFFF, 0x1FFFFFFF, 0x14FFFFFF, 0x38FFFFFF},
                    new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            if (hasFace) {
                face.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, faceTop, faceBottom, Shader.TileMode.CLAMP));
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (rect.isEmpty()) {
                return;
            }
            if (fades(style)) {
                if (backdrop == null || !backdrop.ready || backdrop.failed || !supported()
                        || !canvas.isHardwareAccelerated()) {
                    return;
                }
                int layer = canvas.saveLayer(rect, null);
                int[] src = backdrop.sourceLocation();
                host.getLocationInWindow(hostLoc);
                lastDx = hostLoc[0] - src[0];
                lastDy = hostLoc[1] - src[1];
                int save = canvas.save();
                canvas.translate(-lastDx, -lastDy);
                canvas.scale(1f / SCALE, 1f / SCALE);
                Api31.draw(canvas, backdrop.node);
                canvas.restoreToCount(save);
                canvas.drawRect(rect, fadeMask);
                canvas.restoreToCount(layer);
                return;
            }
            if (backdrop != null && backdrop.ready && !backdrop.failed && supported()
                    && canvas.isHardwareAccelerated()) {
                int[] src = backdrop.sourceLocation();
                host.getLocationInWindow(hostLoc);
                lastDx = hostLoc[0] - src[0];
                lastDy = hostLoc[1] - src[1];
                int save = canvas.save();
                canvas.translate(-lastDx, -lastDy);
                canvas.scale(1f / SCALE, 1f / SCALE);
                Api31.draw(canvas, backdrop.node);
                canvas.restoreToCount(save);
                if (style == STYLE_PANEL && !frost) {
                    // A deeper panel: darken a touch for legibility
                    canvas.drawColor(0x33000000);
                }
            }
            if (frost) {
                if (!hasFace) {
                    canvas.drawRoundRect(rect, radius, radius, veil);
                }
                canvas.drawRoundRect(rect, radius, radius, hasFace ? face : tint);
                canvas.drawRoundRect(rect, radius, radius, noise);
                canvas.drawRoundRect(rimRect, radius, radius, rim);
                return;
            }
            canvas.drawRoundRect(rect, radius, radius, hasFace ? face : tint);
            canvas.drawRoundRect(rect, radius, radius, sheen);
            canvas.drawRoundRect(rimRect, radius, radius, rim);
        }

        @Override
        public void getOutline(@NonNull Outline outline) {
            outline.setRoundRect(getBounds(), radius);
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            tint.setAlpha(alpha);
            face.setAlpha(alpha);
            sheen.setAlpha(alpha);
            rim.setAlpha(alpha);
            veil.setAlpha(Math.round(veilBase * alpha / 255f));
            noise.setAlpha(frost ? Math.round(alpha * 0.4f) : alpha);
            invalidateSelf();
        }

        @Override
        public int getAlpha() {
            return alpha;
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private static final class Api31 {
        static Object createNode(float blurPx, boolean frost) {
            RenderNode node = new RenderNode("PocketGlass");
            ColorMatrix vibrancy = new ColorMatrix();
            vibrancy.setSaturation(frost ? 1.3f : 1.45f);
            ColorMatrix lift = new ColorMatrix();
            float l = frost ? 1f : 1.08f;
            lift.setScale(l, l, l, 1f);
            vibrancy.postConcat(lift);
            node.setRenderEffect(RenderEffect.createChainEffect(
                    RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(vibrancy)),
                    RenderEffect.createBlurEffect(Math.max(1f, blurPx), Math.max(1f, blurPx), Shader.TileMode.CLAMP)));
            return node;
        }

        static void record(Object target, View source, int w, int h, float scale) {
            RenderNode node = (RenderNode) target;
            int sw = Math.max(1, Math.round(w * scale));
            int sh = Math.max(1, Math.round(h * scale));
            node.setPosition(0, 0, sw, sh);
            RecordingCanvas canvas = node.beginRecording(sw, sh);
            try {
                canvas.scale(sw / (float) w, sh / (float) h);
                source.draw(canvas);
            } finally {
                node.endRecording();
            }
        }

        static void draw(Canvas canvas, Object node) {
            canvas.drawRenderNode((RenderNode) node);
        }
    }
}
