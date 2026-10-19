package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesCombatSkills;
import com.fiercerbattles.FiercerBattlesCustomData;
import com.fiercerbattles.FiercerBattlesMultipleHits;
import com.fiercerbattles.FiercerBattlesSkillEffects;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.fiercerbattles.bettercombat.logic.PlayerAttackHelper")
public abstract class PlayerAttackHelperCombatSkillMixin {
    private static final Logger LOGGER = LogUtils.getLogger();
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
            if (!(player instanceof ServerPlayer) || encodedComboCount == null) {
                return;
            }
            // The hand can be null when the main hand holds no weapon at all.
            // An off-hand weapon skill is still released from the off-hand
            // slot, so the skill hand is rebuilt from that weapon below.
            FiercerBattlesCombatSkills.clearActiveSkillRange(player);
            Object hand = cir.getReturnValue();
            FiercerBattlesCustomData.AttackData customData;
            if (FiercerBattlesCombatSkills.isCombatSkillCombo(encodedComboCount)) {
                customData = FiercerBattlesCombatSkills.getCombatSkillDataForRequest(
                        hand,
                        encodedComboCount,
                        player
                );
                if (customData != null && player instanceof ServerPlayer serverPlayer) {
                    // Multiple-hit skills send one attack request per hit
                    // frame, and multi-stage skills send one request per
                    // chained combo swing. Only the first request of the
                    // sequence may activate the skill (cooldown, effects);
                    // the continuation requests must not re-run tryActivate
                    // or the cooldown started by the first request rejects
                    // every later hit.
                    boolean continuation = FiercerBattlesCombatSkills.isChainSkillCombo(encodedComboCount)
                            || (FiercerBattlesCombatSkills.isMultipleHitCombo(encodedComboCount)
                                    && FiercerBattlesCombatSkills.getMultipleHitIndex(encodedComboCount) > 0);
                    boolean accepted = continuation
                            ? FiercerBattlesSkillEffects.isSameSkillActivation(serverPlayer, customData)
                            : FiercerBattlesSkillEffects.tryActivate(serverPlayer, customData);
                    // The skill hand always resolves the skill's own attack
                    // data (its damage multiplier, fixed damage, knockback).
                    // Accepting only gates the activation effects/cooldown;
                    // rejecting must not silently swap the swing to a normal
                    // attack, whose per-segment multiplier would replace the
                    // skill's damage. An off-hand skill request rebuilds the
                    // hand from the off-hand weapon instead of the hand Better
                    // Combat derived from the combo count.
                    Object skillHand = FiercerBattlesCombatSkills.replaceEncodedSkillHand(
                            hand,
                            encodedComboCount,
                            player
                    );
                    if (skillHand != null && skillHand != hand) {
                        hand = skillHand;
                        cir.setReturnValue(hand);
                        FiercerBattlesCombatSkills.setActiveSkillRange(player, hand, customData);
                    }
                    if (accepted) {
                        if (continuation) {
                            // Continuation hits resolve at full attack strength
                            // like the activation hit. The previous hit's
                            // Player.attack() reset the vanilla attack-strength
                            // ticker; without this boost the later hits would be
                            // charged only partially and deal reduced damage.
                            FiercerBattlesSkillEffects.beginServerSkillAttack(serverPlayer, customData);
                        }
                    } else {
                        // The skill cannot be accepted right now (its cooldown
                        // is still running, the continuation window expired, or
                        // the client is out of sync). The swing still resolves
                        // with the skill's own attack data; only the activation
                        // effects (and their cooldown) are skipped. Boost the
                        // attack strength so the hit lands at full power.
                        FiercerBattlesSkillEffects.boostServerAttackStrength(serverPlayer);
                    }
                } else {
                    // Skill packet for a weapon without a loaded skill
                    // definition (client/server datapack mismatch): resolve it
                    // as a plain normal attack instead of dropping the request.
                    // An off-hand skill request resolves the off-hand weapon.
                    customData = FiercerBattlesCustomData.get(
                            FiercerBattlesCombatSkills.itemIdForRequest(hand, encodedComboCount, player),
                            FiercerBattlesCombatSkills.attackIndexForRequest(hand, encodedComboCount, player)
                    );
                }
            } else {
                customData = FiercerBattlesCustomData.get(
                        FiercerBattlesCombatSkills.itemIdForHand(hand),
                        FiercerBattlesCombatSkills.attackIndex(hand)
                );
            }
            // Install the per-frame multiple-hit multiplier (when the packet
            // carries one) plus the segment's own multiplier, flat damage and
            // extra knockback for the damage/knockback events that fire while
            // this request resolves.
            FiercerBattlesMultipleHits.beginServerAttackTask(customData, hand, encodedComboCount);
        } catch (RuntimeException exception) {
            // A skill request must never take the server (or the integrated
            // server's packet handler) down: an unexpected failure in the
            // skill resolution degrades to the normal attack instead of
            // crashing the game.
            FiercerBattlesMultipleHits.clearServerHit();
            LOGGER.warn("Fiercer Battles failed to resolve an attack request; falling back to the normal attack", exception);
        } finally {
            FIERCERBATTLES_ATTACK_REQUEST.remove();
        }
    }

}
