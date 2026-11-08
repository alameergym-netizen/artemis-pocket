package com.limelight.utils;

import java.util.ArrayList;
import java.util.Iterator;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;

public class SpinnerDialog implements Runnable,OnCancelListener {
    /**
     * Artemis Pocket: an activity that draws its own progress screen instead of the Android
     * dialog (the stream shows a console-style "Starting..." screen).
     */
    public interface Host {
        void onSpinnerShow(String title, String message);
        void onSpinnerMessage(String message);
        void onSpinnerHide();
    }

    private boolean hostShown;
    private final String title;
    private final String message;
    private final Activity activity;
    private ProgressDialog progress;
    private final boolean finish;

    private static final ArrayList<SpinnerDialog> rundownDialogs = new ArrayList<>();

    private SpinnerDialog(Activity activity, String title, String message, boolean finish)
    {
        this.activity = activity;
        this.title = title;
        this.message = message;
        this.progress = null;
        this.finish = finish;
    }

    public static SpinnerDialog displayDialog(Activity activity, String title, String message, boolean finish)
    {
        SpinnerDialog spinner = new SpinnerDialog(activity, title, message, finish);
        activity.runOnUiThread(spinner);
        return spinner;
    }

    public static void closeDialogs(Activity activity)
    {
        synchronized (rundownDialogs) {
            Iterator<SpinnerDialog> i = rundownDialogs.iterator();
            while (i.hasNext()) {
                SpinnerDialog dialog = i.next();
                if (dialog.activity == activity) {
                    i.remove();
                    if (dialog.progress != null && dialog.progress.isShowing()) {
                        dialog.progress.dismiss();
                    }
                    else if (dialog.hostShown && activity instanceof Host) {
                        dialog.hostShown = false;
                        ((Host) activity).onSpinnerHide();
                    }
                }
            }
        }
    }

    public void dismiss()
    {
        // Running again with progress != null will destroy it
        activity.runOnUiThread(this);
    }

    public void setMessage(final String message)
    {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (progress != null) {
                    progress.setMessage(message);
                }
                else if (hostShown && activity instanceof Host) {
                    ((Host) activity).onSpinnerMessage(message);
                }
            }
        });
    }

    @Override
    public void run() {

        // If we're dying, don't bother doing anything
        if (activity.isFinishing()) {
            return;
        }

        if (activity instanceof Host) {
            synchronized (rundownDialogs) {
                if (!hostShown) {
                    hostShown = true;
                    rundownDialogs.add(this);
                    ((Host) activity).onSpinnerShow(title, message);
                }
                else {
                    hostShown = false;
                    rundownDialogs.remove(this);
                    ((Host) activity).onSpinnerHide();
                }
            }
            return;
        }

        if (progress == null)
        {
            progress = new ProgressDialog(activity);

            progress.setTitle(title);
            progress.setMessage(message);
            progress.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            progress.setOnCancelListener(this);

            // If we want to finish the activity when this is killed, make it cancellable
            if (finish)
            {
                progress.setCancelable(true);
                progress.setCanceledOnTouchOutside(false);
            }
            else
            {
                progress.setCancelable(false);
            }

            synchronized (rundownDialogs) {
                rundownDialogs.add(this);
                progress.show();
            }
        }
        else
        {
            synchronized (rundownDialogs) {
                if (rundownDialogs.remove(this) && progress.isShowing()) {
                    progress.dismiss();
                }
            }
        }
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        synchronized (rundownDialogs) {
            rundownDialogs.remove(this);
        }

        // This will only be called if finish was true, so we don't need to check again
        activity.finish();
    }
}
