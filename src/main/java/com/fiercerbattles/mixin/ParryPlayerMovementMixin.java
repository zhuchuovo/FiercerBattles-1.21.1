package com.fiercerbattles.mixin;

import com.fiercerbattles.parry.ParryServer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Movement packets cannot move a parrying or stunned player away from the locked position. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ParryPlayerMovementMixin {
    @Shadow public ServerPlayer player;

    @ModifyVariable(method = "handleMovePlayer", at = @At("HEAD"), argsOnly = true)
    private ServerboundMovePlayerPacket fiercerbattles$keepParryRotation(ServerboundMovePlayerPacket packet) {
        // Staggered actors are already frozen above; only an active parry reaches this point.
        if (!ParryServer.isMovementLocked(player) || ParryServer.isStaggered(player)) return packet;
        // A parry still falls: the footprint stays pinned to the server position while the height
        // keeps following the client, so gravity is never frozen mid-gesture.
        return new ServerboundMovePlayerPacket.PosRot(player.getX(), packet.getY(player.getY()), player.getZ(),
                packet.getYRot(player.getYRot()), packet.getXRot(player.getXRot()), packet.isOnGround());
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void fiercerbattles$holdParryStaggerPosition(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (ParryServer.isStaggered(player)) ci.cancel();
    }
}
