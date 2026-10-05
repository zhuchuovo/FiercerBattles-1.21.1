package com.fiercerbattles.mixin;

import com.fiercerbattles.execution.ExecutionModelPart;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidModel.class)
public abstract class ExecutionHumanoidModelMixin {
    @Inject(method = "<init>(Lnet/minecraft/client/model/geom/ModelPart;Ljava/util/function/Function;)V", at = @At("TAIL"))
    private void fiercerbattles$identifyExecutionParts(CallbackInfo ci) {
        HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
        identify(model.head, "head");
        identify(model.hat, "head");
        identify(model.body, "body");
        identify(model.rightArm, "rightArm");
        identify(model.leftArm, "leftArm");
        identify(model.rightLeg, "rightLeg");
        identify(model.leftLeg, "leftLeg");
    }

    private static void identify(ModelPart part, String name) {
        ((ExecutionModelPart) (Object) part).fiercerbattles$setExecutionPart(name);
    }
}
