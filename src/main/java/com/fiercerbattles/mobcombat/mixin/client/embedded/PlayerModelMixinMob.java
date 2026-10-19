package com.fiercerbattles.mobcombat.mixin.client.embedded;

import com.fiercerbattles.mobcombat.internal.mobanim.EmbeddedPlayerAnimator;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PlayerModel is also used by piglins and some modded humanoids. Player Animator handles actual
 * players itself; this hook feeds the same model pipeline when the rendered entity is a mob.
 */
@Mixin(value = PlayerModel.class, priority = 2000)
public abstract class PlayerModelMixinMob<T extends LivingEntity> extends HumanoidModelMixin<T> {
    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/geom/ModelPart;copyFrom(Lnet/minecraft/client/model/geom/ModelPart;)V",
                    ordinal = 0
            )
    )
    private void bmc$applyAnimationToMobPlayerModel(
            T entity,
            float limbSwing,
            float limbSwingAmount,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            CallbackInfo ci
    ) {
        if (!(entity instanceof AbstractClientPlayer)) {
            EmbeddedPlayerAnimator.applyToModel(this, EmbeddedPlayerAnimator.getAnimation(entity));
        }
    }
}
