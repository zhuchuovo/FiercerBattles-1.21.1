package com.fiercerbattles;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.fiercerbattles.config.FiercerBattlesConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads custom FiercerBattles fields from BetterCombat weapon_attributes JSON files.
 *
 * Data is stored per item id and per attack index, so repeated animations
 * (for example dagger combos) can use different values for each combo step.
 */
public final class FiercerBattlesCustomData {
    private FiercerBattlesCustomData() {}

    public static class AttackData {
        public double distance;
        public double durationTicks;
        public boolean hasDisplacement = false;

        public double rangeBonus = 0;
        public double afterCooldown = 0;

        public float attackSpeedMultiplier = 0;
        public double upswing = 0;
        public float movementSpeedMultiplier = 0;
        public float rangeMultiplier = 0;
        public MultipleHits multipleHits;
    }

    public static final class MultipleHits {
        public final int[] frames;
        public final float[] damageMultipliers;

        public MultipleHits(int[] frames, float[] damageMultipliers) {
            this.frames = frames;
            this.damageMultipliers = damageMultipliers;
        }

        public boolean isValidIndex(int index) {
            return index >= 0 && index < frames.length;
        }
    }
    private static final Map<String, List<AttackData>> DATA = new HashMap<>();

    /**
     * Maps item ids whose attributes were resolved from another weapon
     * attributes container (for example daggers assigned the
     * `bettercombat:dagger` preset through BetterCombat's fallback
     * compatibility) to the container id that holds the custom data.
     */
    private static final Map<String, String> ALIASES = new HashMap<>();

    /**
     * Raw (pre-inheritance) content of one weapon_attributes file.
     */
    private static final class ContainerData {
        final String parent;
        final List<AttackData> attacks;

        ContainerData(String parent, List<AttackData> attacks) {
            this.parent = parent;
            this.attacks = attacks;
        }
    }

