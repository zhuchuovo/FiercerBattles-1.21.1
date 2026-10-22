package com.fiercerbattles.boss;

import com.fiercerbattles.execution.RiposteTarget;
import com.fiercerbattles.mobcombat.api.MobAttackRhythm;
import com.fiercerbattles.mobcombat.logic.MobCombatLogic;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Skeleton raised where a Zombie Lord skull lands: iron sword, iron helmet, chainmail chest, legs and
 * boots, 30 health. It takes 1.5 s to dig out of the ground. Its sword swings come in pairs: after
 * every second swing the next one waits an extra 0.5 s. Melee goes through the embedded mob combat
 * (Better Combat sword animations), so the player's parry stuns it and a riposte execution works.
 */
public class LordSkeleton extends AbstractSkeleton implements RiposteTarget, MobAttackRhythm {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int EMERGE_TICKS = 30;
    static final int PAUSE_AFTER_PAIR_TICKS = 10;
    /** A gap this long between swings starts a new pair. */
    static final int PAIR_RESET_TICKS = 60;

    private static final EntityDataAccessor<Long> DATA_EMERGE_START = SynchedEntityData.defineId(LordSkeleton.class, EntityDataSerializers.LONG);

    // Assigned from registerGoals during the super constructor, so these must not have initializers.
    private LordMeleeGoal melee;
    private int swings;
    private long lastSwing;

    public LordSkeleton(EntityType<? extends LordSkeleton> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return AbstractSkeleton.createAttributes().add(Attributes.MAX_HEALTH, 30.0);
    }

