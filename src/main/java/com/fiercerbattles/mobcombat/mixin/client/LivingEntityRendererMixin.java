package com.fiercerbattles.mobcombat.mixin.client;

import com.fiercerbattles.mobcombat.BetterMobCombatReimagined;
import com.fiercerbattles.mobcombat.api.MobAnimationAccess;
import com.fiercerbattles.mobcombat.client.RangedMobAnimator;
import com.fiercerbattles.mobcombat.config.BMCConfig;
import com.fiercerbattles.mobcombat.internal.mobanim.EmbeddedPlayerAnimator;
import com.fiercerbattles.mobcombat.internal.mobanim.GenericHumanoidModelCompat;
import com.fiercerbattles.mobcombat.internal.mobanim.HumanoidModelAccess;
import com.fiercerbattles.mobcombat.internal.mobanim.OptionalEmfCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

@Mixin(value = LivingEntityRenderer.class, priority = 2000)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, M extends EntityModel<T>> {
    @Unique
    private static final Set<String> BMC$RENDER_DIAGNOSTICS = new HashSet<>();

    /**
     * DEBUG_LOGGING lives in the SERVER config, which NeoForge only loads inside a world.
     * Entities rendered outside one (title screen, FancyMenu / Fancy Entity Renderer player
     * previews, other menus) would otherwise throw "Cannot get config value before config
     * is loaded". Outside a world, debug logging simply counts as off.
     */
    @Unique
    private static boolean bmc$debugLogging() {
        try {
            return BMCConfig.DEBUG_LOGGING.get();
        } catch (IllegalStateException notLoadedYet) {
            return false;
        }
    }

    @Shadow
    protected M model;

    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
                    shift = At.Shift.AFTER
            )
    )
    private void bmc$applyRangedMobPoseAfterModelSetup(
            T entity,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            CallbackInfo ci
    ) {
        if (!(entity instanceof MobAnimationAccess animatedMob)) {
            return;
        }

        animatedMob.bmc$setRenderPartialTick(partialTick);

        if (animatedMob.bmc$isArmAnimationActive()
                && bmc$debugLogging()) {
            String diagnosticKey = entity.getType() + "|" + this.model.getClass().getName()
                    + "|" + animatedMob.bmc$isTwoHandedArmAnimationActive();

            if (BMC$RENDER_DIAGNOSTICS.add(diagnosticKey)) {
                BetterMobCombatReimagined.LOGGER.info(
                        "[BMC render diagnostic] mob={} model={} stackActive={} armOwned={} "
                                + "twoHandedArmOwned={} detectedParts={}",
                        entity.getType(),
                        this.model.getClass().getName(),
                        EmbeddedPlayerAnimator.isAnimating(entity),
                        animatedMob.bmc$isArmAnimationActive(),
                        animatedMob.bmc$isTwoHandedArmAnimationActive(),
                        EmbeddedPlayerAnimator.getCurrentlyAnimatedParts(entity)
                );
            }
        }

        if (this.model instanceof HumanoidModel<?> humanoidModel) {
            float headXRotBefore = humanoidModel.head.xRot;
            float headYRotBefore = humanoidModel.head.yRot;
            float headZRotBefore = humanoidModel.head.zRot;

            RangedMobAnimator.apply(entity, humanoidModel, partialTick);
            if (humanoidModel.hat != humanoidModel.head) {
                humanoidModel.hat.xRot += humanoidModel.head.xRot - headXRotBefore;
                humanoidModel.hat.yRot += humanoidModel.head.yRot - headYRotBefore;
                humanoidModel.hat.zRot += humanoidModel.head.zRot - headZRotBefore;
            }
        }
    }

    @Inject(
            method = "setupRotations(Lnet/minecraft/world/entity/LivingEntity;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V",
            at = @At("RETURN")
    )
    private void bmc$applyEmbeddedMobRootTransform(
            T entity,
            PoseStack poseStack,
            float ageInTicks,
            float bodyYaw,
            float partialTick,
            float scale,
            CallbackInfo ci
    ) {

        if (entity instanceof Mob
                && !entity.isBaby()
                && !(this.model instanceof IllagerModel<?>)
                && GenericHumanoidModelCompat.supportsModel(this.model)) {
            EmbeddedPlayerAnimator.applyBodyTransform(
                    EmbeddedPlayerAnimator.getAnimation(entity),
                    poseStack,
                    partialTick
            );
        }
    }


    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD")
    )
    private void bmc$pauseFreshAnimationsBeforeSetupAnim(
            T entity,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            CallbackInfo ci
    ) {
        if (entity instanceof MobAnimationAccess animatedMob) {
            animatedMob.bmc$setRenderPartialTick(partialTick);
        }
        OptionalEmfCompat.pause(entity, this.model);
    }


    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void bmc$applyGenericModelBeforeBaseModel(
            T entity,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            CallbackInfo ci
    ) {
        OptionalEmfCompat.refreshCurrentRenderModel(entity, this.model);
        OptionalEmfCompat.reapplyArmsBeforeBaseModel(entity, this.model);
        GenericHumanoidModelCompat.apply(entity, this.model, partialTick);
        bmc$logLegsOnce(entity);
    }

    @Unique
    private void bmc$logLegsOnce(T entity) {
        // Cheap checks first: most rendered entities bail out here.
        if (!(this.model instanceof HumanoidModelAccess access)) {
            return;
        }
        if (!EmbeddedPlayerAnimator.isAnimating(entity)) {
            return;
        }
        if (!bmc$debugLogging()) {
            return;
        }
        String key = "legs|" + entity.getType();
        if (!BMC$RENDER_DIAGNOSTICS.add(key)) {
            return;
        }
        var leftLeg = access.bmc$getLeftLeg();
        var rightLeg = access.bmc$getRightLeg();
        var body = access.bmc$getBody();
        BetterMobCombatReimagined.LOGGER.warn(
                "[BMC-LEGS] {} model={} isIllagerModel={} | leftLeg pivot=({},{},{}) rot=({},{},{})"
                        + " | rightLeg pivot=({},{},{}) rot=({},{},{})"
                        + " | body pivot=({},{},{})",
                entity.getType(),
                this.model.getClass().getSimpleName(),
                this.model instanceof IllagerModel<?>,
                leftLeg.x, leftLeg.y, leftLeg.z, leftLeg.xRot, leftLeg.yRot, leftLeg.zRot,
                rightLeg.x, rightLeg.y, rightLeg.z, rightLeg.xRot, rightLeg.yRot, rightLeg.zRot,
                body.x, body.y, body.z
        );
    }

    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("TAIL")
    )
    private void bmc$resumeFreshAnimationsAfterAllRenderLayers(
            T entity,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            CallbackInfo ci
    ) {
        GenericHumanoidModelCompat.restore(entity);
        OptionalEmfCompat.resume(entity);
    }

}