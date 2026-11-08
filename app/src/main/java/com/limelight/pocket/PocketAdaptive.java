package com.limelight.pocket;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

/**
 * Adaptive stream: picks a starting bitrate that the current Wi-Fi link can actually carry.
 * (Lowering the quality during a stream is done by Game, which reconnects at a lower bitrate
 * while the game keeps running on the PC; the result is remembered here for a while.)
 */
public final class PocketAdaptive {
    private PocketAdaptive() {}

    public static final int MIN_KBPS = 5000;

    /** The bitrate to start a Wi-Fi stream with, never above what the player chose. */
    public static int startBitrate(Context context, int configuredKbps) {
        if (!PocketPrefs.adaptiveStream(context)) {
            return configuredKbps;
        }
        int result = configuredKbps;
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            @SuppressWarnings("deprecation")
            WifiInfo info = wifi != null ? wifi.getConnectionInfo() : null;
            if (info != null && info.getNetworkId() != -1) {
                int linkMbps = info.getLinkSpeed();
                int rssi = info.getRssi();
                if (linkMbps > 0) {
                    // Real throughput is about half the link rate, and the stream needs headroom
                    result = Math.min(result, linkMbps * 1000 * 45 / 100);
                }
                if (rssi < -75) {
                    result = Math.min(result, 20000);
                }
                else if (rssi < -67) {
                    result = Math.min(result, 40000);
                }
            }
        } catch (RuntimeException ignored) {
        }
        int learned = PocketPrefs.adaptiveCapKbps(context);
        if (learned > 0) {
            result = Math.min(result, learned);
        }
        return Math.max(Math.min(MIN_KBPS, configuredKbps), Math.min(configuredKbps, result));
    }

    /** One step down from the current bitrate (about two thirds), or the same if at the floor. */
    public static int stepDown(int currentKbps) {
        int next = (int) (currentKbps * 0.65f) / 1000 * 1000;
        return Math.max(MIN_KBPS, next);
    }
}
