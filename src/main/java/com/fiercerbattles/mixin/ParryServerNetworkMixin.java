package com.fiercerbattles.mixin;

import com.fiercerbattles.parry.ParryServer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents interrupted Better Combat attacks from surviving in the server queue. */
@Mixin(targets = "com.fiercerbattles.bettercombat.network.ServerNetwork", priority = 2100)
public abstract class ParryServerNetworkMixin {
    @Inject(method = "handleAttackRequest", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fiercerbattles$blockParryAttackRequest(
            @Coerce Object request, MinecraftServer server, ServerPlayer player,
            ServerGamePacketListenerImpl listener, CallbackInfo ci) {
        if (ParryServer.blocksActions(player)) ci.cancel();
    }

    @WrapOperation(method = "handleAttackRequest", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;executeIfPossible(Ljava/lang/Runnable;)V"),
            remap = false)
    private static void fiercerbattles$fenceQueuedParryAttack(
            MinecraftServer server, Runnable attack, Operation<Void> original,
            @Local(argsOnly = true) ServerPlayer player) {
        long epoch = ParryServer.attackEpoch(player.getUUID());
        original.call(server, (Runnable) () -> {
            if (epoch == ParryServer.attackEpoch(player.getUUID()) && !ParryServer.blocksActions(player)) {
                attack.run();
            }
        });
    }

    // The outer task sets combo state and swaps attributes before invoking the hit loop.
    // Reject here so that the inner loop's cleanup is never skipped halfway through an attack.
    @Inject(method = "lambda$handleAttackRequest$4", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fiercerbattles$blockQueuedParryAttack(
            ServerPlayer player, @Coerce Object request, @Coerce Object hand,
            @Coerce Object attributes, @Coerce Object attack, @Coerce Object level,
            boolean vanillaPacket, @Coerce Object listener, CallbackInfo ci) {
        if (ParryServer.blocksActions(player)) ci.cancel();
        else ParryServer.damageChecked(player);
    }

    @Inject(method = "handleAttackAnimation", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fiercerbattles$blockParryAttackAnimation(
            @Coerce Object animation, MinecraftServer server, ServerPlayer player, CallbackInfo ci) {
        if (ParryServer.blocksActions(player)) ci.cancel();
    }

    @Inject(method = "lambda$handleAttackAnimation$1", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fiercerbattles$blockQueuedParryAnimation(
            @Coerce Object animation, ServerPlayer player, CallbackInfo ci) {
        if (ParryServer.blocksActions(player)) ci.cancel();
    }
}
