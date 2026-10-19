package com.fiercerbattles.mobcombat.internal.mobanim;

import com.fiercerbattles.mobcombat.BetterMobCombatReimagined;
import com.fiercerbattles.mobcombat.api.MobAnimationAccess;
import dev.kosmx.playerAnim.impl.animation.AnimationApplier;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class GenericHumanoidModelCompat {
    private static final Map<EntityModel<?>, PartBinding> BINDINGS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<String> LOGGED = Collections.synchronizedSet(new HashSet<>());
    private static final Map<LivingEntity, Set<VisibilityState>> SAVED_VISIBILITY =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<EmbeddedPlayerAnimator.AnimatedPart, String[]> ALIASES = aliases();
    private static final String[] CROSSED_ARM_ALIASES = {
            "arms", "crossed_arms", "crossedarms", "arms_rotation", "armsrotation"
    };

    private GenericHumanoidModelCompat() {
    }

    public static boolean supportsModel(EntityModel<?> model) {
        if (model instanceof HumanoidModel<?> || model instanceof IllagerModel<?>) {
            return true;
        }

        Package modelPackage = model.getClass().getPackage();
        if (modelPackage != null && modelPackage.getName().startsWith("net.minecraft.client.model")) {
            return false;
        }

        return BINDINGS.computeIfAbsent(model, GenericHumanoidModelCompat::resolve)
                .hasCompleteHumanoidSkeleton();
    }

    public static void apply(LivingEntity entity, EntityModel<?> model, float partialTick) {
        // A renderer normally reaches our TAIL hook, but clear a stale visibility snapshot first
        // in case another mod cancelled the previous render after the base model was prepared.
        restore(entity);

        if (!(entity instanceof MobAnimationAccess access)
                || model instanceof HumanoidModel<?>
                || model instanceof IllagerModel<?>
                || OptionalEmfCompat.isEmfModel(model)
                || !supportsModel(model)) {
            return;
        }

        AnimationApplier animation = EmbeddedPlayerAnimator.getAnimation(entity);
        if (animation == null || !animation.isActive()) {
            return;
        }

        EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated =
                EmbeddedPlayerAnimator.getCurrentlyAnimatedParts(entity);
        ensureWeaponArms(entity, access, animated);
        if (animated.isEmpty()) {
            return;
        }

        PartBinding binding = BINDINGS.computeIfAbsent(model, GenericHumanoidModelCompat::resolve);
        if (!binding.hasCompleteHumanoidSkeleton() || !binding.hasAny(animated)) {
            logMissingOnce(entity, model, animated, binding);
            return;
        }

        animation.setTickDelta(partialTick);
        if (!animation.isActive()) {
            return;
        }

        prepareIndependentWeaponArms(entity, access, binding, animated);

        apply(animation, "head", binding.get(EmbeddedPlayerAnimator.AnimatedPart.HEAD),
                animated.contains(EmbeddedPlayerAnimator.AnimatedPart.HEAD));
        apply(animation, "torso", binding.get(EmbeddedPlayerAnimator.AnimatedPart.TORSO),
                animated.contains(EmbeddedPlayerAnimator.AnimatedPart.TORSO));
        apply(animation, "leftArm", binding.get(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM),
                animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM));
        apply(animation, "rightArm", binding.get(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM),
                animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM));
        apply(animation, "leftLeg", binding.get(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG),
                animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG));
        apply(animation, "rightLeg", binding.get(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG),
                animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG));

        String key = "resolved|" + entity.getType() + "|" + model.getClass().getName();
        if (LOGGED.add(key)) {
            BetterMobCombatReimagined.LOGGER.info(
                    "[BMC custom-model] mob={} model={} resolvedParts={} crossedArms={} activeChannels={}",
                    entity.getType(), model.getClass().getName(), binding.names(),
                    binding.crossedArmsName(), animated
            );
        }
    }

    /**
     * Applies only Better Combat's live weapon-arm channels directly to a custom model.
     *
     * <p>This intentionally bypasses the normal EMF exclusion. It is for compatibility hooks that
     * run from the custom model's own {@code setupAnim} after that model has finished resetting and
     * posing its visible arm parts. Villager Retaliation's combat/humanoid villager models are the
     * first user of this path.</p>
     *
     * @return true when at least one weapon arm was updated
     */
    public static boolean applyAttackArmsDirect(
            LivingEntity entity,
            EntityModel<?> model,
            float partialTick
    ) {
        if (!(entity instanceof MobAnimationAccess access)
                || !access.bmc$isAttackAnimationActive()
                || !supportsModel(model)) {
            return false;
        }

        AnimationApplier animation = EmbeddedPlayerAnimator.getAnimation(entity);
        if (animation == null || !animation.isActive()) {
            return false;
        }

        animation.setTickDelta(partialTick);
        if (!animation.isActive()) {
            return false;
        }

        EnumSet<EmbeddedPlayerAnimator.AnimatedPart> owned =
                EmbeddedPlayerAnimator.getCurrentlyAnimatedParts(entity);
        ensureWeaponArms(entity, access, owned);

        PartBinding binding = BINDINGS.computeIfAbsent(model, GenericHumanoidModelCompat::resolve);
        ModelPart leftArm = binding.get(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
        ModelPart rightArm = binding.get(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);

        boolean leftOwned = owned.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
        boolean rightOwned = owned.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
        boolean applied = false;

        if (leftOwned && leftArm != null) {
            animation.updatePart("leftArm", leftArm);
            leftArm.visible = true;
            applied = true;
        }
        if (rightOwned && rightArm != null) {
            animation.updatePart("rightArm", rightArm);
            rightArm.visible = true;
            applied = true;
        }

        if (applied && binding.crossedArms() != null
                && binding.crossedArms() != leftArm
                && binding.crossedArms() != rightArm
                && !containsPart(binding.crossedArms(), leftArm)
                && !containsPart(binding.crossedArms(), rightArm)) {
            binding.crossedArms().visible = false;
        }

        if (applied) {
            String key = "direct-arms|" + entity.getType() + "|" + model.getClass().getName();
            if (LOGGED.add(key)) {
                BetterMobCombatReimagined.LOGGER.info(
                        "[BMC custom-model direct arms] mob={} model={} leftOwned={} rightOwned={} parts={}",
                        entity.getType(), model.getClass().getName(), leftOwned, rightOwned, binding.names()
                );
            }
        }

        return applied;
    }

    /**
     * Restores temporary visibility changes made for custom models with separate crossed-arm and
     * independent arm branches (for example Villager Retaliation's held-item combat model).
     */
    public static void restore(LivingEntity entity) {
        Set<VisibilityState> saved = SAVED_VISIBILITY.remove(entity);
        if (saved == null) {
            return;
        }
        for (VisibilityState state : saved) {
            state.part().visible = state.visible();
        }
    }

    private static void prepareIndependentWeaponArms(
            LivingEntity entity,
            MobAnimationAccess access,
            PartBinding binding,
            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated
    ) {
        if (!access.bmc$isAttackAnimationActive()) {
            return;
        }

        boolean leftOwned = animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
        boolean rightOwned = animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
        if (!leftOwned && !rightOwned) {
            return;
        }

        ModelPart leftArm = binding.get(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
        ModelPart rightArm = binding.get(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
        ModelPart crossedArms = binding.crossedArms();

        Set<VisibilityState> saved = Collections.newSetFromMap(new IdentityHashMap<>());
        if (leftOwned) {
            setVisible(leftArm, true, saved);
        }
        if (rightOwned) {
            setVisible(rightArm, true, saved);
        }

        // Do not hide an "arms" node when it is merely the parent container for the independent
        // arms. Villager-style combat models usually expose it as a separate crossed-arm sibling.
        if (crossedArms != null
                && crossedArms != leftArm
                && crossedArms != rightArm
                && !containsPart(crossedArms, leftArm)
                && !containsPart(crossedArms, rightArm)) {
            setVisible(crossedArms, false, saved);
        }

        if (!saved.isEmpty()) {
            SAVED_VISIBILITY.put(entity, saved);
        }
    }

    private static void setVisible(ModelPart part, boolean visible, Set<VisibilityState> saved) {
        if (part == null || part.visible == visible) {
            return;
        }
        saved.add(new VisibilityState(part, part.visible));
        part.visible = visible;
    }

    private static boolean containsPart(ModelPart root, ModelPart target) {
        return root != null
                && target != null
                && root.getAllParts().anyMatch(part -> part == target);
    }

    private static void apply(AnimationApplier animation, String channel, ModelPart part, boolean active) {
        if (active && part != null) {
            animation.updatePart(channel, part);
        }
    }

    private static void ensureWeaponArms(
            LivingEntity entity,
            MobAnimationAccess access,
            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated
    ) {
        if (access.bmc$isTwoHandedArmAnimationActive()) {
            animated.add(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
            animated.add(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
        } else if (access.bmc$isAttackAnimationActive()) {
            // Generic models (MCA/Townstead/custom villagers/NPCs) do not pass through the vanilla
            // HumanoidModel arm ownership path. Respect the packet's logical hand here instead of
            // assuming every attack uses the mob's dominant hand.
            boolean leftArm = access.bmc$isOffHandAttackAnimationActive();
            if (entity instanceof Mob mob && mob.isLeftHanded()) {
                leftArm = !leftArm;
            }
            animated.add(leftArm
                    ? EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM
                    : EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
        }
    }

    private static PartBinding resolve(EntityModel<?> model) {
        EnumMap<EmbeddedPlayerAnimator.AnimatedPart, ModelPart> parts =
                new EnumMap<>(EmbeddedPlayerAnimator.AnimatedPart.class);
        EnumMap<EmbeddedPlayerAnimator.AnimatedPart, String> names =
                new EnumMap<>(EmbeddedPlayerAnimator.AnimatedPart.class);

        for (Class<?> type = model.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!ModelPart.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                EmbeddedPlayerAnimator.AnimatedPart channel = match(field.getName());
                if (channel == null || parts.containsKey(channel)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(model);
                    if (value instanceof ModelPart part) {
                        parts.put(channel, part);
                        names.put(channel, "field:" + field.getName());
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Fall through to root-tree lookup below.
                }
            }
        }

        ModelPart crossedArms = null;
        String crossedArmsName = null;
        if (model instanceof HierarchicalModel<?> hierarchical) {
            ModelPart root = hierarchical.root();
            if (root != null) {
                resolveFromTree(root, parts, names);
                crossedArms = findCrossedArms(root);
                if (crossedArms != null) {
                    crossedArmsName = "part:crossed-arms";
                }
            }
        }

        if (crossedArms == null) {
            CrossedArmBinding reflected = findCrossedArmsField(model);
            if (reflected != null) {
                crossedArms = reflected.part();
                crossedArmsName = "field:" + reflected.name();
            }
        }

        return new PartBinding(parts, names, crossedArms, crossedArmsName);
    }

    private static void resolveFromTree(
            ModelPart root,
            EnumMap<EmbeddedPlayerAnimator.AnimatedPart, ModelPart> parts,
            EnumMap<EmbeddedPlayerAnimator.AnimatedPart, String> names
    ) {
        for (Map.Entry<EmbeddedPlayerAnimator.AnimatedPart, String[]> entry : ALIASES.entrySet()) {
            if (parts.containsKey(entry.getKey())) {
                continue;
            }
            for (String alias : entry.getValue()) {
                ModelPart found = findExactDescendant(root, alias);
                if (found != null) {
                    parts.put(entry.getKey(), found);
                    names.put(entry.getKey(), "part:" + alias);
                    break;
                }
            }
        }

        try {
            Field childrenField = ModelPart.class.getDeclaredField("children");
            childrenField.setAccessible(true);
            ArrayDeque<NamedPart> queue = new ArrayDeque<>();
            queue.add(new NamedPart("root", root));
            Set<ModelPart> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            while (!queue.isEmpty()) {
                NamedPart current = queue.removeFirst();
                if (!visited.add(current.part())) {
                    continue;
                }
                EmbeddedPlayerAnimator.AnimatedPart channel = match(current.name());
                if (channel != null && !parts.containsKey(channel)) {
                    parts.put(channel, current.part());
                    names.put(channel, "part:" + current.name());
                }
                Object raw = childrenField.get(current.part());
                if (raw instanceof Map<?, ?> children) {
                    for (Map.Entry<?, ?> child : children.entrySet()) {
                        if (child.getKey() instanceof String name && child.getValue() instanceof ModelPart part) {
                            queue.addLast(new NamedPart(name, part));
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static ModelPart findCrossedArms(ModelPart root) {
        for (String alias : CROSSED_ARM_ALIASES) {
            ModelPart found = findExactDescendant(root, alias);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static CrossedArmBinding findCrossedArmsField(EntityModel<?> model) {
        for (Class<?> type = model.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!ModelPart.class.isAssignableFrom(field.getType())
                        || !matchesAlias(field.getName(), CROSSED_ARM_ALIASES)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(model);
                    if (value instanceof ModelPart part) {
                        return new CrossedArmBinding(field.getName(), part);
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Tree lookup is preferred; a blocked reflective field is simply skipped.
                }
            }
        }
        return null;
    }

    private static boolean matchesAlias(String value, String[] aliases) {
        String normalized = normalize(value);
        for (String alias : aliases) {
            if (normalized.equals(normalize(alias))) {
                return true;
            }
        }
        return false;
    }

    private static ModelPart findExactDescendant(ModelPart root, String name) {
        if (root.hasChild(name)) {
            return root.getChild(name);
        }

        try {
            Field childrenField = ModelPart.class.getDeclaredField("children");
            childrenField.setAccessible(true);
            ArrayDeque<ModelPart> queue = new ArrayDeque<>();
            queue.add(root);
            Set<ModelPart> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            while (!queue.isEmpty()) {
                ModelPart current = queue.removeFirst();
                if (!visited.add(current)) {
                    continue;
                }
                Object raw = childrenField.get(current);
                if (!(raw instanceof Map<?, ?> children)) {
                    continue;
                }
                Object direct = children.get(name);
                if (direct instanceof ModelPart part) {
                    return part;
                }
                for (Object child : children.values()) {
                    if (child instanceof ModelPart part) {
                        queue.addLast(part);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static EmbeddedPlayerAnimator.AnimatedPart match(String name) {
        String normalized = normalize(name);
        for (Map.Entry<EmbeddedPlayerAnimator.AnimatedPart, String[]> entry : ALIASES.entrySet()) {
            for (String alias : entry.getValue()) {
                if (normalized.equals(normalize(alias))) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private static String normalize(String value) {
        return value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static Map<EmbeddedPlayerAnimator.AnimatedPart, String[]> aliases() {
        EnumMap<EmbeddedPlayerAnimator.AnimatedPart, String[]> map =
                new EnumMap<>(EmbeddedPlayerAnimator.AnimatedPart.class);
        map.put(EmbeddedPlayerAnimator.AnimatedPart.HEAD,
                new String[]{"head", "head_root", "headroot", "skull"});
        map.put(EmbeddedPlayerAnimator.AnimatedPart.TORSO,
                new String[]{"body", "torso", "chest", "upper_body", "upperbody"});
        map.put(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM,
                new String[]{"left_arm", "leftarm", "arm_left", "armleft", "left_upper_arm", "leftupperarm", "larm"});
        map.put(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM,
                new String[]{"right_arm", "rightarm", "arm_right", "armright", "right_upper_arm", "rightupperarm", "rarm"});
        map.put(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG,
                new String[]{"left_leg", "leftleg", "leg_left", "legleft", "left_upper_leg", "leftupperleg", "lleg"});
        map.put(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG,
                new String[]{"right_leg", "rightleg", "leg_right", "legright", "right_upper_leg", "rightupperleg", "rleg"});
        return Collections.unmodifiableMap(map);
    }

    private static void logMissingOnce(
            LivingEntity entity,
            EntityModel<?> model,
            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated,
            PartBinding binding
    ) {
        String key = "missing|" + entity.getType() + "|" + model.getClass().getName();
        if (LOGGED.add(key)) {
            BetterMobCombatReimagined.LOGGER.warn(
                    "[BMC custom-model] Could not resolve the requested animation parts for mob={} model={} "
                            + "activeChannels={} resolvedParts={}",
                    entity.getType(), model.getClass().getName(), animated, binding.names()
            );
        }
    }

    private record VisibilityState(ModelPart part, boolean visible) {
    }

    private record CrossedArmBinding(String name, ModelPart part) {
    }

    private record NamedPart(String name, ModelPart part) {
    }

    private record PartBinding(
            EnumMap<EmbeddedPlayerAnimator.AnimatedPart, ModelPart> parts,
            EnumMap<EmbeddedPlayerAnimator.AnimatedPart, String> names,
            ModelPart crossedArms,
            String crossedArmsName
    ) {
        ModelPart get(EmbeddedPlayerAnimator.AnimatedPart part) {
            return parts.get(part);
        }

        boolean hasAny(EnumSet<EmbeddedPlayerAnimator.AnimatedPart> requested) {
            for (EmbeddedPlayerAnimator.AnimatedPart part : requested) {
                if (parts.containsKey(part)) {
                    return true;
                }
            }
            return false;
        }

        boolean hasCompleteHumanoidSkeleton() {
            return parts.containsKey(EmbeddedPlayerAnimator.AnimatedPart.HEAD)
                    && parts.containsKey(EmbeddedPlayerAnimator.AnimatedPart.TORSO)
                    && parts.containsKey(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM)
                    && parts.containsKey(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM)
                    && parts.containsKey(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG)
                    && parts.containsKey(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG);
        }
    }
}
