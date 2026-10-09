package com.fiercerbattles.mixin;

import com.fiercerbattles.parry.ParryServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stops attacks before mob enchantment and knockback effects are resolved. */
@Mixin(Mob.class)
public abstract class ParryMobAttackMixin {
    @Inject(method = "doHurtTarget", at = @At("HEAD"), cancellable = true)
    private void fiercerbattles$blockParryMobAttack(Entity target, CallbackInfoReturnable<Boolean> cir) {
        if (ParryServer.blocksActions((Mob) (Object) this)) cir.setReturnValue(false);
    }
}
