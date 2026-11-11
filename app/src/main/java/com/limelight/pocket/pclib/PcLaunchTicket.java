package com.limelight.pocket.pclib;

import android.os.SystemClock;

/**
 * A game from the PC library that Apollo has no entry for: the stream starts with Apollo's
 * Desktop, and once it's up the stream asks Pocket Nexus PC to start the game, so it opens on
 * the screen being streamed. Kept in memory for a few minutes.
 */
public final class PcLaunchTicket {
    private PcLaunchTicket() {}

    private static final long VALID_MS = 3 * 60 * 1000;

    private static String uuid;
    private static int appId;
    private static String command;
    private static String name;
    private static long at;

    /** viaAppId: the Apollo app the stream starts with (Desktop); only that stream picks it up. */
    public static synchronized void arm(String pcUuid, int viaAppId, String launchCommand, String gameName) {
        uuid = pcUuid;
        appId = viaAppId;
        command = launchCommand;
        name = gameName;
        at = SystemClock.elapsedRealtime();
    }

    /** {command, game name} for a stream of this PC and app, if one is waiting (it's used up). */
    public static synchronized String[] take(String pcUuid, int streamAppId) {
        if (command == null || pcUuid == null || !pcUuid.equals(uuid)
                || SystemClock.elapsedRealtime() - at > VALID_MS) {
            return null;
        }
        if (streamAppId != appId) {
            // Something else was started in the meantime: this wish is stale
            command = null;
            return null;
        }
        String[] out = {command, name};
        command = null;
        name = null;
        uuid = null;
        return out;
    }
}
