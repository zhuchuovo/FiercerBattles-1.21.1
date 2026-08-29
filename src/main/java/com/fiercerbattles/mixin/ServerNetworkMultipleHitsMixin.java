package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesMultipleHits;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Consumes the per-frame damage multiplier that the server records while it
 * resolves a multiple-hit attack request.
 *
 * In Better Combat 2.4.0 the damage multiplier of an attack request is read
 * inside the private synthetic lambda {@code lambda$handleAttackRequest$3}
 * (the actual server-thread attack task), not inside
 * {@code handleAttackRequest} itself, so the redirect must target the lambda
 * method name.
 *
 * This mixin is registered as a common mixin (not a server-only one) because
 * the integrated server of a singleplayer game runs in the client
 * environment; a server-only mixin would never apply there and the per-frame
 * multipliers would silently do nothing.
 */
@Mixin(targets = "net.bettercombat.network.ServerNetwork")
public abstract class ServerNetworkMultipleHitsMixin {
    @Redirect(
            method = "lambda$handleAttackRequest$3",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/bettercombat/api/WeaponAttributes$Attack;damageMultiplier()D"
            ),
            remap = false
    )
    private static double fiercerbattles$applyMultipleHitDamage(@Coerce Object attack) {
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
