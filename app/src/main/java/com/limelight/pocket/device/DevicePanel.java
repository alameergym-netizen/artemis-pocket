package com.limelight.pocket.device;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.pocket.ConsoleActivity;
import com.limelight.pocket.PocketSounds;
import com.limelight.pocket.ui.StreamMenuView;

import java.util.ArrayList;
import java.util.List;

/**
 * The handheld's quick controls, with the controller: volume, brightness, Wi-Fi, Bluetooth,
 * the notifications, battery and storage, and the system's own panels for the rest.
 */
public final class DevicePanel {
    private DevicePanel() {}

    public static void show(final ConsoleActivity a) {
        PocketSounds.play(a, PocketSounds.OPEN);
        a.showConsolePanel(a.getString(R.string.pocket_device_title), batteryLine(a), null, items(a));
    }

    private static List<StreamMenuView.Item> items(final ConsoleActivity a) {
        List<StreamMenuView.Item> items = new ArrayList<>();
        final AudioManager audio = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);

        items.add(StreamMenuView.Item.header(a.getString(R.string.pocket_device_sound_screen)));
        if (audio != null) {
            items.add(StreamMenuView.Item.value(a.getString(R.string.pocket_device_volume), volumeText(audio), direction -> {
                int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int now = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                int next = Math.max(0, Math.min(max, now + (direction >= 0 ? 1 : -1)));
                try {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
                } catch (SecurityException ignored) {
                    // Do not disturb won't let it change
                }
                PocketSounds.play(a, PocketSounds.NAV);
                return volumeText(audio);
            }).withIcon("VOLUME_UP"));
        }
        items.add(StreamMenuView.Item.value(a.getString(R.string.pocket_device_brightness), brightnessText(a), direction -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(a)) {
                askWriteSettings(a);
                return a.getString(R.string.pocket_device_allow);
            }
            try {
                Settings.System.putInt(a.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                int now = Settings.System.getInt(a.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
                int step = 255 / 12;
                int next = Math.max(4, Math.min(255, now + (direction >= 0 ? step : -step)));
                Settings.System.putInt(a.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, next);
            } catch (RuntimeException ignored) {
            }
            return brightnessText(a);
        }).withIcon("SUN"));
        items.add(StreamMenuView.Item.value(a.getString(R.string.pocket_device_auto_brightness), autoBrightnessText(a), direction -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(a)) {
                askWriteSettings(a);
                return a.getString(R.string.pocket_device_allow);
            }
            try {
                int mode = Settings.System.getInt(a.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
                Settings.System.putInt(a.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                        mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                                ? Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL : Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            } catch (RuntimeException ignored) {
            }
            return autoBrightnessText(a);
        }));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            items.add(StreamMenuView.Item.action(a.getString(R.string.pocket_device_sound_panel),
                    () -> open(a, new Intent(Settings.Panel.ACTION_VOLUME))));
        }

        items.add(StreamMenuView.Item.header(a.getString(R.string.pocket_device_connections)));
        items.add(StreamMenuView.Item.action(a.getString(R.string.pocket_apps_set_wifi), () -> open(a,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? new Intent(Settings.Panel.ACTION_WIFI)
                        : new Intent(Settings.ACTION_WIFI_SETTINGS))).withValue(wifiText(a)));
        items.add(StreamMenuView.Item.action(a.getString(R.string.pocket_apps_set_bluetooth),
                () -> open(a, new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))).withValue(bluetoothText(a)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            items.add(StreamMenuView.Item.action(a.getString(R.string.pocket_device_internet),
                    () -> open(a, new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))));
        }

        items.add(StreamMenuView.Item.header(a.getString(R.string.pocket_device_notifications)));
        if (PocketNotifications.allowed(a)) {
            final List<PocketNotifications.Note> notes = PocketNotifications.current(a);
            items.add(StreamMenuView.Item.submenu(a.getString(R.string.pocket_device_notifications),
                    () -> a.pushConsolePage(a.getString(R.string.pocket_device_notifications), notificationItems(a)))
                    .withValue(String.valueOf(notes.size())));
        }
        else {
            items.add(StreamMenuView.Item.action(a.getString(R.string.pocket_device_allow_notifications),
                    () -> PocketNotifications.askForAccess(a)));
        }

        items.add(StreamMenuView.Item.header(a.getString(R.string.pocket_device_device)));
        items.add(StreamMenuView.Item.action(a.getString(R.string.pocket_apps_set_battery),
                () -> open(a, new Intent(Intent.ACTION_POWER_USAGE_SUMMARY))).withValue(batteryLine(a)));
        items.add(StreamMenuView.Item.action(a.getString(R.string.pocket_apps_set_storage),
                () -> open(a, new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))).withValue(storageText(a)));
        items.add(StreamMenuView.Item.action(a.getString(R.string.pocket_apps_set_display),
                () -> open(a, new Intent(Settings.ACTION_DISPLAY_SETTINGS))));
        items.add(StreamMenuView.Item.action(a.getString(R.string.pocket_apps_set_all),
                () -> open(a, new Intent(Settings.ACTION_SETTINGS))));
        return items;
    }

    private static List<StreamMenuView.Item> notificationItems(final ConsoleActivity a) {
        List<StreamMenuView.Item> items = new ArrayList<>();
        List<PocketNotifications.Note> notes = PocketNotifications.current(a);
        if (notes.isEmpty()) {
            items.add(StreamMenuView.Item.header(a.getString(R.string.pocket_device_no_notifications)));
            return items;
        }
        for (final PocketNotifications.Note n : notes) {
            String title = n.app + "  ·  " + (n.title.isEmpty() ? n.text : n.title);
            String when = DateUtils.getRelativeTimeSpanString(n.when, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString();
            items.add(StreamMenuView.Item.action(title, () -> {
                if (!PocketNotifications.open(a, n)) {
                    Toast.makeText(a, R.string.pocket_apps_cant_open, Toast.LENGTH_SHORT).show();
                }
            }).withValue(when));
            if (!n.title.isEmpty() && !n.text.isEmpty()) {
                items.add(StreamMenuView.Item.header(n.text.length() > 140 ? n.text.substring(0, 140) + "…" : n.text));
            }
        }
        items.add(StreamMenuView.Item.confirm(a.getString(R.string.pocket_device_clear_all), () -> {
            PocketNotifications.dismissAll();
            PocketSounds.play(a, PocketSounds.TOGGLE_OFF);
        }));
        return items;
    }

    private static void open(ConsoleActivity a, Intent i) {
        try {
            a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void askWriteSettings(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                c.startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Toast.makeText(c, R.string.pocket_device_allow_brightness, Toast.LENGTH_LONG).show();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static String volumeText(AudioManager audio) {
        int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return Math.round(100f * audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max) + "%";
    }

    private static String brightnessText(Context c) {
        try {
            int b = Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            return Math.round(b * 100f / 255f) + "%";
        } catch (Settings.SettingNotFoundException | RuntimeException e) {
            return "";
        }
    }

    private static String autoBrightnessText(Context c) {
        int mode = Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
        return c.getString(mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC ? R.string.pocket_on : R.string.pocket_off);
    }

    private static String wifiText(Context c) {
        try {
            WifiManager wifi = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && !wifi.isWifiEnabled()) {
                return c.getString(R.string.pocket_off);
            }
        } catch (RuntimeException ignored) {
        }
        return c.getString(ConsoleActivity.isWifiConnected(c) ? R.string.pocket_device_connected : R.string.pocket_on);
    }

    private static String bluetoothText(Context c) {
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt == null) {
                return null;
            }
            return c.getString(bt.isEnabled() ? R.string.pocket_on : R.string.pocket_off);
        } catch (RuntimeException e) {
            return null; // not allowed to ask on this Android
        }
    }

    static String batteryLine(Context c) {
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) {
            return "";
        }
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        if (level < 0) {
            return "";
        }
        int pct = Math.round(level * 100f / Math.max(1, scale));
        return c.getString(charging ? R.string.pocket_device_battery_charging : R.string.pocket_device_battery, pct);
    }

    private static String storageText(Context c) {
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            long free = fs.getAvailableBytes();
            return c.getString(R.string.pocket_device_free, Formatter.formatShortFileSize(c, free));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
