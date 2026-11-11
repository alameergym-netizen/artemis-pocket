package com.limelight.pocket.info;

import android.content.Context;

import com.limelight.pocket.PocketPrefs;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * IGDB (Twitch's game database): descriptions, release dates, studios, screenshots, artworks
 * and trailers for any game, Steam or not. It needs the person's own free Twitch app (client
 * ID and secret from dev.twitch.tv); the access token is kept until it runs out.
 */
public final class Igdb {
    private Igdb() {}

    private static final String KEY_ID = "pocket_igdb_client_id";
    private static final String KEY_SECRET = "pocket_igdb_client_secret";
    private static final String KEY_TOKEN = "pocket_igdb_token";
    private static final String KEY_TOKEN_UNTIL = "pocket_igdb_token_until";
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    public static boolean configured(Context c) {
        return !clientId(c).isEmpty() && !PocketPrefs.prefs(c).getString(KEY_SECRET, "").isEmpty();
    }

    public static String clientId(Context c) {
        return PocketPrefs.prefs(c).getString(KEY_ID, "");
    }

    public static void setCredentials(Context c, String id, String secret) {
        PocketPrefs.prefs(c).edit()
                .putString(KEY_ID, id == null ? "" : id.trim())
                .putString(KEY_SECRET, secret == null ? "" : secret.trim())
                .remove(KEY_TOKEN).remove(KEY_TOKEN_UNTIL)
                .apply();
    }

    /** A token (blocking): the kept one while it lasts, else a new one from Twitch. */
    private static String token(Context c, boolean fresh) throws IOException {
        String kept = PocketPrefs.prefs(c).getString(KEY_TOKEN, "");
        long until = PocketPrefs.prefs(c).getLong(KEY_TOKEN_UNTIL, 0);
        if (!fresh && !kept.isEmpty() && System.currentTimeMillis() < until) {
            return kept;
        }
        String url = "https://id.twitch.tv/oauth2/token?client_id=" + enc(clientId(c))
                + "&client_secret=" + enc(PocketPrefs.prefs(c).getString(KEY_SECRET, ""))
                + "&grant_type=client_credentials";
        Request request = new Request.Builder().url(url)
                .post(RequestBody.create("", MediaType.get("application/x-www-form-urlencoded")))
                .build();
        String[] t = ExtraParsers.twitchToken(call(request));
        if (t == null) {
            throw new IOException("No token");
        }
        long seconds = Math.max(60, Long.parseLong(t[1]));
        PocketPrefs.prefs(c).edit().putString(KEY_TOKEN, t[0])
                .putLong(KEY_TOKEN_UNTIL, System.currentTimeMillis() + (seconds - 3600) * 1000L).apply();
        return t[0];
    }

    /** IGDB's games for a name (the raw JSON, blocking). Null when IGDB isn't set up. */
    public static String search(Context c, String name) throws IOException {
        if (!configured(c)) {
            return null;
        }
        try {
            return query(c, name, token(c, false));
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("401")) {
                // The token ran out early: once more with a new one
                return query(c, name, token(c, true));
            }
            throw e;
        }
    }

    /** Checks the client ID and secret (blocking): true when Twitch gives a token. */
    public static boolean check(Context c) {
        try {
            return token(c, true) != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static String query(Context c, String name, String token) throws IOException {
        Request request = new Request.Builder().url("https://api.igdb.com/v4/games")
                .header("Client-ID", clientId(c))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .post(RequestBody.create(ExtraParsers.igdbQuery(name), MediaType.get("text/plain")))
                .build();
        return call(request);
    }

    private static String call(Request request) throws IOException {
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

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (IOException e) {
            return s;
        }
    }
}
