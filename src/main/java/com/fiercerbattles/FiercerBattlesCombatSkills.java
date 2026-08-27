package com.fiercerbattles;

import com.mojang.logging.LogUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.entity.player.Player;

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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

public final class FiercerBattlesCombatSkills {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int COMBAT_SKILL_COMBO_MARKER = 1_000_000;
    private static final Gson GSON = new Gson();
    private static final Map<String, Definition> DEFINITIONS = new HashMap<>();
    private static final Map<String, String> ALIASES = new HashMap<>();
    /**
     * Better Combat receives only the item stack in getRangeForItem(). Keep the
     * currently selected skill range keyed by player so both the client and the
     * server can apply the skill's range to that lookup.
     */
    private static final Map<RangeKey, SkillRangeOverride> ACTIVE_SKILL_RANGES = new ConcurrentHashMap<>();

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
                LoadedJson loadedSkill = readSkillFile(resourceManager, entry.getKey(), marker.get("json").getAsString());
                if (loadedSkill == null || !loadedSkill.root.has("attributes")
                        || !loadedSkill.root.get("attributes").isJsonObject()) {
                    continue;
                }
                JsonObject skillRoot = loadedSkill.root;
                JsonObject attributes = skillRoot.getAsJsonObject("attributes").deepCopy();
                FiercerBattlesCustomData.SkillEffectData skillEffect =
                        FiercerBattlesCustomData.SkillEffectData.parse(skillRoot);
                if (skillEffect != null && !skillEffect.actionJson.isBlank()) {
                    LoadedJson action = readSkillFile(resourceManager, loadedSkill.location, skillEffect.actionJson);
                    if (action != null) {
                        applyActionJson(attributes, action.root);
                    }
                }
                String skillId = loadedSkill.location.toString();
                DEFINITIONS.put(itemId(entry.getKey()), new Definition(attributes, skillEffect, skillId));
            } catch (Exception exception) {
                LOGGER.warn("Failed to load combat skill marker {}", entry.getKey(), exception);
            }
        }
        LOGGER.info("Loaded {} Fiercer Battles combat skill definition(s)", DEFINITIONS.size());
    }

    public static void registerAlias(String itemId, String sourceId) {
        if (itemId != null && sourceId != null) {
            ALIASES.putIfAbsent(itemId, sourceId);
        }
    }

    public static Object armClientSkill(Object hand) {
        return armClientSkill(hand, null);
    }

    public static Object armClientSkill(Object hand, Object player) {
        Selection selection = selectionForHand(hand, player);
        if (selection == null) {
            return null;
        }
        armedClientSelection = selection;
        // Install the range override as soon as the skill is armed. Better
        // Combat queries its range from several paths (target preview,
        // upswing and the actual hit); waiting for getCurrentAttack() leaves
        // the first query on the vanilla/TConstruct reach value.
        setActiveSkillRange(player, hand, selection.customData);
        return selection.attributes;
    }

    public static void clearArmedClientSkill() {
        armedClientSelection = null;
    }

    public static void clearClientSkill() {
        armedClientSelection = null;
        activeClientSelection = null;
    }

    /**
     * Drops all client-side per-session state. The client player entity (and
     * its tick counter) is recreated on every world rejoin, so stale session
     * state must not survive a logout.
     */
    public static void clearClientState() {
        clearClientSkill();
        ACTIVE_SKILL_RANGES.keySet().removeIf(key -> !key.server());
    }

    public static FiercerBattlesCustomData.AttackData getArmedClientSkillCustomData() {
        return armedClientSelection != null ? armedClientSelection.customData : null;
    }

    public static Object replaceArmedClientHand(Object normalHand) {
        return replaceArmedClientHand(normalHand, null);
    }

    public static Object replaceArmedClientHand(Object normalHand, Object player) {
        Selection selection = armedClientSelection;
        if (selection != null) {
            armedClientSelection = null;
            if (!conditionsMatch(player, normalHand, selection.attack, selection.customData)) {
                activeClientSelection = null;
                return normalHand;
            }
            Object skillHand = createSkillHand(normalHand, selection);
            if (skillHand == null) {
                activeClientSelection = null;
                return normalHand;
            }
            activeClientSelection = selection;
            setActiveSkillRange(player, skillHand, selection.customData);
            return skillHand;
        }

        selection = activeClientSelection;
        if (selection != null) {
            if (!conditionsMatch(player, normalHand, selection.attack, selection.customData)) {
                activeClientSelection = null;
                return normalHand;
            }
            Object skillHand = createSkillHand(normalHand, selection);
            if (skillHand != null) {
                setActiveSkillRange(player, skillHand, selection.customData);
                return skillHand;
            }
        }
        return normalHand;
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
        return replaceEncodedSkillHand(normalHand, encodedComboCount, null);
    }

    public static Object replaceEncodedSkillHand(Object normalHand, int encodedComboCount, Object player) {
        if (!isCombatSkillCombo(encodedComboCount)) {
            return normalHand;
        }
        Selection selection = selectionForHand(normalHand, player);
        Object skillHand = selection != null ? createSkillHand(normalHand, selection) : null;
        if (skillHand != null && selection != null) {
            setActiveSkillRange(player, skillHand, selection.customData);
        }
        return skillHand != null ? skillHand : normalHand;
    }

    public static FiercerBattlesCustomData.AttackData getActiveSkillCustomData() {
        return activeClientSelection != null ? activeClientSelection.customData : null;
    }

    public static boolean isClientSkillPending() {
        return armedClientSelection != null || activeClientSelection != null;
    }

    public static void setActiveSkillRange(Object player, Object hand, FiercerBattlesCustomData.AttackData customData) {
        RangeKey playerKey = playerKey(player);
        if (playerKey == null || hand == null || customData == null
                || (customData.attackRange == 0 && customData.rangeBonus == 0)) {
            clearActiveSkillRange(player);
            return;
        }
        ACTIVE_SKILL_RANGES.put(playerKey, new SkillRangeOverride(
                customData.attackRange,
                customData.rangeBonus
        ));
    }

    public static void clearActiveSkillRange(Object player) {
        RangeKey playerKey = playerKey(player);
        if (playerKey != null) {
            ACTIVE_SKILL_RANGES.remove(playerKey);
        }
    }

    public static double applyActiveSkillRange(Object player, Object stack, double baseRange) {
        RangeKey playerKey = playerKey(player);
        SkillRangeOverride override = playerKey != null
                ? ACTIVE_SKILL_RANGES.get(playerKey)
                : null;
        // The client can query its target list while Better Combat is
        // rebuilding the AttackHand. During that small window the per-player
        // cache may not have been published yet (or may have just been
        // cleared by a normal-attack lookup). Use the still-active selection
        // as a direct fallback so the skill never falls back to the vanilla
        // one-block item range.
        if (override == null && !(player instanceof ServerPlayer)) {
            Selection selection = activeClientSelection != null
                    ? activeClientSelection
                    : armedClientSelection;
            if (selection != null && selection.customData != null) {
                override = new SkillRangeOverride(
                        selection.customData.attackRange,
                        selection.customData.rangeBonus
                );
            }
        }
        if (override == null) {
            return baseRange;
        }
        if (override.attackRange != 0) {
            return override.attackRange;
        }
        return baseRange + override.rangeBonus;
    }

    private static RangeKey playerKey(Object player) {
        if (!(player instanceof Player actualPlayer)) {
            return null;
        }
        return new RangeKey(actualPlayer.getUUID(), player instanceof ServerPlayer);
    }

    public static boolean isActiveClientSkillHand(Object hand) {
        Selection selection = activeClientSelection;
        return selection != null && hand != null && invokeNoArg(hand, "attack") == selection.attack;
    }

    private static Selection selectionForHand(Object hand) {
        return selectionForHand(hand, null);
    }

    private static Selection selectionForHand(Object hand, Object player) {
        if (hand == null) {
            return null;
        }
        Definition definition = definitionForHand(hand);
        if (definition == null) {
            return null;
        }
        int normalAttackIndex = attackIndex(hand);
        if (normalAttackIndex < 0) {
            return null;
        }
        Object attributes = definition.attributes();
        int skillAttackIndex = definition.attackIndexFor(normalAttackIndex, attributes);
        Object attack = definition.attackFor(skillAttackIndex, attributes);
        FiercerBattlesCustomData.AttackData customData = definition.customDataFor(skillAttackIndex);
        if (attack == null || !conditionsMatch(player, hand, attack, customData)) {
            return null;
        }
        return new Selection(attributes, attack, customData);
    }

    private static Definition definitionForHand(Object hand) {
        Definition definition = definitionFor(itemIdForHand(hand));
        if (definition != null) {
            return definition;
        }
        return definitionFor(registryItemIdForHand(hand));
    }

    public static FiercerBattlesCustomData.AttackData getCombatSkillDataForHand(Object hand) {
        return getCombatSkillDataForHand(hand, null);
    }

    public static FiercerBattlesCustomData.AttackData getCombatSkillDataForHand(Object hand, Object player) {
        Selection selection = selectionForHand(hand, player);
        return selection != null ? selection.customData : null;
    }

    /**
     * BetterCombat filters its normal attack list before returning an AttackHand.
     * Combat-skill attacks are selected afterwards, so their conditions must be
     * checked here as well. Conditions are an AND list, matching BetterCombat.
     */
    private static boolean conditionsMatch(
            Object player,
            Object hand,
            Object attack,
            FiercerBattlesCustomData.AttackData customData
    ) {
        Object conditions = invokeNoArg(attack, "conditions");
        if (conditions instanceof Object[] values && values.length > 0) {
            for (Object value : values) {
                if (value != null && !evaluateCondition(value.toString(), player, hand)) {
                    return false;
                }
            }
            return true;
        }
        if (customData != null && customData.conditions != null) {
            for (String condition : customData.conditions) {
                if (!evaluateCondition(condition, player, hand)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean evaluateCondition(String condition, Object player, Object hand) {
        if (!(player instanceof Player actualPlayer) || condition == null) {
            return false;
        }
        String name = condition.trim().toUpperCase(java.util.Locale.ROOT);
        ItemStack mainHand = actualPlayer.getMainHandItem();
        ItemStack offHand = actualPlayer.getOffhandItem();
        boolean dualWielding = isDualWielding(actualPlayer, mainHand, offHand);
        return switch (name) {
            case "NOT_DUAL_WIELDING" -> !dualWielding;
            case "DUAL_WIELDING_ANY" -> dualWielding;
            case "DUAL_WIELDING_SAME" -> dualWielding
                    && mainHand.getItem() == offHand.getItem();
            case "DUAL_WIELDING_SAME_CATEGORY" -> sameCategory(mainHand, offHand, dualWielding);
            case "NO_OFFHAND_ITEM" -> offHand.isEmpty();
            case "OFF_HAND_SHIELD" -> !offHand.isEmpty() && offHand.getItem() instanceof ShieldItem;
            case "MAIN_HAND_ONLY" -> !isOffHand(hand);
            case "OFF_HAND_ONLY" -> isOffHand(hand);
            case "MOUNTED" -> actualPlayer.getVehicle() != null;
            case "NOT_MOUNTED" -> actualPlayer.getVehicle() == null;
            default -> true;
        };
    }

    private static boolean isOffHand(Object hand) {
        Object value = invokeNoArg(hand, "isOffHand");
        return value instanceof Boolean flag && flag;
    }

    private static boolean isDualWielding(Player player, ItemStack mainHand, ItemStack offHand) {
        if (mainHand.isEmpty() || offHand.isEmpty()) {
            return false;
        }
        Object mainAttributes = weaponAttributes(mainHand);
        Object offAttributes = weaponAttributes(offHand);
        return mainAttributes != null && offAttributes != null
                && !booleanProperty(mainAttributes, "isTwoHanded")
                && !booleanProperty(offAttributes, "isTwoHanded");
    }

    private static boolean sameCategory(ItemStack mainHand, ItemStack offHand, boolean dualWielding) {
        if (!dualWielding) {
            return false;
        }
        Object mainAttributes = weaponAttributes(mainHand);
        Object offAttributes = weaponAttributes(offHand);
        Object mainCategory = property(mainAttributes, "category");
        Object offCategory = property(offAttributes, "category");
        if (!(mainCategory instanceof String main) || !(offCategory instanceof String off)
                || main.isBlank() || off.isBlank()) {
            return false;
        }
        return main.equals(off);
    }

    private static Object weaponAttributes(ItemStack stack) {
        try {
            Class<?> registryClass = Class.forName("net.bettercombat.logic.WeaponRegistry");
            for (Method method : registryClass.getDeclaredMethods()) {
                if (!method.getName().equals("getAttributes") || method.getParameterCount() != 1
                        || !method.getParameterTypes()[0].isAssignableFrom(stack.getClass())) {
                    continue;
                }
                method.setAccessible(true);
                return method.invoke(null, stack);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return null;
    }

    private static boolean booleanProperty(Object target, String methodName) {
        return Boolean.TRUE.equals(property(target, methodName));
    }

    private static Object property(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        Object value = invokeNoArg(target, methodName);
        if (value != null) {
            return value;
        }
        try {
            Field field = target.getClass().getDeclaredField(methodName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
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
            copyMissingSwingSound(normalHand, selection.attack);
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

    private static void copyMissingSwingSound(Object normalHand, Object skillAttack) {
        if (skillAttack == null) {
            return;
        }
        try {
            Object normalAttack = invokeNoArg(normalHand, "attack");
            if (normalAttack == null) {
                return;
            }
            Field soundField = skillAttack.getClass().getDeclaredField("swing_sound");
            soundField.setAccessible(true);
            if (soundField.get(skillAttack) == null) {
                Field normalSoundField = normalAttack.getClass().getDeclaredField("swing_sound");
                normalSoundField.setAccessible(true);
                soundField.set(skillAttack, normalSoundField.get(normalAttack));
            }
        } catch (ReflectiveOperationException ignored) {
        }
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

    private static String registryItemIdForHand(Object hand) {
        try {
            Object stack = invokeNoArg(hand, "itemStack");
            if (stack instanceof ItemStack itemStack) {
                return BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString();
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

    private static LoadedJson readSkillFile(ResourceManager resourceManager, ResourceLocation source, String reference) {
        for (ResourceLocation location : candidateLocations(source, reference)) {
            Optional<Resource> resource = resourceManager.getResource(location);
            if (resource.isEmpty()) {
                continue;
            }
            try (InputStream input = resource.get().open();
                 InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (root.isJsonObject()) {
                    return new LoadedJson(location, root.getAsJsonObject());
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * An action JSON may provide one Better Combat animation for the whole
     * skill, or an attributes.attacks array with per-step animations.
     */
    private static void applyActionJson(JsonObject attributes, JsonObject actionRoot) {
        String animation = actionRoot.has("animation") && actionRoot.get("animation").isJsonPrimitive()
                ? actionRoot.get("animation").getAsString()
                : null;
        JsonArray actionAttacks = null;
        if (actionRoot.has("attributes") && actionRoot.get("attributes").isJsonObject()) {
            JsonObject actionAttributes = actionRoot.getAsJsonObject("attributes");
            if (actionAttributes.has("attacks") && actionAttributes.get("attacks").isJsonArray()) {
                actionAttacks = actionAttributes.getAsJsonArray("attacks");
            }
        } else if (actionRoot.has("attacks") && actionRoot.get("attacks").isJsonArray()) {
            actionAttacks = actionRoot.getAsJsonArray("attacks");
        }

        if (!attributes.has("attacks") || !attributes.get("attacks").isJsonArray()) {
            return;
        }
        JsonArray attacks = attributes.getAsJsonArray("attacks");
        for (int index = 0; index < attacks.size(); index++) {
            JsonElement attack = attacks.get(index);
            if (!attack.isJsonObject()) {
                continue;
            }
            String stepAnimation = animation;
            if (actionAttacks != null && index < actionAttacks.size()
                    && actionAttacks.get(index).isJsonObject()) {
                JsonObject actionAttack = actionAttacks.get(index).getAsJsonObject();
                if (actionAttack.has("animation") && actionAttack.get("animation").isJsonPrimitive()) {
                    stepAnimation = actionAttack.get("animation").getAsString();
                }
            }
            if (stepAnimation != null && !stepAnimation.isBlank()) {
                attack.getAsJsonObject().addProperty("animation", stepAnimation);
            }
        }
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

        private Definition(JsonObject attributesJson, FiercerBattlesCustomData.SkillEffectData skillEffect, String skillId) {
            this.attributesJson = attributesJson.deepCopy();
            this.customData = parseCustomData(attributesJson, skillEffect, skillId);
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
            int index = attackIndexFor(normalAttackIndex, resolvedAttributes);
            if (index < 0) {
                return null;
            }
            try {
                Field attacksField = resolvedAttributes.getClass().getDeclaredField("attacks");
                attacksField.setAccessible(true);
                Object[] attacks = (Object[]) attacksField.get(resolvedAttributes);
                return attacks != null && index < attacks.length ? attacks[index] : null;
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }

        private int attackIndexFor(int normalAttackIndex, Object resolvedAttributes) {
            if (resolvedAttributes == null) {
                return -1;
            }
            try {
                Field attacksField = resolvedAttributes.getClass().getDeclaredField("attacks");
                attacksField.setAccessible(true);
                Object[] attacks = (Object[]) attacksField.get(resolvedAttributes);
                if (attacks == null) {
                    return -1;
                }
                int lastMatchingIndex = -1;
                for (int index = 0; index < attacks.length && index <= normalAttackIndex; index++) {
                    if (hasAnimation(attacks[index])) {
                        lastMatchingIndex = index;
                    }
                }
                return lastMatchingIndex;
            } catch (ReflectiveOperationException ignored) {
                return -1;
            }
        }

        private FiercerBattlesCustomData.AttackData customDataFor(int index) {
            return index >= 0 && index < customData.size() ? customData.get(index) : null;
        }

        private static List<FiercerBattlesCustomData.AttackData> parseCustomData(
                JsonObject attributes,
                FiercerBattlesCustomData.SkillEffectData skillEffect,
                String skillId
        ) {
            List<FiercerBattlesCustomData.AttackData> values = new ArrayList<>();
            if (!attributes.has("attacks") || !attributes.get("attacks").isJsonArray()) {
                return values;
            }
            double attackRange = attributes.has("attack_range")
                    && attributes.get("attack_range").isJsonPrimitive()
                    ? attributes.get("attack_range").getAsDouble()
                    : 0;
            JsonArray attacks = attributes.getAsJsonArray("attacks");
            for (JsonElement attack : attacks) {
                if (!attack.isJsonObject()) {
                    values.add(null);
                    continue;
                }
                FiercerBattlesCustomData.AttackData data =
                        FiercerBattlesCustomData.parseAttackData(attack.getAsJsonObject());
                data.attackRange = attackRange;
                data.skillEffect = skillEffect;
                data.skillId = skillId;
                values.add(data);
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

    private record LoadedJson(ResourceLocation location, JsonObject root) {
    }

    private record SkillRangeOverride(double attackRange, double rangeBonus) {
    }

    private record RangeKey(UUID playerId, boolean server) {
    }

    private record Selection(Object attributes, Object attack, FiercerBattlesCustomData.AttackData customData) {
    }
}