    /** Raises a skeleton at the blast point, digging out of the ground; null when there is no room. */
    @Nullable
    static LordSkeleton raise(ServerLevel level, Vec3 ground, @Nullable ZombieLord lord) {
        EntityType<LordSkeleton> type = BossEntities.LORD_SKELETON.get();
        for (Vec3 offset : new Vec3[]{Vec3.ZERO, new Vec3(0, 1, 0), new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1)}) {
            Vec3 at = ZombieLord.groundBelow(level, ground.add(offset));
            if (!level.noCollision(type.getSpawnAABB(at.x, at.y, at.z))) continue;
            LordSkeleton skeleton = type.create(level);
            if (skeleton == null) return null;
            LivingEntity target = lord != null ? lord.getTarget() : null;
            float yaw = target == null ? level.random.nextFloat() * 360.0F
                    : (float) (Mth.atan2(target.getZ() - at.z, target.getX() - at.x) * Mth.RAD_TO_DEG) - 90.0F;
            skeleton.moveTo(at.x, at.y, at.z, yaw, 0.0F);
            skeleton.setYHeadRot(yaw);
            skeleton.setYBodyRot(yaw);
            skeleton.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(at)), MobSpawnType.MOB_SUMMONED, null);
            skeleton.entityData.set(DATA_EMERGE_START, level.getGameTime());
            if (target != null) skeleton.setTarget(target);
            level.addFreshEntity(skeleton);
            return skeleton;
        }
        return null;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_EMERGE_START, -1L);
    }

    @Override
    protected void registerGoals() {
        melee = new LordMeleeGoal(this);
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(4, melee);
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 1.0));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, ZombieLord.class, LordSkeleton.class));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
    }

    /** The sword goal is permanent; the bow goal of ordinary skeletons is never installed. */
    @Override
    public void reassessWeaponGoal() {}

    @Override
    protected void populateDefaultEquipmentSlots(RandomSource random, DifficultyInstance difficulty) {
        setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.CHAINMAIL_CHESTPLATE));
        setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.CHAINMAIL_LEGGINGS));
        setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.CHAINMAIL_BOOTS));
        for (EquipmentSlot slot : EquipmentSlot.values()) setDropChance(slot, 0.0F);
    }

    @Override
    protected void populateDefaultEquipmentEnchantments(ServerLevelAccessor level, RandomSource random, DifficultyInstance difficulty) {
        // A fixed loadout: no random enchantments.
    }

    // ---------------------------------------------------------------- emerging

    public boolean isEmerging() {
        long start = entityData.get(DATA_EMERGE_START);
        return start >= 0 && level().getGameTime() - start < EMERGE_TICKS;
    }

    /** 0 while still buried, 1 once fully out of the ground. */
    public float emergeProgress(float partialTick) {
        long start = entityData.get(DATA_EMERGE_START);
        if (start < 0) return 1.0F;
        return Mth.clamp((float) (level().getGameTime() - start + partialTick) / EMERGE_TICKS, 0.0F, 1.0F);
    }

    @Override
    public void aiStep() {
        if (!isEmerging()) {
            super.aiStep();
            return;
        }
        // Buried: no AI, no movement; dirt flies off as it climbs out.
        if (level() instanceof ServerLevel level) {
            long age = level.getGameTime() - entityData.get(DATA_EMERGE_START);
            level.sendParticles(ZombieLord.blockDust(level, position()), getX(), getY() + 0.1, getZ(), 4, 0.3, 0.05, 0.3, 0.1);
            if (age % 6 == 0) level.playSound(null, getX(), getY(), getZ(), SoundEvents.ROOTED_DIRT_BREAK, SoundSource.HOSTILE, 1.0F, 0.7F);
            if (age == EMERGE_TICKS - 1) playSound(SoundEvents.SKELETON_AMBIENT, 1.0F, 0.8F);
        }
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return isEmerging() && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || super.isInvulnerableTo(source);
    }

    @Override
    public boolean isPickable() { return !isEmerging() && super.isPickable(); }

    @Override
    public boolean isPushable() { return !isEmerging() && super.isPushable(); }

    @Override
    protected boolean isSunBurnTick() { return false; }

    @Override
    public boolean riposteAtAnyHealth() { return true; }

    // ---------------------------------------------------------------- swing rhythm

    /** Each real swing start (not every goal attempt): every second swing of a pair delays the next by 0.5 s. */
    @Override
    public int bmc$extraCooldownAfterSwing() {
        long now = level().getGameTime();
        if (now - lastSwing > PAIR_RESET_TICKS) swings = 0;
        lastSwing = now;
        swings++;
        int delay = swings % 2 == 0 ? PAUSE_AFTER_PAIR_TICKS : 0;
        LOGGER.debug("Risen skeleton {} swing {} at {}, extra wait before the next {}", getId(), swings, now, delay);
        return delay;
    }

    @Override
    protected SoundEvent getAmbientSound() { return SoundEvents.SKELETON_AMBIENT; }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.SKELETON_HURT; }

    @Override
    protected SoundEvent getDeathSound() { return SoundEvents.SKELETON_DEATH; }

    @Override
    protected SoundEvent getStepSound() { return SoundEvents.SKELETON_STEP; }

    /**
     * Vanilla skeleton melee, but with a sword it tries every tick: the mob combat cooldown (which
     * carries the pair rhythm) is then the only gate, so the 0.5 s pause is not rounded up to a
     * whole extra goal interval.
     */
    static final class LordMeleeGoal extends MeleeAttackGoal {
        private final LordSkeleton skeleton;
        private boolean resetting;

        LordMeleeGoal(LordSkeleton skeleton) {
            super(skeleton, 1.2, false);
            this.skeleton = skeleton;
        }

        @Override
        protected void resetAttackCooldown() {
            resetting = MobCombatLogic.isEligible(skeleton);
            super.resetAttackCooldown();
            resetting = false;
        }

        @Override
        protected int adjustedTickDelay(int ticks) {
            return resetting ? 1 : super.adjustedTickDelay(ticks);
        }

        @Override
        public void start() {
            super.start();
            skeleton.setAggressive(true);
        }

        @Override
        public void stop() {
            super.stop();
            skeleton.setAggressive(false);
        }
    }
}
