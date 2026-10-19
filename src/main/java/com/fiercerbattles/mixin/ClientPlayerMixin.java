package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesRuntime;
import com.fiercerbattles.FiercerBattlesExecutionClient;
import com.fiercerbattles.parry.client.ParryClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies forward displacement while a configured BetterCombat attack is playing.
 */
@Mixin(targets = "net.minecraft.client.player.LocalPlayer")
public abstract class ClientPlayerMixin {

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void fiercerbattles$holdParryPosition(CallbackInfo ci) {
        ParryClient.lockMovement((LocalPlayer) (Object) this);
    }

    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true, remap = false)
    private Vec3 fiercerbattles$keepParryFall(Vec3 movement) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (!ParryClient.isMovementLocked(player) || ParryClient.isVerticallyLocked(player)) return movement;
        // A parry still falls: the horizontal displacement is dropped, the vertical one is kept.
        return new Vec3(0.0, movement.y, 0.0);
    }

    @Inject(method = "move", at = @At("HEAD"), cancellable = true, remap = false)
    private void fiercerbattles$blockParryMovement(MoverType type, Vec3 movement, CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (!ParryClient.isMovementLocked(player) || !ParryClient.isVerticallyLocked(player)) return;
        ParryClient.lockMovement(player);
        ci.cancel();
    }

    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void fiercerbattles$tickDisplacement(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        ParryClient.lockMovement(player);
        if (FiercerBattlesExecutionClient.isExecuting(player) || ParryClient.blocksActions()) return;
        FiercerBattlesRuntime.tickDisplacement(player);
        FiercerBattlesRuntime.tickMultipleHits(player);
    }
}
