package com.fiercerbattles.mobcombat.mixin.client.embedded;

import com.fiercerbattles.mobcombat.api.MobAnimationAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.monster.Vindicator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Ported from the original 1.20.1 mod. VindicatorRenderer's anonymous ItemInHandLayer subclass
 * only draws the held item while {@code Vindicator.isAggressive()} is true - a check that lives
 * entirely outside IllagerModel and knows nothing about a Better Combat attack. Force it true
 * whenever Better Combat owns the arms so the axe remains aligned through the complete swing,
 * fade, and return-to-crossed-arms handoff.
 */
@Mixin(targets = "net/minecraft/client/renderer/entity/VindicatorRenderer$1")
public abstract class ItemInHandLayerMixin_VindicatorRenderer
        extends ItemInHandLayer<Vindicator, IllagerModel<Vindicator>> {

    public ItemInHandLayerMixin_VindicatorRenderer(
            RenderLayerParent<Vindicator, IllagerModel<Vindicator>> parent,
            ItemInHandRenderer itemInHandRenderer
    ) {
        super(parent, itemInHandRenderer);
    }

    @WrapOperation(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/monster/Vindicator;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/Vindicator;isAggressive()Z")
    )
    private boolean bmc$forceAggressiveWhileAttacking(Vindicator instance, Operation<Boolean> original) {
        if (((MobAnimationAccess) instance).bmc$shouldForceAttackItemVisible()) {
            return true;
        }
        return original.call(instance);
    }
}
