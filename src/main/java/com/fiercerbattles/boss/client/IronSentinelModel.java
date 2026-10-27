package com.fiercerbattles.boss.client;

import com.fiercerbattles.animation.core.AnimationClip;
import com.fiercerbattles.boss.IronSentinel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Map;

/**
 * Draws the exported Blender meshes of the Iron Sentinel directly, each object placed by its clip channel (an
 * absolute pose in vanilla model space), as ZombieLordModel does.
 */
public final class IronSentinelModel extends EntityModel<IronSentinel> {
    private Map<String, AnimationClip.Transform> pose = Map.of();

    public IronSentinelModel() {
        super(RenderType::entityCutoutNoCull);
    }

    @Override
    public void setupAnim(IronSentinel sentinel, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
                          float headPitch) {
        // LivingEntityRenderer passes tickCount + partialTick as ageInTicks.
        pose = IronSentinelPoser.pose(sentinel, ageInTicks - sentinel.tickCount);
    }

    @Override
    public void renderToBuffer(PoseStack poses, VertexConsumer buffer, int light, int overlay, int color) {
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        for (ZombieLordAssets.MeshObject object : IronSentinelAssets.mesh()) {
            AnimationClip.Transform transform = pose.get(object.channel());
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
