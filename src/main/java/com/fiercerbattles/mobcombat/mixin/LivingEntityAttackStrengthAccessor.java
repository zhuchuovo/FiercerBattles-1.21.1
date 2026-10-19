package com.fiercerbattles.mobcombat.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the internal attack-strength ticker so each committed Better Mob Combat hit can preserve
 * the same vanilla attack state. The original Better Mob Combat restored this value before every
 * target damage call; without it, later combo entries can inherit state mutated by an earlier hit.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityAttackStrengthAccessor {
    @Accessor("attackStrengthTicker")
    int bmc$getAttackStrengthTicker();

    @Accessor("attackStrengthTicker")
    void bmc$setAttackStrengthTicker(int ticks);
}
