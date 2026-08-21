package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesCombatSkills;
import com.fiercerbattles.FiercerBattlesCustomData;
import com.fiercerbattles.FiercerBattlesMultipleHits;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.bettercombat.logic.PlayerAttackHelper")
public abstract class PlayerAttackHelperCombatSkillMixin {
    private static final ThreadLocal<AttackRequest> FIERCERBATTLES_ATTACK_REQUEST = new ThreadLocal<>();

    @ModifyVariable(method = "getCurrentAttack", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private static int fiercerbattles$decodeCombatSkillCombo(int comboCount) {
        boolean multipleHits = FiercerBattlesCombatSkills.isMultipleHitCombo(comboCount);
        FIERCERBATTLES_ATTACK_REQUEST.set(new AttackRequest(
                FiercerBattlesCombatSkills.isCombatSkillCombo(comboCount),
                multipleHits,
                FiercerBattlesCombatSkills.getMultipleHitIndex(comboCount)
        ));
        return FiercerBattlesCombatSkills.decodeCombatSkillCombo(comboCount);
    }

    @Inject(method = "getCurrentAttack", at = @At("RETURN"), cancellable = true, remap = false)
    private static void fiercerbattles$selectCombatSkill(@Coerce Object player, int comboCount, CallbackInfoReturnable<Object> cir) {
        try {
            AttackRequest request = FIERCERBATTLES_ATTACK_REQUEST.get();
            if (request == null || cir.getReturnValue() == null) {
                return;
            }
            Object hand = cir.getReturnValue();
            FiercerBattlesCustomData.AttackData customData;
            if (request.combatSkill) {
                customData = FiercerBattlesCombatSkills.getCombatSkillDataForHand(hand);
                hand = FiercerBattlesCombatSkills.replaceEncodedSkillHand(hand, 1_000_000);
                cir.setReturnValue(hand);
            } else {
                customData = FiercerBattlesCustomData.get(
                        FiercerBattlesCombatSkills.itemIdForHand(hand),
                        FiercerBattlesCombatSkills.attackIndex(hand)
                );
            }
            if (request.multipleHits) {
                FiercerBattlesCustomData.MultipleHits multipleHits = FiercerBattlesCustomData.getMultipleHits(
                        customData,
                        animationName(hand)
                );
                double multiplier = 0;
                if (multipleHits != null && multipleHits.isValidIndex(request.multipleHitIndex)) {
                    multiplier = multipleHits.damageMultipliers[request.multipleHitIndex];
                }
                FiercerBattlesMultipleHits.beginServerHit(multiplier);
            }
        } finally {
            FIERCERBATTLES_ATTACK_REQUEST.remove();
        }
    }

    private static String animationName(Object hand) {
        try {
            Object attack = hand.getClass().getMethod("attack").invoke(hand);
            Object animation = attack != null ? attack.getClass().getMethod("animation").invoke(attack) : null;
            return animation instanceof String value ? value : "";
        } catch (ReflectiveOperationException ignored) {
            return "";
        }
    }
    private record AttackRequest(boolean combatSkill, boolean multipleHits, int multipleHitIndex) {
    }
}
