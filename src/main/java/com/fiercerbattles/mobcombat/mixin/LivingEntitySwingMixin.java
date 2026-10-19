package com.fiercerbattles.mobcombat.mixin;

import com.fiercerbattles.mobcombat.logic.ExternalAttackCompat;
import com.fiercerbattles.mobcombat.logic.MobCombatLogic;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntitySwingMixin {
    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Z)V", at = @At("HEAD"), cancellable = true)
    private void bmc$suppressVanillaMobSwing(InteractionHand hand, boolean updateSelf, CallbackInfo ci) {
        if (!((Object) this instanceof Mob mob)) {
            return;
        }

        // Vanilla mobs enter BMC through Mob#doHurtTarget immediately after this call. A custom
        // mob can bypass that injection completely, so its visual bridge is allowed to claim the
        // swing even when its held item did not resolve through the normal eligibility check.
        boolean eligible = MobCombatLogic.isEligible(mob);
        boolean externallyAnimated = ExternalAttackCompat.handleSwing(mob, hand);
        if (eligible || externallyAnimated) {
            ci.cancel();
        }
    }
}
