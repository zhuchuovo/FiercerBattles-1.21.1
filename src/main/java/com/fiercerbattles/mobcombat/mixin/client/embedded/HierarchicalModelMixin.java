package com.fiercerbattles.mobcombat.mixin.client.embedded;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

/** Bend-aware render extension for models such as IllagerModel that are not HumanoidModel. */
@Mixin(net.minecraft.client.model.HierarchicalModel.class)
public abstract class HierarchicalModelMixin<T extends Entity> extends EntityModelMixin<T> {
    protected HierarchicalModelMixin(Function<ResourceLocation, RenderType> renderType) {
        super(renderType);
    }

    @Inject(method = "renderToBuffer", at = @At("HEAD"), cancellable = true)
    private void bmc$renderEmbeddedBends(
            PoseStack poseStack,
            VertexConsumer vertices,
            int packedLight,
            int packedOverlay,
            int color,
            CallbackInfo ci
    ) {
        if (this.bmc$bendRenderToBuffer(poseStack, vertices, packedLight, packedOverlay, color)) {
            ci.cancel();
        }
    }

    @Unique
    protected boolean bmc$bendRenderToBuffer(
            PoseStack poseStack,
            VertexConsumer vertices,
            int packedLight,
            int packedOverlay,
            int color
    ) {
        return false;
    }
}
