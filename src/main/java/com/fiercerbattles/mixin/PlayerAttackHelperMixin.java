package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds per-attack range bonus from datapack custom fields.
 */
@Mixin(targets = "net.bettercombat.logic.PlayerAttackHelper")
public abstract class PlayerAttackHelperMixin {

    @Inject(method = "getStaticRange", at = @At("RETURN"), remap = false, cancellable = true)
    private static void fiercerbattles$addRangeBonus(@Coerce Object player, @Coerce Object stack, CallbackInfoReturnable<Double> cir) {
        double bonus = FiercerBattlesRuntime.getCurrentRangeBonus();
        if (bonus != 0) {
            cir.setReturnValue(cir.getReturnValue() + bonus);
        }
    }

    @Inject(method = "getAttackCooldownTicksCapped", at = @At("RETURN"), remap = false, cancellable = true)
    private static void fiercerbattles$applyAttackSpeed(@Coerce Object player, CallbackInfoReturnable<Float> cir) {
        float multiplier = FiercerBattlesRuntime.getCurrentAttackSpeedMultiplier();
        if (multiplier != 1.0F && multiplier != 0) {
            cir.setReturnValue(cir.getReturnValue() / multiplier);
        }
    }
}
