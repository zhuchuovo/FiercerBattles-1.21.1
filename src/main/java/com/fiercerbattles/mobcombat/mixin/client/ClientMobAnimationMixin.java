package com.fiercerbattles.mobcombat.mixin.client;

import com.fiercerbattles.mobcombat.BetterMobCombatReimagined;
import com.fiercerbattles.mobcombat.api.MobAnimationAccess;
import com.fiercerbattles.mobcombat.config.BMCConfig;
import com.fiercerbattles.mobcombat.logic.MobAttackSelector;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.AnimationStack;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import dev.kosmx.playerAnim.impl.animation.AnimationApplier;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import com.fiercerbattles.bettercombat.api.WeaponAttributes;
import com.fiercerbattles.bettercombat.client.animation.AttackAnimationSubStack;
import com.fiercerbattles.bettercombat.client.animation.CustomAnimationPlayer;
import com.fiercerbattles.bettercombat.client.animation.PoseSubStack;
import com.fiercerbattles.bettercombat.client.animation.modifier.TransmissionSpeedModifier;
import com.fiercerbattles.bettercombat.logic.WeaponRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Owns the embedded Mob Player Animator stack and adds Better Combat's pose and attack layers.
 * Implementing Player Animator's IAnimatedPlayer contract is the critical part that lets its
 * existing HumanoidModel armor/bend pipeline treat mobs exactly like animated players.
 *
 * <p>The lower-priority pose layers mirror Better Combat's player idle-pose system. This is what
 * gives two-handed swords, bows, crossbows and other attributed weapons their intended stance
 * between attacks. The attack layer remains at priority 2000 so swings always override the pose.</p>
 */
@Mixin(Mob.class)
public abstract class ClientMobAnimationMixin extends LivingEntity implements MobAnimationAccess, IAnimatedPlayer {
    @Unique
    private static final Set<ResourceLocation> BMC$MISSING_ANIMATIONS = new HashSet<>();

    @Unique
    private static final Set<String> BMC$POSE_DIAGNOSTICS = new HashSet<>();

    @Unique
    private static final Set<String> BMC$ATTACK_DIAGNOSTICS = new HashSet<>();

    /** Embedded Mob Player Animator storage. */
    @Unique
    private final Map<ResourceLocation, IAnimation> bmc$associatedAnimations = new HashMap<>();

    @Unique
    private final AnimationStack bmc$animationStack = new AnimationStack();

    @Unique
    private final AnimationApplier bmc$animationApplier = new AnimationApplier(this.bmc$animationStack);

    @Unique
    private final PoseSubStack bmc$mainHandItemPose = new PoseSubStack(null, false, true);

    @Unique
    private final PoseSubStack bmc$mainHandBodyPose = new PoseSubStack(null, true, true);

    @Unique
    private final PoseSubStack bmc$offHandItemPose = new PoseSubStack(null, false, true);

    @Unique
    private final PoseSubStack bmc$offHandBodyPose = new PoseSubStack(null, true, false);

    @Unique
    private final AttackAnimationSubStack bmc$attackAnimation =
            new AttackAnimationSubStack(new AdjustmentModifier(this::bmc$attackAdjustment));

    @Unique
    private float bmc$renderPartialTick;

    /** Exact client render lifetime for the current attack, independent of Player Animator fade state. */
    @Unique
    private int bmc$attackVisualTicks;

    /** True only while the synchronized attack uses Better Combat's two-handed animation path. */
    @Unique
    private boolean bmc$twoHandedAttack;

    /** Exact logical hand for the active attack; required by generic/custom humanoid models. */
    @Unique
    private boolean bmc$offHandAttack;

    /**
     * Deterministic ownership flag for Better Combat's idle body-pose arm channels. Do not infer
     * this by reflectively walking Player Animator's modifier tree: fades and wrapper layers can
     * temporarily hide the underlying keyframe player even though the authored grip is active.
     */
    @Unique
    private boolean bmc$weaponBodyPoseActive;

    /** True while the active idle body pose comes from a two-handed main-hand preset. */
    @Unique
    private boolean bmc$twoHandedWeaponPoseActive;

