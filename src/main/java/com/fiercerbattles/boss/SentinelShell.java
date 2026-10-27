package com.fiercerbattles.boss;

import com.mojang.logging.LogUtils;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * The Iron Sentinel's cannon shells. DIRECT flies straight from the muzzle at the target and bursts on whatever it
 * meets (2 blocks, 4 damage). SKY is the volley into the sky, only seen. MORTAR stays on a marked spot of ground:
 * the client draws the 3 block warning circle while it counts down and the shell dropping onto it at the end, where
 * it bursts (6 damage at the centre, 3 at the rim).
 */
public class SentinelShell extends Entity {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int DIRECT = 0;
    public static final int SKY = 1;
    public static final int MORTAR = 2;
    static final double DIRECT_RADIUS = 2.0;
    static final float DIRECT_DAMAGE = 4.0F;
    static final int DIRECT_LIFE = 60;
    static final int SKY_LIFE = 20;
    public static final double MORTAR_RADIUS = 3.0;
    static final float MORTAR_DAMAGE_NEAR = 6.0F;
    static final float MORTAR_DAMAGE_FAR = 3.0F;
    /** The warning shows this long before the shell lands; the shell is seen falling over the last MORTAR_FALL_TICKS. */
    public static final int MORTAR_WARN_TICKS = 25;
    public static final int MORTAR_FALL_TICKS = 10;
    public static final double MORTAR_DROP_HEIGHT = 20.0;

    private static final EntityDataAccessor<Integer> DATA_MODE = SynchedEntityData.defineId(SentinelShell.class, EntityDataSerializers.INT);

    @Nullable private UUID ownerId;
    @Nullable private IronSentinel owner;
    private Vec3 velocity = Vec3.ZERO;

    public SentinelShell(EntityType<? extends SentinelShell> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    private SentinelShell(Level level, IronSentinel owner, int mode, Vec3 at, Vec3 velocity) {
        this(BossEntities.SENTINEL_SHELL.get(), level);
        this.owner = owner;
        this.ownerId = owner.getUUID();
        this.velocity = velocity;
        entityData.set(DATA_MODE, mode);
        setPos(at.x, at.y, at.z);
        setDeltaMovement(velocity);
        face(velocity);
    }

    static SentinelShell direct(Level level, IronSentinel owner, Vec3 at, Vec3 velocity) {
        return new SentinelShell(level, owner, DIRECT, at, velocity);
    }

    static SentinelShell sky(Level level, IronSentinel owner, Vec3 at, Vec3 velocity) {
        return new SentinelShell(level, owner, SKY, at, velocity);
    }

    static SentinelShell mortar(Level level, IronSentinel owner, Vec3 ground) {
        return new SentinelShell(level, owner, MORTAR, ground, Vec3.ZERO);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_MODE, DIRECT);
    }

    public int mode() { return entityData.get(DATA_MODE); }

    /** MORTAR: height of the falling shell above the marked ground, or a negative value while it is not yet seen. */
    public double dropHeight(float partialTick) {
        double left = MORTAR_WARN_TICKS - (tickCount + partialTick);
        return left > MORTAR_FALL_TICKS ? -1.0 : MORTAR_DROP_HEIGHT * Math.max(0.0, left) / MORTAR_FALL_TICKS;
    }

    @Override
    public void tick() {
        super.tick();
        int mode = mode();
        if (level().isClientSide) {
            if (mode != MORTAR) {
                level().addParticle(ParticleTypes.SMOKE, getX(), getY(), getZ(), 0, 0.01, 0);
                level().addParticle(ParticleTypes.SMALL_FLAME, getX(), getY(), getZ(), 0, 0, 0);
            } else if (dropHeight(0.0F) >= 0) {
                level().addParticle(ParticleTypes.LARGE_SMOKE, getX(), getY() + dropHeight(0.0F) + 0.5, getZ(), 0, 0.05, 0);
            }
            return;
        }
        if (!(level() instanceof ServerLevel level)) return;
        switch (mode) {
            case DIRECT -> tickDirect(level);
            case SKY -> {
                setPos(position().add(velocity));
                if (tickCount >= SKY_LIFE) discard();
            }
            default -> {
                if (tickCount == MORTAR_WARN_TICKS - MORTAR_FALL_TICKS) {
                    level.playSound(null, getX(), getY(), getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.HOSTILE, 1.5F, 0.5F);
                }
                if (tickCount >= MORTAR_WARN_TICKS) burst(level, position(), MORTAR_RADIUS, MORTAR_DAMAGE_NEAR, MORTAR_DAMAGE_FAR,
                        BossEntities.SENTINEL_MORTAR);
            }
        }
    }

