package com.limelight.pocket;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.input.ControllerPacket;

import org.junit.Test;

/** Macros (recording, storing, describing) and which controller buttons actions press. */
public class MacroTest {

    private static final int A = ControllerPacket.A_FLAG;
    private static final int B = ControllerPacket.B_FLAG;
    private static final int LB = ControllerPacket.LB_FLAG;
    private static final int RB = ControllerPacket.RB_FLAG;

    @Test
    public void recording_startsAtFirstPressAndEndsReleased() {
        Macro m = new Macro.Builder()
                .state(0, 500)        // nothing yet: ignored
                .state(A, 1000)       // the macro starts here
                .state(A, 1050)       // same state: dropped
                .state(0, 1100)
                .state(LB | RB, 1300)
                .build();
        assertEquals(4, m.steps().size());
        assertEquals(A, m.steps().get(0).flags);
        assertEquals(0, m.steps().get(0).atMs);
        assertEquals(100, m.steps().get(1).atMs);
        assertEquals(LB | RB, m.steps().get(2).flags);
        assertEquals(300, m.steps().get(2).atMs);
        // Still held at the end: released shortly after
        assertEquals(0, m.steps().get(3).flags);
        assertEquals(420, m.steps().get(3).atMs);
        assertEquals(420, m.durationMs());
        assertEquals(A | LB | RB, m.allFlags());
        assertFalse(m.isEmpty());
    }

    @Test
    public void recording_neverGoesBackInTimeOrPastTheLimits() {
        Macro.Builder b = new Macro.Builder().state(A, 1000).state(0, 900);
        Macro m = b.build();
        assertEquals(0, m.steps().get(1).atMs);

        Macro.Builder longOne = new Macro.Builder().state(A, 0).state(0, 100).state(B, Macro.MAX_MS + 1);
        assertTrue(longOne.isFull());
        assertEquals(2, longOne.build().steps().size());

        Macro.Builder many = new Macro.Builder();
        for (int i = 0; i < Macro.MAX_STEPS * 2; i++) {
            many.state(i % 2 == 0 ? A : B, i * 10);
        }
        assertTrue(many.isFull());
        Macro capped = many.build();
        assertTrue(capped.steps().size() <= Macro.MAX_STEPS);
        assertEquals(0, capped.steps().get(capped.steps().size() - 1).flags);
    }

    @Test
    public void encodeAndParse_roundTrip() {
        Macro m = new Macro.Builder().state(A, 0).state(0, 90).state(LB | RB, 200).state(0, 350).build();
        String text = m.encode();
        assertEquals("1000@0;0@90;300@200;0@350", text);
        Macro back = Macro.parse(text);
        assertNotNull(back);
        assertEquals(text, back.encode());
    }

    @Test
    public void parse_rejectsGarbageAndEmpty() {
        assertNull(Macro.parse(null));
        assertNull(Macro.parse(""));
        assertNull(Macro.parse("hello"));
        assertNull(Macro.parse("1000@x"));
        assertNull(Macro.parse("1000@-5"));
        assertNull(Macro.parse("0@0;0@100"));
    }

    @Test
    public void describe_listsPressesInOrder() {
        Macro m = new Macro.Builder().state(A, 0).state(0, 100).state(LB | RB, 200).state(LB | RB | B, 300).state(0, 1400).build();
        assertEquals("A → LB+RB → B+LB+RB  ·  1.4 s", m.describe());
        assertEquals("", Macro.describeFlags(0));
        assertEquals("A+B", Macro.describeFlags(A | B));
    }

    @Test
    public void padMapping_controllerButtonsAndPairs() {
        assertEquals(ControllerPacket.A_FLAG, PadMapping.flags(PocketAction.PAD_A));
        assertEquals(ControllerPacket.LS_CLK_FLAG, PadMapping.flags(PocketAction.PAD_L3));
        assertEquals(ControllerPacket.BACK_FLAG | ControllerPacket.PLAY_FLAG, PadMapping.flags(PocketAction.COMBO_VIEW_START));
        assertEquals(ControllerPacket.SPECIAL_BUTTON_FLAG, PadMapping.flags(PocketAction.GUIDE));
        assertEquals(0, PadMapping.flags(PocketAction.SCREENSHOT));
        assertEquals(0, PadMapping.flags(PocketAction.MACRO_1));
        assertEquals(0, PadMapping.flags(null));
        assertTrue(PadMapping.isPad(PocketAction.PAD_RIGHT));
        assertFalse(PadMapping.isPad(PocketAction.QUICK_MENU));
    }

    @Test
    public void padMapping_changedBitsOneByOne() {
        assertArrayEquals(new int[]{LB, RB}, PadMapping.changed(0, LB | RB));
        assertArrayEquals(new int[]{A}, PadMapping.changed(A | LB, LB));
        assertArrayEquals(new int[0], PadMapping.changed(B, B));
    }

    @Test
    public void gestures_anyControllerButtonCanBeHeldLive() {
        // M1 / M2 set to a controller button act as that button (like a paddle)
        assertTrue(ButtonGestures.isLive(PocketAction.PAD_L3, PocketAction.NONE, PocketAction.NONE));
        assertTrue(ButtonGestures.isLive(PocketAction.COMBO_LB_RB, PocketAction.NONE, PocketAction.COMBO_LB_RB));
        assertTrue(ButtonGestures.isLive(PocketAction.GUIDE, PocketAction.NONE, PocketAction.NONE));
        // ...but not when another gesture does something else
        assertFalse(ButtonGestures.isLive(PocketAction.PAD_A, PocketAction.SCREENSHOT, PocketAction.NONE));
        assertFalse(ButtonGestures.isLive(PocketAction.MACRO_2, PocketAction.NONE, PocketAction.NONE));
    }

    @Test
    public void actions_macroSlots() {
        assertEquals(0, PocketAction.MACRO_1.macroSlot());
        assertEquals(3, PocketAction.MACRO_4.macroSlot());
        assertEquals(-1, PocketAction.PAD_A.macroSlot());
        assertEquals(PocketAction.PAD_R3, PocketAction.fromId("pad_r3", PocketAction.NONE));
        assertEquals(PocketAction.MACRO_3, PocketAction.fromId("macro3", PocketAction.NONE));
    }
}
