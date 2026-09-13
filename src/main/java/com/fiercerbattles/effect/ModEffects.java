package com.fiercerbattles.effect;

import com.fiercerbattles.FiercerBattles;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry for Fiercer Battles mob effects.
 */
public final class ModEffects {
    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, FiercerBattles.MOD_ID);

    /** 伤痕打击 — the afflicted lose 1 health every 0.25 seconds. */
    public static final DeferredHolder<MobEffect, ScarStrikeMobEffect> SCAR_STRIKE =
            MOB_EFFECTS.register("scar_strike", () -> new ScarStrikeMobEffect(
                    MobEffectCategory.HARMFUL,
                    0xD00000
            ));

    private ModEffects() {
    }

    public static void register(IEventBus modEventBus) {
        MOB_EFFECTS.register(modEventBus);
    }
}
