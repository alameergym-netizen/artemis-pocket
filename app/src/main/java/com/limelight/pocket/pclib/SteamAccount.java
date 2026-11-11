package com.limelight.pocket.pclib;

import android.content.Context;

import com.limelight.R;
import com.limelight.pocket.PocketPrefs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The linked Steam account: its games (owned, playtime) through the Steam Web API, with the
 * person's own Web API key. Cached on the handheld for a few hours.
 */
public final class SteamAccount {
    private SteamAccount() {}

    private static final String KEY_ID = "pocket_steam_id";
    private static final String KEY_API = "pocket_steam_api_key";
    private static final String KEY_NAME = "pocket_steam_name";
    private static final long FRESH_MS = 6L * 60 * 60 * 1000;
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    public static String steamId(Context c) {
        return PocketPrefs.prefs(c).getString(KEY_ID, "");
    }

    public static String apiKey(Context c) {
        return PocketPrefs.prefs(c).getString(KEY_API, "");
    }

    /** The account's display name ("" when unknown). */
    public static String name(Context c) {
        return PocketPrefs.prefs(c).getString(KEY_NAME, "");
    }

    public static boolean linked(Context c) {
        return !steamId(c).isEmpty() && !apiKey(c).isEmpty();
    }

    public static void unlink(Context c) {
        PocketPrefs.prefs(c).edit().remove(KEY_ID).remove(KEY_API).remove(KEY_NAME).apply();
        File dir = cacheDir(c);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /**
     * Checks the account and the key, and saves them (blocking: never on the main thread).
     * Returns 0 when linked, else a string resource saying what's wrong.
     */
    public static int link(Context c, String account, String key) {
        String k = key == null ? "" : key.trim();
        if (!SteamParsers.looksLikeApiKey(k)) {
            return R.string.pocket_steam_err_key;
        }
        String id = SteamParsers.accountInput(account);
        if (id == null) {
            return R.string.pocket_steam_err_account;
        }
        try {
            if (id.startsWith("vanity:")) {
                id = SteamParsers.vanityResult(http("https://api.steampowered.com/ISteamUser/ResolveVanityURL/v1/?key="
                        + enc(k) + "&vanityurl=" + enc(id.substring(7))));
                if (id == null) {
                    return R.string.pocket_steam_err_account;
                }
            }
            String owned = http(ownedUrl(k, id));
            if (SteamParsers.ownedGames(owned) == null) {
                return R.string.pocket_steam_err_private;
            }
            String name = "";
            try {
                name = SteamParsers.personaName(http("https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/?key="
                        + enc(k) + "&steamids=" + enc(id)));
            } catch (IOException ignored) {
            }
            PocketPrefs.prefs(c).edit().putString(KEY_ID, id).putString(KEY_API, k).putString(KEY_NAME, name).apply();
            write(cacheFile(c, id), owned);
            return 0;
        } catch (IOException e) {
            return e.getMessage() != null && e.getMessage().contains("403")
                    ? R.string.pocket_steam_err_key : R.string.pocket_steam_err_network;
        }
    }

    /**
     * The account's games (blocking), from the cache while it's fresh (refresh: ask Steam now).
     * Null when no account is linked or Steam couldn't be reached and nothing was cached.
     */
    public static List<PcGame> owned(Context c, boolean refresh) {
        if (!linked(c)) {
            return null;
        }
        String id = steamId(c);
        File f = cacheFile(c, id);
        boolean fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < FRESH_MS;
        if (!fresh || refresh) {
            try {
                String json = http(ownedUrl(apiKey(c), id));
                if (SteamParsers.ownedGames(json) != null) {
                    write(f, json);
                }
            } catch (IOException ignored) {
                // Offline: what was cached still helps
            }
        }
        String cached = read(f);
        return cached != null ? SteamParsers.ownedGames(cached) : null;
    }

    private static String ownedUrl(String key, String id) {
        return "https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/?key=" + enc(key) + "&steamid=" + enc(id)
                + "&include_appinfo=1&include_played_free_games=1&format=json";
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (IOException e) {
            return s;
        }
    }

    private static String http(String url) throws IOException {
        Request request = new Request.Builder().url(url)
                .header("User-Agent", "PocketNexus/1.0 (game streaming client)")
                .build();
        try (Response response = CLIENT.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("HTTP " + response.code());
            }
            byte[] bytes = body.bytes();
            if (bytes.length > MAX_BYTES) {
                throw new IOException("Too big");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static File cacheDir(Context c) {
        File dir = new File(c.getApplicationContext().getFilesDir(), "pocket_pclib");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private static File cacheFile(Context c, String id) {
        return new File(cacheDir(c), "owned_" + id.replaceAll("[^0-9]", "") + ".json");
    }

    private static void write(File f, String text) {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    private static String read(File f) {
        if (!f.exists() || f.length() > MAX_BYTES) {
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            byte[] bytes = new byte[(int) f.length()];
            int off = 0;
            while (off < bytes.length) {
                int n = in.read(bytes, off, bytes.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            return new String(bytes, 0, off, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
