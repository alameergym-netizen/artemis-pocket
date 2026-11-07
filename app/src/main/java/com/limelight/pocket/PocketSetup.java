package com.limelight.pocket;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Tuned defaults for the Retroid Pocket 6 (Snapdragon 8 Gen 2, 5.5" 1080p 120 Hz AMOLED,
 * Android 13, built-in controller). Applied once on first launch and from Settings on demand.
 */
public final class PocketSetup {
    private PocketSetup() {}

    public static final String PRESET_BATTERY = "battery";
    public static final String PRESET_BALANCED = "balanced";
    public static final String PRESET_QUALITY = "quality";
    public static final String PRESET_CUSTOM = "custom";

    public static void applyFirstRunDefaults(Context context) {
        SharedPreferences prefs = PocketPrefs.prefs(context);
        if (prefs.getBoolean(PocketPrefs.KEY_FIRST_RUN_DONE, false)) {
            int version = prefs.getInt("pocket_defaults_version", 1);
            SharedPreferences.Editor e = prefs.edit();
            // v2: the ADB USB bridge tops out around 60 Mbps, so move the old 80 Mbps default down
            if (version < 2 && prefs.getInt(PocketPrefs.KEY_USB_BITRATE, 0) == 80000) {
                e.putInt(PocketPrefs.KEY_USB_BITRATE, PocketPrefs.DEFAULT_USB_BITRATE_KBPS);
            }
            // v3: Back/Home are now handled by Button actions; Snapdragon 8 Gen 2 low-latency decoding
            if (version < 3) {
                e.putBoolean("checkbox_back_as_guide", false);
                e.putBoolean("checkbox_ultra_low_latency", true);
            }
            e.putInt("pocket_defaults_version", 3).apply();
            return;
        }
        applyRp6Defaults(context);
        prefs.edit().putBoolean(PocketPrefs.KEY_FIRST_RUN_DONE, true).putInt("pocket_defaults_version", 3).apply();
    }

    public static void applyRp6Defaults(Context context) {
        SharedPreferences.Editor e = PocketPrefs.prefs(context).edit();

        applyPresetValues(e, PRESET_QUALITY);

        // Video
        e.putString("video_format", "forceh265");           // HEVC: lowest decode latency on Adreno 740
        e.putString("frame_pacing", "balanced");
        e.putBoolean("checkbox_ultra_low_latency", true);     // made for Snapdragon 8 Gen 2
        e.putBoolean("checkbox_enforce_display_mode", true); // keep the panel at 120 Hz while streaming
        e.putBoolean("checkbox_unlock_fps", false);
        e.putString("list_video_scale_mode", "fit");
        e.putBoolean("checkbox_enable_hdr", false);          // the RP6 panel is SDR-tuned

        // Controls: built-in pad, Back acts as Xbox Guide, device motor for rumble
        e.putBoolean("checkbox_back_as_guide", false); // Back is handled by Button actions
        e.putBoolean("checkbox_back_as_meta", false);
        e.putBoolean("checkbox_show_onscreen_controls", false);
        e.putBoolean("checkbox_enable_keyboard", false);
        e.putBoolean("checkbox_enable_floating_button", false);
        e.putBoolean("checkbox_enable_rumble", true);
        e.putBoolean("checkbox_vibrate_fallback", true);
        e.putInt("seekbar_vibrate_fallback_strength", 100);
        e.putInt("seekbar_deadzone", 4);
        e.putBoolean("checkbox_mouse_emulation", true);
        e.putBoolean("checkbox_enable_quit_dialog", true);   // hold START for the quick menu
        e.putBoolean("checkbox_multi_controller", true);

        // Audio
        e.putString("list_audio_config", "2");

        // UI
        e.putBoolean("checkbox_resume_without_confirm", true);
        e.putBoolean("checkbox_small_icon_mode", false);
        e.putBoolean("checkbox_enable_pip", false);
        e.putBoolean("checkbox_auto_orientation", false);
        e.putBoolean("checkbox_enable_perf_overlay", false);

        // Artemis Pocket
        e.putBoolean(PocketPrefs.KEY_USB_AUTO_CONNECT, true);
        e.putString(PocketPrefs.KEY_USB_ON_CONNECT, PocketPrefs.ON_CONNECT_LIBRARY);
        e.putBoolean(PocketPrefs.KEY_ANIMATIONS, true);

        e.apply();
    }

    /** Writes resolution / fps / bitrates for one of the simple presets. */
    public static void applyPresetValues(SharedPreferences.Editor e, String preset) {
        switch (preset) {
            case PRESET_BATTERY:
                e.putString("list_resolution", "1280x720");
                e.putString("list_fps", "60");
                e.putInt("seekbar_bitrate_kbps", 15000);
                e.putInt(PocketPrefs.KEY_USB_BITRATE, 30000);
                break;
            case PRESET_BALANCED:
                e.putString("list_resolution", "1920x1080");
                e.putString("list_fps", "60");
                e.putInt("seekbar_bitrate_kbps", 30000);
                e.putInt(PocketPrefs.KEY_USB_BITRATE, 60000);
                break;
            case PRESET_QUALITY:
            default:
                e.putString("list_resolution", "1920x1080");
                e.putString("list_fps", "120");
                e.putInt("seekbar_bitrate_kbps", 50000);
                e.putInt(PocketPrefs.KEY_USB_BITRATE, PocketPrefs.DEFAULT_USB_BITRATE_KBPS);
                break;
        }
        e.putString(PocketPrefs.KEY_PRESET, preset);
    }
}
