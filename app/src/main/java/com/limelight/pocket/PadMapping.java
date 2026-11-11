package com.limelight.pocket;

import com.limelight.nvstream.input.ControllerPacket;

/**
 * Which controller buttons an action presses on the PC (0 for actions that aren't controller
 * buttons). Such actions can be held: the PC sees them down exactly as long as the button.
 * Pure Java (tested).
 */
public final class PadMapping {

    private PadMapping() {
    }

    public static int flags(PocketAction action) {
        if (action == null) {
            return 0;
        }
        switch (action) {
            case GUIDE: return ControllerPacket.SPECIAL_BUTTON_FLAG;
            case VIEW: return ControllerPacket.BACK_FLAG;
            case START: return ControllerPacket.PLAY_FLAG;
            case PAD_A: return ControllerPacket.A_FLAG;
            case PAD_B: return ControllerPacket.B_FLAG;
            case PAD_X: return ControllerPacket.X_FLAG;
            case PAD_Y: return ControllerPacket.Y_FLAG;
            case PAD_LB: return ControllerPacket.LB_FLAG;
            case PAD_RB: return ControllerPacket.RB_FLAG;
            case PAD_L3: return ControllerPacket.LS_CLK_FLAG;
            case PAD_R3: return ControllerPacket.RS_CLK_FLAG;
            case PAD_UP: return ControllerPacket.UP_FLAG;
            case PAD_DOWN: return ControllerPacket.DOWN_FLAG;
            case PAD_LEFT: return ControllerPacket.LEFT_FLAG;
            case PAD_RIGHT: return ControllerPacket.RIGHT_FLAG;
            case COMBO_VIEW_START: return ControllerPacket.BACK_FLAG | ControllerPacket.PLAY_FLAG;
            case COMBO_LB_RB: return ControllerPacket.LB_FLAG | ControllerPacket.RB_FLAG;
            case COMBO_L3_R3: return ControllerPacket.LS_CLK_FLAG | ControllerPacket.RS_CLK_FLAG;
            default: return 0;
        }
    }

    /** True for an action that is one or more controller buttons. */
    public static boolean isPad(PocketAction action) {
        return flags(action) != 0;
    }

    /** The flags that changed between two button sets, one by one (lowest first). */
    public static int[] changed(int before, int after) {
        int diff = before ^ after;
        int[] out = new int[Integer.bitCount(diff)];
        int n = 0;
        while (diff != 0) {
            int bit = Integer.lowestOneBit(diff);
            out[n++] = bit;
            diff &= ~bit;
        }
        return out;
    }
}
