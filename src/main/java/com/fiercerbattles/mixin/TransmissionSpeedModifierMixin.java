package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Applies a real animation playback speed multiplier just before
 * TransmissionSpeedModifier forwards to the player animation super call.
 *
 * The speed field is located reflectively, so this project does not need a
 * compile-time dependency on BetterCombat or PlayerAnimator internals.
 */
@Mixin(targets = "com.fiercerbattles.bettercombat.client.animation.modifier.TransmissionSpeedModifier")
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
        if (FiercerBattlesRuntime.isForceOriginalPlayback()) {
            try {
                Field speedField = findField(getClass(), "speed");
                if (speedField != null) {
                    speedField.setAccessible(true);
                    speedField.setFloat(this, 1.0F);
                }
                Field gearsField = findField(getClass(), "gears");
                if (gearsField != null) {
                    gearsField.setAccessible(true);
                    gearsField.set(this, List.of());
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
            return;
        }
        float multiplier = FiercerBattlesRuntime.getAnimationSpeed();
        if (multiplier == 1.0F) {
            return;
        }
        try {
            Field speedField = findField(getClass(), "speed");
            if (speedField == null) {
                return;
            }
            speedField.setAccessible(true);
            float current = speedField.getFloat(this);
            speedField.setFloat(this, current * multiplier);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
