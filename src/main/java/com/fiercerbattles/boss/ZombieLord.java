package com.fiercerbattles.boss;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Zombie Lord boss. The server runs one action at a time and publishes it as (action, clip start
 * game time, blend ticks); the client samples the matching clip on the same world clock. All frame
 * numbers below are Blender frames of the 20 FPS sources in 敌军动作/动作, i.e. game ticks.
 */
public class ZombieLord extends Monster {
    private static final Logger LOGGER = LogUtils.getLogger();
    // Locomotion: always walks, 2.5 m/s (exact blocks per tick on flat ground).
    public static final double WALK_SPEED = 2.5 / 20.0;
    /** The one.blend rig is drawn 1.5x (敌军动作 README); the hitbox in BossEntities matches. */
    public static final float RENDER_SCALE = 1.5F;
    /** Ground speed the walk clip is authored for (zombie_lord README: 1.0 px per tick at scale 1). */
    public static final double WALK_CLIP_SPEED = 1.0 / 16.0 * RENDER_SCALE;

    // Three-hit combo.
    public static final double MELEE_TRIGGER_RANGE = 3.0;
    /** Each attack advances 1.5 blocks over its wind-up, before the blade comes round. */
    static final int STEP_TICKS = 10;
    static final double STEP_DISTANCE = 1.5;
    /** The step stops this far short of the target's hitbox, so the boss never walks into the target. */
    static final double STEP_MIN_GAP = 0.5;
    static final double SWEEP_RANGE = 3.0;
    static final float SWEEP_HALF_ANGLE = 60.0F;
    /** Each hit tick checks its slice of the arc plus this margin, so a target near a slice edge is not skipped. */
    static final float SWEEP_SLICE_MARGIN = 10.0F;
    static final float SWEEP_DAMAGE = 6.0F;
    static final int SWEEP_FIRST_FRAME = 10;
    static final int SWEEP_LAST_FRAME = 15;
    static final int ATTACK1_CHAIN_FRAME = 20;
    static final int CHAIN_BLEND_TICKS = 5;
    static final int ATTACK2_LENGTH = 32;
    static final int ATTACK3_SLAM_FRAME = 20;
    /** From this attack3 frame on, a target stepping away no longer turns it into a leap slam. */
    static final int ATTACK3_LEAP_CUTOFF_FRAME = 5;
    static final int ATTACK3_LENGTH = 40;
    static final double SLAM_RADIUS = 5.0;
    /** attack3's slam hits the front and both sides (270 degrees); the 90 degrees behind the boss are safe. */
    static final float SLAM_HALF_ARC = 135.0F;
    static final float SLAM_DAMAGE_NEAR = 10.0F;
    static final float SLAM_DAMAGE_FAR = 2.0F;

    // Leap slam onto a target up to 18 blocks away (measured to its near side): in place of attack3 when the target is
    // 5 blocks or more away, or while chasing as soon as it is 6 blocks or more away (half a second out of reach, so a
    // target only just stepping out is not leapt at). The ring slash ends in a leap at any range.
    static final double LEAP_MIN_DISTANCE = 6.0;
    static final double ATTACK3_LEAP_MIN_DISTANCE = 5.0;
    static final double LEAP_MAX_DISTANCE = 18.0;
    // Clip frames; the wind-up before take-off plays 50% slower in game (11 frames over 17 ticks).
    static final int LEAP_TAKEOFF_FRAME = 11;
    static final int LEAP_LAND_FRAME = 23;
    static final int LEAP_CLIP_LENGTH = 48;
    static final int LEAP_WINDUP_TICKS = 17;
    static final int LEAP_DELAY = LEAP_WINDUP_TICKS - LEAP_TAKEOFF_FRAME;
    static final int LEAP_TAKEOFF_TICK = LEAP_WINDUP_TICKS;
    static final int LEAP_LAND_TICK = LEAP_LAND_FRAME + LEAP_DELAY;
    static final int LEAP_LENGTH = LEAP_CLIP_LENGTH + LEAP_DELAY;
    static final double LEAP_RADIUS = 5.0;
    static final float LEAP_DAMAGE_NEAR = 7.0F;
    static final float LEAP_DAMAGE_FAR = 2.0F;
    static final int CHASE_LEAP_TICKS = 10;

    // Shoulder charge after three full rounds of the combo, four in phase 2 (attack1-2-3, or 1-2 and the leap that
    // replaced 3).
    static final int CHARGE_AFTER_ROUNDS = 3;
    static final int CHARGE_AFTER_ROUNDS_PHASE2 = 4;
    /** zombie_lord_charge: wind-up 0-18, one dash cycle 18-30 (looped while dashing), slam and recovery 30-57. */
    public static final int CHARGE_WINDUP_FRAMES = 18;
    public static final int CHARGE_CYCLE_FRAMES = 12;
    static final int CHARGE_RECOVER_FRAMES = 27;
    /** A dash that runs its full length ends in an overhead chop into the ground, this many ticks later (frame 37). */
    static final int CHARGE_SLAM_TICKS = 7;
    static final double CHARGE_SLAM_RADIUS = 5.0;
    static final float CHARGE_SLAM_DAMAGE_NEAR = 7.0F;
    static final float CHARGE_SLAM_DAMAGE_FAR = 2.0F;
    /** The wind-up keeps turning toward the target until this frame, then the line is fixed. */
    static final int CHARGE_AIM_LOCK_FRAME = 12;
    static final int CHARGE_DASH_TICKS = 16;
    /** Blocks per tick (12 m/s): 9.6 blocks over the dash. */
    public static final double CHARGE_SPEED = 0.6;
    /** Ground speed of the dash cycle at render scale (54 px per 12 tick cycle at scale 1). */
    public static final double CHARGE_CLIP_SPEED = 54.0 / 12.0 / 16.0 * RENDER_SCALE;
    static final float CHARGE_DAMAGE = 8.0F;
    static final double CHARGE_KNOCKBACK = 1.6;
    /** Client-side ground scar left by the dash. */
    public static final int CHARGE_TRAIL_TICKS = 60;
    /** Dash progress below this share of CHARGE_SPEED against a wall: the boss crashes into it instead of sliding along. */
    static final double CHARGE_WALL_PROGRESS = 0.6;

    // Stun after charging into a wall: zombie_lord_stun (80 frames) is the whole 4 s, getting up included.
    static final int STUN_TICKS = 4 * 20;
    /** The rebound off the wall, in blocks, over the first ticks of the stun. */
    static final double STUN_REBOUND = 1.0;
    static final int STUN_REBOUND_TICKS = 4;
    /** Clip frames between which stars circle the lolling head (until he shakes it off). */
    static final int STUN_DAZE_FROM = 6;
    static final int STUN_DAZE_TO = 68;

    // Grab and throw: when a leap slam has landed, an enemy right beside the boss is grabbed, slammed into the ground
    // and thrown.
    /** Leap ticks after the landing while the boss is still bent over the blow (it starts rising at clip frame 31). */
    static final int GRAB_WINDOW_FROM = LEAP_LAND_TICK + 1;
    static final int GRAB_WINDOW_TO = LEAP_LAND_TICK + 8;
    static final double GRAB_TRIGGER_RANGE = 3.0;
    /** zombie_lord_grab: turns toward the target until frame 6, the free hand is fully out at 9; a miss plays to 30. */
    static final int GRAB_AIM_LOCK_FRAME = 6;
    static final int GRAB_REACH_FRAME = 9;
    static final int GRAB_LENGTH = 30;
    /** Caught: within this distance (plus half the victim's width) of the line from the boss out past its hand. */
    static final double GRAB_RADIUS = 0.8;
    /** Victims bigger than this cannot be lifted. */
    static final float GRAB_MAX_WIDTH = 1.0F;
    static final float GRAB_MAX_HEIGHT = 2.2F;
    /** zombie_lord_throw, started from the grab's reach pose: slammed into the ground at 11, released at 27. */
    static final int THROW_SLAM_FRAME = 11;
    static final int THROW_RELEASE_FRAME = 27;
    static final int THROW_LENGTH = 48;
    static final float THROW_SLAM_DAMAGE = 4.0F;
    static final float THROW_WALL_DAMAGE = 4.0F;
    /** Launch velocity in blocks per tick, applied the tick after the release. */
    static final double THROW_SPEED = 1.0;
    static final double THROW_LIFT = 0.45;
    /** A wall met within this many ticks of the release, before landing, hurts the victim. */
    static final int THROW_FLIGHT_TICKS = 40;

    // Phase 2 from half health: the transition (zombie_lord_transition, 9 s, invulnerable, watched by the players
    // nearby through a cinematic camera, BossClient), after which the breastplate is broken open and the ghost blade
    // and the ring slash join in.
    static final float PHASE2_HEALTH = 0.5F;
    public static final int TRANSITION_LENGTH = 180;
    public static final int TRANSITION_BURST_FRAME = 100;
    public static final int TRANSITION_ROAR_FRAME = 112;
    /** A heartbeat that quickens while the power gathers, up to the burst. */
    static final int[] TRANSITION_HEARTBEATS = {36, 54, 68, 78, 85, 90, 94, 97};
    /** Players this close watch the transition and cannot be hurt meanwhile. */
    public static final double TRANSITION_AUDIENCE_RANGE = 48.0;

    // Ring slash (phase 2): after every third combo round, sprint at the target with the sword raised, following it,
    // spin a full circle as it comes within reach and leap at it.
    static final int RING_AFTER_ROUNDS = 3;
    /** zombie_lord_ring_slash: wind-up 0-8, one run cycle 8-20 (looped while running), spin pose from 20, 32 long. */
    public static final int RING_WINDUP_FRAMES = 8;
    public static final int RING_CYCLE_FRAMES = 12;
    static final int RING_SPIN_FRAME = 20;
    /** 7 blocks per second. */
    public static final double RING_SPEED = 0.35;
    /** Ground speed of the run cycle at render scale (44.8 px per 12 tick cycle at scale 1). */
    public static final double RING_CLIP_SPEED = 44.8 / 12.0 / 16.0 * RENDER_SCALE;
    /** The run turns toward the target at most this fast (degrees per tick), so a quick side step still slips it. */
    static final float RING_TURN_RATE = 15.0F;
    /** The spin starts once the target is this close, and the boss keeps closing in by up to RING_SPIN_DISTANCE, stopping RING_STOP_SHORT from it. */
    static final double RING_SPIN_RANGE = 3.0;
    static final double RING_SPIN_DISTANCE = 2.0;
    static final double RING_STOP_SHORT = 1.2;
    /** The run gives up and spins anyway after this many ticks (about 21 blocks). */
    static final int RING_MAX_RUN_TICKS = 60;
    static final int RING_SPIN_TICKS = 8;
    static final double RING_SPIN_RADIUS = 3.5;
    static final float RING_DAMAGE = 8.0F;

