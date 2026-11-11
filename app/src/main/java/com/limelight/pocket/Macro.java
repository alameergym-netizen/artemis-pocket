package com.limelight.pocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A recorded sequence of controller buttons (for M1 / M2, a press of another button or a radial
 * slot): the set of buttons held changes at given times. Stored as text
 * ("hexFlags@ms;hexFlags@ms;..."), always ending with everything released. Pure Java (tested).
 */
public final class Macro {

    public static final int MAX_STEPS = 96;
    public static final int MAX_MS = 10_000;

    /** From atMs on, exactly these buttons are held (ControllerPacket flags). */
    public static final class Step {
        public final int flags;
        public final int atMs;

        public Step(int flags, int atMs) {
            this.flags = flags;
            this.atMs = atMs;
        }
    }

    private final List<Step> steps;

    private Macro(List<Step> steps) {
        this.steps = Collections.unmodifiableList(steps);
    }

    public List<Step> steps() {
        return steps;
    }

    public boolean isEmpty() {
        for (Step s : steps) {
            if (s.flags != 0) {
                return false;
            }
        }
        return true;
    }

    public int durationMs() {
        return steps.isEmpty() ? 0 : steps.get(steps.size() - 1).atMs;
    }

    /** Every button the macro ever presses. */
    public int allFlags() {
        int all = 0;
        for (Step s : steps) {
            all |= s.flags;
        }
        return all;
    }

    public String encode() {
        StringBuilder b = new StringBuilder();
        for (Step s : steps) {
            if (b.length() > 0) {
                b.append(';');
            }
            b.append(Integer.toHexString(s.flags)).append('@').append(s.atMs);
        }
        return b.toString();
    }

    /** Reads a stored macro (null for nothing or garbage). Steps are tidied the same way recording does. */
    public static Macro parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        Builder builder = new Builder();
        for (String part : text.split(";")) {
            int at = part.indexOf('@');
            if (at <= 0) {
                return null;
            }
            try {
                int flags = (int) Long.parseLong(part.substring(0, at).trim(), 16);
                int ms = Integer.parseInt(part.substring(at + 1).trim());
                if (ms < 0) {
                    return null;
                }
                builder.state(flags, ms);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        Macro m = builder.build();
        return m.isEmpty() ? null : m;
    }

    /**
     * Builds a macro from button states as they happen (times in ms from the start): repeated
     * states are dropped, time never goes backwards, nothing goes past MAX_MS or MAX_STEPS, and
     * it ends with everything released (shortly after the last change).
     */
    public static final class Builder {
        private final List<Step> steps = new ArrayList<>();
        private int last;
        private int lastMs;
        private int origin = -1;
        private boolean full;

        /** These buttons are held from this moment on (ms on any clock that only goes forward). */
        public Builder state(int flags, int ms) {
            if (full) {
                return this;
            }
            if (steps.isEmpty()) {
                if (flags == 0) {
                    return this;
                }
                // A macro starts the moment its first button goes down
                origin = ms;
            }
            int rel = Math.max(ms - origin, lastMs);
            if (rel > MAX_MS) {
                full = true;
                return this;
            }
            if (!steps.isEmpty() && flags == last) {
                return this;
            }
            if (steps.size() >= MAX_STEPS - 1) {
                full = true;
                return this;
            }
            steps.add(new Step(flags, rel));
            last = flags;
            lastMs = rel;
            return this;
        }

        /** True once it can't take more (too long or too many changes). */
        public boolean isFull() {
            return full;
        }

        /** The macro (ending with everything released). */
        public Macro build() {
            List<Step> out = new ArrayList<>(steps);
            if (!out.isEmpty() && last != 0) {
                out.add(new Step(0, Math.min(MAX_MS, lastMs + 120)));
            }
            return new Macro(out);
        }
    }

    // ---- For people -------------------------------------------------------------------------

    private static final int[] FLAG_ORDER = {
            0x1000, 0x2000, 0x4000, 0x8000, // A B X Y
            0x0100, 0x0200, 0x0040, 0x0080, // LB RB LS RS
            0x0001, 0x0002, 0x0004, 0x0008, // D-pad
            0x0020, 0x0010, 0x0400,         // View Menu Guide
    };
    private static final String[] FLAG_NAMES = {
            "A", "B", "X", "Y", "LB", "RB", "L3", "R3", "↑", "↓", "←", "→", "View", "Menu", "Guide",
    };

    /** "LB+RB" for a set of buttons. */
    public static String describeFlags(int flags) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < FLAG_ORDER.length; i++) {
            if ((flags & FLAG_ORDER[i]) != 0) {
                if (b.length() > 0) {
                    b.append('+');
                }
                b.append(FLAG_NAMES[i]);
            }
        }
        return b.toString();
    }

    /** "A → LB+RB → B · 1.4 s": the presses in order (at most a few), then how long it takes. */
    public String describe() {
        List<String> presses = new ArrayList<>();
        int held = 0;
        for (Step s : steps) {
            int pressedNow = s.flags & ~held;
            if (pressedNow != 0) {
                presses.add(describeFlags(s.flags));
            }
            held = s.flags;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < presses.size() && i < 6; i++) {
            if (i > 0) {
                b.append(" → ");
            }
            b.append(presses.get(i));
        }
        if (presses.size() > 6) {
            b.append(" …");
        }
        b.append(String.format(Locale.ROOT, "  ·  %.1f s", durationMs() / 1000f));
        return b.toString();
    }
}
