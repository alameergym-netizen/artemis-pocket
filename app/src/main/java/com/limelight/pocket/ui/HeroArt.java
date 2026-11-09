package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import com.limelight.R;
import com.limelight.pocket.PocketPrefs;
import com.limelight.utils.CacheHelper;

import java.io.File;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Game art for the console screens: finds a game's cached box art on disk (small, cached in
 * memory), tells real cover art from plain placeholders (the grey "Desktop" monitor and the like,
 * which look like a smudge when blown up behind the screen), and cross-fades a blurred backdrop
 * between two layers.
 */
public final class HeroArt {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final Map<Bitmap, Boolean> PLACEHOLDER = new WeakHashMap<>();

    private final ImageView a;
    private final ImageView b;
    private boolean onA;
    private Bitmap shown;

    public HeroArt(View root) {
        a = root.findViewById(R.id.pocketHeroArtA);
        b = root.findViewById(R.id.pocketHeroArtB);
    }

    /** The cached box art of a game (scaled down for cards and backdrops), or null. */
    public static Bitmap load(Context context, String pcUuid, int appId) {
        if (pcUuid == null || appId == 0) {
            return null;
        }
        File file = CacheHelper.openPath(false, context.getCacheDir(), "boxart", pcUuid, appId + ".png");
        if (!file.exists()) {
            return null;
        }
        String key = file.getPath() + "@" + file.lastModified();
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 320) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), opts);
            if (bitmap != null) {
                CACHE.put(key, bitmap);
            }
            return bitmap;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * True for plain, colourless art (a default icon on grey): it makes a poor backdrop. Real
     * covers are colourful almost without exception.
     */
    public static boolean isPlaceholder(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return true;
        }
        Boolean known = PLACEHOLDER.get(bitmap);
        if (known != null) {
            return known;
        }
        boolean result = false;
        try {
            Bitmap source = bitmap;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.getConfig() == Bitmap.Config.HARDWARE) {
                source = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            }
            Bitmap small = Bitmap.createScaledBitmap(source, 24, 32, true);
            float[] hsv = new float[3];
            float saturation = 0;
            int n = 0;
            for (int y = 0; y < small.getHeight(); y++) {
                for (int x = 0; x < small.getWidth(); x++) {
                    int c = small.getPixel(x, y);
                    Color.colorToHSV(c, hsv);
                    // Near-black pixels have meaningless saturation
                    if (hsv[2] > 0.12f) {
                        saturation += hsv[1];
                        n++;
                    }
                }
            }
            result = n == 0 || saturation / n < 0.12f;
            if (small != source) {
                small.recycle();
            }
            if (source != bitmap) {
                source.recycle();
            }
        } catch (RuntimeException e) {
            result = false;
        }
        PLACEHOLDER.put(bitmap, result);
        return result;
    }

    /**
     * Cross-fades the backdrop to bitmap (blurred, black and white unless colour art is on), or
     * fades it away for null / placeholder art and when the backdrop setting isn't "art".
     */
    public void show(Bitmap bitmap) {
        if (a == null || b == null) {
            return;
        }
        Context context = a.getContext();
        if (bitmap != null && (isPlaceholder(bitmap) || !PocketPrefs.BACKDROP_ART.equals(PocketPrefs.backdrop(context)))) {
            bitmap = null;
        }
        if (bitmap == shown) {
            return;
        }
        shown = bitmap;
        boolean animate = PocketPrefs.animationsEnabled(context);
        ImageView incoming = onA ? b : a;
        ImageView outgoing = onA ? a : b;
        incoming.animate().cancel();
        outgoing.animate().cancel();
        if (bitmap == null) {
            if (animate) {
                a.animate().alpha(0f).setDuration(320).start();
                b.animate().alpha(0f).setDuration(320).start();
            }
            else {
                a.setAlpha(0f);
                b.setAlpha(0f);
            }
            return;
        }
        onA = !onA;
        incoming.setImageBitmap(bitmap);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            incoming.setRenderEffect(RenderEffect.createBlurEffect(42f, 42f, Shader.TileMode.CLAMP));
        }
        boolean color = PocketPrefs.colorBackgroundArt(context);
        if (color) {
            incoming.clearColorFilter();
        }
        else {
            ColorMatrix mono = new ColorMatrix();
            mono.setSaturation(0f);
            incoming.setColorFilter(new ColorMatrixColorFilter(mono));
        }
        float target = color ? 0.5f : 0.42f;
        incoming.setScaleX(1.08f);
        incoming.setScaleY(1.08f);
        if (animate) {
            incoming.animate().alpha(target).scaleX(1.04f).scaleY(1.04f).setDuration(420)
                    .setInterpolator(PocketUi.EASE).start();
            outgoing.animate().alpha(0f).setDuration(320).start();
        }
        else {
            incoming.setAlpha(target);
            outgoing.setAlpha(0f);
        }
    }
}
