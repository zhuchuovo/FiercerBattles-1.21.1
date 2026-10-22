package com.fiercerbattles.boss;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
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
    static final int STEP_TICKS = 4;
    static final double STEP_DISTANCE = 0.5;
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
    static final float SLAM_DAMAGE_NEAR = 10.0F;
    static final float SLAM_DAMAGE_FAR = 2.0F;

    // Leap slam: replaces attack3 when the target stands beyond the slam's 5 blocks but within 12, and closes the
    // gap when the target has stayed outside melee range for 3 s while the boss was not attacking.
    /** Same measure as the slam (to the near side of the target), so one of the two always reaches it. */
    static final double LEAP_MIN_DISTANCE = SLAM_RADIUS;
    static final double LEAP_MAX_DISTANCE = 12.0;
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
    static final int CHASE_LEAP_TICKS = 3 * 20;

    // Shoulder charge after two full rounds of the combo (attack1-2-3, or 1-2 and the leap that replaced 3).
    static final int CHARGE_AFTER_ROUNDS = 2;
    /** zombie_lord_charge: wind-up 0-18, one dash cycle 18-30 (looped while dashing), recovery 30-52. */
    public static final int CHARGE_WINDUP_FRAMES = 18;
    public static final int CHARGE_CYCLE_FRAMES = 12;
    static final int CHARGE_RECOVER_FRAMES = 22;
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
    /** One summon for every 40% of maximum health lost (at 60% and 20% left). */
    static final float SUMMON_HEALTH_STEP = 0.4F;
    /** A target farther than this also triggers a summon, at most once per cooldown. */
    static final double SUMMON_DISTANCE = 12.0;
    static final int QUAKE_COOLDOWN_TICKS = 20 * 20;

    static final int LOCOMOTION_BLEND_TICKS = 4;
    static final int ACTION_BLEND_TICKS = 4;

    private static final EntityDataAccessor<Integer> DATA_ACTION = SynchedEntityData.defineId(ZombieLord.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> DATA_ACTION_START = SynchedEntityData.defineId(ZombieLord.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> DATA_BLEND = SynchedEntityData.defineId(ZombieLord.class, EntityDataSerializers.INT);

    private final ServerBossEvent bossEvent = (ServerBossEvent) new ServerBossEvent(getDisplayName(),
            BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10).setDarkenScreen(true);

    // Server combat state.
    private float lockedYaw;
    private float chainFromYaw;
    private final Set<Integer> hitThisSegment = new HashSet<>();
    private Vec3 leapFrom = Vec3.ZERO;
    private Vec3 leapTo = Vec3.ZERO;
    private int quakeCooldown;
    /** Consecutive chase ticks with the target outside melee range. */
    private int outOfRangeTicks;
    /** Full combo rounds in a row; the second one is followed by the charge. */
    private int comboRounds;
    private boolean leapEndsRound;
    /** Health-loss summons already used; each 40% lost earns one. */
    private int healthSummonsUsed;
    private int repathTicks;
    @Nullable private Vec3 lastTargetPos;
    private final List<LordSkull> skulls = new ArrayList<>();
    private final List<LordSkeleton> minions = new ArrayList<>();
    private int skullsFired;
    private int allFiredTick = -1;
    /** Horizontal drive set by the move control for this tick: unit direction and blocks per tick. */
    private double driveX, driveZ, driveSpeed;

    // Client animation state; plain data so this class stays loadable on a dedicated server.
    public final ZombieLordPoseState poseState = new ZombieLordPoseState();
    private double walkDistance;
    private double walkDistanceO;

    public ZombieLord(EntityType<? extends ZombieLord> type, Level level) {
        super(type, level);
        this.moveControl = new LordMoveControl(this);
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
        if (quakeCooldown > 0) quakeCooldown--;
        LivingEntity target = getTarget();
        if (target != null && (!target.isAlive() || target.isSpectator())) target = null;
        if (target != null) lastTargetPos = target.position();
        int t = actionTick();
        switch (getAction()) {
            case IDLE, WALK -> tickChase(target);
            case ATTACK1 -> tickAttack1(target, t);
            case ATTACK2 -> tickAttack2(target, t);
            case ATTACK3 -> tickAttack3(target, t);
            case LEAP_SLAM -> tickLeap(target, t);
            case CHARGE -> tickCharge(target, t);
            case CHARGE_END -> {
                applyYaw(lockedYaw);
                if (t >= CHARGE_WINDUP_FRAMES + CHARGE_CYCLE_FRAMES + CHARGE_RECOVER_FRAMES) endAction();
            }
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
        // Summons only start between actions, never in the middle of the combo.
        if (healthSummonsUsed < healthSummonsEarned()) {
            healthSummonsUsed++;
            startQuake("health " + Math.round(getHealth()) + "/" + Math.round(getMaxHealth()));
            return;
        }
        double distance = horizontalDistance(target.position());
        if (distance > SUMMON_DISTANCE && quakeCooldown <= 0 && hasLineOfSight(target)) {
            startQuake("target " + String.format("%.1f", distance) + " blocks away");
            return;
        }
        boolean inReach = distance <= MELEE_TRIGGER_RANGE && Math.abs(target.getY() - getY()) < 2.5;
        if (inReach && hasLineOfSight(target)) {
            startAttack1(target);
            return;
        }
        // Out of reach for 3 s straight (above or below counts too): leap onto the target.
        if (inReach) {
            outOfRangeTicks = 0;
        } else if (++outOfRangeTicks > CHASE_LEAP_TICKS && hasLineOfSight(target)) {
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

    private int healthSummonsEarned() {
        float lost = 1.0F - getHealth() / getMaxHealth();
        return (int) Math.floor(lost / SUMMON_HEALTH_STEP + 1.0E-4F);
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
        stepForward(t);
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
        stepForward(t);
        if (t == SWEEP_FIRST_FRAME) playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.5F, 0.7F);
        if (t >= SWEEP_FIRST_FRAME && t <= SWEEP_LAST_FRAME) sweep(t, false);
        if (t >= ATTACK2_LENGTH) {
            // The third segment only starts once attack2 has fully played.
            if (target != null && leapRange(target)) startLeap(target, true);
            else setAction(ZombieLordAction.ATTACK3, ACTION_BLEND_TICKS, 0);
        }
    }

    private void tickAttack3(@Nullable LivingEntity target, int t) {
        applyYaw(lockedYaw);
        if (t < ATTACK3_LEAP_CUTOFF_FRAME && target != null && leapRange(target)) {
            startLeap(target, true);
            return;
        }
        stepForward(t);
        if (t == ATTACK3_SLAM_FRAME) {
            radialBlow(position(), SLAM_RADIUS, SLAM_DAMAGE_NEAR, SLAM_DAMAGE_FAR);
            dustBurst(position(), SLAM_RADIUS);
        }
        if (t >= ATTACK3_LENGTH) endRound(target);
    }

    /** The third segment (slam or its leap) finished one full round of the combo. */
    private void endRound(@Nullable LivingEntity target) {
        if (++comboRounds >= CHARGE_AFTER_ROUNDS) {
            comboRounds = 0;
            startCharge(target);
        } else {
            endAction();
        }
    }

    private boolean leapRange(LivingEntity target) {
        double distance = horizontalDistance(target.position());
        return distance - target.getBbWidth() / 2 > LEAP_MIN_DISTANCE && distance < LEAP_MAX_DISTANCE;
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
            radialBlow(position(), LEAP_RADIUS, LEAP_DAMAGE_NEAR, LEAP_DAMAGE_FAR);
            dustBurst(position(), LEAP_RADIUS);
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

    /** Wind-up (turning to the target, two boot scrapes), a straight dash that tramples what it meets, recovery. */
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
        double moved = position().subtract(before).horizontalDistance();
        DamageSource source = damageSources().source(BossEntities.LORD_SLASH, this);
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
        boolean blocked = dashTick > 0 && moved < CHARGE_SPEED * 0.4;
        if (blocked) {
            playSound(SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, 1.5F, 0.6F);
            if (level() instanceof ServerLevel level) {
                Vec3 front = position().add(dir.scale(0.8));
                level.sendParticles(ParticleTypes.EXPLOSION, front.x, front.y + 1.2, front.z, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
        if (blocked || dashTick + 1 >= CHARGE_DASH_TICKS) {
            // Recovery: the rest of the clip from the end of the dash cycle.
            setAction(ZombieLordAction.CHARGE_END, 3, -(CHARGE_WINDUP_FRAMES + CHARGE_CYCLE_FRAMES));
        }
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
            quakeCooldown = QUAKE_COOLDOWN_TICKS;
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

    // ---------------------------------------------------------------- hits

    private void stepForward(int t) {
        if (t < 0 || t >= STEP_TICKS) return;
        move(MoverType.SELF, forward(lockedYaw).scale(STEP_DISTANCE / STEP_TICKS));
    }

    /**
     * One hit tick of a horizontal sweep. The 120 degree front arc is cut into one slice per hit tick,
     * swept from the boss's right to its left (attack1) or left to right (attack2).
     */
    private void sweep(int t, boolean rightToLeft) {
        int slices = SWEEP_LAST_FRAME - SWEEP_FIRST_FRAME + 1;
        float width = 2 * SWEEP_HALF_ANGLE / slices;
        float from = SWEEP_HALF_ANGLE - width * (t - SWEEP_FIRST_FRAME);
        float to = from - width;
        if (!rightToLeft) {
            from = -from;
            to = -to;
        }
        float low = Math.min(from, to) - SWEEP_SLICE_MARGIN;
        float high = Math.max(from, to) + SWEEP_SLICE_MARGIN;
        DamageSource source = damageSources().source(BossEntities.LORD_SLASH, this);
        for (LivingEntity victim : victims(getBoundingBox().inflate(SWEEP_RANGE + 1.0, 1.5, SWEEP_RANGE + 1.0))) {
            Vec3 offset = victim.position().subtract(position());
            double distance = offset.horizontalDistance();
            if (distance - victim.getBbWidth() / 2 > SWEEP_RANGE || Math.abs(offset.y) > 3.0) continue;
            float bearing = distance < 1.0 ? 0.0F : relativeBearing(offset);
            if (bearing < low || bearing > high || !hitThisSegment.add(victim.getId())) continue;
            boolean hurt = victim.hurt(source, SWEEP_DAMAGE);
            LOGGER.debug("Zombie Lord {} {} frame {} hit {} at {} deg, {} blocks: {} damage, landed={}",
                    getId(), getAction(), t, victim, Math.round(bearing), String.format("%.2f", distance), SWEEP_DAMAGE, hurt);
        }
    }

    /** Ground blow centred on {@code centre}; damage falls linearly from {@code near} to {@code far} at the rim. */
    /** Ground blow (slam or leap landing): it ignores shields (zombie_lord_smash is in bypasses_shield). */
    private void radialBlow(Vec3 centre, double radius, float near, float far) {
        DamageSource source = damageSources().source(BossEntities.LORD_SMASH, this);
        for (LivingEntity victim : victims(new AABB(centre, centre).inflate(radius + 1.0, 0, radius + 1.0).expandTowards(0, 3.0, 0).move(0, -1.0, 0))) {
            double distance = Math.max(0.0, victim.position().subtract(centre).horizontalDistance() - victim.getBbWidth() / 2);
            if (distance > radius || !hitThisSegment.add(victim.getId())) continue;
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
        if (!(level() instanceof ServerLevel level)) return;
        for (double r = 0.8; r <= radius; r += 0.8) {
            int points = (int) (r * 9);
            for (int i = 0; i < points; i++) {
                double angle = Mth.TWO_PI * i / points;
                Vec3 at = groundBelow(level, centre.add(Math.cos(angle) * r, 0.5, Math.sin(angle) * r));
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
        } else {
            setYHeadRot(getYRot());
            setYBodyRot(getYRot());
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
        // The leap is fast and its landing is keyed to the clip; follow the server without the 3 tick smoothing.
        super.lerpTo(x, y, z, yRot, xRot, getAction() == ZombieLordAction.LEAP_SLAM ? 1 : steps);
    }

    @Override
    public void push(double x, double y, double z) {
        // Shoving players or mobs never displaces the boss.
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
        tag.putInt("QuakeCooldown", quakeCooldown);
        tag.putInt("HealthSummonsUsed", healthSummonsUsed);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        quakeCooldown = tag.getInt("QuakeCooldown");
        healthSummonsUsed = tag.getInt("HealthSummonsUsed");
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

    /** Pathing drive: turns toward the next node and moves at exactly the navigation speed (blocks per tick). */
    static final class LordMoveControl extends MoveControl {
        private final ZombieLord lord;

        LordMoveControl(ZombieLord lord) {
            super(lord);
            this.lord = lord;
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
                lord.drive(dx / horizontal, dz / horizontal, speedModifier);
                if (dy > mob.maxUpStep() && horizontal * horizontal < Math.max(1.0F, mob.getBbWidth())) {
                    mob.getJumpControl().jump();
                    operation = Operation.JUMPING;
                }
            } else if (operation == Operation.JUMPING) {
                double dx = wantedX - mob.getX();
                double dz = wantedZ - mob.getZ();
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                if (horizontal > 1.0E-3) lord.drive(dx / horizontal, dz / horizontal, speedModifier);
                if (mob.onGround()) operation = Operation.WAIT;
            }
        }
    }
}
