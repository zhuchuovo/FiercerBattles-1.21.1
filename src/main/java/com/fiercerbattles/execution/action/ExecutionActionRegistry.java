package com.fiercerbattles.execution.action;

import com.fiercerbattles.FiercerBattles;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Datapack definitions are reloaded atomically; malformed entries do not hide valid actions. */
@EventBusSubscriber(modid = FiercerBattles.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class ExecutionActionRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile Map<ResourceLocation, PairedExecutionAction> ACTIONS = Map.of();
    private ExecutionActionRegistry() {}

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new SimpleJsonResourceReloadListener(new GsonBuilder().create(), "fiercerbattles_actions") {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> definitions, ResourceManager resources, ProfilerFiller profiler) {
                Map<ResourceLocation, PairedExecutionAction> loaded = new HashMap<>();
                definitions.forEach((id, element) -> {
                    try {
                        PairedExecutionAction action = PairedExecutionAction.load(id, element.getAsJsonObject(), resources);
                        loaded.put(id, action);
                    } catch (Exception exception) {
                        LOGGER.error("Ignoring invalid paired execution action {}", id, exception);
                    }
                });
                ACTIONS = Map.copyOf(loaded);
                LOGGER.info("Loaded {} paired execution action(s)", ACTIONS.size());
            }
        });
    }

    public static PairedExecutionAction get(String id) {
        try { return ACTIONS.get(ResourceLocation.parse(id)); }
        catch (RuntimeException exception) { return null; }
    }
}
