package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesMultipleHits;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Consumes the per-request hit state that the server records while it
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
@Mixin(targets = "com.fiercerbattles.bettercombat.network.ServerNetwork")
public abstract class ServerNetworkMultipleHitsMixin {
    @Redirect(
            method = "lambda$handleAttackRequest$3",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/fiercerbattles/bettercombat/api/WeaponAttributes$Attack;damageMultiplier()D"
            ),
            remap = false
    )
    private static double fiercerbattles$applyMultipleHitDamage(@Coerce Object attack) {
        return FiercerBattlesMultipleHits.getServerDamageMultiplier(attackDamageMultiplier(attack));
    }

    /**
     * Rebuilds the per-request hit state on the server thread. The packet
     * handler (and with it the {@code getCurrentAttack} resolution that
     * normally records this state) can run on the network thread, while this
     * attack task runs on the server thread and reads the damage multiplier
     * before {@code Player.attack} re-resolves {@code getCurrentAttack} there.
     * Without this rebuild the multiplier, fixed damage and knockback read
     * here would be whatever the previous request left on this thread - one
     * multiple-hit frame off, or a stale frame multiplier silently applied to
     * every later normal attack.
     */
    @Inject(method = "lambda$handleAttackRequest$3", at = @At("HEAD"), remap = false)
    private static void fiercerbattles$beginServerAttackTaskState(
            @Coerce Object player,
            @Coerce Object attributes,
            @Coerce Object attack,
            @Coerce Object attackHand,
            @Coerce Object level,
            @Coerce Object request,
            boolean useVanillaPacket,
            @Coerce Object listener,
            CallbackInfo ci
    ) {
        if (!(player instanceof ServerPlayer)) {
            return;
        }
        FiercerBattlesMultipleHits.beginServerAttackTask(player, attackHand, requestComboCount(request));
    }

    /**
     * The attack task is the authoritative consumer of the per-request hit
     * state, so it also owns its cleanup. Clearing only at
     * {@code handleAttackRequest}'s RETURN is wrong in two ways: off the
     * server thread it fires before this task has even run, and it never
     * clears the server thread's copy - which would leak the last frame's
     * multiplier, fixed damage and knockback into the player's next,
     * unrelated damage events.
     */
    @Inject(method = "lambda$handleAttackRequest$3", at = @At("RETURN"), remap = false)
    private static void fiercerbattles$endServerAttackTaskState(CallbackInfo ci) {
        FiercerBattlesMultipleHits.clearServerHit();
    }

    @Inject(method = "handleAttackRequest", at = @At("RETURN"), remap = false)
    private static void fiercerbattles$clearMultipleHitDamage(CallbackInfo ci) {
        FiercerBattlesMultipleHits.clearServerHit();
    }

    private static int requestComboCount(Object request) {
        try {
            Object value = request.getClass().getMethod("comboCount").invoke(request);
            return value instanceof Number number ? number.intValue() : -1;
        } catch (ReflectiveOperationException ignored) {
            return -1;
        }
    }

    private static double attackDamageMultiplier(Object attack) {
        try {
            return (double) attack.getClass().getMethod("damageMultiplier").invoke(attack);
        } catch (ReflectiveOperationException ignored) {
            return 1.0;
        }
    }
}
