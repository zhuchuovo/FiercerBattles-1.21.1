package com.fiercerbattles.mobcombat.internal.mobanim;

import com.fiercerbattles.mobcombat.BetterMobCombatReimagined;
import com.fiercerbattles.mobcombat.api.MobAnimationAccess;
import com.fiercerbattles.mobcombat.config.BMCConfig;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Vindicator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

public final class OptionalEmfCompat {
    private static final String EMF_API = "traben.entity_model_features.EMFAnimationApi";
    private static final String EMF_MODEL_INTERFACE = "traben.entity_model_features.models.IEMFModel";

    private static final String[] HEAD_NAMES = {"head", "head_root", "headroot", "skull"};
    private static final String[] BODY_NAMES = {"body", "torso", "chest", "upper_body", "upperbody"};
    private static final String[] LEFT_ARM_NAMES = {
            "left_arm", "leftarm", "arm_left", "armleft", "left_upper_arm", "leftupperarm", "larm"
    };
    private static final String[] RIGHT_ARM_NAMES = {
            "right_arm", "rightarm", "arm_right", "armright", "right_upper_arm", "rightupperarm", "rarm"
    };
    private static final String[] LEFT_LEG_NAMES = {
            "left_leg", "leftleg", "leg_left", "legleft", "left_upper_leg", "leftupperleg", "lleg"
    };
    private static final String[] RIGHT_LEG_NAMES = {
            "right_leg", "rightleg", "leg_right", "legright", "right_upper_leg", "rightupperleg", "rleg"
    };
    private static final String[] CROSSED_ARM_NAMES = {
            "arms", "crossed_arms", "crossedarms", "arms_rotation", "armsrotation"
    };

    private static final Set<UUID> PAUSED = new HashSet<>();
    private static final Set<String> BMC$EMF_ARM_DIAGNOSTICS = new HashSet<>();
    private static final Map<UUID, List<PartState>> SAVED_PARTS = new HashMap<>();


    private static final Set<LivingEntity> HUMANOID_RENDER_PASS =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final ThreadLocal<EmfRenderContext> ACTIVE_EMF_RENDER = new ThreadLocal<>();

    private static final Map<LivingEntity, NonArmSnapshot>
            NON_ARM_STATE = new WeakHashMap<>();
    private static final Map<LivingEntity, NonArmSnapshot>
            WATERBORNE_STATE = new WeakHashMap<>();
    private static final Map<LivingEntity, Boolean>
            LAST_WATERBORNE_STATE = new WeakHashMap<>();

    private static boolean initialized;
    private static boolean available;
    private static boolean warned;
    private static boolean pauseConditionRegistered;
    private static Method emfEntityAccessor;
    private static Class<?> emfModelInterface;
    private static Method isModelAnimated;
    private static Method emfEntityOf;
    private static Method pauseAnimations;
    private static Method resumeAnimations;
    private static Method getEmfRootModel;
    private static final Map<Class<?>, Method> EMF_VANILLA_PART_METHODS = new HashMap<>();
    private static final Set<Class<?>> EMF_VANILLA_PART_METHOD_MISSES = new HashSet<>();
    private static final Map<Class<?>, Field> EMF_VANILLA_NAME_FIELDS = new HashMap<>();
    private static final Set<Class<?>> EMF_VANILLA_NAME_FIELD_MISSES = new HashSet<>();

    private OptionalEmfCompat() {
    }

    public static boolean isWaterborne(LivingEntity entity) {
        return entity.isSwimming()
                || entity.isVisuallySwimming()
                || entity.isInWater()
                || entity.isUnderWater();
    }

