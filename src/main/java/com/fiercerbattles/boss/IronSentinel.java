package com.fiercerbattles.boss;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.execution.ExecutionTarget;
import com.fiercerbattles.parry.BossParryTarget;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
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
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Iron Sentinel boss. Like the Zombie Lord, the server runs one action at a time and publishes it as (action, clip
 * frame 0's game time, blend ticks, playback speed); the client samples the matching clip on the same world clock.
 * Frame numbers below are frames of the 20 FPS sources in BOSS/钢铁卫戍/动作, i.e. game ticks at normal speed.
 *
 * <p>Phase 1 punches with its fists (attack_left into attack_right). After 20 seconds of fighting, or below 80%
 * health, the left fist turns into the chainsaw (phase 2) and the boss fights with a three-segment saw combo:
 * left-to-right sweep, right-to-left sweep, thrust. The sweeps (and the punches) can be parried, which staggers the
 * boss and opens its riposte execution; the thrust cannot be parried but can be rolled through, and skewers what it
 * hits. The right fist opens into a cannon after every third combo round, or when the target keeps more than seven
 * blocks away for six seconds: four shells at the target, three into the sky, and once the cannon is folded away
 * those come down as mortar shells on the target, one every 1.5 seconds.
 */
public class IronSentinel extends Monster implements BossParryTarget, ExecutionTarget {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Drawn at the kit's own size (vanilla iron golem height): the saw then reaches the 3.5 blocks it hits at. */
    public static final float RENDER_SCALE = 1.0F;
    public static final double WALK_SPEED = 0.09;
    /** Ground speed the walk clips are authored for (钢铁卫戍 README: 0.6 px per tick at scale 1). */
    public static final double WALK_CLIP_SPEED = 0.6 / 16.0 * RENDER_SCALE;

    // Phase 2: after 20 seconds of fighting or below 80% health, between two actions.
    static final int PHASE2_COMBAT_TICKS = 20 * 20;
    static final float PHASE2_HEALTH = 0.8F;
    /** iron_sentinel_saw_transform: the fist splits at 13, the saw is out at 33. */
    static final int TRANSFORM_SPLIT_FRAME = 13;
    static final int TRANSFORM_READY_FRAME = 33;
    static final int TRANSFORM_LENGTH = 44;

    // Phase 1: attack_left (the left arm sweeps from the far left to the far right) chained into attack_right.
    static final double PUNCH_TRIGGER_RANGE = 3.0;
    static final double PUNCH_RANGE = 3.0;
    static final float PUNCH_HALF_ANGLE = 80.0F;
    static final float PUNCH_DAMAGE = 3.0F;
    static final int PUNCH_FIRST_FRAME = 16;
    static final int PUNCH_LAST_FRAME = 20;
    /** The striding foot carries the boss this far forward over these frames, stopping short of the target. */
    static final int PUNCH_STEP_FROM = 7;
    static final int PUNCH_STEP_TO = 17;
    static final double PUNCH_STEP = 1.0;
    static final int PUNCH_CHAIN_FRAME = 26;
    static final int PUNCH_LENGTH = 46;
    static final int PUNCH_COOLDOWN = 20;

    // Phase 2: the saw combo. A round is left, right and left sweep, then the thrust. One sweep per round plays 50%
    // faster, in turn: the first one in the first round, the second in the next, the third in the one after, then
    // the first again. After a parry the next round opens with the thrust.
    static final double SAW_TRIGGER_RANGE = 4.5;
    static final double SAW_RANGE = 3.5;
    static final float SAW_DAMAGE = 6.0F;
    /** Every saw segment lunges this far forward, stopping LUNGE_MIN_GAP short of the target's hitbox. */
    static final double SAW_LUNGE = 2.0;
    static final double LUNGE_MIN_GAP = 0.5;
    static final float FAST_SPEED = 1.5F;
    static final float SWEEP_HALF_ANGLE = 75.0F;
    static final int SWEEP_ACTIVE_FROM = 15;
    static final int SWEEP_ACTIVE_TO = 21;
    static final int SWEEP_LUNGE_FROM = 4;
    static final int SWEEP_LUNGE_TO = 17;
    static final int SWEEP_CHAIN_FRAME = 25;
    static final int SWEEP_LR_LENGTH = 44;
    static final int SWEEP_RL_LENGTH = 46;
    /**
     * The thrust (saw_thrust / saw_cannon_thrust): its wind-up always plays 50% faster up to the end of its active
     * frames, it keeps turning to the target until THRUST_AIM_LOCK_FRAME, and it hits a narrow cone: only a dodge
     * roll's invulnerability gets out of it (its damage type is not in fiercerbattles:ignores_dodge_roll).
     */
    static final float THRUST_SPEED = 1.5F;
    static final int THRUST_FAST_UNTIL = 12;
    static final int THRUST_AIM_LOCK_FRAME = 7;
    static final float THRUST_TURN_RATE = 20.0F;
    static final int THRUST_HIT_FRAME = 10;
    static final int THRUST_ACTIVE_FROM = 9;
    static final int THRUST_ACTIVE_TO = 12;
    static final int THRUST_LUNGE_FROM = 5;
    static final int THRUST_LUNGE_TO = 10;
    static final float THRUST_HALF_ANGLE = 25.0F;
    /** Opening a round after a parry, a thrust that misses chains into the sweeps from this frame. */
    static final int THRUST_CHAIN_FRAME = 22;
    static final int THRUST_LENGTH = 36;
    static final int SAW_COOLDOWN = 12;
    static final int CHAIN_BLEND_TICKS = 5;
    private static final IronSentinelAction[] COMBO = {IronSentinelAction.SWEEP_LR, IronSentinelAction.SWEEP_RL,
            IronSentinelAction.SWEEP_LR, IronSentinelAction.THRUST};
    private static final IronSentinelAction[] PUNISH = {IronSentinelAction.THRUST, IronSentinelAction.SWEEP_LR,
            IronSentinelAction.SWEEP_RL, IronSentinelAction.SWEEP_LR};
    private static final int SWEEPS_PER_ROUND = 3;

    // The impale (saw_impale / saw_cannon_impale, started at the thrust's hit frame): lifted overhead from 14, sawn
    // 24-54, flung off the blade at 55 (IronSentinelPoints).
    static final int IMPALE_RELEASE_FRAME = 55;
    static final int IMPALE_LENGTH = 84;
    static final int[] IMPALE_GRIND_FRAMES = {24, 34, 44, 54};
    static final float IMPALE_GRIND_DAMAGE = 1.0F;
    static final float IMPALE_MAX_WIDTH = 1.0F;
    static final float IMPALE_MAX_HEIGHT = 2.2F;
    /** The saw runs through the victim's middle, this share of its height above its feet. */
    public static final double IMPALE_HEIGHT = 0.5;
    /** The flung victim is watched this long for its landing. */
    static final int RELEASE_FLIGHT_TICKS = 40;

    // Parried: iron_sentinel_stagger / saw_stagger. It can be executed (the paired action below, 20 true damage)
    // until it starts to get up.
    static final int STAGGER_LENGTH = 56;
    static final int STAGGER_EXECUTABLE_TO = 42;
    static final int EXECUTED_STAB_FRAME = 7;
    static final int EXECUTED_RIP_FRAME = 17;
    static final int EXECUTED_LENGTH = 76;
    static final float EXECUTION_DAMAGE = 20.0F;
    public static final String EXECUTION_ACTION = FiercerBattles.MOD_ID + ":iron_sentinel_execution";

    // Death: iron_sentinel_death / saw_death (60 frames): thrown back a step, the furnace sputters, it sags, then
    // topples forward like a felled statue (30) and crashes face down (44); the body goes a short while later.
    public static final int DEATH_CLIP_FRAMES = 60;
    public static final int DEATH_IMPACT_FRAME = 44;
    static final int DEATH_TOPPLE_FRAME = 30;
    static final int DEATH_TICKS = DEATH_CLIP_FRAMES + 10;
    /** The fallen body's middle lies this far in front of the entity (blocks). */
    static final double DEATH_BODY_FORWARD = 1.7;

    // The cannon (skill_cannon / saw_cannon, the right fist): deploy 0-48, then one shot (frames 48-62) per 15 ticks,
    // four at the target, the sky volley (cannon_sky: shots at 8, 13, 18), and the retract (frames 84-118).
    static final double CANNON_FAR = 7.0;
    static final int CANNON_FAR_TICKS = 6 * 20;
    static final int CANNON_AFTER_ROUNDS = 3;
    static final double CANNON_MAX_RANGE = 40.0;
    static final float CANNON_TURN_RATE = 10.0F;
    static final int CANNON_DEPLOY_FRAMES = 48;
    public static final int CANNON_FIRE_FRAME = 48;
    public static final int CANNON_FIRE_CLIP_FRAMES = 14;
    public static final int CANNON_FIRE_PERIOD = 15;
    static final int CANNON_SHOTS = 4;
    static final int[] SKY_SHOT_FRAMES = {8, 13, 18};
    static final int SKY_LENGTH = 32;
    public static final int CANNON_RETRACT_FRAME = 84;
    static final int CANNON_LENGTH = 118;
    /** Phase 2: a target this close while the cannon fires is met with the thrust (once per cannon). */
    static final double CANNON_INTERRUPT_RANGE = 4.0;
    static final double SHELL_SPEED = 1.2;
    static final double SKY_SHELL_SPEED = 1.6;
    /** After the retract, a mortar shell per sky shot comes down on the target, one every 1.5 seconds. */
    static final int MORTAR_COUNT = 3;
    static final int MORTAR_INTERVAL = 30;

    static final int LOCOMOTION_BLEND_TICKS = 4;
    static final int ACTION_BLEND_TICKS = 4;
    /** Each blow checks its slice of the arc plus this margin, so a target near a slice edge is not skipped. */
    static final float SLICE_MARGIN = 10.0F;

    private static final EntityDataAccessor<Integer> DATA_ACTION = SynchedEntityData.defineId(IronSentinel.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> DATA_ACTION_START = SynchedEntityData.defineId(IronSentinel.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> DATA_BLEND = SynchedEntityData.defineId(IronSentinel.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_SPEED = SynchedEntityData.defineId(IronSentinel.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_FAST_UNTIL = SynchedEntityData.defineId(IronSentinel.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_PHASE = SynchedEntityData.defineId(IronSentinel.class, EntityDataSerializers.INT);

    private final ServerBossEvent bossEvent = (ServerBossEvent) new ServerBossEvent(getDisplayName(),
            BossEvent.BossBarColor.WHITE, BossEvent.BossBarOverlay.NOTCHED_10).setDarkenScreen(true);

    // Server combat state.
    private float lockedYaw;
    private float chainFromYaw;
    private final Set<Integer> hitThisSegment = new HashSet<>();
    /** Ticks spent with a target; the 20 second rule of phase 2. */
    private int combatTicks;
    private int attackCooldown;
    /** Ticks in a row the target has been more than CANNON_FAR away. */
    private int farTicks;
    private IronSentinelAction[] roundOrder = COMBO;
    private int comboStep;
    /** Saw rounds begun; round n plays its sweep n % SWEEPS_PER_ROUND fast. */
    private int sawRoundsStarted;
    private int roundsSinceCannon;
    private boolean cannonDue;
    /** Parried: the next saw round opens with the thrust. */
    private boolean punishPending;
    /** Which sweep of the current round (0, 1, 2) plays fast. */
    private int fastSweep;
    private int shotsFired;
    private boolean cannonThrustUsed;
    private int mortarsLeft;
    private long nextMortar;
    @Nullable private Vec3 lastTargetPos;
    private int repathTicks;
    /** Set while the boss itself lets go of its victim, so the dismount is not blocked (BossEvents). */
    private boolean releasing;
    @Nullable private LivingEntity released;
    private Vec3 releaseVelocity = Vec3.ZERO;
    private int releasedTicks;
    /** Horizontal drive set by the move control for this tick: unit direction and blocks per tick. */
    private double driveX, driveZ, driveSpeed;

    // Client animation state; plain data so this class stays loadable on a dedicated server.
    public final IronSentinelPoseState poseState = new IronSentinelPoseState();
    private double walkDistance;
    private double walkDistanceO;

    public IronSentinel(EntityType<? extends IronSentinel> type, Level level) {
        super(type, level);
        this.moveControl = new ZombieLord.LordMoveControl(this, this::drive);
        this.xpReward = 80;
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 200.0)
                .add(Attributes.ARMOR, 10.0)
                .add(Attributes.ATTACK_DAMAGE, SAW_DAMAGE)
                .add(Attributes.MOVEMENT_SPEED, WALK_SPEED)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.STEP_HEIGHT, 1.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION, IronSentinelAction.IDLE.ordinal());
        builder.define(DATA_ACTION_START, 0L);
        builder.define(DATA_BLEND, 0);
        builder.define(DATA_SPEED, 1.0F);
        builder.define(DATA_FAST_UNTIL, 0.0F);
        builder.define(DATA_PHASE, 1);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    // ---------------------------------------------------------------- synced action

    public IronSentinelAction getAction() { return IronSentinelAction.byId(entityData.get(DATA_ACTION)); }
    public long getActionStart() { return entityData.get(DATA_ACTION_START); }
    public int getActionBlend() { return entityData.get(DATA_BLEND); }
    /** 1 with fists, 2 once the chainsaw is out. */
    public int getPhase() { return entityData.get(DATA_PHASE); }
    private boolean saw() { return getPhase() >= 2; }

    private void setPhase(int phase) {
        entityData.set(DATA_PHASE, phase);
        bossEvent.setColor(phase >= 2 ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.WHITE);
    }

    /** Ticks since the current clip's frame 0 on the shared world clock; negative while a delayed clip blends in. */
    public double actionTime(float partialTick) {
        return level().getGameTime() - getActionStart() + partialTick;
    }

    private int actionTick() { return (int) (level().getGameTime() - getActionStart()); }

    /**
     * Clip frame at {@code elapsed} ticks after frame 0 when the clip's first {@code until} frames play at
     * {@code speed} and the rest at the authored rate. Shared by the server's frame logic and the client's poser.
     */
    public static double clipTime(double elapsed, float speed, float until) {
        if (elapsed <= 0.0 || speed == 1.0F) return elapsed;
        double fast = until / speed;
        return elapsed <= fast ? elapsed * speed : until + (elapsed - fast);
    }

    /** The current clip's frame at this point of the tick. */
    public double clipTime(float partialTick) {
        return clipTime(actionTime(partialTick), entityData.get(DATA_SPEED), entityData.get(DATA_FAST_UNTIL));
    }

    private double clipFrame(int tick) {
        return clipTime(tick, entityData.get(DATA_SPEED), entityData.get(DATA_FAST_UNTIL));
    }

    /**
     * @param startOffset ticks from now at which the clip's frame 0 plays: positive delays it (a blend into frame 0),
     *                    negative starts it mid-clip.
     * @param speed       clip frames per tick over the clip's first {@code until} frames (1 = as authored)
     */
    private void setAction(IronSentinelAction action, int blendTicks, int startOffset, float speed, float until) {
        LOGGER.debug("Iron Sentinel {} {} -> {} at game time {} (clip frame 0 at +{} ticks, blend {}, speed {} until {})",
                getId(), getAction(), action, level().getGameTime(), startOffset, blendTicks, speed, until);
        entityData.set(DATA_ACTION, action.ordinal());
        entityData.set(DATA_ACTION_START, level().getGameTime() + startOffset);
        entityData.set(DATA_BLEND, blendTicks);
        entityData.set(DATA_SPEED, speed);
        entityData.set(DATA_FAST_UNTIL, until);
        hitThisSegment.clear();
    }

    private void setAction(IronSentinelAction action, int blendTicks, int startOffset) {
        setAction(action, blendTicks, startOffset, 1.0F, 0.0F);
    }

    private IronSentinelAction idleAction() { return saw() ? IronSentinelAction.SAW_IDLE : IronSentinelAction.IDLE; }

    private IronSentinelAction walkAction() { return saw() ? IronSentinelAction.SAW_WALK : IronSentinelAction.WALK; }

    private void setLocomotion(IronSentinelAction action) {
        if (getAction() != action) setAction(action, LOCOMOTION_BLEND_TICKS, 0);
    }

    // ---------------------------------------------------------------- server AI

    @Nullable
    private LivingEntity target() {
        LivingEntity target = getTarget();
        return target != null && isEnemy(target) ? target : null;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        driveSpeed = 0;
        bossEvent.setProgress(getHealth() / getMaxHealth());
        LivingEntity target = target();
        if (target != null) {
            combatTicks++;
            lastTargetPos = target.position();
        }
        tickReleased();
        tickMortars(target);
        if (isVehicle() && !getAction().impaling()) letGo();
        int t = actionTick();
        switch (getAction()) {
            case IDLE, WALK, SAW_IDLE, SAW_WALK -> tickChase(target);
            case ATTACK_LEFT -> tickPunch(target, t, true);
            case ATTACK_RIGHT -> tickPunch(target, t, false);
            case TRANSFORM -> tickTransform(t);
            case SWEEP_LR, SWEEP_RL, THRUST -> tickSaw(target, t);
            case IMPALE, CANNON_IMPALE -> tickImpale(target, t);
            case STAGGER, SAW_STAGGER -> {
                applyYaw(lockedYaw);
                if (t >= STAGGER_LENGTH) endAction();
            }
            case EXECUTED, SAW_EXECUTED -> {
                // The execution session holds the boss (no AI) for the player's half; it gets back up afterwards.
                if (t >= EXECUTED_LENGTH) endAction();
            }
            case CANNON_DEPLOY, SAW_CANNON_DEPLOY -> tickCannonDeploy(target, t);
            case CANNON_FIRE, SAW_CANNON_FIRE -> tickCannonFire(target, t);
            case CANNON_SKY, SAW_CANNON_SKY -> tickCannonSky(target, t);
            case CANNON_THRUST -> tickCannonThrust(target, t);
            case CANNON_RETRACT, SAW_CANNON_RETRACT -> tickCannonRetract(t);
        }
    }

    private void tickChase(@Nullable LivingEntity target) {
        if (target == null) {
            getNavigation().stop();
            farTicks = 0;
            setLocomotion(idleAction());
            return;
        }
        // Phase change and the cannon only start between actions, never in the middle of a combo.
        if (!saw() && (combatTicks > PHASE2_COMBAT_TICKS || getHealth() < getMaxHealth() * PHASE2_HEALTH)) {
            startTransform();
            return;
        }
        double distance = horizontalDistance(target.position());
        farTicks = distance > CANNON_FAR ? farTicks + 1 : 0;
        boolean sees = hasLineOfSight(target);
        if ((cannonDue || farTicks > CANNON_FAR_TICKS) && distance < CANNON_MAX_RANGE && sees) {
            startCannon(target, cannonDue ? "every " + CANNON_AFTER_ROUNDS + " rounds" : "kept away");
            return;
        }
        if (attackCooldown > 0) attackCooldown--;
        double reach = saw() ? SAW_TRIGGER_RANGE : PUNCH_TRIGGER_RANGE;
        boolean inReach = distance - target.getBbWidth() / 2 <= reach && Math.abs(target.getY() - getY()) < 2.5 && sees;
        if (inReach && attackCooldown <= 0) {
            if (saw()) startSawRound(target);
            else startPunch(target);
            return;
        }
        if (inReach) {
            // Waiting out the cooldown, facing the target.
            getNavigation().stop();
            setLocomotion(idleAction());
            applyYaw(Mth.approachDegrees(getYRot(), yawToward(target.position()), 12.0F));
            return;
        }
        if (--repathTicks <= 0 || getNavigation().isDone()) {
            getNavigation().moveTo(target, WALK_SPEED);
            repathTicks = 5;
        }
        boolean moving = !getNavigation().isDone();
        setLocomotion(moving ? walkAction() : idleAction());
        if (!moving) applyYaw(Mth.approachDegrees(getYRot(), yawToward(target.position()), 12.0F));
    }

    private void startTransform() {
        LOGGER.debug("Iron Sentinel {} enters phase 2 after {} ticks of fighting at {}/{}", getId(), combatTicks,
                Math.round(getHealth()), Math.round(getMaxHealth()));
        getNavigation().stop();
        lockedYaw = getYRot();
        letGo();
        setAction(IronSentinelAction.TRANSFORM, ACTION_BLEND_TICKS, 0);
        playSound(SoundEvents.IRON_GOLEM_REPAIR, 2.0F, 0.5F);
    }

    /** The fist splits, the saw runs out of the forearm and revs; phase 2 from the end of the clip. */
    private void tickTransform(int t) {
        applyYaw(lockedYaw);
        if (t == TRANSFORM_SPLIT_FRAME) playSound(SoundEvents.PISTON_EXTEND, 1.5F, 0.6F);
        if (t == TRANSFORM_READY_FRAME) {
            playSound(SoundEvents.GRINDSTONE_USE, 2.0F, 0.5F);
            sawSparks(0.0);
        }
        if (t >= TRANSFORM_LENGTH) {
            setPhase(2);
            endAction();
        }
    }

    // ---------------------------------------------------------------- phase 1: fists

    private void startPunch(LivingEntity target) {
        getNavigation().stop();
        lockedYaw = yawToward(target.position());
        applyYaw(lockedYaw);
        setAction(IronSentinelAction.ATTACK_LEFT, ACTION_BLEND_TICKS, 0);
    }

    /** attack_left sweeps the left arm from the far left to the far right, attack_right the right arm back. */
    private void tickPunch(@Nullable LivingEntity target, int t, boolean left) {
        if (t < 0) {
            turnOverChain(t);
            return;
        }
        applyYaw(lockedYaw);
        advance(target, t - 1, t, PUNCH_STEP_FROM, PUNCH_STEP_TO, PUNCH_STEP);
        if (t == PUNCH_FIRST_FRAME) playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.5F, 0.5F);
        if (t >= PUNCH_FIRST_FRAME && t <= PUNCH_LAST_FRAME) {
            double slices = PUNCH_LAST_FRAME - PUNCH_FIRST_FRAME + 1;
            if (arcSweep((t - PUNCH_FIRST_FRAME) / slices, (t - PUNCH_FIRST_FRAME + 1) / slices, !left, PUNCH_RANGE,
                    PUNCH_HALF_ANGLE, BossEntities.SENTINEL_PUNCH, PUNCH_DAMAGE)) return;
        }
        if (left && t >= PUNCH_CHAIN_FRAME) {
            chain(target);
            setAction(IronSentinelAction.ATTACK_RIGHT, CHAIN_BLEND_TICKS, CHAIN_BLEND_TICKS);
        } else if (!left && t >= PUNCH_LENGTH) {
            attackCooldown = PUNCH_COOLDOWN;
            endAction();
        }
    }

    /** Lock the target once more for the next segment; its blend turns the boss toward it (turnOverChain). */
    private void chain(@Nullable LivingEntity target) {
        chainFromYaw = lockedYaw;
        Vec3 aim = target != null ? target.position() : lastTargetPos;
        if (aim != null && horizontalDistance(aim) > 0.1) lockedYaw = yawToward(aim);
    }

    /** Over a chain's blend (t runs -CHAIN_BLEND_TICKS .. -1) the boss turns from the old facing to the new one. */
    private void turnOverChain(int t) {
        float progress = (t + CHAIN_BLEND_TICKS + 1) / (float) CHAIN_BLEND_TICKS;
        applyYaw(Mth.rotLerp(Math.min(1.0F, progress), chainFromYaw, lockedYaw));
    }

    // ---------------------------------------------------------------- phase 2: the saw combo

    private void startSawRound(LivingEntity target) {
        getNavigation().stop();
        roundOrder = punishPending ? PUNISH : COMBO;
        punishPending = false;
        fastSweep = sawRoundsStarted % SWEEPS_PER_ROUND;
        LOGGER.debug("Iron Sentinel {} saw round {}: {}, sweep {} fast", getId(), sawRoundsStarted + 1,
                Arrays.toString(roundOrder), fastSweep + 1);
        sawRoundsStarted++;
        comboStep = 0;
        lockedYaw = yawToward(target.position());
        applyYaw(lockedYaw);
        startSegment(ACTION_BLEND_TICKS, 0);
    }

    /** Segment comboStep of the round: the thrust with its fast wind-up, a sweep fast if it is the round's fast one. */
    private void startSegment(int blendTicks, int startOffset) {
        IronSentinelAction action = roundOrder[comboStep];
        if (action == IronSentinelAction.THRUST) {
            setAction(action, blendTicks, startOffset, THRUST_SPEED, THRUST_FAST_UNTIL);
            return;
        }
        int sweep = 0;
        for (int i = 0; i < comboStep; i++) {
            if (roundOrder[i] != IronSentinelAction.THRUST) sweep++;
        }
        setAction(action, blendTicks, startOffset, sweep == fastSweep ? FAST_SPEED : 1.0F, Float.MAX_VALUE);
    }

    private void tickSaw(@Nullable LivingEntity target, int t) {
        if (t < 0) {
            turnOverChain(t);
            return;
        }
        IronSentinelAction action = getAction();
        double now = clipFrame(t), before = clipFrame(t - 1);
        boolean thrust = action == IronSentinelAction.THRUST;
        if (thrust) {
            if (thrustTick(target, now, before, false)) return;
        } else {
            applyYaw(lockedYaw);
            advance(target, before, now, SWEEP_LUNGE_FROM, SWEEP_LUNGE_TO, SAW_LUNGE);
            if (before < SWEEP_ACTIVE_FROM && now >= SWEEP_ACTIVE_FROM) {
                playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.5F, 0.6F);
                playSound(SoundEvents.GRINDSTONE_USE, 1.5F, 0.7F);
            }
            if (before < SWEEP_ACTIVE_TO && now >= SWEEP_ACTIVE_FROM - 1) {
                double span = SWEEP_ACTIVE_TO - SWEEP_ACTIVE_FROM + 1;
                double from = Mth.clamp((before - (SWEEP_ACTIVE_FROM - 1)) / span, 0.0, 1.0);
                double to = Mth.clamp((now - (SWEEP_ACTIVE_FROM - 1)) / span, 0.0, 1.0);
                // saw_sweep_lr runs from the boss's left to its right, saw_sweep_rl back.
                if (to > from && arcSweep(from, to, action == IronSentinelAction.SWEEP_RL, SAW_RANGE, SWEEP_HALF_ANGLE,
                        BossEntities.SENTINEL_SAW, SAW_DAMAGE)) return;
            }
        }
        boolean last = comboStep >= roundOrder.length - 1;
        int chainFrame = thrust ? THRUST_CHAIN_FRAME : SWEEP_CHAIN_FRAME;
        if (!last && now >= chainFrame) {
            chain(target);
            comboStep++;
            startSegment(CHAIN_BLEND_TICKS, CHAIN_BLEND_TICKS);
        } else if (last && now >= segmentLength(action)) {
            endRound();
        }
    }

    private static int segmentLength(IronSentinelAction action) {
        return switch (action) {
            case SWEEP_LR -> SWEEP_LR_LENGTH;
            case SWEEP_RL -> SWEEP_RL_LENGTH;
            default -> THRUST_LENGTH;
        };
    }

    /**
     * One tick of a thrust (saw_thrust or saw_cannon_thrust): turn to the target until the aim locks, lunge, and hit
     * the narrow cone over the active frames; returns true once a victim has been skewered (the impale took over).
     */
    private boolean thrustTick(@Nullable LivingEntity target, double now, double before, boolean cannon) {
        if (now <= THRUST_AIM_LOCK_FRAME && target != null && horizontalDistance(target.position()) > 0.5) {
            lockedYaw = Mth.approachDegrees(lockedYaw, yawToward(target.position()), THRUST_TURN_RATE);
        }
        applyYaw(lockedYaw);
        advance(target, before, now, THRUST_LUNGE_FROM, THRUST_LUNGE_TO, SAW_LUNGE);
        if (before < THRUST_ACTIVE_FROM && now >= THRUST_ACTIVE_FROM) {
            playSound(SoundEvents.PLAYER_ATTACK_STRONG, 1.5F, 0.5F);
            playSound(SoundEvents.GRINDSTONE_USE, 1.5F, 0.8F);
        }
        if (before >= THRUST_ACTIVE_TO || now < THRUST_ACTIVE_FROM) return false;
        DamageSource source = damageSources().source(BossEntities.SENTINEL_THRUST, this);
        LivingEntity skewered = null;
        for (LivingEntity victim : victims(getBoundingBox().inflate(SAW_RANGE + 1.0, 2.0, SAW_RANGE + 1.0))) {
            Vec3 offset = victim.position().subtract(position());
            double distance = offset.horizontalDistance();
            if (distance - victim.getBbWidth() / 2 > SAW_RANGE || Math.abs(offset.y) > 2.5) continue;
            float bearing = distance < 1.0 ? 0.0F : relativeBearing(offset);
            if (Math.abs(bearing) > THRUST_HALF_ANGLE || !hitThisSegment.add(victim.getId())) continue;
            boolean hurt = victim.hurt(source, SAW_DAMAGE);
            LOGGER.debug("Iron Sentinel {} thrust hit {} at {} deg, {} blocks: {} damage, landed={}", getId(), victim,
                    Math.round(bearing), String.format("%.2f", distance), SAW_DAMAGE, hurt);
            if (hurt && victim.isAlive() && impalable(victim) && (skewered == null || victim == target)) skewered = victim;
        }
        if (skewered == null) return false;
        startImpale(skewered, cannon);
        return true;
    }

    /** A round (the three segments) is over: the cannon follows every third one. */
    private void endRound() {
        roundsSinceCannon++;
        if (roundsSinceCannon >= CANNON_AFTER_ROUNDS) cannonDue = true;
        attackCooldown = SAW_COOLDOWN;
        endAction();
    }

    // ---------------------------------------------------------------- the impale (a passenger on the saw)

    private static boolean impalable(LivingEntity entity) {
        return !entity.isPassenger() && !entity.isVehicle() && entity.getBbWidth() <= IMPALE_MAX_WIDTH
                && entity.getBbHeight() <= IMPALE_MAX_HEIGHT;
    }

    private void startImpale(LivingEntity victim, boolean cannon) {
        // The victim rides the boss while it is skewered: both sides then place it on the saw every tick.
        if (!victim.startRiding(this, true)) {
            LOGGER.debug("Iron Sentinel {} could not lift {}", getId(), victim);
            return;
        }
        LOGGER.debug("Iron Sentinel {} skewers {}", getId(), victim);
        setAction(cannon ? IronSentinelAction.CANNON_IMPALE : IronSentinelAction.IMPALE, 2, -THRUST_HIT_FRAME);
        positionRider(victim);
        playSound(SoundEvents.PLAYER_ATTACK_CRIT, 1.5F, 0.6F);
    }

    /** Heave the skewered victim overhead, saw it, fling it forward off the blade; then back to the stance. */
    private void tickImpale(@Nullable LivingEntity target, int t) {
        applyYaw(lockedYaw);
        LivingEntity victim = getFirstPassenger() instanceof LivingEntity held ? held : null;
        if (victim != null) positionRider(victim);
        if (victim != null && t > 22 && t < IMPALE_RELEASE_FRAME && t % 4 == 0) {
            playSound(SoundEvents.GRINDSTONE_USE, 1.2F, 0.6F + random.nextFloat() * 0.2F);
            sawSparks(1.0);
        }
        for (int frame : IMPALE_GRIND_FRAMES) {
            if (t != frame || victim == null) continue;
            victim.invulnerableTime = 0;
            boolean hurt = victim.hurt(damageSources().source(BossEntities.SENTINEL_IMPALE, this), IMPALE_GRIND_DAMAGE);
            LOGGER.debug("Iron Sentinel {} saws {}: {} damage, landed={}", getId(), victim, IMPALE_GRIND_DAMAGE, hurt);
        }
        if (t == IMPALE_RELEASE_FRAME && victim != null) {
            letGo();
            released = victim;
            releaseVelocity = toWorldDirection(IronSentinelPoints.release(getAction() == IronSentinelAction.CANNON_IMPALE)
                    .scale(RENDER_SCALE));
            releasedTicks = 0;
            playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.5F, 0.5F);
        }
        if (t >= IMPALE_LENGTH) {
            if (getAction() == IronSentinelAction.CANNON_IMPALE) startRetract();
            else endRound();
        }
    }

    /** Launch the flung victim the tick after the release (after the dismount reached the client). */
    private void tickReleased() {
        LivingEntity victim = released;
        if (victim == null) return;
        releasedTicks++;
        if (!victim.isAlive() || victim.isPassenger() || releasedTicks > RELEASE_FLIGHT_TICKS
                || releasedTicks > 3 && victim.onGround()) {
            released = null;
            return;
        }
        if (releasedTicks == 1) {
            victim.setDeltaMovement(releaseVelocity);
            victim.hasImpulse = true;
            victim.hurtMarked = true;
        }
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

    /** Where a skewered victim's feet go: its middle on the saw's impale point (IronSentinelPoints). */
    public Vec3 heldPosition(Entity passenger) {
        IronSentinelAction action = getAction();
        double time = action.impaling() ? Mth.clamp(actionTime(0.0F), THRUST_HIT_FRAME, IMPALE_RELEASE_FRAME) : THRUST_HIT_FRAME;
        Vec3 point = toWorld(IronSentinelPoints.impale(action == IronSentinelAction.CANNON_IMPALE, time).scale(RENDER_SCALE));
        return point.subtract(0, passenger.getBbHeight() * IMPALE_HEIGHT, 0);
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction move) {
        if (!hasPassenger(passenger)) return;
        Vec3 at = heldPosition(passenger);
        move.accept(passenger, at.x, at.y, at.z);
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        // Let go where it hangs, unless that is inside a block.
        Vec3 held = heldPosition(passenger);
        AABB box = passenger.getDimensions(passenger.getPose()).makeBoundingBox(held);
        return level().noCollision(passenger, box) ? held : super.getDismountLocationForPassenger(passenger);
    }

    @Override
    public boolean shouldRiderSit() { return false; }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        // Only the impale puts anything on the boss (it mounts with force).
        return false;
    }

    @Nullable
    @Override
    public LivingEntity getControllingPassenger() { return null; }

    // ---------------------------------------------------------------- parry and execution

    /** A punch or sweep was parried: no more blows this round; the next saw round opens with the thrust. */
    @Override
    public void parried(LivingEntity defender) {
        IronSentinelAction action = getAction();
        if (action.staggered() || action.executed() || isDeadOrDying()) return;
        LOGGER.debug("Iron Sentinel {} parried by {} during {}", getId(), defender, action);
        getNavigation().stop();
        punishPending = saw();
        lockedYaw = getYRot();
        letGo();
        setAction(saw() ? IronSentinelAction.SAW_STAGGER : IronSentinelAction.STAGGER, 2, 0);
        playSound(SoundEvents.IRON_GOLEM_DAMAGE, 2.0F, 0.6F);
        playSound(SoundEvents.ANVIL_LAND, 1.0F, 0.7F);
        if (level() instanceof ServerLevel level) {
            Vec3 chest = position().add(forward(lockedYaw).scale(0.4)).add(0, 1.6 * RENDER_SCALE, 0);
            level.sendParticles(ParticleTypes.CRIT, chest.x, chest.y, chest.z, 20, 0.5, 0.4, 0.5, 0.3);
        }
    }

    /** Staggered and not yet getting up: open to the riposte execution, at any health. */
    @Nullable
    @Override
    public String executionAction(ServerPlayer executor) {
        return getAction().staggered() && actionTick() <= STAGGER_EXECUTABLE_TO && isAlive() ? EXECUTION_ACTION : null;
    }

    /** The boss plays its half (executed / saw_executed) from the frame the player's half starts at. */
    @Override
    public void executionStarted(ServerPlayer executor, long startGameTime) {
        int delay = (int) (startGameTime - level().getGameTime());
        LOGGER.debug("Iron Sentinel {} executed by {}, frame 0 in {} ticks", getId(), executor, delay);
        getNavigation().stop();
        setAction(saw() ? IronSentinelAction.SAW_EXECUTED : IronSentinelAction.EXECUTED, Math.max(1, delay), delay);
    }

    /** The player's half has played: 20 damage that no armour, enchantment or effect reduces. */
    @Override
    public void executionFinished(ServerPlayer executor) {
        DamageSource source = new DamageSource(level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(BossEntities.SENTINEL_EXECUTION), executor);
        invulnerableTime = 0;
        boolean hurt = hurt(source, EXECUTION_DAMAGE);
        LOGGER.debug("Iron Sentinel {} execution finished by {}: {} damage, landed={}, health {}", getId(), executor,
                EXECUTION_DAMAGE, hurt, getHealth());
    }

    /** Sparks and sounds of the execution; the session holds the boss without AI, so they run from tick(). */
    private void executedEffects(int t) {
        if (!(level() instanceof ServerLevel level)) return;
        Vec3 chest = position().add(forward(getYRot()).scale(0.5)).add(0, 1.35 * RENDER_SCALE, 0);
        if (t == EXECUTED_STAB_FRAME) {
            playSound(SoundEvents.PLAYER_ATTACK_STRONG, 1.5F, 0.8F);
            playSound(SoundEvents.ANVIL_PLACE, 1.5F, 0.6F);
            playSound(SoundEvents.IRON_GOLEM_HURT, 2.0F, 0.5F);
            level.sendParticles(ParticleTypes.LAVA, chest.x, chest.y, chest.z, 12, 0.2, 0.2, 0.2, 0.0);
        }
        if (t > EXECUTED_STAB_FRAME && t < EXECUTED_RIP_FRAME) {
            level.sendParticles(ParticleTypes.FLAME, chest.x, chest.y, chest.z, 3, 0.15, 0.15, 0.15, 0.03);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, chest.x, chest.y, chest.z, 2, 0.2, 0.2, 0.2, 0.1);
        }
        if (t == EXECUTED_RIP_FRAME) {
            playSound(SoundEvents.IRON_GOLEM_DAMAGE, 2.0F, 0.5F);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, chest.x, chest.y, chest.z, 16, 0.2, 0.2, 0.2, 0.05);
            level.sendParticles(ParticleTypes.FLAME, chest.x, chest.y, chest.z, 24, 0.1, 0.1, 0.1, 0.15);
        }
    }

    // ---------------------------------------------------------------- the cannon

    private void startCannon(LivingEntity target, String reason) {
        LOGGER.debug("Iron Sentinel {} opens the cannon: {}", getId(), reason);
        getNavigation().stop();
        farTicks = 0;
        cannonDue = false;
        roundsSinceCannon = 0;
        shotsFired = 0;
        cannonThrustUsed = false;
        lockedYaw = yawToward(target.position());
        applyYaw(lockedYaw);
        setAction(saw() ? IronSentinelAction.SAW_CANNON_DEPLOY : IronSentinelAction.CANNON_DEPLOY, ACTION_BLEND_TICKS, 0);
    }

    private void turnToward(@Nullable LivingEntity target, float rate) {
        if (target != null && horizontalDistance(target.position()) > 0.5) {
            lockedYaw = Mth.approachDegrees(lockedYaw, yawToward(target.position()), rate);
        }
        applyYaw(lockedYaw);
    }

    /** The right fist splits and folds back, the barrel runs out; the boss braces and turns onto the target. */
    private void tickCannonDeploy(@Nullable LivingEntity target, int t) {
        turnToward(target, CANNON_TURN_RATE);
        if (t == 14) playSound(SoundEvents.PISTON_EXTEND, 1.5F, 0.5F);
        if (t == 32) playSound(SoundEvents.IRON_GOLEM_REPAIR, 1.5F, 0.6F);
        if (t >= CANNON_DEPLOY_FRAMES) {
            if (target == null) {
                startRetract();
                return;
            }
            // The fire loop starts on its shot frame: the first shell leaves now, the others once per loop; a target
            // already close gets the thrust instead.
            if (cannonInterrupted(target)) return;
            setAction(saw() ? IronSentinelAction.SAW_CANNON_FIRE : IronSentinelAction.CANNON_FIRE, 0, 0);
            fireShell(target);
            shotsFired = 1;
        }
    }

    /** A shell every CANNON_FIRE_PERIOD ticks at the target (each loop of the shot frames), then the sky volley. */
    private void tickCannonFire(@Nullable LivingEntity target, int t) {
        turnToward(target, CANNON_TURN_RATE);
        if (target == null || horizontalDistance(target.position()) > CANNON_MAX_RANGE) {
            startRetract();
            return;
        }
        if (cannonInterrupted(target)) return;
        if (t > 0 && t % CANNON_FIRE_PERIOD == 0 && shotsFired < CANNON_SHOTS) {
            fireShell(target);
            shotsFired++;
        }
        if (t >= CANNON_FIRE_PERIOD * CANNON_SHOTS) {
            setAction(saw() ? IronSentinelAction.SAW_CANNON_SKY : IronSentinelAction.CANNON_SKY, 3, 0);
        }
    }

    /** Three quick shots into the sky (they come down after the retract, tickMortars). */
    private void tickCannonSky(@Nullable LivingEntity target, int t) {
        applyYaw(lockedYaw);
        if (cannonInterrupted(target)) return;
        for (int i = 0; i < SKY_SHOT_FRAMES.length; i++) {
            if (t == SKY_SHOT_FRAMES[i]) fireSky(i);
        }
        if (t >= SKY_LENGTH) startRetract();
    }

    /**
     * Phase 2: a target closing in while the cannon fires gets the saw thrust (once per cannon), the cannon kept
     * out; a skewered victim is impaled with the cannon still out, and the retract follows either way.
     */
    private boolean cannonInterrupted(@Nullable LivingEntity target) {
        if (!saw() || cannonThrustUsed || target == null) return false;
        double distance = horizontalDistance(target.position()) - target.getBbWidth() / 2;
        if (distance > CANNON_INTERRUPT_RANGE || Math.abs(target.getY() - getY()) >= 2.5) return false;
        LOGGER.debug("Iron Sentinel {} meets {} closing in on the cannon with the thrust", getId(), target);
        cannonThrustUsed = true;
        lockedYaw = yawToward(target.position());
        applyYaw(lockedYaw);
        setAction(IronSentinelAction.CANNON_THRUST, 3, 0, THRUST_SPEED, THRUST_FAST_UNTIL);
        return true;
    }

    private void tickCannonThrust(@Nullable LivingEntity target, int t) {
        double now = clipFrame(t), before = clipFrame(t - 1);
        if (thrustTick(target, now, before, true)) return;
        if (now >= THRUST_LENGTH) startRetract();
    }

    private void startRetract() {
        setAction(saw() ? IronSentinelAction.SAW_CANNON_RETRACT : IronSentinelAction.CANNON_RETRACT, 4, -CANNON_RETRACT_FRAME);
    }

    /** The barrel runs in and the fist closes; then the mortar shells start coming down. */
    private void tickCannonRetract(int t) {
        applyYaw(lockedYaw);
        if (t == 90) playSound(SoundEvents.PISTON_CONTRACT, 1.5F, 0.5F);
        if (t == 101) playSound(SoundEvents.ANVIL_PLACE, 1.0F, 0.8F);
        if (t >= CANNON_LENGTH) {
            mortarsLeft = MORTAR_COUNT;
            nextMortar = level().getGameTime();
            endAction();
        }
    }

    /** The muzzle of the current shot table, in the world (it turns with the boss). */
    private Vec3 muzzle(float[] table, int shot) {
        return toWorld(IronSentinelPoints.muzzle(table, shot).scale(RENDER_SCALE));
    }

    private void fireShell(LivingEntity target) {
        if (!(level() instanceof ServerLevel level)) return;
        Vec3 from = muzzle(saw() ? IronSentinelPoints.SAW_FIRE_MUZZLE : IronSentinelPoints.FIRE_MUZZLE, 0);
        Vec3 aim = target.position().add(0, target.getBbHeight() * 0.5, 0);
        Vec3 velocity = aim.subtract(from).normalize().scale(SHELL_SPEED);
        level.addFreshEntity(SentinelShell.direct(level, this, from, velocity));
        muzzleFlash(level, from, velocity.normalize());
        LOGGER.debug("Iron Sentinel {} fires shell {} at {} from {}", getId(), shotsFired + 1, target, from);
    }

    private void fireSky(int shot) {
        if (!(level() instanceof ServerLevel level)) return;
        float[] table = saw() ? IronSentinelPoints.SAW_SKY_MUZZLE : IronSentinelPoints.SKY_MUZZLE;
        Vec3 from = muzzle(table, shot);
        Vec3 direction = toWorldDirection(IronSentinelPoints.bore(table, shot)).normalize();
        level.addFreshEntity(SentinelShell.sky(level, this, from, direction.scale(SKY_SHELL_SPEED)));
        muzzleFlash(level, from, direction);
    }

    private void muzzleFlash(ServerLevel level, Vec3 at, Vec3 direction) {
        playSound(SoundEvents.GENERIC_EXPLODE.value(), 1.2F, 1.4F);
        playSound(SoundEvents.FIRECHARGE_USE, 1.2F, 0.7F);
        Vec3 out = at.add(direction.scale(0.3));
        level.sendParticles(ParticleTypes.FLAME, out.x, out.y, out.z, 8, 0.1, 0.1, 0.1, 0.05);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, out.x, out.y, out.z, 6, 0.15, 0.15, 0.15, 0.02);
    }

    /** One mortar shell per MORTAR_INTERVAL on the target's position of that moment, marked before it lands. */
    private void tickMortars(@Nullable LivingEntity target) {
        if (mortarsLeft <= 0 || level().getGameTime() < nextMortar || !(level() instanceof ServerLevel level)) return;
        mortarsLeft--;
        nextMortar += MORTAR_INTERVAL;
        Vec3 at = target != null ? target.position() : lastTargetPos;
        if (at == null) return;
        Vec3 ground = ZombieLord.groundBelow(level, at);
        level.addFreshEntity(SentinelShell.mortar(level, this, ground));
        LOGGER.debug("Iron Sentinel {} mortar shell marked at {}", getId(), ground);
    }

    // ---------------------------------------------------------------- hits

    /**
     * The boss moves forward over clip frames [from, to] by up to {@code distance} in all, this tick's share of it,
     * stopping LUNGE_MIN_GAP short of the target's hitbox so it never walks into the target.
     */
    private void advance(@Nullable LivingEntity target, double before, double now, int from, int to, double distance) {
        double covered = Math.min(now, to) - Math.max(before, from);
        if (covered <= 0) return;
        double step = distance * covered / (to - from);
        if (target != null) {
            double gap = horizontalDistance(target.position()) - (getBbWidth() + target.getBbWidth()) / 2 - LUNGE_MIN_GAP;
            step = Math.min(step, Math.max(0.0, gap));
        }
        if (step > 0) move(MoverType.SELF, forward(lockedYaw).scale(step));
    }

    /**
     * The share [from, to] of a horizontal swing across the front arc (+-halfAngle), from the boss's right to its
     * left or the other way; each target is hit once per action segment. Returns true if a parry staggered the boss
     * (the swing stops there).
     */
    private boolean arcSweep(double from, double to, boolean rightToLeft, double range, float halfAngle,
                             ResourceKey<DamageType> type, float damage) {
        float a = (float) Mth.lerp(from, -halfAngle, halfAngle), b = (float) Mth.lerp(to, -halfAngle, halfAngle);
        if (rightToLeft) {
            a = -a;
            b = -b;
        }
        float low = Math.min(a, b) - SLICE_MARGIN, high = Math.max(a, b) + SLICE_MARGIN;
        DamageSource source = damageSources().source(type, this);
        for (LivingEntity victim : victims(getBoundingBox().inflate(range + 1.0, 2.5, range + 1.0))) {
            Vec3 offset = victim.position().subtract(position());
            double distance = offset.horizontalDistance();
            if (distance - victim.getBbWidth() / 2 > range || Math.abs(offset.y) > 2.5) continue;
            float bearing = distance < 1.0 ? 0.0F : relativeBearing(offset);
            if (bearing < low || bearing > high || !hitThisSegment.add(victim.getId())) continue;
            boolean hurt = victim.hurt(source, damage);
            LOGGER.debug("Iron Sentinel {} {} hit {} at {} deg, {} blocks: {} damage, landed={}", getId(), getAction(),
                    victim, Math.round(bearing), String.format("%.2f", distance), damage, hurt);
            if (getAction().staggered()) return true;
        }
        return false;
    }

    private List<LivingEntity> victims(AABB area) {
        return level().getEntitiesOfClass(LivingEntity.class, area, IronSentinel::isEnemy);
    }

    /** Everything the boss and its shells may hurt: not the boss itself, spectators or creative players. */
    static boolean isEnemy(LivingEntity entity) {
        return entity.isAlive() && !entity.isSpectator() && !(entity instanceof IronSentinel)
                && !(entity instanceof Player player && player.isCreative());
    }

    private void sawSparks(double up) {
        if (!(level() instanceof ServerLevel level)) return;
        Vec3 at = position().add(forward(getYRot()).scale(1.6 * RENDER_SCALE)).add(0, (1.0 + up * 2.6) * RENDER_SCALE, 0);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 6, 0.3, 0.2, 0.3, 0.15);
        level.sendParticles(ParticleTypes.SMALL_FLAME, at.x, at.y, at.z, 2, 0.2, 0.1, 0.2, 0.02);
    }

    // ---------------------------------------------------------------- facing and movement

    static Vec3 forward(float yaw) {
        return ZombieLord.forward(yaw);
    }

    /** A point given in the boss's frame (right, up, forward; blocks) in the world. */
    private Vec3 toWorld(Vec3 local) {
        return position().add(toWorldDirection(local));
    }

    private Vec3 toWorldDirection(Vec3 local) {
        Vec3 f = forward(getYRot());
        return new Vec3(-f.z * local.x + f.x * local.z, local.y, f.x * local.x + f.z * local.z);
    }

    /** Signed horizontal angle of {@code offset} from the locked facing, in degrees; positive is the boss's right. */
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

    private void endAction() {
        setNoGravity(false);
        setAction(idleAction(), ACTION_BLEND_TICKS, 0);
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
            if (!getAction().locomotion()) {
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
        } else {
            setYHeadRot(getYRot());
            setYBodyRot(getYRot());
            if (getAction().executed()) executedEffects(actionTick());
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
        // Lunges and turns during attacks are keyed to their clips; follow the server without the 3 tick smoothing.
        super.lerpTo(x, y, z, yRot, xRot, getAction().locomotion() ? steps : 1);
    }

    @Override
    public void push(double x, double y, double z) {
        // Shoving players or mobs never displaces the boss.
    }

    @Override
    public void die(DamageSource source) {
        letGo();
        setNoGravity(false);
        super.die(source);
    }

    /** The death clip plays out (the renderer's tip-over is off) before the body disappears in smoke. */
    @Override
    protected void tickDeath() {
        deathTime++;
        if (!(level() instanceof ServerLevel level)) return;
        if (deathTime == DEATH_TOPPLE_FRAME) playSound(SoundEvents.IRON_GOLEM_DAMAGE, 1.5F, 0.4F);
        if (deathTime == DEATH_IMPACT_FRAME) {
            playSound(SoundEvents.GENERIC_EXPLODE.value(), 1.2F, 0.5F);
            playSound(SoundEvents.ANVIL_LAND, 1.5F, 0.4F);
            Vec3 body = ZombieLord.groundBelow(level, position().add(forward(getYRot()).scale(DEATH_BODY_FORWARD)));
            level.sendParticles(ZombieLord.blockDust(level, body), body.x, body.y + 0.1, body.z, 40, 1.2, 0.1, 1.2, 0.15);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, body.x, body.y + 0.4, body.z, 12, 0.8, 0.2, 0.8, 0.02);
        }
        if (deathTime >= DEATH_TICKS && !isRemoved()) {
            level.broadcastEntityEvent(this, EntityEvent.POOF);
            remove(RemovalReason.KILLED);
        }
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
        // The saw reaches 3.5 blocks out.
        return super.getBoundingBoxForCulling().inflate(SAW_RANGE, 1.0, SAW_RANGE);
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
        tag.putInt("CombatTicks", combatTicks);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setPhase(Math.max(1, tag.getInt("Phase")));
        combatTicks = tag.getInt("CombatTicks");
        if (hasCustomName()) bossEvent.setName(getDisplayName());
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.IRON_GOLEM_HURT; }

    @Override
    protected SoundEvent getDeathSound() { return SoundEvents.IRON_GOLEM_DEATH; }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        playSound(SoundEvents.IRON_GOLEM_STEP, 1.5F, 0.6F);
    }

    @Override
    public float getVoicePitch() { return 0.6F + random.nextFloat() * 0.1F; }

    @Override
    protected float getSoundVolume() { return 2.0F; }
}
