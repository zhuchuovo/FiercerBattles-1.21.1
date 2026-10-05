package com.fiercerbattles.mixin;

import com.fiercerbattles.animation.humanoid.HumanoidAnimationAdapter;
import net.minecraft.client.model.HumanoidModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidModel.class)
public abstract class ExecutionHumanoidModelMixin {
    @Inject(method = "<init>(Lnet/minecraft/client/model/geom/ModelPart;Ljava/util/function/Function;)V", at = @At("TAIL"))
    private void fiercerbattles$identifyExecutionParts(CallbackInfo ci) {
        HumanoidAnimationAdapter.identify((net.minecraft.client.model.HumanoidModel<?>) (Object) this);
    }
}
