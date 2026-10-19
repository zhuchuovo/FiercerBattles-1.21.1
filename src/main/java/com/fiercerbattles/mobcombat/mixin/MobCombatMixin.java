package com.fiercerbattles.mobcombat.mixin;

import com.fiercerbattles.mobcombat.api.MobCombatState;
import com.fiercerbattles.mobcombat.logic.MobCombatLogic;
import com.fiercerbattles.bettercombat.api.AttackHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mob.class)
public abstract class MobCombatMixin extends LivingEntity implements MobCombatState {
    @Unique private int bmc$comboCount;
    @Unique private int bmc$attackCooldown;
    @Unique private int bmc$attackStartDelayTicks;
    @Unique private int bmc$windupTicks;
    @Unique private int bmc$comboResetTicks;
    @Unique private int bmc$intendedTargetId = -1;
    @Unique private boolean bmc$callingVanillaAttack;
    @Unique @Nullable private AttackHand bmc$pendingAttack;
    @Unique @Nullable private ItemStack bmc$weaponOverride;

    protected MobCombatMixin(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void bmc$tickCombatState(CallbackInfo ci) {
        if (!this.level().isClientSide) {
            this.bmc$tickBetterCombatAttack();
        }
    }

    @Inject(method = "doHurtTarget", at = @At("HEAD"), cancellable = true)
    private void bmc$replaceVanillaMeleeAttack(Entity target, CallbackInfoReturnable<Boolean> cir) {
        if (this.bmc$callingVanillaAttack) {
            return;
        }
        Mob mob = (Mob) (Object) this;
        if (MobCombatLogic.isEligible(mob)) {
            // Better Combat weapons are fully server-authoritative here. Even if a stale AI call
            // arrives after the target stepped out of range, never let vanilla damage bypass the
            // windup/range checks.
            MobCombatLogic.beginAttack(mob, target);
            cir.setReturnValue(false);
            return;
        }
        if (MobCombatLogic.beginDelayedFallbackMeleeAttack(mob, target)) {
            cir.setReturnValue(false);
            return;
        }
        MobCombatLogic.playFallbackMeleeAnimation(mob);
    }


    @Inject(method = "isWithinMeleeAttackRange", at = @At("RETURN"), cancellable = true)
    private void bmc$extendMeleeRange(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        Mob mob = (Mob) (Object) this;
        if (MobCombatLogic.isEligible(mob)) {
            // Replace vanilla's reach decision instead of only extending it. This lets short-range
            // attacks stay short and makes long weapons use the stricter Better Combat start range.
            cir.setReturnValue(MobCombatLogic.isWithinWeaponRange(mob, target));
        } else if (MobCombatLogic.isFallbackMeleeCandidate(mob)) {
            cir.setReturnValue(MobCombatLogic.isWithinFallbackRange(mob, target));
        }
    }

    @Override
    public int bmc$getComboCount() {
        return this.bmc$comboCount;
    }

    @Override
    public void bmc$setComboCount(int comboCount) {
        this.bmc$comboCount = Math.max(0, comboCount);
    }

    @Override
    public int bmc$getAttackCooldown() {
        return this.bmc$attackCooldown;
    }

    @Override
    public void bmc$setAttackCooldown(int ticks) {
        this.bmc$attackCooldown = Math.max(0, ticks);
    }


    @Override
    public int bmc$getAttackStartDelayTicks() {
        return this.bmc$attackStartDelayTicks;
    }

    @Override
    public void bmc$setAttackStartDelayTicks(int ticks) {
        this.bmc$attackStartDelayTicks = Math.max(0, ticks);
    }

    @Override
    public int bmc$getWindupTicks() {
        return this.bmc$windupTicks;
    }

    @Override
    public void bmc$setWindupTicks(int ticks) {
        this.bmc$windupTicks = Math.max(0, ticks);
    }

    @Override
    public int bmc$getComboResetTicks() {
        return this.bmc$comboResetTicks;
    }

    @Override
    public void bmc$setComboResetTicks(int ticks) {
        this.bmc$comboResetTicks = Math.max(0, ticks);
    }

    @Override
    public @Nullable AttackHand bmc$getPendingAttack() {
        return this.bmc$pendingAttack;
    }

    @Override
    public void bmc$setPendingAttack(@Nullable AttackHand attack) {
        this.bmc$pendingAttack = attack;
    }

    @Override
    public int bmc$getIntendedTargetId() {
        return this.bmc$intendedTargetId;
    }

    @Override
    public void bmc$setIntendedTargetId(int entityId) {
        this.bmc$intendedTargetId = entityId;
    }

    @Override
    public boolean bmc$isCallingVanillaAttack() {
        return this.bmc$callingVanillaAttack;
    }

    @Override
    public void bmc$setCallingVanillaAttack(boolean callingVanilla) {
        this.bmc$callingVanillaAttack = callingVanilla;
    }


    @Override
    public @Nullable ItemStack bmc$getWeaponOverride() {
        return this.bmc$weaponOverride;
    }

    @Override
    public void bmc$setWeaponOverride(@Nullable ItemStack stack) {
        this.bmc$weaponOverride = stack;
    }

    @Override
    public void bmc$beginBetterCombatAttack(Entity intendedTarget) {
        MobCombatLogic.beginAttack((Mob) (Object) this, intendedTarget);
    }

    @Override
    public void bmc$tickBetterCombatAttack() {
        MobCombatLogic.tick((Mob) (Object) this);
    }
}
