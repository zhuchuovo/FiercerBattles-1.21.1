package com.fiercerbattles.boss.client;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.boss.ZombieLord;
import com.fiercerbattles.boss.ZombieLordAction;
import com.fiercerbattles.boss.ZombieLordPoseState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

public final class ZombieLordRenderer extends MobRenderer<ZombieLord, ZombieLordModel> {
    static final ResourceLocation TEXTURE = texture("zombie_lord");
    static final ResourceLocation GLOW = texture("zombie_lord_glow");
    static final ResourceLocation RING = texture("quake_ring");
    static final ResourceLocation TRAIL = texture("charge_trail");
    static final ResourceLocation WARNING = texture("skull_warning");
    private static final RenderType GLOW_TYPE = RenderType.eyes(GLOW);
    /** The ring grows from this radius to its full size over the two-second tremor. */
    private static final float RING_START_RADIUS = 0.6F;
    private static final float RING_FULL_RADIUS = 5.0F;
    private static final float TRAIL_HALF_WIDTH = 0.55F;

    public ZombieLordRenderer(EntityRendererProvider.Context context) {
        super(context, new ZombieLordModel(), 0.9F);
        addLayer(new GlowLayer(this));
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

    /** The death clip collapses the body itself; no vanilla tip-over on top of it. */
    @Override
    protected float getFlipDegrees(ZombieLord lord) { return 0.0F; }

    @Override
    public void render(ZombieLord lord, float yaw, float partialTick, PoseStack poses, MultiBufferSource buffers, int light) {
        super.render(lord, yaw, partialTick, poses, buffers, light);
        renderQuakeRing(lord, partialTick, poses, buffers);
        renderChargeTrail(lord, partialTick, poses, buffers, light);
        renderLeapMark(lord, partialTick, poses, buffers);
    }

    /** While the ghost blade's charged leap is in the air: a burning ring on its landing point, pulsing faster as it falls. */
    private static void renderLeapMark(ZombieLord lord, float partialTick, PoseStack poses, MultiBufferSource buffers) {
        if (lord.isDeadOrDying() || lord.getAction() != ZombieLordAction.GHOST_BLADE) return;
        float time = (float) lord.actionTime(partialTick);
        if (time < ZombieLord.GB_LEAP_TAKEOFF || time >= ZombieLord.GB_LEAP_LAND) return;
        float progress = (time - ZombieLord.GB_LEAP_TAKEOFF) / (ZombieLord.GB_LEAP_LAND - ZombieLord.GB_LEAP_TAKEOFF);
        float alpha = Mth.clamp(progress * 4.0F, 0.0F, 1.0F) * (0.65F + 0.3F * Mth.sin(time * (0.4F + progress * 0.8F)));
        Vec3 mark = lord.getLeapMark();
        poses.pushPose();
        poses.translate(mark.x - Mth.lerp(partialTick, lord.xo, lord.getX()), mark.y - Mth.lerp(partialTick, lord.yo, lord.getY()) + 0.05,
                mark.z - Mth.lerp(partialTick, lord.zo, lord.getZ()));
        poses.mulPose(Axis.YP.rotationDegrees(time * 3.0F));
        float radius = (float) ZombieLord.GB_LEAP_RADIUS;
        groundQuad(poses, buffers.getBuffer(RenderType.entityTranslucentEmissive(RING)), radius, radius, alpha, 0xFF7A2A);
        groundQuad(poses, buffers.getBuffer(RenderType.entityTranslucentEmissive(WARNING)), 1.5F, 1.5F, alpha, 0xFFFFFF);
        poses.popPose();
    }

    /** Light ring under the boss from the sword plant on, growing over the tremor and fading as it rises. */
    private static void renderQuakeRing(ZombieLord lord, float partialTick, PoseStack poses, MultiBufferSource buffers) {
        ZombieLordAction action = lord.getAction();
        if (lord.isDeadOrDying() || action != ZombieLordAction.QUAKE && action != ZombieLordAction.QUAKE_END) return;
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

    /** Scorched furrow behind a charge: one ground strip per pair of dash samples, fading over three seconds. */
    private static void renderChargeTrail(ZombieLord lord, float partialTick, PoseStack poses, MultiBufferSource buffers, int light) {
        List<ZombieLordPoseState.TrailPoint> trail = lord.poseState.trail;
        if (trail.size() < 2) return;
        double originX = Mth.lerp(partialTick, lord.xo, lord.getX());
        double originY = Mth.lerp(partialTick, lord.yo, lord.getY());
        double originZ = Mth.lerp(partialTick, lord.zo, lord.getZ());
        double now = lord.level().getGameTime() + (double) partialTick;
        VertexConsumer buffer = buffers.getBuffer(RenderType.entityTranslucent(TRAIL));
        PoseStack.Pose pose = poses.last();
        Matrix4f matrix = pose.pose();
        float v = 0.0F;
        for (int i = 0; i + 1 < trail.size(); i++) {
            ZombieLordPoseState.TrailPoint a = trail.get(i), b = trail.get(i + 1);
            double dx = b.x() - a.x(), dz = b.z() - a.z();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length < 1.0E-3 || length > 2.0) continue;
            float sideX = (float) (-dz / length) * TRAIL_HALF_WIDTH, sideZ = (float) (dx / length) * TRAIL_HALF_WIDTH;
            int colorA = trailColor(now - a.time()), colorB = trailColor(now - b.time());
            float ax = (float) (a.x() - originX), ay = (float) (a.y() - originY) + 0.02F, az = (float) (a.z() - originZ);
            float bx = (float) (b.x() - originX), by = (float) (b.y() - originY) + 0.02F, bz = (float) (b.z() - originZ);
            float v2 = v + (float) length / (2 * TRAIL_HALF_WIDTH);
            trailVertex(buffer, matrix, pose, ax - sideX, ay, az - sideZ, 0.0F, v, colorA, light);
            trailVertex(buffer, matrix, pose, ax + sideX, ay, az + sideZ, 1.0F, v, colorA, light);
            trailVertex(buffer, matrix, pose, bx + sideX, by, bz + sideZ, 1.0F, v2, colorB, light);
            trailVertex(buffer, matrix, pose, bx - sideX, by, bz - sideZ, 0.0F, v2, colorB, light);
            v = v2;
        }
    }

    private static int trailColor(double age) {
        float alpha = (float) Mth.clamp(1.0 - age / ZombieLord.CHARGE_TRAIL_TICKS, 0.0, 1.0);
        return FastColor.ARGB32.color((int) (alpha * alpha * 230), 255, 255, 255);
    }

    private static void trailVertex(VertexConsumer buffer, Matrix4f matrix, PoseStack.Pose pose, float x, float y, float z,
                                    float u, float v, int color, int light) {
        buffer.addVertex(matrix, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    /** Horizontal textured quad centred on the pose origin, full bright. */
    static void groundQuad(PoseStack poses, VertexConsumer buffer, float halfX, float halfZ, float alpha) {
        groundQuad(poses, buffer, halfX, halfZ, alpha, 0xFFFFFF);
    }

    /** As above, tinted {@code rgb}. */
    static void groundQuad(PoseStack poses, VertexConsumer buffer, float halfX, float halfZ, float alpha, int rgb) {
        PoseStack.Pose pose = poses.last();
        Matrix4f matrix = pose.pose();
        int color = ((int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255) << 24) | rgb;
        float[][] corners = {{-halfX, -halfZ, 0, 0}, {-halfX, halfZ, 0, 1}, {halfX, halfZ, 1, 1}, {halfX, -halfZ, 1, 0}};
        for (float[] corner : corners) {
            buffer.addVertex(matrix, corner[0], 0.0F, corner[1]).setColor(color).setUv(corner[2], corner[3])
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 1.0F, 0.0F);
        }
    }

    /** Glowing eyes, runes and heart; they die out over the death clip. */
    private static final class GlowLayer extends RenderLayer<ZombieLord, ZombieLordModel> {
        GlowLayer(RenderLayerParent<ZombieLord, ZombieLordModel> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack poses, MultiBufferSource buffers, int light, ZombieLord lord, float limbSwing,
                           float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            float glow = lord.isDeadOrDying()
                    ? Mth.clamp(1.0F - (lord.deathTime + partialTick) / ZombieLord.DEATH_CLIP_FRAMES, 0.0F, 1.0F) : 1.0F;
            if (glow <= 0.0F) return;
            int channel = (int) (glow * 255);
            getParentModel().renderToBuffer(poses, buffers.getBuffer(GLOW_TYPE), LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY, FastColor.ARGB32.color(255, channel, channel, channel));
        }
    }
}
