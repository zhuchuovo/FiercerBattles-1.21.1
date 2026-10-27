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
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A cannon shell is a burning fire charge facing the camera. A mortar shell is a target marked on its ground: the
 * rim of the 3 block blast, a disc filling it from the centre that reaches the rim as the shell lands, and a
 * crosshair turning on the spot (textures from tools/sentinel_mortar_textures.py). The marker closes in from wider
 * as it appears, reddens and pulses faster as the shell comes, and flashes while the shell is seen dropping onto it.
 */
public final class SentinelShellRenderer extends EntityRenderer<SentinelShell> {
    private static final ResourceLocation RING = IronSentinelRenderer.texture("mortar_ring");
    private static final ResourceLocation FILL = IronSentinelRenderer.texture("mortar_fill");
    private static final ResourceLocation RETICLE = IronSentinelRenderer.texture("mortar_reticle");
    /** The marker appears this much wider and closes in to its size over LOCK_TICKS. */
    private static final float LOCK_SCALE = 1.35F;
    private static final float LOCK_TICKS = 6.0F;
    private static final float RETICLE_RADIUS = 1.1F;
    private static final int RIM_COLOR = 0xFF8C28;
    private static final int RIM_COLOR_LANDING = 0xFF2814;
    private static final int RIM_COLOR_FLASH = 0xFFE6B4;
    private static final int FILL_COLOR = 0xFF3C14;
    private static final int RETICLE_COLOR = 0xFFD250;
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
            double drop = shell.dropHeight(partialTick);
            drawWarning(shell.tickCount + partialTick, drop >= 0, poses, buffers);
            if (drop >= 0) drawShell(poses, buffers, drop + 0.4, 1.6F);
        } else {
            drawShell(poses, buffers, 0.1, shell.mode() == SentinelShell.SKY ? 0.8F : 1.0F);
        }
        super.render(shell, yaw, partialTick, poses, buffers, light);
    }

    private static void drawWarning(float time, boolean falling, PoseStack poses, MultiBufferSource buffers) {
        float progress = Mth.clamp(time / SentinelShell.MORTAR_WARN_TICKS, 0.0F, 1.0F);
        float lock = Mth.clamp(time / LOCK_TICKS, 0.0F, 1.0F);
        float closing = Mth.lerp(lock * (2.0F - lock), LOCK_SCALE, 1.0F);
        float shown = Mth.clamp(time / 3.0F, 0.0F, 1.0F);
        float radius = (float) SentinelShell.MORTAR_RADIUS;
        // The fill grows at a steady rate, so how fast it nears the rim tells when the shell lands.
        float fill = radius * progress;
        if (fill > 0.05F) {
            poses.pushPose();
            poses.translate(0.0, 0.03, 0.0);
            ZombieLordRenderer.groundQuad(poses, buffers.getBuffer(RenderType.entityTranslucentEmissive(FILL)), fill, fill,
                    0.55F * shown, FILL_COLOR);
            poses.popPose();
        }
        int rim = falling && Mth.floor(time / 2.0F) % 2 == 0 ? RIM_COLOR_FLASH
                : FastColor.ARGB32.lerp(progress, RIM_COLOR, RIM_COLOR_LANDING);
        float pulse = falling ? 1.0F : 0.78F + 0.22F * Mth.sin(time * (0.4F + progress * 1.4F));
        poses.pushPose();
        poses.translate(0.0, 0.05, 0.0);
        poses.mulPose(Axis.YP.rotationDegrees(time));
        ZombieLordRenderer.groundQuad(poses, buffers.getBuffer(RenderType.entityTranslucentEmissive(RING)),
                radius * closing, radius * closing, shown * pulse, rim);
        poses.popPose();
        float reticle = RETICLE_RADIUS * closing;
        poses.pushPose();
        poses.translate(0.0, 0.07, 0.0);
        poses.mulPose(Axis.YP.rotationDegrees(-time * 6.0F));
        ZombieLordRenderer.groundQuad(poses, buffers.getBuffer(RenderType.entityTranslucentEmissive(RETICLE)), reticle,
                reticle, shown * 0.95F, RETICLE_COLOR);
        poses.popPose();
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
