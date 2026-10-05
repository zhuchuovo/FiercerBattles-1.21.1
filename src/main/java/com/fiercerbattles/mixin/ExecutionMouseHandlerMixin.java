package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesExecutionClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MouseHandler.class)
public abstract class ExecutionMouseHandlerMixin {
    @WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"), remap = false)
    private void fiercerbattles$orbitExecution(LocalPlayer player, double yaw, double pitch, Operation<Void> original) {
        if (FiercerBattlesExecutionClient.controlsCamera(player)) {
            FiercerBattlesExecutionClient.turnCamera(yaw, pitch);
        } else {
            original.call(player, yaw, pitch);
        }
    }
}
