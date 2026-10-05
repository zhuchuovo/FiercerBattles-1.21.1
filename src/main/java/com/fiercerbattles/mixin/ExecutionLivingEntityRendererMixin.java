package com.fiercerbattles.mixin;

import com.fiercerbattles.execution.ExecutionRenderContext;
import com.fiercerbattles.FiercerBattlesExecutionClient;
import com.fiercerbattles.execution.ExecutionAnimation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LivingEntityRenderer.class)
public abstract class ExecutionLivingEntityRendererMixin {
    @Shadow protected abstract void setupRotations(LivingEntity entity, PoseStack poses, float age, float yaw, float partial, float scale);
    @Shadow protected abstract void scale(LivingEntity entity, PoseStack poses, float partial);

    @WrapMethod(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
    private void fiercerbattles$renderExecution(LivingEntity entity, float yaw, float partial, PoseStack poses, MultiBufferSource buffers, int light, Operation<Void> original) {
        ExecutionRenderContext.begin(entity, partial);
        poses.pushPose();
        FiercerBattlesExecutionClient.Actor actor = FiercerBattlesExecutionClient.actor(entity);
        if (actor != null && !actor.returning()) {
            // Network lerp can still be in flight when the pair start arrives.
            // Both models must render at the server anchors on the very first frame.
            poses.translate(actor.x() - Mth.lerp(partial, entity.xo, entity.getX()),
                    actor.y() - Mth.lerp(partial, entity.yo, entity.getY()),
                    actor.z() - Mth.lerp(partial, entity.zo, entity.getZ()));
        }
        try {
            original.call(entity, yaw, partial, poses, buffers, light);
        } finally {
            poses.popPose();
            ExecutionRenderContext.end();
        }
    }

    @Redirect(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;setupRotations(Lnet/minecraft/world/entity/LivingEntity;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V"))
    private void fiercerbattles$fixExecutionYaw(LivingEntityRenderer<?, ?> renderer, LivingEntity entity, PoseStack poses, float age, float yaw, float partial, float scale) {
        if (ExecutionRenderContext.isActive()) {
            poses.mulPose(Axis.YP.rotationDegrees(180.0F - ExecutionRenderContext.yaw()));
        } else {
            setupRotations(entity, poses, age, yaw, partial, scale);
        }
    }

    @Redirect(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;scale(Lnet/minecraft/world/entity/LivingEntity;Lcom/mojang/blaze3d/vertex/PoseStack;F)V"))
    private void fiercerbattles$keepSharedExecutionScale(LivingEntityRenderer<?, ?> renderer, LivingEntity entity, PoseStack poses, float partial) {
        if (!ExecutionRenderContext.isActive()) scale(entity, poses, partial);
    }

    @Redirect(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getScale()F"))
    private float fiercerbattles$keepSharedEntityScale(LivingEntity entity) {
        return ExecutionRenderContext.isActive() ? 1.0F : entity.getScale();
    }

    @Redirect(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void fiercerbattles$attachBackLayers(RenderLayer layer, PoseStack poses, MultiBufferSource buffers,
                                                int light, Entity entity, float swing, float amount, float partial,
                                                float age, float headYaw, float headPitch) {
        ExecutionAnimation.Transform body = ExecutionRenderContext.isActive()
                && (layer instanceof CapeLayer || layer instanceof ElytraLayer)
                ? ExecutionRenderContext.transform("body") : null;
        if (body == null) {
            layer.render(poses, buffers, light, entity, swing, amount, partial, age, headYaw, headPitch);
            return;
        }
        poses.pushPose();
        try {
            poses.translate(body.x() / 16.0F, body.y() / 16.0F, body.z() / 16.0F);
            poses.mulPose(body.rotation());
            poses.scale(body.scale(), body.scale(), body.scale());
            layer.render(poses, buffers, light, entity, swing, amount, partial, age, headYaw, headPitch);
        } finally {
            poses.popPose();
        }
    }
}
