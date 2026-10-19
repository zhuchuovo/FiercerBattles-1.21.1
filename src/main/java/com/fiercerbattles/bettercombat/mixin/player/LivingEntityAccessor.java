package com.fiercerbattles.bettercombat.mixin.player;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor
    int getAttackStrengthTicker();
    @Accessor("attackStrengthTicker")
    void setLastAttackedTicks(int lastAttackedTicks);
    @Invoker("tickHeadTurn")
    float invokeTurnHead(float bodyRotation, float headRotation);
}
