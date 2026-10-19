package com.fiercerbattles.bettercombat.neoforge.client;

import com.fiercerbattles.FiercerBattles;
import me.shedaniel.autoconfig.AutoConfig;
import com.fiercerbattles.bettercombat.BetterCombatMod;
import com.fiercerbattles.bettercombat.client.BetterCombatClientMod;
import com.fiercerbattles.bettercombat.client.Keybindings;
import com.fiercerbattles.bettercombat.client.particle.SlashParticle;
import com.fiercerbattles.bettercombat.config.ClientConfigWrapper;
import com.fiercerbattles.bettercombat.particle.BetterCombatParticles;
import com.fiercerbattles.bettercombat.particle.BetterCombatParticles.Entry;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@EventBusSubscriber(modid = FiercerBattles.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class NeoForgeClientMod {
    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event){
        Keybindings.all.forEach(event::register);
    }

    @SubscribeEvent
    public static void onRegisterParticleProviders(RegisterParticleProvidersEvent event) {
        for (var entry : BetterCombatParticles.ENTRIES) {
            event.registerSpriteSet(
                    entry.particleType(),
                    spriteSet -> new SlashParticle.Provider(spriteSet, entry.params())
            );
        }
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event){
        BetterCombatClientMod.init();
        BetterCombatClientMod.loadAnimation();

        ItemProperties.registerGeneric(ResourceLocation.fromNamespaceAndPath(BetterCombatMod.ID, "loaded"), (stack, world, entity, seed) -> {
            return 1.0F;
        });

        ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory.class, () -> {
            return (IConfigScreenFactory) (modContainer, parent) -> AutoConfig.getConfigScreen(ClientConfigWrapper.class, parent).get();
        });
    }
}