package com.fiercerbattles.mobcombat.api;

import com.fiercerbattles.bettercombat.api.AttackHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public interface MobCombatState {
    int bmc$getComboCount();

    void bmc$setComboCount(int comboCount);

    int bmc$getAttackCooldown();

    void bmc$setAttackCooldown(int ticks);

    int bmc$getAttackStartDelayTicks();

    void bmc$setAttackStartDelayTicks(int ticks);

    int bmc$getWindupTicks();

    void bmc$setWindupTicks(int ticks);

    int bmc$getComboResetTicks();

    void bmc$setComboResetTicks(int ticks);

    @Nullable
    AttackHand bmc$getPendingAttack();

    void bmc$setPendingAttack(@Nullable AttackHand attack);

    int bmc$getIntendedTargetId();

    void bmc$setIntendedTargetId(int entityId);

    boolean bmc$isCallingVanillaAttack();

    void bmc$setCallingVanillaAttack(boolean callingVanilla);

    @Nullable
    ItemStack bmc$getWeaponOverride();

    void bmc$setWeaponOverride(@Nullable ItemStack stack);

    void bmc$beginBetterCombatAttack(Entity intendedTarget);

    void bmc$tickBetterCombatAttack();
}
