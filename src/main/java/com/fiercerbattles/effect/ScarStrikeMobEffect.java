package com.fiercerbattles.effect;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

/**
 * 伤痕打击 (Scar Strike): the afflicted creature loses 1 health every
 * 0.25 seconds (5 ticks). Each effect level halves the interval, like
 * vanilla poison and wither.
 *
 * <p>The damage uses the dedicated {@code fiercerbattles:scar_strike}
 * damage type, which is added to the {@code minecraft:bypasses_cooldown}
 * tag so the vanilla post-hit invulnerability window (20 ticks) cannot
 * throttle the 5-tick damage cadence down to once per second. It also
 * bypasses armor, so the loss is always exactly 1 health per tick.
 */
public class ScarStrikeMobEffect extends MobEffect {
    /** Base damage interval in ticks; 5 ticks = 0.25 seconds. */
    private static final int BASE_INTERVAL_TICKS = 5;
    /** Flat health lost on every damage tick. */
    private static final float DAMAGE_PER_TICK = 1.0F;
    /** Custom damage type, defined in {@code data/fiercerbattles/damage_type}. */
    public static final ResourceKey<DamageType> SCAR_STRIKE_DAMAGE = ResourceKey.create(
            Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath("fiercerbattles", "scar_strike")
    );

    public ScarStrikeMobEffect(MobEffectCategory category, int color) {
        super(category, color);
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        // MobEffectInstance ticks on both logical sides; only the server may hurt.
        if (!entity.level().isClientSide) {
            entity.hurt(entity.damageSources().source(SCAR_STRIKE_DAMAGE), DAMAGE_PER_TICK);
        }
        // Returning true keeps the effect running; returning false would remove it.
        return true;
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        int interval = BASE_INTERVAL_TICKS >> amplifier;
        return interval > 0 ? duration % interval == 0 : true;
    }
}
