package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesMultipleHits;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.bettercombat.network.ServerNetwork")
public abstract class ServerNetworkMultipleHitsMixin {
    @Redirect(
            method = "handleAttackRequest",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/bettercombat/api/WeaponAttributes$Attack;damageMultiplier()D"
            ),
            remap = false
    )
    private static double fiercerbattles$applyMultipleHitDamage(Object attack) {
        return FiercerBattlesMultipleHits.getServerDamageMultiplier(attackDamageMultiplier(attack));
    }

    @Inject(method = "handleAttackRequest", at = @At("RETURN"), remap = false)
    private static void fiercerbattles$clearMultipleHitDamage(CallbackInfo ci) {
        FiercerBattlesMultipleHits.clearServerHit();
    }

    private static double attackDamageMultiplier(Object attack) {
        try {
            return (double) attack.getClass().getMethod("damageMultiplier").invoke(attack);
        } catch (ReflectiveOperationException ignored) {
            return 1.0;
        }
    }
}
