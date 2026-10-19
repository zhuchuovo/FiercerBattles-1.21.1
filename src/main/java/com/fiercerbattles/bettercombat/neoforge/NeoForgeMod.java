package com.fiercerbattles.bettercombat.neoforge;

import com.fiercerbattles.bettercombat.BetterCombatMod;
import com.fiercerbattles.bettercombat.particle.BetterCombatParticles;
import com.fiercerbattles.bettercombat.utils.SoundHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.RegisterEvent;

// Embedded in Fiercer Battles: this was Better Combat's @Mod entry point. It is now initialized
// from the Fiercer Battles constructor; registry and resource namespaces stay "bettercombat".
public final class NeoForgeMod {
    private NeoForgeMod() {
    }

    public static void initialize(IEventBus modEventBus) {
        BetterCombatMod.init();
        modEventBus.addListener(RegisterEvent.class, NeoForgeMod::register);
        SOUND_EVENTS.register(modEventBus);
    }

    public static void register(RegisterEvent event) {
        event.register(Registries.PARTICLE_TYPE, reg -> {
            BetterCombatParticles.register();
        });
    }

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, BetterCombatMod.ID);

    static {
        SoundHelper.soundKeys.forEach(soundKey -> SOUND_EVENTS.register(soundKey, () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(BetterCombatMod.ID, soundKey))));
    }
}
