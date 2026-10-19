package com.fiercerbattles.bettercombat.mixin;

import com.fiercerbattles.bettercombat.api.component.BetterCombatDataComponents;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DataComponents.class)
public class DataComponentTypesMixin {

    // Static class init tail inject

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void static_init_TAIL_SpellEngine(CallbackInfo ci) {
        var asd = BetterCombatDataComponents.WEAPON_PRESET_ID;
    }
}
