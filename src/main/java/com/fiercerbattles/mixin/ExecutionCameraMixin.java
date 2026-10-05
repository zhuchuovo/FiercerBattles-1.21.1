package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesExecutionClient;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Camera.class)
public abstract class ExecutionCameraMixin {
    @Shadow private Entity entity;

    @ModifyArg(method = "setup", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"), index = 0, remap = false)
    private float fiercerbattles$easeExecutionDistance(float distance) {
        // Ease before vanilla's collision rays, so orbiting never pulls the camera through a wall.
        return FiercerBattlesExecutionClient.controlsCamera(entity)
                ? FiercerBattlesExecutionClient.cameraDistance(distance) : distance;
    }
}
