package com.fiercerbattles.boss;

import com.fiercerbattles.FiercerBattles;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Set;

/** Zombie Lord boss, its skull projectiles and the skeletons they raise. */
public final class BossEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, FiercerBattles.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FiercerBattles.MOD_ID);

    /** Rendered at 1.5x the one.blend rig (README of 敌军动作), so the hitbox is 1.5x a zombie's. */
    public static final DeferredHolder<EntityType<?>, EntityType<ZombieLord>> ZOMBIE_LORD = ENTITY_TYPES.register("zombie_lord",
            () -> EntityType.Builder.of(ZombieLord::new, MobCategory.MONSTER)
                    .sized(0.9F, 2.9F).eyeHeight(2.55F).fireImmune()
                    .clientTrackingRange(10).updateInterval(1)
                    .build("zombie_lord"));
    public static final DeferredHolder<EntityType<?>, EntityType<LordSkull>> LORD_SKULL = ENTITY_TYPES.register("zombie_lord_skull",
            () -> EntityType.Builder.<LordSkull>of(LordSkull::new, MobCategory.MISC)
                    .sized(0.625F, 0.625F).fireImmune().noSummon()
                    .clientTrackingRange(8).updateInterval(1)
                    .build("zombie_lord_skull"));
    public static final DeferredHolder<EntityType<?>, EntityType<LordSkeleton>> LORD_SKELETON = ENTITY_TYPES.register("zombie_lord_skeleton",
            () -> EntityType.Builder.of(LordSkeleton::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.99F).eyeHeight(1.74F).ridingOffset(-0.7F)
                    .clientTrackingRange(8)
                    .build("zombie_lord_skeleton"));

    public static final DeferredItem<DeferredSpawnEggItem> ZOMBIE_LORD_SPAWN_EGG = ITEMS.register("zombie_lord_spawn_egg",
            () -> new DeferredSpawnEggItem(ZOMBIE_LORD, 0x2B3A1E, 0xD9A521, new Item.Properties()));
    public static final DeferredItem<DeferredSpawnEggItem> LORD_SKELETON_SPAWN_EGG = ITEMS.register("zombie_lord_skeleton_spawn_egg",
            () -> new DeferredSpawnEggItem(LORD_SKELETON, 0xC1C1C1, 0x7A7F86, new Item.Properties()));

    // None is a vanilla melee type, so a parry cannot stun the boss.
    /** attack1 and attack2: in fiercerbattles:ignores_dodge_roll, so a dodge roll does not escape them. */
    public static final ResourceKey<DamageType> LORD_SLASH = damageType("zombie_lord_slash");
    /** attack3's slam: in minecraft:bypasses_shield and fiercerbattles:ignores_dodge_roll. */
    public static final ResourceKey<DamageType> LORD_CHOP = damageType("zombie_lord_chop");
    /** Other ground blows (leap landings, the charge's slam): listed in minecraft:bypasses_shield. */
    public static final ResourceKey<DamageType> LORD_SMASH = damageType("zombie_lord_smash");
    /** The charge's trample and the ring slash's spin. */
    public static final ResourceKey<DamageType> LORD_STRIKE = damageType("zombie_lord_strike");
    /** The ghost blade's burning cuts. */
    public static final ResourceKey<DamageType> LORD_GHOST_BLADE = damageType("zombie_lord_ghost_blade");
    /** Blast of a skull landing. */
    public static final ResourceKey<DamageType> LORD_SKULL_BLAST = damageType("zombie_lord_skull");
    /** Grab and throw: slammed into the ground, then into a wall. Listed in minecraft:bypasses_shield. */
    public static final ResourceKey<DamageType> LORD_GRAB = damageType("zombie_lord_grab");

    /** Built-in humanoid mobs that the execution allowlist always includes, whatever the config lists. */
    public static final Set<String> BUILTIN_HUMANOIDS = Set.of(FiercerBattles.MOD_ID + ":zombie_lord_skeleton");

    private BossEntities() {}

    private static ResourceKey<DamageType> damageType(String path) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(FiercerBattles.MOD_ID, path));
    }

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
        ITEMS.register(modEventBus);
        modEventBus.addListener(BossEntities::attributes);
        modEventBus.addListener(BossEntities::creativeTabs);
    }

    private static void attributes(EntityAttributeCreationEvent event) {
        event.put(ZOMBIE_LORD.get(), ZombieLord.createAttributes().build());
        event.put(LORD_SKELETON.get(), LordSkeleton.createAttributes().build());
    }

    private static void creativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(ZOMBIE_LORD_SPAWN_EGG.get());
            event.accept(LORD_SKELETON_SPAWN_EGG.get());
        }
    }
}
