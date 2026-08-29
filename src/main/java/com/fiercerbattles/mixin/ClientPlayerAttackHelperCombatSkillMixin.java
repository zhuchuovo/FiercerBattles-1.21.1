package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesCombatSkills;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.bettercombat.logic.PlayerAttackHelper")
public abstract class ClientPlayerAttackHelperCombatSkillMixin {
    @Inject(method = "getCurrentAttack", at = @At("RETURN"), cancellable = true, remap = false)
    private static void fiercerbattles$selectClientCombatSkill(
            @Coerce Object player,
            int comboCount,
            CallbackInfoReturnable<Object> cir
    ) {
        if (player instanceof ServerPlayer) {
            // Logical-server requests are handled by the common combat-skill
            // mixin, which registers the server-side range override. This
            // client hook must never clear that state.
            return;
        }
        Object hand = cir.getReturnValue();
        if (hand == null) {
            return;
        }
        Object skillHand = FiercerBattlesCombatSkills.replaceArmedClientHand(hand, player);
        if (skillHand != hand) {
            // Better Combat resolves the target range from the item stack, so
            // the skill's own attack_range must be published here for
            // getRangeForItem to pick it up while the skill swing is live.
            FiercerBattlesCombatSkills.setActiveSkillRange(
                    player,
                    skillHand,
                    FiercerBattlesCombatSkills.getActiveSkillCustomData()
            );
            cir.setReturnValue(skillHand);
        } else {
            FiercerBattlesCombatSkills.clearActiveSkillRange(player);
        }
    }
}
