package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesSkillEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks the server-side processing window of one Better Combat attack
 * request. A combat-skill request temporarily raises the vanilla
 * attack-strength ticker so the skill hit resolves at full strength; the
 * RETURN hook restores the cooldown state once the request is fully
 * processed, mirroring the client's post-attack reset.
 *
 * This mixin is common so the integrated server in singleplayer is covered
 * as well.
 */
@Mixin(targets = "com.fiercerbattles.bettercombat.network.ServerNetwork")
public abstract class ServerNetworkSkillMixin {

    @Inject(method = "handleAttackRequest", at = @At("HEAD"), remap = false)
    private static void fiercerbattles$beginSkillRequest(CallbackInfo ci) {
        FiercerBattlesSkillEffects.beginServerAttackRequest();
    }

    @Inject(method = "handleAttackRequest", at = @At("RETURN"), remap = false)
    private static void fiercerbattles$finishSkillRequest(CallbackInfo ci) {
        FiercerBattlesSkillEffects.finishServerAttackRequest();
    }
}
