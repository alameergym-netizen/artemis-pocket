package com.limelight.pocket;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Per-game settings (one of the most requested Moonlight features): bitrate, frame rate,
 * resolution and frame generation can be set for one game and override the global settings.
 * 0 / "" / FRAME_GEN_DEFAULT mean "use the normal setting".
 */
public final class GameProfile {
    private GameProfile() {}

    public static final int FRAME_GEN_DEFAULT = 0;
    public static final int FRAME_GEN_ON = 1;
    public static final int FRAME_GEN_OFF = 2;
    public static final int FRAME_GEN_LSFG = 3;
    public static final int FRAME_GEN_MODES = 4;

    public static final int[] BITRATES_MBPS = {0, 10, 15, 20, 30, 40, 50, 60, 80, 100, 120, 150};
    public static final int[] FPS = {0, 30, 45, 60, 90, 120};
    public static final String[] RESOLUTIONS = {"", "1280x720", "1600x900", "1920x1080", "2560x1440"};

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("pocket_game_profiles", Context.MODE_PRIVATE);
    }

    private static String key(String pcUuid, int appId, String field) {
        return pcUuid + "_" + appId + "_" + field;
    }

    public static int bitrateKbps(Context c, String pcUuid, int appId) {
        return prefs(c).getInt(key(pcUuid, appId, "bitrate"), 0);
    }

    public static int fps(Context c, String pcUuid, int appId) {
        return prefs(c).getInt(key(pcUuid, appId, "fps"), 0);
    }

    public static String resolution(Context c, String pcUuid, int appId) {
        return prefs(c).getString(key(pcUuid, appId, "res"), "");
    }

    public static int frameGen(Context c, String pcUuid, int appId) {
        return prefs(c).getInt(key(pcUuid, appId, "framegen"), FRAME_GEN_DEFAULT);
    }

    public static void setBitrateKbps(Context c, String pcUuid, int appId, int kbps) {
        prefs(c).edit().putInt(key(pcUuid, appId, "bitrate"), kbps).apply();
    }

    public static void setFps(Context c, String pcUuid, int appId, int fps) {
        prefs(c).edit().putInt(key(pcUuid, appId, "fps"), fps).apply();
    }

    public static void setResolution(Context c, String pcUuid, int appId, String res) {
        prefs(c).edit().putString(key(pcUuid, appId, "res"), res).apply();
    }

    public static void setFrameGen(Context c, String pcUuid, int appId, int mode) {
        prefs(c).edit().putInt(key(pcUuid, appId, "framegen"), mode).apply();
    }

    public static void clear(Context c, String pcUuid, int appId) {
        prefs(c).edit()
                .remove(key(pcUuid, appId, "bitrate"))
                .remove(key(pcUuid, appId, "fps"))
                .remove(key(pcUuid, appId, "res"))
                .remove(key(pcUuid, appId, "framegen"))
                .apply();
    }

    public static boolean hasAny(Context c, String pcUuid, int appId) {
        return bitrateKbps(c, pcUuid, appId) > 0 || fps(c, pcUuid, appId) > 0
                || !resolution(c, pcUuid, appId).isEmpty() || frameGen(c, pcUuid, appId) != FRAME_GEN_DEFAULT;
    }

    /** Width and height of a "1920x1080" value, or null. */
    public static int[] parseResolution(String res) {
        if (res == null) {
            return null;
        }
        String[] parts = res.split("x");
        if (parts.length != 2) {
            return null;
        }
        try {
            int w = Integer.parseInt(parts[0]);
            int h = Integer.parseInt(parts[1]);
            return w > 0 && h > 0 ? new int[]{w, h} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "1080p · 90 FPS · 60 Mbps" style summary of what is customised, or "". */
    public static String summary(Context c, String pcUuid, int appId) {
        StringBuilder sb = new StringBuilder();
        int[] res = parseResolution(resolution(c, pcUuid, appId));
        if (res != null) {
            sb.append(res[1]).append('p');
        }
        int fps = fps(c, pcUuid, appId);
        if (fps > 0) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append(fps).append(" FPS");
        }
        int kbps = bitrateKbps(c, pcUuid, appId);
        if (kbps > 0) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append(kbps / 1000).append(" Mbps");
        }
        int fg = frameGen(c, pcUuid, appId);
        if (fg != FRAME_GEN_DEFAULT) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append(fg == FRAME_GEN_ON ? "FG 2×" : fg == FRAME_GEN_LSFG ? "LSFG" : "FG off");
        }
        return sb.toString();
    }

    /** Index of value in values, or 0 (default). */
    public static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return 0;
    }

    public static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }
}
