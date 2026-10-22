package com.fiercerbattles.boss.client;

import com.fiercerbattles.animation.core.AnimationClip;
import com.fiercerbattles.boss.ZombieLord;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Map;

/**
 * Draws the exported Blender meshes directly. Each object is placed by its clip channel: an absolute
 * pose in vanilla model space (pixels, y down, feet at 24), so no ModelPart hierarchy is involved.
 */
public final class ZombieLordModel extends EntityModel<ZombieLord> {
    private Map<String, AnimationClip.Transform> pose = Map.of();
    /** Past the first moments of the death clip the red death tint is dropped. */
    private boolean clearOverlay;

    public ZombieLordModel() {
        super(RenderType::entityCutoutNoCull);
    }

    @Override
    public void setupAnim(ZombieLord lord, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        // LivingEntityRenderer passes tickCount + partialTick as ageInTicks.
        pose = ZombieLordPoser.pose(lord, ageInTicks - lord.tickCount);
        clearOverlay = lord.isDeadOrDying() && lord.deathTime > 8;
    }

    @Override
    public void renderToBuffer(PoseStack poses, VertexConsumer buffer, int light, int overlay, int color) {
        if (clearOverlay) overlay = OverlayTexture.NO_OVERLAY;
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        for (ZombieLordAssets.MeshObject object : ZombieLordAssets.mesh()) {
            AnimationClip.Transform transform = pose.get(object.name());
            if (transform == null) continue;
            poses.pushPose();
            poses.translate(transform.x() / 16.0F, transform.y() / 16.0F, transform.z() / 16.0F);
            poses.mulPose(transform.rotation());
            PoseStack.Pose last = poses.last();
            Matrix4f matrix = last.pose();
            float[] faces = object.faces();
            for (int base = 0; base < faces.length; base += ZombieLordAssets.FACE_STRIDE) {
                last.transformNormal(faces[base], faces[base + 1], faces[base + 2], normal);
                for (int corner = 0; corner < 4; corner++) {
                    int at = base + 3 + corner * 5;
                    matrix.transformPosition(faces[at] / 16.0F, faces[at + 1] / 16.0F, faces[at + 2] / 16.0F, position);
                    buffer.addVertex(position.x(), position.y(), position.z(), color, faces[at + 3], faces[at + 4],
                            overlay, light, normal.x(), normal.y(), normal.z());
                }
            }
            poses.popPose();
        }
    }
}
