package com.fiercerbattles.bettercombat.mixin.client;

import com.mojang.authlib.GameProfile;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.AnimationStack;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation.AnimationBuilder;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import com.fiercerbattles.bettercombat.BetterCombatMod;
import com.fiercerbattles.bettercombat.Platform;
import com.fiercerbattles.bettercombat.api.EntityPlayer_BetterCombat;
import com.fiercerbattles.bettercombat.api.fx.ParticlePlacement;
import com.fiercerbattles.bettercombat.api.fx.TrailAppearance;
import com.fiercerbattles.bettercombat.client.BetterCombatClientMod;
import com.fiercerbattles.bettercombat.client.animation.*;
import com.fiercerbattles.bettercombat.client.animation.modifier.HarshAdjustmentModifier;
import com.fiercerbattles.bettercombat.client.animation.modifier.TransmissionSpeedModifier;
import com.fiercerbattles.bettercombat.client.compat.FirstPersonAnimationCompatibility;
import com.fiercerbattles.bettercombat.client.particle.SlashParticleUtil;
import com.fiercerbattles.bettercombat.client.particle.SlashParticleUtil.SpawnArgs;
import com.fiercerbattles.bettercombat.logic.AnimatedHand;
import com.fiercerbattles.bettercombat.logic.PlayerAttackHelper;
import com.fiercerbattles.bettercombat.logic.Pose;
import com.fiercerbattles.bettercombat.mixin.player.LivingEntityAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;

