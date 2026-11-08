package com.limelight.pocket;

import android.content.Context;

import com.limelight.R;

/**
 * PC power actions. With Artemis Pocket PC running they go straight to Windows; during a stream
 * they also work without it, by typing the command into the Run box on the PC.
 */
public enum PcPower {
    SLEEP("sleep", R.string.pocket_power_sleep, true,
            "powershell -NoProfile -WindowStyle Hidden -Command \"Add-Type -AssemblyName System.Windows.Forms;"
                    + "[System.Windows.Forms.Application]::SetSuspendState('Suspend',$false,$false)\""),
    HIBERNATE("hibernate", R.string.pocket_power_hibernate, true, "shutdown /h"),
    RESTART("restart", R.string.pocket_power_restart, true, "shutdown /r /t 0"),
    SHUTDOWN("shutdown", R.string.pocket_power_shutdown, true, "shutdown /s /t 0"),
    LOCK("lock", R.string.pocket_power_lock, false, "rundll32.exe user32.dll,LockWorkStation"),
    SIGN_OUT("signout", R.string.pocket_power_signout, true, "shutdown /l"),
    DISPLAY_OFF("displayoff", R.string.pocket_power_display_off, false, null);

    /** Word sent to the PC helper ("POWER sleep"). */
    public final String command;
    public final int label;
    /** The stream can't survive it: disconnect cleanly right after. */
    public final boolean endsStream;
    /** What to type into Win+R when the helper isn't running (null: needs the helper). */
    public final String runBox;

    PcPower(String command, int label, boolean endsStream, String runBox) {
        this.command = command;
        this.label = label;
        this.endsStream = endsStream;
        this.runBox = runBox;
    }

    public String label(Context context) {
        return context.getString(label);
    }
}
