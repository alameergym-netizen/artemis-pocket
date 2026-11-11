package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Pictures from the internet (screenshots, trailer thumbnails, store art) for the game page:
 * downloaded once into a size-capped disk cache, decoded at the size they are shown, made ready
 * for the GPU off the main thread (HeroArt.prepare) and kept in memory while in use.
 */
public final class RemoteImages {

    public interface Callback {
        /** On the main thread; null when it couldn't be loaded. */
        void onImage(Bitmap bitmap);
    }

    private static final long DISK_LIMIT = 160L * 1024 * 1024;
    private static final int MAX_BYTES = 12 * 1024 * 1024;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(40 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static boolean trimmed;

    private RemoteImages() {
    }

    private static String key(String url, int width) {
        return width + "@" + url;
    }

    /** Already in memory at this width, or null (never blocks). */
    public static Bitmap cached(String url, int width) {
        if (url == null) {
            return null;
        }
        synchronized (MEMORY) {
            return MEMORY.get(key(url, width));
        }
    }

    /** Loads the picture about this wide (px), from memory, disk or the internet. */
    public static void load(Context context, final String url, final int width, final Callback callback) {
        if (url == null) {
            callback.onImage(null);
            return;
        }
        Bitmap hit = cached(url, width);
        if (hit != null) {
            callback.onImage(hit);
            return;
        }
        final File dir = new File(context.getApplicationContext().getCacheDir(), "pocket_remote");
        IO.execute(() -> {
            Bitmap bitmap = null;
            try {
                synchronized (MEMORY) {
                    bitmap = MEMORY.get(key(url, width));
                }
                if (bitmap == null) {
                    File f = new File(dir, sha1(url));
                    if (!f.exists()) {
                        download(url, f);
                    }
                    else {
                        f.setLastModified(System.currentTimeMillis());
                    }
                    bitmap = decode(f, width);
                    if (bitmap != null) {
                        bitmap = HeroArt.prepare(bitmap, true);
                        synchronized (MEMORY) {
                            MEMORY.put(key(url, width), bitmap);
                        }
                    }
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                bitmap = null;
            }
            if (!trimmed) {
                trimmed = true;
                trim(dir);
            }
            final Bitmap result = bitmap;
            MAIN.post(() -> callback.onImage(result));
        });
    }

    private static void download(String url, File target) throws IOException {
        File dir = target.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("Can't create cache");
        }
        Request request = new Request.Builder().url(url)
                .header("User-Agent", "ArtemisPocket/1.0").build();
        try (Response response = CLIENT.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("HTTP " + response.code());
            }
            byte[] bytes = body.bytes();
            if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                throw new IOException("Bad size");
            }
            File tmp = new File(target.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            if (!tmp.renameTo(target)) {
                tmp.delete();
                throw new IOException("Can't keep");
            }
        }
    }

    private static Bitmap decode(File f, int width) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0) {
            f.delete();
            return null;
        }
        int sample = 1;
        while (width > 0 && bounds.outWidth / (sample * 2) >= width) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), opts);
    }

    /** Keeps the disk cache under its limit (oldest used go first). */
    private static void trim(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long total = 0;
        for (File f : files) {
            total += f.length();
        }
        if (total <= DISK_LIMIT) {
            return;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File f : files) {
            if (total <= DISK_LIMIT * 3 / 4) {
                break;
            }
            total -= f.length();
            f.delete();
        }
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) {
                b.append(String.format(Locale.ROOT, "%02x", x));
            }
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
