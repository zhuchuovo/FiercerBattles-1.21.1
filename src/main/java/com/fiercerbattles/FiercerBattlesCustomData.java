package com.fiercerbattles;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.fiercerbattles.config.FiercerBattlesConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads custom FiercerBattles fields from BetterCombat weapon_attributes JSON files.
 *
 * Data is stored per item id and per attack index, so repeated animations
 * (for example dagger combos) can use different values for each combo step.
 */
public final class FiercerBattlesCustomData {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** Deduplication for malformed-Multiplehits warnings (one per animation/value). */
    private static final Set<String> MULTIPLE_HITS_WARNINGS = ConcurrentHashMap.newKeySet();

    private FiercerBattlesCustomData() {}

    public static class AttackData {
        /** Optional BetterCombat attack conditions, kept as their JSON enum names. */
        public List<String> conditions;
        /** Better Combat animation id used to match main-animation settings when an item preset masks its item id. */
        public String animation = "";
        public double distance;
        public double durationTicks;
        public boolean hasDisplacement = false;

        public double rangeBonus = 0;
        /** Absolute attack range supplied by a combat-skill attributes object. */
        public double attackRange = 0;
        public double afterCooldown = 0;
        /**
         * New animation-resource recovery window. Values are inclusive and
         * describe the animation's PLAYED frame position (0..stopTick) by
         * default; set {@code recovery_cancel_frames_unit: "remaining"} to
         * interpret them as remaining frames instead.
         */
        public int recoveryCancelMinFrames = 0;
        public int recoveryCancelMaxFrames = 0;
        public boolean hasRecoveryCancelFrames = false;
        /** True when the frame window uses played-frame positions (default). */
        public boolean recoveryCancelFramesPlayed = true;

        /** Maximum remaining recovery (in ticks) that this skill segment may cancel (legacy). */
        public int recoveryCancelTicks = 0;

        public float attackSpeedMultiplier = 0;
        public double upswing = 0;
        public float movementSpeedMultiplier = 0;
        public float rangeMultiplier = 0;
        public MultipleHits multipleHits;

        /** Flat damage added to every hit of this segment, after armor reduction. */
        public double fixedDamage = 0;
        /** Extra knockback in blocks, added to the vanilla knockback of every hit. */
        public double knockback = 0;
        /**
         * The segment's own Better Combat damage multiplier (0 when the
         * segment did not write one). Recorded per request so the server can
         * always resolve the skill's own multiplier instead of whatever
         * normal attack the decoded combo happened to select.
         */
        public double damageMultiplier = 0;

        /** Optional effect payload inherited from a combat-skill JSON root. */
        public SkillEffectData skillEffect;
        public String skillId;
    }

    /**
     * Effects applied when a combat skill is accepted by the server.
     * Percent fields use decimal form: 0.20 means an additional 20 percent.
     */
    public static final class SkillEffectData {
        public double durationSeconds;
        public double cooldownSeconds;
        public double damageBonus;
        public double armorBonus;
        public double healPercent;
        public String actionJson = "";
        /**
         * 代码战技钩子 id（{@code skill.hook}），对应
         * {@link com.fiercerbattles.combatskill.CombatSkillHooks} 里登记的战技；
         * 留空表示这是一个纯 JSON 战技。
         */
        public String hookId = "";
        /** 传给钩子的参数（{@code skill.hook_params}），原样交给 {@code CombatSkillContext} 读取。 */
        public JsonObject hookParams;
        public String soundId;
        public float soundVolume = 1.0F;
        public float soundPitch = 1.0F;
        public PotionEffectData potionEffect;
        /**
         * Whether releasing the skill is allowed to interrupt the ongoing
         * attack animation. Defaults to {@code true}; set
         * {@code "interrupt_attack": false} to require the current attack to
         * finish before the skill can start, or a number N to only allow the
         * interrupt once the attack animation has played N frames.
         */
        public boolean interruptAttack = true;
        /**
         * When greater than zero, the attack animation must have PLAYED at
         * least this many frames (0..stopTick) before the skill may interrupt
         * it. Ignored when {@link #interruptAttack} is {@code false}.
         */
        public int interruptAfterFrame = 0;

