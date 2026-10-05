package com.fiercerbattles.mixin;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Select the victim's native hurt sound without applying damage or changing its pose. */
@Mixin(LivingEntity.class)
public interface ExecutionLivingEntityInvoker {
    @Invoker("getHurtSound")
    SoundEvent fiercerbattles$getHurtSound(DamageSource source);
}
