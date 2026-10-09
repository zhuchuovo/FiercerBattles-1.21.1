package com.fiercerbattles.mixin;

import com.fiercerbattles.parry.client.ParryRenderContext;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ModelPart.class, priority = 2100)
public abstract class ParryPoseCaptureMixin {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V", at = @At("HEAD"))
    private void fiercerbattles$restoreRewoundMeshBend(PoseStack poses, VertexConsumer vertices,
                                                     int light, int overlay, int color, CallbackInfo ci) {
        ParryRenderContext.applyBend((ModelPart) (Object) this);
    }

    @Inject(method = "translateAndRotate", at = @At("HEAD"), cancellable = true)
    private void fiercerbattles$replayParriedAttack(PoseStack poses, CallbackInfo ci) {
        if (ParryRenderContext.applyReplay((ModelPart) (Object) this, poses)) ci.cancel();
    }

    @Inject(method = "translateAndRotate", at = @At("RETURN"))
    private void fiercerbattles$captureRenderedAttack(PoseStack poses, CallbackInfo ci) {
        ParryRenderContext.capture((ModelPart) (Object) this, poses);
    }
}