        public static SkillEffectData parse(JsonObject root) {
            JsonObject skill = root.has("skill") && root.get("skill").isJsonObject()
                    ? root.getAsJsonObject("skill")
                    : root;
            if (!hasSkillFields(skill)) {
                return null;
            }

            SkillEffectData data = new SkillEffectData();
            data.durationSeconds = nonNegative(number(skill, "duration_seconds", "duration", 0));
            data.cooldownSeconds = nonNegative(number(skill, "cooldown", "cooldown_seconds", 0));
            data.damageBonus = nonNegative(number(skill, "damage_bonus", "damage_percent", 0));
            data.armorBonus = nonNegative(number(skill, "armor_bonus", "armor_percent", 0));
            data.healPercent = nonNegative(number(skill, "heal_percent", "healing_percent", 0));
            if (skill.has("interrupt_attack") && skill.get("interrupt_attack").isJsonPrimitive()) {
                JsonPrimitive interrupt = skill.getAsJsonPrimitive("interrupt_attack");
                if (interrupt.isNumber()) {
                    // "interrupt_attack": 30 — only allow the interrupt once
                    // the attack animation has played 30 frames.
                    data.interruptAttack = true;
                    data.interruptAfterFrame = Math.max(0, interrupt.getAsInt());
                } else if (interrupt.isBoolean()) {
                    data.interruptAttack = interrupt.getAsBoolean();
                } else if (interrupt.isString()) {
                    String raw = interrupt.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
                    try {
                        data.interruptAttack = true;
                        data.interruptAfterFrame = Math.max(0, Integer.parseInt(raw));
                    } catch (NumberFormatException ignored) {
                        data.interruptAttack = !raw.equals("false");
                    }
                }
            }

            if (skill.has("action_json") && skill.get("action_json").isJsonPrimitive()) {
                data.actionJson = skill.get("action_json").getAsString().trim();
            } else if (skill.has("action") && skill.get("action").isJsonPrimitive()) {
                data.actionJson = skill.get("action").getAsString().trim();
            }

            if (skill.has("hook") && skill.get("hook").isJsonPrimitive()) {
                data.hookId = skill.get("hook").getAsString().trim();
            }
            JsonElement hookParams = skill.get("hook_params");
            if (hookParams != null && hookParams.isJsonObject()) {
                data.hookParams = hookParams.getAsJsonObject();
            }

            JsonElement sound = skill.get("sound");
            if (sound != null && sound.isJsonPrimitive()) {
                data.soundId = sound.getAsString();
            } else if (sound != null && sound.isJsonObject()) {
                JsonObject soundObject = sound.getAsJsonObject();
                if (soundObject.has("id") && soundObject.get("id").isJsonPrimitive()) {
                    data.soundId = soundObject.get("id").getAsString();
                }
                data.soundVolume = (float) number(soundObject, "volume", null, 1.0);
                data.soundPitch = (float) number(soundObject, "pitch", null, 1.0);
            }

            JsonElement potion = skill.has("potion_effect") ? skill.get("potion_effect") : skill.get("effect");
            if (potion != null && potion.isJsonObject()) {
                JsonObject potionObject = potion.getAsJsonObject();
                if (potionObject.has("id") && potionObject.get("id").isJsonPrimitive()) {
                    String id = potionObject.get("id").getAsString().trim();
                    int amplifier = (int) Math.max(0, number(potionObject, "amplifier", "level", 0));
                    int durationTicks = (int) Math.max(0,
                            Math.ceil(number(potionObject, "duration", "duration_ticks", 0)));
                    PotionEffectTarget target = PotionEffectTarget.parse(
                            potionObject.has("target") ? potionObject.get("target")
                                    : potionObject.get("apply_to")
                    );
                    data.potionEffect = new PotionEffectData(id, amplifier, durationTicks, target);
                }
            }
            return data;
        }

        private static boolean hasSkillFields(JsonObject object) {
            return object.has("skill") || object.has("duration_seconds") || object.has("duration")
                    || object.has("cooldown") || object.has("cooldown_seconds")
                    || object.has("damage_bonus") || object.has("damage_percent")
                    || object.has("armor_bonus") || object.has("armor_percent")
                    || object.has("heal_percent") || object.has("healing_percent")
                    || object.has("interrupt_attack")
                    || object.has("potion_effect") || object.has("effect")
                    || object.has("action_json") || object.has("action") || object.has("sound")
                    || object.has("hook") || object.has("hook_params");
        }

