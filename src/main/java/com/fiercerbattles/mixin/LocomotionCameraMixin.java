package com.fiercerbattles.mixin;

import com.fiercerbattles.locomotion.client.LocomotionClient;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class LocomotionCameraMixin {
    @Shadow private Entity entity;
    @Shadow private float eyeHeight;

    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void fiercerbattles$followRollEyes(CallbackInfo ci) {
        // A roll holds the swimming pose, so vanilla would sink the eyes straight to crawl height.
        // Follow the roll's own dip instead; once it lets go, vanilla eases on from this height.
        float eye = LocomotionClient.rollEyeHeight(entity);
        if (!Float.isNaN(eye)) eyeHeight = eye;
    }
}
