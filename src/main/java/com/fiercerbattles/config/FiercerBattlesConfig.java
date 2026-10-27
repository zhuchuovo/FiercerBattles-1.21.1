package com.fiercerbattles.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.fiercerbattles.boss.BossEntities;
import com.fiercerbattles.execution.ExecutionWeapons;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;

public final class FiercerBattlesConfig {
    private FiercerBattlesConfig() {}

    public static class Entry {
        public String animation;
        public Double upswing;
        public Float movement_speed_multiplier;
        public Float attack_speed_multiplier;
        public Float range_multiplier;
        public Double range_bonus;
        public Double after_cooldown;
        public String attack_displacement;
        public String Multiplehits;
        public Double fixed_damage;
        public Double flat_damage;
        public Double knockback;
        public Double knockback_bonus;
        public Boolean hit_interruptible;
    }

    public static class Displacement {
        public double distance;
        public double durationTicks;

        public Displacement(double distance, double durationTicks) {
            this.distance = distance;
            this.durationTicks = durationTicks;
        }
    }
    public static class Root {
        public List<Entry> entries = List.of();
        public Execution execution = new Execution();
        public Hitstun hitstun = new Hitstun();
        public Parry parry = new Parry();
    }

    /** Successful parry: counter damage to the attacker and a riposte window while it is stunned. */
    public static class Parry {
        public double counter_damage = 4.0;
        /** Execution health ratio while the target is stunned by a parry (normally execution.health_threshold). */
        public double stagger_execution_health_threshold = 0.6;

        public float counterDamage() {
            return Double.isFinite(counter_damage) && counter_damage > 0 ? (float) Math.min(counter_damage, 1000) : 0;
        }

        public double staggerExecutionHealthThreshold() {
            return Double.isFinite(stagger_execution_health_threshold) && stagger_execution_health_threshold > 0
                    && stagger_execution_health_threshold <= 1 ? stagger_execution_health_threshold : 0.6;
        }
    }

    /** Hit reaction for players: a short flinch that cancels interruptible attacks. */
    public static class Hitstun {
        public boolean enabled = true;
        /** Used when neither the attack segment, its weapon file nor an entry sets hit_interruptible. */
        public boolean default_hit_interruptible = true;
        /** Adult mobs listed in execution.humanoid_entities flinch too, losing the attack they were winding up. */
        public boolean humanoid_mobs = true;
        /** Humanoid mobs that never flinch even when humanoid_mobs is on. */
        public List<String> exempt_mobs = List.of("minecraft:zombie", "minecraft:husk");

        public boolean exempts(LivingEntity entity) {
            return exempt_mobs != null
                    && exempt_mobs.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        }
    }

    /** The entity allowlist also declares that these mobs use an adult humanoid model. */
    public static class Execution {
        private static final List<Integer> DEFAULT_HURT_SOUND_FRAMES = List.of(12, 20, 24, 28, 32, 36, 40, 48);
        private static final List<Integer> DEFAULT_SWING_SOUND_FRAMES = List.of(12, 24);

        public boolean enabled = true;
        public double health_threshold = 0.25;
        public double range = 3.0;
        /** Retained for existing configs: the first paired action is selected by swords. */
        public String action_id = ExecutionWeapons.SWORD_ACTION;
        public String axe_action_id = ExecutionWeapons.AXE_ACTION;
        /** Mobs rendered by a standard HumanoidModel; wider or rescaled models are left to explicit configs. */
        public List<String> humanoid_entities = List.of(
                "minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:zombie_villager",
                "minecraft:skeleton", "minecraft:stray", "minecraft:bogged",
                "minecraft:piglin", "minecraft:piglin_brute", "minecraft:zombified_piglin");
        /** Configurable sword hurt sounds; axes use the single authored frame 13. */
        public List<Integer> hurt_sound_frames = DEFAULT_HURT_SOUND_FRAMES;
        public List<Integer> swing_sound_frames = DEFAULT_SWING_SOUND_FRAMES;

        public double healthThreshold() {
            return Double.isFinite(health_threshold) && health_threshold > 0 && health_threshold <= 1
                    ? health_threshold : 0.25;
        }

        public double range() {
            return Double.isFinite(range) && range > 0 && range <= 6 ? range : 3.0;
        }

        public boolean allows(LivingEntity entity) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            return BossEntities.BUILTIN_HUMANOIDS.contains(id) || humanoid_entities != null && humanoid_entities.contains(id);
        }

        public List<Integer> hurtSoundFrames(int durationTicks) {
            return soundFrames(hurt_sound_frames, DEFAULT_HURT_SOUND_FRAMES, durationTicks);
        }

        /** Axe executions use only authored frame 13; approach ticks are outside the clip. */
        public List<Integer> hurtSoundFrames(int durationTicks, boolean axe) {
            if (!axe) return hurtSoundFrames(durationTicks);
            return durationTicks >= 13 ? List.of(13) : List.of();
        }

        public List<Integer> swingSoundFrames(int durationTicks) {
            return soundFrames(swing_sound_frames, DEFAULT_SWING_SOUND_FRAMES, durationTicks);
        }

