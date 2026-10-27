package com.fiercerbattles.boss.client;

import com.fiercerbattles.boss.SentinelShell;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A cannon shell is a burning fire charge facing the camera. A mortar shell is a pulsing warning circle on its
 * marked ground (3 blocks across each way), pulsing faster as it comes, with the shell dropping onto it at the end.
 */
public final class SentinelShellRenderer extends EntityRenderer<SentinelShell> {
    private static final ResourceLocation RING = ZombieLordRenderer.texture("quake_ring");
    private static final ItemStack SHELL = new ItemStack(Items.FIRE_CHARGE);
    private final ItemRenderer items;

    public SentinelShellRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.items = context.getItemRenderer();
    }

    @Override
    public ResourceLocation getTextureLocation(SentinelShell shell) { return TextureAtlas.LOCATION_BLOCKS; }

    @Override
    public boolean shouldRender(SentinelShell shell, Frustum frustum, double x, double y, double z) {
        // The warning circle may be on screen while the shell's own small box is not.
        return shell.mode() == SentinelShell.MORTAR || super.shouldRender(shell, frustum, x, y, z);
    }

    @Override
    public void render(SentinelShell shell, float yaw, float partialTick, PoseStack poses, MultiBufferSource buffers, int light) {
        if (shell.mode() == SentinelShell.MORTAR) {
            float time = shell.tickCount + partialTick;
            float progress = Mth.clamp(time / SentinelShell.MORTAR_WARN_TICKS, 0.0F, 1.0F);
            float alpha = Mth.clamp(time / 4.0F, 0.0F, 1.0F) * (0.55F + 0.4F * Mth.sin(time * (0.5F + progress * 1.2F)));
            float radius = (float) SentinelShell.MORTAR_RADIUS;
            poses.pushPose();
            poses.translate(0.0, 0.05, 0.0);
            poses.mulPose(Axis.YP.rotationDegrees(time * 4.0F));
            ZombieLordRenderer.groundQuad(poses, buffers.getBuffer(RenderType.entityTranslucentEmissive(RING)), radius,
                    radius, alpha, 0xFF3A1A);
            poses.popPose();
            double drop = shell.dropHeight(partialTick);
            if (drop >= 0) drawShell(poses, buffers, drop + 0.4, 1.6F);
        } else {
            drawShell(poses, buffers, 0.1, shell.mode() == SentinelShell.SKY ? 0.8F : 1.0F);
        }
        super.render(shell, yaw, partialTick, poses, buffers, light);
    }

    private void drawShell(PoseStack poses, MultiBufferSource buffers, double up, float scale) {
        poses.pushPose();
        poses.translate(0.0, up, 0.0);
        poses.mulPose(entityRenderDispatcher.cameraOrientation());
        poses.scale(scale, scale, scale);
        items.renderStatic(SHELL, ItemDisplayContext.GROUND, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, poses,
                buffers, null, 0);
        poses.popPose();
    }
}
