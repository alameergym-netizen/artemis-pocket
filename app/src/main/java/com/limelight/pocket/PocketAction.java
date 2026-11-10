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
    QUIT("quit", R.string.pocket_act_quit);

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
