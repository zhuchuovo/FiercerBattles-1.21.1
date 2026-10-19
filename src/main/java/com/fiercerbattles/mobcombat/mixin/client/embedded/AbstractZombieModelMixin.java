package com.fiercerbattles.mobcombat.mixin.client.embedded;

import com.fiercerbattles.mobcombat.internal.mobanim.EmbeddedPlayerAnimator;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import net.minecraft.client.model.AbstractZombieModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.monster.Monster;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Stops vanilla zombie-arm animation from replacing the Better Combat pose. */
@Mixin(value = AbstractZombieModel.class, priority = 2000)
public abstract class AbstractZombieModelMixin<T extends Monster> extends HumanoidModel<T> {
    protected AbstractZombieModelMixin(ModelPart root) {
        super(root);
    }

    @WrapWithCondition(
            method = "setupAnim",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/AnimationUtils;animateZombieArms(Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;ZFF)V"
            )
    )
    private boolean bmc$onlyRunVanillaArmsWhenIdle(
            ModelPart leftArm,
            ModelPart rightArm,
            boolean aggressive,
            float attackTime,
            float ageInTicks,
            T zombie,
            float limbSwing,
            float limbSwingAmount,
            float entityAge,
            float netHeadYaw,
            float headPitch
    ) {
        return !EmbeddedPlayerAnimator.isArmAnimating(zombie);
    }
}