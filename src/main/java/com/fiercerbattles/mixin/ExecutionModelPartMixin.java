package com.fiercerbattles.mixin;

import com.fiercerbattles.execution.ExecutionAnimation;
import com.fiercerbattles.execution.ExecutionModelPart;
import com.fiercerbattles.execution.ExecutionRenderContext;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ModelPart.class, priority = 2000)
public abstract class ExecutionModelPartMixin implements ExecutionModelPart {
    @Unique private String fiercerbattles$executionPart;

    @Override
    public void fiercerbattles$setExecutionPart(String name) {
        fiercerbattles$executionPart = name;
    }

    @Override
    public String fiercerbattles$getExecutionPart() {
        return fiercerbattles$executionPart;
    }

    @Inject(method = "translateAndRotate", at = @At("HEAD"), cancellable = true)
    private void fiercerbattles$applyEvaluatedExecutionPose(PoseStack poses, CallbackInfo ci) {
        ModelPart part = (ModelPart) (Object) this;
        ExecutionAnimation.Transform transform = ExecutionRenderContext.transform(fiercerbattles$executionPart, part);
        if (transform == null) return;
        poses.translate(transform.x() / 16.0F, transform.y() / 16.0F, transform.z() / 16.0F);
        poses.mulPose(transform.rotation());
        float progress = ExecutionRenderContext.returnProgress();
        poses.scale(transform.scale() * Mth.lerp(progress, 1, part.xScale),
                transform.scale() * Mth.lerp(progress, 1, part.yScale),
                transform.scale() * Mth.lerp(progress, 1, part.zScale));
        ci.cancel();
    }
}
