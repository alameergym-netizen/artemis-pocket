package com.limelight.pocket;

import android.content.Context;
import android.content.SharedPreferences;

import com.limelight.R;

/**
 * What each special button does while streaming, per gesture (tap / double press / hold),
 * plus the eight slots of the radial menu.
 */
public final class ButtonActions {
    private ButtonActions() {}

    public static final int BUTTON_BACK = 0;
    public static final int BUTTON_HOME = 1;
    public static final int BUTTON_MENU = 2;

    public static final int GESTURE_TAP = 0;
    public static final int GESTURE_DOUBLE = 1;
    public static final int GESTURE_HOLD = 2;

    public static final int RADIAL_SLOTS = 8;

    private static final String[] BUTTON_KEYS = {"back", "home", "menu"};
    private static final String[] GESTURE_KEYS = {"tap", "double", "hold"};

    private static final PocketAction[][] DEFAULTS = {
            // Back: tap = Xbox Guide, double = quick menu, hold = radial menu
            {PocketAction.GUIDE, PocketAction.QUICK_MENU, PocketAction.RADIAL},
            // Home: tap = Xbox Guide, double = nothing, hold = go to Android home
            {PocketAction.GUIDE, PocketAction.NONE, PocketAction.ANDROID_HOME},
            // Menu button (M1/M2 or whatever was assigned): quick menu / radial
            {PocketAction.QUICK_MENU, PocketAction.NONE, PocketAction.RADIAL},
    };

    private static final PocketAction[] RADIAL_DEFAULTS = {
            PocketAction.GUIDE,
            PocketAction.GAME_BAR,
            PocketAction.SCREENSHOT,
            PocketAction.ALT_TAB,
            PocketAction.ESC,
            PocketAction.TASK_MANAGER,
            PocketAction.STATS,
            PocketAction.KEYBOARD,
    };

    public static int buttonLabel(int button) {
        switch (button) {
            case BUTTON_BACK: return R.string.pocket_button_back;
            case BUTTON_HOME: return R.string.pocket_button_home;
            default: return R.string.pocket_button_menu;
        }
    }

    public static int gestureLabel(int gesture) {
        switch (gesture) {
            case GESTURE_TAP: return R.string.pocket_gesture_tap;
            case GESTURE_DOUBLE: return R.string.pocket_gesture_double;
            default: return R.string.pocket_gesture_hold;
        }
    }

    private static String key(int button, int gesture) {
        return "pocket_btn_" + BUTTON_KEYS[button] + "_" + GESTURE_KEYS[gesture];
    }

    public static PocketAction get(Context context, int button, int gesture) {
        SharedPreferences prefs = PocketPrefs.prefs(context);
        return PocketAction.fromId(prefs.getString(key(button, gesture), null), DEFAULTS[button][gesture]);
    }

    public static void set(Context context, int button, int gesture, PocketAction action) {
        PocketPrefs.prefs(context).edit().putString(key(button, gesture), action.id).apply();
    }

    public static PocketAction getRadial(Context context, int slot) {
        return PocketAction.fromId(PocketPrefs.prefs(context).getString("pocket_radial_" + slot, null),
                RADIAL_DEFAULTS[slot]);
    }

    public static void setRadial(Context context, int slot, PocketAction action) {
        PocketPrefs.prefs(context).edit().putString("pocket_radial_" + slot, action.id).apply();
    }

    /** True if any gesture of the Home button does something (needs the accessibility service). */
    public static boolean homeIsUsed(Context context) {
        for (int g = 0; g < 3; g++) {
            PocketAction a = get(context, BUTTON_HOME, g);
            if (a != PocketAction.NONE && a != PocketAction.ANDROID_HOME && a != PocketAction.DEFAULT) {
                return true;
            }
        }
        return false;
    }
}
