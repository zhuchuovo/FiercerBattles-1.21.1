package com.fiercerbattles.mobcombat.mixin;

import com.fiercerbattles.mobcombat.logic.ExternalAttackCompat;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Visual fallback for custom melee routines that never invoke LivingEntity#swing. */
@Mixin(LivingEntity.class)
public abstract class CustomMobDamageAnimationMixin {
    @Inject(
            method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At("RETURN")
    )
    private void bmc$animateSuccessfulCustomMeleeDamage(
            DamageSource source,
            float amount,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        if (source.getEntity() instanceof Mob attacker
                && source.getDirectEntity() == attacker
                && attacker != (Object) this) {
            ExternalAttackCompat.handleSuccessfulMeleeDamage(attacker);
        }
    }
}
