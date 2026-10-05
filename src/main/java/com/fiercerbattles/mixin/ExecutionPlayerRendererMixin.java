package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesExecutionClient;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerRenderer.class)
public abstract class ExecutionPlayerRendererMixin {
    @Inject(method = "getRenderOffset(Lnet/minecraft/client/player/AbstractClientPlayer;F)Lnet/minecraft/world/phys/Vec3;", at = @At("RETURN"), cancellable = true)
    private void fiercerbattles$preventCrouchOffset(AbstractClientPlayer player, float partial, CallbackInfoReturnable<Vec3> cir) {
        var actor = FiercerBattlesExecutionClient.actor(player);
        if (actor == null) return;
        cir.setReturnValue(actor.returning()
                ? cir.getReturnValue().scale(actor.returnProgress(partial)) : Vec3.ZERO);
    }
}
