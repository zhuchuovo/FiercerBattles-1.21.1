package com.fiercerbattles.mobcombat.mixin.client.embedded;

import com.fiercerbattles.mobcombat.internal.mobanim.EmbeddedPlayerAnimator;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import net.minecraft.client.model.AnimationUtils;
import net.minecraft.client.model.PiglinModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps piglin-specific arm resets from replacing active Better Combat animations. */
@Mixin(value = PiglinModel.class, priority = 2000)
public abstract class PiglinModelMixin<T extends Mob> {
    @WrapWithCondition(
            method = "setupAnim",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/PiglinModel;holdWeaponHigh(Lnet/minecraft/world/entity/Mob;)V"
            )
    )
    private boolean bmc$onlyHoldWeaponHighWhenIdle(PiglinModel<T> model, T piglin) {
        return !EmbeddedPlayerAnimator.isArmAnimating(piglin);
    }

    @WrapWithCondition(
            method = "setupAttackAnimation",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/AnimationUtils;swingWeaponDown(Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/world/entity/Mob;FF)V"
            )
    )
    private boolean bmc$onlySwingWeaponDownWhenIdle(
            ModelPart rightArm,
            ModelPart leftArm,
            Mob mob,
            float attackTime,
            float ageInTicks
    ) {
        return !EmbeddedPlayerAnimator.isArmAnimating(mob);
    }

    @WrapWithCondition(
            method = "setupAnim",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/AnimationUtils;animateZombieArms(Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;ZFF)V"
            )
    )
    private boolean bmc$onlyAnimateZombieArmsWhenIdle(
            ModelPart leftArm,
            ModelPart rightArm,
            boolean aggressive,
            float attackTime,
            float ageInTicks,
            T piglin,
            float limbSwing,
            float limbSwingAmount,
            float entityAge,
            float netHeadYaw,
            float headPitch
    ) {
        return !EmbeddedPlayerAnimator.isArmAnimating(piglin);
    }

    @WrapWithCondition(
            method = "setupAnim",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/geom/ModelPart;loadPose(Lnet/minecraft/client/model/geom/PartPose;)V"
            )
    )
    private boolean bmc$onlyLoadDefaultPoseWhenIdle(
            ModelPart part,
            PartPose pose,
            T piglin,
            float limbSwing,
            float limbSwingAmount,
            float entityAge,
            float netHeadYaw,
            float headPitch
    ) {
        return !EmbeddedPlayerAnimator.isArmAnimating(piglin);
    }
}
