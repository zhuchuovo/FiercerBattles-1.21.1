package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesRuntime;
import com.fiercerbattles.parry.client.ParryClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Captures the current BetterCombat attack animation name before the animation
 * player is set up, so TransmissionSpeedModifierMixin can slow it down.
 *
 * playAttackAnimation is merged into AbstractClientPlayer by BetterCombat's
 * own mixin (priority 1000); injecting into it requires a higher priority.
 */
@Mixin(targets = "net.minecraft.client.player.AbstractClientPlayer", priority = 2000)
public abstract class AbstractClientPlayerMixin {

    @Unique
    private boolean fiercerbattles$forceOriginalPlayback;

    @Inject(method = "playAttackAnimation", at = @At("HEAD"), remap = false)
    private void fiercerbattles$captureAnimation(String name, @Coerce Object hand, float length, float upswing, CallbackInfo ci) {
        fiercerbattles$forceOriginalPlayback = FiercerBattlesRuntime.shouldForceOriginalPlayback(name);
        if ((Object) this instanceof LocalPlayer) {
            int startTick = ((Entity) (Object) this).tickCount;
            FiercerBattlesRuntime.setCurrentAnimation(
                    name,
                    startTick,
                    ((LocalPlayer) (Object) this).getCurrentItemAttackStrengthDelay(),
                    length
            );
            ParryClient.reportSwing();
        }
        fiercerbattles$setAttackAdjustmentEnabled(
                !fiercerbattles$forceOriginalPlayback
        );
    }

    /**
     * Better Combat disables the resource's head pitch immediately before it
     * builds the copied animation. Restore that state for animations that opt
     * into original playback. The handler uses Object/reflection because this
     * mod intentionally has no compile-time Better Combat or PlayerAnimator
     * dependency.
     */
    @Redirect(
            method = "playAttackAnimation",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/kosmx/playerAnim/core/data/KeyframeAnimation$AnimationBuilder;build()Ldev/kosmx/playerAnim/core/data/KeyframeAnimation;"
            ),
            remap = false
    )
    @Coerce
    private Object fiercerbattles$buildAnimationWithOriginalHeadPitch(@Coerce Object builder) {
        if (fiercerbattles$forceOriginalPlayback) {
            fiercerbattles$setEnabled(
                    fiercerbattles$readField(
                            fiercerbattles$readField(builder, "head"),
                            "pitch"
                    ),
                    true
            );
        }
        return fiercerbattles$invoke(builder, "build");
    }

    @ModifyArg(
            method = "playAttackAnimation",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/fiercerbattles/bettercombat/client/animation/modifier/TransmissionSpeedModifier;set(FLjava/util/List;)V"
            ),
            index = 0,
            remap = false
    )
    private float fiercerbattles$keepOriginalAnimationSpeed(float speed) {
        return fiercerbattles$forceOriginalPlayback ? 1.0F : speed;
    }

    @ModifyArg(
            method = "playAttackAnimation",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/fiercerbattles/bettercombat/client/animation/modifier/TransmissionSpeedModifier;set(FLjava/util/List;)V"
            ),
            index = 1,
            remap = false
    )
    private List<?> fiercerbattles$clearOriginalAnimationGears(List<?> gears) {
        return fiercerbattles$forceOriginalPlayback ? List.of() : gears;
    }

    private void fiercerbattles$setAttackAdjustmentEnabled(boolean enabled) {
        try {
            Object attackAnimation = fiercerbattles$readField(this, "attackAnimation");
            Object adjustment = fiercerbattles$readField(attackAnimation, "adjustmentModifier");
            fiercerbattles$setEnabled(adjustment, enabled);
        } catch (RuntimeException ignored) {
            // Better Combat internals are intentionally accessed defensively.
        }
    }

    private static Object fiercerbattles$readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static void fiercerbattles$setEnabled(Object target, boolean enabled) {
        if (target == null) {
            return;
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("enabled");
                field.setAccessible(true);
                field.setBoolean(target, enabled);
                return;
            } catch (NoSuchFieldException ignored) {
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return;
            }
        }
    }

    private static Object fiercerbattles$invoke(Object target, String name) {
        if (target == null) {
            throw new IllegalStateException("Cannot invoke " + name + " on null");
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to invoke " + name, e);
            }
        }
        throw new IllegalStateException("Missing method " + name);
    }
}