        private static double number(JsonObject object, String primary, String alias, double fallback) {
            JsonElement value = object.get(primary);
            if (value == null && alias != null) {
                value = object.get(alias);
            }
            return value != null && value.isJsonPrimitive() ? value.getAsDouble() : fallback;
        }

        private static double nonNegative(double value) {
            return Double.isFinite(value) ? Math.max(0, value) : 0;
        }
    }

    public enum PotionEffectTarget {
        SELF,
        HIT_TARGET;

        private static PotionEffectTarget parse(JsonElement value) {
            if (value == null || !value.isJsonPrimitive()) {
                return SELF;
            }
            String raw = value.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
            return switch (raw) {
                case "hit", "target", "hit_target", "victim", "enemy" -> HIT_TARGET;
                default -> SELF;
            };
        }
    }

    public record PotionEffectData(
            String id,
            int amplifier,
            int durationTicks,
            PotionEffectTarget target
    ) {
        public PotionEffectData(String id, int amplifier, int durationTicks) {
            this(id, amplifier, durationTicks, PotionEffectTarget.SELF);
        }
    }

    public static final class MultipleHits {
        public final int[] frames;
        public final float[] damageMultipliers;
        /**
         * True when {@link #frames} are PlayerAnimator resource frames of the
         * attack animation (0..stopTick) instead of game ticks since the swing
         * started. The client maps the live animation playback position onto
         * the resource frames.
         */
        public final boolean animationFrames;

        public MultipleHits(int[] frames, float[] damageMultipliers) {
            this(frames, damageMultipliers, false);
        }

        public MultipleHits(int[] frames, float[] damageMultipliers, boolean animationFrames) {
            this.frames = frames;
            this.damageMultipliers = damageMultipliers;
            this.animationFrames = animationFrames;
        }

        public boolean isValidIndex(int index) {
            return index >= 0 && index < frames.length;
        }
    }
    /**
     * Per-item attack data, published atomically by {@link #load}. In
     * singleplayer the integrated server and the client can both reload the
     * datapack (server thread and render thread); readers always see a
     * complete, immutable-enough snapshot instead of a half-cleared map.
     */
    private static volatile Map<String, List<AttackData>> DATA = Map.of();

    /**
     * Maps item ids whose attributes were resolved from another weapon
     * attributes container (for example daggers assigned the
     * `bettercombat:dagger` preset through BetterCombat's fallback
     * compatibility) to the container id that holds the custom data.
     * Written from the registry resolution path, read from the attack path,
     * so it must tolerate concurrent access.
     */
    private static final Map<String, String> ALIASES = new ConcurrentHashMap<>();

    /**
     * Per weapon id value of the {@code two_handed_attack} option, read from
     * the weapon_attributes file (root level, or its {@code attributes}
     * block). When the value is {@code false} the weapon's {@code two_handed}
     * flag must not drive the attack action: the weapon is judged as a
     * one-handed weapon, so the attack animation is played as a one-handed
     * swing and the off-hand slot keeps working (a shield can be held).
     * Absent entries mean "true" (Better Combat's default behaviour).
     */
    private static volatile Map<String, Boolean> TWO_HANDED_ATTACK = Map.of();

    /**
     * WeaponAttributes instances that belong to a weapon whose
     * {@code two_handed_attack} option is false. {@code WeaponAttributes} is
     * final and its {@code two_handed} field is only ever read through
     * {@code isTwoHanded()}, so the override is applied by
     * {@code WeaponAttributesMixin} through this identity set instead of
     * mutating Better Combat's parsed objects (which are also serialized into
     * the weapon registry sync).
     */
    private static final Set<Object> ONE_HANDED_ATTACK_ATTRIBUTES =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    /**
     * Raw (pre-inheritance) content of one weapon_attributes file.
     */
    private static final class ContainerData {
        final String parent;
        final List<AttackData> attacks;
        /** {@code two_handed_attack} written in this file, or null when absent. */
        final Boolean twoHandedAttack;
        /** {@code offhand_skill} written in this file, or null when absent. */
        final Boolean offHandSkill;

        ContainerData(String parent, List<AttackData> attacks, Boolean twoHandedAttack, Boolean offHandSkill) {
            this.parent = parent;
            this.attacks = attacks;
            this.twoHandedAttack = twoHandedAttack;
            this.offHandSkill = offHandSkill;
        }
    }

