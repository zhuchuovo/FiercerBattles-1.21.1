package com.fiercerbattles.boss.client;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.boss.IronSentinel;
import com.mojang.blaze3d.vertex.PoseStack;
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

/** The Iron Sentinel's Blender meshes with their glowing layer (eyes, furnace, vents, cannon bore, saw teeth). */
public final class IronSentinelRenderer extends MobRenderer<IronSentinel, IronSentinelModel> {
    static final ResourceLocation TEXTURE = texture("iron_sentinel");
    static final ResourceLocation GLOW = texture("iron_sentinel_glow");
    private static final RenderType GLOW_TYPE = RenderType.eyes(GLOW);

    public IronSentinelRenderer(EntityRendererProvider.Context context) {
        super(context, new IronSentinelModel(), 1.0F);
        addLayer(new GlowLayer(this));
    }

    static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(FiercerBattles.MOD_ID, "textures/entity/iron_sentinel/" + name + ".png");
    }

    @Override
    public ResourceLocation getTextureLocation(IronSentinel sentinel) { return TEXTURE; }

    @Override
    protected void scale(IronSentinel sentinel, PoseStack poses, float partialTick) {
        poses.scale(IronSentinel.RENDER_SCALE, IronSentinel.RENDER_SCALE, IronSentinel.RENDER_SCALE);
    }

    /** The furnace and eyes burn out as it falls over. */
    private static final class GlowLayer extends RenderLayer<IronSentinel, IronSentinelModel> {
        GlowLayer(RenderLayerParent<IronSentinel, IronSentinelModel> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack poses, MultiBufferSource buffers, int light, IronSentinel sentinel, float limbSwing,
                           float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            float glow = sentinel.isDeadOrDying() ? Mth.clamp(1.0F - (sentinel.deathTime + partialTick) / 20.0F, 0.0F, 1.0F) : 1.0F;
            if (glow <= 0.0F) return;
            int channel = (int) (glow * 255);
            getParentModel().renderToBuffer(poses, buffers.getBuffer(GLOW_TYPE), LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY, FastColor.ARGB32.color(255, channel, channel, channel));
        }
    }
}