    // Ghost blade (phase 2): once as phase 2 begins, then after every third combo round (zombie_lord_ghost_blade).
    static final int GHOST_AFTER_ROUNDS = 3;
    static final int GB_ROAR_FRAME = 4;
    /** The blade catches fire from the guard to the point over these frames and burns until GB_FIRE_END. */
    public static final int GB_IGNITE_FROM = 28;
    public static final int GB_IGNITE_TO = 42;
    public static final int GB_FIRE_END = 172;
    /**
     * Leaping slashes {take-off, cut, landing}: each sprung from a deep crouch (the legs straighten over the 3 frames
     * before take-off), 16 ticks in the air, and cut on the way down; the cut hits over the GB_SLASH_TICKS from the cut
     * frame to the landing.
     */
    static final int[][] GB_JUMPS = {{52, 64, 68}, {80, 92, 96}, {108, 120, 124}};
    static final int GB_SLASH_TICKS = 5;
    static final double GB_JUMP_HEIGHT = 3.0;
    static final double GB_JUMP_MAX = 14.0;
    /** The boss lands this far short of the (predicted) target: right in front of it. */
    static final double GB_LAND_SHORT = 1.3;
    /** Prediction looks this many ticks ahead (take-off to the middle of the cut), at most GB_PREDICT_MAX blocks. */
    static final int GB_PREDICT_TICKS = 14;
    static final double GB_PREDICT_MAX = 6.0;
    static final double GB_SLASH_RANGE = 3.5;
    static final float GB_SLASH_HALF_ANGLE = 70.0F;
    static final float GB_SLASH_DAMAGE = 9.0F;
    static final float GB_BURN_SECONDS = 1.0F;
    /** Charged leap: take-off, apex, landing; higher and slower than the leap slam. Its landing point is marked. */
    public static final int GB_LEAP_TAKEOFF = 136;
    static final int GB_LEAP_APEX = 148;
    public static final int GB_LEAP_LAND = 164;
    static final double GB_LEAP_HEIGHT = 7.0;
    public static final double GB_LEAP_RADIUS = 6.0;
    static final float GB_LEAP_DAMAGE_NEAR = 15.0F;
    static final float GB_LEAP_DAMAGE_FAR = 3.0F;
    static final int GB_LENGTH = 186;

    // Death: the 60 frame death clip, then a short hold before the body disappears.
    public static final int DEATH_CLIP_FRAMES = 60;
    static final int DEATH_TICKS = DEATH_CLIP_FRAMES + 10;
    static final int DEATH_PLANT_FRAME = 16;
    static final int DEATH_FALL_FRAME = 48;

    // Quake summon.
    public static final int QUAKE_PLANT_FRAME = 21;
    public static final int QUAKE_FREEZE_FRAME = 40;
    public static final int QUAKE_LENGTH = 50;
    public static final int TREMOR_TICKS = 40;
    static final int[] SHOCKWAVE_FRAMES = {21, 25, 29};
    static final int SKULL_COUNT = 3;
    /** Skulls spiral up during the two-second tremor, then leave one every 3 s. */
    public static final int SKULL_RISE_TICKS = TREMOR_TICKS;
    static final int SKULL_FIRE_INTERVAL = 3 * 20;
    static final int SUMMON_TIMEOUT_TICKS = 20 * 20;
    /** The summon is cast once as health falls to each of these shares of the maximum. */
    static final float[] QUAKE_HEALTH = {0.7F, 0.2F};
    /** Share of incoming damage the boss takes for the whole quake (80% reduction). */
    static final float QUAKE_DAMAGE_TAKEN = 0.2F;

    static final int LOCOMOTION_BLEND_TICKS = 4;
    static final int ACTION_BLEND_TICKS = 4;

    private static final EntityDataAccessor<Integer> DATA_ACTION = SynchedEntityData.defineId(ZombieLord.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> DATA_ACTION_START = SynchedEntityData.defineId(ZombieLord.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> DATA_BLEND = SynchedEntityData.defineId(ZombieLord.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_PHASE = SynchedEntityData.defineId(ZombieLord.class, EntityDataSerializers.INT);
    /** Landing point of the ghost blade's charged leap, marked on the ground while the boss is in the air. */
    private static final EntityDataAccessor<Vector3f> DATA_MARK = SynchedEntityData.defineId(ZombieLord.class, EntityDataSerializers.VECTOR3);

    private final ServerBossEvent bossEvent = (ServerBossEvent) new ServerBossEvent(getDisplayName(),
            BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10).setDarkenScreen(true);

    // Server combat state.
    private float lockedYaw;
    private float chainFromYaw;
    private final Set<Integer> hitThisSegment = new HashSet<>();
    private Vec3 leapFrom = Vec3.ZERO;
    private Vec3 leapTo = Vec3.ZERO;
    /** Consecutive chase ticks with the target outside melee range. */
    private int outOfRangeTicks;
    /** Full combo rounds since the last charge; the third (fourth in phase 2) is followed by the charge. */
    private int comboRounds;
    /** Full combo rounds since the last ghost blade and the last ring slash (phase 2). */
    private int ghostRounds;
    private int ringRounds;
    /** Phase 2 opens with a ghost blade as soon as there is a target. */
    private boolean ghostPending;
    private boolean leapEndsRound;
    /** Quake summons already cast, one per QUAKE_HEALTH threshold. */
    private int quakesUsed;
    private double ringRun;
    private double ringTravel;
    private float spinFromYaw;
    /** Where the target is heading, blocks per tick (smoothed), for the ghost blade's prediction. */
    private Vec3 targetVelocity = Vec3.ZERO;
    private int repathTicks;
    @Nullable private Vec3 lastTargetPos;
    private final List<LordSkull> skulls = new ArrayList<>();
    private final List<LordSkeleton> minions = new ArrayList<>();
    private int skullsFired;
    private int allFiredTick = -1;
    @Nullable private LivingEntity grabTarget;
    /** The grab followed a leap that ended a combo round, so the throw ends it instead. */
    private boolean grabEndsRound;
    /** Set while the boss itself lets go of its victim, so the dismount is not blocked (BossEvents). */
    private boolean releasing;
    @Nullable private LivingEntity thrown;
    private Vec3 throwDir = Vec3.ZERO;
    private int thrownTicks;
    /** Horizontal drive set by the move control for this tick: unit direction and blocks per tick. */
    private double driveX, driveZ, driveSpeed;

    // Client animation state; plain data so this class stays loadable on a dedicated server.
    public final ZombieLordPoseState poseState = new ZombieLordPoseState();
    private double walkDistance;
    private double walkDistanceO;

    public ZombieLord(EntityType<? extends ZombieLord> type, Level level) {
        super(type, level);
        this.moveControl = new LordMoveControl(this, this::drive);
        this.xpReward = 100;
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 300.0)
                .add(Attributes.ARMOR, 10.0)
                .add(Attributes.ATTACK_DAMAGE, SWEEP_DAMAGE)
                .add(Attributes.MOVEMENT_SPEED, WALK_SPEED)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.STEP_HEIGHT, 1.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION, ZombieLordAction.IDLE.ordinal());
        builder.define(DATA_ACTION_START, 0L);
        builder.define(DATA_BLEND, 0);
        builder.define(DATA_PHASE, 1);
        builder.define(DATA_MARK, new Vector3f());
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, LordSkeleton.class));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    // ---------------------------------------------------------------- synced action

    public ZombieLordAction getAction() { return ZombieLordAction.byId(entityData.get(DATA_ACTION)); }
    public long getActionStart() { return entityData.get(DATA_ACTION_START); }
    public int getActionBlend() { return entityData.get(DATA_BLEND); }
    /** 1, or 2 once the transition has broken the breastplate open. */
    public int getPhase() { return entityData.get(DATA_PHASE); }
    public Vec3 getLeapMark() { return new Vec3(entityData.get(DATA_MARK)); }

    private void setPhase(int phase) {
        entityData.set(DATA_PHASE, phase);
        bossEvent.setColor(phase >= 2 ? BossEvent.BossBarColor.PURPLE : BossEvent.BossBarColor.RED);
    }

    /** Clip time of the current action on the shared world clock; negative while a delayed clip blends in. */
    public double actionTime(float partialTick) {
        return level().getGameTime() - getActionStart() + partialTick;
    }

    private int actionTick() { return (int) (level().getGameTime() - getActionStart()); }

    /**
     * @param startOffset ticks from now at which the clip's frame 0 plays: positive delays it (a blend
     *                    into frame 0), negative starts it mid-clip.
     */
    private void setAction(ZombieLordAction action, int blendTicks, int startOffset) {
        LOGGER.debug("Zombie Lord {} {} -> {} at game time {} (clip frame 0 at +{} ticks, blend {})",
                getId(), getAction(), action, level().getGameTime(), startOffset, blendTicks);
        entityData.set(DATA_ACTION, action.ordinal());
        entityData.set(DATA_ACTION_START, level().getGameTime() + startOffset);
        entityData.set(DATA_BLEND, blendTicks);
        hitThisSegment.clear();
    }

    private void setLocomotion(ZombieLordAction action) {
        if (getAction() != action) setAction(action, LOCOMOTION_BLEND_TICKS, 0);
    }

