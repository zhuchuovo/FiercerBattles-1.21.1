package com.fiercerbattles.mixin;

import com.fiercerbattles.animation.humanoid.HumanoidAnimationAdapter;
import net.minecraft.client.model.PlayerModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerModel.class)
public abstract class ExecutionPlayerModelMixin {
    @Inject(method = "<init>(Lnet/minecraft/client/model/geom/ModelPart;Z)V", at = @At("TAIL"))
    private void fiercerbattles$identifyExecutionClothing(CallbackInfo ci) {
        HumanoidAnimationAdapter.identifyPlayerClothing((net.minecraft.client.model.PlayerModel<?>) (Object) this);
    }
}
