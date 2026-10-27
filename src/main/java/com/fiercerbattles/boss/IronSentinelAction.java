package com.fiercerbattles.boss;

/**
 * Clip each Iron Sentinel action plays. Shared by both sides: the server keeps the frame logic in
 * {@link IronSentinel}, the client samples {@code assets/fiercerbattles/iron_sentinel/clips/<clip>.json}.
 * Clips come from BOSS/钢铁卫戍/动作/iron_sentinel_<clip>.blend at 20 FPS, so a Blender frame is a game tick; an
 * action may play its clip faster (IronSentinel#clipTime), and the cannon actions play parts of one clip.
 */
public enum IronSentinelAction {
    /** Phase 1, fists: breathing, looped on the world clock. */
    IDLE("idle", Playback.CYCLE),
    WALK("walk", Playback.LOOP),
    /** Phase 2, the chainsaw out. */
    SAW_IDLE("saw_idle", Playback.CYCLE),
    SAW_WALK("saw_walk", Playback.LOOP),
    ATTACK_LEFT("attack_left", Playback.ONCE),
    ATTACK_RIGHT("attack_right", Playback.ONCE),
    /** Phase 2 begins: the left fist splits into the chainsaw. */
    TRANSFORM("saw_transform", Playback.ONCE),
    /** The saw combo: first, second and third segment. */
    SWEEP_LR("saw_sweep_lr", Playback.ONCE),
    SWEEP_RL("saw_sweep_rl", Playback.ONCE),
    THRUST("saw_thrust", Playback.ONCE),
    /** The thrust connected: the victim is lifted on the saw, sawn and flung (started at the thrust's hit frame). */
    IMPALE("saw_impale", Playback.ONCE),
    /** Parried; executable (FiercerBattlesExecutionServer, via ExecutionTarget) until it starts to recover. */
    STAGGER("stagger", Playback.ONCE),
    SAW_STAGGER("saw_stagger", Playback.ONCE),
    /** The sentinel's half of the riposte execution; the player's half is the paired action iron_sentinel_execution. */
    EXECUTED("executed", Playback.ONCE),
    SAW_EXECUTED("saw_executed", Playback.ONCE),
    /** The cannon skill, split out of one clip: the fist opens into the cannon (frames 0-48)... */
    CANNON_DEPLOY("skill_cannon", Playback.ONCE),
    SAW_CANNON_DEPLOY("saw_cannon", Playback.ONCE),
    /** ...one shot (frames 48-62) looped once per shot... */
    CANNON_FIRE("skill_cannon", Playback.FIRE),
    SAW_CANNON_FIRE("saw_cannon", Playback.FIRE),
    /** ...three quick shots into the sky (their own clip, from and back to the braced aim)... */
    CANNON_SKY("cannon_sky", Playback.ONCE),
    SAW_CANNON_SKY("saw_cannon_sky", Playback.ONCE),
    /** ...and the cannon folding back into a fist (frames 84-118, started mid-clip). */
    CANNON_RETRACT("skill_cannon", Playback.ONCE),
    SAW_CANNON_RETRACT("saw_cannon", Playback.ONCE),
    /** A target closing in while the cannon fires is met with the saw thrust, the cannon kept out. */
    CANNON_THRUST("saw_cannon_thrust", Playback.ONCE),
    CANNON_IMPALE("saw_cannon_impale", Playback.ONCE);

    /** LOOP follows the distance walked, CYCLE the world clock, FIRE repeats the cannon's shot frames. */
    public enum Playback { ONCE, LOOP, CYCLE, FIRE }

    private static final IronSentinelAction[] VALUES = values();
    public final String clip;
    public final Playback playback;

    IronSentinelAction(String clip, Playback playback) {
        this.clip = clip;
        this.playback = playback;
    }

    public boolean locomotion() { return this == IDLE || this == WALK || this == SAW_IDLE || this == SAW_WALK; }

    public boolean staggered() { return this == STAGGER || this == SAW_STAGGER; }

    public boolean executed() { return this == EXECUTED || this == SAW_EXECUTED; }

    public boolean impaling() { return this == IMPALE || this == CANNON_IMPALE; }

    public static IronSentinelAction byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : IDLE;
    }
}
