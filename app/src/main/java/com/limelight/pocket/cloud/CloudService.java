package com.limelight.pocket.cloud;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import com.limelight.R;

import java.util.List;

/**
 * A cloud gaming service with a built-in client: its site, run full screen in a browser of its
 * own (its own process and task, so each keeps playing and stays signed in by itself), and its
 * official Android app when that's installed.
 */
public final class CloudService {
    public final String id;
    public final int name;
    public final int about;
    public final String url;
    /** The official apps, most wanted first. */
    public final String[] officialPackages;
    public final Class<? extends CloudPlayerActivity> player;
    public final int color;

    private CloudService(String id, int name, int about, String url, String[] officialPackages,
                         Class<? extends CloudPlayerActivity> player, int color) {
        this.id = id;
        this.name = name;
        this.about = about;
        this.url = url;
        this.officialPackages = officialPackages;
        this.player = player;
        this.color = color;
    }

    public static final CloudService XCLOUD = new CloudService("xcloud", R.string.pocket_cloud_xcloud,
            R.string.pocket_cloud_xcloud_about, "https://www.xbox.com/play",
            new String[] {"com.gamepass", "com.microsoft.xboxone.smartglass"}, CloudPlayerActivity.Xcloud.class, 0xFF107C10);
    public static final CloudService GFN = new CloudService("gfn", R.string.pocket_cloud_gfn,
            R.string.pocket_cloud_gfn_about, "https://play.geforcenow.com",
            new String[] {"com.nvidia.geforcenow"}, CloudPlayerActivity.Gfn.class, 0xFF5E9A00);
    public static final CloudService BOOSTEROID = new CloudService("boosteroid", R.string.pocket_cloud_boosteroid,
            R.string.pocket_cloud_boosteroid_about, "https://cloud.boosteroid.com",
            new String[] {"com.boosteroid.streaming"}, CloudPlayerActivity.Boosteroid.class, 0xFF5B3CC4);

    public static final CloudService[] ALL = {XCLOUD, GFN, BOOSTEROID};

    public static CloudService byId(String id) {
        for (CloudService s : ALL) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return null;
    }

    /** Its process name (each client runs in one of its own). */
    public String processName(Context context) {
        return context.getPackageName() + ":" + id;
    }

    /** True while its built-in client is running (in the background or not). */
    public boolean isRunning(Context context) {
        return pid(context) > 0;
    }

    int pid(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return 0;
        }
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs == null) {
            return 0;
        }
        String want = processName(context);
        for (ActivityManager.RunningAppProcessInfo p : procs) {
            if (want.equals(p.processName)) {
                return p.pid;
            }
        }
        return 0;
    }

    /** Ends its built-in client (its process). */
    public void stop(Context context) {
        int pid = pid(context);
        if (pid > 0) {
            android.os.Process.killProcess(pid);
        }
    }

    /** The official app installed, or null. */
    public String installedOfficial(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String p : officialPackages) {
            if (pm.getLaunchIntentForPackage(p) != null) {
                return p;
            }
        }
        return null;
    }

    /** Opens the built-in client (back to where it was when it's running). */
    public Intent playerIntent(Context context) {
        return new Intent(context, player).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
