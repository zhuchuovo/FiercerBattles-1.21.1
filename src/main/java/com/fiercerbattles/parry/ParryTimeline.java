package com.fiercerbattles.parry;

/** Transition clocks belong to the gesture; the animation core remains unchanged. */
public final class ParryTimeline {
    public static final int IN_TICKS = 3;
    public static final int REVERSE_TICKS = 5;
    public static final int RETURN_TICKS = 5;
    /** STAGGER skips source frame 0 and begins at source frame 1 (the second frame). */
    public static final int STAGGER_START_FRAME_TICK = 1;
    public static final int WINDOW_START = 5;
    public static final int WINDOW_END = 12;
    /** The hitstun flinch fades in over its first tick and back to the ordinary pose over its last two. */
    public static final int HITSTUN_IN_TICKS = 1;
    public static final int HITSTUN_OUT_TICKS = 2;
    /** Hitstun always holds the victim this long; the authored clip is stretched to fit. */
    public static final int HITSTUN_TICKS = 10;
    /** This many flinches inside the chain window grant a short immunity, so a victim cannot be stunlocked. */
    public static final int HITSTUN_CHAIN_LIMIT = 3;
    public static final int HITSTUN_CHAIN_WINDOW_TICKS = 40;
    public static final int HITSTUN_IMMUNITY_TICKS = 20;

    private ParryTimeline() {}

    public static float progress(float elapsed, int ticks) {
        if (ticks <= 0) return elapsed >= 0 ? 1 : 0;
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

    public static int clipStartOffsetTicks(ParryNetwork.Kind kind) {
        return kind == ParryNetwork.Kind.STAGGER ? STAGGER_START_FRAME_TICK : 0;
    }

    public static float clipTick(ParryNetwork.Kind kind, long start, long now, float partial, int duration) {
        return Math.max(0, Math.min(duration,
                now - start - reverseTicks(kind) + clipStartOffsetTicks(kind) + partial));
    }

    public static int inTicks(ParryNetwork.Kind kind) {
        return kind == ParryNetwork.Kind.HITSTUN ? HITSTUN_IN_TICKS : IN_TICKS;
    }

    /** Fade back to the ordinary pose inside the clip, so the action ends with the clip. */
    public static int fadeOutTicks(ParryNetwork.Kind kind) {
        return kind == ParryNetwork.Kind.HITSTUN ? HITSTUN_OUT_TICKS : 0;
    }

    public static int returnTicks(ParryNetwork.Kind kind) {
        return kind == ParryNetwork.Kind.HITSTUN ? 0 : RETURN_TICKS;
    }

    public static int totalTicks(ParryNetwork.Kind kind, int duration) {
        if (kind == ParryNetwork.Kind.HITSTUN) return HITSTUN_TICKS;
        return reverseTicks(kind) + duration - clipStartOffsetTicks(kind) + returnTicks(kind);
    }

    /** Clip ticks played per game tick: hitstun is stretched to {@link #HITSTUN_TICKS}, every other kind plays 1:1. */
    public static float playbackRate(ParryNetwork.Kind kind, int duration) {
        return kind == ParryNetwork.Kind.HITSTUN ? (float) duration / HITSTUN_TICKS : 1.0F;
    }
}
