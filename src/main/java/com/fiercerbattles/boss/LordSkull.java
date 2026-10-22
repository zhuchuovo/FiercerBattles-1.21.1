package com.fiercerbattles.boss;

import com.mojang.logging.LogUtils;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Skull raised by the Zombie Lord's quake. It spirals up out of the ground around the boss to above
 * its head, hovers until fired, then flies to a point locked at launch. While it flies the client
 * draws the 3x3 blast area at that point. On impact it deals 2 damage in the 3x3 area and a
 * {@link LordSkeleton} digs itself out of the ground there.
 */
public class LordSkull extends Entity {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int RISING = 0;
    public static final int HOVERING = 1;
    public static final int FLYING = 2;
    static final double RISE_RADIUS_START = 2.6;
    static final double RISE_RADIUS_END = 1.4;
    static final double ORBIT_RADIANS_PER_TICK = 0.16;
    /** Average flight speed; flight time is clamped to 1-2 s so there is always time to step out. */
    static final double FLIGHT_SPEED = 0.55;
    static final int MIN_FLIGHT_TICKS = 20;
    static final int MAX_FLIGHT_TICKS = 40;
    static final double FLIGHT_ARC = 1.5;
    /** Half of the 3x3 blast square. */
    public static final double BLAST_HALF_WIDTH = 1.5;
    static final float BLAST_DAMAGE = 2.0F;

