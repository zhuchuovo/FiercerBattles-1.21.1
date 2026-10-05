package com.fiercerbattles.mixin;

import com.fiercerbattles.execution.client.ExecutionRenderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Suppress PlayerAnimator's extra body bends and item adjustments in our render scope. */
@Pseudo
@Mixin(targets = "dev.kosmx.playerAnim.core.impl.AnimationProcessor", remap = false)
public abstract class ExecutionAnimationProcessorMixin {
    @Inject(method = "isActive()Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void fiercerbattles$useExecutionPose(CallbackInfoReturnable<Boolean> cir) {
        if (ExecutionRenderContext.isActive()) cir.setReturnValue(false);
    }
}
