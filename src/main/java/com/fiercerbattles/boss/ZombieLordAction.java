package com.fiercerbattles.boss;

/**
 * Clip each Zombie Lord action plays. Shared by both sides: the server keeps the frame logic in
 * {@link ZombieLord}, the client samples {@code assets/fiercerbattles/zombie_lord/clips/<clip>.json}.
 * Clips come from 敌军动作/动作/zombie_lord_<clip>.blend at 20 FPS, so a Blender frame is a game tick.
 */
public enum ZombieLordAction {
    /** Breathing in the combat stance, looped on the world clock. */
    IDLE("idle", Playback.CYCLE),
    WALK("walk", Playback.LOOP),
    ATTACK1("attack1", Playback.ONCE),
    ATTACK2("attack2", Playback.ONCE),
    ATTACK3("attack3", Playback.ONCE),
    /** The in-place variant: the entity itself flies to the locked target between take-off and landing. */
    LEAP_SLAM("leap_slam_inplace", Playback.LEAP),
    /** Plays to {@link ZombieLord#QUAKE_FREEZE_FRAME} and holds there for the whole skull summon. */
    QUAKE("quake", Playback.HOLD_AT_FREEZE),
    /** The rest of the quake clip after the freeze frame; the server starts it mid-clip. */
    QUAKE_END("quake", Playback.ONCE),
    /** Wind-up, then the dash cycle looped for as long as the dash lasts. */
    CHARGE("charge", ZombieLord.CHARGE_WINDUP_FRAMES, ZombieLord.CHARGE_CYCLE_FRAMES,
            ZombieLord.CHARGE_SPEED / ZombieLord.CHARGE_CLIP_SPEED),
    /** The charge clip after the dash (slam and recovery), started mid-clip by the server. */
    CHARGE_END("charge", Playback.ONCE),
    /** Dazed after charging into a wall; the clip is the whole stun. */
    STUN("stun", Playback.ONCE),
    /** Lunge with the free hand after attack3's slam; a catch switches to THROW at the reach frame. */
    GRAB("grab", Playback.ONCE),
    /** The caught victim is slammed down and hurled; it hangs from the left fist until the release frame. */
    THROW("throw", Playback.ONCE),
    /** Ring slash: sword raised, then the run cycle looped while the boss sprints at the target. */
    RING_RUN("ring_slash", ZombieLord.RING_WINDUP_FRAMES, ZombieLord.RING_CYCLE_FRAMES,
            ZombieLord.RING_SPEED / ZombieLord.RING_CLIP_SPEED),
    /** The ring slash clip from its spin pose (the server turns the boss through the spin), started mid-clip. */
    RING_SPIN("ring_slash", Playback.ONCE),
    /** Roar, burning blade, three leaping slashes and a charged leap; the server flies the boss. */
    GHOST_BLADE("ghost_blade", Playback.ONCE),
    /** Phase transition: kneel, the breastplate bursts, roar. */
    TRANSITION("transition", Playback.ONCE),
    /** Never sent by the server: the client plays it from the entity's death time. */
    DEATH("death", Playback.ONCE);

    /**
     * LOOP follows the distance walked, CYCLE the world clock. DASH plays a wind-up, then loops one cycle of the clip
     * at the rate the boss covers ground (cycle frames per tick).
     */
    public enum Playback { ONCE, LOOP, CYCLE, HOLD_AT_FREEZE, LEAP, DASH }

    private static final ZombieLordAction[] VALUES = values();
    public final String clip;
    public final Playback playback;
    /** DASH only: frames of the wind-up, of the looped cycle, and cycle frames played per tick. */
    public final int windup, cycle;
    public final double cycleRate;

    ZombieLordAction(String clip, Playback playback) {
        this(clip, playback, 0, 0, 0.0);
    }

    ZombieLordAction(String clip, int windup, int cycle, double cycleRate) {
        this(clip, Playback.DASH, windup, cycle, cycleRate);
    }

    ZombieLordAction(String clip, Playback playback, int windup, int cycle, double cycleRate) {
        this.clip = clip;
        this.playback = playback;
        this.windup = windup;
        this.cycle = cycle;
        this.cycleRate = cycleRate;
    }

    public boolean locomotion() { return this == IDLE || this == WALK; }

    public static ZombieLordAction byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : IDLE;
    }
}
