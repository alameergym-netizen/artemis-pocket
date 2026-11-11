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
    /** M1: the button assigned as the menu button (any key: M1, M2, a remapped one...). */
    public static final int BUTTON_MENU = 2;
    /** M2: a second assignable button. */
    public static final int BUTTON_M2 = 3;
    public static final int BUTTON_COUNT = 4;

    public static final int GESTURE_TAP = 0;
    public static final int GESTURE_DOUBLE = 1;
    public static final int GESTURE_HOLD = 2;

    public static final int RADIAL_SLOTS = 8;

    private static final String[] BUTTON_KEYS = {"back", "home", "menu", "m2"};
    private static final String[] GESTURE_KEYS = {"tap", "double", "hold"};

    private static final PocketAction[][] DEFAULTS = {
            // Back: tap = Xbox Guide, double = quick menu, hold = radial menu
            {PocketAction.GUIDE, PocketAction.QUICK_MENU, PocketAction.RADIAL},
            // Home: a real Xbox Guide button (tap and long press both reach the PC as they are)
            {PocketAction.GUIDE, PocketAction.NONE, PocketAction.GUIDE},
            // M1 (the menu button): quick menu / radial
            {PocketAction.QUICK_MENU, PocketAction.NONE, PocketAction.RADIAL},
            // M2: a screenshot on the PC; set it to any controller button to use it as a paddle
            {PocketAction.SCREENSHOT, PocketAction.NONE, PocketAction.NONE},
    };

    private static final PocketAction[] RADIAL_DEFAULTS = {
            PocketAction.GUIDE,
            PocketAction.GAME_BAR,
            PocketAction.SCREENSHOT,
            PocketAction.ALT_TAB,
            PocketAction.ESC,
            PocketAction.TASK_MANAGER,
            // The PC keyboard you tap on the screen (Esc, Ctrl, Alt, F-keys...)
            PocketAction.TOUCH_KEYBOARD,
            PocketAction.KEYBOARD,
    };

    public static int buttonLabel(int button) {
        switch (button) {
            case BUTTON_BACK: return R.string.pocket_button_back;
            case BUTTON_HOME: return R.string.pocket_button_home;
            case BUTTON_M2: return R.string.pocket_button_m2;
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

    // ---- On the home screens (not streaming) ------------------------------------------------

    /** What M1 / M2 do on the home screen and in the library. */
    public static final String LAUNCHER_SETTINGS = "settings";
    public static final String LAUNCHER_QUICK_RESUME = "quick";
    public static final String LAUNCHER_GAME_PAGE = "page";
    public static final String LAUNCHER_RESUME_LAST = "last";
    public static final String LAUNCHER_NONE = "none";
    public static final String[] LAUNCHER_ACTIONS = {LAUNCHER_SETTINGS, LAUNCHER_QUICK_RESUME,
            LAUNCHER_GAME_PAGE, LAUNCHER_RESUME_LAST, LAUNCHER_NONE};

    public static int launcherLabel(String action) {
        switch (action) {
            case LAUNCHER_QUICK_RESUME: return R.string.pocket_launcher_quick;
            case LAUNCHER_GAME_PAGE: return R.string.pocket_launcher_page;
            case LAUNCHER_RESUME_LAST: return R.string.pocket_launcher_last;
            case LAUNCHER_NONE: return R.string.pocket_act_none;
            default: return R.string.pocket_launcher_settings;
        }
    }

    /** M1 opens the settings there by default (as before), M2 Quick Resume. */
    public static String getLauncher(Context context, int button) {
        String def = button == BUTTON_M2 ? LAUNCHER_QUICK_RESUME : LAUNCHER_SETTINGS;
        String v = PocketPrefs.prefs(context).getString("pocket_launcher_" + BUTTON_KEYS[button], def);
        for (String a : LAUNCHER_ACTIONS) {
            if (a.equals(v)) {
                return a;
            }
        }
        return def;
    }

    public static void setLauncher(Context context, int button, String action) {
        PocketPrefs.prefs(context).edit().putString("pocket_launcher_" + BUTTON_KEYS[button], action).apply();
    }

    // ---- Macros -------------------------------------------------------------------------------

    public static final int MACRO_SLOTS = 4;

    public static Macro getMacro(Context context, int slot) {
        return Macro.parse(PocketPrefs.prefs(context).getString("pocket_macro_" + slot, null));
    }

    public static void setMacro(Context context, int slot, Macro macro) {
        if (macro == null || macro.isEmpty()) {
            PocketPrefs.prefs(context).edit().remove("pocket_macro_" + slot).apply();
        }
        else {
            PocketPrefs.prefs(context).edit().putString("pocket_macro_" + slot, macro.encode()).apply();
        }
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
