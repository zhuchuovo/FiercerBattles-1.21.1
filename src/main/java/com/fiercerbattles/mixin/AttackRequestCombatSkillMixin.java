package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesCombatSkills;
import com.fiercerbattles.FiercerBattlesRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(targets = "net.bettercombat.network.Packets$C2S_AttackRequest")
public abstract class AttackRequestCombatSkillMixin {
    @ModifyVariable(method = "<init>(IZII[I)V", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private static int fiercerbattles$markCombatSkillPacket(int comboCount) {
        return FiercerBattlesCombatSkills.encodeAttackPacket(
                comboCount,
                FiercerBattlesRuntime.getExecutingMultipleHitIndex(),
                FiercerBattlesRuntime.isExecutingFinalMultipleHit()
        );
    }
}
