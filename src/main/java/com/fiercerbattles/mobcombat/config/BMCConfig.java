package com.fiercerbattles.mobcombat.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BMCConfig {



    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = SERVER_BUILDER
            .comment("Master switch for Better Combat attacks on mobs.")
            .define("enabled", true);

    public static final ModConfigSpec.BooleanValue ENABLE_FALLBACK_MELEE_ANIMATIONS = SERVER_BUILDER
            .comment("Animate vanilla melee attacks for empty-handed mobs and mobs holding items without Better Combat attributes.")
            .define("enableFallbackMeleeAnimations", true);

    public static final ModConfigSpec.IntValue EMPTY_HANDED_ATTACK_DELAY = SERVER_BUILDER
            .comment("Ticks between a fallback melee attack starting and dealing damage. This applies to empty hands "
                    + "and held items without Better Combat attack attributes, such as shovels, hoes, and pickaxes. "
                    + "Set to 0 for vanilla instant damage.")
            .defineInRange("emptyHandedAttackDelayTicks", 6, 0, 40);

    public static final ModConfigSpec.BooleanValue REQUIRE_LINE_OF_SIGHT = SERVER_BUILDER
            .comment("Require line of sight for every target caught in an attack arc.")
            .define("requireLineOfSight", true);

    public static final ModConfigSpec.BooleanValue ALLOW_FRIENDLY_FIRE = SERVER_BUILDER
            .comment("Allow a mob's attack arc to hit entities allied to that mob.")
            .define("allowFriendlyFire", false);

    public static final ModConfigSpec.IntValue MAX_TARGETS = SERVER_BUILDER
            .comment("Maximum number of living entities one mob swing may hit.")
            .defineInRange("maxTargets", 8, 1, 64);

    public static final ModConfigSpec.DoubleValue ATTACK_START_RANGE_MULTIPLIER = SERVER_BUILDER
            .comment("How much of the selected Better Combat attack range is used by the AI reach check. "
                    + "The original 1.20.1 mod used 1.0; the separate start delay lets the mob close distance before the visible swing begins.")
            .defineInRange("attackStartRangeMultiplier", 1.0D, 0.25D, 1.0D);

    public static final ModConfigSpec.DoubleValue ADDITIONAL_ATTACK_RANGE = SERVER_BUILDER
            .comment("Extra blocks added to Better Combat mob melee reach and hit detection.")
            .defineInRange("additionalAttackRange", 0.0D, 0.0D, 16.0D);

    public static final ModConfigSpec.IntValue ATTACK_START_DELAY = SERVER_BUILDER
            .comment("Ticks between the AI deciding it is in range and the Better Combat upswing beginning. "
                    + "Matches the original 1.20.1 Better Mob Combat default and prevents attacks from visibly starting at maximum reach.")
            .defineInRange("attackStartDelayTicks", 10, 0, 100);

    public static final ModConfigSpec.IntValue ADDITIONAL_ATTACK_COOLDOWN = SERVER_BUILDER
            .comment("Extra recovery ticks after a Better Combat attack interval. The original 1.20.1 default was 7.")
            .defineInRange("additionalAttackCooldownTicks", 7, 0, 100);

    public static final ModConfigSpec.BooleanValue TRACK_TARGET_DURING_WINDUP = SERVER_BUILDER
            .comment("Continuously force a mob to face its intended target during the entire windup. "
                    + "Disabled by default so telegraphed swings can be sidestepped.")
            .define("trackTargetDuringWindup", false);

    public static final ModConfigSpec.IntValue MINIMUM_ATTACK_INTERVAL = SERVER_BUILDER
            .comment("Fastest allowed attack interval, in ticks. Prevents extreme attack-speed values from creating hit spam.")
            .defineInRange("minimumAttackIntervalTicks", 5, 1, 40);

    public static final ModConfigSpec.IntValue COMBO_RESET_TICKS = SERVER_BUILDER
            .comment("Ticks without starting another attack before the mob returns to the first combo animation.")
            .defineInRange("comboResetTicks", 30, 1, 400);

    public static final ModConfigSpec.DoubleValue ADDITIONAL_UPSWING_MULTIPLIER = SERVER_BUILDER
            .comment("Amount added to Better Combat's own upswing multiplier for mob attacks. "
                    + "This matches the original 1.20.1 Better Mob Combat behavior: 0.0 uses Better Combat's normal timing, "
                    + "while positive values make the telegraphed upswing longer.")
            .defineInRange("additionalUpswingMultiplier", 0.15D, 0.0D, 0.8D);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ENTITY_BLACKLIST = SERVER_BUILDER
            .comment("Entity types that keep vanilla combat and vanilla animations entirely.",
                    "Accepts entity type ids, entity type tags (tags are prefixed with '#'), and namespace wildcards.",
                    "A bare id is assumed to be in the 'minecraft' namespace, so \"warden\" and",
                    "\"minecraft:warden\" both work. Use \"minecolonies:*\" to exclude every entity from a mod.",
                    "Entries are case-sensitive and must be lowercase.",
                    "Examples: [\"minecraft:warden\", \"#minecraft:raiders\", \"minecolonies:*\"]")
            .defineListAllowEmpty("entityBlacklist", List.of(), BMCConfig::validEntityEntry);

    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = SERVER_BUILDER
            .comment("Log one-off diagnostics about resolved weapon poses and attack animations. "
                    + "Useful when reporting a bug; noisy otherwise.")
            .define("debugLogging", false);

    public static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();

    // CLIENT

    private static final ModConfigSpec.Builder CLIENT_BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLE_RANGED_ANIMATIONS = CLIENT_BUILDER
            .comment("Add enhanced bow, crossbow, spear, and generic projectile-weapon animations to humanoid mobs.")
            .define("enableRangedAnimations", true);

    public static final ModConfigSpec.BooleanValue ENABLE_WEAPON_IDLE_POSES = CLIENT_BUILDER
            .comment("Apply Better Combat weapon idle poses to humanoid mobs, including two-handed bow and crossbow stances.")
            .define("enableWeaponIdlePoses", true);

    public static final ModConfigSpec.DoubleValue CLIENT_HIT_TIMING_NUDGE_TICKS = CLIENT_BUILDER
            .comment("Fine visual-only adjustment (in ticks, 1 tick = 0.05s) to when the swing animation "
                    + "appears to make contact, relative to the server's actual damage tick. This does NOT "
                    + "change when damage is applied - the server tick rate can't be adjusted more finely "
                    + "than whole ticks - it only re-paces the client animation playback. Positive values "
                    + "make the visible swing reach its hit pose later (sound/damage happens first); "
                    + "negative values make it reach the hit pose earlier. Try small values like 0.1-0.3.")
            .defineInRange("clientHitTimingNudgeTicks", 0.0D, -5.0D, 5.0D);

    public static final ModConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();
    private static volatile Set<ResourceLocation> blacklistIds = Set.of();
    private static volatile Set<TagKey<EntityType<?>>> blacklistTags = Set.of();
    private static volatile Set<String> blacklistNamespaces = Set.of();

    private BMCConfig() {
    }

    public static void rebuildCaches() {
        Set<ResourceLocation> ids = new HashSet<>();
        Set<TagKey<EntityType<?>>> tags = new HashSet<>();
        Set<String> namespaces = new HashSet<>();

        for (String raw : ENTITY_BLACKLIST.get()) {
            if (raw == null) {
                continue;
            }
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            boolean isTag = entry.startsWith("#");
            String value = isTag ? entry.substring(1) : entry;
            if (!isTag && value.endsWith(":*") && value.length() > 2) {
                String namespace = value.substring(0, value.length() - 2);
                if (ResourceLocation.tryParse(namespace + ":placeholder") != null) {
                    namespaces.add(namespace);
                }
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(value);
            if (id == null) {
                continue;
            }
            if (isTag) {
                tags.add(TagKey.create(Registries.ENTITY_TYPE, id));
            } else {
                ids.add(id);
            }
        }

        blacklistIds = Set.copyOf(ids);
        blacklistTags = Set.copyOf(tags);
        blacklistNamespaces = Set.copyOf(namespaces);
    }

    /** Built-in mobs that animate and time their own attacks (the Zombie Lord boss); never configurable. */
    private static final Set<ResourceLocation> SELF_ANIMATED = Set.of(
            ResourceLocation.fromNamespaceAndPath("fiercerbattles", "zombie_lord"));

    public static boolean isBlacklisted(EntityType<?> type) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (id != null && SELF_ANIMATED.contains(id)) {
            return true;
        }
        Set<ResourceLocation> ids = blacklistIds;
        Set<TagKey<EntityType<?>>> tags = blacklistTags;
        Set<String> namespaces = blacklistNamespaces;
        if (ids.isEmpty() && tags.isEmpty() && namespaces.isEmpty()) {
            return false;
        }

        if (id != null) {
            if (ids.contains(id)) {
                return true;
            }
            if (namespaces.contains(id.getNamespace())) {
                return true;
            }
        }

        for (TagKey<EntityType<?>> tag : tags) {
            if (type.is(tag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean validEntityEntry(Object value) {
        if (!(value instanceof String raw)) {
            return false;
        }
        String entry = raw.trim();
        if (entry.isEmpty()) {
            return false;
        }
        if (!entry.startsWith("#") && entry.endsWith(":*") && entry.length() > 2) {
            String namespace = entry.substring(0, entry.length() - 2);
            return ResourceLocation.tryParse(namespace + ":placeholder") != null;
        }
        String id = entry.startsWith("#") ? entry.substring(1) : entry;
        return ResourceLocation.tryParse(id) != null;
    }
}