package com.fiercerbattles.boss.client;

import com.fiercerbattles.boss.LordSkeleton;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.SkeletonRenderer;
import net.minecraft.util.Mth;

/** Vanilla skeleton look; while it digs out, the model is drawn sunk into the ground and the terrain hides the rest. */
public final class LordSkeletonRenderer extends SkeletonRenderer<LordSkeleton> {
    public LordSkeletonRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(LordSkeleton skeleton, float yaw, float partialTick, PoseStack poses, MultiBufferSource buffers, int light) {
        float progress = skeleton.emergeProgress(partialTick);
        if (progress >= 1.0F) {
            super.render(skeleton, yaw, partialTick, poses, buffers, light);
            return;
        }
        float eased = 1.0F - (1.0F - progress) * (1.0F - progress);
        float age = skeleton.tickCount + partialTick;
        poses.pushPose();
        poses.translate(0.0F, -(1.0F - eased) * (skeleton.getBbHeight() + 0.1F), 0.0F);
        // A struggling wobble while it claws its way up.
        poses.mulPose(Axis.ZP.rotationDegrees(Mth.sin(age * 1.3F) * 6.0F * (1.0F - progress)));
        super.render(skeleton, yaw, partialTick, poses, buffers, light);
        poses.popPose();
    }
}
