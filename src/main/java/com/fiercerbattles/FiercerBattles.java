package com.fiercerbattles;

import com.fiercerbattles.bettercombat.neoforge.NeoForgeMod;
import com.fiercerbattles.boss.BossEntities;
import com.fiercerbattles.effect.ModEffects;
import com.fiercerbattles.mobcombat.BetterMobCombatReimagined;
import com.fiercerbattles.sound.ModSounds;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(FiercerBattles.MOD_ID)
public class FiercerBattles {
    public static final String MOD_ID = "fiercerbattles";

    public FiercerBattles(IEventBus modEventBus, ModContainer container) {
        NeoForgeMod.initialize(modEventBus);
        ModEffects.register(modEventBus);
        ModSounds.register(modEventBus);
        BossEntities.register(modEventBus);
        BetterMobCombatReimagined.initialize(modEventBus, container);
    }
}
