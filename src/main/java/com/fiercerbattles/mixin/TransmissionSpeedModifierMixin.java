package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * Applies a real animation playback speed multiplier just before
 * TransmissionSpeedModifier forwards to the player animation super call.
 *
 * The speed field is located reflectively, so this project does not need a
 * compile-time dependency on BetterCombat or PlayerAnimator internals.
 */
@Mixin(targets = "net.bettercombat.client.animation.modifier.TransmissionSpeedModifier")
public abstract class TransmissionSpeedModifierMixin {

    @Inject(
            method = "setupAnim",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/kosmx/playerAnim/api/layered/modifier/SpeedModifier;setupAnim(F)V"
            ),
            remap = false
    )
    private void fiercerbattles$applyAnimationSpeed(float tickDelta, CallbackInfo ci) {
        float multiplier = FiercerBattlesRuntime.getAnimationSpeed();
        if (multiplier == 1.0F) {
            return;
        }
        try {
            Field speedField = findSpeedField(getClass());
            if (speedField == null) {
                return;
            }
            speedField.setAccessible(true);
            float current = speedField.getFloat(this);
            speedField.setFloat(this, current * multiplier);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static Field findSpeedField(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField("speed");
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
