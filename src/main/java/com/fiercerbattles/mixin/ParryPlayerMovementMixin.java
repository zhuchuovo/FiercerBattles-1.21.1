package com.fiercerbattles.mixin;

import com.fiercerbattles.parry.ParryServer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A stunned player's movement packet must not replace the server's locked position. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ParryPlayerMovementMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void fiercerbattles$holdParryStaggerPosition(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (ParryServer.isStaggered(player)) ci.cancel();
    }
}
