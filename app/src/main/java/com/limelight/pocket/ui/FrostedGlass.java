package com.limelight.pocket.ui;

import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.widget.ImageView;

/**
 * Real frosted glass over the stream. The game picture lives in a SurfaceView that normal view
 * blur can't reach, so menus take a small snapshot of it (PixelCopy), and show it blurred,
 * dimmed and slightly desaturated behind their glass.
 */
public final class FrostedGlass {
    private FrostedGlass() {}

    public interface Callback {
        void onSnapshot(Bitmap bitmap);
    }

    /** A low-resolution copy of the current stream frame (null on failure). Main thread callback. */
    public static void snapshot(SurfaceView view, final Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0
                || view.getHolder() == null || view.getHolder().getSurface() == null
                || !view.getHolder().getSurface().isValid()) {
            main.post(() -> callback.onSnapshot(null));
            return;
        }
        int w = Math.max(16, view.getWidth() / 6);
        int h = Math.max(16, view.getHeight() / 6);
        final Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            main.post(() -> callback.onSnapshot(null));
            return;
        }
        try {
            PixelCopy.request(view, bitmap, result -> callback.onSnapshot(result == PixelCopy.SUCCESS ? bitmap : null), main);
        } catch (RuntimeException e) {
            main.post(() -> callback.onSnapshot(null));
        }
    }

    /** Something that says whether a live glass should keep updating. */
    public interface Condition {
        boolean get();
    }

    /**
     * Live frosted glass: re-copies the stream picture about 20 times a second (at an eighth of
     * the size, so it's cheap) while the menu is open, so the game keeps moving behind the glass.
     */
    public static final class Live {
        private final SurfaceView view;
        private final Callback callback;
        private final Condition keepGoing;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Bitmap[] buffers = new Bitmap[2];
        private int next;
        private boolean running;
        private final Runnable capture = this::capture;

        public Live(SurfaceView view, Condition keepGoing, Callback callback) {
            this.view = view;
            this.keepGoing = keepGoing;
            this.callback = callback;
        }

        public void start() {
            if (running || view == null) {
                return;
            }
            running = true;
            capture();
        }

        public void stop() {
            running = false;
            main.removeCallbacks(capture);
        }

        private void capture() {
            if (!running) {
                return;
            }
            if (!keepGoing.get() || view.getWidth() <= 0 || view.getHolder() == null
                    || view.getHolder().getSurface() == null || !view.getHolder().getSurface().isValid()) {
                stop();
                return;
            }
            int w = Math.max(16, view.getWidth() / 8);
            int h = Math.max(16, view.getHeight() / 8);
            Bitmap target = buffers[next];
            if (target == null || target.getWidth() != w || target.getHeight() != h) {
                try {
                    target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                } catch (OutOfMemoryError e) {
                    stop();
                    return;
                }
                buffers[next] = target;
            }
            final Bitmap filled = target;
            try {
                PixelCopy.request(view, filled, result -> {
                    if (!running) {
                        return;
                    }
                    if (result == PixelCopy.SUCCESS && keepGoing.get()) {
                        callback.onSnapshot(filled);
                        next ^= 1;
                    }
                    main.postDelayed(capture, 50);
                }, main);
            } catch (RuntimeException e) {
                stop();
            }
        }
    }

    /** Like apply(), but for a live feed: the first frame fades in, later ones just replace it. */
    public static void update(ImageView image, Bitmap bitmap, float brightness) {
        if (bitmap == null) {
            return;
        }
        if (image.getDrawable() == null) {
            apply(image, bitmap, brightness);
        }
        else {
            image.setImageBitmap(bitmap);
        }
    }

    /** Shows a snapshot as frosted glass in the ImageView (blur + dim + desaturate), fading in. */
    public static void apply(ImageView image, Bitmap bitmap, float brightness) {
        if (bitmap == null) {
            image.setImageDrawable(null);
            return;
        }
        image.setImageBitmap(bitmap);
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(0.82f);
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(brightness, brightness, brightness, 1f);
        sat.postConcat(dim);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            float radius = 26f * image.getResources().getDisplayMetrics().density;
            image.setRenderEffect(RenderEffect.createChainEffect(
                    RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(sat)),
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)));
        }
        else {
            image.setColorFilter(new ColorMatrixColorFilter(sat));
        }
        image.setAlpha(0f);
        image.animate().alpha(1f).setDuration(220).start();
    }
}
