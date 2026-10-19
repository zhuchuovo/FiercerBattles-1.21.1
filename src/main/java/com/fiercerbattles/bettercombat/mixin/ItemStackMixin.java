package com.fiercerbattles.bettercombat.mixin;

import com.fiercerbattles.bettercombat.api.WeaponAttributes;
import com.fiercerbattles.bettercombat.logic.ItemStackNBTWeaponAttributes;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin implements ItemStackNBTWeaponAttributes {
    private boolean hasInvalidAttributes = false;
    public boolean hasInvalidAttributes() {
        return hasInvalidAttributes;
    }
    public void setInvalidAttributes(boolean invalid) {
        this.hasInvalidAttributes = invalid;
    }

    private WeaponAttributes weaponAttributes;
    @Override
    public WeaponAttributes getWeaponAttributes() {
        return weaponAttributes;
    }
    @Override
    public void setWeaponAttributes(WeaponAttributes weaponAttributes) {
        this.weaponAttributes = weaponAttributes;
    }
}