    public static void load(ResourceManager resourceManager) {
        DATA.clear();
        ALIASES.clear();
        Map<String, ContainerData> containers = new HashMap<>();
        Map<ResourceLocation, Resource> resources = resourceManager.listResources(
                "weapon_attributes",
                location -> location.getPath().endsWith(".json")
        );
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            String itemId = itemId(entry.getKey());
            if (itemId == null) {
                continue;
            }
            try (InputStream input = entry.getValue().open();
                 InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (root == null) {
                    continue;
                }
                String parent = root.has("parent") && root.get("parent").isJsonPrimitive()
                        ? root.get("parent").getAsString()
                        : null;
                List<AttackData> attacks = new ArrayList<>();
                if (root.has("attributes") && root.get("attributes").isJsonObject()) {
                    JsonObject attributes = root.getAsJsonObject("attributes");
                    if (attributes.has("attacks") && attributes.get("attacks").isJsonArray()) {
                        JsonArray attackArray = attributes.getAsJsonArray("attacks");
                        for (JsonElement element : attackArray) {
                            AttackData data = new AttackData();
                            if (element.isJsonObject()) {
                                parseAttack(element.getAsJsonObject(), data);
                            }
                            attacks.add(data);
                        }
                    }
                }
                containers.put(itemId, new ContainerData(parent, attacks));
            } catch (Exception ignored) {
            }
        }
        for (String itemId : containers.keySet()) {
            DATA.put(itemId, resolveInheritance(itemId, containers, new HashSet<>()));
        }
    }

    /**
     * Resolves the `parent` inheritance chain, mirroring BetterCombat's own
     * rule: when the item's file defines no attacks, the parent's attack list
     * is used entirely; otherwise parent attacks are merged element-wise and
     * the item's own non-zero fields win.
     */
    private static List<AttackData> resolveInheritance(String itemId, Map<String, ContainerData> containers, Set<String> visiting) {
        ContainerData container = containers.get(itemId);
        if (container == null) {
            return List.of();
        }
        if (container.parent == null || !containers.containsKey(container.parent)) {
            return container.attacks;
        }
        if (!visiting.add(container.parent)) {
            return container.attacks; // cycle guard
        }
        List<AttackData> parentAttacks = resolveInheritance(container.parent, containers, visiting);
        if (container.attacks.isEmpty()) {
            return parentAttacks;
        }
        List<AttackData> merged = new ArrayList<>(container.attacks.size());
        for (int i = 0; i < container.attacks.size(); i++) {
            AttackData base = i < parentAttacks.size() ? parentAttacks.get(i) : null;
            merged.add(merge(container.attacks.get(i), base));
        }
        return merged;
    }

    private static AttackData merge(AttackData child, AttackData parent) {
        if (parent == null) {
            return child;
        }
        AttackData merged = new AttackData();
        merged.attackSpeedMultiplier = child.attackSpeedMultiplier != 0 ? child.attackSpeedMultiplier : parent.attackSpeedMultiplier;
        merged.upswing = child.upswing != 0 ? child.upswing : parent.upswing;
        merged.movementSpeedMultiplier = child.movementSpeedMultiplier != 0 ? child.movementSpeedMultiplier : parent.movementSpeedMultiplier;
        merged.rangeMultiplier = child.rangeMultiplier != 0 ? child.rangeMultiplier : parent.rangeMultiplier;
        merged.rangeBonus = child.rangeBonus != 0 ? child.rangeBonus : parent.rangeBonus;
        merged.afterCooldown = child.afterCooldown != 0 ? child.afterCooldown : parent.afterCooldown;
        merged.multipleHits = child.multipleHits != null ? child.multipleHits : parent.multipleHits;
        if (child.hasDisplacement) {
            merged.distance = child.distance;
            merged.durationTicks = child.durationTicks;
            merged.hasDisplacement = true;
        } else if (parent.hasDisplacement) {
            merged.distance = parent.distance;
            merged.durationTicks = parent.durationTicks;
            merged.hasDisplacement = true;
        }
        return merged;
    }

    private static String itemId(ResourceLocation location) {
        String path = location.getPath();
        String prefix = "weapon_attributes/";
        if (!path.startsWith(prefix) || !path.endsWith(".json")) {
            return null;
        }
        String name = path.substring(prefix.length(), path.length() - ".json".length());
        return location.getNamespace() + ":" + name;
    }

    private static void parseAttack(JsonObject attack, AttackData data) {
        if (attack.has("attack_displacement") && attack.get("attack_displacement").isJsonPrimitive()) {
            String raw = attack.get("attack_displacement").getAsString();
            parseDisplacement(raw, data);
        }
        if (attack.has("range_bonus") && attack.get("range_bonus").isJsonPrimitive()) {
            data.rangeBonus = attack.get("range_bonus").getAsDouble();
        }
        if (attack.has("after_cooldown") && attack.get("after_cooldown").isJsonPrimitive()) {
            data.afterCooldown = attack.get("after_cooldown").getAsDouble();
        }
        if (attack.has("attack_speed_multiplier") && attack.get("attack_speed_multiplier").isJsonPrimitive()) {
            data.attackSpeedMultiplier = attack.get("attack_speed_multiplier").getAsFloat();
        }
        if (attack.has("upswing") && attack.get("upswing").isJsonPrimitive()) {
            data.upswing = attack.get("upswing").getAsDouble();
        }
        if (attack.has("movement_speed_multiplier") && attack.get("movement_speed_multiplier").isJsonPrimitive()) {
            data.movementSpeedMultiplier = attack.get("movement_speed_multiplier").getAsFloat();
        }
        if (attack.has("Multiplehits") && attack.get("Multiplehits").isJsonPrimitive()) {
            data.multipleHits = parseMultipleHits(attack.get("Multiplehits").getAsString());
        }        if (attack.has("range_multiplier") && attack.get("range_multiplier").isJsonPrimitive()) {
            data.rangeMultiplier = attack.get("range_multiplier").getAsFloat();
        }
    }

    public static MultipleHits getMultipleHits(AttackData customData, String animation) {
        if (customData != null && customData.multipleHits != null) {
            return customData.multipleHits;
        }
        return FiercerBattlesConfig.getMultipleHits(animation);
    }
    public static MultipleHits parseMultipleHits(String raw) {
        if (raw == null) {
            return null;
        }
        String[] groups = raw.trim().split("\\]\\s*,\\s*\\[", -1);
        if (groups.length != 2) {
            return null;
        }
        String frameText = groups[0].replaceFirst("^\\s*\\[", "").trim();
        String multiplierText = groups[1].replaceFirst("\\]\\s*$", "").trim();
        if (frameText.isEmpty() || multiplierText.isEmpty()) {
            return null;
        }
        String[] frameParts = frameText.split(",", -1);
        String[] multiplierParts = multiplierText.split(",", -1);
        if (frameParts.length != multiplierParts.length || frameParts.length == 0) {
            return null;
        }
        int[] frames = new int[frameParts.length];
        float[] multipliers = new float[multiplierParts.length];
        try {
            for (int index = 0; index < frameParts.length; index++) {
                frames[index] = Integer.parseInt(frameParts[index].trim());
                multipliers[index] = Float.parseFloat(multiplierParts[index].trim());
                if (frames[index] < 0 || multipliers[index] < 0 || index > 0 && frames[index] <= frames[index - 1]) {
                    return null;
                }
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return new MultipleHits(frames, multipliers);
    }
    public static AttackData parseAttackData(JsonObject attack) {
        AttackData data = new AttackData();
        parseAttack(attack, data);
        return data;
    }

    public static void applyToAttack(Object attack, AttackData custom) {
        try {
            Class<?> attackClass = attack.getClass();
            Field animationField = attackClass.getDeclaredField("animation");
            animationField.setAccessible(true);
            String animation = (String) animationField.get(attack);
            if (animation == null || animation.isEmpty()) {
                return;
            }

            Double upswing = custom != null && custom.upswing != 0
                    ? custom.upswing
                    : FiercerBattlesConfig.getUpswing(animation);
            if (upswing != null) {
                Field upswingField = attackClass.getDeclaredField("upswing");
                upswingField.setAccessible(true);
                upswingField.setDouble(attack, upswing);
            }

            Float movementSpeed = custom != null && custom.movementSpeedMultiplier != 0
                    ? custom.movementSpeedMultiplier
                    : FiercerBattlesConfig.getMovementSpeedMultiplier(animation);
            if (movementSpeed != null) {
                Field movementField = attackClass.getDeclaredField("movement_speed_multiplier");
                movementField.setAccessible(true);
                movementField.setFloat(attack, movementSpeed);
            }

            Float rangeMultiplier = custom != null && custom.rangeMultiplier != 0
                    ? custom.rangeMultiplier
                    : FiercerBattlesConfig.getRangeMultiplier(animation);
            if (rangeMultiplier != null) {
                Field rangeField = attackClass.getDeclaredField("range_multiplier");
                rangeField.setAccessible(true);
                rangeField.setFloat(attack, rangeMultiplier);
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }
    private static void parseDisplacement(String raw, AttackData data) {
        String[] parts = raw.split("[,，]");
        if (parts.length >= 2) {
            try {
                double durationSeconds = Double.parseDouble(parts[0].trim());
                double distance = Double.parseDouble(parts[1].trim());
                if (durationSeconds <= 0 || distance <= 0) {
                    return;
                }
                data.distance = distance;
                data.durationTicks = durationSeconds * 20.0;
                data.hasDisplacement = true;
            } catch (NumberFormatException ignored) {
            }
        }
    }

    public static AttackData get(String itemId, int attackIndex) {
        String key = itemId;
        if (key == null || !DATA.containsKey(key)) {
            String alias = ALIASES.get(key);
            if (alias != null) {
                key = alias;
            }
        }
        List<AttackData> list = key == null ? null : DATA.get(key);
        if (list == null || attackIndex < 0 || attackIndex >= list.size()) {
            return null;
        }
        return list.get(attackIndex);
    }

    /**
     * True if the item id has its own weapon_attributes file with parsed data.
     */
    public static boolean contains(String itemId) {
        return itemId != null && DATA.containsKey(itemId);
    }

    /**
     * Records that attributes for {@code itemId} were resolved from the
     * container {@code sourceId}, so custom data lookups can follow it.
     */
    public static void registerAlias(String itemId, String sourceId) {
        if (itemId != null && sourceId != null) {
            ALIASES.putIfAbsent(itemId, sourceId);
        }
    }

    public static boolean isEmpty() {
        return DATA.isEmpty();
    }
}
