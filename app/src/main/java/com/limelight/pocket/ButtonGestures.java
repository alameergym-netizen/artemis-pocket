package com.limelight.pocket;

import android.os.Handler;
import android.os.Looper;

/**
 * Turns raw down/up events of one button into tap, double press and hold, each with its own
 * action. Rules:
 *  - If only "tap" is set to DEFAULT and nothing else is assigned, the button passes straight
 *    through (down/up as they happen) so it can still be held in games.
 *  - "Live" controller buttons: when tap is a controller button (Guide, View, Start, A, LB, L3,
 *    D-pad... or a pair) and double press and hold are empty or the same, the press goes to the
 *    PC exactly as long as it is held (M1 / M2 become extra buttons, like paddles).
 *  - A tap waits for a possible second press only if a double-press action exists.
 *  - Hold fires after HOLD_MS. Guide, View or Start as the hold action stay pressed on the PC
 *    until the button is let go. The radial menu stays open while the button is held and the
 *    highlighted slot runs on release.
 */
public class ButtonGestures {

    public interface Callbacks {
        /** Run a finished gesture's action. */
        void runAction(int button, PocketAction action);

        /** Pass-through press/release of the button's original function. */
        void passThrough(int button, boolean down);

        /** Radial menu shown while the button is held; release picks the slot. */
        void openRadialHeld(int button);

        void releaseRadialHeld(int button);

        /** Press (down) or release a controller button action that can be held on the PC. */
        void holdAction(int button, PocketAction action, boolean down);
    }

    private static final long HOLD_MS = 420;
    private static final long DOUBLE_MS = 260;

    private final int button;
    private final Callbacks callbacks;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private PocketAction tap = PocketAction.DEFAULT;
    private PocketAction doubleTap = PocketAction.NONE;
    private PocketAction hold = PocketAction.NONE;

    private boolean down;
    private boolean holdFired;
    private boolean passingThrough;
    private PocketAction liveAction;
    private PocketAction heldAction;
    private boolean tapPending;
    private boolean secondPress;

    private final Runnable holdRunnable = new Runnable() {
        @Override
        public void run() {
            if (!down) {
                return;
            }
            holdFired = true;
            tapPending = false;
            handler.removeCallbacks(tapRunnable);
            if (hold == PocketAction.RADIAL) {
                callbacks.openRadialHeld(button);
            }
            else if (canBeHeld(hold)) {
                heldAction = hold;
                callbacks.holdAction(button, hold, true);
            }
            else {
                callbacks.runAction(button, hold);
            }
        }
    };

    private final Runnable tapRunnable = new Runnable() {
        @Override
        public void run() {
            if (tapPending) {
                tapPending = false;
                callbacks.runAction(button, tap);
            }
        }
    };

    public ButtonGestures(int button, Callbacks callbacks) {
        this.button = button;
        this.callbacks = callbacks;
    }

    public void configure(PocketAction tap, PocketAction doubleTap, PocketAction hold) {
        this.tap = tap;
        this.doubleTap = doubleTap;
        this.hold = hold;
    }

    /** Controller buttons that can stay pressed on the PC for as long as the button is held. */
    public static boolean canBeHeld(PocketAction action) {
        // Any controller button (or pair): Guide, View, Start, A/B/X/Y, bumpers, sticks, D-pad
        return PadMapping.isPad(action);
    }

    /** True when this combination makes the button behave exactly like that controller button. */
    public static boolean isLive(PocketAction tap, PocketAction doubleTap, PocketAction hold) {
        return canBeHeld(tap) && (doubleTap == PocketAction.NONE || doubleTap == tap)
                && (hold == PocketAction.NONE || hold == tap);
    }

    private boolean isPlainPassThrough() {
        return tap == PocketAction.DEFAULT && doubleTap == PocketAction.NONE && hold == PocketAction.NONE;
    }

    public boolean isDown() {
        return down;
    }

    /**
     * A new press. Callers filter key repeats, so a press while we still think the button is
     * down means its release never reached us (some ROMs swallow the release of system keys).
     * Finish the old press first instead of ignoring the button forever.
     */
    public void onDown() {
        if (down) {
            onUp();
        }
        down = true;
        holdFired = false;

        if (isPlainPassThrough()) {
            passingThrough = true;
            callbacks.passThrough(button, true);
            return;
        }
        passingThrough = false;

        if (isLive(tap, doubleTap, hold)) {
            // Straight to the PC, no waiting: held exactly as long as the button
            tapPending = false;
            handler.removeCallbacks(tapRunnable);
            liveAction = tap;
            callbacks.holdAction(button, tap, true);
            return;
        }

        secondPress = tapPending;
        if (secondPress) {
            // Second press of a double press
            tapPending = false;
            handler.removeCallbacks(tapRunnable);
        }
        if (hold != PocketAction.NONE && !secondPress) {
            handler.postDelayed(holdRunnable, HOLD_MS);
        }
    }

    public void onUp() {
        if (!down) {
            return;
        }
        down = false;
        handler.removeCallbacks(holdRunnable);

        if (passingThrough) {
            passingThrough = false;
            callbacks.passThrough(button, false);
            return;
        }
        if (liveAction != null) {
            PocketAction released = liveAction;
            liveAction = null;
            callbacks.holdAction(button, released, false);
            return;
        }
        if (holdFired) {
            holdFired = false;
            if (heldAction != null) {
                PocketAction released = heldAction;
                heldAction = null;
                callbacks.holdAction(button, released, false);
            }
            else if (hold == PocketAction.RADIAL) {
                callbacks.releaseRadialHeld(button);
            }
            return;
        }
        if (secondPress) {
            secondPress = false;
            callbacks.runAction(button, doubleTap);
            return;
        }
        if (doubleTap != PocketAction.NONE) {
            tapPending = true;
            handler.postDelayed(tapRunnable, DOUBLE_MS);
        }
        else {
            callbacks.runAction(button, tap);
        }
    }

    public void cancel() {
        handler.removeCallbacks(holdRunnable);
        handler.removeCallbacks(tapRunnable);
        if (passingThrough) {
            callbacks.passThrough(button, false);
        }
        if (liveAction != null) {
            callbacks.holdAction(button, liveAction, false);
        }
        if (heldAction != null) {
            callbacks.holdAction(button, heldAction, false);
        }
        down = false;
        holdFired = false;
        passingThrough = false;
        liveAction = null;
        heldAction = null;
        tapPending = false;
        secondPress = false;
    }
}
