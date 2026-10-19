package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesExecutionServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Guards both packet reception and already-queued Better Combat attack tasks. */
@Mixin(targets = "com.fiercerbattles.bettercombat.network.ServerNetwork", priority = 2000)
public abstract class ExecutionServerNetworkMixin {
    @Inject(method = "handleAttackRequest", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fiercerbattles$blockExecutionAttackRequest(
            @Coerce Object request, MinecraftServer server, ServerPlayer player,
            ServerGamePacketListenerImpl listener, CallbackInfo ci
    ) {
        if (FiercerBattlesExecutionServer.isExecuting(player)) {
            ci.cancel();
        }
    }

    @Inject(method = "lambda$handleAttackRequest$3", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fiercerbattles$blockQueuedExecutionAttack(
            @Coerce Object player, @Coerce Object attributes, @Coerce Object attack,
            @Coerce Object hand, @Coerce Object level, @Coerce Object request,
            boolean vanillaPacket, @Coerce Object listener, CallbackInfo ci
    ) {
        if (player instanceof ServerPlayer serverPlayer && FiercerBattlesExecutionServer.isExecuting(serverPlayer)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleAttackAnimation", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fiercerbattles$blockExecutionAttackAnimation(
            @Coerce Object animation, MinecraftServer server, ServerPlayer player, CallbackInfo ci
    ) {
        if (FiercerBattlesExecutionServer.isExecuting(player)) {
            ci.cancel();
        }
    }

    @Inject(method = "lambda$handleAttackAnimation$1", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fiercerbattles$blockQueuedExecutionAnimation(
            @Coerce Object animation, ServerPlayer player, CallbackInfo ci
    ) {
        if (FiercerBattlesExecutionServer.isExecuting(player)) {
            ci.cancel();
        }
    }
}