    private void tickDirect(ServerLevel level) {
        Vec3 next = position().add(velocity);
        HitResult wall = level.clip(new ClipContext(position(), next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        if (wall.getType() != HitResult.Type.MISS) {
            burst(level, wall.getLocation().subtract(velocity.normalize().scale(0.2)), DIRECT_RADIUS, DIRECT_DAMAGE,
                    DIRECT_DAMAGE, BossEntities.SENTINEL_SHELL_BLAST);
            return;
        }
        AABB swept = getBoundingBox().expandTowards(velocity).inflate(0.2);
        if (!level.getEntitiesOfClass(LivingEntity.class, swept, IronSentinel::isEnemy).isEmpty()) {
            burst(level, next, DIRECT_RADIUS, DIRECT_DAMAGE, DIRECT_DAMAGE, BossEntities.SENTINEL_SHELL_BLAST);
            return;
        }
        setPos(next);
        if (tickCount >= DIRECT_LIFE) burst(level, next, DIRECT_RADIUS, DIRECT_DAMAGE, DIRECT_DAMAGE, BossEntities.SENTINEL_SHELL_BLAST);
    }

    /** Burst: damage falls linearly from {@code near} at the centre to {@code far} at the rim. */
    private void burst(ServerLevel level, Vec3 at, double radius, float near, float far, ResourceKey<DamageType> type) {
        DamageSource source = damageSources().source(type, this, owner(level));
        AABB area = new AABB(at, at).inflate(radius + 1.0);
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, area, IronSentinel::isEnemy)) {
            double distance = Math.sqrt(victim.getBoundingBox().distanceToSqr(at));
            if (distance > radius) continue;
            float damage = Mth.lerp((float) (distance / radius), near, far);
            boolean hurt = victim.hurt(source, damage);
            LOGGER.debug("Sentinel shell {} ({}) hit {} at {} blocks: {} damage, landed={}", getId(), mode(), victim,
                    String.format("%.2f", distance), damage, hurt);
        }
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.3, at.z, radius > 2.5 ? 3 : 1, radius * 0.25, 0.1,
                radius * 0.25, 0.0);
        level.sendParticles(ParticleTypes.FLAME, at.x, at.y + 0.3, at.z, (int) (radius * 10), radius * 0.35, 0.2,
                radius * 0.35, 0.05);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.3, at.z, (int) (radius * 5), radius * 0.3, 0.2,
                radius * 0.3, 0.02);
        level.sendParticles(ZombieLord.blockDust(level, at), at.x, at.y + 0.1, at.z, (int) (radius * 10), radius * 0.4,
                0.1, radius * 0.4, 0.15);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE,
                radius > 2.5 ? 1.6F : 1.0F, radius > 2.5 ? 0.8F : 1.2F);
        discard();
    }

    private void face(Vec3 motion) {
        if (motion.horizontalDistanceSqr() > 1.0E-6) setYRot((float) (Mth.atan2(motion.z, motion.x) * Mth.RAD_TO_DEG) - 90.0F);
        setXRot((float) (-Mth.atan2(motion.y, Math.max(1.0E-4, motion.horizontalDistance())) * Mth.RAD_TO_DEG));
    }

    @Nullable
    private IronSentinel owner(ServerLevel level) {
        if (owner == null && ownerId != null && level.getEntity(ownerId) instanceof IronSentinel sentinel) owner = sentinel;
        return owner;
    }

    @Override
    public boolean shouldBeSaved() {
        // Shells belong to the running cannon, which is not persisted.
        return false;
    }

    @Override
    public boolean isPickable() { return false; }

    @Override
    public boolean isAttackable() { return false; }

    @Override
    public boolean hurt(DamageSource source, float amount) { return false; }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) { return distance < 128 * 128; }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}
}
