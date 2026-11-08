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
