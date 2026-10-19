package com.fiercerbattles.bettercombat.client;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.JanksonConfigSerializer;
import me.shedaniel.autoconfig.serializer.PartitioningSerializer;
import com.fiercerbattles.bettercombat.client.compat.CompatibilityFlags;
import com.fiercerbattles.bettercombat.config.ClientConfig;
import com.fiercerbattles.bettercombat.config.ClientConfigWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManager;

public class BetterCombatClientMod {
    public static boolean ENABLED = false;
    public static ClientConfig config;

    public static void init() {
        AutoConfig.register(ClientConfigWrapper.class, PartitioningSerializer.wrap(JanksonConfigSerializer::new));
        // Intuitive way to load a config :)
        config = AutoConfig.getConfigHolder(ClientConfigWrapper.class).getConfig().client;

        CompatibilityFlags.initialize();
    }

    public static void loadAnimation() {
        var resourceManager = Minecraft.getInstance().getResourceManager();
    }
}
