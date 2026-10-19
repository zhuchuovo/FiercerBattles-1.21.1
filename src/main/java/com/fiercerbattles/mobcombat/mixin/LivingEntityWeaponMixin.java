package com.fiercerbattles.mobcombat.mixin;

import com.fiercerbattles.mobcombat.api.MobCombatState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityWeaponMixin {
    @Inject(method = "getWeaponItem", at = @At("HEAD"), cancellable = true)
    private void bmc$useSelectedAttackWeapon(CallbackInfoReturnable<ItemStack> cir) {
        if ((Object) this instanceof MobCombatState state && state.bmc$getWeaponOverride() != null) {
            cir.setReturnValue(state.bmc$getWeaponOverride());
        }
    }
}
