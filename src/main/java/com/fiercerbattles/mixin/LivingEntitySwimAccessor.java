package com.fiercerbattles.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets a roll in the swimming pose keep vanilla's crawl tilt from building up. */
@Mixin(LivingEntity.class)
public interface LivingEntitySwimAccessor {
    @Accessor(value = "swimAmount", remap = false)
    void fiercerbattles$setSwimAmount(float amount);

    @Accessor(value = "swimAmountO", remap = false)
    void fiercerbattles$setSwimAmountO(float amount);
}