    private static final EntityDataAccessor<Integer> DATA_STATE = SynchedEntityData.defineId(LordSkull.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Vector3f> DATA_TARGET = SynchedEntityData.defineId(LordSkull.class, EntityDataSerializers.VECTOR3);

    @Nullable private UUID ownerId;
    @Nullable private ZombieLord owner;
    private float phase;
    private long spawnTime;
    private Vec3 centre = Vec3.ZERO;
    private double topY;
    private Vec3 flyFrom = Vec3.ZERO;
    private Vec3 flyTo = Vec3.ZERO;
    private long flyStart;
    private int flyTicks = MIN_FLIGHT_TICKS;

    public LordSkull(EntityType<? extends LordSkull> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    LordSkull(Level level, ZombieLord owner, float phase) {
        this(BossEntities.LORD_SKULL.get(), level);
        this.owner = owner;
        this.ownerId = owner.getUUID();
        this.phase = phase;
        this.spawnTime = level.getGameTime();
        this.centre = owner.position();
        this.topY = owner.getY() + owner.getBbHeight() + 1.0;
        Vec3 start = orbit(0);
        setPos(start.x, start.y, start.z);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_STATE, RISING);
        builder.define(DATA_TARGET, new Vector3f());
    }

    public int state() { return entityData.get(DATA_STATE); }
    public boolean isFlying() { return state() == FLYING; }
    /** Locked landing point, valid while flying. */
    public Vector3f target() { return entityData.get(DATA_TARGET); }

    /** Bottom-centre of the skull at {@code age} ticks after it broke out of the ground. */
    private Vec3 orbit(long age) {
        double s = Mth.clamp(age / (double) ZombieLord.SKULL_RISE_TICKS, 0.0, 1.0);
        double eased = s * s * (3 - 2 * s);
        double radius = Mth.lerp(eased, RISE_RADIUS_START, RISE_RADIUS_END);
        double angle = phase + age * ORBIT_RADIANS_PER_TICK;
        double y = Mth.lerp(eased, centre.y - 0.9, topY);
        return new Vec3(centre.x + Math.cos(angle) * radius, y, centre.z + Math.sin(angle) * radius);
    }

    private Vec3 flight(double u) {
        return flyFrom.lerp(flyTo, u).add(0, FLIGHT_ARC * Math.sin(Math.PI * u), 0);
    }

    void fire(ServerLevel level, Vec3 target) {
        flyFrom = position();
        flyTo = target;
        flyTicks = Mth.clamp((int) Math.round(flyFrom.distanceTo(flyTo) / FLIGHT_SPEED), MIN_FLIGHT_TICKS, MAX_FLIGHT_TICKS);
        flyStart = level.getGameTime();
        entityData.set(DATA_TARGET, new Vector3f((float) target.x, (float) target.y, (float) target.z));
        entityData.set(DATA_STATE, FLYING);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 1.0F, 0.8F);
        LOGGER.debug("Lord skull {} fired at game time {} from {} to {}, {} ticks", getId(), flyStart, flyFrom, flyTo, flyTicks);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            level().addParticle(ParticleTypes.SOUL_FIRE_FLAME, getX(), getY() + 0.3, getZ(), 0, 0.01, 0);
            if (tickCount % 2 == 0) level().addParticle(ParticleTypes.SMOKE, getX(), getY() + 0.3, getZ(), 0, 0.02, 0);
            return;
        }
        if (!(level() instanceof ServerLevel level)) return;
        ZombieLord lord = owner(level);
        if (state() != FLYING && (lord == null || !lord.isAlive())) {
            level.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.3, getZ(), 6, 0.2, 0.2, 0.2, 0.02);
            discard();
            return;
        }
        long now = level.getGameTime();
        Vec3 next;
        if (state() == FLYING) {
            double u = Math.min(1.0, (now - flyStart) / (double) flyTicks);
            next = flight(u);
            HitResult wall = level.clip(new ClipContext(position(), next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            if (wall.getType() != HitResult.Type.MISS && u < 1.0) {
                // Step back off the face so the blast and the skeleton land beside the wall, not in it.
                Vec3 back = next.subtract(position()).normalize().scale(0.3);
                explode(level, lord, wall.getLocation().subtract(back));
                return;
            }
            AABB swept = getBoundingBox().expandTowards(next.subtract(position()));
            if (u < 1.0 && !level.getEntitiesOfClass(LivingEntity.class, swept, ZombieLord::isEnemy).isEmpty()) {
                explode(level, lord, next);
                return;
            }
            face(next);
            setPos(next.x, next.y, next.z);
            if (u >= 1.0) explode(level, lord, flyTo);
        } else {
            long age = now - spawnTime;
            if (state() == RISING && age >= ZombieLord.SKULL_RISE_TICKS) entityData.set(DATA_STATE, HOVERING);
            next = orbit(age);
            face(next);
            setPos(next.x, next.y, next.z);
            if (age == 0) level.sendParticles(ZombieLord.blockDust(level, next), next.x, next.y + 0.9, next.z, 12, 0.3, 0.1, 0.3, 0.15);
        }
    }

    private void face(Vec3 next) {
        Vec3 motion = next.subtract(position());
        if (motion.horizontalDistanceSqr() > 1.0E-6) setYRot((float) (Mth.atan2(motion.z, motion.x) * Mth.RAD_TO_DEG) - 90.0F);
        setXRot((float) (-Mth.atan2(motion.y, Math.max(1.0E-4, motion.horizontalDistance())) * Mth.RAD_TO_DEG) * 0.5F);
    }

    private void explode(ServerLevel level, @Nullable ZombieLord lord, Vec3 at) {
        Vec3 ground = ZombieLord.groundBelow(level, at);
        AABB blast = new AABB(ground.x - BLAST_HALF_WIDTH, ground.y - 1.0, ground.z - BLAST_HALF_WIDTH,
                ground.x + BLAST_HALF_WIDTH, ground.y + 2.0, ground.z + BLAST_HALF_WIDTH);
        DamageSource source = damageSources().source(BossEntities.LORD_SKULL_BLAST, this, lord);
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, blast, ZombieLord::isEnemy)) {
            boolean hurt = victim.hurt(source, BLAST_DAMAGE);
            LOGGER.debug("Lord skull {} blast hit {}: {} damage, landed={}", getId(), victim, BLAST_DAMAGE, hurt);
        }
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, ground.x, ground.y + 0.3, ground.z, 30, 0.6, 0.3, 0.6, 0.06);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, ground.x, ground.y + 0.3, ground.z, 10, 0.6, 0.2, 0.6, 0.02);
        level.sendParticles(ZombieLord.blockDust(level, ground), ground.x, ground.y + 0.1, ground.z, 24, 0.8, 0.1, 0.8, 0.15);
        level.playSound(null, ground.x, ground.y, ground.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 0.9F, 1.3F);
        level.playSound(null, ground.x, ground.y, ground.z, SoundEvents.SKELETON_HURT, SoundSource.HOSTILE, 1.0F, 0.6F);
        LordSkeleton raised = LordSkeleton.raise(level, ground, lord);
        if (lord != null) lord.onSkullLanded(this, raised);
        discard();
    }

    @Nullable
    private ZombieLord owner(ServerLevel level) {
        if (owner == null && ownerId != null && level.getEntity(ownerId) instanceof ZombieLord lord) owner = lord;
        return owner;
    }

    @Override
    public boolean shouldBeSaved() {
        // Its spiral and flight belong to the running summon, which is not persisted.
        return false;
    }

    @Override
    public boolean isPickable() { return false; }

    @Override
    public boolean isAttackable() { return false; }

    @Override
    public boolean hurt(DamageSource source, float amount) { return false; }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) { return distance < 96 * 96; }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}
}
