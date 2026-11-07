package com.limelight.pocket;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import androidx.preference.PreferenceManager;

/**
 * Settings that only exist in Artemis Pocket (console UI, USB link, hotkeys).
 * They live in the default shared preferences next to the upstream Artemis options.
 */
public final class PocketPrefs {
    private PocketPrefs() {}

    // Hotkey that opens settings (home screens) or the quick menu (while streaming).
    // Stored as "k:<keycode>" or "s:<scancode>" (for buttons Android has no keycode for).
    public static final String KEY_MENU_HOTKEY = "pocket_menu_hotkey";
    public static final String KEY_HOME_AS_GUIDE = "pocket_home_as_guide";
    public static final String KEY_BACK_AS_GUIDE = "checkbox_back_as_guide"; // upstream key
    public static final String KEY_USB_AUTO_CONNECT = "pocket_usb_auto_connect";
    public static final String KEY_USB_ON_CONNECT = "pocket_usb_on_connect"; // library | last | nothing
    public static final String KEY_USB_BITRATE = "pocket_usb_bitrate_kbps";
    public static final String KEY_PRESET = "pocket_preset"; // battery | balanced | quality | custom
    public static final String KEY_ANIMATIONS = "pocket_animations";
    public static final String KEY_FIRST_RUN_DONE = "pocket_first_run_done_v1";

    private static final String KEY_LAST_USB_IP = "pocket_last_usb_ip";
    private static final String KEY_LAST_USB_UUID = "pocket_last_usb_uuid";
    private static final String KEY_LAST_APP_PREFIX = "pocket_last_app_";

    public static final String ON_CONNECT_LIBRARY = "library";
    public static final String ON_CONNECT_LAST_GAME = "last";
    public static final String ON_CONNECT_NOTHING = "nothing";

    public static final int DEFAULT_USB_BITRATE_KBPS = 80000;

    public static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    // ---- Hotkey -------------------------------------------------------------------------

    public static String getMenuHotkey(Context context) {
        return prefs(context).getString(KEY_MENU_HOTKEY, "");
    }

    public static void setMenuHotkey(Context context, KeyEvent event) {
        String value;
        if (event.getKeyCode() != KeyEvent.KEYCODE_UNKNOWN) {
            value = "k:" + event.getKeyCode();
        }
        else {
            value = "s:" + event.getScanCode();
        }
        prefs(context).edit().putString(KEY_MENU_HOTKEY, value).apply();
    }

    public static void clearMenuHotkey(Context context) {
        prefs(context).edit().remove(KEY_MENU_HOTKEY).apply();
    }

    public static boolean matchesHotkey(String hotkey, KeyEvent event) {
        if (hotkey == null || hotkey.length() < 3) {
            return false;
        }
        try {
            int value = Integer.parseInt(hotkey.substring(2));
            if (hotkey.startsWith("k:")) {
                return event.getKeyCode() == value;
            }
            else if (hotkey.startsWith("s:")) {
                return event.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN && event.getScanCode() == value;
            }
        } catch (NumberFormatException ignored) {
        }
        return false;
    }

    public static String describeHotkey(String hotkey) {
        if (hotkey == null || hotkey.length() < 3) {
            return null;
        }
        try {
            int value = Integer.parseInt(hotkey.substring(2));
            if (hotkey.startsWith("k:")) {
                String name = KeyEvent.keyCodeToString(value);
                if (name.startsWith("KEYCODE_")) {
                    name = name.substring("KEYCODE_".length());
                }
                if (name.startsWith("BUTTON_")) {
                    name = name.substring("BUTTON_".length());
                }
                return name.replace('_', ' ');
            }
            else {
                return "SCAN " + value;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- Toggles ------------------------------------------------------------------------

    public static boolean homeAsGuide(Context context) {
        return prefs(context).getBoolean(KEY_HOME_AS_GUIDE, false);
    }

    public static boolean usbAutoConnect(Context context) {
        return prefs(context).getBoolean(KEY_USB_AUTO_CONNECT, true);
    }

    public static String usbOnConnect(Context context) {
        return prefs(context).getString(KEY_USB_ON_CONNECT, ON_CONNECT_LIBRARY);
    }

    public static int usbBitrateKbps(Context context) {
        return prefs(context).getInt(KEY_USB_BITRATE, DEFAULT_USB_BITRATE_KBPS);
    }

    public static boolean animationsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ANIMATIONS, true);
    }

    // ---- USB host memory ----------------------------------------------------------------

    public static String getLastUsbIp(Context context) {
        return prefs(context).getString(KEY_LAST_USB_IP, null);
    }

    public static String getLastUsbUuid(Context context) {
        return prefs(context).getString(KEY_LAST_USB_UUID, null);
    }

    public static void setLastUsbHost(Context context, String ip, String uuid) {
        prefs(context).edit()
                .putString(KEY_LAST_USB_IP, ip)
                .putString(KEY_LAST_USB_UUID, uuid)
                .apply();
    }

    // ---- Last game per PC ---------------------------------------------------------------

    public static void setLastApp(Context context, String pcUuid, int appId, String appName) {
        if (pcUuid == null || appId == 0) {
            return;
        }
        prefs(context).edit()
                .putInt(KEY_LAST_APP_PREFIX + pcUuid + "_id", appId)
                .putString(KEY_LAST_APP_PREFIX + pcUuid + "_name", appName)
                .apply();
    }

    public static int getLastAppId(Context context, String pcUuid) {
        return prefs(context).getInt(KEY_LAST_APP_PREFIX + pcUuid + "_id", 0);
    }

    public static String getLastAppName(Context context, String pcUuid) {
        return prefs(context).getString(KEY_LAST_APP_PREFIX + pcUuid + "_name", null);
    }
}
