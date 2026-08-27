package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesCombatSkills;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the active combat-skill range to Better Combat's real target-range
 * lookup. This must be a common mixin: the server performs its own distance
 * validation after receiving the attack packet.
 */
@Mixin(targets = "net.bettercombat.logic.PlayerAttackHelper")
public abstract class PlayerAttackRangeMixin {
    @Inject(method = "getRangeForItem", at = @At("RETURN"), remap = false, cancellable = true)
    private static void fiercerbattles$applySkillRange(
            @Coerce Object player,
            @Coerce Object stack,
            CallbackInfoReturnable<Double> cir
    ) {
        double original = cir.getReturnValue();
        double range = FiercerBattlesCombatSkills.applyActiveSkillRange(player, stack, original);
        if (range != original) {
            cir.setReturnValue(range);
        }
    }
}
