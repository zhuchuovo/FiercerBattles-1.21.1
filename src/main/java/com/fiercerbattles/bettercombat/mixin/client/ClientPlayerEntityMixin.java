package com.fiercerbattles.bettercombat.mixin.client;

import com.fiercerbattles.bettercombat.BetterCombatMod;
import com.fiercerbattles.bettercombat.api.MinecraftClient_BetterCombat;
import com.fiercerbattles.bettercombat.api.WeaponAttributes.Attack;
import com.fiercerbattles.bettercombat.config.ServerConfig;
import com.fiercerbattles.bettercombat.utils.MathHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public class ClientPlayerEntityMixin {
    @Inject(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/Input;tick(ZF)V", shift = At.Shift.AFTER))
    private void tickMovement_ModifyInput(CallbackInfo ci) {
        var config = BetterCombatMod.config;
        var client = (MinecraftClient_BetterCombat) Minecraft.getInstance();
        var multiplier = Math.min(Math.max(config.movement_speed_while_attacking, 0.0), 1.0);
        var attack = client.getCurrentAttack();
        if (attack != null) {
            multiplier *= attack.movementSpeedMultiplier();
        }
//        System.out.println("Multiplier " + multiplier);
        if (multiplier == 1) {
            return;
        }
        var clientPlayer = (LocalPlayer)((Object)this);
        if (clientPlayer.isPassenger() && !config.movement_speed_effected_while_mounting) {
            return;
        }

        var swingProgress = client.getSwingProgress();
        if (swingProgress < 0.98) {
            // Fiercer Battles: an attack that sets movement_speed_multiplier to 0 holds the player still for the
            // whole swing instead of easing the slowdown in and out.
            var standStill = attack != null && attack.movementSpeedMultiplier() == 0;
            if (config.movement_speed_applied_smoothly && !standStill) {
                double p2 = 0;
                if (swingProgress <= 0.5) {
                    p2 = MathHelper.easeOutCubic(swingProgress * 2);
                } else {
                    p2 = MathHelper.easeOutCubic(1 - ((swingProgress - 0.5) * 2));
                }
                multiplier = (float) ( 1.0 - (1.0 - multiplier) * p2 );
//                var chart = "-".repeat((int)(100.0 * multiplier)) + "x";
//                System.out.println("Movement speed multiplier: " + String.format("%.4f", multiplier) + ">" + chart);
            }
            clientPlayer.input.forwardImpulse *= multiplier;
            clientPlayer.input.leftImpulse *= multiplier;
        }
    }
}
