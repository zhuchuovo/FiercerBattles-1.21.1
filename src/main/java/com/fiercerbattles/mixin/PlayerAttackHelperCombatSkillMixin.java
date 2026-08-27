package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesCombatSkills;
import com.fiercerbattles.FiercerBattlesCustomData;
import com.fiercerbattles.FiercerBattlesMultipleHits;
import com.fiercerbattles.FiercerBattlesSkillEffects;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.bettercombat.logic.PlayerAttackHelper")
public abstract class PlayerAttackHelperCombatSkillMixin {
    private static final ThreadLocal<Integer> FIERCERBATTLES_ATTACK_REQUEST = new ThreadLocal<>();

    @ModifyVariable(method = "getCurrentAttack", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private static int fiercerbattles$decodeCombatSkillCombo(int comboCount) {
        FIERCERBATTLES_ATTACK_REQUEST.set(comboCount);
        return FiercerBattlesCombatSkills.decodeCombatSkillCombo(comboCount);
    }

    @Inject(method = "getCurrentAttack", at = @At("RETURN"), cancellable = true, remap = false)
    private static void fiercerbattles$selectCombatSkill(@Coerce Object player, int comboCount, CallbackInfoReturnable<Object> cir) {
        try {
            Integer encodedComboCount = FIERCERBATTLES_ATTACK_REQUEST.get();
            // This mixin is common so logical servers in singleplayer are also
            // covered. Client-side skill selection is handled by the dedicated
            // client mixin; never clear its active range here.
            if (!(player instanceof ServerPlayer)
                    || encodedComboCount == null
                    || cir.getReturnValue() == null) {
                return;
            }
            FiercerBattlesCombatSkills.clearActiveSkillRange(player);
            Object hand = cir.getReturnValue();
            FiercerBattlesCustomData.AttackData customData;
            if (FiercerBattlesCombatSkills.isCombatSkillCombo(encodedComboCount)) {
                customData = FiercerBattlesCombatSkills.getCombatSkillDataForHand(hand, player);
                if (customData == null) {
                    cir.setReturnValue(null);
                    return;
                }
                if (player instanceof ServerPlayer serverPlayer
                        && !FiercerBattlesSkillEffects.tryActivate(serverPlayer, customData)) {
                    cir.setReturnValue(null);
                    return;
                }
                Object normalHand = hand;
                hand = FiercerBattlesCombatSkills.replaceEncodedSkillHand(hand, 1_000_000, player);
                if (hand != normalHand) {
                    FiercerBattlesCombatSkills.setActiveSkillRange(player, hand, customData);
                }
                cir.setReturnValue(hand);
            } else {
                customData = FiercerBattlesCustomData.get(
                        FiercerBattlesCombatSkills.itemIdForHand(hand),
                        FiercerBattlesCombatSkills.attackIndex(hand)
                );
            }
            if (FiercerBattlesCombatSkills.isMultipleHitCombo(encodedComboCount)) {
                FiercerBattlesCustomData.MultipleHits multipleHits = FiercerBattlesCustomData.getMultipleHits(
                        customData,
                        animationName(hand)
                );
                int multipleHitIndex = FiercerBattlesCombatSkills.getMultipleHitIndex(encodedComboCount);
                if (multipleHits != null && multipleHits.isValidIndex(multipleHitIndex)) {
                    // A valid zero is intentional (a hit may deal no damage),
                    // so only install the override when the packet maps to a
                    // real configured hit.  For an unknown/stale packet keep
                    // Better Combat's attack multiplier instead of turning
                    // the damage modifier into -100%.
                    FiercerBattlesMultipleHits.beginServerHit(
                            multipleHits.damageMultipliers[multipleHitIndex]
                    );
                } else {
                    FiercerBattlesMultipleHits.clearServerHit();
                }
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
}