        private static List<Integer> soundFrames(List<Integer> configured, List<Integer> defaults, int durationTicks) {
            return (configured == null ? defaults : configured).stream()
                    .filter(Objects::nonNull)
                    .filter(frame -> frame >= 0 && frame <= durationTicks)
                    .distinct().sorted().toList();
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile Root root;

    public static Root get() {
        Root current = root;
        if (current != null) {
            return current;
        }
        synchronized (FiercerBattlesConfig.class) {
            current = root;
            if (current == null) {
                current = load();
                root = current;
            }
            return current;
        }
    }

    private static Root load() {
        Path path = FMLPaths.CONFIGDIR.get().resolve("fiercerbattles.json");
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                Root loaded = GSON.fromJson(reader, Root.class);
                if (loaded != null) {
                    if (loaded.entries == null) {
                        loaded.entries = List.of();
                    }
                    if (loaded.execution == null) {
                        loaded.execution = new Execution();
                    }
                    if (loaded.hitstun == null) {
                        loaded.hitstun = new Hitstun();
                    }
                    if (loaded.parry == null) {
                        loaded.parry = new Parry();
                    }
                    return loaded;
                }
            } catch (IOException | RuntimeException ignored) {
                // A malformed or half-written config file must never crash an
                // attack; fall back to an empty configuration.
            }
        }
        Root empty = new Root();
        empty.entries = new java.util.ArrayList<>();
        return empty;
    }

    public static Execution getExecution() {
        return get().execution;
    }

    public static Hitstun getHitstun() {
        return get().hitstun;
    }

    public static Parry getParry() {
        return get().parry;
    }

    /** Per-animation override of whether being hit interrupts the attack, or null when unset. */
    public static Boolean getHitInterruptible(String animation) {
        for (Entry entry : get().entries) {
            if (entry.animation != null && entry.animation.equals(animation) && entry.hit_interruptible != null) {
                return entry.hit_interruptible;
            }
        }
        return null;
    }

    public static Double getUpswing(String animation) {
        for (Entry entry : get().entries) {
            if (entry.animation != null && entry.animation.equals(animation) && entry.upswing != null) {
                return entry.upswing;
            }
        }
        return null;
    }

    public static Float getMovementSpeedMultiplier(String animation) {
        for (Entry entry : get().entries) {
            if (entry.animation != null && entry.animation.equals(animation) && entry.movement_speed_multiplier != null) {
                return entry.movement_speed_multiplier;
            }
        }
        return null;
    }

    public static Float getRangeMultiplier(String animation) {
        for (Entry entry : get().entries) {
            if (entry.animation != null && entry.animation.equals(animation) && entry.range_multiplier != null) {
                return entry.range_multiplier;
            }
        }
        return null;
    }

    public static Float getAttackSpeedMultiplier(String animation) {
        for (Entry entry : get().entries) {
            if (entry.animation != null && entry.animation.equals(animation) && entry.attack_speed_multiplier != null) {
                return entry.attack_speed_multiplier;
            }
        }
        return null;
    }

    public static Double getRangeBonus(String animation) {
        for (Entry entry : get().entries) {
            if (entry.animation != null && entry.animation.equals(animation) && entry.range_bonus != null) {
                return entry.range_bonus;
            }
        }
        return null;
    }

    public static Double getAfterCooldown(String animation) {
        for (Entry entry : get().entries) {
            if (entry.animation != null && entry.animation.equals(animation) && entry.after_cooldown != null) {
                return entry.after_cooldown;
            }
        }
        return null;
    }

    public static com.fiercerbattles.FiercerBattlesCustomData.MultipleHits getMultipleHits(String animation) {
        for (Entry entry : get().entries) {
            if (entry.animation != null && entry.animation.equals(animation) && entry.Multiplehits != null) {
                return com.fiercerbattles.FiercerBattlesCustomData.parseMultipleHits(entry.Multiplehits);
            }
        }
        return null;
    }
    public static Displacement getDisplacement(String animation) {
        for (Entry entry : get().entries) {
            if (entry.animation != null && entry.animation.equals(animation) && entry.attack_displacement != null) {
                String[] parts = entry.attack_displacement.split("[,，]");
                if (parts.length >= 2) {
                    try {
                        // Format: "durationSeconds,distanceBlocks"
                        // Example: "0.2,1" = move 1 block over 0.2 seconds.
                        double durationSeconds = Double.parseDouble(parts[0].trim());
                        double distance = Double.parseDouble(parts[1].trim());
                        if (durationSeconds <= 0 || distance <= 0) {
                            return null;
                        }
                        double durationTicks = durationSeconds * 20.0;
                        return new Displacement(distance, durationTicks);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return null;
    }

    /** Flat damage for the animation from the global config, or 0 when unset. */
    public static double getFixedDamage(String animation) {
        for (Entry entry : get().entries) {
            if (entry.animation != null && entry.animation.equals(animation)) {
                if (entry.fixed_damage != null) {
                    return Math.max(0, entry.fixed_damage);
                }
                if (entry.flat_damage != null) {
                    return Math.max(0, entry.flat_damage);
                }
            }
        }
        return 0;
    }

    /** Extra knockback (blocks) for the animation from the global config, or 0 when unset. */
    public static double getKnockback(String animation) {
        for (Entry entry : get().entries) {
            if (entry.animation != null && entry.animation.equals(animation)) {
                if (entry.knockback != null) {
                    return Math.max(0, entry.knockback);
                }
                if (entry.knockback_bonus != null) {
                    return Math.max(0, entry.knockback_bonus);
                }
            }
        }
        return 0;
    }
}
