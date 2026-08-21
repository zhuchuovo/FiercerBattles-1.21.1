package com.fiercerbattles.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Root root;

    public static Root get() {
        if (root == null) {
            root = load();
        }
        return root;
    }

    private static Root load() {
        Path path = FMLPaths.CONFIGDIR.get().resolve("fiercerbattles.json");
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                Root loaded = GSON.fromJson(reader, Root.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (IOException ignored) {
            }
        }
        Root empty = new Root();
        empty.entries = new java.util.ArrayList<>();
        return empty;
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
}
