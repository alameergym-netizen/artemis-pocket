package com.limelight.pocket;

import android.content.Context;

import com.limelight.R;

import java.util.ArrayList;
import java.util.List;

/** Everything a button, a double press, a hold or a radial-menu slot can do while streaming. */
public enum PocketAction {
    NONE("none", R.string.pocket_act_none),
    DEFAULT("default", R.string.pocket_act_default),
    GUIDE("guide", R.string.pocket_act_guide),
    GAME_BAR("gamebar", R.string.pocket_act_gamebar),
    RADIAL("radial", R.string.pocket_act_radial),
    QUICK_MENU("quickmenu", R.string.pocket_act_quickmenu),
    VIEW("view", R.string.pocket_act_view),
    START("start", R.string.pocket_act_start),
    ESC("esc", R.string.pocket_act_esc),
    ALT_TAB("alttab", R.string.pocket_act_alttab),
    WIN("win", R.string.pocket_act_win),
    ALT_F4("altf4", R.string.pocket_act_altf4),
    ALT_ENTER("altenter", R.string.pocket_act_altenter),
    TASK_MANAGER("taskmgr", R.string.pocket_act_taskmgr),
    DESKTOP("desktop", R.string.pocket_act_desktop),
    SCREENSHOT("screenshot", R.string.pocket_act_screenshot),
    RECORD("record", R.string.pocket_act_record),
    PLAY_PAUSE("playpause", R.string.pocket_act_playpause),
    VOLUME_UP("volup", R.string.pocket_act_volup),
    VOLUME_DOWN("voldown", R.string.pocket_act_voldown),
    MUTE("mute", R.string.pocket_act_mute),
    STATS("stats", R.string.pocket_act_stats),
    KEYBOARD("keyboard", R.string.pocket_act_keyboard),
    TOUCH_KEYBOARD("touchkeys", R.string.pocket_act_touch_keyboard),
    MOUSE("mouse", R.string.pocket_act_mouse),
    ANDROID_HOME("androidhome", R.string.pocket_act_androidhome),
    DISCONNECT("disconnect", R.string.pocket_act_disconnect),
    QUIT("quit", R.string.pocket_act_quit),
    // A controller button on the PC, pressed for as long as the button is held (M1 / M2 as
    // extra buttons, like paddles), and a few useful pairs
    PAD_A("pad_a", R.string.pocket_act_pad_a),
    PAD_B("pad_b", R.string.pocket_act_pad_b),
    PAD_X("pad_x", R.string.pocket_act_pad_x),
    PAD_Y("pad_y", R.string.pocket_act_pad_y),
    PAD_LB("pad_lb", R.string.pocket_act_pad_lb),
    PAD_RB("pad_rb", R.string.pocket_act_pad_rb),
    PAD_L3("pad_l3", R.string.pocket_act_pad_l3),
    PAD_R3("pad_r3", R.string.pocket_act_pad_r3),
    PAD_UP("pad_up", R.string.pocket_act_pad_up),
    PAD_DOWN("pad_down", R.string.pocket_act_pad_down),
    PAD_LEFT("pad_left", R.string.pocket_act_pad_left),
    PAD_RIGHT("pad_right", R.string.pocket_act_pad_right),
    COMBO_VIEW_START("combo_view_start", R.string.pocket_act_combo_view_start),
    COMBO_LB_RB("combo_lb_rb", R.string.pocket_act_combo_lb_rb),
    COMBO_L3_R3("combo_l3_r3", R.string.pocket_act_combo_l3_r3),
    // Recorded button sequences
    MACRO_1("macro1", R.string.pocket_act_macro_1),
    MACRO_2("macro2", R.string.pocket_act_macro_2),
    MACRO_3("macro3", R.string.pocket_act_macro_3),
    MACRO_4("macro4", R.string.pocket_act_macro_4);

    /** Which macro slot (0..3) this action plays, or -1. */
    public int macroSlot() {
        switch (this) {
            case MACRO_1: return 0;
            case MACRO_2: return 1;
            case MACRO_3: return 2;
            case MACRO_4: return 3;
            default: return -1;
        }
    }

    public final String id;
    public final int label;

    PocketAction(String id, int label) {
        this.id = id;
        this.label = label;
    }

    public static PocketAction fromId(String id, PocketAction fallback) {
        if (id != null) {
            for (PocketAction a : values()) {
                if (a.id.equals(id)) {
                    return a;
                }
            }
        }
        return fallback;
    }

    /** Short label for the radial menu (falls back to the full label). */
    public String shortLabel(Context context) {
        int res;
        switch (this) {
            case GUIDE: res = R.string.pocket_short_guide; break;
            case GAME_BAR: res = R.string.pocket_short_gamebar; break;
            case QUICK_MENU: res = R.string.pocket_short_quickmenu; break;
            case TASK_MANAGER: res = R.string.pocket_short_taskmgr; break;
            case SCREENSHOT: res = R.string.pocket_short_screenshot; break;
            case RECORD: res = R.string.pocket_short_record; break;
            case DESKTOP: res = R.string.pocket_short_desktop; break;
            case ALT_F4: res = R.string.pocket_short_altf4; break;
            case ALT_ENTER: res = R.string.pocket_short_altenter; break;
            case PLAY_PAUSE: res = R.string.pocket_short_playpause; break;
            case VOLUME_UP: res = R.string.pocket_short_volup; break;
            case VOLUME_DOWN: res = R.string.pocket_short_voldown; break;
            case STATS: res = R.string.pocket_short_stats; break;
            case KEYBOARD: res = R.string.pocket_short_keyboard; break;
            case TOUCH_KEYBOARD: res = R.string.pocket_short_touch_keyboard; break;
            case MOUSE: res = R.string.pocket_short_mouse; break;
            case ANDROID_HOME: res = R.string.pocket_short_androidhome; break;
            case DISCONNECT: res = R.string.pocket_short_disconnect; break;
            case QUIT: res = R.string.pocket_short_quit; break;
            default: res = label; break;
        }
        return context.getString(res);
    }

    /** Actions offered for a button gesture. */
    public static List<PocketAction> forButtons() {
        List<PocketAction> list = new ArrayList<>();
        for (PocketAction a : values()) {
            list.add(a);
        }
        return list;
    }

    /** Actions offered for a radial slot (no nested radial, no "default"). */
    public static List<PocketAction> forRadial() {
        List<PocketAction> list = new ArrayList<>();
        for (PocketAction a : values()) {
            if (a != RADIAL && a != DEFAULT) {
                list.add(a);
            }
        }
        return list;
    }
}
