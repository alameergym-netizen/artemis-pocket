package com.limelight.pocket;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.InputDevice;
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
    public static final String KEY_UI_SCALE = "pocket_ui_scale";
    public static final String KEY_HAPTICS = "pocket_nav_haptics";
    public static final String KEY_COLOR_ART = "pocket_color_background_art";
    public static final String KEY_AUTO_BATTERY = "pocket_auto_battery_saver";
    public static final String KEY_OPEN_LAST_PC = "pocket_open_last_pc";
    public static final String KEY_SORT_RECENT = "pocket_sort_recent";
    public static final String KEY_SHOW_LATENCY = "pocket_show_latency";
    public static final String KEY_ACTION_FEEDBACK = "pocket_action_feedback";
    public static final String KEY_FOCUS_STYLE = "pocket_focus_style";

    public static final String FOCUS_RING_ZOOM = "ring_zoom";
    public static final String FOCUS_RING = "ring";
    public static final String FOCUS_ZOOM = "zoom";
    private static final String KEY_LAST_PC_UUID = "pocket_last_pc_uuid";
    private static final String KEY_PLAYED_PREFIX = "pocket_played_";

    public static final int DEFAULT_UI_SCALE = 90;

    private static final String KEY_LAST_USB_IP = "pocket_last_usb_ip";
    private static final String KEY_LAST_USB_UUID = "pocket_last_usb_uuid";
    private static final String KEY_LAST_APP_PREFIX = "pocket_last_app_";

    public static final String ON_CONNECT_LIBRARY = "library";
    public static final String ON_CONNECT_LAST_GAME = "last";
    public static final String ON_CONNECT_NOTHING = "nothing";

    public static final int DEFAULT_USB_BITRATE_KBPS = 60000;

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

    // M2: a second assignable button (same encoding as the menu button / M1)
    public static final String KEY_M2_HOTKEY = "pocket_m2_hotkey";

    public static String getM2Hotkey(Context context) {
        return prefs(context).getString(KEY_M2_HOTKEY, "");
    }

    public static void setM2Hotkey(Context context, KeyEvent event) {
        prefs(context).edit().putString(KEY_M2_HOTKEY, encodeKey(event)).apply();
    }

    public static void clearM2Hotkey(Context context) {
        prefs(context).edit().remove(KEY_M2_HOTKEY).apply();
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

    // ---- Which physical key is Back / Home ----------------------------------------------

    public static final String KEY_BACK_KEY = "pocket_key_back";
    public static final String KEY_HOME_KEY = "pocket_key_home";
    public static final String KEY_AUTO = "auto";

    public static String encodeKey(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_UNKNOWN) {
            return "k:" + event.getKeyCode();
        }
        return "s:" + event.getScanCode();
    }

    public static String getBackKey(Context context) {
        return prefs(context).getString(KEY_BACK_KEY, KEY_AUTO);
    }

    public static String getHomeKey(Context context) {
        return prefs(context).getString(KEY_HOME_KEY, KEY_AUTO);
    }

    public static void setButtonKey(Context context, String prefKey, KeyEvent event) {
        prefs(context).edit().putString(prefKey, encodeKey(event)).apply();
    }

    public static void resetButtonKey(Context context, String prefKey) {
        prefs(context).edit().remove(prefKey).apply();
    }

    /** Back: "auto" is Android's Back key (not a mouse's back button). */
    public static boolean matchesBack(String spec, KeyEvent event) {
        if (KEY_AUTO.equals(spec)) {
            int source = event.getSource();
            boolean fromMouse = (source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                    || source == InputDevice.SOURCE_MOUSE_RELATIVE;
            return event.getKeyCode() == KeyEvent.KEYCODE_BACK && !fromMouse;
        }
        return matchesHotkey(spec, event);
    }

    /**
     * Home: "auto" is Android's Home key, or the Guide/Mode button of the handheld's built-in
     * controller (some handhelds send their Home button that way). Guide buttons of external
     * controllers keep working as plain Guide.
     */
    public static boolean matchesHome(String spec, KeyEvent event) {
        if (KEY_AUTO.equals(spec)) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_MODE) {
                InputDevice device = event.getDevice();
                return device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !device.isExternal();
            }
            return false;
        }
        return matchesHotkey(spec, event);
    }

    public static String describeButtonKey(Context context, String spec, int autoLabel) {
        if (KEY_AUTO.equals(spec)) {
            return context.getString(autoLabel);
        }
        String name = describeHotkey(spec);
        return name != null ? name : context.getString(autoLabel);
    }

    public static boolean uiSounds(Context context) {
        return prefs(context).getBoolean(KEY_UI_SOUNDS, true);
    }

    public static final String KEY_UI_SOUNDS = "pocket_ui_sounds";
    public static final String KEY_BATTERY_ALERTS = "pocket_battery_alerts";

    public static boolean batteryAlerts(Context context) {
        return prefs(context).getBoolean(KEY_BATTERY_ALERTS, true);
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

    public static int uiScalePercent(Context context) {
        return prefs(context).getInt(KEY_UI_SCALE, DEFAULT_UI_SCALE);
    }

    public static boolean hapticsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_HAPTICS, false);
    }

    public static boolean actionFeedback(Context context) {
        return prefs(context).getBoolean(KEY_ACTION_FEEDBACK, true);
    }

    public static String focusStyle(Context context) {
        return prefs(context).getString(KEY_FOCUS_STYLE, FOCUS_RING_ZOOM);
    }

    public static boolean colorBackgroundArt(Context context) {
        // Colour by default (the screen takes on the game's colours); off = black and white
        return prefs(context).getBoolean(KEY_COLOR_ART, true);
    }

    public static boolean autoBatterySaver(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_BATTERY, true);
    }

    /** Another game running: switch to the new one straight away, without asking. */
    public static final String KEY_SWITCH_NO_ASK = "pocket_switch_without_asking";

    public static boolean switchWithoutAsking(Context context) {
        return prefs(context).getBoolean(KEY_SWITCH_NO_ASK, false);
    }

    public static void setSwitchWithoutAsking(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_SWITCH_NO_ASK, value).apply();
    }

    public static boolean openLastPc(Context context) {
        return prefs(context).getBoolean(KEY_OPEN_LAST_PC, false);
    }

    public static boolean sortRecent(Context context) {
        return SORT_RECENT.equals(sortMode(context));
    }

    public static final String KEY_SORT_MODE = "pocket_sort_mode";
    public static final String SORT_RECENT = "recent";
    public static final String SORT_AZ = "az";
    public static final String SORT_PC = "pc";
    public static final String[] SORT_MODES = {SORT_RECENT, SORT_AZ, SORT_PC};

    /** Library order: recently played, A to Z, or the PC's own order (favourites always first). */
    public static String sortMode(Context context) {
        String mode = prefs(context).getString(KEY_SORT_MODE, null);
        if (mode == null) {
            return prefs(context).getBoolean(KEY_SORT_RECENT, true) ? SORT_RECENT : SORT_PC;
        }
        return mode;
    }

    public static int sortModeIndex(Context context) {
        String mode = sortMode(context);
        for (int i = 0; i < SORT_MODES.length; i++) {
            if (SORT_MODES[i].equals(mode)) {
                return i;
            }
        }
        return 0;
    }

    public static boolean showLatency(Context context) {
        return prefs(context).getBoolean(KEY_SHOW_LATENCY, true);
    }

    public static String getLastPcUuid(Context context) {
        return prefs(context).getString(KEY_LAST_PC_UUID, null);
    }

    public static void setLastPcUuid(Context context, String uuid) {
        prefs(context).edit().putString(KEY_LAST_PC_UUID, uuid).apply();
    }

    /** When a game was last started from this handheld (0 if never). */
    public static long getLastPlayed(Context context, String pcUuid, int appId) {
        return prefs(context).getLong(KEY_PLAYED_PREFIX + pcUuid + "_" + appId, 0);
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
                .putLong(KEY_PLAYED_PREFIX + pcUuid + "_" + appId, System.currentTimeMillis())
                .putString(KEY_LAST_PC_UUID, pcUuid)
                .apply();
    }

    // ---- v2: resume, adaptive stream, frame generation, mic, theme, playtime ------------

    public static final String KEY_SEAMLESS_RESUME = "pocket_seamless_resume";
    public static final String KEY_ADAPTIVE = "pocket_adaptive_stream";
    public static final String KEY_FRAME_GEN = "pocket_frame_gen";
    public static final String KEY_MIC = "pocket_mic_passthrough";
    public static final String KEY_ACCENT = "pocket_accent";
    public static final String KEY_BACKDROP = "pocket_backdrop";
    public static final String KEY_INTRO = "pocket_intro";
    public static final String KEY_STREAM_BRIGHTNESS = "pocket_stream_brightness";
    private static final String KEY_PLAYTIME_PREFIX = "pocket_playtime_";
    private static final String KEY_ADAPTIVE_CAP = "pocket_adaptive_cap_kbps";
    private static final String KEY_ADAPTIVE_CAP_UNTIL = "pocket_adaptive_cap_until";
    private static final String KEY_COMPANION_TOKEN_PREFIX = "pocket_companion_token_";

    public static final String FRAME_GEN_OFF = "off";
    public static final String FRAME_GEN_2X = "2x";
    /** LSFG-Android (a separate overlay app) makes the frames; the stream only prepares for it. */
    public static final String FRAME_GEN_LSFG = "lsfg";

    public static final String BACKDROP_GLOW = "glow";
    /** Light in the colours of the focused game, its key art when it has one (the default). */
    public static final String BACKDROP_AMBIENT = "ambient";
    public static final String BACKDROP_ART = "art";
    public static final String BACKDROP_BLACK = "black";

    /** Pick the game up again by itself after the screen was off or the app was left. */
    public static boolean seamlessResume(Context context) {
        return prefs(context).getBoolean(KEY_SEAMLESS_RESUME, true);
    }

    /** Start quality from the connection, and lower it by itself when the connection struggles. */
    public static boolean adaptiveStream(Context context) {
        return prefs(context).getBoolean(KEY_ADAPTIVE, true);
    }

    public static String frameGen(Context context) {
        return prefs(context).getString(KEY_FRAME_GEN, FRAME_GEN_OFF);
    }

    public static boolean frameGenEnabled(Context context) {
        return FRAME_GEN_2X.equals(frameGen(context));
    }

    public static boolean frameGenLsfg(Context context) {
        return FRAME_GEN_LSFG.equals(frameGen(context));
    }

    // ---- v3: sound styles & levels, live glass, auto-recovery, stream modes --------------

    public static final String KEY_SOUND_STYLE = "pocket_sound_style";
    public static final String KEY_VOL_MASTER = "pocket_vol_master";
    public static final String KEY_VOL_NAV = "pocket_vol_nav";
    public static final String KEY_VOL_MENU = "pocket_vol_menu";
    public static final String KEY_VOL_BOOT = "pocket_vol_boot";
    public static final String KEY_LIVE_GLASS = "pocket_live_glass";
    public static final String KEY_AUTO_RECOVER = "pocket_auto_recover";
    public static final String KEY_STREAM_MODE = "pocket_stream_mode";

    public static final String[] SOUND_STYLES = {"glass", "soft", "crisp", "retro", "pop"};

    public static int soundStyleIndex(Context context) {
        String style = prefs(context).getString(KEY_SOUND_STYLE, SOUND_STYLES[0]);
        for (int i = 0; i < SOUND_STYLES.length; i++) {
            if (SOUND_STYLES[i].equals(style)) {
                return i;
            }
        }
        return 0;
    }

    /** 0..100 */
    public static int soundVolume(Context context, String key) {
        int fallback = KEY_VOL_NAV.equals(key) ? 70 : KEY_VOL_MENU.equals(key) ? 90 : KEY_VOL_BOOT.equals(key) ? 100 : 85;
        return Math.max(0, Math.min(100, prefs(context).getInt(key, fallback)));
    }

    /** Game page: the trailer plays (muted) behind the page after a moment, like a console. */
    public static boolean trailerAutoplay(Context context) {
        return prefs(context).getBoolean("pocket_trailer_autoplay", true);
    }

    public static void setTrailerAutoplay(Context context, boolean on) {
        prefs(context).edit().putBoolean("pocket_trailer_autoplay", on).apply();
    }

    /** Real frosted glass (live blur) on the console screens. */
    public static boolean liveGlass(Context context) {
        return prefs(context).getBoolean(KEY_LIVE_GLASS, true);
    }

    /** Reconnect by itself when the stream drops unexpectedly. */
    public static boolean autoRecover(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_RECOVER, true);
    }

    public static boolean micPassthrough(Context context) {
        return prefs(context).getBoolean(KEY_MIC, false);
    }

    /** Accent color for focus and highlights (0xAARRGGBB). White by default. */
    public static int accentColor(Context context) {
        return prefs(context).getInt(KEY_ACCENT, 0xFFFFFFFF);
    }

    public static String backdrop(Context context) {
        return prefs(context).getString(KEY_BACKDROP, BACKDROP_AMBIENT);
    }

    public static boolean introAnimation(Context context) {
        return prefs(context).getBoolean(KEY_INTRO, true);
    }

    /** In-stream screen brightness, 0 = follow the system. */
    public static int streamBrightness(Context context) {
        return prefs(context).getInt(KEY_STREAM_BRIGHTNESS, 0);
    }

    // Playtime per game (seconds streamed from this handheld)
    public static long getPlaytime(Context context, String pcUuid, int appId) {
        return prefs(context).getLong(KEY_PLAYTIME_PREFIX + pcUuid + "_" + appId, 0);
    }

    public static void addPlaytime(Context context, String pcUuid, int appId, long seconds) {
        if (pcUuid == null || appId == 0 || seconds <= 0) {
            return;
        }
        String key = KEY_PLAYTIME_PREFIX + pcUuid + "_" + appId;
        SharedPreferences p = prefs(context);
        p.edit().putLong(key, p.getLong(key, 0) + seconds).apply();
    }

    // Adaptive stream memory: after the stream had to lower its quality, start the next one
    // there too for a while instead of struggling again.
    public static int adaptiveCapKbps(Context context) {
        SharedPreferences p = prefs(context);
        if (System.currentTimeMillis() > p.getLong(KEY_ADAPTIVE_CAP_UNTIL, 0)) {
            return 0;
        }
        return p.getInt(KEY_ADAPTIVE_CAP, 0);
    }

    public static void setAdaptiveCap(Context context, int kbps) {
        prefs(context).edit()
                .putInt(KEY_ADAPTIVE_CAP, kbps)
                .putLong(KEY_ADAPTIVE_CAP_UNTIL, kbps > 0 ? System.currentTimeMillis() + 2 * 60 * 60 * 1000L : 0)
                .apply();
    }

    private static final String KEY_COMPANION_SEEN_PREFIX = "pocket_companion_seen_";

    public static void markCompanionSeen(Context context, String pcUuid) {
        if (pcUuid != null) {
            prefs(context).edit().putLong(KEY_COMPANION_SEEN_PREFIX + pcUuid, System.currentTimeMillis()).apply();
        }
    }

    /** The PC helper answered from this PC in the last two weeks (so its silence means something). */
    public static boolean companionRecentlySeen(Context context, String pcUuid) {
        if (pcUuid == null) {
            return false;
        }
        long seen = prefs(context).getLong(KEY_COMPANION_SEEN_PREFIX + pcUuid, 0);
        return seen > 0 && System.currentTimeMillis() - seen < 14L * 24 * 3600 * 1000;
    }

    // PC companion pairing token per PC
    public static String getCompanionToken(Context context, String pcUuid) {
        return pcUuid == null ? null : prefs(context).getString(KEY_COMPANION_TOKEN_PREFIX + pcUuid, null);
    }

    public static void setCompanionToken(Context context, String pcUuid, String token) {
        if (pcUuid != null) {
            prefs(context).edit().putString(KEY_COMPANION_TOKEN_PREFIX + pcUuid, token).apply();
        }
    }

    public static int getLastAppId(Context context, String pcUuid) {
        return prefs(context).getInt(KEY_LAST_APP_PREFIX + pcUuid + "_id", 0);
    }

    public static String getLastAppName(Context context, String pcUuid) {
        return prefs(context).getString(KEY_LAST_APP_PREFIX + pcUuid + "_name", null);
    }

    // ---- v4: first boot setup & first-time tips ---------------------------------------------

    public static final String KEY_ONBOARDING_PENDING = "pocket_onboarding_pending";
    public static final String TIP_PCS = "pcs";
    public static final String TIP_GAMES = "games";
    public static final String TIP_STREAM = "stream";
    private static final String[] TIPS = {TIP_PCS, TIP_GAMES, TIP_STREAM};
    private static final String KEY_TIP_PREFIX = "pocket_tip_seen_";

    /** The first boot setup still has to run (fresh installs only, or asked for in Settings). */
    public static boolean onboardingPending(Context context) {
        return prefs(context).getBoolean(KEY_ONBOARDING_PENDING, false);
    }

    public static void setOnboardingPending(Context context, boolean pending) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_PENDING, pending).apply();
    }

    public static boolean tipSeen(Context context, String tip) {
        return prefs(context).getBoolean(KEY_TIP_PREFIX + tip, false);
    }

    public static void markTipSeen(Context context, String tip) {
        prefs(context).edit().putBoolean(KEY_TIP_PREFIX + tip, true).apply();
    }

    /** Shows every first-time tip once more. */
    public static void resetTips(Context context) {
        SharedPreferences.Editor e = prefs(context).edit();
        for (String tip : TIPS) {
            e.remove(KEY_TIP_PREFIX + tip);
        }
        e.apply();
    }

    // ---- Favourites ---------------------------------------------------------------------------

    private static final String KEY_FAVORITE_PREFIX = "pocket_fav_";

    /** Favourite games stay at the front of the library. */
    public static boolean isFavorite(Context context, String pcUuid, int appId) {
        return pcUuid != null && prefs(context).getBoolean(KEY_FAVORITE_PREFIX + pcUuid + "_" + appId, false);
    }

    public static void setFavorite(Context context, String pcUuid, int appId, boolean favorite) {
        SharedPreferences.Editor e = prefs(context).edit();
        if (favorite) {
            e.putBoolean(KEY_FAVORITE_PREFIX + pcUuid + "_" + appId, true);
        }
        else {
            e.remove(KEY_FAVORITE_PREFIX + pcUuid + "_" + appId);
        }
        e.apply();
    }
}