    public static void pause(LivingEntity entity, EntityModel<?> model) {

        boolean vindicatorCombatOverride = IllagerArmOwnership.suppressesVanillaAttack(entity);
        if (!(entity instanceof MobAnimationAccess) || !initialize()) {
            return;
        }

        HUMANOID_RENDER_PASS.remove(entity);
        if (!GenericHumanoidModelCompat.supportsModel(model)) {
            return;
        }
        HUMANOID_RENDER_PASS.add(entity);
        beginEmfRender(entity, model);

        if (!EmbeddedPlayerAnimator.isAnimating(entity) && !vindicatorCombatOverride) {
            return;
        }

        UUID uuid = entity.getUUID();

        if (pauseConditionRegistered && shouldPauseWholeEmfModel(entity)) {
            try {
                restoreSavedParts(SAVED_PARTS.remove(uuid));

                if (!Boolean.TRUE.equals(isModelAnimated.invoke(null, model))) {
                    return;
                }

                EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated =
                        EmbeddedPlayerAnimator.getCurrentlyAnimatedParts(entity);
                bmc$ensureWeaponArms(entity, animated);
                if (animated.isEmpty()) {
                    return;
                }

                Set<ModelPart> unused = Collections.newSetFromMap(new IdentityHashMap<>());
                List<PartState> saved =
                        applyFreshAnimationsArmMapping(entity, model, animated, unused);
                if (!saved.isEmpty()) {
                    SAVED_PARTS.put(uuid, saved);
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                restoreSavedParts(SAVED_PARTS.remove(uuid));
                warnOnce("Failed to apply Fresh Animations visibility mapping", exception);
            }
            return;
        }
        try {

            resumeIfPaused(entity, uuid);
            restoreSavedParts(SAVED_PARTS.remove(uuid));

            if (!Boolean.TRUE.equals(isModelAnimated.invoke(null, model))) {
                return;
            }

            // Babies and Vindicators keep EMF/Fresh Animations fully live. BMC overlays only
            // their combat rotations immediately after EMFModelPartRoot.animate(), so FA never
            // crosses a pause/resume boundary during an attack.
            if (usesLiveLateArmOverlay(entity)) {
                return;
            }

            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated =
                    EmbeddedPlayerAnimator.getCurrentlyAnimatedParts(entity);


            boolean isEmfHumanoid = model instanceof HumanoidModel<?> || model instanceof IllagerModel<?>;
            if (isEmfHumanoid) {
                bmc$ensureWeaponArms(entity, animated);
            }

            if (entity.isBaby()) {
                // Fresh Animations keeps ownership of the baby's head/torso/legs (that is where
                // FA's baby shaping lives). Better Combat pauses and replaces only the arms.
                animated.retainAll(EnumSet.of(
                        EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM,
                        EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM
                ));
            }

            if (model instanceof IllagerModel<?> && entity instanceof AbstractIllager) {
                if (entity instanceof Vindicator && isWaterborne(entity)) {
                    // During swimming, Fresh Animations keeps ownership of the current-frame
                    // head/body/legs. BMC pauses and replaces only the weapon arms.
                    animated.retainAll(EnumSet.of(
                            EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM,
                            EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM
                    ));
                } else {
                    animated.retainAll(EnumSet.of(
                            EmbeddedPlayerAnimator.AnimatedPart.HEAD,
                            EmbeddedPlayerAnimator.AnimatedPart.TORSO,
                            EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM,
                            EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM
                    ));
                }
            }

            if (animated.isEmpty()) {
                return;
            }

            Set<ModelPart> partsToPause = Collections.newSetFromMap(new IdentityHashMap<>());
            collectControlledParts(model, animated, partsToPause);

            List<PartState> saved = applyFreshAnimationsArmMapping(entity, model, animated, partsToPause);
            if (!saved.isEmpty()) {
                SAVED_PARTS.put(uuid, saved);
            }

            if (partsToPause.isEmpty()) {
                restoreSavedParts(SAVED_PARTS.remove(uuid));
                return;
            }

            Object emfEntity = emfEntityOf.invoke(null, entity);
            if (emfEntity == null) {
                restoreSavedParts(SAVED_PARTS.remove(uuid));
                return;
            }

            pauseAnimations.invoke(null, emfEntity, (Object) partsToPause.toArray(ModelPart[]::new));
            PAUSED.add(uuid);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            try {
                resumeIfPaused(entity, uuid);
            } catch (ReflectiveOperationException | RuntimeException resumeException) {
                exception.addSuppressed(resumeException);
            }
            restoreSavedParts(SAVED_PARTS.remove(uuid));
            warnOnce("Failed to coordinate Fresh Animations/EMF with a mob attack animation", exception);
        }
    }

    private static void beginEmfRender(LivingEntity entity, EntityModel<?> model) {
        ACTIVE_EMF_RENDER.remove();
        pushEmfRenderContext(entity, model, null);
    }

    /**
     * Starts a nested EMF render context for a secondary model rendered by an entity layer.
     * Drowned render their outer skin with a second DrownedModel, so it must receive its own
     * post-EMF Better Combat overlay instead of reusing the already-applied base-model context.
     */
    public static void beginEmfLayerRender(LivingEntity entity, EntityModel<?> model) {
        EmfRenderContext current = ACTIVE_EMF_RENDER.get();
        if (current == null || current.entity != entity) {
            return;
        }
        pushEmfRenderContext(entity, model, current);
    }

    /** Restores the parent model context after a secondary EMF render layer finishes. */
    public static void endEmfLayerRender(LivingEntity entity, EntityModel<?> model) {
        EmfRenderContext current = ACTIVE_EMF_RENDER.get();
        if (current == null || current.entity != entity || current.model != model) {
            return;
        }
        if (current.previous == null) {
            ACTIVE_EMF_RENDER.remove();
        } else {
            ACTIVE_EMF_RENDER.set(current.previous);
        }
    }

    private static void pushEmfRenderContext(
            LivingEntity entity,
            EntityModel<?> model,
            EmfRenderContext previous
    ) {
        if (emfModelInterface == null || !emfModelInterface.isInstance(model)) {
            return;
        }
        try {
            Object rootObject = getEmfRootModel.invoke(model);
            if (rootObject instanceof ModelPart) {
                ACTIVE_EMF_RENDER.set(new EmfRenderContext(entity, model, previous));
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Failed to prepare the EMF render-time arm overlay", exception);
        }
    }

    /**
     * Re-checks the renderer's actual model immediately before the base model is rendered.
     *
     * Some renderer mods (notably Villager Retaliation) swap from the vanilla villager model to
     * a separate held-item/combat model after LivingEntityRenderer#render has already started.
     * The HEAD hook therefore sees an unsupported vanilla VillagerModel and cannot establish an
     * EMF render context. Without refreshing here, EMF animates the replacement model but BMC's
     * post-EMF arm overlay has no model/root to target, producing a moving body with frozen arms.
     */
    public static void refreshCurrentRenderModel(LivingEntity entity, EntityModel<?> model) {
        if (!(entity instanceof MobAnimationAccess)
                || !initialize()
                || !GenericHumanoidModelCompat.supportsModel(model)
                || emfModelInterface == null
                || !emfModelInterface.isInstance(model)) {
            return;
        }

        HUMANOID_RENDER_PASS.add(entity);

        EmfRenderContext current = ACTIVE_EMF_RENDER.get();
        if (current != null && current.entity == entity && current.model == model) {
            return;
        }

        // This is a base-renderer model replacement, not a nested render layer. Any context that
        // was prepared at HEAD belongs to the stale model and must not be restored afterward.
        ACTIVE_EMF_RENDER.remove();
        pushEmfRenderContext(entity, model, null);

    }

    /** Called by the optional EMF model-part mixin directly after EMF runs its live animation. */
    public static void reapplyArmsAfterEmfAnimation() {
        EmfRenderContext context = ACTIVE_EMF_RENDER.get();
        if (context == null || context.applied) {
            return;
        }

        // Humanoid models render their head/body/limbs as separate EMFModelPartWithState sections.
        // The first section asks the shared root to animate the entity for this render; requiring
        // that section itself to be the root meant the late overlay never ran for baby zombies.
        context.applied = true;
        reapplyArms(context.entity, context.model);
    }

    /**
     * Final safety net immediately before the renderer draws the base model.
     *
     * <p>Normally {@link #reapplyArmsAfterEmfAnimation()} runs directly after EMF evaluates its
     * live animation. A renderer that swaps to a different model later in the same render (for
     * example Villager Retaliation's held-item combat model) creates its EMF context only after
     * that animation callback has already passed. In that case the refreshed context is valid but
     * still unapplied. Reapply it here exactly once, after every model swap/setup pass and before
     * {@code renderToBuffer}, so Fresh Animations cannot leave the visible weapon arms in its idle
     * pose.</p>
     */
    public static void reapplyArmsBeforeBaseModel(LivingEntity entity, EntityModel<?> model) {
        EmfRenderContext context = ACTIVE_EMF_RENDER.get();
        if (context == null
                || context.applied
                || context.entity != entity
                || context.model != model) {
            return;
        }

        context.applied = true;
        reapplyArms(entity, model);
    }

    public static void reapplyArms(LivingEntity entity, EntityModel<?> model) {
        boolean vindicatorCombatOverride = IllagerArmOwnership.suppressesVanillaAttack(entity);
        if (!(entity instanceof MobAnimationAccess)
                || (!EmbeddedPlayerAnimator.isAnimating(entity) && !vindicatorCombatOverride)
                || !initialized
                || !available
                || !GenericHumanoidModelCompat.supportsModel(model)) {
            return;
        }

        if (pauseConditionRegistered && shouldPauseWholeEmfModel(entity)) {
            reapplyWholePausedEmfAnimation(entity, model);
            return;
        }
        if (!usesLiveLateArmOverlay(entity) && !PAUSED.contains(entity.getUUID())) {
            return;
        }

        if (entity.isBaby() && reapplyEmfArmsOnly(entity, model)) {
            return;
        }

        if (entity instanceof AbstractIllager
                && model instanceof IllagerModel<?>
                && reapplyIllagerEmfUpperBody(entity, model)) {
            return;
        }

        if (!(model instanceof HumanoidModelAccess humanoidAccess)) {
            return;
        }
        if (!(model instanceof HumanoidModel<?> || model instanceof IllagerModel<?>)) {
            return;
        }

        EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated =
                EmbeddedPlayerAnimator.getCurrentlyAnimatedParts(entity);
        bmc$ensureWeaponArms(entity, animated);
        if (animated.isEmpty()) {
            return;
        }

        EmbeddedPlayerAnimator.applyAttackArmsOnly(
                humanoidAccess,
                EmbeddedPlayerAnimator.getAnimation(entity),
                animated
        );

        boolean attacking = EmbeddedPlayerAnimator.isAttackAnimating(entity);
        if (BMCConfig.DEBUG_LOGGING.get() && attacking && ROT_SAMPLES < 24) {
            ROT_SAMPLES++;
            ModelPart right = humanoidAccess.bmc$getRightArm();
            ModelPart left = humanoidAccess.bmc$getLeftArm();
            var applier = EmbeddedPlayerAnimator.getAnimation(entity);
            BetterMobCombatReimagined.LOGGER.warn(
                    "[BMC-EMF-ROT] {} #{} attacking={} applier={} applierActive={} channels={} "
                            + "| R x={} y={} z={} | L x={} y={} z={}",
                    entity.getType(),
                    ROT_SAMPLES,
                    attacking,
                    applier == null ? "null" : "present",
                    applier != null && applier.isActive(),
                    animated,
                    right.xRot, right.yRot, right.zRot,
                    left.xRot, left.yRot, left.zRot
            );
        }
    }

    private static boolean reapplyIllagerEmfUpperBody(LivingEntity entity, EntityModel<?> model) {
        if (emfModelInterface == null
                || !emfModelInterface.isInstance(model)
                || !(model instanceof HumanoidModelAccess humanoidAccess)) {
            return false;
        }

        try {
            if (!Boolean.TRUE.equals(isModelAnimated.invoke(null, model))) {
                return false;
            }
            Object rootObject = getEmfRootModel.invoke(model);
            if (!(rootObject instanceof ModelPart root)) {
                return false;
            }

            var animation = EmbeddedPlayerAnimator.getAnimation(entity);
            boolean animationActive = animation != null && animation.isActive();
            if (animationActive && entity instanceof MobAnimationAccess access) {
                animation.setTickDelta(access.bmc$getRenderPartialTick());
                animationActive = animation.isActive();
            }
            if (!animationActive && !IllagerArmOwnership.suppressesVanillaAttack(entity)) {
                return false;
            }

            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> authored = animationActive
                    ? EmbeddedPlayerAnimator.getCurrentlyAnimatedParts(entity)
                    : EnumSet.noneOf(EmbeddedPlayerAnimator.AnimatedPart.class);
            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> owned = authored.clone();
            bmc$ensureWeaponArms(entity, owned);
            boolean leftOwned = owned.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
            boolean rightOwned = owned.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
            if (!leftOwned && !rightOwned) {
                return false;
            }

            ModelPart emfLeftArm = findEmfVanillaPart(root, LEFT_ARM_NAMES);
            ModelPart emfRightArm = findEmfVanillaPart(root, RIGHT_ARM_NAMES);
            if (emfLeftArm == null && emfRightArm == null) {
                return false;
            }

            List<PartState> saved = SAVED_PARTS.computeIfAbsent(
                    entity.getUUID(), ignored -> new ArrayList<>()
            );
            setIllagerCrossedArmsVisible(model, root, false, saved);
            setVisible(emfLeftArm, true, saved);
            setVisible(emfRightArm, true, saved);

            if (entity instanceof Vindicator) {
                if (animationActive) {
                    // Fresh Animations already produced the current-frame walk/swim/idle hierarchy.
                    // Layer only Better Combat's rotational channels over that live pose. Never
                    // reset or copy the head, pivots, scale, or legs: those writes caused the head
                    // hop, leg explosions, and pause/resume stutter on Vindicators.
                    if (authored.contains(EmbeddedPlayerAnimator.AnimatedPart.TORSO)) {
                        ModelPart emfBody = findEmfVanillaPart(root, BODY_NAMES);
                        applyRotationWithoutBend(animation, "torso", emfBody);
                    }
                    applyRotationOnly(animation, "leftArm", emfLeftArm, leftOwned);
                    applyRotationOnly(animation, "rightArm", emfRightArm, rightOwned);
                }

                debugLiveArmOverlayOnce(entity, root, emfLeftArm, emfRightArm, "live-vindicator");
                return (leftOwned && emfLeftArm != null) || (rightOwned && emfRightArm != null);
            }

            // Keep the existing paused-part behavior for other illagers. Only Vindicators are
            // moved to the fully live FA overlay path by this compatibility fix.
            if (authored.contains(EmbeddedPlayerAnimator.AnimatedPart.TORSO)) {
                ModelPart emfBody = findAliasedPart(root, BODY_NAMES);
                resetPart(emfBody);
                copyPoseDelta(humanoidAccess.bmc$getBody(), emfBody);
            }
            if (authored.contains(EmbeddedPlayerAnimator.AnimatedPart.HEAD)) {
                ModelPart emfHead = findAliasedPart(root, HEAD_NAMES);
                resetPartTree(emfHead);
                copyPoseDelta(humanoidAccess.bmc$getHead(), emfHead);
            }

            resetPartTree(emfLeftArm);
            resetPartTree(emfRightArm);
            copyPoseDelta(humanoidAccess.bmc$getLeftArm(), emfLeftArm);
            copyPoseDelta(humanoidAccess.bmc$getRightArm(), emfRightArm);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Failed to apply Better Combat to Fresh Animations' visible illager arms", exception);
            return false;
        }
    }


    private static boolean applyCapturedArmBranches(
            dev.kosmx.playerAnim.impl.animation.AnimationApplier animation,
            List<ArmBranchState> branches,
            String channel,
            boolean active
    ) {
        if (!active || branches.isEmpty()) {
            return false;
        }

        boolean applied = false;
        for (ArmBranchState branch : branches) {
            branch.restore();
            applyRotationOnly(animation, channel, branch.root());
            applied = true;
        }
        return applied;
    }

    private static void applyRotationOnly(
            dev.kosmx.playerAnim.impl.animation.AnimationApplier animation,
            String channel,
            ModelPart part
    ) {
        applyRotationOnly(animation, channel, part, true);
    }

    private static void applyRotationOnly(
            dev.kosmx.playerAnim.impl.animation.AnimationApplier animation,
            String channel,
            ModelPart part,
            boolean active
    ) {
        if (!active || part == null) {
            return;
        }

        dev.kosmx.playerAnim.core.util.Vec3f rotation = animation.get3DTransform(
                channel,
                dev.kosmx.playerAnim.api.TransformType.ROTATION,
                new dev.kosmx.playerAnim.core.util.Vec3f(part.xRot, part.yRot, part.zRot)
        );
        part.setRotation(rotation.getX(), rotation.getY(), rotation.getZ());
        dev.kosmx.playerAnim.impl.animation.IBendHelper.INSTANCE.bend(
                part,
                animation.getBend(channel)
        );
    }

    private static void applyRotationWithoutBend(
            dev.kosmx.playerAnim.impl.animation.AnimationApplier animation,
            String channel,
            ModelPart part
    ) {
        if (part == null) {
            return;
        }

        dev.kosmx.playerAnim.core.util.Vec3f rotation = animation.get3DTransform(
                channel,
                dev.kosmx.playerAnim.api.TransformType.ROTATION,
                new dev.kosmx.playerAnim.core.util.Vec3f(part.xRot, part.yRot, part.zRot)
        );
        part.setRotation(rotation.getX(), rotation.getY(), rotation.getZ());
    }

    private static boolean reapplyEmfArmsOnly(LivingEntity entity, EntityModel<?> model) {
        if (emfModelInterface == null || !emfModelInterface.isInstance(model)) {
            return false;
        }

        try {
            if (!Boolean.TRUE.equals(isModelAnimated.invoke(null, model))) {
                return false;
            }
            Object rootObject = getEmfRootModel.invoke(model);
            if (!(rootObject instanceof ModelPart root)) {
                return false;
            }

            var animation = EmbeddedPlayerAnimator.getAnimation(entity);
            boolean animationActive = animation != null && animation.isActive();
            if (animationActive && entity instanceof MobAnimationAccess access) {
                animation.setTickDelta(access.bmc$getRenderPartialTick());
                animationActive = animation.isActive();
            }
            if (!animationActive) {
                return false;
            }

            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> owned =
                    EmbeddedPlayerAnimator.getCurrentlyAnimatedParts(entity);
            bmc$ensureWeaponArms(entity, owned);
            boolean leftOwned = owned.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
            boolean rightOwned = owned.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
            if (!leftOwned && !rightOwned) {
                return false;
            }

            ModelPart emfLeftArm = findEmfVanillaPart(root, LEFT_ARM_NAMES);
            ModelPart emfRightArm = findEmfVanillaPart(root, RIGHT_ARM_NAMES);
            applyRotationOnly(animation, "leftArm", emfLeftArm, leftOwned);
            applyRotationOnly(animation, "rightArm", emfRightArm, rightOwned);
            debugLiveArmOverlayOnce(entity, root, emfLeftArm, emfRightArm, "baby");
            return (leftOwned && emfLeftArm != null) || (rightOwned && emfRightArm != null);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Failed to apply Better Combat arms to an EMF baby model", exception);
            return false;
        }
    }

    private static void resetPart(ModelPart part) {
        if (part != null) {
            part.resetPose();
        }
    }

    private static void resetPartTree(ModelPart part) {
        if (part == null) {
            return;
        }
        part.getAllParts().forEach(ModelPart::resetPose);
    }

    private static void copyTransform(ModelPart source, ModelPart target) {
        if (source == null || target == null || source == target) {
            return;
        }
        target.x = source.x;
        target.y = source.y;
        target.z = source.z;
        target.xRot = source.xRot;
        target.yRot = source.yRot;
        target.zRot = source.zRot;
        target.xScale = source.xScale;
        target.yScale = source.yScale;
        target.zScale = source.zScale;
    }

    private static void copyPoseDelta(ModelPart source, ModelPart target) {
        if (source == null || target == null || source == target) {
            return;
        }
        var sourceBase = source.getInitialPose();
        var targetBase = target.getInitialPose();
        target.x = targetBase.x + (source.x - sourceBase.x);
        target.y = targetBase.y + (source.y - sourceBase.y);
        target.z = targetBase.z + (source.z - sourceBase.z);
        target.xRot = targetBase.xRot + (source.xRot - sourceBase.xRot);
        target.yRot = targetBase.yRot + (source.yRot - sourceBase.yRot);
        target.zRot = targetBase.zRot + (source.zRot - sourceBase.zRot);
        target.xScale = source.xScale;
        target.yScale = source.yScale;
        target.zScale = source.zScale;
    }

    private static void reapplyWholePausedEmfAnimation(LivingEntity entity, EntityModel<?> model) {
        if (emfModelInterface == null || !emfModelInterface.isInstance(model)) {
            return;
        }

        try {
            Object rootObject = getEmfRootModel.invoke(model);
            if (!(rootObject instanceof ModelPart root)) {
                return;
            }

            var animation = EmbeddedPlayerAnimator.getAnimation(entity);
            if (animation == null || !animation.isActive()) {
                return;
            }

            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> authored =
                    EmbeddedPlayerAnimator.getCurrentlyAnimatedParts(entity);
            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> owned = authored.clone();
            bmc$ensureWeaponArms(entity, owned);
            if (owned.isEmpty()) {
                return;
            }

            if (entity instanceof MobAnimationAccess access) {
                animation.setTickDelta(access.bmc$getRenderPartialTick());
            }
            if (!animation.isActive()) {
                return;
            }

            ModelPart emfLeftArm = findAliasedPart(root, LEFT_ARM_NAMES);
            ModelPart emfRightArm = findAliasedPart(root, RIGHT_ARM_NAMES);
            boolean leftOwned = owned.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
            boolean rightOwned = owned.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);

            // Some custom villager renderers swap to a separate combat model whenever an item is
            // held. Villager Retaliation is one example: its combat model has independent
            // RightArm/LeftArm parts plus a separate crossed-arms part. When EMF owns that model,
            // make the actual weapon arms visible and hide only a truly separate crossed-arms
            // branch for the duration of BMC's attack render.
            if (leftOwned || rightOwned) {
                List<PartState> saved = SAVED_PARTS.computeIfAbsent(
                        entity.getUUID(), ignored -> new ArrayList<>()
                );
                if (leftOwned) {
                    setVisible(emfLeftArm, true, saved);
                }
                if (rightOwned) {
                    setVisible(emfRightArm, true, saved);
                }
                hideSeparatedCrossedArms(root, emfLeftArm, emfRightArm, saved);
            }

            if (entity instanceof Vindicator) {
                boolean waterborne = isWaterborne(entity);
                NonArmSnapshot snapshot;
                if (waterborne) {
                    snapshot = restoreWaterborneVindicatorNonArmState(entity, root);
                } else {
                    restoreVindicatorNonArmState(entity, root);
                    snapshot = null;
                    resetPartTree(emfLeftArm);
                    resetPartTree(emfRightArm);
                }

                List<PartState> saved = SAVED_PARTS.computeIfAbsent(
                        entity.getUUID(), ignored -> new ArrayList<>()
                );
                setIllagerCrossedArmsVisible(model, root, false, saved);

                if (waterborne) {
                    boolean leftApplied = false;
                    boolean rightApplied = false;
                    if (snapshot != null) {
                        leftApplied = applyCapturedArmBranches(
                                animation, snapshot.leftArmBranches(), "leftArm", leftOwned
                        );
                        rightApplied = applyCapturedArmBranches(
                                animation, snapshot.rightArmBranches(), "rightArm", rightOwned
                        );

                        setArmBranchesVisible(snapshot.leftArmBranches(), leftOwned, saved);
                        setArmBranchesVisible(snapshot.rightArmBranches(), rightOwned, saved);
                    }

                    // Never write Player Animator's arm POSITION channel into a live FA swimming
                    // hierarchy. On a transition frame there may be no water snapshot yet; keeping
                    // the current pivot and applying only the exact blended rotation prevents the
                    // arms from jumping above the Vindicator's head.
                    if (!leftApplied) {
                        setVisible(emfLeftArm, true, saved);
                        applyRotationOnly(animation, "leftArm", emfLeftArm, leftOwned);
                    }
                    if (!rightApplied) {
                        setVisible(emfRightArm, true, saved);
                        applyRotationOnly(animation, "rightArm", emfRightArm, rightOwned);
                    }
                } else {
                    setVisible(emfLeftArm, true, saved);
                    setVisible(emfRightArm, true, saved);
                    updateEmfPart(animation, "leftArm", emfLeftArm, leftOwned);
                    updateEmfPart(animation, "rightArm", emfRightArm, rightOwned);
                }
                return;
            }

            ModelPart emfHead = findAliasedPart(root, HEAD_NAMES);
            ModelPart emfBody = findAliasedPart(root, BODY_NAMES);
            ModelPart emfLeftLeg = findAliasedPart(root, LEFT_LEG_NAMES);
            ModelPart emfRightLeg = findAliasedPart(root, RIGHT_LEG_NAMES);

            resetPartTree(emfHead);
            resetPartTree(emfBody);
            resetPartTree(emfLeftArm);
            resetPartTree(emfRightArm);
            resetPartTree(emfLeftLeg);
            resetPartTree(emfRightLeg);

            if (model instanceof HumanoidModelAccess facade) {
                if (!authored.contains(EmbeddedPlayerAnimator.AnimatedPart.HEAD)) {
                    copyPoseDelta(facade.bmc$getHead(), emfHead);
                }
                if (!authored.contains(EmbeddedPlayerAnimator.AnimatedPart.TORSO)) {
                    copyPoseDelta(facade.bmc$getBody(), emfBody);
                }
                if (!leftOwned) {
                    copyPoseDelta(facade.bmc$getLeftArm(), emfLeftArm);
                }
                if (!rightOwned) {
                    copyPoseDelta(facade.bmc$getRightArm(), emfRightArm);
                }
                if (!authored.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG)) {
                    copyPoseDelta(facade.bmc$getLeftLeg(), emfLeftLeg);
                }
                if (!authored.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG)) {
                    copyPoseDelta(facade.bmc$getRightLeg(), emfRightLeg);
                }
            }

            updateEmfPart(animation, "head", emfHead,
                    authored.contains(EmbeddedPlayerAnimator.AnimatedPart.HEAD));
            updateEmfPart(animation, "torso", emfBody,
                    authored.contains(EmbeddedPlayerAnimator.AnimatedPart.TORSO));
            // `owned` includes the synchronized logical weapon arm even when Player Animator's
            // introspection does not report that arm as an authored channel. Using `authored` here
            // was the .11 regression that produced animated torsos with frozen weapon arms on
            // Villager Retaliation's held-item combat model.
            updateEmfPart(animation, "leftArm", emfLeftArm, leftOwned);
            updateEmfPart(animation, "rightArm", emfRightArm, rightOwned);
            updateEmfPart(animation, "leftLeg", emfLeftLeg,
                    authored.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG));
            updateEmfPart(animation, "rightLeg", emfRightLeg,
                    authored.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Failed to apply Better Combat keyframes to EMF's visible model tree", exception);
        }
    }

    private static void updateEmfPart(
            dev.kosmx.playerAnim.impl.animation.AnimationApplier animation,
            String channel,
            ModelPart part,
            boolean active
    ) {
        if (active && part != null) {
            animation.updatePart(channel, part);
        }
    }

    private static int ROT_SAMPLES;


    private static void bmc$ensureWeaponArms(
            LivingEntity entity,
            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated
    ) {

        if (entity instanceof AbstractIllager
                && IllagerArmOwnership.suppressesVanillaAttack(entity)) {
            animated.add(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
            animated.add(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
            return;
        }

        if (!(entity instanceof MobAnimationAccess access)) {
            return;
        }

        if (access.bmc$isTwoHandedArmAnimationActive()) {
            animated.add(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
            animated.add(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
            return;
        }

        if (access.bmc$isAttackAnimationActive()) {
            // Use the synchronized attack packet's logical hand instead of assuming every
            // one-handed attack belongs to the mob's dominant arm. Custom villager/NPC renderers
            // (Villager Retaliation, MCA-style models, etc.) commonly expose independent arms and
            // need the same hand ownership rule as GenericHumanoidModelCompat.
            boolean leftArm = access.bmc$isOffHandAttackAnimationActive();
            if (entity instanceof Mob mob && mob.isLeftHanded()) {
                leftArm = !leftArm;
            }
            animated.add(leftArm
                    ? EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM
                    : EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
        }
    }

    public static void resume(LivingEntity entity) {
        if (!(entity instanceof MobAnimationAccess) || !initialized || !available) {
            return;
        }

        UUID uuid = entity.getUUID();
        try {
            resumeIfPaused(entity, uuid);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Failed to resume Fresh Animations/EMF after an animated mob render", exception);
        } finally {
            HUMANOID_RENDER_PASS.remove(entity);
            EmfRenderContext context = ACTIVE_EMF_RENDER.get();
            if (context != null && context.entity == entity) {
                ACTIVE_EMF_RENDER.remove();
            }
            restoreSavedParts(SAVED_PARTS.remove(uuid));
        }
    }

    private static void resumeIfPaused(LivingEntity entity, UUID uuid) throws ReflectiveOperationException {
        if (!PAUSED.remove(uuid)) {
            return;
        }
        Object emfEntity = emfEntityOf.invoke(null, entity);
        if (emfEntity != null) {
            resumeAnimations.invoke(null, emfEntity);
        }
    }


    private static List<PartState> applyFreshAnimationsArmMapping(
            LivingEntity entity,
            EntityModel<?> model,
            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated,
            Set<ModelPart> partsToPause
    ) throws ReflectiveOperationException {
        boolean isIllager = model instanceof IllagerModel<?>;
        boolean isHumanoidFamily = isIllager || model instanceof HumanoidModel<?>;
        if (!isHumanoidFamily || emfModelInterface == null || !emfModelInterface.isInstance(model)) {
            return List.of();
        }

        Object rootObject = getEmfRootModel.invoke(model);
        if (!(rootObject instanceof ModelPart root)) {
            return List.of();
        }

        dumpEmfTreeOnce(entity, root);
        dumpIdentityOnce(entity, model, root);

        List<PartState> saved = new ArrayList<>();
        boolean head = animated.contains(EmbeddedPlayerAnimator.AnimatedPart.HEAD);
        boolean torso = animated.contains(EmbeddedPlayerAnimator.AnimatedPart.TORSO);
        boolean left = animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
        boolean right = animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
        boolean foundAny = false;


        ModelPart emfHead = findAliasedPart(root, HEAD_NAMES);
        ModelPart emfBody = findAliasedPart(root, BODY_NAMES);
        ModelPart emfLeftArm = findAliasedPart(root, LEFT_ARM_NAMES);
        ModelPart emfRightArm = findAliasedPart(root, RIGHT_ARM_NAMES);

        if (head && addPartTree(emfHead, partsToPause)) {
            foundAny = true;
        }
        if (torso && addPartTree(emfBody, partsToPause)) {
            foundAny = true;
        }
        NonArmSnapshot snapshot = getSnapshot(entity, root, false);
        boolean capturedSnapshot = snapshot != null;

        if (left) {
            if (!entity.isBaby()) {
                setVisible(emfLeftArm, true, saved);
            }
            if (entity.isBaby() && capturedSnapshot
                    ? addArmBranches(snapshot.leftArmBranches(), partsToPause)
                    : addPartTree(emfLeftArm, partsToPause)) {
                foundAny = true;
            }
        }
        if (right) {
            if (!entity.isBaby()) {
                setVisible(emfRightArm, true, saved);
            }
            if (entity.isBaby() && capturedSnapshot
                    ? addArmBranches(snapshot.rightArmBranches(), partsToPause)
                    : addPartTree(emfRightArm, partsToPause)) {
                foundAny = true;
            }
        }

        if (isIllager && (left || right)) {
            setIllagerCrossedArmsVisible(model, root, false, saved);
        }

        if ((head || torso || left || right) && !foundAny) {
            debugOnce("EMF upper-body part lookup found no matching head/body/arm branches on the "
                    + "EMF root for " + entity.getType() + " (model "
                    + model.getClass().getName() + "). Use EMF's config > Tools > Export models "
                    + "to log this model's real part names.");
        }

        return saved;
    }

    private static final Set<String> DEBUG_LOGGED = new HashSet<>();

    private static void debugOnce(String message) {
        if (!BMCConfig.DEBUG_LOGGING.get()) {
            return;
        }
        if (DEBUG_LOGGED.add(message)) {
            BetterMobCombatReimagined.LOGGER.warn("[BMC-EMF-DEBUG] {}", message);
        }
    }


    private static void dumpIdentityOnce(LivingEntity entity, EntityModel<?> model, ModelPart root) {
        if (!BMCConfig.DEBUG_LOGGING.get()) {
            return;
        }
        String key = "identity|" + entity.getType();
        if (!DEBUG_LOGGED.add(key)) {
            return;
        }
        if (!(model instanceof HumanoidModelAccess access)) {
            BetterMobCombatReimagined.LOGGER.warn(
                    "[BMC-EMF-IDENTITY] {} model {} is NOT a HumanoidModelAccess - our arm pose never reaches it",
                    entity.getType(),
                    model.getClass().getName()
            );
            return;
        }
        ModelPart posedLeft = access.bmc$getLeftArm();
        ModelPart posedRight = access.bmc$getRightArm();
        ModelPart emfLeft = findAliasedPart(root, LEFT_ARM_NAMES);
        ModelPart emfRight = findAliasedPart(root, RIGHT_ARM_NAMES);

        BetterMobCombatReimagined.LOGGER.warn(
                "[BMC-EMF-IDENTITY] {} | posedLeft={} emfLeft={} SAME_LEFT={} | posedRight={} emfRight={} SAME_RIGHT={}",
                entity.getType(),
                System.identityHashCode(posedLeft),
                emfLeft == null ? "null" : String.valueOf(System.identityHashCode(emfLeft)),
                posedLeft == emfLeft,
                System.identityHashCode(posedRight),
                emfRight == null ? "null" : String.valueOf(System.identityHashCode(emfRight)),
                posedRight == emfRight
        );
    }


    @SuppressWarnings("unchecked")
    private static void dumpEmfTreeOnce(LivingEntity entity, ModelPart root) {
        if (!BMCConfig.DEBUG_LOGGING.get()) {
            return;
        }
        String key = "tree|" + entity.getType();
        if (!DEBUG_LOGGED.add(key)) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        try {
            java.lang.reflect.Field childrenField = ModelPart.class.getDeclaredField("children");
            childrenField.setAccessible(true);
            appendTree(root, (Map<String, ModelPart>) childrenField.get(root), childrenField, builder, 0);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            builder.append(" <could not read children: ").append(exception).append('>');
        }
        BetterMobCombatReimagined.LOGGER.warn(
                "[BMC-EMF-TREE] real EMF part hierarchy for {}:\n{}",
                entity.getType(),
                builder
        );
    }

    @SuppressWarnings("unchecked")
    private static void appendTree(
            ModelPart part,
            Map<String, ModelPart> children,
            java.lang.reflect.Field childrenField,
            StringBuilder builder,
            int depth
    ) throws ReflectiveOperationException {
        if (depth > 6 || children == null) {
            return;
        }
        for (Map.Entry<String, ModelPart> entry : children.entrySet()) {
            builder.append("  ".repeat(depth + 1)).append(entry.getKey()).append('\n');
            appendTree(
                    entry.getValue(),
                    (Map<String, ModelPart>) childrenField.get(entry.getValue()),
                    childrenField,
                    builder,
                    depth + 1
            );
        }
    }


    private static void debugArmBranchesOnce(
            LivingEntity entity,
            ModelPart root,
            List<ArmBranchState> leftBranches,
            List<ArmBranchState> rightBranches
    ) {
        if (!BMCConfig.DEBUG_LOGGING.get()) {
            return;
        }
        String key = "arm-branches|" + entity.getType() + "|baby=" + entity.isBaby()
                + "|water=" + isWaterborne(entity);
        if (!DEBUG_LOGGED.add(key)) {
            return;
        }
        BetterMobCombatReimagined.LOGGER.warn(
                "[BMC-EMF-ARMS] {} baby={} water={} | left={} | right={}",
                entity.getType(),
                entity.isBaby(),
                isWaterborne(entity),
                describeArmBranches(root, leftBranches),
                describeArmBranches(root, rightBranches)
        );
    }

    private static List<String> describeArmBranches(
            ModelPart root,
            List<ArmBranchState> branches
    ) {
        List<String> descriptions = new ArrayList<>(branches.size());
        for (ArmBranchState branch : branches) {
            ModelPart part = branch.root();
            descriptions.add(findPartPath(root, part)
                    + "@" + System.identityHashCode(part)
                    + " visible=" + part.visible
                    + " skipDraw=" + part.skipDraw
                    + " pivot=(" + part.x + "," + part.y + "," + part.z + ")");
        }
        return descriptions;
    }

    private static String findPartPath(ModelPart root, ModelPart target) {
        if (root == target) {
            return "root";
        }
        try {
            Field childrenField = ModelPart.class.getDeclaredField("children");
            childrenField.setAccessible(true);
            ArrayDeque<PartPathNode> queue = new ArrayDeque<>();
            queue.add(new PartPathNode(root, "root"));
            Set<ModelPart> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            while (!queue.isEmpty()) {
                PartPathNode current = queue.removeFirst();
                if (!visited.add(current.part())) {
                    continue;
                }
                Object raw = childrenField.get(current.part());
                if (!(raw instanceof Map<?, ?> children)) {
                    continue;
                }
                for (Map.Entry<?, ?> child : children.entrySet()) {
                    if (!(child.getKey() instanceof String name)
                            || !(child.getValue() instanceof ModelPart part)) {
                        continue;
                    }
                    String path = current.path() + "#" + name;
                    if (part == target) {
                        return path;
                    }
                    queue.addLast(new PartPathNode(part, path));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return "<unresolved>";
    }

    private static List<ArmBranchState> captureArmBranches(
            ModelPart root,
            boolean includeHiddenVariants,
            String... aliases
    ) {
        List<ModelPart> branchRoots;
        if (includeHiddenVariants) {
            // Model instances are reused between entities, so visibility at render HEAD can still
            // describe the previously-rendered adult. Capture every outermost baby arm variant;
            // hidden branches remain hidden and only FA's selected branch is eventually rendered.
            branchRoots = findAliasedBranchRoots(root, false, aliases);
        } else {
            branchRoots = findAliasedBranchRoots(root, true, aliases);
            if (branchRoots.isEmpty()) {
                branchRoots = findAliasedBranchRoots(root, false, aliases);
            }
        }

        List<ArmBranchState> branches = new ArrayList<>(branchRoots.size());
        for (ModelPart branchRoot : branchRoots) {
            List<PartState> states = new ArrayList<>();
            branchRoot.getAllParts().forEach(part -> states.add(PartState.capture(part)));
            branches.add(new ArmBranchState(branchRoot, List.copyOf(states)));
        }
        return branches;
    }

    private static List<ModelPart> findAliasedBranchRoots(
            ModelPart root,
            boolean visibleOnly,
            String... aliases
    ) {
        if (root == null) {
            return List.of();
        }

        Set<String> normalizedAliases = new HashSet<>();
        for (String alias : aliases) {
            normalizedAliases.add(normalizePartName(alias));
        }

        List<ModelPart> matches = new ArrayList<>();
        try {
            Field childrenField = ModelPart.class.getDeclaredField("children");
            childrenField.setAccessible(true);

            ArrayDeque<ArmSearchNode> queue = new ArrayDeque<>();
            queue.add(new ArmSearchNode(root, root.visible, false));
            Set<ModelPart> visited = Collections.newSetFromMap(new IdentityHashMap<>());

            while (!queue.isEmpty()) {
                ArmSearchNode current = queue.removeFirst();
                if (!visited.add(current.part())) {
                    continue;
                }

                Object raw = childrenField.get(current.part());
                if (!(raw instanceof Map<?, ?> children)) {
                    continue;
                }

                for (Map.Entry<?, ?> child : children.entrySet()) {
                    if (!(child.getKey() instanceof String name)
                            || !(child.getValue() instanceof ModelPart part)) {
                        continue;
                    }

                    boolean effectiveVisible = current.effectivelyVisible() && part.visible;
                    boolean aliasMatch = matchesPartAlias(normalizePartName(name), normalizedAliases);
                    if (aliasMatch
                            && !current.ancestorAlias()
                            && (!visibleOnly || effectiveVisible)) {
                        matches.add(part);
                    }

                    queue.addLast(new ArmSearchNode(
                            part,
                            effectiveVisible,
                            current.ancestorAlias() || aliasMatch
                    ));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return List.of();
        }
        return List.copyOf(matches);
    }

    private static boolean addArmBranches(
            List<ArmBranchState> branches,
            Set<ModelPart> partsToPause
    ) {
        boolean added = false;
        for (ArmBranchState branch : branches) {
            for (PartState state : branch.states()) {
                partsToPause.add(state.part());
                added = true;
            }
        }
        return added;
    }

    private static void setArmBranchesVisible(
            List<ArmBranchState> branches,
            boolean visible,
            List<PartState> saved
    ) {
        if (!visible) {
            return;
        }
        for (ArmBranchState branch : branches) {
            setVisible(branch.root(), true, saved);
        }
    }

    private static boolean addPartTree(ModelPart part, Set<ModelPart> partsToPause) {
        if (part == null) {
            return false;
        }
        part.getAllParts().forEach(partsToPause::add);
        return true;
    }

    private static void hideSeparatedCrossedArms(
            ModelPart root,
            ModelPart leftArm,
            ModelPart rightArm,
            List<PartState> saved
    ) {
        ModelPart crossedArms = findAliasedPart(root, CROSSED_ARM_NAMES);
        if (crossedArms == null
                || crossedArms == leftArm
                || crossedArms == rightArm
                || containsPart(crossedArms, leftArm)
                || containsPart(crossedArms, rightArm)) {
            return;
        }
        setVisible(crossedArms, false, saved);
    }

    private static boolean containsPart(ModelPart root, ModelPart target) {
        return root != null
                && target != null
                && root.getAllParts().anyMatch(part -> part == target);
    }

    private static void setVisible(ModelPart part, boolean visible, List<PartState> saved) {
        if (part != null && part.visible != visible) {
            saveOnce(part, saved);
            part.visible = visible;
        }
    }

    private static boolean addIfPresent(ModelPart root, String path, Set<ModelPart> partsToPause) {
        ModelPart part = findPart(root, path);
        if (part != null) {
            partsToPause.add(part);
            return true;
        }
        return false;
    }

    private static void saveOnce(ModelPart part, List<PartState> saved) {
        for (PartState state : saved) {
            if (state.part() == part) {
                return;
            }
        }
        saved.add(PartState.capture(part));
    }


    public static boolean isEmfModel(EntityModel<?> model) {
        return initialize()
                && emfModelInterface != null
                && emfModelInterface.isInstance(model);
    }


    public static boolean isEmfAnimatedModel(EntityModel<?> model) {
        if (!initialize()) {
            return false;
        }
        try {
            return emfModelInterface != null
                    && emfModelInterface.isInstance(model)
                    && Boolean.TRUE.equals(isModelAnimated.invoke(null, model));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    public static boolean isAnimatedEmfVindicator(LivingEntity entity, EntityModel<?> model) {
        if (entity.getType() != EntityType.VINDICATOR || !initialize()) {
            return false;
        }
        try {
            return emfModelInterface != null
                    && emfModelInterface.isInstance(model)
                    && Boolean.TRUE.equals(isModelAnimated.invoke(null, model));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Failed to query the active EMF Vindicator model", exception);
            return false;
        }
    }

    private static void setIllagerCrossedArmsVisible(
            EntityModel<?> model,
            ModelPart root,
            boolean visible,
            List<PartState> saved
    ) {
        Set<ModelPart> crossed = Collections.newSetFromMap(new IdentityHashMap<>());
        crossed.addAll(findAllAliasedParts(root, CROSSED_ARM_NAMES));
        crossed.addAll(findLikelyCrossedArmParts(root));
        if (model instanceof IllagerModelAccess access) {
            crossed.add(access.bmc$getCrossedArms());
        }
        for (ModelPart part : crossed) {
            setVisibleTree(part, visible, saved);
        }
    }


    private static List<ModelPart> findLikelyCrossedArmParts(ModelPart root) {
        if (root == null) {
            return List.of();
        }

        Set<ModelPart> matches = Collections.newSetFromMap(new IdentityHashMap<>());
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
                for (Map.Entry<?, ?> child : children.entrySet()) {
                    if (!(child.getKey() instanceof String name)
                            || !(child.getValue() instanceof ModelPart part)) {
                        continue;
                    }
                    String normalized = normalizePartName(name);
                    while (normalized.startsWith("emf") && normalized.length() > 3) {
                        normalized = normalized.substring(3);
                    }
                    boolean sided = normalized.contains("left") || normalized.contains("right");
                    boolean groupedArms = normalized.equals("arms")
                            || normalized.startsWith("armsrotation")
                            || normalized.startsWith("crossedarm")
                            || normalized.startsWith("crossedhand")
                            || normalized.startsWith("foldedarm")
                            || normalized.startsWith("foldedhand")
                            || normalized.startsWith("joinedarm")
                            || normalized.startsWith("joinedhand")
                            || normalized.startsWith("armscrossed")
                            || normalized.startsWith("armsfolded");
                    if (!sided && groupedArms) {
                        matches.add(part);
                    }
                    queue.addLast(part);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {

        }
        return List.copyOf(matches);
    }

    private static void setVisibleTree(ModelPart part, boolean visible, List<PartState> saved) {
        if (part == null) {
            return;
        }
        part.getAllParts().forEach(child -> setVisible(child, visible, saved));
    }

    /** Captures Fresh Animations' non-arm pose before a Vindicator attack takes ownership. */
    private static void captureNonArmState(
            LivingEntity entity,
            EntityModel<?> model
    ) {
        if (emfModelInterface == null || !emfModelInterface.isInstance(model)) {
            return;
        }
        try {
            if (!Boolean.TRUE.equals(isModelAnimated.invoke(null, model))) {
                return;
            }
            Object rootObject = getEmfRootModel.invoke(model);
            if (!(rootObject instanceof ModelPart root)) {
                return;
            }

            boolean waterborneSnapshot = entity instanceof Vindicator && isWaterborne(entity);
            Map<LivingEntity, NonArmSnapshot> target =
                    waterborneSnapshot ? WATERBORNE_STATE : NON_ARM_STATE;

            if (entity instanceof Vindicator) {
                Boolean previousWaterborne = LAST_WATERBORNE_STATE.put(entity, waterborneSnapshot);
                if (previousWaterborne == null || previousWaterborne != waterborneSnapshot) {
                    // captureNonArmState runs at renderer HEAD, before setupAnim. On the first frame
                    // after crossing the shoreline the model still contains the previous
                    // environment's pose, so do not label that stale land pose as a water pose (or
                    // vice versa). The next frame is safe to capture.
                    return;
                }
            }

            NonArmSnapshot previous = target.get(entity);
            boolean attackActive = entity instanceof MobAnimationAccess access
                    && access.bmc$isAttackAnimationActive();
            if (attackActive && previous != null && previous.root() == root) {
                return;
            }

            Set<ModelPart> excluded = Collections.newSetFromMap(new IdentityHashMap<>());
            addAliasedPartTrees(root, LEFT_ARM_NAMES, excluded);
            addAliasedPartTrees(root, RIGHT_ARM_NAMES, excluded);
            addAliasedPartTrees(root, CROSSED_ARM_NAMES, excluded);
            if (model instanceof IllagerModelAccess illager) {
                addPartTree(illager.bmc$getLeftArm(), excluded);
                addPartTree(illager.bmc$getRightArm(), excluded);
                addPartTree(illager.bmc$getCrossedArms(), excluded);
            }

            IdentityHashMap<ModelPart, VisibilityState> visibilityStates = new IdentityHashMap<>();
            root.getAllParts().forEach(part -> {
                if (!excluded.contains(part)) {
                    visibilityStates.put(part, VisibilityState.capture(part));
                }
            });

            Set<ModelPart> legParts = Collections.newSetFromMap(new IdentityHashMap<>());
            addAliasedPartTrees(root, LEFT_LEG_NAMES, legParts);
            addAliasedPartTrees(root, RIGHT_LEG_NAMES, legParts);
            List<PartState> legStates = new ArrayList<>(legParts.size());
            for (ModelPart part : legParts) {
                legStates.add(PartState.capture(part));
            }

            List<PartState> nonArmStates = new ArrayList<>();
            root.getAllParts().forEach(part -> {
                if (!excluded.contains(part)) {
                    nonArmStates.add(PartState.capture(part));
                }
            });

            boolean captureHiddenArmVariants = entity.isBaby();
            List<ArmBranchState> leftArmBranches =
                    captureArmBranches(root, captureHiddenArmVariants, LEFT_ARM_NAMES);
            List<ArmBranchState> rightArmBranches =
                    captureArmBranches(root, captureHiddenArmVariants, RIGHT_ARM_NAMES);
            debugArmBranchesOnce(entity, root, leftArmBranches, rightArmBranches);

            ModelPart leftLegRoot = findAliasedPart(root, LEFT_LEG_NAMES);
            ModelPart rightLegRoot = findAliasedPart(root, RIGHT_LEG_NAMES);
            float partialTick = entity instanceof MobAnimationAccess access
                    ? access.bmc$getRenderPartialTick()
                    : 1.0F;
            float walkPosition = entity.walkAnimation.position(partialTick);
            float walkSpeed = Mth.clamp(entity.walkAnimation.speed(partialTick), 0.0F, 1.0F);

            target.put(
                    entity,
                    new NonArmSnapshot(
                            root,
                            visibilityStates,
                            List.copyOf(legStates),
                            List.copyOf(nonArmStates),
                            List.copyOf(leftArmBranches),
                            List.copyOf(rightArmBranches),
                            leftLegRoot,
                            rightLegRoot,
                            walkPosition,
                            walkSpeed,
                            entity.tickCount + partialTick
                    )
            );
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Failed to preserve Fresh Animations non-arm state", exception);
        }
    }

    private static void restoreVindicatorNonArmState(LivingEntity entity, ModelPart root) {
        NonArmSnapshot snapshot = getSnapshot(entity, root, false);
        if (snapshot == null) {
            return;
        }

        for (PartState state : snapshot.legStates()) {
            state.restore();
        }
        applyLegContinuation(entity, snapshot);
        snapshot.visibilityStates().forEach((part, state) -> state.restore(part));
    }

    private static NonArmSnapshot restoreWaterborneVindicatorNonArmState(
            LivingEntity entity,
            ModelPart root
    ) {
        NonArmSnapshot snapshot = getSnapshot(entity, root, true);
        if (snapshot == null) {
            return null;
        }

        for (PartState state : snapshot.nonArmStates()) {
            state.restore();
        }
        return snapshot;
    }

    private static NonArmSnapshot getSnapshot(
            LivingEntity entity,
            ModelPart root,
            boolean waterborne
    ) {
        Map<LivingEntity, NonArmSnapshot> states =
                waterborne ? WATERBORNE_STATE : NON_ARM_STATE;
        NonArmSnapshot snapshot = states.get(entity);
        if (snapshot != null && snapshot.root() != root) {
            states.remove(entity);
            snapshot = null;
        }
        return snapshot;
    }

    private static void applyLegContinuation(
            LivingEntity entity,
            NonArmSnapshot snapshot
    ) {
        ModelPart leftLeg = snapshot.leftLegRoot();
        ModelPart rightLeg = snapshot.rightLegRoot();
        if (leftLeg == null && rightLeg == null) {
            return;
        }

        float partialTick = entity instanceof MobAnimationAccess access
                ? access.bmc$getRenderPartialTick()
                : 1.0F;
        float walkPosition = entity.walkAnimation.position(partialTick);
        float walkSpeed = Mth.clamp(entity.walkAnimation.speed(partialTick), 0.0F, 1.0F);

        float capturedRight = Mth.cos(snapshot.walkPosition() * 0.6662F)
                * 0.55F * snapshot.walkSpeed();
        float capturedLeft = Mth.cos(snapshot.walkPosition() * 0.6662F + Mth.PI)
                * 0.55F * snapshot.walkSpeed();
        float currentRight = Mth.cos(walkPosition * 0.6662F) * 0.55F * walkSpeed;
        float currentLeft = Mth.cos(walkPosition * 0.6662F + Mth.PI) * 0.55F * walkSpeed;


        float currentAge = entity.tickCount + partialTick;
        float idleDelta = (Mth.sin(currentAge * 0.12F)
                - Mth.sin(snapshot.capturedAge() * 0.12F)) * 0.012F;

        if (rightLeg != null) {
            rightLeg.xRot += currentRight - capturedRight + idleDelta;
        }
        if (leftLeg != null) {
            leftLeg.xRot += currentLeft - capturedLeft - idleDelta;
        }
    }

    private static void addAliasedPartTrees(
            ModelPart root,
            String[] aliases,
            Set<ModelPart> output
    ) {
        for (ModelPart part : findAllAliasedParts(root, aliases)) {
            addPartTree(part, output);
        }
    }

    private static ModelPart findIllagerCrossedArms(ModelPart root) {
        if (root == null) {
            return null;
        }
        try {
            if (root.hasChild("arms")) {
                return root.getChild("arms");
            }
        } catch (RuntimeException ignored) {
        }
        return findAliasedPart(root, CROSSED_ARM_NAMES);
    }

    /** Finds every exact normalized alias rather than stopping at the first rebuilt EMF branch. */
    private static List<ModelPart> findAllAliasedParts(ModelPart root, String... aliases) {
        if (root == null) {
            return List.of();
        }

        Set<String> normalizedAliases = new HashSet<>();
        for (String alias : aliases) {
            normalizedAliases.add(normalizePartName(alias));
        }

        Set<ModelPart> matches = Collections.newSetFromMap(new IdentityHashMap<>());
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
                for (Map.Entry<?, ?> child : children.entrySet()) {
                    if (!(child.getKey() instanceof String name)
                            || !(child.getValue() instanceof ModelPart part)) {
                        continue;
                    }
                    if (matchesPartAlias(normalizePartName(name), normalizedAliases)) {
                        matches.add(part);
                    }
                    queue.addLast(part);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return List.copyOf(matches);
        }
        return List.copyOf(matches);
    }

    private static ModelPart findEmfVanillaPart(ModelPart root, String... aliases) {
        if (root == null) {
            return null;
        }

        Set<String> normalizedAliases = new HashSet<>();
        for (String alias : aliases) {
            normalizedAliases.add(normalizePartName(alias));
        }

        Method allVanillaParts = getAllVanillaPartsMethod(root.getClass());
        if (allVanillaParts != null) {
            try {
                Object rawParts = allVanillaParts.invoke(root);
                if (rawParts instanceof Iterable<?> parts) {
                    for (Object candidate : parts) {
                        if (!(candidate instanceof ModelPart part)) {
                            continue;
                        }
                        String name = readEmfVanillaPartName(candidate);
                        if (name != null && normalizedAliases.contains(normalizePartName(name))) {
                            return part;
                        }
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Fall through to the generic tree lookup for older EMF versions.
            }
        }
        return findAliasedPart(root, aliases);
    }

    private static Method getAllVanillaPartsMethod(Class<?> type) {
        Method cached = EMF_VANILLA_PART_METHODS.get(type);
        if (cached != null) {
            return cached;
        }
        if (EMF_VANILLA_PART_METHOD_MISSES.contains(type)) {
            return null;
        }
        try {
            Method method = type.getMethod("getAllVanillaPartsEMF");
            method.setAccessible(true);
            EMF_VANILLA_PART_METHODS.put(type, method);
            return method;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            EMF_VANILLA_PART_METHOD_MISSES.add(type);
            return null;
        }
    }

    private static String readEmfVanillaPartName(Object part) {
        Class<?> type = part.getClass();
        Field cached = EMF_VANILLA_NAME_FIELDS.get(type);
        if (cached == null && !EMF_VANILLA_NAME_FIELD_MISSES.contains(type)) {
            Class<?> cursor = type;
            while (cursor != null) {
                try {
                    cached = cursor.getDeclaredField("name");
                    cached.setAccessible(true);
                    EMF_VANILLA_NAME_FIELDS.put(type, cached);
                    break;
                } catch (NoSuchFieldException ignored) {
                    cursor = cursor.getSuperclass();
                } catch (RuntimeException exception) {
                    break;
                }
            }
            if (cached == null) {
                EMF_VANILLA_NAME_FIELD_MISSES.add(type);
            }
        }
        if (cached == null) {
            return null;
        }
        try {
            Object value = cached.get(part);
            return value instanceof String name ? name : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static void debugLiveArmOverlayOnce(
            LivingEntity entity,
            ModelPart root,
            ModelPart leftArm,
            ModelPart rightArm,
            String mode
    ) {
        if (!BMCConfig.DEBUG_LOGGING.get()) {
            return;
        }
        String key = "late-overlay|" + mode + "|" + entity.getType();
        if (!BMC$EMF_ARM_DIAGNOSTICS.add(key)) {
            return;
        }
        BetterMobCombatReimagined.LOGGER.warn(
                "[BMC-EMF-LATE-ARMS] mode={} entity={} root={} left={} right={}",
                mode,
                entity.getType(),
                root.getClass().getName(),
                leftArm == null ? "missing" : leftArm.getClass().getName(),
                rightArm == null ? "missing" : rightArm.getClass().getName()
        );
    }

    private static ModelPart findAliasedPart(ModelPart root, String... aliases) {
        if (root == null) {
            return null;
        }

        // Fast path for vanilla/standard CEM names.
        for (String alias : aliases) {
            try {
                if (root.hasChild(alias)) {
                    return root.getChild(alias);
                }
            } catch (RuntimeException ignored) {
                // Continue with the recursive normalized lookup.
            }
        }

        Set<String> normalizedAliases = new HashSet<>();
        for (String alias : aliases) {
            normalizedAliases.add(normalizePartName(alias));
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
                for (Map.Entry<?, ?> child : children.entrySet()) {
                    if (!(child.getKey() instanceof String name)
                            || !(child.getValue() instanceof ModelPart part)) {
                        continue;
                    }
                    if (matchesPartAlias(normalizePartName(name), normalizedAliases)) {
                        return part;
                    }
                    queue.addLast(part);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static boolean matchesPartAlias(String normalizedName, Set<String> normalizedAliases) {
        String candidate = normalizedName;
        while (candidate.startsWith("emf") && candidate.length() > 3) {
            candidate = candidate.substring(3);
        }

        for (String alias : normalizedAliases) {
            if (candidate.equals(alias)
                    || candidate.startsWith(alias)
                    || candidate.endsWith(alias)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizePartName(String value) {
        return value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static ModelPart findPart(ModelPart root, String path) {
        ModelPart current = root;
        for (String child : path.split("#")) {
            try {
                current = current.getChild(child);
            } catch (RuntimeException exception) {
                return null;
            }
        }
        return current;
    }

    private static void restoreSavedParts(List<PartState> states) {
        if (states == null) {
            return;
        }
        for (PartState state : states) {
            state.restore();
        }
    }

    private static void collectControlledParts(
            EntityModel<?> model,
            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated,
            Set<ModelPart> parts
    ) {
        if (model instanceof PlayerModel<?> player) {
            addHumanoidParts(player, animated, parts);
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.TORSO)) parts.add(player.jacket);
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM)) parts.add(player.leftSleeve);
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM)) parts.add(player.rightSleeve);
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG)) parts.add(player.leftPants);
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG)) parts.add(player.rightPants);
            return;
        }

        if (model instanceof HumanoidModel<?> humanoid) {
            addHumanoidParts(humanoid, animated, parts);
            return;
        }

        if (model instanceof IllagerModel<?> && model instanceof IllagerModelAccess illager) {
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.HEAD)) {
                parts.add(illager.bmc$getHead());
                parts.add(illager.bmc$getHat());
            }
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.TORSO)) {
                parts.add(illager.bmc$getBody());
            }
            boolean left = animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
            boolean right = animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
            if (left) parts.add(illager.bmc$getLeftArm());
            if (right) parts.add(illager.bmc$getRightArm());
            if (left || right) parts.add(illager.bmc$getCrossedArms());
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG)) parts.add(illager.bmc$getLeftLeg());
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG)) parts.add(illager.bmc$getRightLeg());
        }
    }

    private static void addHumanoidParts(
            HumanoidModel<?> model,
            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated,
            Set<ModelPart> parts
    ) {
        if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.HEAD)) {
            parts.add(model.head);
            parts.add(model.hat);
        }
        if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.TORSO)) parts.add(model.body);
        if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM)) parts.add(model.leftArm);
        if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM)) parts.add(model.rightArm);
        if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG)) parts.add(model.leftLeg);
        if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG)) parts.add(model.rightLeg);
    }

    private static boolean initialize() {
        if (initialized) {
            return available;
        }
        initialized = true;
        try {
            Class<?> api = Class.forName(EMF_API);
            emfModelInterface = Class.forName(EMF_MODEL_INTERFACE);
            isModelAnimated = findStatic(api, "isModelAnimatedByEMF", 1);
            emfEntityOf = findStatic(api, "emfEntityOf", Entity.class);
            pauseAnimations = findStatic(api, "pauseCustomAnimationsForThesePartsOfEntity", 2);
            resumeAnimations = findStatic(api, "resumeAllCustomAnimationsForEntity", 1);
            getEmfRootModel = emfModelInterface.getMethod("emf$getEMFRootModel");
            available = true;
            registerPauseCondition(api);
        } catch (ClassNotFoundException ignored) {
            available = false;
        } catch (ReflectiveOperationException exception) {
            available = false;
            warnOnce("Entity Model Features was found, but its animation API was not compatible", exception);
        }
        return available;
    }

    private static void registerPauseCondition(Class<?> api) {
        Method register = null;
        for (Method method : api.getMethods()) {
            if (method.getName().equals("registerPauseCondition")
                    && method.getParameterCount() == 1
                    && Modifier.isStatic(method.getModifiers())) {
                register = method;
                break;
            }
        }
        if (register == null) {
            debugOnce("EMF has no registerPauseCondition(Function) - this EMF version predates the "
                    + "animation-mod pause API, falling back to per-part pausing (which EMF may "
                    + "override during its own render pass)");
            return;
        }

        try {
            java.util.function.Function<Object, Boolean> condition = emfEntity -> {
                try {
                    Entity entity = entityFromEmfEntity(emfEntity);
                    if (!(entity instanceof LivingEntity living)) {
                        return Boolean.FALSE;
                    }

                    return shouldPauseWholeEmfModel(living);
                } catch (RuntimeException exception) {
                    return Boolean.FALSE;
                }
            };
            register.invoke(null, condition);
            pauseConditionRegistered = true;
            BetterMobCombatReimagined.LOGGER.info(
                    "[BMC-EMF] Registered pause condition with EMF - Fresh Animations will yield the "
                            + "visible model tree during synchronized Better Combat mob attacks."
            );
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Failed to register a pause condition with Entity Model Features", exception);
        }
    }

    private static boolean usesLiveLateArmOverlay(LivingEntity entity) {
        return entity.isBaby() || entity instanceof Vindicator;
    }

    private static boolean shouldPauseWholeEmfModel(LivingEntity entity) {
        if (entity.isBaby()
                || !HUMANOID_RENDER_PASS.contains(entity)
                || !(entity instanceof MobAnimationAccess access)
                || !access.bmc$isAttackAnimationActive()) {
            return false;
        }

        // Babies and all Vindicators stay fully live in EMF. Pausing Fresh Animations for a land
        // Vindicator caused a visible pause/resume boundary every attack: head hop, unstable legs,
        // and a slow/stuttered swing. The optional EMF model-part mixin now overlays only BMC's
        // combat rotations after FA has animated the current frame.
        if (entity instanceof Vindicator) {
            return false;
        }
        return !(entity instanceof AbstractIllager);
    }

    /** Resolves the vanilla Entity behind EMF's EMFEntity wrapper, whatever it names its accessor. */
    private static Entity entityFromEmfEntity(Object emfEntity) {
        if (emfEntity instanceof Entity direct) {
            return direct;
        }
        if (emfEntityAccessor == null) {
            for (Method method : emfEntity.getClass().getMethods()) {
                if (method.getParameterCount() == 0
                        && Entity.class.isAssignableFrom(method.getReturnType())) {
                    method.setAccessible(true);
                    emfEntityAccessor = method;
                    debugOnce("Resolved EMFEntity -> Entity accessor: " + method.getName());
                    break;
                }
            }
        }
        if (emfEntityAccessor == null) {
            debugOnce("Could not resolve an Entity accessor on EMFEntity ("
                    + emfEntity.getClass().getName() + ")");
            return null;
        }
        try {
            return (Entity) emfEntityAccessor.invoke(emfEntity);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private static Method findStatic(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameterTypes);
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException(owner.getName() + "#" + name + " is not static");
        }
        return method;
    }

    private static Method findStatic(Class<?> owner, String name, int parameterCount)
            throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == parameterCount
                    && Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "#" + name + "/" + parameterCount);
    }

    private static void warnOnce(String message, Throwable throwable) {
        if (!warned) {
            warned = true;
            BetterMobCombatReimagined.LOGGER.warn(message, throwable);
        }
    }

    private static final class EmfRenderContext {
        private final LivingEntity entity;
        private final EntityModel<?> model;
        private final EmfRenderContext previous;
        private boolean applied;

        private EmfRenderContext(
                LivingEntity entity,
                EntityModel<?> model,
                EmfRenderContext previous
        ) {
            this.entity = entity;
            this.model = model;
            this.previous = previous;
        }
    }

    private record NonArmSnapshot(
            ModelPart root,
            IdentityHashMap<ModelPart, VisibilityState> visibilityStates,
            List<PartState> legStates,
            List<PartState> nonArmStates,
            List<ArmBranchState> leftArmBranches,
            List<ArmBranchState> rightArmBranches,
            ModelPart leftLegRoot,
            ModelPart rightLegRoot,
            float walkPosition,
            float walkSpeed,
            float capturedAge
    ) {
    }

    private record PartPathNode(ModelPart part, String path) {
    }

    private record ArmSearchNode(
            ModelPart part,
            boolean effectivelyVisible,
            boolean ancestorAlias
    ) {
    }

    private record ArmBranchState(
            ModelPart root,
            List<PartState> states
    ) {
        void restore() {
            for (PartState state : states) {
                state.restore();
            }
        }
    }

    private record VisibilityState(boolean visible, boolean skipDraw) {
        static VisibilityState capture(ModelPart part) {
            return new VisibilityState(part.visible, part.skipDraw);
        }

        void restore(ModelPart part) {
            part.visible = visible;
            part.skipDraw = skipDraw;
        }
    }

    private record PartState(
            ModelPart part,
            float x,
            float y,
            float z,
            float xRot,
            float yRot,
            float zRot,
            float xScale,
            float yScale,
            float zScale,
            boolean visible,
            boolean skipDraw
    ) {
        static PartState capture(ModelPart part) {
            return new PartState(
                    part,
                    part.x, part.y, part.z,
                    part.xRot, part.yRot, part.zRot,
                    part.xScale, part.yScale, part.zScale,
                    part.visible, part.skipDraw
            );
        }

        void restore() {
            part.x = x;
            part.y = y;
            part.z = z;
            part.xRot = xRot;
            part.yRot = yRot;
            part.zRot = zRot;
            part.xScale = xScale;
            part.yScale = yScale;
            part.zScale = zScale;
            part.visible = visible;
            part.skipDraw = skipDraw;
        }
    }
}