    public static void load(ResourceManager resourceManager) {
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
                // `two_handed_attack` may be written next to `Combatskill` at
                // the file root, or inside the `attributes` block; the root
                // wins when both are present.
                Boolean twoHandedAttack = readFlag(root, TWO_HANDED_ATTACK_KEYS);
                Boolean offHandSkill = readFlag(root, OFF_HAND_SKILL_KEYS);
                List<AttackData> attacks = new ArrayList<>();
                if (root.has("attributes") && root.get("attributes").isJsonObject()) {
                    JsonObject attributes = root.getAsJsonObject("attributes");
                    if (twoHandedAttack == null) {
                        twoHandedAttack = readFlag(attributes, TWO_HANDED_ATTACK_KEYS);
                    }
                    if (offHandSkill == null) {
                        offHandSkill = readFlag(attributes, OFF_HAND_SKILL_KEYS);
                    }
                    // Root-level defaults applied to every attack segment; a
                    // value written directly on the segment wins.
                    double fixedDamage = positiveNumber(attributes, "fixed_damage", "flat_damage");
                    double knockback = positiveNumber(attributes, "knockback", "knockback_bonus");
                    double damageMultiplier = positiveNumber(attributes, "damage_multiplier", null);
                    if (attributes.has("attacks") && attributes.get("attacks").isJsonArray()) {
                        JsonArray attackArray = attributes.getAsJsonArray("attacks");
                        for (JsonElement element : attackArray) {
                            AttackData data = new AttackData();
                            if (element.isJsonObject()) {
                                parseAttack(element.getAsJsonObject(), data);
                            }
                            if (data.fixedDamage == 0) {
                                data.fixedDamage = fixedDamage;
                            }
                            if (data.knockback == 0) {
                                data.knockback = knockback;
                            }
                            if (data.damageMultiplier == 0) {
                                data.damageMultiplier = damageMultiplier;
                            }
                            attacks.add(data);
                        }
                    }
                }
                containers.put(itemId, new ContainerData(parent, attacks, twoHandedAttack, offHandSkill));
            } catch (Exception ignored) {
            }
        }
        // Publish a complete snapshot atomically so readers on the other
        // thread (client vs integrated server) never observe a half-loaded
        // map.
        Map<String, List<AttackData>> resolved = new HashMap<>(containers.size());
        Map<String, Boolean> attackActionFlags = new HashMap<>();
        for (String itemId : containers.keySet()) {
            resolved.put(itemId, resolveInheritance(itemId, containers, new HashSet<>()));
            Boolean flag = resolveTwoHandedAttack(itemId, containers, new HashSet<>());
            if (flag != null) {
                attackActionFlags.put(itemId, flag);
            }
        }
        DATA = resolved;
        TWO_HANDED_ATTACK = attackActionFlags;
        // Attribute instances are re-created by every reload, so the
        // previously marked ones are stale.
        ONE_HANDED_ATTACK_ATTRIBUTES.clear();
        long oneHandedAttackWeapons = attackActionFlags.values().stream()
                .filter(value -> !value)
                .count();
        if (oneHandedAttackWeapons > 0) {
            LOGGER.info(
                    "Fiercer Battles: {} weapon(s) set two_handed_attack=false "
                            + "(judged as one-handed for the attack action)",
                    oneHandedAttackWeapons
            );
        }
    }

    /**
     * Resolves {@code two_handed_attack} through the {@code parent} chain:
     * the weapon's own value wins, otherwise the nearest ancestor that
     * defines it is used.
     *
     * A weapon that releases its combat skill from the off-hand
     * ({@code offhand_skill}) defaults to the one-handed judgement, because a
     * two-handed weapon is removed from the off-hand slot by Better Combat -
     * it could not even be held there.
     */
    private static Boolean resolveTwoHandedAttack(
            String itemId,
            Map<String, ContainerData> containers,
            Set<String> visiting
    ) {
        ContainerData container = containers.get(itemId);
        if (container == null) {
            return null;
        }
        if (container.twoHandedAttack != null) {
            return container.twoHandedAttack;
        }
        if (container.offHandSkill != null) {
            return Boolean.FALSE;
        }
        if (container.parent == null || !containers.containsKey(container.parent)
                || !visiting.add(container.parent)) {
            return null;
        }
        return resolveTwoHandedAttack(container.parent, containers, visiting);
    }

    /** Reads a boolean flag, honoring all accepted aliases. */
    public static Boolean readFlag(JsonObject object, String[] keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonPrimitive()) {
                try {
                    return value.getAsBoolean();
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
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
        merged.conditions = child.conditions != null ? child.conditions : parent.conditions;
        merged.animation = child.animation != null && !child.animation.isBlank()
                ? child.animation
                : parent.animation;
        merged.attackSpeedMultiplier = child.attackSpeedMultiplier != 0 ? child.attackSpeedMultiplier : parent.attackSpeedMultiplier;
        merged.upswing = child.upswing != 0 ? child.upswing : parent.upswing;
        merged.movementSpeedMultiplier = child.movementSpeedMultiplier != 0 ? child.movementSpeedMultiplier : parent.movementSpeedMultiplier;
        merged.rangeMultiplier = child.rangeMultiplier != 0 ? child.rangeMultiplier : parent.rangeMultiplier;
        merged.rangeBonus = child.rangeBonus != 0 ? child.rangeBonus : parent.rangeBonus;
        merged.attackRange = child.attackRange != 0 ? child.attackRange : parent.attackRange;
        merged.afterCooldown = child.afterCooldown != 0 ? child.afterCooldown : parent.afterCooldown;
        merged.hasRecoveryCancelFrames = child.hasRecoveryCancelFrames
                ? true
                : parent.hasRecoveryCancelFrames;
        merged.recoveryCancelMinFrames = child.hasRecoveryCancelFrames
                ? child.recoveryCancelMinFrames
                : parent.recoveryCancelMinFrames;
        merged.recoveryCancelMaxFrames = child.hasRecoveryCancelFrames
                ? child.recoveryCancelMaxFrames
                : parent.recoveryCancelMaxFrames;
        merged.recoveryCancelFramesPlayed = child.hasRecoveryCancelFrames
                ? child.recoveryCancelFramesPlayed
                : parent.recoveryCancelFramesPlayed;
        merged.recoveryCancelTicks = child.recoveryCancelTicks != 0
                ? child.recoveryCancelTicks
                : parent.recoveryCancelTicks;
        merged.multipleHits = child.multipleHits != null ? child.multipleHits : parent.multipleHits;
        merged.fixedDamage = child.fixedDamage != 0 ? child.fixedDamage : parent.fixedDamage;
        merged.knockback = child.knockback != 0 ? child.knockback : parent.knockback;
        merged.damageMultiplier = child.damageMultiplier != 0 ? child.damageMultiplier : parent.damageMultiplier;
        merged.skillEffect = child.skillEffect != null ? child.skillEffect : parent.skillEffect;
        merged.skillId = child.skillId != null ? child.skillId : parent.skillId;
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

    /** Reads a non-negative numeric field, honoring an alias. */
    private static double positiveNumber(JsonObject object, String primary, String alias) {
        if (object.has(primary) && object.get(primary).isJsonPrimitive()) {
            return Math.max(0, object.get(primary).getAsDouble());
        }
        if (alias != null && object.has(alias) && object.get(alias).isJsonPrimitive()) {
            return Math.max(0, object.get(alias).getAsDouble());
        }
        return 0;
    }

    private static void parseAttack(JsonObject attack, AttackData data) {
        if (attack.has("animation") && attack.get("animation").isJsonPrimitive()) {
            data.animation = attack.get("animation").getAsString().trim();
        }
        if (attack.has("conditions") && attack.get("conditions").isJsonArray()) {
            List<String> conditions = new ArrayList<>();
            for (JsonElement element : attack.getAsJsonArray("conditions")) {
                if (element.isJsonPrimitive()) {
                    String condition = element.getAsString().trim();
                    if (!condition.isEmpty()) {
                        conditions.add(condition);
                    }
                }
            }
            data.conditions = conditions;
        }
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
        parseRecoveryCancelWindow(attack, data);
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
            String rawMultipleHits = attack.get("Multiplehits").getAsString();
            data.multipleHits = parseMultipleHits(rawMultipleHits, isAnimationFrameUnit(attack));
            if (data.multipleHits == null) {
                warnInvalidMultipleHits(rawMultipleHits, attack);
            }
        }
        if (attack.has("range_multiplier") && attack.get("range_multiplier").isJsonPrimitive()) {
            data.rangeMultiplier = attack.get("range_multiplier").getAsFloat();
        }
        if (attack.has("fixed_damage") && attack.get("fixed_damage").isJsonPrimitive()) {
            data.fixedDamage = Math.max(0, attack.get("fixed_damage").getAsDouble());
        } else if (attack.has("flat_damage") && attack.get("flat_damage").isJsonPrimitive()) {
            data.fixedDamage = Math.max(0, attack.get("flat_damage").getAsDouble());
        }
        if (attack.has("knockback") && attack.get("knockback").isJsonPrimitive()) {
            data.knockback = Math.max(0, attack.get("knockback").getAsDouble());
        } else if (attack.has("knockback_bonus") && attack.get("knockback_bonus").isJsonPrimitive()) {
            data.knockback = Math.max(0, attack.get("knockback_bonus").getAsDouble());
        }
        if (attack.has("damage_multiplier") && attack.get("damage_multiplier").isJsonPrimitive()) {
            data.damageMultiplier = Math.max(0, attack.get("damage_multiplier").getAsDouble());
        }
    }

    private static boolean isAnimationFrameUnit(JsonObject attack) {
        JsonElement unit = attack.get("MultiplehitsUnit");
        if (unit == null) {
            unit = attack.get("multiplehits_unit");
        }
        return unit != null && unit.isJsonPrimitive()
                && "animation".equalsIgnoreCase(unit.getAsString().trim());
    }

    private static void parseRecoveryCancelWindow(JsonObject attack, AttackData data) {
        JsonElement frames = attack.get("recovery_cancel_frames");
        if (frames == null) {
            frames = attack.get("interrupt_recovery_frames");
        }
        int[] range = parseFrameRange(frames);
        if (range != null) {
            data.recoveryCancelMinFrames = range[0];
            data.recoveryCancelMaxFrames = range[1];
            data.hasRecoveryCancelFrames = true;
            data.recoveryCancelFramesPlayed = isPlayedFrameUnit(attack);
            return;
        }
        data.recoveryCancelTicks = parseRecoveryCancelTicks(attack);
    }

    /**
     * The frame window is interpreted as the animation's PLAYED frame
     * position (0..stopTick) unless the file explicitly requests the old
     * "remaining frames" meaning via {@code recovery_cancel_frames_unit:
     * "remaining"}.
     */
    private static boolean isPlayedFrameUnit(JsonObject attack) {
        JsonElement unit = attack.get("recovery_cancel_frames_unit");
        if (unit == null) {
            unit = attack.get("interrupt_recovery_frames_unit");
        }
        if (unit != null && unit.isJsonPrimitive()) {
            String value = unit.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
            if (value.equals("remaining")) {
                return false;
            }
        }
        return true;
    }

    private static int[] parseFrameRange(JsonElement value) {
        if (value == null) {
            return null;
        }
        try {
            int first;
            int second;
            if (value.isJsonArray() && value.getAsJsonArray().size() >= 2) {
                first = value.getAsJsonArray().get(0).getAsInt();
                second = value.getAsJsonArray().get(1).getAsInt();
            } else if (value.isJsonPrimitive()) {
                String raw = value.getAsString().trim();
                String[] parts = raw.split("[,，]", -1);
                if (parts.length != 2) {
                    return null;
                }
                first = Integer.parseInt(parts[0].trim());
                second = Integer.parseInt(parts[1].trim());
            } else {
                return null;
            }
            first = Math.max(0, first);
            second = Math.max(0, second);
            return new int[]{Math.min(first, second), Math.max(first, second)};
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static int parseRecoveryCancelTicks(JsonObject attack) {
        JsonElement ticks = attack.get("recovery_cancel_ticks");
        if (ticks == null) {
            ticks = attack.get("interrupt_recovery_ticks");
        }
        if (ticks != null && ticks.isJsonPrimitive()) {
            try {
                return Math.max(0, ticks.getAsInt());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        // Convenience aliases use seconds, matching after_cooldown's unit.
        JsonElement seconds = attack.get("recovery_cancel_seconds");
        if (seconds == null) {
            seconds = attack.get("recovery_cancel");
        }
        if (seconds != null && seconds.isJsonPrimitive()) {
            try {
                double value = seconds.getAsDouble();
                return Double.isFinite(value) ? Math.max(0, (int) Math.ceil(value * 20.0)) : 0;
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    public static MultipleHits getMultipleHits(AttackData customData, String animation) {
        if (customData != null && customData.multipleHits != null) {
            return customData.multipleHits;
        }
        return FiercerBattlesConfig.getMultipleHits(animation);
    }
    public static MultipleHits parseMultipleHits(String raw) {
        return parseMultipleHits(raw, false);
    }

    public static MultipleHits parseMultipleHits(String raw, boolean animationFrames) {
        if (raw == null) {
            return null;
        }
        // Both "[10,20],[1,1]" and "[10]:[1]" are accepted: the separator
        // between the frame list and the multiplier list must not decide
        // whether the configured hits silently disappear.
        String[] groups = raw.trim().split("\\]\\s*[,:]\\s*\\[", -1);
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
        if (frameParts.length == 0) {
            return null;
        }
        // The multiplier list may be shorter than the frame list: the missing
        // tail stages inherit the last configured multiplier. Without this,
        // one missing multiplier used to invalidate the whole Multiplehits
        // setting and silently disabled every hit stage.
        String lastMultiplier = multiplierParts[multiplierParts.length - 1];
        int[] frames = new int[frameParts.length];
        float[] multipliers = new float[frameParts.length];
        try {
            for (int index = 0; index < frameParts.length; index++) {
                frames[index] = Integer.parseInt(frameParts[index].trim());
                multipliers[index] = Float.parseFloat(
                        (index < multiplierParts.length ? multiplierParts[index] : lastMultiplier).trim()
                );
                if (frames[index] < 0 || multipliers[index] < 0 || index > 0 && frames[index] <= frames[index - 1]) {
                    return null;
                }
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return new MultipleHits(frames, multipliers, animationFrames);
    }
    /**
     * A malformed {@code Multiplehits} used to disable the segment's hit frames
     * without a trace. Report it once per animation/value pair so a typo (for
     * example {@code "[10]:[1]"}) is visible instead of reading as "the skill
     * does no damage".
     */
    private static void warnInvalidMultipleHits(String raw, JsonObject attack) {
        String animation = attack.has("animation") && attack.get("animation").isJsonPrimitive()
                ? attack.get("animation").getAsString()
                : "?";
        if (MULTIPLE_HITS_WARNINGS.add(animation + '|' + raw)) {
            LOGGER.warn(
                    "Fiercer Battles: ignoring invalid Multiplehits \"{}\" on attack segment '{}'; "
                            + "expected \"[frame,...],[multiplier,...]\"",
                    raw,
                    animation
            );
        }
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
                    ? Double.valueOf(custom.upswing)
                    : FiercerBattlesConfig.getUpswing(animation);
            if (upswing != null) {
                Field upswingField = attackClass.getDeclaredField("upswing");
                upswingField.setAccessible(true);
                upswingField.setDouble(attack, upswing);
            }

            Float movementSpeed = custom != null && custom.movementSpeedMultiplier != 0
                    ? Float.valueOf(custom.movementSpeedMultiplier)
                    : FiercerBattlesConfig.getMovementSpeedMultiplier(animation);
            if (movementSpeed != null) {
                Field movementField = attackClass.getDeclaredField("movement_speed_multiplier");
                movementField.setAccessible(true);
                movementField.setFloat(attack, movementSpeed);
            }

            Float rangeMultiplier = custom != null && custom.rangeMultiplier != 0
                    ? Float.valueOf(custom.rangeMultiplier)
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
        // A null item id (empty hand, item without weapon attributes) must not
        // reach the ConcurrentHashMap lookups below: they reject null keys.
        String key = itemId;
        if (key != null && !DATA.containsKey(key)) {
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

    /** Returns true when any main-weapon animation segment defines a recovery window. */
    public static boolean hasRecoveryCancelWindow(String itemId) {
        String key = itemId;
        if (key != null && !DATA.containsKey(key)) {
            String alias = ALIASES.get(key);
            if (alias != null) {
                key = alias;
            }
        }
        List<AttackData> list = key == null ? null : DATA.get(key);
        if (list == null) {
            return false;
        }
        for (AttackData data : list) {
            if (data != null && (data.hasRecoveryCancelFrames || data.recoveryCancelTicks > 0)) {
                return true;
            }
        }
        return false;
    }

    /** Looks up a main-weapon recovery window by animation id as a fallback for preset-backed items. */
    public static AttackData getRecoveryWindowForAnimation(String animation) {
        if (animation == null || animation.isBlank()) {
            return null;
        }
        for (List<AttackData> attacks : DATA.values()) {
            for (AttackData data : attacks) {
                if (data != null
                        && animation.equals(data.animation)
                        && (data.hasRecoveryCancelFrames || data.recoveryCancelTicks > 0)) {
                    return data;
                }
            }
        }
        return null;
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

    /** Accepted spellings of the "does `two_handed` drive the attack action" option. */
    public static final String[] TWO_HANDED_ATTACK_KEYS = {
            "two_handed_attack",
            "two_handed_affects_attack",
            "affect_attack_action",
            "affect_attack_animation"
    };

    /** Accepted spellings of the "release this weapon's skill from the off-hand" option. */
    public static final String[] OFF_HAND_SKILL_KEYS = {
            "offhand_skill",
            "use_offhand_skill",
            "offhand_combat_skill"
    };

    /**
     * False when the weapon opted out of {@code two_handed} driving its attack
     * action (the weapon is then judged as a one-handed weapon: one-handed
     * attack animation and a usable off-hand slot). Weapons that do not write
     * the option keep Better Combat's default two-handed handling.
     */
    public static boolean isTwoHandedAttackEnabled(String itemId) {
        return !Boolean.FALSE.equals(flagFor(itemId, TWO_HANDED_ATTACK));
    }

    private static Boolean flagFor(String itemId, Map<String, Boolean> flags) {
        if (itemId == null) {
            return null;
        }
        Boolean value = flags.get(itemId);
        if (value != null) {
            return value;
        }
        String alias = ALIASES.get(itemId);
        return alias != null ? flags.get(alias) : null;
    }

    /**
     * Remembers that the given resolved attributes object belongs to
     * {@code itemId}, so the {@code WeaponAttributes} mixin can answer
     * {@code isTwoHanded()} with the weapon's configured attack-action
     * judgement.
     */
    public static void rememberAttackActionAttributes(Object itemId, Object attributes) {
        if (itemId == null || attributes == null) {
            return;
        }
        if (isTwoHandedAttackEnabled(itemId.toString())) {
            ONE_HANDED_ATTACK_ATTRIBUTES.remove(attributes);
        } else {
            ONE_HANDED_ATTACK_ATTRIBUTES.add(attributes);
        }
    }

    /**
     * Same as {@link #rememberAttackActionAttributes(Object, Object)} for an
     * item stack, using Better Combat's own id rule: the weapon preset
     * component wins, the registry id is the fallback.
     */
    public static void rememberAttackActionAttributesForStack(Object stack, Object attributes) {
        if (!(stack instanceof ItemStack itemStack) || attributes == null) {
            return;
        }
        String registryId = BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString();
        String presetId = presetId(itemStack);
        Boolean effective = presetId != null ? flagFor(presetId, TWO_HANDED_ATTACK) : null;
        if (effective == null) {
            effective = flagFor(registryId, TWO_HANDED_ATTACK);
        }
        if (effective == null) {
            return;
        }
        if (effective) {
            ONE_HANDED_ATTACK_ATTRIBUTES.remove(attributes);
        } else {
            ONE_HANDED_ATTACK_ATTRIBUTES.add(attributes);
        }
    }

    /**
     * Forces an arbitrary attributes object (for example the attributes of a
     * combat-skill file, which never passes through BetterCombat's weapon
     * registry) to be judged as one-handed for the attack action.
     */
    public static void markAttackActionOneHanded(Object attributes) {
        if (attributes != null) {
            ONE_HANDED_ATTACK_ATTRIBUTES.add(attributes);
        }
    }

    /** True when this attributes instance must report itself as one-handed. */
    public static boolean isAttackActionOneHanded(Object attributes) {
        return attributes != null && ONE_HANDED_ATTACK_ATTRIBUTES.contains(attributes);
    }

    /** Better Combat's id rule for a stack: preset component first, registry id second. */
    public static String itemIdForStack(Object stack) {
        if (!(stack instanceof ItemStack itemStack)) {
            return null;
        }
        String preset = presetId(itemStack);
        return preset != null ? preset : BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString();
    }

    /** The BetterCombat weapon preset id assigned to the stack, or null. */
    public static String presetId(ItemStack stack) {
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
}
