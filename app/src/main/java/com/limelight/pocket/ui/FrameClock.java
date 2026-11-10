package com.limelight.pocket.ui;

import android.view.Choreographer;

/**
 * Drives a hand-stepped animation on the display's frame clock: it asks for the next frame and
 * keeps that frame's vsync time, so a spring is stepped by the time the frame is actually shown
 * (like the system's own animations), not by whenever the draw happens to run. A late frame then
 * still shows things where they belong, instead of one part lagging and then jumping.
 */
final class FrameClock implements Choreographer.FrameCallback {

    /** Older than this, a vsync can't belong to the frame being drawn. */
    private static final long STALE_NS = 50_000_000L;

    private final Runnable onFrame;
    private boolean posted;
    private long vsync;

    FrameClock(Runnable onFrame) {
        this.onFrame = onFrame;
    }

    /** Asks for the next frame; onFrame runs at its start (before it is drawn). */
    void request() {
        if (!posted) {
            posted = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    void cancel() {
        if (posted) {
            Choreographer.getInstance().removeFrameCallback(this);
            posted = false;
        }
        vsync = 0;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        posted = false;
        vsync = frameTimeNanos;
        onFrame.run();
    }

    /** The vsync of the frame being drawn when this clock asked for it, else the time now. */
    long now() {
        long v = vsync;
        vsync = 0;
        long t = System.nanoTime();
        return v != 0 && t - v < STALE_NS ? v : t;
    }
}
