package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesCustomData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the "does {@code two_handed} affect the attack action" option.
 *
 * A weapon_attributes file (or a combat-skill file) can write
 * {@code "two_handed_attack": false} to keep its two-handed pose/two-handed
 * animations while still being judged as a <em>one-handed</em> weapon for the
 * attack: the swing is played as a one-handed attack and the off-hand slot
 * keeps working, so a shield can be carried.
 *
 * Better Combat reads that judgement from a single place -
 * {@code WeaponAttributes.isTwoHanded()} - therefore the option is applied
 * here instead of rewriting every call site:
 *
 * <ul>
 *   <li>{@code PlayerEntityMixin.getEquippedStack} stops replacing the
 *       off-hand slot with {@code ItemStack.EMPTY}.</li>
 *   <li>{@code PlayerAttackHelper.isDualWielding} / {@code isTwoHandedWielding}
 *       judge the weapon as one-handed.</li>
 *   <li>{@code MinecraftClientInject.startUpswing} builds the attack animation
 *       from {@code AnimatedHand.from(isOffHand, isTwoHanded())} and therefore
 *       plays the one-handed animation variant.</li>
 * </ul>
 *
 * The target class is final and the flag only ever reaches gameplay through
 * this accessor, so no field of Better Combat is mutated; flagged instances
 * are tracked in {@link FiercerBattlesCustomData} by identity.
 */
@Mixin(targets = "com.fiercerbattles.bettercombat.api.WeaponAttributes", priority = 2000)
public abstract class WeaponAttributesMixin {

    @Inject(method = "isTwoHanded", at = @At("HEAD"), cancellable = true, remap = false)
    private void fiercerbattles$applyTwoHandedAttackOption(CallbackInfoReturnable<Boolean> cir) {
        if (FiercerBattlesCustomData.isAttackActionOneHanded((Object) this)) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }
}
