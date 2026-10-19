package com.fiercerbattles.mobcombat.mixin;

import com.fiercerbattles.mobcombat.logic.MobCombatLogic;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = MeleeAttackGoal.class, priority = 2000)
public abstract class MeleeAttackGoalMixin extends Goal {
    @Shadow @Final protected PathfinderMob mob;

    @ModifyArg(
            method = "resetAttackCooldown",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/goal/MeleeAttackGoal;adjustedTickDelay(I)I"
            ),
            index = 0
    )
    private int bmc$useWeaponAttackInterval(int vanillaInterval) {
        return MobCombatLogic.isEligible(this.mob)
                ? MobCombatLogic.attackIntervalForCurrentWeapon(this.mob)
                : vanillaInterval;
    }

    @Redirect(
            method = "checkAndPerformAttack",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/PathfinderMob;doHurtTarget(Lnet/minecraft/world/entity/Entity;)Z"
            )
    )
    private boolean bmc$delayEntireDynamicMeleeAttack(PathfinderMob mob, Entity target) {
        if (MobCombatLogic.interceptGoalMeleeAttack(mob, target)) {
            return false;
        }
        return mob.doHurtTarget(target);
    }
}
