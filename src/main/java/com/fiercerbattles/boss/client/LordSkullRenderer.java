package com.fiercerbattles.boss.client;

import com.fiercerbattles.boss.LordSkull;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.SkullModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/** Skeleton skull with a soul-fire trail; while it flies, the 3x3 blast square is drawn at its landing point. */
public final class LordSkullRenderer extends EntityRenderer<LordSkull> {
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/skeleton/skeleton.png");
    private static final ResourceLocation WARNING = ZombieLordRenderer.texture("skull_warning");
    private static final float SCALE = 1.25F;
    private final SkullModel model;

    public LordSkullRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new SkullModel(context.bakeLayer(ModelLayers.SKELETON_SKULL));
    }

    @Override
    public ResourceLocation getTextureLocation(LordSkull skull) { return TEXTURE; }

    @Override
    public boolean shouldRender(LordSkull skull, Frustum frustum, double x, double y, double z) {
        // The warning square may be on screen while the skull itself is not.
        return skull.isFlying() || super.shouldRender(skull, frustum, x, y, z);
    }

    @Override
    public void render(LordSkull skull, float yaw, float partialTick, PoseStack poses, MultiBufferSource buffers, int light) {
        poses.pushPose();
        poses.scale(-SCALE, -SCALE, SCALE);
        model.setupAnim(0.0F, Mth.rotLerp(partialTick, skull.yRotO, skull.getYRot()), Mth.lerp(partialTick, skull.xRotO, skull.getXRot()));
        model.renderToBuffer(poses, buffers.getBuffer(RenderType.entityCutoutNoCullZOffset(TEXTURE)), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        poses.popPose();
        if (skull.isFlying()) {
            Vector3f target = skull.target();
            poses.pushPose();
            poses.translate(target.x() - Mth.lerp(partialTick, skull.xo, skull.getX()),
                    target.y() - Mth.lerp(partialTick, skull.yo, skull.getY()) + 0.03,
                    target.z() - Mth.lerp(partialTick, skull.zo, skull.getZ()));
            float alpha = 0.6F + 0.35F * Mth.sin((skull.tickCount + partialTick) * 0.9F);
            float half = (float) LordSkull.BLAST_HALF_WIDTH;
            ZombieLordRenderer.groundQuad(poses, buffers.getBuffer(RenderType.entityTranslucentEmissive(WARNING)), half, half, alpha);
            poses.popPose();
        }
        super.render(skull, yaw, partialTick, poses, buffers, light);
    }
}
