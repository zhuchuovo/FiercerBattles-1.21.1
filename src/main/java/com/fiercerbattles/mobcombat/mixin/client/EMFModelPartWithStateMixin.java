package com.fiercerbattles.mobcombat.mixin.client;

import com.fiercerbattles.mobcombat.internal.mobanim.OptionalEmfCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
        targets = "traben.entity_model_features.models.parts.EMFModelPartWithState",
        priority = 500,
        remap = false
)
public abstract class EMFModelPartWithStateMixin {
    @Inject(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ltraben/entity_model_features/models/parts/EMFModelPartRoot;animate()V",
                    shift = At.Shift.AFTER,
                    remap = false
            ),
            remap = false
    )
    private void bmc$overlayAttackArmsAfterEmfAnimation(
            PoseStack poseStack,
            VertexConsumer vertexConsumer,
            int packedLight,
            int packedOverlay,
            int packedColor,
            CallbackInfo ci
    ) {
        OptionalEmfCompat.reapplyArmsAfterEmfAnimation();
    }
}
