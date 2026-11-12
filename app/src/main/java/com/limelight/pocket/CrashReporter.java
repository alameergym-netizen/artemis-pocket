package com.limelight.pocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.widget.Toast;

import com.limelight.BuildConfig;
import com.limelight.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Saves the stack trace of a crash (or a caught error worth reporting) so the next launch can
 * show it with a Copy button. No logcat or PC needed to find out what went wrong.
 */
public final class CrashReporter {
    private CrashReporter() {}

    private static final String FILE = "pocket_last_crash.txt";
    private static final int MAX_CHARS = 12000;

    public static void install(Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                save(app, thread, error, true);
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
            else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    /** Records an error that was caught (the app kept running) so it can still be reported. */
    public static void saveCaught(Context context, Throwable error) {
        try {
            save(context.getApplicationContext(), Thread.currentThread(), error, false);
        } catch (Throwable ignored) {
        }
    }

    private static void save(Context context, Thread thread, Throwable error, boolean fatal) throws Exception {
        StringWriter text = new StringWriter();
        PrintWriter out = new PrintWriter(text);
        out.println("Pocket Nexus " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
                + (fatal ? "" : " [caught]"));
        out.println("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), "
                + Build.MANUFACTURER + " " + Build.MODEL);
        out.println("Thread: " + (thread != null ? thread.getName() : "?") + ", " + new Date());
        out.println();
        error.printStackTrace(out);
        out.flush();
        String report = text.toString();
        if (report.length() > MAX_CHARS) {
            report = report.substring(0, MAX_CHARS) + "\n…";
        }
        try (FileOutputStream stream = new FileOutputStream(new File(context.getFilesDir(), FILE))) {
            stream.write(report.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The saved report, removed so it is shown only once. Null when there is none. */
    public static String takeReport(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        if (!file.exists()) {
            return null;
        }
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), MAX_CHARS * 4L)];
            int read = 0;
            while (read < data.length) {
                int n = stream.read(data, read, data.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return new String(data, 0, read, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /** Shows the last report, if any, with a Copy button. */
    public static void showPendingReport(final Activity activity) {
        final String report = takeReport(activity);
        if (report == null || report.trim().isEmpty()) {
            return;
        }
        try {
            com.limelight.pocket.ConsoleActivity.showImmersive(new AlertDialog.Builder(activity)
                    .setTitle(R.string.pocket_crash_title)
                    .setMessage(activity.getString(R.string.pocket_crash_body) + "\n\n" + report)
                    .setPositiveButton(R.string.pocket_crash_copy, (dialog, which) -> {
                        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(ClipData.newPlainText("Pocket Nexus crash", report));
                            Toast.makeText(activity, R.string.pocket_crash_copied, Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(R.string.pocket_crash_dismiss, null)
                    .create());
        } catch (RuntimeException ignored) {
        }
    }
}
