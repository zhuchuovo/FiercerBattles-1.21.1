package com.fiercerbattles;

import com.fiercerbattles.effect.ModEffects;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(FiercerBattles.MOD_ID)
public class FiercerBattles {
    public static final String MOD_ID = "fiercerbattles";

    public FiercerBattles(IEventBus modEventBus) {
        ModEffects.register(modEventBus);
    }
}
