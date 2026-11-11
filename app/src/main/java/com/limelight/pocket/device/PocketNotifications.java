package com.limelight.pocket.device;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationManagerCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Notification access: the handheld's notifications, shown in Pocket Nexus's own panel (open
 * one with A, clear it with X) and announced on the home screens as they come in.
 */
public class PocketNotifications extends NotificationListenerService {

    /** A notification as the panel shows it. */
    public static final class Note {
        public final String key;
        public final String packageName;
        public final String app;
        public final String title;
        public final String text;
        public final long when;
        public final boolean clearable;
        final PendingIntent open;

        Note(String key, String packageName, String app, String title, String text, long when, boolean clearable,
             PendingIntent open) {
            this.key = key;
            this.packageName = packageName;
            this.app = app;
            this.title = title;
            this.text = text;
            this.when = when;
            this.clearable = clearable;
            this.open = open;
        }
    }

    /** Told (on the main thread) when a notification comes in. */
    public interface Listener {
        void onNotification(Note note);
    }

    private static PocketNotifications connected;
    private static Listener listener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void setListener(Listener l) {
        listener = l;
    }

    /** True when the user gave notification access. */
    public static boolean allowed(Context context) {
        return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.getPackageName());
    }

    /** Opens the system screen where it's given. */
    public static void askForAccess(Context context) {
        Intent i;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            new ComponentName(context, PocketNotifications.class).flattenToString());
        }
        else {
            i = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(i);
        } catch (RuntimeException e) {
            try {
                context.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public void onListenerConnected() {
        connected = this;
    }

    @Override
    public void onListenerDisconnected() {
        if (connected == this) {
            connected = null;
        }
    }

    @Override
    public void onDestroy() {
        if (connected == this) {
            connected = null;
        }
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        final Note n = toNote(this, sbn);
        if (n == null || !n.clearable) {
            return;
        }
        MAIN.post(() -> {
            Listener l = listener;
            if (l != null) {
                l.onNotification(n);
            }
        });
    }

    private static Note toNote(Context context, StatusBarNotification sbn) {
        if (sbn == null || sbn.getPackageName().equals(context.getPackageName())) {
            return null;
        }
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return null;
        }
        Bundle extras = n.extras;
        CharSequence title = extras != null ? extras.getCharSequence(Notification.EXTRA_TITLE) : null;
        CharSequence text = extras != null ? extras.getCharSequence(Notification.EXTRA_TEXT) : null;
        if (extras != null && text == null) {
            text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        }
        if (title == null && text == null) {
            return null;
        }
        String app = sbn.getPackageName();
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(app, 0);
            app = pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        return new Note(sbn.getKey(), sbn.getPackageName(), app, title != null ? title.toString() : "",
                text != null ? text.toString() : "", sbn.getPostTime(), sbn.isClearable(), n.contentIntent);
    }

    /** The notifications now, newest first (empty without access). */
    public static List<Note> current(Context context) {
        List<Note> out = new ArrayList<>();
        PocketNotifications s = connected;
        if (s == null) {
            return out;
        }
        StatusBarNotification[] active;
        try {
            active = s.getActiveNotifications();
        } catch (RuntimeException e) {
            return out;
        }
        if (active == null) {
            return out;
        }
        for (StatusBarNotification sbn : active) {
            Note n = toNote(context, sbn);
            if (n != null) {
                out.add(n);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.when, a.when));
        return out;
    }

    /** Opens what the notification is about. */
    public static boolean open(Context context, Note note) {
        if (note.open == null) {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(note.packageName);
            if (launch == null) {
                return false;
            }
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Lets it open its screen while Pocket Nexus is the one in front
                Bundle options = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        .toBundle();
                note.open.send(context, 0, null, null, null, null, options);
            }
            else {
                note.open.send();
            }
            dismiss(note);
            return true;
        } catch (PendingIntent.CanceledException | RuntimeException e) {
            return false;
        }
    }

    public static void dismiss(Note note) {
        PocketNotifications s = connected;
        if (s != null && note.clearable) {
            try {
                s.cancelNotification(note.key);
            } catch (RuntimeException ignored) {
            }
        }
    }

    public static void dismissAll() {
        PocketNotifications s = connected;
        if (s != null) {
            try {
                s.cancelAllNotifications();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
