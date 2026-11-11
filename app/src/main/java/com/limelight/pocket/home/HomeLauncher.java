package com.limelight.pocket.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.widget.Toast;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.nvstream.http.NvApp;
import com.limelight.pocket.PocketSounds;
import com.limelight.pocket.cloud.CloudService;
import com.limelight.pocket.pclib.PcLaunchTicket;
import com.limelight.pocket.pclib.PcLibrary;
import com.limelight.pocket.retro.EsdeConfig;
import com.limelight.pocket.retro.RetroLauncher;
import com.limelight.pocket.retro.RetroLibrary;
import com.limelight.pocket.retro.RomScanner;

import java.io.File;
import java.net.URISyntaxException;

/** Starts anything on Home, whatever kind it is. */
public final class HomeLauncher {
    private HomeLauncher() {}

    /** Apps that start a PC game by its store id (like ES-DE's commands for them). */
    public static final String GAMENATIVE = "gamenative";
    public static final String GAMEHUB_LITE = "gamehub";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Starts it (true when it went; a message says why not otherwise). */
    public static void launch(final Activity a, final HomeItem item) {
        switch (item.kind) {
            case HomeItem.PC:
                startOnPc(a, item.a, item.n);
                return;
            case HomeItem.PC_LIBRARY_GAME:
                startPcLibraryGame(a, item);
                return;
            case HomeItem.CLOUD: {
                CloudService s = CloudService.byId(item.a);
                if (s == null) {
                    return;
                }
                HomeStore.record(a, item);
                PocketSounds.play(a, PocketSounds.SELECT);
                a.startActivity(s.playerIntent(a));
                return;
            }
            case HomeItem.RETRO:
                startRetro(a, item);
                return;
            case HomeItem.ANDROID: {
                Intent launch = a.getPackageManager().getLaunchIntentForPackage(item.a);
                if (launch == null) {
                    Toast.makeText(a, R.string.pocket_home_missing, Toast.LENGTH_LONG).show();
                    return;
                }
                HomeStore.record(a, item);
                PocketSounds.play(a, PocketSounds.SELECT);
                a.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            case HomeItem.SHORTCUT:
                if (startShortcut(a, item)) {
                    HomeStore.record(a, item);
                    PocketSounds.play(a, PocketSounds.SELECT);
                }
                else {
                    Toast.makeText(a, R.string.pocket_home_shortcut_failed, Toast.LENGTH_LONG).show();
                }
                return;
            case HomeItem.NATIVE:
                if (startNative(a, item)) {
                    HomeStore.record(a, item);
                    PocketSounds.play(a, PocketSounds.SELECT);
                }
                return;
            default:
                break;
        }
    }

    /** A game of a PC: the Streaming screen starts it (it knows the PC's state, wakes and pairs). */
    public static void startOnPc(Activity a, String uuid, int appId) {
        PocketSounds.play(a, PocketSounds.SELECT);
        Intent i = new Intent(a, PcView.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(PcView.EXTRA_HOME_UUID, uuid)
                .putExtra(PcView.EXTRA_HOME_APP, appId);
        a.startActivity(i);
        a.overridePendingTransition(R.anim.pocket_section_in, R.anim.pocket_section_out);
    }

    private static void startPcLibraryGame(Activity a, HomeItem item) {
        NvApp app = PcLibrary.apolloAppFor(a, item.a, item.name);
        if (app != null) {
            HomeStore.record(a, item);
            startOnPc(a, item.a, app.getAppId());
            return;
        }
        NvApp desktop = PcLibrary.desktopApp(a, item.a);
        if (desktop == null) {
            Toast.makeText(a, R.string.pocket_pclib_no_desktop, Toast.LENGTH_LONG).show();
            return;
        }
        // The stream opens on the PC's desktop and asks the PC to start the game
        PcLaunchTicket.arm(item.a, desktop.getAppId(), item.b, item.name, item.n);
        HomeStore.record(a, item);
        startOnPc(a, item.a, desktop.getAppId());
    }

    private static void startRetro(final Activity a, final HomeItem item) {
        final Context app = a.getApplicationContext();
        new Thread(() -> {
            final EsdeConfig cfg = RetroLibrary.config(app);
            final File root = RetroLibrary.romRoot(app);
            MAIN.post(() -> {
                if (a.isFinishing()) {
                    return;
                }
                EsdeConfig.SystemDef s = cfg.system(item.a);
                if (s == null || root == null) {
                    Toast.makeText(a, R.string.pocket_home_missing, Toast.LENGTH_LONG).show();
                    return;
                }
                File dir = s.absolute ? new File(s.folder) : new File(root, s.folder);
                File file = new File(dir, item.b);
                RomScanner.Rom rom = new RomScanner.Rom(s.name, item.b, file, file.isDirectory());
                EsdeConfig.Command cmd = RetroLibrary.gameCommand(a, null, s, rom);
                RetroLauncher.Result r = RetroLauncher.launch(a, cfg, cmd, file, root);
                if (r.status == RetroLauncher.OK) {
                    PocketSounds.play(a, PocketSounds.SELECT);
                    RetroLibrary.markPlayed(a, rom);
                }
                else if (r.status == RetroLauncher.NOT_INSTALLED) {
                    Toast.makeText(a, a.getString(R.string.pocket_home_emulator_missing,
                            cmd != null ? cmd.shortLabel() : ""), Toast.LENGTH_LONG).show();
                }
                else if (r.status == RetroLauncher.NO_FILE) {
                    Toast.makeText(a, R.string.pocket_retro_file_gone, Toast.LENGTH_LONG).show();
                }
                else {
                    Toast.makeText(a, a.getString(R.string.pocket_retro_failed, String.valueOf(r.detail)), Toast.LENGTH_LONG).show();
                }
            });
        }, "PocketHomeRetro").start();
    }

    // ---- Shortcuts other apps made (Winlator, GameHub ...) -----------------------------------

    /** True when Android lets Pocket Nexus open other apps' shortcuts (it's the home app). */
    public static boolean canUseShortcuts(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return false;
        }
        try {
            LauncherApps la = (LauncherApps) c.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            return la != null && la.hasShortcutHostPermission();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean startShortcut(Activity a, HomeItem item) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 && !item.b.isEmpty() && canUseShortcuts(a)) {
            try {
                LauncherApps la = (LauncherApps) a.getSystemService(Context.LAUNCHER_APPS_SERVICE);
                la.startShortcut(item.a, item.b, null, null, Process.myUserHandle());
                return true;
            } catch (RuntimeException ignored) {
                // Removed, or Pocket Nexus isn't the home app any more: try its intent
            }
        }
        if (!item.c.isEmpty()) {
            try {
                Intent i = Intent.parseUri(item.c, Intent.URI_INTENT_SCHEME);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(i);
                return true;
            } catch (URISyntaxException | RuntimeException ignored) {
            }
        }
        return false;
    }

    /** GameNative / GameHub Lite, started straight into a game by its id (ES-DE's commands). */
    private static boolean startNative(Activity a, HomeItem item) {
        Intent i = new Intent();
        ComponentName cn;
        if (GAMEHUB_LITE.equals(item.a)) {
            cn = RetroLauncher.component(a, RetroLibrary.config(a), "GAMEHUB-LITE");
            i.setAction("gamehub.lite.LAUNCH_GAME");
            i.putExtra("autoStartGame", true);
            i.putExtra("steamAppId", String.valueOf(item.n));
        }
        else {
            cn = RetroLauncher.component(a, RetroLibrary.config(a), "GAMENATIVE");
            i.setAction("app.gamenative.LAUNCH_GAME");
            i.putExtra("game_source", item.b.isEmpty() ? "STEAM" : item.b);
            i.putExtra("app_id", item.n);
        }
        if (cn == null) {
            Toast.makeText(a, a.getString(R.string.pocket_home_emulator_missing,
                    GAMEHUB_LITE.equals(item.a) ? "GameHub Lite" : "GameNative"), Toast.LENGTH_LONG).show();
            return false;
        }
        i.setComponent(cn);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            a.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(a, R.string.pocket_home_shortcut_failed, Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