    // ---------------------------------------------------------------- server AI

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        driveSpeed = 0;
        bossEvent.setProgress(getHealth() / getMaxHealth());
        LivingEntity target = getTarget();
        if (target != null && (!target.isAlive() || target.isSpectator())) target = null;
        if (target != null) {
            Vec3 now = target.position();
            if (lastTargetPos != null && now.distanceToSqr(lastTargetPos) < 4.0) {
                targetVelocity = targetVelocity.lerp(now.subtract(lastTargetPos), 0.5);
            }
            lastTargetPos = now;
        }
        int t = actionTick();
        tickThrown();
        if (isVehicle() && getAction() != ZombieLordAction.THROW) letGo();
        switch (getAction()) {
            case IDLE, WALK -> tickChase(target);
            case ATTACK1 -> tickAttack1(target, t);
            case ATTACK2 -> tickAttack2(target, t);
            case ATTACK3 -> tickAttack3(target, t);
            case LEAP_SLAM -> tickLeap(target, t);
            case CHARGE -> tickCharge(target, t);
            case CHARGE_END -> tickChargeEnd(t);
            case STUN -> tickStun(t);
            case GRAB -> tickGrab(target, t);
            case THROW -> tickThrow(target, t);
            case RING_RUN -> tickRingRun(target, t);
            case RING_SPIN -> tickRingSpin(target, t);
            case GHOST_BLADE -> tickGhostBlade(target, t);
            case TRANSITION -> tickTransition(t);
            case QUAKE -> tickQuake(target, t);
            case QUAKE_END -> {
                applyYaw(lockedYaw);
                if (t >= QUAKE_LENGTH) endAction();
            }
            case DEATH -> {}
        }
    }

    private void tickChase(@Nullable LivingEntity target) {
        if (target == null) {
            getNavigation().stop();
            outOfRangeTicks = 0;
            setLocomotion(ZombieLordAction.IDLE);
            return;
        }
        // Phase change and summons only start between actions, never in the middle of the combo.
        if (getPhase() == 1 && getHealth() <= getMaxHealth() * PHASE2_HEALTH) {
            startTransition();
            return;
        }
        // Phase 2 opens with the ghost blade, before any summon that fell due meanwhile.
        if (ghostPending) {
            ghostPending = false;
            startGhostBlade(target);
            return;
        }
        if (quakesUsed < QUAKE_HEALTH.length && getHealth() <= getMaxHealth() * QUAKE_HEALTH[quakesUsed]) {
            quakesUsed++;
            startQuake("health " + Math.round(getHealth()) + "/" + Math.round(getMaxHealth()));
            return;
        }
        double distance = horizontalDistance(target.position());
        boolean inReach = distance <= MELEE_TRIGGER_RANGE && Math.abs(target.getY() - getY()) < 2.5;
        if (inReach && hasLineOfSight(target)) {
            startAttack1(target);
            return;
        }
        // Out of reach (above or below counts too) and 6-18 blocks away: leap onto the target.
        if (inReach) {
            outOfRangeTicks = 0;
        } else if (++outOfRangeTicks > CHASE_LEAP_TICKS && leapRange(target, LEAP_MIN_DISTANCE) && hasLineOfSight(target)) {
            comboRounds = 0;
            startLeap(target, false);
            return;
        }
        if (--repathTicks <= 0 || getNavigation().isDone()) {
            getNavigation().moveTo(target, WALK_SPEED);
            repathTicks = 5;
        }
        boolean moving = !getNavigation().isDone();
        setLocomotion(moving ? ZombieLordAction.WALK : ZombieLordAction.IDLE);
        if (!moving) applyYaw(Mth.approachDegrees(getYRot(), yawToward(target.position()), 12.0F));
    }

    private void startAttack1(LivingEntity target) {
        getNavigation().stop();
        outOfRangeTicks = 0;
        // The attack locks the facing it starts with; no turning until the chain re-targets.
        lockedYaw = yawToward(target.position());
        applyYaw(lockedYaw);
        setAction(ZombieLordAction.ATTACK1, ACTION_BLEND_TICKS, 0);
    }

    private void tickAttack1(@Nullable LivingEntity target, int t) {
        applyYaw(lockedYaw);
        stepForward(target, t);
        if (t == SWEEP_FIRST_FRAME) playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.5F, 0.6F);
        if (t >= SWEEP_FIRST_FRAME && t <= SWEEP_LAST_FRAME) sweep(t, true);
        if (t >= ATTACK1_CHAIN_FRAME) {
            // Five ticks from this pose to attack2's frame 0; the target is locked once, here.
            chainFromYaw = lockedYaw;
            Vec3 aim = target != null ? target.position() : lastTargetPos;
            if (aim != null && horizontalDistance(aim) > 0.1) lockedYaw = yawToward(aim);
            setAction(ZombieLordAction.ATTACK2, CHAIN_BLEND_TICKS, CHAIN_BLEND_TICKS);
        }
    }

    private void tickAttack2(@Nullable LivingEntity target, int t) {
        if (t < 0) {
            // Turn to the re-locked target over the transition (t runs -5 .. -1).
            float progress = (t + CHAIN_BLEND_TICKS + 1) / (float) CHAIN_BLEND_TICKS;
            applyYaw(Mth.rotLerp(Math.min(1.0F, progress), chainFromYaw, lockedYaw));
            return;
        }
        applyYaw(lockedYaw);
        stepForward(target, t);
        if (t == SWEEP_FIRST_FRAME) playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.5F, 0.7F);
        if (t >= SWEEP_FIRST_FRAME && t <= SWEEP_LAST_FRAME) sweep(t, false);
        if (t >= ATTACK2_LENGTH) {
            // The third segment only starts once attack2 has fully played.
            if (target != null && leapRange(target, ATTACK3_LEAP_MIN_DISTANCE)) {
                startLeap(target, true);
            } else {
                setAction(ZombieLordAction.ATTACK3, ACTION_BLEND_TICKS, 0);
            }
        }
    }

    private void tickAttack3(@Nullable LivingEntity target, int t) {
        applyYaw(lockedYaw);
        if (t < ATTACK3_LEAP_CUTOFF_FRAME && target != null && leapRange(target, ATTACK3_LEAP_MIN_DISTANCE)) {
            startLeap(target, true);
            return;
        }
        stepForward(target, t);
        if (t == ATTACK3_SLAM_FRAME) {
            // Cannot be rolled through (zombie_lord_chop is in fiercerbattles:ignores_dodge_roll).
            radialBlow(BossEntities.LORD_CHOP, position(), SLAM_RADIUS, SLAM_DAMAGE_NEAR, SLAM_DAMAGE_FAR, SLAM_HALF_ARC);
            dustBurst(position(), SLAM_RADIUS, SLAM_HALF_ARC);
        }
        if (t >= ATTACK3_LENGTH) endRound(target);
    }

    /** Small enough to lift, and not already riding or carrying something. */
    private static boolean grabbable(LivingEntity entity) {
        return isEnemy(entity) && !entity.isPassenger() && !entity.isVehicle()
                && entity.getBbWidth() <= GRAB_MAX_WIDTH && entity.getBbHeight() <= GRAB_MAX_HEIGHT;
    }

    /** The target if it stands right beside the boss and can be lifted, else the nearest enemy that does. */
    @Nullable
    private LivingEntity grabCandidate(@Nullable LivingEntity target) {
        if (target != null && grabbable(target) && besideForGrab(target)) return target;
        LivingEntity best = null;
        for (LivingEntity enemy : victims(getBoundingBox().inflate(GRAB_TRIGGER_RANGE + 1.0, 2.0, GRAB_TRIGGER_RANGE + 1.0))) {
            if (!grabbable(enemy) || !besideForGrab(enemy)) continue;
            if (best == null || distanceToSqr(enemy) < distanceToSqr(best)) best = enemy;
        }
        return best;
    }

    private boolean besideForGrab(LivingEntity entity) {
        return horizontalDistance(entity.position()) <= GRAB_TRIGGER_RANGE && Math.abs(entity.getY() - getY()) < 2.0;
    }

    private void startGrab(LivingEntity target, boolean endsRound) {
        getNavigation().stop();
        outOfRangeTicks = 0;
        grabTarget = target;
        grabEndsRound = endsRound;
        setAction(ZombieLordAction.GRAB, 3, 0);
        playSound(SoundEvents.ZOMBIE_AMBIENT, 2.0F, 0.5F);
    }

    /** Wrench the sword out, turn on the target and lunge with the free hand; whoever is in reach at frame 9 is caught. */
    private void tickGrab(@Nullable LivingEntity target, int t) {
        LivingEntity aim = grabTarget;
        if (t < GRAB_AIM_LOCK_FRAME && aim != null && aim.isAlive() && horizontalDistance(aim.position()) > 0.5) {
            lockedYaw = Mth.approachDegrees(getYRot(), yawToward(aim.position()), 30.0F);
        }
        applyYaw(lockedYaw);
        if (t == GRAB_REACH_FRAME) {
            LivingEntity caught = caughtAtReach(aim);
            LOGGER.debug("Zombie Lord {} grab at {}: caught {}", getId(), position(), caught);
            if (caught != null) {
                startThrow(caught);
                return;
            }
            playSound(SoundEvents.PLAYER_ATTACK_NODAMAGE, 1.5F, 0.6F);
        }
        if (t >= GRAB_LENGTH) {
            grabTarget = null;
            endGrab(target);
        }
    }

    /** The intended target if it is in reach, else anyone else in reach; null on a miss. */
    @Nullable
    private LivingEntity caughtAtReach(@Nullable LivingEntity aim) {
        Vec3 hand = ZombieLordGrip.hand(0).add(0, 0, 0.4);
        Vec3 from = position();
        Vec3 to = toWorld(hand);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity victim : victims(new AABB(from, to).inflate(GRAB_RADIUS + 1.0, 2.0, GRAB_RADIUS + 1.0))) {
            if (!grabbable(victim) || victim.getY() < getY() - 1.0 || victim.getY() > getY() + 1.5) continue;
            double distance = horizontalSegmentDistance(victim.position(), from, to) - victim.getBbWidth() / 2;
            if (distance > GRAB_RADIUS) continue;
            if (victim == aim) return victim;
            if (distance < bestDistance) {
                best = victim;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static double horizontalSegmentDistance(Vec3 point, Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dz = b.z - a.z;
        double length = dx * dx + dz * dz;
        double s = length < 1.0E-6 ? 0.0 : Mth.clamp(((point.x - a.x) * dx + (point.z - a.z) * dz) / length, 0.0, 1.0);
        return Math.sqrt(Mth.square(point.x - (a.x + s * dx)) + Mth.square(point.z - (a.z + s * dz)));
    }

    private void startThrow(LivingEntity victim) {
        // The victim rides the boss while it is held: both sides then place it on the left fist every tick.
        if (!victim.startRiding(this, true)) {
            LOGGER.debug("Zombie Lord {} could not lift {}", getId(), victim);
            return;
        }
        grabTarget = null;
        setAction(ZombieLordAction.THROW, 1, 0);
        positionRider(victim);
        playSound(SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1.5F, 0.5F);
    }

    /** Haul the victim overhead, slam it into the ground, haul it up again and hurl it; then back to the stance. */
    private void tickThrow(@Nullable LivingEntity target, int t) {
        applyYaw(lockedYaw);
        LivingEntity victim = getFirstPassenger() instanceof LivingEntity held ? held : null;
        if (victim != null) positionRider(victim);
        if (t == THROW_SLAM_FRAME && victim != null && level() instanceof ServerLevel level) {
            Vec3 at = toWorld(ZombieLordGrip.hand(t));
            Vec3 ground = groundBelow(level, at);
            victim.invulnerableTime = 0;
            boolean hurt = victim.hurt(damageSources().source(BossEntities.LORD_GRAB, this), THROW_SLAM_DAMAGE);
            LOGGER.debug("Zombie Lord {} slammed {} into the ground: {} damage, landed={}", getId(), victim, THROW_SLAM_DAMAGE, hurt);
            playSound(SoundEvents.GENERIC_EXPLODE.value(), 1.2F, 0.8F);
            playSound(SoundEvents.ANVIL_LAND, 1.0F, 0.6F);
            level.sendParticles(blockDust(level, ground), ground.x, ground.y + 0.1, ground.z, 30, 0.6, 0.1, 0.6, 0.15);
            level.sendParticles(ParticleTypes.EXPLOSION, ground.x, ground.y + 0.3, ground.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
        if (t == THROW_RELEASE_FRAME && victim != null) {
            letGo();
            thrown = victim;
            throwDir = forward(lockedYaw);
            thrownTicks = 0;
            playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.5F, 0.5F);
        }
        if (t >= THROW_LENGTH) endGrab(target);
    }

    private void endGrab(@Nullable LivingEntity target) {
        if (grabEndsRound) endRound(target);
        else endAction();
    }

    /** Drop whatever the boss is holding, where it is held. */
    private void letGo() {
        releasing = true;
        try {
            ejectPassengers();
        } finally {
            releasing = false;
        }
    }

    boolean isReleasing() { return releasing; }

    /** Launch the released victim the tick after the release (after the dismount reached the client), then watch for a wall. */
    private void tickThrown() {
        LivingEntity victim = thrown;
        if (victim == null) return;
        thrownTicks++;
        if (!victim.isAlive() || victim.isPassenger() || thrownTicks > THROW_FLIGHT_TICKS) {
            thrown = null;
            return;
        }
        if (thrownTicks == 1) {
            victim.setDeltaMovement(throwDir.x * THROW_SPEED, THROW_LIFT, throwDir.z * THROW_SPEED);
            victim.hasImpulse = true;
            victim.hurtMarked = true;
            return;
        }
        if (thrownTicks > 3 && victim.onGround()) {
            thrown = null;
            return;
        }
        AABB ahead = victim.getBoundingBox().inflate(0, -0.1, 0).move(throwDir.x * 0.3, 0, throwDir.z * 0.3);
        if (level().noCollision(victim, ahead)) return;
        thrown = null;
        victim.invulnerableTime = 0;
        boolean hurt = victim.hurt(damageSources().source(BossEntities.LORD_GRAB, this), THROW_WALL_DAMAGE);
        LOGGER.debug("Zombie Lord {} threw {} into a wall after {} ticks: {} damage, landed={}", getId(), victim, thrownTicks, THROW_WALL_DAMAGE, hurt);
        victim.level().playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.PLAYER_BIG_FALL, getSoundSource(), 1.5F, 0.8F);
        if (level() instanceof ServerLevel level) {
            Vec3 front = victim.position().add(throwDir.scale(victim.getBbWidth() / 2 + 0.2)).add(0, victim.getBbHeight() / 2, 0);
            BlockState wall = level.getBlockState(BlockPos.containing(front));
            if (!wall.isAir()) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, wall), front.x, front.y, front.z, 20, 0.2, 0.4, 0.2, 0.15);
            }
        }
    }

    /** A point given in the boss's frame (right, up, forward; blocks) in the world. */
    private Vec3 toWorld(Vec3 local) {
        Vec3 forward = forward(getYRot());
        return position().add(-forward.z * local.x + forward.x * local.z, local.y, forward.x * local.x + forward.z * local.z);
    }

    /**
     * The third segment (slam or its leap, and a throw after that leap) finished one full round of the combo. The
     * charge follows every third round (fourth in phase 2); in phase 2 the ghost blade and the ring slash each follow
     * every third round. One skill per round end, in that order of precedence (ghost blade, ring slash, charge): a
     * skill that falls due together with another waits for the next round end, still counting.
     */
    private void endRound(@Nullable LivingEntity target) {
        comboRounds++;
        if (getPhase() >= 2) {
            ghostRounds++;
            ringRounds++;
        }
        if (getPhase() >= 2 && ghostRounds >= GHOST_AFTER_ROUNDS) {
            ghostRounds = 0;
            startGhostBlade(target);
        } else if (getPhase() >= 2 && ringRounds >= RING_AFTER_ROUNDS) {
            ringRounds = 0;
            startRingSlash(target);
        } else if (comboRounds >= (getPhase() >= 2 ? CHARGE_AFTER_ROUNDS_PHASE2 : CHARGE_AFTER_ROUNDS)) {
            comboRounds = 0;
            startCharge(target);
        } else {
            endAction();
        }
    }

    private boolean leapRange(LivingEntity target, double minDistance) {
        double distance = horizontalDistance(target.position());
        return distance - target.getBbWidth() / 2 >= minDistance && distance < LEAP_MAX_DISTANCE;
    }

    private void startLeap(LivingEntity target, boolean endsRound) {
        leapEndsRound = endsRound;
        getNavigation().stop();
        outOfRangeTicks = 0;
        // The landing point is locked once; the leap never follows the target afterwards.
        leapFrom = position();
        leapTo = target.position();
        lockedYaw = yawToward(leapTo);
        applyYaw(lockedYaw);
        setAction(ZombieLordAction.LEAP_SLAM, ACTION_BLEND_TICKS, 0);
    }

    /** Clip time of the leap at {@code elapsed} ticks: the wind-up is stretched by 50%, the rest plays as authored. */
    public static double leapClipTime(double elapsed) {
        return elapsed < LEAP_WINDUP_TICKS ? elapsed * LEAP_TAKEOFF_FRAME / LEAP_WINDUP_TICKS : elapsed - LEAP_DELAY;
    }

    private void tickLeap(@Nullable LivingEntity target, int t) {
        applyYaw(lockedYaw);
        if (t == LEAP_TAKEOFF_TICK) {
            leapFrom = position();
            setNoGravity(true);
            playSound(SoundEvents.RAVAGER_STEP, 2.0F, 0.6F);
        }
        if (t > LEAP_TAKEOFF_TICK && t <= LEAP_LAND_TICK) {
            double s = (t - LEAP_TAKEOFF_TICK) / (double) (LEAP_LAND_TICK - LEAP_TAKEOFF_TICK);
            double apex = Math.min(5.0, 2.5 + 0.3 * leapFrom.subtract(leapTo).horizontalDistance());
            Vec3 wanted = leapFrom.lerp(leapTo, s).add(0, apex * 4 * s * (1 - s), 0);
            setDeltaMovement(Vec3.ZERO);
            move(MoverType.SELF, wanted.subtract(position()));
        }
        if (t == LEAP_LAND_TICK) {
            setNoGravity(false);
            resetFallDistance();
            radialBlow(BossEntities.LORD_SMASH, position(), LEAP_RADIUS, LEAP_DAMAGE_NEAR, LEAP_DAMAGE_FAR);
            dustBurst(position(), LEAP_RADIUS);
        }
        // Still bent over the landing: an enemy right beside the boss is grabbed and thrown.
        if (t >= GRAB_WINDOW_FROM && t <= GRAB_WINDOW_TO) {
            LivingEntity victim = grabCandidate(target);
            if (victim != null) {
                LOGGER.debug("Zombie Lord {} grabs at {} after the leap (leap tick {})", getId(), victim, t);
                startGrab(victim, leapEndsRound);
                return;
            }
        }
        if (t >= LEAP_LENGTH) {
            if (leapEndsRound) endRound(target);
            else endAction();
        }
    }

    private void startCharge(@Nullable LivingEntity target) {
        getNavigation().stop();
        outOfRangeTicks = 0;
        // A target standing on top of the boss gives no usable bearing; then it charges the way it faces.
        lockedYaw = target != null && horizontalDistance(target.position()) > 1.5 ? yawToward(target.position()) : getYRot();
        applyYaw(lockedYaw);
        setAction(ZombieLordAction.CHARGE, ACTION_BLEND_TICKS, 0);
        playSound(SoundEvents.RAVAGER_ROAR, 2.5F, 0.7F);
    }

    /**
     * Wind-up (turning to the target, two boot scrapes), then a straight dash that tramples what it meets. A dash
     * that runs its full length ends in a slam; one stopped by a wall leaves the boss stunned.
     */
    private void tickCharge(@Nullable LivingEntity target, int t) {
        if (t < CHARGE_AIM_LOCK_FRAME && target != null && horizontalDistance(target.position()) > 1.5) {
            lockedYaw = Mth.approachDegrees(lockedYaw, yawToward(target.position()), 12.0F);
        }
        applyYaw(lockedYaw);
        if ((t == 9 || t == 13) && level() instanceof ServerLevel level) {
            Vec3 heel = position().subtract(forward(lockedYaw).scale(0.6));
            level.sendParticles(blockDust(level, heel), heel.x, heel.y + 0.1, heel.z, 10, 0.3, 0.05, 0.3, 0.12);
            playSound(SoundEvents.GRAVEL_BREAK, 1.2F, 0.6F);
        }
        if (t < CHARGE_WINDUP_FRAMES) return;
        int dashTick = t - CHARGE_WINDUP_FRAMES;
        Vec3 dir = forward(lockedYaw);
        Vec3 before = position();
        move(MoverType.SELF, dir.scale(CHARGE_SPEED));
        double progress = position().subtract(before).dot(dir);
        DamageSource source = damageSources().source(BossEntities.LORD_STRIKE, this);
        for (LivingEntity victim : victims(getBoundingBox().inflate(0.6, 0.2, 0.6))) {
            if (!hitThisSegment.add(victim.getId())) continue;
            boolean hurt = victim.hurt(source, CHARGE_DAMAGE);
            victim.knockback(CHARGE_KNOCKBACK, -dir.x, -dir.z);
            victim.hurtMarked = true;
            LOGGER.debug("Zombie Lord {} charge hit {}: {} damage, landed={}", getId(), victim, CHARGE_DAMAGE, hurt);
        }
        if (level() instanceof ServerLevel level) {
            Vec3 ground = groundBelow(level, position());
            level.sendParticles(blockDust(level, ground), ground.x, ground.y + 0.1, ground.z, 8, 0.35, 0.05, 0.35, 0.15);
            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, ground.x, ground.y + 0.2, ground.z, 1, 0.2, 0.0, 0.2, 0.01);
            level.sendParticles(ParticleTypes.FLAME, ground.x, ground.y + 0.1, ground.z, 2, 0.3, 0.0, 0.3, 0.01);
            if (dashTick % 3 == 0) playSound(SoundEvents.IRON_GOLEM_STEP, 1.5F, 0.6F);
        }
        // A wall met head-on (or near it) stops the dash; a glancing one is slid along. Mobs and players never block.
        if (dashTick > 0 && horizontalCollision && progress < CHARGE_SPEED * CHARGE_WALL_PROGRESS) {
            startStun(dir);
        } else if (dashTick + 1 >= CHARGE_DASH_TICKS) {
            // Arrival: the rest of the clip from the end of the dash cycle, the slam and the recovery.
            setAction(ZombieLordAction.CHARGE_END, 3, -(CHARGE_WINDUP_FRAMES + CHARGE_CYCLE_FRAMES));
        }
    }

    private void tickChargeEnd(int t) {
        applyYaw(lockedYaw);
        if (t == CHARGE_WINDUP_FRAMES + CHARGE_CYCLE_FRAMES + CHARGE_SLAM_TICKS) {
            radialBlow(BossEntities.LORD_SMASH, position(), CHARGE_SLAM_RADIUS, CHARGE_SLAM_DAMAGE_NEAR, CHARGE_SLAM_DAMAGE_FAR);
            dustBurst(position(), CHARGE_SLAM_RADIUS);
        }
        if (t >= CHARGE_WINDUP_FRAMES + CHARGE_CYCLE_FRAMES + CHARGE_RECOVER_FRAMES) endAction();
    }

    private void startStun(Vec3 dir) {
        LOGGER.debug("Zombie Lord {} charged into a wall at {}", getId(), position());
        playSound(SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, 2.0F, 0.5F);
        playSound(SoundEvents.ANVIL_LAND, 1.0F, 0.6F);
        if (level() instanceof ServerLevel level) {
            Vec3 front = position().add(dir.scale(getBbWidth() / 2 + 0.2));
            BlockState wall = level.getBlockState(BlockPos.containing(front.x, getY() + 1.2, front.z));
            if (wall.isAir()) wall = level.getBlockState(BlockPos.containing(front.x, getY() + 0.5, front.z));
            if (!wall.isAir()) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, wall), front.x, getY() + 1.4, front.z,
                        40, 0.2, 0.7, 0.2, 0.2);
            }
            level.sendParticles(ParticleTypes.EXPLOSION, front.x, getY() + 1.2, front.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
        setAction(ZombieLordAction.STUN, 2, 0);
    }

    /** Rebound off the wall, then dazed until the clip has him back in the stance; he does nothing else meanwhile. */
    private void tickStun(int t) {
        applyYaw(lockedYaw);
        // The stun starts mid-tick (from the dash), so its first AI tick is t = 1.
        if (t >= 1 && t <= STUN_REBOUND_TICKS) move(MoverType.SELF, forward(lockedYaw).scale(-STUN_REBOUND / STUN_REBOUND_TICKS));
        if (t >= STUN_TICKS) endAction();
    }

    private void startQuake(String reason) {
        LOGGER.debug("Zombie Lord {} summons: {}", getId(), reason);
        getNavigation().stop();
        outOfRangeTicks = 0;
        comboRounds = 0;
        lockedYaw = getYRot();
        skulls.clear();
        minions.clear();
        skullsFired = 0;
        allFiredTick = -1;
        setAction(ZombieLordAction.QUAKE, ACTION_BLEND_TICKS, 0);
        playSound(SoundEvents.RAVAGER_ROAR, 3.0F, 0.6F);
    }

    private void tickQuake(@Nullable LivingEntity target, int t) {
        applyYaw(lockedYaw);
        if (!(level() instanceof ServerLevel level)) return;
        if (t == QUAKE_PLANT_FRAME) {
            playSound(SoundEvents.ANVIL_LAND, 2.0F, 0.5F);
            playSound(SoundEvents.GENERIC_EXPLODE.value(), 1.5F, 0.6F);
            for (int i = 0; i < SKULL_COUNT; i++) {
                LordSkull skull = new LordSkull(level, this, i * Mth.TWO_PI / SKULL_COUNT);
                level.addFreshEntity(skull);
                skulls.add(skull);
            }
        }
        for (int i = 0; i < SHOCKWAVE_FRAMES.length; i++) {
            if (t == SHOCKWAVE_FRAMES[i]) shockwave(level, 1.5 + 1.5 * i);
        }
        if (t >= QUAKE_PLANT_FRAME && t < QUAKE_PLANT_FRAME + TREMOR_TICKS) tremor(level, t - QUAKE_PLANT_FRAME);
        int fireStart = QUAKE_PLANT_FRAME + SKULL_RISE_TICKS;
        if (skullsFired < SKULL_COUNT && t >= fireStart && (t - fireStart) % SKULL_FIRE_INTERVAL == 0) {
            LordSkull skull = skullsFired < skulls.size() ? skulls.get(skullsFired) : null;
            skullsFired++;
            if (skull != null && skull.isAlive()) skull.fire(level, aimPoint(target));
        }
        if (skullsFired < SKULL_COUNT) return;
        if (allFiredTick < 0) allFiredTick = t;
        boolean skullsPending = skulls.stream().anyMatch(LordSkull::isAlive);
        boolean minionsAlive = minions.stream().anyMatch(LordSkeleton::isAlive);
        if (!skullsPending && !minionsAlive || t - allFiredTick >= SUMMON_TIMEOUT_TICKS) {
            // Resume: play the rest of the quake clip from the freeze frame, then chase again.
            setAction(ZombieLordAction.QUAKE_END, 0, -QUAKE_FREEZE_FRAME);
        }
    }

    private Vec3 aimPoint(@Nullable LivingEntity target) {
        Vec3 aim = target != null ? target.position() : lastTargetPos;
        if (aim == null) aim = position().add(forward(lockedYaw).scale(6.0));
        return groundBelow(level(), aim);
    }

    void onSkullLanded(LordSkull skull, @Nullable LordSkeleton raised) {
        LOGGER.debug("Zombie Lord {} skull landed at game time {} at {}, raised {}", getId(), level().getGameTime(), skull.position(), raised);
        if (raised != null) minions.add(raised);
    }

    private void endAction() {
        outOfRangeTicks = 0;
        setNoGravity(false);
        setAction(ZombieLordAction.IDLE, ACTION_BLEND_TICKS, 0);
    }

    // ---------------------------------------------------------------- phase 2: transition, ring slash, ghost blade

    private void startTransition() {
        LOGGER.debug("Zombie Lord {} enters phase 2 at {}/{}", getId(), Math.round(getHealth()), Math.round(getMaxHealth()));
        getNavigation().stop();
        outOfRangeTicks = 0;
        comboRounds = 0;
        lockedYaw = getYRot();
        letGo();
        setAction(ZombieLordAction.TRANSITION, ACTION_BLEND_TICKS, 0);
        playSound(SoundEvents.ZOMBIE_VILLAGER_CONVERTED, 2.0F, 0.5F);
    }

    /**
     * Kneel on the planted sword, convulse while dark power gathers, rise; the breastplate bursts (phase 2 from that
     * frame) and the boss roars with the sword raised. Invulnerable throughout (hurt); the players nearby watch it
     * through the cinematic camera (BossClient) and are not hurt meanwhile (BossEvents).
     */
    private void tickTransition(int t) {
        applyYaw(lockedYaw);
        if (!(level() instanceof ServerLevel level)) return;
        Vec3 chest = position().add(forward(lockedYaw).scale(0.3)).add(0, 2.0, 0);
        if (t == 16) {
            playSound(SoundEvents.ANVIL_LAND, 1.5F, 0.5F);
            Vec3 tip = groundBelow(level, position().add(forward(lockedYaw).scale(1.4)));
            level.sendParticles(blockDust(level, tip), tip.x, tip.y + 0.1, tip.z, 20, 0.4, 0.05, 0.4, 0.1);
        }
        if (t >= 28 && t < TRANSITION_BURST_FRAME) {
            // Dark power drawn in from the ground around the kneeling lord, to a heartbeat that quickens.
            for (int i = 0; i < 2; i++) {
                double angle = random.nextDouble() * Mth.TWO_PI, r = 1.5 + random.nextDouble() * 3.0;
                Vec3 at = position().add(Math.cos(angle) * r, 0.2, Math.sin(angle) * r);
                Vec3 pull = chest.subtract(at).scale(0.07);
                level.sendParticles(i == 0 ? ParticleTypes.SOUL : ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 0, pull.x, pull.y, pull.z, 1.0);
            }
            for (int beat : TRANSITION_HEARTBEATS) {
                if (t == beat) playSound(SoundEvents.WARDEN_HEARTBEAT, 3.0F, 0.6F);
            }
        }
        if (t == TRANSITION_BURST_FRAME) {
            setPhase(2);
            playSound(SoundEvents.GENERIC_EXPLODE.value(), 3.0F, 0.6F);
            playSound(SoundEvents.ANVIL_DESTROY, 2.0F, 0.5F);
            level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.NETHERITE_SCRAP)),
                    chest.x, chest.y, chest.z, 40, 0.3, 0.3, 0.3, 0.35);
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, chest.x, chest.y, chest.z, 40, 0.2, 0.2, 0.2, 0.25);
            level.sendParticles(ParticleTypes.EXPLOSION, chest.x, chest.y, chest.z, 3, 0.4, 0.4, 0.4, 0.0);
            flameRing(level, position(), 3.0, ParticleTypes.SOUL_FIRE_FLAME);
            for (LivingEntity near : victims(getBoundingBox().inflate(6.0, 2.0, 6.0))) {
                Vec3 away = near.position().subtract(position()).multiply(1, 0, 1);
                if (away.lengthSqr() < 1.0E-4) continue;
                away = away.normalize();
                near.knockback(1.2, -away.x, -away.z);
                near.hurtMarked = true;
            }
        }
        if (t == TRANSITION_ROAR_FRAME) {
            playSound(SoundEvents.RAVAGER_ROAR, 4.0F, 0.5F);
            playSound(SoundEvents.ENDER_DRAGON_GROWL, 2.0F, 0.6F);
        }
        for (int i = 0; i < 3; i++) {
            if (t == TRANSITION_ROAR_FRAME + 6 * i) flameRing(level, position(), 2.5 + 2.0 * i, ParticleTypes.FLAME);
        }
        if (t > TRANSITION_ROAR_FRAME && t < TRANSITION_ROAR_FRAME + TREMOR_TICKS) tremor(level, t - TRANSITION_ROAR_FRAME);
        if (t >= TRANSITION_LENGTH) {
            // Phase 2 opens with the ghost blade.
            ghostPending = true;
            endAction();
        }
    }

    private void flameRing(ServerLevel level, Vec3 centre, double radius, ParticleOptions particle) {
        int points = (int) (radius * 10);
        for (int i = 0; i < points; i++) {
            double angle = Mth.TWO_PI * i / points;
            Vec3 at = groundBelow(level, centre.add(Math.cos(angle) * radius, 0.5, Math.sin(angle) * radius));
            level.sendParticles(particle, at.x, at.y + 0.1, at.z, 1, 0.05, 0.05, 0.05, 0.02);
        }
    }

    private void startRingSlash(@Nullable LivingEntity target) {
        getNavigation().stop();
        outOfRangeTicks = 0;
        ringTravel = 0.0;
        ringRun = Double.MAX_VALUE;
        Vec3 aim = target != null ? target.position() : lastTargetPos;
        if (aim != null && horizontalDistance(aim) > 0.5) lockedYaw = yawToward(aim);
        spinFromYaw = lockedYaw;
        applyYaw(lockedYaw);
        setAction(ZombieLordAction.RING_RUN, ACTION_BLEND_TICKS, 0);
        playSound(SoundEvents.RAVAGER_ROAR, 2.0F, 0.9F);
    }

    /** Sword raised, sprint at the target, turning after it; spin once it is within RING_SPIN_RANGE. */
    private void tickRingRun(@Nullable LivingEntity target, int t) {
        Vec3 aim = target != null ? target.position() : lastTargetPos;
        if (aim != null && horizontalDistance(aim) > 0.5) lockedYaw = Mth.approachDegrees(lockedYaw, yawToward(aim), RING_TURN_RATE);
        spinFromYaw = lockedYaw;
        applyYaw(lockedYaw);
        if (t < RING_WINDUP_FRAMES) return;
        double distance = aim != null ? horizontalDistance(aim) : 0.0;
        boolean arrived = distance <= RING_SPIN_RANGE;
        double progress = arrived ? 0.0 : ringStep();
        if (level() instanceof ServerLevel level) {
            Vec3 ground = groundBelow(level, position());
            level.sendParticles(blockDust(level, ground), ground.x, ground.y + 0.1, ground.z, 4, 0.3, 0.05, 0.3, 0.1);
            if (t % 4 == 0) playSound(SoundEvents.IRON_GOLEM_STEP, 1.5F, 0.7F);
        }
        boolean blocked = !arrived && t > RING_WINDUP_FRAMES && horizontalCollision && progress < RING_SPEED * 0.5;
        if (arrived || blocked || t >= RING_WINDUP_FRAMES + RING_MAX_RUN_TICKS) {
            // Keep closing in through the spin, but stop short of the target.
            ringRun = ringTravel + (blocked ? 0.0 : Mth.clamp(distance - RING_STOP_SHORT, 0.0, RING_SPIN_DISTANCE));
            LOGGER.debug("Zombie Lord {} ring slash spins after {} blocks, target {} blocks away", getId(),
                    String.format("%.1f", ringTravel), String.format("%.1f", distance));
            setAction(ZombieLordAction.RING_SPIN, 2, -RING_SPIN_FRAME);
            playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 2.0F, 0.5F);
        }
    }

    /** One tick of the run along spinFromYaw, never past ringRun; returns the ground covered along that line. */
    private double ringStep() {
        double step = Math.min(RING_SPEED, ringRun - ringTravel);
        if (step <= 1.0E-3) return 0.0;
        Vec3 dir = forward(spinFromYaw);
        Vec3 before = position();
        move(MoverType.SELF, dir.scale(step));
        double progress = position().subtract(before).dot(dir);
        ringTravel += Math.max(0.0, progress);
        return progress;
    }

    /** A full turn over RING_SPIN_TICKS with the blade held out while still closing in; then a leap onto the target. */
    private void tickRingSpin(@Nullable LivingEntity target, int t) {
        // Started mid-tick from the run, so the first spin tick is s = 1.
        int s = t - RING_SPIN_FRAME;
        ringStep();
        if (s >= 1 && s <= RING_SPIN_TICKS) {
            applyYaw(Mth.wrapDegrees(spinFromYaw + 360.0F * s / RING_SPIN_TICKS));
            spinHits();
        } else {
            applyYaw(spinFromYaw);
        }
        if (s > RING_SPIN_TICKS) {
            lockedYaw = spinFromYaw;
            if (target != null) startLeap(target, false);
            else endAction();
        }
    }

    private void spinHits() {
        DamageSource source = damageSources().source(BossEntities.LORD_STRIKE, this);
        for (LivingEntity victim : victims(getBoundingBox().inflate(RING_SPIN_RADIUS + 1.0, 2.0, RING_SPIN_RADIUS + 1.0))) {
            Vec3 offset = victim.position().subtract(position());
            if (offset.horizontalDistance() - victim.getBbWidth() / 2 > RING_SPIN_RADIUS || Math.abs(offset.y) > 2.5) continue;
            if (!hitThisSegment.add(victim.getId())) continue;
            boolean hurt = victim.hurt(source, RING_DAMAGE);
            Vec3 away = offset.multiply(1, 0, 1);
            if (away.lengthSqr() > 1.0E-4) {
                away = away.normalize();
                victim.knockback(0.8, -away.x, -away.z);
                victim.hurtMarked = true;
            }
            LOGGER.debug("Zombie Lord {} spin hit {}: {} damage, landed={}", getId(), victim, RING_DAMAGE, hurt);
        }
        if (level() instanceof ServerLevel level) {
            // The blade points to the boss's right.
            Vec3 right = forward(getYRot() + 90.0F);
            for (double r = 1.2; r <= 3.0; r += 0.9) {
                Vec3 at = position().add(right.scale(r)).add(0, 1.2, 0);
                level.sendParticles(ParticleTypes.SWEEP_ATTACK, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    private void startGhostBlade(@Nullable LivingEntity target) {
        getNavigation().stop();
        outOfRangeTicks = 0;
        lockedYaw = target != null && horizontalDistance(target.position()) > 1.0 ? yawToward(target.position()) : getYRot();
        applyYaw(lockedYaw);
        setAction(ZombieLordAction.GHOST_BLADE, ACTION_BLEND_TICKS, 0);
    }

    /**
     * Roar, raise the blade while it catches fire, three leaping slashes onto where the target is going to be (left
     * to right, right to left, left to right; each cut on the way down and burning), then a charged leap onto its
     * marked landing point.
     */
    private void tickGhostBlade(@Nullable LivingEntity target, int t) {
        // On the ground between the jumps the boss turns to keep the target in front; take-off locks the facing.
        if (t < GB_LEAP_TAKEOFF && !ghostAirborne(t) && target != null && horizontalDistance(target.position()) > 1.0) {
            lockedYaw = Mth.approachDegrees(lockedYaw, yawToward(target.position()), 12.0F);
        }
        applyYaw(lockedYaw);
        if (!(level() instanceof ServerLevel level)) return;
        if (t == GB_ROAR_FRAME) {
            playSound(SoundEvents.RAVAGER_ROAR, 3.0F, 0.5F);
            playSound(SoundEvents.WITHER_AMBIENT, 1.5F, 0.6F);
        }
        if (t == GB_IGNITE_FROM) playSound(SoundEvents.BLAZE_SHOOT, 2.0F, 0.6F);
        if (t > GB_IGNITE_FROM && t < GB_FIRE_END && t % 6 == 0) playSound(SoundEvents.FIRE_AMBIENT, 1.5F, 0.8F);
        for (int i = 0; i < GB_JUMPS.length; i++) {
            int takeOff = GB_JUMPS[i][0], cut = GB_JUMPS[i][1], landing = GB_JUMPS[i][2];
            if (t == takeOff) ghostJump(target, i);
            if (t > takeOff && t <= landing) flyArc(takeOff, landing, t, GB_JUMP_HEIGHT);
            int slice = t - cut;
            if (slice >= 0 && slice < GB_SLASH_TICKS) {
                if (slice == 0) {
                    hitThisSegment.clear();
                    playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 2.0F, 0.6F);
                    playSound(SoundEvents.FIRECHARGE_USE, 1.2F, 0.8F);
                }
                // The second cut runs right to left, the others left to right.
                arcSweep(slice, GB_SLASH_TICKS, i == 1, GB_SLASH_RANGE, GB_SLASH_HALF_ANGLE, 4.0,
                        BossEntities.LORD_GHOST_BLADE, GB_SLASH_DAMAGE, true);
                fireArc(level, slice, i == 1);
            }
            if (t == landing) {
                setNoGravity(false);
                resetFallDistance();
                Vec3 ground = groundBelow(level, position());
                level.sendParticles(blockDust(level, ground), ground.x, ground.y + 0.1, ground.z, 15, 0.5, 0.05, 0.5, 0.12);
                playSound(SoundEvents.IRON_GOLEM_STEP, 2.0F, 0.6F);
                if (target != null) {
                    LOGGER.debug("Zombie Lord {} ghost blade jump {} landed {} blocks from {}", getId(), i + 1,
                            String.format("%.2f", horizontalDistance(target.position())), target);
                }
            }
        }
        if (t == GB_LEAP_TAKEOFF) {
            // The landing point is locked at take-off and marked on the ground (client) until the boss comes down.
            leapFrom = position();
            Vec3 aim = target != null ? target.position() : lastTargetPos != null ? lastTargetPos : position();
            leapTo = new Vec3(aim.x, aim.y, aim.z);
            if (horizontalDistance(leapTo) > 0.5) lockedYaw = yawToward(leapTo);
            applyYaw(lockedYaw);
            entityData.set(DATA_MARK, leapTo.toVector3f());
            setNoGravity(true);
            playSound(SoundEvents.RAVAGER_STEP, 2.5F, 0.5F);
            playSound(SoundEvents.BLAZE_SHOOT, 2.0F, 0.5F);
        }
        if (t > GB_LEAP_TAKEOFF && t <= GB_LEAP_LAND) flyChargedLeap(t);
        if (t == GB_LEAP_LAND) {
            setNoGravity(false);
            resetFallDistance();
            hitThisSegment.clear();
            radialBlow(BossEntities.LORD_SMASH, position(), GB_LEAP_RADIUS, GB_LEAP_DAMAGE_NEAR, GB_LEAP_DAMAGE_FAR);
            dustBurst(position(), GB_LEAP_RADIUS);
            flameRing(level, position(), GB_LEAP_RADIUS * 0.5, ParticleTypes.FLAME);
            flameRing(level, position(), GB_LEAP_RADIUS, ParticleTypes.FLAME);
        }
        if (t >= GB_LENGTH) endAction();
    }

    /** Between a leaping slash's take-off and its landing. */
    private static boolean ghostAirborne(int t) {
        for (int[] jump : GB_JUMPS) {
            if (t > jump[0] && t <= jump[2]) return true;
        }
        return false;
    }

    /** Take off toward the (predicted) target, landing GB_LAND_SHORT before it: right in front of it. */
    private void ghostJump(@Nullable LivingEntity target, int index) {
        leapFrom = position();
        Vec3 aim = target != null ? predictedTarget(target, index) : position().add(forward(lockedYaw).scale(GB_LAND_SHORT));
        Vec3 offset = new Vec3(aim.x - getX(), 0, aim.z - getZ());
        double distance = offset.length();
        if (distance > 0.5) lockedYaw = yawToward(aim);
        applyYaw(lockedYaw);
        Vec3 dir = distance > 1.0E-3 ? offset.scale(1.0 / distance) : forward(lockedYaw);
        double travel = Mth.clamp(distance - GB_LAND_SHORT, 0.0, GB_JUMP_MAX);
        leapTo = new Vec3(getX() + dir.x * travel, aim.y, getZ() + dir.z * travel);
        setNoGravity(true);
        playSound(SoundEvents.RAVAGER_STEP, 1.5F, 0.9F);
    }

    /**
     * Where the target will be when the cut lands, from its recent movement: the first and third cut follow a target
     * backing away from the boss, the second one stepping toward it; sideways movement is always followed.
     */
    private Vec3 predictedTarget(LivingEntity target, int index) {
        Vec3 pos = target.position();
        Vec3 away = new Vec3(pos.x - getX(), 0, pos.z - getZ());
        if (away.lengthSqr() < 1.0E-4) return pos;
        away = away.normalize();
        Vec3 lead = new Vec3(targetVelocity.x, 0, targetVelocity.z).scale(GB_PREDICT_TICKS);
        if (lead.length() > GB_PREDICT_MAX) lead = lead.normalize().scale(GB_PREDICT_MAX);
        double radial = lead.dot(away);
        Vec3 sideways = lead.subtract(away.scale(radial));
        boolean follow = index == 1 ? radial < 0 : radial > 0;
        Vec3 predicted = pos.add(sideways).add(follow ? away.scale(radial) : Vec3.ZERO);
        LOGGER.debug("Zombie Lord {} ghost blade cut {}: target at {}, moving {} per tick, aiming at {}",
                getId(), index + 1, pos, targetVelocity, predicted);
        return predicted;
    }

    /** Ballistic hop from leapFrom to leapTo between two clip frames. */
    private void flyArc(int from, int to, int t, double apex) {
        double s = (t - from) / (double) (to - from);
        Vec3 wanted = leapFrom.lerp(leapTo, s).add(0, apex * 4 * s * (1 - s), 0);
        setDeltaMovement(Vec3.ZERO);
        move(MoverType.SELF, wanted.subtract(position()));
    }

    /** Up fast to GB_LEAP_HEIGHT, then a slow descent onto the marked point. */
    private void flyChargedLeap(int t) {
        double u = (t - GB_LEAP_TAKEOFF) / (double) (GB_LEAP_LAND - GB_LEAP_TAKEOFF);
        double apexAt = (GB_LEAP_APEX - GB_LEAP_TAKEOFF) / (double) (GB_LEAP_LAND - GB_LEAP_TAKEOFF);
        double across = u * u * (3 - 2 * u);
        double height = u <= apexAt ? Math.sin(u / apexAt * Math.PI / 2) : Math.cos((u - apexAt) / (1 - apexAt) * Math.PI / 2);
        Vec3 wanted = leapFrom.lerp(leapTo, across).add(0, GB_LEAP_HEIGHT * height, 0);
        setDeltaMovement(Vec3.ZERO);
        move(MoverType.SELF, wanted.subtract(position()));
    }

    /** Flames along this tick's slice of a burning cut, at blade height. */
    private void fireArc(ServerLevel level, int slice, boolean rightToLeft) {
        float width = 2 * GB_SLASH_HALF_ANGLE / GB_SLASH_TICKS;
        float from = GB_SLASH_HALF_ANGLE - width * slice;
        float to = from - width;
        if (!rightToLeft) {
            from = -from;
            to = -to;
        }
        for (int i = 0; i <= 4; i++) {
            Vec3 dir = forward(lockedYaw + Mth.lerp(i / 4.0F, from, to));
            for (double r = 1.4; r <= 3.2; r += 0.9) {
                Vec3 at = position().add(dir.scale(r)).add(0, 1.3, 0);
                level.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 2, 0.08, 0.08, 0.08, 0.02);
            }
        }
    }

    // ---------------------------------------------------------------- hits

    private void stepForward(@Nullable LivingEntity target, int t) {
        // Ticks 1..STEP_TICKS: attack1 and attack3 are started mid-tick, so their first AI tick is already t = 1.
        if (t < 1 || t > STEP_TICKS) return;
        double step = STEP_DISTANCE / STEP_TICKS;
        if (target != null) {
            double gap = horizontalDistance(target.position()) - (getBbWidth() + target.getBbWidth()) / 2 - STEP_MIN_GAP;
            step = Math.min(step, Math.max(0.0, gap));
        }
        if (step > 0) move(MoverType.SELF, forward(lockedYaw).scale(step));
    }

    /**
     * One hit tick of a combo sweep (zombie_lord_slash: cannot be rolled through). The 120 degree front arc is cut
     * into one slice per hit tick, swept from the boss's right to its left (attack1) or left to right (attack2).
     */
    private void sweep(int t, boolean rightToLeft) {
        arcSweep(t - SWEEP_FIRST_FRAME, SWEEP_LAST_FRAME - SWEEP_FIRST_FRAME + 1, rightToLeft, SWEEP_RANGE, SWEEP_HALF_ANGLE,
                3.0, BossEntities.LORD_SLASH, SWEEP_DAMAGE, false);
    }

    /**
     * Slice {@code slice} of {@code slices} of a horizontal cut across the front arc (+-halfAngle), from the boss's
     * right to its left or the other way; each target is hit once per action segment.
     */
    private void arcSweep(int slice, int slices, boolean rightToLeft, double range, float halfAngle, double reachY,
                          ResourceKey<DamageType> type, float damage, boolean burns) {
        float width = 2 * halfAngle / slices;
        float from = halfAngle - width * slice;
        float to = from - width;
        if (!rightToLeft) {
            from = -from;
            to = -to;
        }
        float low = Math.min(from, to) - SWEEP_SLICE_MARGIN;
        float high = Math.max(from, to) + SWEEP_SLICE_MARGIN;
        DamageSource source = damageSources().source(type, this);
        for (LivingEntity victim : victims(getBoundingBox().inflate(range + 1.0, reachY, range + 1.0))) {
            Vec3 offset = victim.position().subtract(position());
            double distance = offset.horizontalDistance();
            if (distance - victim.getBbWidth() / 2 > range || Math.abs(offset.y) > reachY) continue;
            float bearing = distance < 1.0 ? 0.0F : relativeBearing(offset);
            if (bearing < low || bearing > high || !hitThisSegment.add(victim.getId())) continue;
            boolean hurt = victim.hurt(source, damage);
            if (hurt && burns) victim.igniteForSeconds(GB_BURN_SECONDS);
            LOGGER.debug("Zombie Lord {} {} slice {} hit {} at {} deg, {} blocks: {} damage, landed={}",
                    getId(), getAction(), slice, victim, Math.round(bearing), String.format("%.2f", distance), damage, hurt);
        }
    }

    /**
     * Ground blow centred on {@code centre}; damage falls linearly from {@code near} to {@code far} at the rim. The
     * boss's ground blows (zombie_lord_smash, zombie_lord_chop) are in bypasses_shield.
     */
    private void radialBlow(ResourceKey<DamageType> type, Vec3 centre, double radius, float near, float far) {
        radialBlow(type, centre, radius, near, far, 180.0F);
    }

    /** As above, limited to +-halfArc degrees around the facing (a target on the centre is always in it). */
    private void radialBlow(ResourceKey<DamageType> type, Vec3 centre, double radius, float near, float far, float halfArc) {
        DamageSource source = damageSources().source(type, this);
        for (LivingEntity victim : victims(new AABB(centre, centre).inflate(radius + 1.0, 0, radius + 1.0).expandTowards(0, 3.0, 0).move(0, -1.0, 0))) {
            Vec3 offset = victim.position().subtract(centre);
            double distance = Math.max(0.0, offset.horizontalDistance() - victim.getBbWidth() / 2);
            if (distance > radius || offset.horizontalDistance() > 1.0 && Math.abs(relativeBearing(offset)) > halfArc) continue;
            if (!hitThisSegment.add(victim.getId())) continue;
            float damage = Mth.lerp((float) (distance / radius), near, far);
            boolean hurt = victim.hurt(source, damage);
            LOGGER.debug("Zombie Lord {} {} blow hit {} at {} blocks: {} damage, landed={}",
                    getId(), getAction(), victim, String.format("%.2f", distance), damage, hurt);
        }
        playSound(SoundEvents.GENERIC_EXPLODE.value(), 1.6F, 0.7F);
        playSound(SoundEvents.ANVIL_LAND, 1.2F, 0.5F);
    }

    private List<LivingEntity> victims(AABB area) {
        return level().getEntitiesOfClass(LivingEntity.class, area, ZombieLord::isEnemy);
    }

    /** Everything the boss, its skulls and blows may hurt: not the boss itself or its raised skeletons. */
    static boolean isEnemy(LivingEntity entity) {
        return entity.isAlive() && !entity.isSpectator() && !(entity instanceof ZombieLord) && !(entity instanceof LordSkeleton)
                && !(entity instanceof Player player && player.isCreative());
    }

    // ---------------------------------------------------------------- effects

    private void dustBurst(Vec3 centre, double radius) {
        dustBurst(centre, radius, 180.0F);
    }

    /** Dust over the ground the blow covers: +-halfArc degrees around the facing. */
    private void dustBurst(Vec3 centre, double radius, float halfArc) {
        if (!(level() instanceof ServerLevel level)) return;
        for (double r = 0.8; r <= radius; r += 0.8) {
            int points = (int) (r * 9);
            for (int i = 0; i < points; i++) {
                double angle = Mth.TWO_PI * i / points;
                Vec3 offset = new Vec3(Math.cos(angle) * r, 0.5, Math.sin(angle) * r);
                if (Math.abs(relativeBearing(offset)) > halfArc) continue;
                Vec3 at = groundBelow(level, centre.add(offset));
                level.sendParticles(blockDust(level, at), at.x, at.y + 0.1, at.z, 4, 0.2, 0.1, 0.2, 0.15);
                if (i % 3 == 0) level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, at.x, at.y + 0.2, at.z, 1, 0.1, 0.05, 0.1, 0.02);
            }
        }
        level.sendParticles(ParticleTypes.EXPLOSION, centre.x, centre.y + 0.3, centre.z, 2, 0.6, 0.1, 0.6, 0.0);
    }

    private void shockwave(ServerLevel level, double radius) {
        int points = (int) (radius * 10);
        for (int i = 0; i < points; i++) {
            double angle = Mth.TWO_PI * i / points;
            Vec3 at = groundBelow(level, position().add(Math.cos(angle) * radius, 0.5, Math.sin(angle) * radius));
            level.sendParticles(blockDust(level, at), at.x, at.y + 0.1, at.z, 3, 0.1, 0.05, 0.1, 0.2);
            level.sendParticles(ParticleTypes.POOF, at.x, at.y + 0.15, at.z, 1, 0.05, 0.05, 0.05, 0.03);
        }
    }

    /** Ground tremor: loose debris jumping across the ground around the planted sword. */
    private void tremor(ServerLevel level, int tick) {
        if (tick % 2 == 0) {
            for (int i = 0; i < 8; i++) {
                double angle = random.nextDouble() * Mth.TWO_PI;
                double r = 1.0 + random.nextDouble() * 7.0;
                Vec3 at = groundBelow(level, position().add(Math.cos(angle) * r, 0.5, Math.sin(angle) * r));
                level.sendParticles(blockDust(level, at), at.x, at.y + 0.05, at.z, 3, 0.25, 0.0, 0.25, 0.12);
            }
        }
        if (tick % 10 == 0) playSound(SoundEvents.WARDEN_DIG, 1.6F, 0.6F);
    }

    static ParticleOptions blockDust(Level level, Vec3 surface) {
        BlockState state = level.getBlockState(BlockPos.containing(surface.x, surface.y - 0.2, surface.z));
        if (state.isAir()) state = Blocks.DIRT.defaultBlockState();
        return new BlockParticleOption(ParticleTypes.BLOCK, state);
    }

    /**
     * Highest supporting surface at or up to eight blocks below {@code at} (climbing out first if
     * {@code at} is inside a block); {@code at} itself when none.
     */
    static Vec3 groundBelow(Level level, Vec3 at) {
        BlockPos.MutableBlockPos pos = BlockPos.containing(at.x, at.y + 0.5, at.z).mutable();
        for (int i = 0; i < 3 && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty(); i++) pos.move(Direction.UP);
        for (int i = 0; i < 9; i++) {
            BlockPos below = pos.below();
            VoxelShape shape = level.getBlockState(below).getCollisionShape(level, below);
            if (!shape.isEmpty()) return new Vec3(at.x, below.getY() + shape.max(Direction.Axis.Y), at.z);
            pos.move(Direction.DOWN);
        }
        return at;
    }

    // ---------------------------------------------------------------- facing and movement

    static Vec3 forward(float yaw) {
        float radians = yaw * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(radians), 0, Mth.cos(radians));
    }

    /** Signed horizontal angle of {@code offset} from the facing, in degrees; positive is the boss's right. */
    private float relativeBearing(Vec3 offset) {
        float radians = lockedYaw * Mth.DEG_TO_RAD;
        double front = -Mth.sin(radians) * offset.x + Mth.cos(radians) * offset.z;
        double right = -Mth.cos(radians) * offset.x - Mth.sin(radians) * offset.z;
        return (float) (Mth.atan2(right, front) * Mth.RAD_TO_DEG);
    }

    private float yawToward(Vec3 point) {
        return (float) (Mth.atan2(point.z - getZ(), point.x - getX()) * Mth.RAD_TO_DEG) - 90.0F;
    }

    private double horizontalDistance(Vec3 point) {
        return Math.sqrt(Mth.square(point.x - getX()) + Mth.square(point.z - getZ()));
    }

    private void applyYaw(float yaw) {
        setYRot(yaw);
        setYHeadRot(yaw);
        setYBodyRot(yaw);
    }

    private boolean holdsPosition() {
        ZombieLordAction action = getAction();
        return !action.locomotion();
    }

    void drive(double x, double z, double speed) {
        driveX = x;
        driveZ = z;
        driveSpeed = speed;
    }

    @Override
    public void travel(Vec3 input) {
        if (!level().isClientSide && isEffectiveAi()) {
            Vec3 motion = getDeltaMovement();
            if (holdsPosition()) {
                setDeltaMovement(0, motion.y, 0);
            } else if (driveSpeed > 0) {
                setDeltaMovement(driveX * driveSpeed, motion.y, driveZ * driveSpeed);
            }
            super.travel(Vec3.ZERO);
            return;
        }
        super.travel(input);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            walkDistanceO = walkDistance;
            walkDistance += Math.sqrt(Mth.square(getX() - xo) + Mth.square(getZ() - zo));
            long now = level().getGameTime();
            poseState.trail.removeIf(point -> now - point.time() > CHARGE_TRAIL_TICKS);
            if (getAction() == ZombieLordAction.CHARGE && actionTime(0) >= CHARGE_WINDUP_FRAMES) {
                poseState.trail.add(new ZombieLordPoseState.TrailPoint(getX(), getY(), getZ(), now));
            }
            if (getAction() == ZombieLordAction.STUN) dazeStars(actionTime(0));
        } else {
            setYHeadRot(getYRot());
            setYBodyRot(getYRot());
        }
    }

    /** Three sparks circling above the hanging head while the boss is dazed. */
    private void dazeStars(double time) {
        if (time < STUN_DAZE_FROM || time > STUN_DAZE_TO) return;
        Vec3 centre = position().add(forward(getYRot()).scale(0.45)).add(0, 2.85, 0);
        for (int i = 0; i < 3; i++) {
            double angle = time * 0.3 + i * Mth.TWO_PI / 3;
            level().addParticle(ParticleTypes.ELECTRIC_SPARK, centre.x + Math.cos(angle) * 0.55, centre.y + 0.08 * Math.sin(angle * 2),
                    centre.z + Math.sin(angle) * 0.55, 0.0, 0.0, 0.0);
        }
    }

    /** Distance walked on this client, interpolated; drives the walk cycle so the feet do not slide. */
    public double walkDistance(float partialTick) {
        return Mth.lerp(partialTick, walkDistanceO, walkDistance);
    }

    @Override
    protected float tickHeadTurn(float yRot, float animStep) {
        // The body always faces the entity yaw; the clips own the head.
        this.yBodyRot = getYRot();
        this.yHeadRot = getYRot();
        return animStep;
    }

    @Override
    protected BodyRotationControl createBodyControl() {
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {}
        };
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        // Leaps, the spin and the ghost blade's jumps are fast and keyed to their clips; follow the server without the
        // 3 tick smoothing.
        ZombieLordAction action = getAction();
        boolean exact = action == ZombieLordAction.LEAP_SLAM || action == ZombieLordAction.RING_SPIN
                || action == ZombieLordAction.GHOST_BLADE;
        super.lerpTo(x, y, z, yRot, xRot, exact ? 1 : steps);
    }

    @Override
    public void push(double x, double y, double z) {
        // Shoving players or mobs never displaces the boss.
    }

    // ---------------------------------------------------------------- the held victim (a passenger)

    /** Where a held victim's feet go: hanging from the left fist (ZombieLordGrip), facing the boss. */
    public Vec3 heldPosition(Entity passenger, float partialTick) {
        double time = getAction() == ZombieLordAction.THROW ? Mth.clamp(actionTime(partialTick), 0.0, THROW_RELEASE_FRAME) : 0.0;
        Vec3 fist = toWorld(ZombieLordGrip.hand(time).add(0, 0, ZombieLordGrip.CHEST_DEPTH));
        return fist.subtract(0, passenger.getBbHeight() * ZombieLordGrip.GRIP_HEIGHT, 0);
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction move) {
        if (!hasPassenger(passenger)) return;
        Vec3 at = heldPosition(passenger, 0.0F);
        move.accept(passenger, at.x, at.y, at.z);
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        // Let go where it hangs, unless that is inside a block.
        Vec3 held = heldPosition(passenger, 0.0F);
        AABB box = passenger.getDimensions(passenger.getPose()).makeBoundingBox(held);
        return level().noCollision(passenger, box) ? held : super.getDismountLocationForPassenger(passenger);
    }

    @Override
    public boolean shouldRiderSit() {
        return false;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        // Only the grab puts anything on the boss (it mounts with force).
        return false;
    }

    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        return null;
    }

    @Override
    public void die(DamageSource source) {
        // A boss killed in mid-air (leaps, the ghost blade's jumps) falls to the ground for its death clip.
        letGo();
        setNoGravity(false);
        super.die(source);
    }

    /** The death clip plays out (the renderer's tip-over is off) before the body disappears in smoke. */
    @Override
    protected void tickDeath() {
        deathTime++;
        if (level() instanceof ServerLevel level) {
            if (deathTime == DEATH_PLANT_FRAME) playSound(SoundEvents.ANVIL_LAND, 1.2F, 0.5F);
            if (deathTime == DEATH_FALL_FRAME) {
                playSound(SoundEvents.GENERIC_EXPLODE.value(), 1.0F, 0.5F);
                Vec3 front = position().add(forward(getYRot()).scale(1.5));
                level.sendParticles(blockDust(level, front), front.x, front.y + 0.1, front.z, 30, 1.0, 0.1, 1.0, 0.15);
            }
            if (deathTime >= DEATH_TICKS && !isRemoved()) {
                LOGGER.debug("Zombie Lord {} death sequence over after {} ticks", getId(), deathTime);
                level.broadcastEntityEvent(this, EntityEvent.POOF);
                remove(RemovalReason.KILLED);
            }
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Invulnerable through the phase transition; the sword planted for the summon braces the boss for the whole
        // quake. /kill and the void still go through.
        ZombieLordAction action = getAction();
        if (action == ZombieLordAction.TRANSITION && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        if ((action == ZombieLordAction.QUAKE || action == ZombieLordAction.QUAKE_END)
                && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            amount *= QUAKE_DAMAGE_TAKEN;
        }
        return super.hurt(source, amount);
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        ZombieLordAction action = getAction();
        AABB box = super.getBoundingBoxForCulling().inflate(1.5, 0.5, 1.5);
        if (!poseState.trail.isEmpty()) box = box.inflate(CHARGE_SPEED * CHARGE_DASH_TICKS, 0, CHARGE_SPEED * CHARGE_DASH_TICKS);
        // The charged leap's landing mark is drawn by this renderer.
        if (action == ZombieLordAction.GHOST_BLADE && actionTime(0.0F) >= GB_LEAP_TAKEOFF) {
            box = box.minmax(new AABB(getLeapMark(), getLeapMark()).inflate(GB_LEAP_RADIUS + 1.0));
        }
        return action == ZombieLordAction.QUAKE || action == ZombieLordAction.QUAKE_END ? box.inflate(4.0, 0, 4.0) : box;
    }

    // ---------------------------------------------------------------- boss bar, sounds, save

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        bossEvent.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        bossEvent.removePlayer(player);
    }

    @Override
    public void setCustomName(@Nullable Component name) {
        super.setCustomName(name);
        bossEvent.setName(getDisplayName());
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Phase", getPhase());
        tag.putInt("QuakesUsed", quakesUsed);
        tag.putBoolean("GhostPending", ghostPending);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setPhase(Math.max(1, tag.getInt("Phase")));
        quakesUsed = tag.getInt("QuakesUsed");
        ghostPending = tag.getBoolean("GhostPending");
        if (hasCustomName()) bossEvent.setName(getDisplayName());
    }

    @Override
    protected SoundEvent getAmbientSound() { return SoundEvents.ZOMBIE_AMBIENT; }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.ZOMBIE_HURT; }

    @Override
    protected SoundEvent getDeathSound() { return SoundEvents.ZOMBIE_DEATH; }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        playSound(SoundEvents.IRON_GOLEM_STEP, 1.2F, 0.7F);
    }

    @Override
    public float getVoicePitch() { return 0.55F + random.nextFloat() * 0.1F; }

    @Override
    protected float getSoundVolume() { return 2.0F; }

    /** Receives a move control's horizontal drive for the tick: unit direction and blocks per tick. */
    interface Drive {
        void drive(double x, double z, double speed);
    }

    /**
     * Pathing drive of the bosses (also the Iron Sentinel's): turns toward the next node and moves at exactly the
     * navigation speed (blocks per tick), handed to the boss's travel() through {@code drive}.
     */
    static final class LordMoveControl extends MoveControl {
        private final Drive drive;

        LordMoveControl(Mob mob, Drive drive) {
            super(mob);
            this.drive = drive;
        }

        @Override
        public void tick() {
            if (operation == Operation.MOVE_TO) {
                operation = Operation.WAIT;
                double dx = wantedX - mob.getX();
                double dz = wantedZ - mob.getZ();
                double dy = wantedY - mob.getY();
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                if (horizontal < 1.0E-3) return;
                float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
                mob.setYRot(rotlerp(mob.getYRot(), yaw, 25.0F));
                drive.drive(dx / horizontal, dz / horizontal, speedModifier);
                if (dy > mob.maxUpStep() && horizontal * horizontal < Math.max(1.0F, mob.getBbWidth())) {
                    mob.getJumpControl().jump();
                    operation = Operation.JUMPING;
                }
            } else if (operation == Operation.JUMPING) {
                double dx = wantedX - mob.getX();
                double dz = wantedZ - mob.getZ();
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                if (horizontal > 1.0E-3) drive.drive(dx / horizontal, dz / horizontal, speedModifier);
                if (mob.onGround()) operation = Operation.WAIT;
            }
        }
    }
}
