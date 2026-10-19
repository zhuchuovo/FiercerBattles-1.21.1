package com.fiercerbattles.mobcombat.mixin.client.embedded;

import com.fiercerbattles.mobcombat.api.MobAnimationAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.monster.SpellcasterIllager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Ported from the original 1.20.1 mod. EvokerRenderer's anonymous ItemInHandLayer subclass only
 * draws the held item while {@code SpellcasterIllager.isCastingSpell()} is true - a check that
 * lives entirely outside IllagerModel and knows nothing about a Better Combat attack. Force it
 * true whenever our attack animation is actually playing so the weapon never vanishes mid-swing.
 */
@Mixin(targets = "net/minecraft/client/renderer/entity/EvokerRenderer$1")
public abstract class ItemInHandLayerMixin_EvokerRenderer
        extends ItemInHandLayer<SpellcasterIllager, IllagerModel<SpellcasterIllager>> {

    public ItemInHandLayerMixin_EvokerRenderer(
            RenderLayerParent<SpellcasterIllager, IllagerModel<SpellcasterIllager>> parent,
            ItemInHandRenderer itemInHandRenderer
    ) {
        super(parent, itemInHandRenderer);
    }

    @WrapOperation(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/monster/SpellcasterIllager;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/SpellcasterIllager;isCastingSpell()Z")
    )
    private boolean bmc$forceCastingSpellWhileAttacking(SpellcasterIllager instance, Operation<Boolean> original) {
        if (((MobAnimationAccess) instance).bmc$isAttackAnimationActive()) {
            return true;
        }
        return original.call(instance);
    }
}
