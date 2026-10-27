package com.fiercerbattles.sound;

import com.fiercerbattles.FiercerBattles;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Sound events; the files and subtitles are declared in {@code assets/fiercerbattles/sounds.json}. */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, FiercerBattles.MOD_ID);

    /** Metallic ring of a successful parry. */
    public static final DeferredHolder<SoundEvent, SoundEvent> PARRY = SOUNDS.register("parry",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(FiercerBattles.MOD_ID, "parry")));

    /** Wind-up cue played once when a parry gesture starts. */
    public static final DeferredHolder<SoundEvent, SoundEvent> PARRY_USE = SOUNDS.register("parry_use",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(FiercerBattles.MOD_ID, "parry_use")));

    public static final DeferredHolder<SoundEvent, SoundEvent> BRUTAL_HIT = SOUNDS.register("brutal_hit",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(FiercerBattles.MOD_ID, "brutal_hit")));

    public static final DeferredHolder<SoundEvent, SoundEvent> PUSH_OPEN = SOUNDS.register("push_open",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(FiercerBattles.MOD_ID, "push_open")));

    /** Tumble of a dodge roll, played once as the roll starts. */
    public static final DeferredHolder<SoundEvent, SoundEvent> ROLL = SOUNDS.register("roll",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(FiercerBattles.MOD_ID, "roll")));

    /** A blow of the Iron Sentinel's chainsaw landing. */
    public static final DeferredHolder<SoundEvent, SoundEvent> IRON_SENTINEL_SAW_HIT = SOUNDS.register("iron_sentinel_saw_hit",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(FiercerBattles.MOD_ID, "iron_sentinel_saw_hit")));

    /** The Iron Sentinel's boss music (streamed; boss/client/IronSentinelMusic): the fight, looped, and its finale. */
    public static final DeferredHolder<SoundEvent, SoundEvent> IRON_SENTINEL_BATTLE_MUSIC = SOUNDS.register("music.iron_sentinel_battle",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(FiercerBattles.MOD_ID, "music.iron_sentinel_battle")));

    public static final DeferredHolder<SoundEvent, SoundEvent> IRON_SENTINEL_FINALE_MUSIC = SOUNDS.register("music.iron_sentinel_finale",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(FiercerBattles.MOD_ID, "music.iron_sentinel_finale")));

    private ModSounds() {}

    public static void register(IEventBus modEventBus) {
        SOUNDS.register(modEventBus);
    }
}