@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerEntityMixin extends Player implements PlayerAttackAnimatable {
    private final AttackAnimationSubStack attackAnimation = new AttackAnimationSubStack(createAttackAdjustment());
    private final PoseSubStack mainHandBodyPose = new PoseSubStack(createPoseAdjustment(), true, true);
    private final PoseSubStack mainHandItemPose = new PoseSubStack(null, false, true);
    private final PoseSubStack offHandBodyPose = new PoseSubStack(null, true, false);
    private final PoseSubStack offHandItemPose = new PoseSubStack(null, false, true);

    public AbstractClientPlayerEntityMixin(Level world, BlockPos pos, float yaw, GameProfile gameProfile) {
        super(world, pos, yaw, gameProfile);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void postInit(ClientLevel world, GameProfile profile, CallbackInfo ci) {
        var stack = ((IAnimatedPlayer) this).getAnimationStack();
        stack.addAnimLayer(1, offHandItemPose.base);
        stack.addAnimLayer(2, offHandBodyPose.base);
        stack.addAnimLayer(3, mainHandItemPose.base);
        stack.addAnimLayer(4, mainHandBodyPose.base);
        stack.addAnimLayer(2000, attackAnimation.base);

        mainHandBodyPose.configure = this::updateAnimationByCurrentActivity;
        offHandBodyPose.configure = this::updateAnimationByCurrentActivity;
    }

    @Override
    public void updateAnimationsOnTick() {
        var instance = (Object)this;
        var player = (Player)instance;
        var isLeftHanded = isLeftHanded();
        var hasActiveAttackAnimation = attackAnimation.base.getAnimation() != null && attackAnimation.base.getAnimation().isActive();
        var mainHandStack = player.getMainHandItem();
        // No pose during special activities

        if (scheduledParticles != null && scheduledParticles.time() == player.tickCount) {
            SlashParticleUtil.spawnParticles(scheduledParticles.args());
            scheduledParticles = null;
        }

        if (player.swinging // Official mapping name: `isHandBusy`
                || player.isSwimming()
                || player.isUsingItem()
                || player.onClimbable()
                || player.isFallFlying()
                || Platform.isCastingSpell(player)
                || CrossbowItem.isCharged(mainHandStack)) {
            mainHandBodyPose.setPose(null, isLeftHanded);
            mainHandItemPose.setPose(null, isLeftHanded);
            offHandBodyPose.setPose(null, isLeftHanded);
            offHandItemPose.setPose(null, isLeftHanded);
            return;
        }

        // Restore auto body rotation upon swing - Fix issue #11

        if (hasActiveAttackAnimation) {
            ((LivingEntityAccessor)player).invokeTurnHead(player.getYHeadRot(), 0);
        }

        // Pose

        var betterCombatPlayer = (EntityPlayer_BetterCombat)player;

        KeyframeAnimation newMainHandPose = null;
        KeyframeAnimation newOffHandPose = null;
        if (Minecraft.getInstance().player == player) { // Logic on local player too for improved responsiveness
            var pose = PlayerAttackHelper.poseForPlayer(player);
            if (!pose.base().isEmpty()) {
                newMainHandPose = (KeyframeAnimation) PlayerAnimationRegistry.getAnimation(ResourceLocation.parse(pose.base()));
            }
            if (!pose.offHand().isEmpty()) {
                newOffHandPose = (KeyframeAnimation) PlayerAnimationRegistry.getAnimation(ResourceLocation.parse(pose.offHand()));
            }
        } else {
            if (betterCombatPlayer.getMainHandIdleAnimation() != null && !betterCombatPlayer.getMainHandIdleAnimation().isEmpty()) {
                newMainHandPose = (KeyframeAnimation) PlayerAnimationRegistry.getAnimation(ResourceLocation.parse(betterCombatPlayer.getMainHandIdleAnimation()));
            }
            if (betterCombatPlayer.getOffHandIdleAnimation() != null && !betterCombatPlayer.getOffHandIdleAnimation().isEmpty()) {
                newOffHandPose = (KeyframeAnimation) PlayerAnimationRegistry.getAnimation(ResourceLocation.parse(betterCombatPlayer.getOffHandIdleAnimation()));
            }
        }

        mainHandItemPose.setPose(newMainHandPose, isLeftHanded);
        offHandItemPose.setPose(newOffHandPose, isLeftHanded);

        if (!PlayerAttackHelper.isTwoHandedWielding(player)) {
            if (this.isWalking() || this.isShiftKeyDown()) {
                newMainHandPose = null;
                newOffHandPose = null;
            }
        }
        mainHandBodyPose.setPose(newMainHandPose, isLeftHanded);
        offHandBodyPose.setPose(newOffHandPose, isLeftHanded);
    }

    @Override
    public void playAttackAnimation(String name, AnimatedHand animatedHand, float length, float upswing) {
        // Fiercer Battles: an upswing of 0 (an attack written without "upswing", like a combat skill file)
        // makes upswingSpeed below infinite, and PlayerAnimator's SpeedModifier then steps the animation
        // forever, freezing the client. Keep the speeds finite, the same way the mob attack animation does.
        length = Math.max(1.0F, length);
        upswing = Mth.clamp(upswing, 0.01F, 0.99F);
        try {
            KeyframeAnimation animation = (KeyframeAnimation) PlayerAnimationRegistry.getAnimation(ResourceLocation.parse(name));
            var copy = animation.mutableCopy();
            updateAnimationByCurrentActivity(copy);
            copy.torso.fullyEnablePart(true);
            copy.head.pitch.setEnabled(false);
            var speed = ((float)animation.endTick) / length;
            var mirror = animatedHand.isOffHand();
            if(isLeftHanded()) {
                mirror = !mirror;
            }

            var fadeIn = copy.beginTick;
            var trueUpswingRatio = upswing / BetterCombatMod.config.getUpswingMultiplier();
            float upswingSpeed = speed / trueUpswingRatio;
            float downwindSpeed = (float) (speed *
                    Mth.lerp(Math.max(BetterCombatMod.config.getUpswingMultiplier() - 0.5, 0) / 0.5, // Choosing value :D
                    (1F - upswing),                     // Use this value at config `0.5`
                    upswing / (1F - upswing)));         // Use this value at config `1.0`
            attackAnimation.speed.set(upswingSpeed,
                    List.of(
                            new TransmissionSpeedModifier.Gear(length * upswing, downwindSpeed),
                            new TransmissionSpeedModifier.Gear(length, speed)
                    ));
            attackAnimation.mirror.setEnabled(mirror);

            var player = new CustomAnimationPlayer(copy.build(), 0);
            player.setFirstPersonMode(FirstPersonAnimationCompatibility.firstPersonMode());
            player.setFirstPersonConfiguration(firstPersonConfig(animatedHand));
            attackAnimation.base.replaceAnimationWithFade(
                    AbstractFadeModifier.standardFadeIn(fadeIn, Ease.INOUTSINE),
                    player);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Nullable private SlashParticleUtil.ScheduledSpawnArgs scheduledParticles = null;

    @Override
    public void playAttackParticles(boolean isOffHand, float weaponRange, int delay, List<ParticlePlacement> particles, TrailAppearance appearance) {
        var player = (AbstractClientPlayer)(Object)this;
        var spawn = new SlashParticleUtil.SpawnArgs(
                player,
                isOffHand,
                weaponRange,
                particles,
                appearance
        );
        scheduledParticles = new SlashParticleUtil.ScheduledSpawnArgs(
                spawn,
                player.tickCount + delay
        );
    }

    private AdjustmentModifier createAttackAdjustment() {
        var player = (Player)this;
        return new AdjustmentModifier((partName) -> {
            // System.out.println("Player pitch: " + player.getPitch());
            float rotationX = 0;
            float rotationY = 0;
            float rotationZ = 0;
            float offsetX = 0;
            float offsetY = 0;
            float offsetZ = 0;

            if (FirstPersonMode.isFirstPersonPass()) {
                var pitch = player.getXRot();
                pitch = (float) Math.toRadians(pitch);
                switch (partName) {
                    case "body" -> {
                        rotationX -= pitch;
                        if (pitch < 0) {
                            var offset = Math.abs(Math.sin(pitch));
                            offsetY += offset * 0.5;
                            offsetZ -= offset;
                        }
                    }
//                    case "rightArm", "leftArm" -> {
//                        rotationX = pitch;
//                    }
                    default -> {
                        return Optional.empty();
                    }
                }
            } else {
                var pitch = player.getXRot();
                pitch = (float) Math.toRadians(pitch);
                switch (partName) {
                    case "body" -> {
                        rotationX -= pitch * 0.75F;
                    }
                    case "rightArm", "leftArm" -> {
                        rotationX += pitch * 0.25F;
                    }
                    case "rightLeg", "leftLeg" -> {
                        rotationX -= pitch * 0.75;
                    }
                    default -> {
                        return Optional.empty();
                    }
                }
            }

            return Optional.of(new AdjustmentModifier.PartModifier(
                    new Vec3f(rotationX, rotationY, rotationZ),
                    new Vec3f(offsetX, offsetY, offsetZ))
            );
        });
    }

    private AdjustmentModifier createPoseAdjustment() {
        var player = (Player)this;
        return new HarshAdjustmentModifier((partName) -> {
            float rotationX = 0;
            float rotationY = 0;
            float rotationZ = 0;
            float offsetX = 0;
            float offsetY = 0;
            float offsetZ = 0;

            if (!FirstPersonMode.isFirstPersonPass()) {
                switch (partName) {
                    case "rightArm", "leftArm" -> {
                        if (!mainHandItemPose.lastAnimationUsesBodyChannel && player.isCrouching()) {
                            offsetY += 3;
                        }
                    }
                    default -> {
                        return Optional.empty();
                    }
                }
            }

            return Optional.of(new AdjustmentModifier.PartModifier(
                    new Vec3f(rotationX, rotationY, rotationZ),
                    new Vec3f(offsetX, offsetY, offsetZ))
            );
        });
    }

    private void updateAnimationByCurrentActivity(KeyframeAnimation.AnimationBuilder animation) {
        var pose = getPose();
        switch (pose) {
            case STANDING -> {
            }
            case FALL_FLYING -> {
            }
            case SLEEPING -> {
            }
            case SWIMMING -> {
                StateCollectionHelper.configure(animation.rightLeg, false, false);
                StateCollectionHelper.configure(animation.leftLeg, false, false);
            }
            case SPIN_ATTACK -> {
            }
            case CROUCHING -> {
            }
            case LONG_JUMPING -> {
            }
            case DYING -> {
            }
        }
        if (isMounting()) {
            StateCollectionHelper.configure(animation.rightLeg, false, false);
            StateCollectionHelper.configure(animation.leftLeg, false, false);
        } else {
            var legAnimationThreshold = BetterCombatClientMod.config.legAnimationThreshold;
            if (BetterCombatClientMod.config.legAnimationThreshold > 0) {
                var moving = this.isSprinting() || this.isWalking();
//                var horizontalSpeed = this.getVelocity().horizontalLength();
//                System.out.println("Horizontal speed: " + horizontalSpeed);
                if (moving
                        // && horizontalSpeed > legAnimationThreshold
                        && this.getDeltaMovement().horizontalDistanceSqr() > (legAnimationThreshold * legAnimationThreshold)
                ) {
                    StateCollectionHelper.configure(animation.rightLeg, false, false);
                    StateCollectionHelper.configure(animation.leftLeg, false, false);
                }
            }
        }
    }


    private boolean isWalking() {
        return !this.isDeadOrDying() && (this.isSwimming() || this.getDeltaMovement().horizontalDistance() > 0.03);
    }

    private boolean isMounting() {
        return this.getVehicle() != null;
    }

    public boolean isLeftHanded() {
        return this.getMainArm() == HumanoidArm.LEFT;
    }

    // PlayerAttackAnimatable

    @Override
    public void stopAttackAnimation(float length) {
        IAnimation currentAnimation = attackAnimation.base.getAnimation();
        scheduledParticles = null;
        if (currentAnimation != null && currentAnimation instanceof KeyframeAnimationPlayer) {
            var fadeOut = Math.round(length);
            attackAnimation.adjustmentModifier.fadeOut(fadeOut);
            attackAnimation.base.replaceAnimationWithFade(
                    AbstractFadeModifier.standardFadeIn(fadeOut, Ease.INOUTSINE), null);
        }
    }

    // FirstPersonAnimator

    private FirstPersonConfiguration firstPersonConfig(AnimatedHand animatedHand) {
        // boolean leftHanded = getMainArm() == Arm.LEFT;
        var showRightItem = true;
        var showLeftItem = BetterCombatClientMod.config.isShowingOtherHandFirstPerson || animatedHand == AnimatedHand.TWO_HANDED;
        var showRightArm = showRightItem && BetterCombatClientMod.config.isShowingArmsInFirstPerson;
        var showLeftArm = showLeftItem && BetterCombatClientMod.config.isShowingArmsInFirstPerson;

        var config = new FirstPersonConfiguration(showRightArm, showLeftArm, showRightItem, showLeftItem);
        // System.out.println("Animation config: " + config);
        return config;
    }
}
