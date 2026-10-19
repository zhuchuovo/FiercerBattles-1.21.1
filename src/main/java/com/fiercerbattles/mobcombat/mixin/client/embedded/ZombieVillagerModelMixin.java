package com.fiercerbattles.mobcombat.mixin.client.embedded;

import com.fiercerbattles.mobcombat.internal.mobanim.EmbeddedPlayerAnimator;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.ZombieVillagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.monster.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Stops zombie-villager arm logic from overwriting the animated pose. */
@Mixin(value = ZombieVillagerModel.class, priority = 2000)
public abstract class ZombieVillagerModelMixin<T extends Zombie> extends HumanoidModel<T> {
    protected ZombieVillagerModelMixin(ModelPart root) {
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