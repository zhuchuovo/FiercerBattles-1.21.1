package com.fiercerbattles.parry;

/** Transition clocks belong to the gesture; the animation core remains unchanged. */
public final class ParryTimeline {
    public static final int IN_TICKS = 3;
    public static final int REVERSE_TICKS = 5;
    public static final int RETURN_TICKS = 5;
    public static final int WINDOW_START = 5;
    public static final int WINDOW_END = 12;

    private ParryTimeline() {}

    public static float progress(float elapsed, int ticks) {
        float linear = Math.max(0, Math.min(1, elapsed / ticks));
        return linear * linear * (3 - 2 * linear);
    }

    public static boolean canParry(long start, long now) {
        long frame = now - start;
        return frame >= WINDOW_START && frame <= WINDOW_END;
    }

    public static int reverseTicks(ParryNetwork.Kind kind) {
        return kind == ParryNetwork.Kind.STAGGER ? REVERSE_TICKS : 0;
    }

    public static float clipTick(ParryNetwork.Kind kind, long start, long now, float partial, int duration) {
        return Math.max(0, Math.min(duration, now - start - reverseTicks(kind) + partial));
    }

    public static int totalTicks(ParryNetwork.Kind kind, int duration) {
        return reverseTicks(kind) + duration + RETURN_TICKS;
    }
}
