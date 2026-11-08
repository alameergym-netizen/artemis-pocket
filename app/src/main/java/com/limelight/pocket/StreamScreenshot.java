package com.limelight.pocket;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.PixelCopy;
import android.view.SurfaceView;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Saves the current stream picture to the handheld's gallery (Pictures/Artemis Pocket). */
public final class StreamScreenshot {
    private StreamScreenshot() {}

    public interface Callback {
        /** true when saved. Called on the main thread. */
        void onDone(boolean saved);
    }

    public static void capture(final Context context, SurfaceView view, final String gameName, final Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || view == null
                || view.getWidth() <= 0 || view.getHeight() <= 0 || !view.getHolder().getSurface().isValid()) {
            main.post(() -> callback.onDone(false));
            return;
        }
        final Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            main.post(() -> callback.onDone(false));
            return;
        }
        final HandlerThread worker = new HandlerThread("PocketScreenshot");
        worker.start();
        final Handler workerHandler = new Handler(worker.getLooper());
        final Context app = context.getApplicationContext();
        try {
            PixelCopy.request(view, bitmap, result -> {
                boolean saved = result == PixelCopy.SUCCESS && save(app, bitmap, gameName);
                bitmap.recycle();
                worker.quitSafely();
                main.post(() -> callback.onDone(saved));
            }, workerHandler);
        } catch (RuntimeException e) {
            worker.quitSafely();
            main.post(() -> callback.onDone(false));
        }
    }

    private static boolean save(Context context, Bitmap bitmap, String gameName) {
        String safe = gameName == null ? "Stream" : gameName.replaceAll("[^\\p{L}\\p{N} _-]", "").trim();
        if (safe.isEmpty()) {
            safe = "Stream";
        }
        String name = safe + " " + new SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(new Date()) + ".png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Artemis Pocket");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        ContentResolver resolver = context.getContentResolver();
        Uri uri = null;
        try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return false;
            }
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new java.io.IOException("write failed");
                }
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return true;
        } catch (Exception e) {
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (RuntimeException ignored) {
                }
            }
            return false;
        }
    }
}
