package com.fiercerbattles.mixin;

import com.fiercerbattles.execution.ExecutionModelPart;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerModel.class)
public abstract class ExecutionPlayerModelMixin {
    @Inject(method = "<init>(Lnet/minecraft/client/model/geom/ModelPart;Z)V", at = @At("TAIL"))
    private void fiercerbattles$identifyExecutionClothing(CallbackInfo ci) {
        PlayerModel<?> model = (PlayerModel<?>) (Object) this;
        identify(model.jacket, "body");
        identify(model.rightSleeve, "rightArm");
        identify(model.leftSleeve, "leftArm");
        identify(model.rightPants, "rightLeg");
        identify(model.leftPants, "leftLeg");
    }

    private static void identify(ModelPart part, String name) {
        ((ExecutionModelPart) (Object) part).fiercerbattles$setExecutionPart(name);
    }
}
