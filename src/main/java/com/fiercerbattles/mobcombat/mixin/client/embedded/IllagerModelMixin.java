package com.fiercerbattles.mobcombat.mixin.client.embedded;

import com.fiercerbattles.mobcombat.internal.mobanim.EmbeddedPlayerAnimator;
import com.fiercerbattles.mobcombat.internal.mobanim.FirstPersonTracker;
import com.fiercerbattles.mobcombat.internal.mobanim.HumanoidBodyPose;
import com.fiercerbattles.mobcombat.internal.mobanim.IllagerModelAccess;
import com.fiercerbattles.mobcombat.internal.mobanim.IllagerArmOwnership;
import com.fiercerbattles.mobcombat.internal.mobanim.OptionalEmfCompat;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kosmx.playerAnim.core.impl.AnimationProcessor;
import dev.kosmx.playerAnim.core.util.SetableSupplier;
import dev.kosmx.playerAnim.impl.IMutableModel;
import dev.kosmx.playerAnim.impl.IPlayerModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Vindicator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Function;

/** Embedded Mob Player Animator support for pillagers, vindicators and other illagers. */
@Mixin(value = IllagerModel.class, priority = 2000)
public abstract class IllagerModelMixin<T extends AbstractIllager> extends HierarchicalModelMixin<T>
        implements IPlayerModel, IMutableModel, IllagerModelAccess, FirstPersonTracker {

    @Shadow @Final private ModelPart head;
    @Shadow @Final private ModelPart hat;
    @Shadow @Final private ModelPart leftArm;
    @Shadow @Final private ModelPart rightArm;
    @Shadow @Final private ModelPart rightLeg;
    @Shadow @Final private ModelPart leftLeg;
    @Shadow @Final private ModelPart arms;

    @Unique
    private ModelPart bmc$body;

    @Unique
    private HumanoidBodyPose bmc$initialBodyPose;

    @Unique
    private SetableSupplier<AnimationProcessor> bmc$animation = new SetableSupplier<>();

    @Unique
    private final SetableSupplier<AnimationProcessor> bmc$ownedSupplier = new SetableSupplier<>();

    @Unique
    private boolean bmc$firstPersonNext;

    protected IllagerModelMixin(Function<ResourceLocation, RenderType> renderType) {
        super(renderType);
    }

    @Shadow
    public abstract ModelPart getHead();

    @Shadow
    public abstract ModelPart getHat();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bmc$initializeIllagerModel(ModelPart root, CallbackInfo ci) {
        this.bmc$body = root.getChild("body");
        EmbeddedPlayerAnimator.initializeIllagerBends(root, this);
        EmbeddedPlayerAnimator.initializeSupplier(this, this.bmc$ownedSupplier);
        this.bmc$initialBodyPose = new HumanoidBodyPose(
                this.head,
                this.bmc$body,
                this.leftArm,
                this.rightArm,
                this.leftLeg,
                this.rightLeg
        );

    }

    @Override
    protected void bmc$copyMutatedAttributes(EntityModel<T> otherModel) {
        EmbeddedPlayerAnimator.copySupplier((IMutableModel) otherModel, this.bmc$animation);
    }

    @Override
    protected boolean bmc$bendRenderToBuffer(
            PoseStack poseStack,
            VertexConsumer vertices,
            int packedLight,
            int packedOverlay,
            int color
    ) {
        // When EMF owns the model, it owns the render hierarchy even while its animations are paused.
        // This bend path
        // renders each vanilla ModelPart by hand, which under EMF draws them outside that hierarchy:
        // the legs end up at torso height / inverted, and parts get drawn twice (the visible stutter
        // during a swing). It only ever triggers while the animation processor is active, which is
        // exactly why the legs looked fine walking and broke on every swing.
        //
        // Measured pivots confirmed the pose itself is correct (vindicator legs sit at y=11.95,
        // matching the zombie's 12.0), so this is purely a rendering problem. Fall back to the
        // normal render and let EMF draw its own hierarchy.
        if (OptionalEmfCompat.isEmfModel((EntityModel<?>) (Object) this)) {
            return false;
        }

        // Note: this.hat is deliberately NOT in this list. In IllagerModel the hat is a child of
        // the head, so head.render() already draws it; listing it separately rendered it a second
        // time at head-local coordinates (i.e. floating at the body origin).
        return EmbeddedPlayerAnimator.renderIllagerWithBends(
                poseStack,
                vertices,
                packedLight,
                packedOverlay,
                color,
                this.bmc$animation,
                List.of(this.head),
                List.of(
                        this.bmc$body,
                        this.rightArm,
                        this.leftArm,
                        this.rightLeg,
                        this.leftLeg
                )
        );
    }

    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/monster/AbstractIllager;FFFFF)V",
            at = @At("HEAD")
    )
    private void bmc$restoreIllagerPivots(
            T entity,
            float limbSwing,
            float limbSwingAmount,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            CallbackInfo ci
    ) {
        EmbeddedPlayerAnimator.resetToBakedPose(this);
    }

    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/monster/AbstractIllager;FFFFF)V",
            at = @At("TAIL")
    )
    private void bmc$applyIllagerAnimation(
            T entity,
            float limbSwing,
            float limbSwingAmount,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            CallbackInfo ci
    ) {
        var animation = EmbeddedPlayerAnimator.getAnimation(entity);

        if (entity instanceof Vindicator
                && OptionalEmfCompat.isEmfModel((EntityModel<?>) (Object) this)) {
            // EMF/Fresh Animations owns the complete live Vindicator hierarchy. Keep Player
            // Animator's supplier active for held-item/two-handed transforms, but do not write
            // head, torso, leg, pivot, position, or scale channels into the EMF facade here.
            // OptionalEmfCompat overlays the authored arm/torso rotations after FA animates.
            EmbeddedPlayerAnimator.applyArmsOnlyKeepingSupplier(this, animation);
            return;
        }

        EmbeddedPlayerAnimator.applyToModel(this, animation);
    }

    /**
     * Ported from the original 1.20.1 mod. Vanilla IllagerModel computes a local {@code
     * useCrossedArms} boolean (from {@code entity.isAggressive()}/spellcasting checks) and uses it
     * to swap between the crossed-arms "arms" part and the individual leftArm/rightArm parts -
     * hiding whatever the mob is holding whenever that boolean is true. Force it false while our
     * arm animation is active so two-handed idle grips and attacks both keep the individual arms
     * and held weapon visible.
     */
    @ModifyVariable(
            method = "setupAnim(Lnet/minecraft/world/entity/monster/AbstractIllager;FFFFF)V",
            at = @At(value = "STORE", ordinal = 0),
            ordinal = 0
    )
    private boolean bmc$modifyUseCrossedArms(
            boolean useCrossedArms,
            T illager,
            float limbSwing,
            float limbSwingAmount,
            float ageInTicks,
            float netHeadYaw,
            float headPitch
    ) {
        return IllagerArmOwnership.suppressesVanillaAttack(illager) ? false : useCrossedArms;
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
            T illager,
            float limbSwing,
            float limbSwingAmount,
            float entityAge,
            float netHeadYaw,
            float headPitch
    ) {
        // Suppress vanilla's arm swing whenever Better Combat owns an arm channel. This includes
        // the authored two-handed idle body pose; allowing this call through would immediately
        // replace that grip with IllagerModel's one-handed aggressive stance.
        return !IllagerArmOwnership.suppressesVanillaAttack(illager);
    }

    @WrapWithCondition(
            method = "setupAnim",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/AnimationUtils;swingWeaponDown(Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/world/entity/Mob;FF)V"
            )
    )
    private boolean bmc$onlySwingWeaponWhenIdle(
            ModelPart rightArm,
            ModelPart leftArm,
            Mob mob,
            float attackTime,
            float ageInTicks
    ) {
        return !IllagerArmOwnership.suppressesVanillaAttack(mob);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void setEmoteSupplier(SetableSupplier supplier) {
        this.bmc$animation = supplier;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public SetableSupplier getEmoteSupplier() {
        return this.bmc$animation;
    }

    @Override
    public void playerAnimator_prepForFirstPersonRender() {
        this.bmc$setFirstPersonNext(true);
    }

    @Override
    public ModelPart bmc$getHead() {
        return this.getHead();
    }

    @Override
    public ModelPart bmc$getHat() {
        return this.getHat();
    }

    @Override
    public ModelPart bmc$getBody() {
        return this.bmc$body;
    }

    @Override
    public ModelPart bmc$getLeftArm() {
        return this.leftArm;
    }

    @Override
    public ModelPart bmc$getRightArm() {
        return this.rightArm;
    }

    @Override
    public ModelPart bmc$getLeftLeg() {
        return this.leftLeg;
    }

    @Override
    public ModelPart bmc$getRightLeg() {
        return this.rightLeg;
    }

    @Override
    public ModelPart bmc$getCrossedArms() {
        return this.arms;
    }

    @Override
    public boolean bmc$isFirstPersonNext() {
        return this.bmc$firstPersonNext;
    }

    @Override
    public void bmc$setFirstPersonNext(boolean firstPersonNext) {
        this.bmc$firstPersonNext = firstPersonNext;
    }

    @Override
    public HumanoidBodyPose bmc$getInitialBodyPose() {
        return this.bmc$initialBodyPose;
    }
}