package com.fiercerbattles.boss.client;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.boss.ZombieLord;
import com.fiercerbattles.boss.ZombieLordAction;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

public final class ZombieLordRenderer extends MobRenderer<ZombieLord, ZombieLordModel> {
    static final ResourceLocation TEXTURE = texture("zombie_lord");
    static final ResourceLocation GLOW = texture("zombie_lord_glow");
    static final ResourceLocation RING = texture("quake_ring");
    private static final RenderType GLOW_TYPE = RenderType.eyes(GLOW);
    /** The ring grows from this radius to its full size over the two-second tremor. */
    private static final float RING_START_RADIUS = 0.6F;
    private static final float RING_FULL_RADIUS = 5.0F;

    public ZombieLordRenderer(EntityRendererProvider.Context context) {
        super(context, new ZombieLordModel(), 0.9F);
        addLayer(new EyesLayer<>(this) {
            @Override
            public RenderType renderType() { return GLOW_TYPE; }
        });
    }

    static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(FiercerBattles.MOD_ID, "textures/entity/zombie_lord/" + name + ".png");
    }

    @Override
    public ResourceLocation getTextureLocation(ZombieLord lord) { return TEXTURE; }

    @Override
    protected void scale(ZombieLord lord, PoseStack poses, float partialTick) {
        poses.scale(ZombieLord.RENDER_SCALE, ZombieLord.RENDER_SCALE, ZombieLord.RENDER_SCALE);
    }

    @Override
    public void render(ZombieLord lord, float yaw, float partialTick, PoseStack poses, MultiBufferSource buffers, int light) {
        super.render(lord, yaw, partialTick, poses, buffers, light);
        renderQuakeRing(lord, partialTick, poses, buffers);
    }

    /** Light ring under the boss from the sword plant on, growing over the tremor and fading as it rises. */
    private static void renderQuakeRing(ZombieLord lord, float partialTick, PoseStack poses, MultiBufferSource buffers) {
        ZombieLordAction action = lord.getAction();
        if (action != ZombieLordAction.QUAKE && action != ZombieLordAction.QUAKE_END) return;
        float time = (float) lord.actionTime(partialTick);
        float since = time - ZombieLord.QUAKE_PLANT_FRAME;
        if (since < 0) return;
        float grow = Mth.clamp(since / ZombieLord.TREMOR_TICKS, 0.0F, 1.0F);
        float radius = Mth.lerp(1.0F - (1.0F - grow) * (1.0F - grow), RING_START_RADIUS, RING_FULL_RADIUS);
        float alpha = 0.75F + 0.2F * Mth.sin(since * 0.25F);
        if (action == ZombieLordAction.QUAKE_END) {
            alpha *= Mth.clamp(1.0F - (time - ZombieLord.QUAKE_FREEZE_FRAME) / (ZombieLord.QUAKE_LENGTH - ZombieLord.QUAKE_FREEZE_FRAME), 0.0F, 1.0F);
        }
        if (alpha <= 0.01F) return;
        poses.pushPose();
        poses.translate(0.0F, 0.04F, 0.0F);
        poses.mulPose(Axis.YP.rotationDegrees(since * 1.5F));
        groundQuad(poses, buffers.getBuffer(RenderType.entityTranslucentEmissive(RING)), radius, radius, alpha);
        poses.popPose();
    }

    /** Horizontal textured quad centred on the pose origin, full bright. */
    static void groundQuad(PoseStack poses, VertexConsumer buffer, float halfX, float halfZ, float alpha) {
        PoseStack.Pose pose = poses.last();
        Matrix4f matrix = pose.pose();
        int color = ((int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255) << 24) | 0xFFFFFF;
        float[][] corners = {{-halfX, -halfZ, 0, 0}, {-halfX, halfZ, 0, 1}, {halfX, halfZ, 1, 1}, {halfX, -halfZ, 1, 0}};
        for (float[] corner : corners) {
            buffer.addVertex(matrix, corner[0], 0.0F, corner[1]).setColor(color).setUv(corner[2], corner[3])
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 1.0F, 0.0F);
        }
    }
}
