package com.fiercerbattles.parry;

import net.minecraft.world.entity.LivingEntity;

/**
 * A boss whose own blows can be parried although they are not vanilla melee damage: a blow whose damage type is in
 * {@code fiercerbattles:boss_parryable} is stopped by a parry window like a melee hit, and instead of the humanoid
 * stagger the boss plays its own (its model is not the six-part humanoid the stagger clip needs). The parry sound
 * and counter damage stay those of {@link ParryServer}.
 */
public interface BossParryTarget {
    /** One of this boss's blows was just parried by {@code defender} (server thread). */
    void parried(LivingEntity defender);
}
