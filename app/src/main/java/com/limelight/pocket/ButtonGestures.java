package com.limelight.pocket;

import android.os.Handler;
import android.os.Looper;

/**
 * Turns raw down/up events of one button into tap, double press and hold, each with its own
 * action. Rules:
 *  - If only "tap" is set to DEFAULT and nothing else is assigned, the button passes straight
 *    through (down/up as they happen) so it can still be held in games.
 *  - A tap waits for a possible second press only if a double-press action exists.
 *  - Hold fires after HOLD_MS. When the hold action is the radial menu, the menu stays open
 *    while the button is held and the highlighted slot runs on release.
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

    private boolean isPlainPassThrough() {
        return tap == PocketAction.DEFAULT && doubleTap == PocketAction.NONE && hold == PocketAction.NONE;
    }

    public void onDown() {
        if (down) {
            return; // key repeat
        }
        down = true;
        holdFired = false;

        if (isPlainPassThrough()) {
            passingThrough = true;
            callbacks.passThrough(button, true);
            return;
        }
        passingThrough = false;

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
        if (holdFired) {
            holdFired = false;
            if (hold == PocketAction.RADIAL) {
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
        down = false;
        holdFired = false;
        passingThrough = false;
        tapPending = false;
        secondPress = false;
    }
}
