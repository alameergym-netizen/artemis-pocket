package com.limelight.pocket.pclib;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.limelight.nvstream.http.NvApp;
import com.limelight.pocket.PcCompanion;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.RecentGames;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads a PC's game library: what Pocket Nexus PC finds on the PC (Steam, Epic, GOG) plus the
 * linked Steam account's games, and sends it the library's commands (start, install).
 */
public final class PcLibrary {
    private PcLibrary() {}

    public static final int OK = 0;
    /** Pocket Nexus PC didn't answer (not running, or the firewall). */
    public static final int NO_HELPER = 1;
    /** It answered, but this handheld isn't linked to it yet (PIN). */
    public static final int NOT_LINKED = 2;
    /** It's an older version without the library. */
    public static final int OLD_HELPER = 3;

    public static final class Result {
        public final int status;
        public final List<PcGame> games;

        Result(int status, List<PcGame> games) {
            this.status = status;
            this.games = games;
        }
    }

    public interface Callback {
        /** On the main thread. */
        void onLoaded(Result result);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, List<PcGame>> LAST = new HashMap<>();

    /** The list shown last for this PC (to show at once while a fresh one loads), or null. */
    public static synchronized List<PcGame> cached(String uuid) {
        List<PcGame> l = LAST.get(uuid);
        return l != null ? new ArrayList<>(l) : null;
    }

    private static synchronized void remember(String uuid, List<PcGame> games) {
        LAST.put(uuid, new ArrayList<>(games));
    }

    public static void load(Context context, final String host, final String uuid, final boolean refreshAccount,
                            final Callback callback) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            final Result result = loadNow(app, host, uuid, refreshAccount);
            if (result.status == OK) {
                remember(uuid, result.games);
            }
            MAIN.post(() -> callback.onLoaded(result));
        }, "PocketPcLibrary").start();
    }

    private static Result loadNow(Context context, String host, String uuid, boolean refreshAccount) {
        List<String> lines = new ArrayList<>();
        int status = OK;
        PcCompanion.Session session = PcCompanion.open(context, host, uuid);
        if (session == null) {
            status = NO_HELPER;
        }
        else {
            PocketPrefs.markCompanionSeen(context, uuid);
            try {
                if (!session.authed) {
                    status = NOT_LINKED;
                }
                else {
                    // A big library on a slow disk takes a moment to read
                    session.setReadTimeout(25000);
                    session.send("GAMES");
                    for (int i = 0; i < 20000; i++) {
                        String line = session.readLine();
                        if (line == null || line.startsWith("END")) {
                            break;
                        }
                        if (line.startsWith("ERR")) {
                            status = line.startsWith("ERR auth") ? NOT_LINKED : OLD_HELPER;
                            break;
                        }
                        lines.add(line);
                    }
                }
            } catch (IOException e) {
                status = NO_HELPER;
            } finally {
                session.close();
            }
        }
        List<PcGame> onPc = PcGame.parseAll(lines);
        List<PcGame> owned = SteamAccount.owned(context, refreshAccount);
        List<PcGame> all = PcGame.merge(onPc, owned);
        PcGame.sort(all);
        // Without the helper, the account's games still show (they just can't start or install)
        return new Result(status, all);
    }

    /** Sends one command to Pocket Nexus PC (reply on the main thread, null if unreachable). */
    public static void send(Context context, String host, String uuid, String command, PcCompanion.Callback callback) {
        PcCompanion.runAsync(context, host, uuid, command, callback);
    }

    /** The Apollo app that is this game (same name), else null. */
    public static NvApp apolloAppFor(Context context, String uuid, PcGame game) {
        String want = PcGame.normalize(game.name);
        if (want.isEmpty()) {
            return null;
        }
        for (RecentGames.Game g : RecentGames.forPc(context, uuid, 100000)) {
            if (want.equals(PcGame.normalize(g.app.getAppName()))) {
                return g.app;
            }
        }
        return null;
    }

    /** Apollo's "Desktop" app (the whole PC screen), else null. */
    public static NvApp desktopApp(Context context, String uuid) {
        NvApp loose = null;
        for (RecentGames.Game g : RecentGames.forPc(context, uuid, 100000)) {
            String n = PcGame.normalize(g.app.getAppName());
            if (n.equals("desktop")) {
                return g.app;
            }
            if (loose == null && n.contains("desktop")) {
                loose = g.app;
            }
        }
        return loose;
    }
}
