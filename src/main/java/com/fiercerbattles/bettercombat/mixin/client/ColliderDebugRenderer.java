package com.fiercerbattles.bettercombat.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.fiercerbattles.bettercombat.api.AttackHand;
import com.fiercerbattles.bettercombat.api.MinecraftClient_BetterCombat;
import com.fiercerbattles.bettercombat.api.WeaponAttributes;
import com.fiercerbattles.bettercombat.client.BetterCombatClientMod;
import com.fiercerbattles.bettercombat.client.collision.OrientedBoundingBox;
import com.fiercerbattles.bettercombat.client.collision.TargetFinder;
import com.fiercerbattles.bettercombat.client.collision.TargetFinder.TargetResult;
import com.fiercerbattles.bettercombat.logic.PlayerAttackHelper;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.stream.Collectors;

@Mixin(DebugRenderer.class)
public class ColliderDebugRenderer {
    @Inject(method = "render",at = @At("TAIL"))
    public void renderColliderDebug(PoseStack matrices, MultiBufferSource.BufferSource vertexConsumers, double cameraX, double cameraY, double cameraZ, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (!((MinecraftClientAccessor) client).getEntityRenderDispatcher().shouldRenderHitBoxes()) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }
        if (!BetterCombatClientMod.config.isDebugOBBEnabled) {
            return;
        }
        Camera camera = client.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }
        if (client.player.getMainHandItem() == null) {
            return;
        }
        var extendedClient = (MinecraftClient_BetterCombat)client;
        var comboCount = extendedClient.getComboCount();
        var hand = PlayerAttackHelper.getCurrentAttack(client.player, comboCount);
        if (hand == null) {
            return;
        }
        WeaponAttributes attributes = hand.attributes();
        if (attributes == null) {
            return;
        }
        var cursorTarget = extendedClient.getCursorTarget();
        var range = PlayerAttackHelper.getRangeForItem(player, hand.itemStack());
        range *= hand.attack().rangeMultiplier();
        var target = TargetFinder.findAttackTargetResult(
                player,
                cursorTarget,
                hand.attack(),
                range);
        boolean collides = target.entities.size() > 0;
        Vec3 cameraOffset = camera.getPosition().reverse();
        var obb = target.obb.
                copy()
                .offset(cameraOffset)
                .updateVertex();
        List<OrientedBoundingBox> collidingObbs = target.entities.stream()
                .map(entity -> new OrientedBoundingBox(entity.getBoundingBox())
                        .offset(cameraOffset)
                        .scale(0.95)
                        .updateVertex())
                .collect(Collectors.toList());
        drawOutline(matrices, obb, collidingObbs, collides);
    }

    private void drawOutline(PoseStack matrixStack, OrientedBoundingBox obb, List<OrientedBoundingBox> otherObbs, boolean collides) {
        RenderSystem.enableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Tesselator tessellator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tessellator.begin(VertexFormat.Mode.DEBUG_LINE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        RenderSystem.disableBlend();
        RenderSystem.lineWidth(1.0f);

        if (collides) {
            //System.out.println("Drawing collider +");
            outlineOBB(matrixStack, obb, bufferBuilder,
                    1, 0, 0,
                    1, 0, 0,0.5F);
        } else {
            //System.out.println("Drawing collider -");
            outlineOBB(matrixStack, obb, bufferBuilder,
                    0, 1, 0,
                    1, 1, 0,0.5F);
        }
        look(matrixStack, obb, bufferBuilder, 0.5F);

        for(OrientedBoundingBox otherObb: otherObbs){
            outlineOBB(matrixStack, otherObb, bufferBuilder,
                    1, 0, 0,
                    1, 0, 0,0.5F);
        }

        BufferUploader.drawWithShader(bufferBuilder.buildOrThrow());

        RenderSystem.lineWidth(1.0f);
        RenderSystem.enableBlend();
    }

    private void outlineOBB(PoseStack matrixStack, OrientedBoundingBox box, BufferBuilder buffer,
                            float red1, float green1, float blue1,
                            float red2, float green2, float blue2,
                            float alpha) {
        Matrix4f matrix4f = matrixStack.last().pose();
        buffer.addVertex(matrix4f, (float) box.vertex1.x, (float) box.vertex1.y, (float) box.vertex1.z).setColor(0, 0, 0, 0);

        buffer.addVertex(matrix4f, (float) box.vertex1.x, (float) box.vertex1.y, (float) box.vertex1.z).setColor(red1, green1, blue1, alpha);
        buffer.addVertex(matrix4f, (float) box.vertex2.x, (float) box.vertex2.y, (float) box.vertex2.z).setColor(red1, green1, blue1, alpha);
        buffer.addVertex(matrix4f, (float) box.vertex3.x, (float) box.vertex3.y, (float) box.vertex3.z).setColor(red1, green1, blue1, alpha);
        buffer.addVertex(matrix4f, (float) box.vertex4.x, (float) box.vertex4.y, (float) box.vertex4.z).setColor(red1, green1, blue1, alpha);
        buffer.addVertex(matrix4f, (float) box.vertex1.x, (float) box.vertex1.y, (float) box.vertex1.z).setColor(red1, green1, blue1, alpha);
        buffer.addVertex(matrix4f, (float) box.vertex5.x, (float) box.vertex5.y, (float) box.vertex5.z).setColor(red2, green2, blue2, alpha);
        buffer.addVertex(matrix4f, (float) box.vertex6.x, (float) box.vertex6.y, (float) box.vertex6.z).setColor(red2, green2, blue2, alpha);
        buffer.addVertex(matrix4f, (float) box.vertex2.x, (float) box.vertex2.y, (float) box.vertex2.z).setColor(red1, green1, blue1, alpha);
        buffer.addVertex(matrix4f, (float) box.vertex6.x, (float) box.vertex6.y, (float) box.vertex6.z).setColor(red2, green2, blue2, alpha);
        buffer.addVertex(matrix4f, (float) box.vertex7.x, (float) box.vertex7.y, (float) box.vertex7.z).setColor(red2, green2, blue2, alpha);
        buffer.addVertex(matrix4f, (float) box.vertex3.x, (float) box.vertex3.y, (float) box.vertex3.z).setColor(red1, green1, blue1, alpha);
        buffer.addVertex(matrix4f, (float) box.vertex7.x, (float) box.vertex7.y, (float) box.vertex7.z).setColor(red2, green2, blue2, alpha);
        buffer.addVertex(matrix4f, (float) box.vertex8.x, (float) box.vertex8.y, (float) box.vertex8.z).setColor(red2, green2, blue2, alpha);
        buffer.addVertex(matrix4f, (float) box.vertex4.x, (float) box.vertex4.y, (float) box.vertex4.z).setColor(red1, green1, blue1, alpha);
        buffer.addVertex(matrix4f, (float) box.vertex8.x, (float) box.vertex8.y, (float) box.vertex8.z).setColor(red2, green2, blue2, alpha);
        buffer.addVertex(matrix4f, (float) box.vertex5.x, (float) box.vertex5.y, (float) box.vertex5.z).setColor(red2, green2, blue2, alpha);

        buffer.addVertex(matrix4f, (float) box.vertex5.x, (float) box.vertex5.y, (float) box.vertex5.z).setColor(0, 0, 0, 0);
        buffer.addVertex(matrix4f, (float) box.center.x, (float) box.center.y, (float) box.center.z).setColor(0, 0, 0, 0);
    }

    private void look(PoseStack matrixStack, OrientedBoundingBox box, BufferBuilder buffer, float alpha) {
        Matrix4f matrix4f = matrixStack.last().pose();
        buffer.addVertex(matrix4f, (float) box.center.x, (float) box.center.y, (float) box.center.z).setColor(0, 0, 0, alpha);

        buffer.addVertex(matrix4f, (float) box.center.x, (float) box.center.y, (float) box.center.z).setColor(1, 0, 0, alpha);
        buffer.addVertex(matrix4f, (float) box.axisZ.x, (float) box.axisZ.y, (float) box.axisZ.z).setColor(1, 0, 0, alpha);
        buffer.addVertex(matrix4f, (float) box.center.x, (float) box.center.y, (float) box.center.z).setColor(1, 0, 0, alpha);

        buffer.addVertex(matrix4f, (float) box.center.x, (float) box.center.y, (float) box.center.z).setColor(0, 1, 0, alpha);
        buffer.addVertex(matrix4f, (float) box.axisY.x, (float) box.axisY.y, (float) box.axisY.z).setColor(0, 1, 0, alpha);
        buffer.addVertex(matrix4f, (float) box.center.x, (float) box.center.y, (float) box.center.z).setColor(0, 1, 0, alpha);

        buffer.addVertex(matrix4f, (float) box.center.x, (float) box.center.y, (float) box.center.z).setColor(0, 0, 1, alpha);
        buffer.addVertex(matrix4f, (float) box.axisX.x, (float) box.axisX.y, (float) box.axisX.z).setColor(0, 0, 1, alpha);
        buffer.addVertex(matrix4f, (float) box.center.x, (float) box.center.y, (float) box.center.z).setColor(0, 0, 1, alpha);

        buffer.addVertex(matrix4f, (float) box.center.x, (float) box.center.y, (float) box.center.z).setColor(0, 0, 0, alpha);
    }

    public void printDebug(OrientedBoundingBox obb) {
        Vec3 extent_x = obb.axisX.scale(obb.extent.x);
        Vec3 extent_y = obb.axisY.scale(obb.extent.y);
        Vec3 extent_z = obb.axisZ.scale(obb.extent.z);
        System.out.println("Center: " + vec3Short(obb.center) + " Extent: " + vec3Short(obb.extent) );
        System.out.println("scaledAxisX: " + vec3Short(obb.scaledAxisX)
                + "scaledAxisY: " + vec3Short(obb.scaledAxisY)
                + "scaledAxisZ: " + vec3Short(obb.scaledAxisZ));
        System.out.println("1:" + vec3Short(obb.vertex1)
                + " 2:" + vec3Short(obb.vertex2)
                + " 3:" + vec3Short(obb.vertex3)
                + " 4:" + vec3Short(obb.vertex4));
        System.out.println("5:" + vec3Short(obb.vertex5)
                + " 6:" + vec3Short(obb.vertex6)
                + " 7:" + vec3Short(obb.vertex7)
                + " 8:" + vec3Short(obb.vertex8));
    }

    private String vec3Short(Vec3 vec) {
        return "{" + String.format("%.3f", vec.x) + ", "  + String.format("%.3f", vec.y) + ", "  + String.format("%.3f", vec.z) + "}";
    }

    private Vec3[] getVertices(AABB box) {
        return new Vec3[]{
            new Vec3(box.minX, (float) box.minY, (float) box.minZ),
            new Vec3(box.maxX, (float) box.minY, (float) box.minZ),
            new Vec3(box.minX, (float) box.maxY, (float) box.minZ),
            new Vec3(box.minX, (float) box.minY, (float) box.maxZ),
            new Vec3(box.maxX, (float) box.maxY, (float) box.minZ),
            new Vec3(box.minX, (float) box.maxY, (float) box.maxZ),
            new Vec3(box.maxX, (float) box.minY, (float) box.maxZ),
            new Vec3(box.maxX, (float) box.maxY, (float) box.maxZ)
        };
    }
}
