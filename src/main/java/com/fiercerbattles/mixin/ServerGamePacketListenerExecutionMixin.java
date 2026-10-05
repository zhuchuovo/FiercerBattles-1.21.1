package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesExecutionServer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A movement packet must never replace an execution's shared world-space anchors. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerExecutionMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void fiercerbattles$holdExecutionAnchor(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (FiercerBattlesExecutionServer.isExecuting(player)) {
            ci.cancel();
        }
    }
}
