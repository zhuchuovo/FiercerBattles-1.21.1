package com.fiercerbattles.locomotion;

/** Gameplay clocks around the in-place locomotion clips; the clips themselves never move the entity. */
public final class LocomotionTimeline {
    public static final int ROLL_IN_TICKS = 2;
    public static final int ROLL_OUT_TICKS = 3;
    public static final int ROLL_COOLDOWN_TICKS = 10;
    /** Roll clip frames played per game tick; every roll clock below is authored in clip frames. */
    public static final float ROLL_PLAYBACK_SPEED = 1.25F;
    /** From this clip frame the roller may move or act again; doing so ends the rest of the roll at once. */
    public static final int ROLL_RECOVERY_FRAME = 12;
    /** Rolling ignores damage before this clip frame (falls and damage that bypasses invulnerability still apply). */
    public static final int ROLL_INVULNERABLE_FRAMES = 12;
    /** Deepest first-person eye drop in blocks; the dip is back to zero at the recovery frame. */
    public static final double ROLL_CAMERA_DROP = 1.0;
    /** Blocks travelled over one roll clip; velocity follows a sine bump, fastest mid-roll. */
    public static final double ROLL_DISTANCE = 3.0;
    public static final int PRONE_IN_TICKS = 2;
    /** Sprinting into prone dives forward this far over the drop-down clip, fastest at the start. */
    public static final double PRONE_SLIDE_DISTANCE = 2.0;
    /** prone_down clip tick at which the torso meets the ground and kicks up dust. */
    public static final int PRONE_IMPACT_TICK = 9;
    public static final int CRAWL_CROSSFADE_TICKS = 4;
    public static final int STAND_CROSSFADE_TICKS = 3;
    public static final int STAND_OUT_TICKS = 3;
    public static final int RUN_FADE_TICKS = 3;
    /** Clip ticks per unit of vanilla walk-animation position (sprinting advances it by about 1 per tick). */
    public static final float RUN_TICKS_PER_STRIDE = 1.2F;
    /** Crawling advances walk position by about 0.26 per tick; this gives one crawl cycle per ~0.7 s. */
    public static final float CRAWL_TICKS_PER_STRIDE = 5.5F;
    public static final float CRAWL_HEAD_YAW_LIMIT = 60.0F;

    private LocomotionTimeline() {}

    public static float progress(float elapsed, int ticks) {
        float linear = Math.max(0, Math.min(1, elapsed / ticks));
        return linear * linear * (3 - 2 * linear);
    }

    /** Game ticks taken by {@code frames} roll clip frames. */
    public static float rollTicks(float frames) { return frames / ROLL_PLAYBACK_SPEED; }

    /** Roll clip frame shown {@code elapsed} game ticks into the roll. */
    public static float rollFrame(float elapsed) { return elapsed * ROLL_PLAYBACK_SPEED; }

    public static boolean rollRecovering(float elapsed) { return elapsed >= rollTicks(ROLL_RECOVERY_FRAME); }

    public static boolean rollInvulnerable(float elapsed) { return elapsed < rollTicks(ROLL_INVULNERABLE_FRAMES); }

    /** Distance along the roll heading after {@code ticks} of a roll lasting {@code duration} game ticks. */
    public static double rollTravel(double ticks, double duration) {
        double t = Math.max(0, Math.min(1, ticks / duration));
        return ROLL_DISTANCE * (1 - Math.cos(Math.PI * t)) / 2;
    }

    /** Distance along the slide heading after {@code ticks} of a sprinting dive lasting {@code duration} ticks. */
    public static double proneSlideTravel(double ticks, double duration) {
        double t = Math.max(0, Math.min(1, ticks / duration));
        return PRONE_SLIDE_DISTANCE * (1 - (1 - t) * (1 - t));
    }

    /** First-person eye drop after {@code elapsed} roll ticks: eases down, then back up by the recovery frame. */
    public static double rollCameraDrop(float elapsed) {
        double t = elapsed / rollTicks(ROLL_RECOVERY_FRAME);
        return t <= 0 || t >= 1 ? 0 : ROLL_CAMERA_DROP * (1 - Math.cos(2 * Math.PI * t)) / 2;
    }

    /** Wrap a stride-driven clock into the clip's authored loop range. */
    public static float loopTick(float clock, int loopStart, int loopEnd) {
        float length = loopEnd - loopStart;
        float wrapped = clock % length;
        return loopStart + (wrapped < 0 ? wrapped + length : wrapped);
    }
}
