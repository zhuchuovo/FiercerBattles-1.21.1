package com.fiercerbattles.mixin;

import com.fiercerbattles.animation.humanoid.AnimationModelPart;
import com.fiercerbattles.animation.humanoid.HumanoidAnimationAdapter;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ModelPart.class, priority = 2000)
public abstract class ExecutionModelPartMixin implements AnimationModelPart {
    @Unique private String fiercerbattles$animationChannel;

    @Override
    public void fiercerbattles$setAnimationChannel(String channel) {
        fiercerbattles$animationChannel = channel;
    }

    @Override
    public String fiercerbattles$getAnimationChannel() {
        return fiercerbattles$animationChannel;
    }

    @Inject(method = "translateAndRotate", at = @At("HEAD"), cancellable = true)
    private void fiercerbattles$applyEvaluatedExecutionPose(PoseStack poses, CallbackInfo ci) {
        if (HumanoidAnimationAdapter.apply((ModelPart) (Object) this, poses)) ci.cancel();
    }
}
