package com.fiercerbattles.mobcombat.mixin.client.embedded;

import com.fiercerbattles.mobcombat.api.MobAnimationAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.monster.Illusioner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Ported from the original 1.20.1 mod. IllusionerRenderer's anonymous ItemInHandLayer subclass
 * only draws the held item while {@code Illusioner.isCastingSpell()} or {@code isAggressive()} is
 * true - checks that live entirely outside IllagerModel and know nothing about a Better Combat
 * attack. Force them true whenever our attack animation is actually playing so the weapon never
 * vanishes mid-swing.
 */
@Mixin(targets = "net/minecraft/client/renderer/entity/IllusionerRenderer$1")
public abstract class ItemInHandLayerMixin_IllusionerRenderer
        extends ItemInHandLayer<Illusioner, IllagerModel<Illusioner>> {

    public ItemInHandLayerMixin_IllusionerRenderer(
            RenderLayerParent<Illusioner, IllagerModel<Illusioner>> parent,
            ItemInHandRenderer itemInHandRenderer
    ) {
        super(parent, itemInHandRenderer);
    }

    @WrapOperation(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/monster/Illusioner;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/Illusioner;isCastingSpell()Z")
    )
    private boolean bmc$forceCastingSpellWhileAttacking(Illusioner instance, Operation<Boolean> original) {
        if (((MobAnimationAccess) instance).bmc$isAttackAnimationActive()) {
            return true;
        }
        return original.call(instance);
    }

    @WrapOperation(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/monster/Illusioner;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/Illusioner;isAggressive()Z")
    )
    private boolean bmc$forceAggressiveWhileAttacking(Illusioner instance, Operation<Boolean> original) {
        if (((MobAnimationAccess) instance).bmc$isAttackAnimationActive()) {
            return true;
        }
        return original.call(instance);
    }
}