    protected ClientMobAnimationMixin(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void bmc$initializeMobAnimationStack(EntityType<?> type, Level level, CallbackInfo ci) {
        if (level.isClientSide) {
            this.bmc$animationStack.addAnimLayer(1, this.bmc$offHandItemPose.base);
            this.bmc$animationStack.addAnimLayer(2, this.bmc$offHandBodyPose.base);
            this.bmc$animationStack.addAnimLayer(3, this.bmc$mainHandItemPose.base);
            this.bmc$animationStack.addAnimLayer(4, this.bmc$mainHandBodyPose.base);
            this.bmc$animationStack.addAnimLayer(2000, this.bmc$attackAnimation.base);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void bmc$tickMobAnimationStack(CallbackInfo ci) {
        if (this.level().isClientSide) {
            this.bmc$animationStack.tick();
            if (this.bmc$attackVisualTicks > 0) {
                this.bmc$attackVisualTicks--;
                if (this.bmc$attackVisualTicks == 0) {
                    this.bmc$twoHandedAttack = false;
                    this.bmc$offHandAttack = false;
                }
            }
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void bmc$refreshMobWeaponPoses(CallbackInfo ci) {
        if (this.level().isClientSide) {
            this.bmc$updateWeaponPoses();
        }
    }

    @Override
    public AnimationStack bmc$getAnimationStack() {
        return this.bmc$animationStack;
    }

    @Override
    public AnimationStack getAnimationStack() {
        return this.bmc$animationStack;
    }

    @Override
    public AnimationApplier playerAnimator_getAnimation() {
        return this.bmc$animationApplier;
    }

    @Override
    public @Nullable IAnimation playerAnimator_getAnimation(@NotNull ResourceLocation id) {
        return this.bmc$associatedAnimations.get(id);
    }

    @Override
    public @Nullable IAnimation playerAnimator_setAnimation(
            @NotNull ResourceLocation id,
            @Nullable IAnimation animation
    ) {
        return animation == null
                ? this.bmc$associatedAnimations.remove(id)
                : this.bmc$associatedAnimations.put(id, animation);
    }

    @Override
    public float bmc$getRenderPartialTick() {
        return this.bmc$renderPartialTick;
    }

    @Override
    public void bmc$setRenderPartialTick(float partialTick) {
        this.bmc$renderPartialTick = partialTick;
    }

    @Override
    public boolean bmc$isAttackAnimationActive() {
        // Use the synchronized packet lifetime as the authoritative render gate. Player Animator's
        // isActive() can briefly report false while a replacement/fade modifier changes state,
        // which caused EMF Vindicators to randomly lose an individual swing frame.
        return this.bmc$attackVisualTicks > 0
                && this.bmc$attackAnimation.base.getAnimation() != null;
    }

    @Override
    public boolean bmc$isArmAnimationActive() {
        // Every Better Combat attack animation owns its arm channels. Between attacks, only body
        // pose layers own arms; item-pose layers affect held-item transforms without replacing the
        // vanilla limb pose. This explicit state keeps two-handed grips stable across subclass
        // model passes and across Player Animator fade/modifier transitions.
        return this.bmc$isAttackAnimationActive() || this.bmc$weaponBodyPoseActive;
    }

    @Override
    public boolean bmc$isTwoHandedArmAnimationActive() {
        return (this.bmc$isAttackAnimationActive() && this.bmc$twoHandedAttack)
                || this.bmc$twoHandedWeaponPoseActive;
    }

    @Override
    public boolean bmc$isOffHandAttackAnimationActive() {
        return this.bmc$isAttackAnimationActive() && this.bmc$offHandAttack;
    }

    @Override
    public boolean bmc$shouldForceAttackItemVisible() {
        // Keep conditional illager held-item rendering synchronized with arm ownership. Releasing
        // the axe a few ticks before the Player Animator layer yielded made Fresh Animations close
        // the hands around empty space, then snap the crossed-arm node back independently. The item
        // now disappears on the same frame Better Mob Combat actually gives the arms back.
        return this.bmc$isArmAnimationActive();
    }

    @Override
    public void bmc$stopAttackAnimation() {
        if (!this.level().isClientSide || this.bmc$attackAnimation.base.getAnimation() == null) {
            return;
        }
        // The server cancelled this swing; never finish a hit that will not land.
        this.bmc$attackAnimation.base.replaceAnimationWithFade(
                AbstractFadeModifier.standardFadeIn(2, Ease.INOUTSINE),
                null
        );
        this.bmc$attackVisualTicks = 0;
        this.bmc$twoHandedAttack = false;
        this.bmc$offHandAttack = false;
    }

    @Override
    public void bmc$playAttackAnimation(
            String animationId,
            boolean offHand,
            boolean twoHanded,
            float length,
            float animationUpswing,
            float damageUpswing
    ) {
        if (!this.level().isClientSide) {
            return;
        }

        ResourceLocation id = ResourceLocation.tryParse(animationId);
        if (id == null) {
            BetterMobCombatReimagined.LOGGER.warn("Ignoring invalid Better Combat animation id '{}'", animationId);
            return;
        }

        KeyframeAnimation animation = this.bmc$getKeyframeAnimation(id, "attack");
        if (animation == null) {
            return;
        }

        String diagnosticKey = id + "|" + offHand + "|" + twoHanded;

        if (BMCConfig.DEBUG_LOGGING.get()
                && BMC$ATTACK_DIAGNOSTICS.add(diagnosticKey)) {
            BetterMobCombatReimagined.LOGGER.info(
                    "[BMC attack receive diagnostic] mob={} animation={} animationFound=true "
                            + "offHand={} twoHanded={} packetLengthTicks={} sourceEndTick={}",
                    ((Mob) (Object) this).getType(),
                    id,
                    offHand,
                    twoHanded,
                    length,
                    animation.endTick
            );
        }

        try {
            this.bmc$clearWeaponPoses(((Mob) (Object) this).isLeftHanded());
            this.bmc$twoHandedAttack = twoHanded;
            this.bmc$offHandAttack = offHand;

            KeyframeAnimation.AnimationBuilder copy = animation.mutableCopy();
            copy.head.pitch.setEnabled(false);

            float safeLength = Math.max(1.0F, length);
            this.bmc$attackVisualTicks = Math.max(1, Mth.ceil(safeLength));
            float sourceUpswing = Mth.clamp(animationUpswing, 0.01F, 0.99F);
            float targetUpswing = Mth.clamp(damageUpswing, 0.01F, 0.99F);
            float nudgeTicks = BMCConfig.CLIENT_HIT_TIMING_NUDGE_TICKS.get().floatValue();
            if (nudgeTicks != 0.0F) {
                targetUpswing = Mth.clamp(targetUpswing + nudgeTicks / safeLength, 0.01F, 0.99F);
            }

            float baseSpeed = Math.max(0.01F, (float) animation.endTick / safeLength);
            float upswingSpeed = Math.max(0.01F, baseSpeed * sourceUpswing / targetUpswing);
            float downwindSpeed = Math.max(0.01F, baseSpeed * (1.0F - sourceUpswing) / (1.0F - targetUpswing));
            this.bmc$attackAnimation.speed.set(
                    upswingSpeed,
                    List.of(
                            new TransmissionSpeedModifier.Gear(safeLength * targetUpswing, downwindSpeed),
                            new TransmissionSpeedModifier.Gear(safeLength, baseSpeed)
                    )
            );

            boolean mirror = !twoHanded
                    && (offHand ^ ((Mob) (Object) this).isLeftHanded());
            this.bmc$attackAnimation.mirror.setEnabled(mirror);

            CustomAnimationPlayer player = new CustomAnimationPlayer(copy.build(), 0);
            player.setFirstPersonMode(FirstPersonMode.NONE);
            int fadeIn = Mth.clamp(copy.beginTick, 1, 2);
            this.bmc$attackAnimation.base.replaceAnimationWithFade(
                    AbstractFadeModifier.standardFadeIn(fadeIn, Ease.INOUTSINE),
                    player
            );
        } catch (RuntimeException exception) {
            BetterMobCombatReimagined.LOGGER.error("Failed to play mob attack animation '{}'", id, exception);
        }
    }

    @Unique
    private void bmc$updateWeaponPoses() {
        Mob mob = (Mob) (Object) this;
        boolean leftHanded = mob.isLeftHanded();

        if (!BMCConfig.ENABLED.get()
                || !BMCConfig.ENABLE_WEAPON_IDLE_POSES.get()
                || BMCConfig.isBlacklisted(mob.getType())) {
            this.bmc$clearWeaponPoses(leftHanded);
            return;
        }

        ItemStack mainHand = this.getMainHandItem();
        ItemStack offHand = this.getOffhandItem();
        if (this.bmc$isAttackAnimationActive()
                || this.swinging
                || this.isSwimming()
                || this.isUsingItem()
                || this.isFallFlying()
                || CrossbowItem.isCharged(mainHand)
                || CrossbowItem.isCharged(offHand)
                || ((mainHand.getItem() instanceof ProjectileWeaponItem
                || offHand.getItem() instanceof ProjectileWeaponItem) && mob.isAggressive())) {
            this.bmc$clearWeaponPoses(leftHanded);
            return;
        }

        WeaponAttributes mainAttributes = WeaponRegistry.getAttributes(mainHand);
        boolean twoHanded = mainAttributes != null && mainAttributes.isTwoHanded();
        boolean dualWielding = MobAttackSelector.isDualWielding(this);

        String resolvedPoseId = mainAttributes == null ? null : mainAttributes.pose();
        KeyframeAnimation mainPose = this.bmc$getPose(resolvedPoseId);


        if (!mainHand.isEmpty() && BMCConfig.DEBUG_LOGGING.get()) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(mainHand.getItem());
            String diagnosticKey = itemId + "|" + resolvedPoseId + "|" + twoHanded + "|" + (mainPose != null);
            if (BMC$POSE_DIAGNOSTICS.add(diagnosticKey)) {
                BetterMobCombatReimagined.LOGGER.info(
                        "[BMC pose diagnostic] mob={} item={} attributesFound={} resolvedPose={} twoHanded={} animationFound={}",
                        mob.getType(),
                        itemId,
                        mainAttributes != null,
                        resolvedPoseId,
                        twoHanded,
                        mainPose != null
                );
            }
        }

        KeyframeAnimation offHandPose = null;
        if (!twoHanded && dualWielding) {
            WeaponAttributes offHandAttributes = WeaponRegistry.getAttributes(offHand);
            offHandPose = this.bmc$getPose(
                    offHandAttributes == null ? null : offHandAttributes.offHandPose()
            );
        }

        boolean mirrorMainPose = !twoHanded && leftHanded;
        this.bmc$mainHandItemPose.setPose(mainPose, mirrorMainPose);
        this.bmc$offHandItemPose.setPose(offHandPose, leftHanded);

        KeyframeAnimation mainBodyPose = mainPose;
        KeyframeAnimation offHandBodyPose = offHandPose;
        boolean moving = this.getDeltaMovement().horizontalDistanceSqr() > 0.0009D;
        if (!twoHanded && (moving || this.isShiftKeyDown())) {
            mainBodyPose = null;
            offHandBodyPose = null;
        }

        this.bmc$mainHandBodyPose.setPose(mainBodyPose, mirrorMainPose);
        this.bmc$offHandBodyPose.setPose(offHandBodyPose, leftHanded);

        this.bmc$weaponBodyPoseActive = mainBodyPose != null || offHandBodyPose != null;
        this.bmc$twoHandedWeaponPoseActive = twoHanded && mainBodyPose != null;
    }

    @Unique
    private void bmc$clearWeaponPoses(boolean leftHanded) {
        this.bmc$weaponBodyPoseActive = false;
        this.bmc$twoHandedWeaponPoseActive = false;
        this.bmc$mainHandItemPose.setPose(null, leftHanded);
        this.bmc$mainHandBodyPose.setPose(null, leftHanded);
        this.bmc$offHandItemPose.setPose(null, leftHanded);
        this.bmc$offHandBodyPose.setPose(null, leftHanded);
    }

    @Unique
    private KeyframeAnimation bmc$getPose(String animationId) {
        if (animationId == null || animationId.isBlank()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(animationId);
        return id == null ? null : this.bmc$getKeyframeAnimation(id, "idle pose");
    }

    @Unique
    private KeyframeAnimation bmc$getKeyframeAnimation(ResourceLocation id, String purpose) {
        var playable = PlayerAnimationRegistry.getAnimation(id);
        if (playable == null) {
            if (BMC$MISSING_ANIMATIONS.add(id)) {
                BetterMobCombatReimagined.LOGGER.warn(
                        "Player Animator could not find Better Combat mob {} animation '{}'",
                        purpose,
                        id
                );
            }
            return null;
        }

        if (!(playable instanceof KeyframeAnimation animation)) {
            if (BMC$MISSING_ANIMATIONS.add(id)) {
                BetterMobCombatReimagined.LOGGER.warn(
                        "Better Combat mob {} animation '{}' uses unsupported playable type '{}'",
                        purpose,
                        id,
                        playable.getClass().getName()
                );
            }
            return null;
        }
        return animation;
    }

    @Unique
    private Optional<AdjustmentModifier.PartModifier> bmc$attackAdjustment(String partName) {
        float pitch = (float) Math.toRadians(this.getXRot());
        return switch (partName) {
            case "torso" -> Optional.of(new AdjustmentModifier.PartModifier(
                    new Vec3f(-pitch * 0.75F, 0.0F, 0.0F),
                    Vec3f.ZERO
            ));
            case "rightArm", "leftArm" -> Optional.of(new AdjustmentModifier.PartModifier(
                    new Vec3f(pitch * 0.25F, 0.0F, 0.0F),
                    Vec3f.ZERO
            ));
            case "rightLeg", "leftLeg" -> Optional.of(new AdjustmentModifier.PartModifier(
                    new Vec3f(-pitch * 0.75F, 0.0F, 0.0F),
                    Vec3f.ZERO
            ));
            default -> Optional.empty();
        };
    }
}