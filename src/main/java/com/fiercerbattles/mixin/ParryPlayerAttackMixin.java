package com.fiercerbattles.mixin;

import com.fiercerbattles.parry.ParryServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Covers vanilla attacks and each target of a Better Combat multi-target attack. */
@Mixin(Player.class)
public abstract class ParryPlayerAttackMixin {
    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void fiercerbattles$blockParryPlayerAttack(Entity target, CallbackInfo ci) {
        if (ParryServer.blocksActions((Player) (Object) this)) ci.cancel();
    }
}
