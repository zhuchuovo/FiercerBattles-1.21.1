package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesRuntime;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies forward displacement while a configured BetterCombat attack is playing.
 */
@Mixin(targets = "net.minecraft.client.player.LocalPlayer")
public abstract class ClientPlayerMixin {

    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void fiercerbattles$tickDisplacement(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        FiercerBattlesRuntime.tickDisplacement(player);
        FiercerBattlesRuntime.tickMultipleHits(player);
    }
}
