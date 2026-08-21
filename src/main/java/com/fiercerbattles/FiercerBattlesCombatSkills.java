package com.fiercerbattles;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FiercerBattlesCombatSkills {
    private static final int COMBAT_SKILL_COMBO_MARKER = 1_000_000;
    private static final Gson GSON = new Gson();
    private static final Map<String, Definition> DEFINITIONS = new HashMap<>();
    private static final Map<String, String> ALIASES = new HashMap<>();

    private static Selection armedClientSelection;
    private static Selection activeClientSelection;

    private FiercerBattlesCombatSkills() {
    }

    public static void load(ResourceManager resourceManager) {
        DEFINITIONS.clear();
        ALIASES.clear();
        Map<ResourceLocation, Resource> resources = resourceManager.listResources(
                "weapon_attributes",
                location -> location.getPath().endsWith(".json")
        );
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            try (InputStream input = entry.getValue().open();
                 InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                JsonObject marker = JsonParser.parseReader(reader).getAsJsonObject();
                if (!isEnabled(marker) || !marker.has("json") || !marker.get("json").isJsonPrimitive()) {
                    continue;
                }
                JsonObject skillRoot = readSkillFile(resourceManager, entry.getKey(), marker.get("json").getAsString());
                if (skillRoot == null || !skillRoot.has("attributes") || !skillRoot.get("attributes").isJsonObject()) {
                    continue;
                }
                JsonObject attributes = skillRoot.getAsJsonObject("attributes");
                DEFINITIONS.put(itemId(entry.getKey()), new Definition(attributes));
            } catch (Exception ignored) {
            }
        }
    }

    public static void registerAlias(String itemId, String sourceId) {
        if (itemId != null && sourceId != null) {
            ALIASES.putIfAbsent(itemId, sourceId);
        }
    }

    public static Object armClientSkill(Object hand) {
        Selection selection = selectionForHand(hand);
        if (selection == null) {
            return null;
        }
        armedClientSelection = selection;
        return selection.attributes;
    }

    public static void clearArmedClientSkill() {
        armedClientSelection = null;
    }    public static void clearActiveClientSkillBeforeNormalAttack() {
        if (armedClientSelection == null) {
            activeClientSelection = null;
        }
    }


    public static Object replaceArmedClientHand(Object normalHand) {
        Selection selection = armedClientSelection;
        if (selection == null) {
            return normalHand;
        }
        armedClientSelection = null;
        Object skillHand = createSkillHand(normalHand, selection);
        if (skillHand == null) {
            activeClientSelection = null;
            return normalHand;
        }
        activeClientSelection = selection;
        return skillHand;
    }

    private static final int MULTIPLE_HIT_COMBO_MARKER = 1_000_000_000;
    private static final int MULTIPLE_HIT_SKILL_FLAG = 500_000_000;
    private static final int MULTIPLE_HIT_INDEX_FACTOR = 100_000;

    public static int encodeAttackPacket(int comboCount, int multipleHitIndex, boolean finalMultipleHit) {
        int safeComboCount = Math.max(comboCount, 0);
        if (multipleHitIndex >= 0) {
            boolean combatSkill = activeClientSelection != null;
            if (finalMultipleHit) {
                activeClientSelection = null;
            }
            return MULTIPLE_HIT_COMBO_MARKER
                    + (combatSkill ? MULTIPLE_HIT_SKILL_FLAG : 0)
                    + multipleHitIndex * MULTIPLE_HIT_INDEX_FACTOR
                    + Math.min(safeComboCount, MULTIPLE_HIT_INDEX_FACTOR - 1);
        }
        if (activeClientSelection == null) {
            return safeComboCount;
        }
        activeClientSelection = null;
        return COMBAT_SKILL_COMBO_MARKER + safeComboCount;
    }

    public static boolean isMultipleHitCombo(int comboCount) {
        return comboCount >= MULTIPLE_HIT_COMBO_MARKER;
    }

    public static boolean isCombatSkillCombo(int comboCount) {
        if (isMultipleHitCombo(comboCount)) {
            return comboCount - MULTIPLE_HIT_COMBO_MARKER >= MULTIPLE_HIT_SKILL_FLAG;
        }
        return comboCount >= COMBAT_SKILL_COMBO_MARKER;
    }

    public static int decodeCombatSkillCombo(int comboCount) {
        if (isMultipleHitCombo(comboCount)) {
            int payload = comboCount - MULTIPLE_HIT_COMBO_MARKER;
            if (payload >= MULTIPLE_HIT_SKILL_FLAG) {
                payload -= MULTIPLE_HIT_SKILL_FLAG;
            }
            return payload % MULTIPLE_HIT_INDEX_FACTOR;
        }
        return isCombatSkillCombo(comboCount) ? comboCount - COMBAT_SKILL_COMBO_MARKER : comboCount;
    }

    public static int getMultipleHitIndex(int comboCount) {
        if (!isMultipleHitCombo(comboCount)) {
            return -1;
        }
        int payload = comboCount - MULTIPLE_HIT_COMBO_MARKER;
        if (payload >= MULTIPLE_HIT_SKILL_FLAG) {
            payload -= MULTIPLE_HIT_SKILL_FLAG;
        }
        return payload / MULTIPLE_HIT_INDEX_FACTOR;
    }

    public static Object replaceEncodedSkillHand(Object normalHand, int encodedComboCount) {
        if (!isCombatSkillCombo(encodedComboCount)) {
            return normalHand;
        }
        Selection selection = selectionForHand(normalHand);
        Object skillHand = selection != null ? createSkillHand(normalHand, selection) : null;
        return skillHand != null ? skillHand : normalHand;
    }

    public static FiercerBattlesCustomData.AttackData getActiveSkillCustomData() {
        return activeClientSelection != null ? activeClientSelection.customData : null;
    }

    private static Selection selectionForHand(Object hand) {
        if (hand == null) {
            return null;
        }
        String itemId = itemIdForHand(hand);
        Definition definition = definitionFor(itemId);
        if (definition == null) {
            return null;
        }
        int normalAttackIndex = attackIndex(hand);
        if (normalAttackIndex < 0) {
            return null;
        }
        Object attributes = definition.attributes();
        Object attack = definition.attackFor(normalAttackIndex, attributes);
        if (attack == null) {
            return null;
        }
        return new Selection(attributes, attack, definition.customDataFor(normalAttackIndex));
    }

    public static FiercerBattlesCustomData.AttackData getCombatSkillDataForHand(Object hand) {
        Selection selection = selectionForHand(hand);
        return selection != null ? selection.customData : null;
    }
    private static Definition definitionFor(String itemId) {
        Definition definition = DEFINITIONS.get(itemId);
        if (definition == null) {
            String alias = ALIASES.get(itemId);
            if (alias != null) {
                definition = DEFINITIONS.get(alias);
            }
        }
        return definition;
    }

    private static Object createSkillHand(Object normalHand, Selection selection) {
        try {
            Object combo = invokeNoArg(normalHand, "combo");
            Object itemStack = invokeNoArg(normalHand, "itemStack");
            Object offHand = invokeNoArg(normalHand, "isOffHand");
            if (!(itemStack instanceof ItemStack) || !(offHand instanceof Boolean)) {
                return null;
            }
            for (Constructor<?> constructor : normalHand.getClass().getDeclaredConstructors()) {
                if (constructor.getParameterCount() != 5) {
                    continue;
                }
                constructor.setAccessible(true);
                return constructor.newInstance(selection.attack, combo, offHand, selection.attributes, itemStack);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    public static int attackIndex(Object hand) {
        try {
            Object attributes = invokeNoArg(hand, "attributes");
            Object attack = invokeNoArg(hand, "attack");
            if (attributes == null || attack == null) {
                return -1;
            }
            Field attacksField = attributes.getClass().getDeclaredField("attacks");
            attacksField.setAccessible(true);
            Object[] attacks = (Object[]) attacksField.get(attributes);
            if (attacks == null) {
                return -1;
            }
            for (int index = 0; index < attacks.length; index++) {
                if (attacks[index] == attack) {
                    return index;
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return -1;
    }

    public static String itemIdForHand(Object hand) {
        try {
            Object stack = invokeNoArg(hand, "itemStack");
            if (stack instanceof ItemStack itemStack) {
                String preset = presetId(itemStack);
                return preset != null ? preset : BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String presetId(ItemStack stack) {
        try {
            Class<?> componentClass = Class.forName("net.bettercombat.api.component.BetterCombatDataComponents");
            Object componentType = componentClass.getField("WEAPON_PRESET_ID").get(null);
            Method getMethod = stack.getClass().getMethod("get", Class.forName("net.minecraft.core.component.DataComponentType"));
            Object value = getMethod.invoke(stack, componentType);
            return value instanceof ResourceLocation id ? id.toString() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }



    private static boolean isEnabled(JsonObject marker) {
        return marker.has("Combatskill") && marker.get("Combatskill").isJsonPrimitive()
                && marker.get("Combatskill").getAsBoolean()
                || marker.has("combat_skill") && marker.get("combat_skill").isJsonPrimitive()
                && marker.get("combat_skill").getAsBoolean();
    }

    private static JsonObject readSkillFile(ResourceManager resourceManager, ResourceLocation source, String reference) {
        for (ResourceLocation location : candidateLocations(source, reference)) {
            Optional<Resource> resource = resourceManager.getResource(location);
            if (resource.isEmpty()) {
                continue;
            }
            try (InputStream input = resource.get().open();
                 InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (root.isJsonObject()) {
                    return root.getAsJsonObject();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static List<ResourceLocation> candidateLocations(ResourceLocation source, String reference) {
        String fileName = reference.endsWith(".json") ? reference : reference + ".json";
        List<ResourceLocation> locations = new ArrayList<>();
        if (fileName.indexOf(':') >= 0) {
            ResourceLocation explicit = ResourceLocation.tryParse(fileName);
            if (explicit != null) {
                locations.add(explicit);
            }
            return locations;
        }
        locations.add(ResourceLocation.fromNamespaceAndPath(source.getNamespace(), "fiercerbattles/combat_skills/" + fileName));
        String path = source.getPath();
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0) {
            locations.add(ResourceLocation.fromNamespaceAndPath(source.getNamespace(), path.substring(0, lastSlash + 1) + fileName));
        }
        return locations;
    }

    private static String itemId(ResourceLocation location) {
        String path = location.getPath();
        String prefix = "weapon_attributes/";
        if (!path.startsWith(prefix) || !path.endsWith(".json")) {
            return null;
        }
        return location.getNamespace() + ":" + path.substring(prefix.length(), path.length() - ".json".length());
    }

    private static final class Definition {
        private final JsonObject attributesJson;
        private final List<FiercerBattlesCustomData.AttackData> customData;
        private Object attributes;

        private Definition(JsonObject attributesJson) {
            this.attributesJson = attributesJson.deepCopy();
            this.customData = parseCustomData(attributesJson);
        }

        private Object attributes() {
            if (attributes == null) {
                try {
                    Class<?> attributesClass = Class.forName("net.bettercombat.api.WeaponAttributes");
                    attributes = GSON.fromJson(attributesJson, attributesClass);
                    Field attacksField = attributesClass.getDeclaredField("attacks");
                    attacksField.setAccessible(true);
                    Object[] attacks = (Object[]) attacksField.get(attributes);
                    if (attacks != null) {
                        for (int index = 0; index < attacks.length; index++) {
                            if (attacks[index] != null) {
                                FiercerBattlesCustomData.applyToAttack(attacks[index], customDataFor(index));
                            }
                        }
                    }
                } catch (ReflectiveOperationException ignored) {
                    attributes = null;
                }
            }
            return attributes;
        }

        private Object attackFor(int normalAttackIndex, Object resolvedAttributes) {
            if (resolvedAttributes == null) {
                return null;
            }
            try {
                Field attacksField = resolvedAttributes.getClass().getDeclaredField("attacks");
                attacksField.setAccessible(true);
                Object[] attacks = (Object[]) attacksField.get(resolvedAttributes);
                if (attacks == null) {
                    return null;
                }
                int lastMatchingIndex = -1;
                for (int index = 0; index < attacks.length && index <= normalAttackIndex; index++) {
                    if (hasAnimation(attacks[index])) {
                        lastMatchingIndex = index;
                    }
                }
                return lastMatchingIndex >= 0 ? attacks[lastMatchingIndex] : null;
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }

        private FiercerBattlesCustomData.AttackData customDataFor(int index) {
            return index >= 0 && index < customData.size() ? customData.get(index) : null;
        }

        private static List<FiercerBattlesCustomData.AttackData> parseCustomData(JsonObject attributes) {
            List<FiercerBattlesCustomData.AttackData> values = new ArrayList<>();
            if (!attributes.has("attacks") || !attributes.get("attacks").isJsonArray()) {
                return values;
            }
            JsonArray attacks = attributes.getAsJsonArray("attacks");
            for (JsonElement attack : attacks) {
                values.add(attack.isJsonObject() ? FiercerBattlesCustomData.parseAttackData(attack.getAsJsonObject()) : null);
            }
            return values;
        }

        private static boolean hasAnimation(Object attack) {
            if (attack == null) {
                return false;
            }
            try {
                Field animationField = attack.getClass().getDeclaredField("animation");
                animationField.setAccessible(true);
                Object animation = animationField.get(attack);
                return animation instanceof String value && !value.isBlank();
            } catch (ReflectiveOperationException ignored) {
                return false;
            }
        }
    }

    private record Selection(Object attributes, Object attack, FiercerBattlesCustomData.AttackData customData) {
    }
}
