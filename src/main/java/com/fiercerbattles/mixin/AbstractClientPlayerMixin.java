package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesRuntime;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the current BetterCombat attack animation name before the animation
 * player is set up, so TransmissionSpeedModifierMixin can slow it down.
 *
 * playAttackAnimation is merged into AbstractClientPlayer by BetterCombat's
 * own mixin (priority 1000); injecting into it requires a higher priority.
 */
@Mixin(targets = "net.minecraft.client.player.AbstractClientPlayer", priority = 2000)
public abstract class AbstractClientPlayerMixin {

    @Inject(method = "playAttackAnimation", at = @At("HEAD"), remap = false)
    private void fiercerbattles$captureAnimation(String name, @Coerce Object hand, float length, float upswing, CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer) {
            int startTick = ((Entity) (Object) this).tickCount;
            FiercerBattlesRuntime.setCurrentAnimation(name, startTick);
        }
    }
}
