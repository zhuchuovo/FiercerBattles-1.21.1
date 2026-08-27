package com.fiercerbattles;

import com.fiercerbattles.config.FiercerBattlesConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

public final class FiercerBattlesRuntime {
    private FiercerBattlesRuntime() {}

    private static String currentAnimation = "";
    private static FiercerBattlesConfig.Displacement displacement = null;
    private static int displacementStartTick = 0;
    private static boolean displacementActive = false;
    private static int recoveryUntil = 0;
    private static int currentAttackCooldownTicks = 20;
    private static int animationStartTick = 0;
    private static float animationPlaybackLengthTicks = 20.0F;
    private static int animationResourceStopFrame = -1;
    private static boolean forceOriginalPlayback = false;
    private static int damageResolvedTick = -1;
    private static boolean currentCombatSkill = false;
    private static String preparedAttackKey = "";
    private static double currentRangeBonus = 0;
    private static double currentAfterCooldown = 0;
    private static float currentAttackSpeedMultiplier = 1.0F;
    private static FiercerBattlesCustomData.AttackData currentCustomData = null;
    private static FiercerBattlesCustomData.MultipleHits currentMultipleHits = null;
    private static int multipleHitsStartTick = 0;
    private static int nextMultipleHitIndex = 0;
    private static int executingMultipleHitIndex = -1;
    private static boolean immediateRecoveryCancellationRequested;
    private static final Map<String, Integer> ANIMATION_STOP_FRAMES = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> FORCE_ORIGINAL_PLAYBACK = new ConcurrentHashMap<>();
    private static final int UNKNOWN_ANIMATION = Integer.MIN_VALUE;

    /** Clears attack state when Minecraft replaces the client player on respawn. */
    public static void resetClientState() {
        currentAnimation = "";
        displacement = null;
        displacementStartTick = 0;
        displacementActive = false;
        recoveryUntil = 0;
        currentAttackCooldownTicks = 20;
        animationStartTick = 0;
        animationPlaybackLengthTicks = 20.0F;
        animationResourceStopFrame = -1;
        forceOriginalPlayback = false;
        damageResolvedTick = -1;
        currentCombatSkill = false;
        preparedAttackKey = "";
        currentRangeBonus = 0;
        currentAfterCooldown = 0;
        currentAttackSpeedMultiplier = 1.0F;
        currentCustomData = null;
        currentMultipleHits = null;
        multipleHitsStartTick = 0;
        nextMultipleHitIndex = 0;
        executingMultipleHitIndex = -1;
        immediateRecoveryCancellationRequested = false;
    }

    public static void prepareAttack(String animation, String itemId, int attackIndex) {
        currentAnimation = animation != null ? animation : "";
        String key = String.valueOf(itemId) + '#' + attackIndex + '#' + currentAnimation;
        if (!key.equals(preparedAttackKey) || currentCombatSkill) {
            damageResolvedTick = -1;
        }
        preparedAttackKey = key;
        currentCombatSkill = false;
        tryLoadCustomDataFromClient();

        currentCustomData = FiercerBattlesCustomData.get(itemId, attackIndex);
        float attackSpeed = 0;
        if (currentCustomData != null && currentCustomData.attackSpeedMultiplier != 0) {
            attackSpeed = currentCustomData.attackSpeedMultiplier;
        } else {
            Float configSpeed = FiercerBattlesConfig.getAttackSpeedMultiplier(currentAnimation);
            if (configSpeed != null) {
                attackSpeed = configSpeed;
            }
        }
        currentAttackSpeedMultiplier = attackSpeed == 0 ? 1.0F : attackSpeed;
    }

    public static void prepareCombatSkill(String animation, FiercerBattlesCustomData.AttackData customData) {
        currentAnimation = animation != null ? animation : "";
        currentCombatSkill = true;
        damageResolvedTick = -1;
        preparedAttackKey = "";
        currentCustomData = customData;
        float attackSpeed = 0;
        if (customData != null && customData.attackSpeedMultiplier != 0) {
            attackSpeed = customData.attackSpeedMultiplier;
        } else {
            Float configSpeed = FiercerBattlesConfig.getAttackSpeedMultiplier(currentAnimation);
            if (configSpeed != null) {
                attackSpeed = configSpeed;
            }
        }
        currentAttackSpeedMultiplier = attackSpeed == 0 ? 1.0F : attackSpeed;
    }
    public static void setCurrentAnimation(String animation, int startTick) {
        setCurrentAnimation(animation, startTick, 20);
    }

    public static void setCurrentAnimation(String animation, int startTick, float attackCooldownTicks) {
        setCurrentAnimation(animation, startTick, attackCooldownTicks, attackCooldownTicks);
    }

    /**
     * Captures the Better Combat animation's real playback duration. The
     * resource itself is authored in PlayerAnimator frames (stopTick); the
     * duration argument is the number of game ticks Better Combat stretches it
     * over. This lets the recovery gate use resource frames instead of a
     * hard-coded game-tick window.
     */
    public static void setCurrentAnimation(
            String animation,
            int startTick,
            float attackCooldownTicks,
            float animationLengthTicks
    ) {
        currentAnimation = animation != null ? animation : "";
        damageResolvedTick = -1;
        currentAttackCooldownTicks = Math.max(1, Math.round(attackCooldownTicks));
        animationStartTick = startTick;
        animationPlaybackLengthTicks = Math.max(1.0F, animationLengthTicks);
        animationResourceStopFrame = animationStopFrame(currentAnimation);
        forceOriginalPlayback = forceOriginalPlayback(currentAnimation);

        FiercerBattlesCustomData.AttackData custom = currentCustomData;
        if (custom != null && custom.hasDisplacement) {
            displacement = new FiercerBattlesConfig.Displacement(
                    custom.distance,
                    custom.durationTicks
            );
        } else {
            displacement = FiercerBattlesConfig.getDisplacement(currentAnimation);
        }
        currentMultipleHits = custom != null && custom.multipleHits != null
                ? custom.multipleHits
                : FiercerBattlesConfig.getMultipleHits(currentAnimation);
        multipleHitsStartTick = startTick;
        nextMultipleHitIndex = 0;        displacementStartTick = startTick;
        displacementActive = displacement != null;

        currentRangeBonus = custom != null ? custom.rangeBonus : 0;
        if (currentRangeBonus == 0) {
            Double configRangeBonus = FiercerBattlesConfig.getRangeBonus(currentAnimation);
            if (configRangeBonus != null) {
                currentRangeBonus = configRangeBonus;
            }
        }

        currentAfterCooldown = custom != null ? custom.afterCooldown : 0;
        if (currentAfterCooldown == 0) {
            Double configAfterCooldown = FiercerBattlesConfig.getAfterCooldown(currentAnimation);
            if (configAfterCooldown != null) {
                currentAfterCooldown = configAfterCooldown;
            }
        }

        // Better Combat normally uses the attack cooldown as the base recovery;
        // after_cooldown adds an explicit extra tail on top of it.
        recoveryUntil = startTick + currentAttackCooldownTicks
                + (int) Math.ceil(Math.max(0, currentAfterCooldown) * 20.0);
    }

    /** Marks the point at which Better Combat has completed the attack's damage check. */
    public static void markDamageResolved(int tick) {
        if (!currentCombatSkill) {
            damageResolvedTick = tick;
        }
    }

    /**
     * Returns whether the selected skill may cancel the current normal
     * animation's recovery. The recovery window belongs to the normal attack
     * data captured by prepareAttack(); the skill data is only a compatibility
     * fallback for older files that placed the field on the skill segment.
     */
    public static boolean canInterruptRecovery(
            int currentTick,
            FiercerBattlesCustomData.AttackData skillData
    ) {
        FiercerBattlesCustomData.AttackData windowData = recoveryWindowData(skillData);
        if (currentCombatSkill || damageResolvedTick < 0 || windowData == null
                || recoveryUntil <= 0
                || currentTick < damageResolvedTick) {
            return false;
        }
        if (windowData.hasRecoveryCancelFrames) {
            int remainingFrames = getAnimationRemainingFrames(currentTick);
            return remainingFrames >= windowData.recoveryCancelMinFrames
                    && remainingFrames <= windowData.recoveryCancelMaxFrames;
        }
        if (windowData.recoveryCancelTicks <= 0) {
            return false;
        }
        int remaining = Math.max(0, recoveryUntil - currentTick);
        return remaining <= windowData.recoveryCancelTicks;
    }

    public static void consumeRecoveryCancellation(int currentTick, FiercerBattlesCustomData.AttackData skillData) {
        if (!canInterruptRecovery(currentTick, skillData)) {
            return;
        }
        FiercerBattlesCustomData.AttackData windowData = recoveryWindowData(skillData);
        if (windowData != null && !windowData.hasRecoveryCancelFrames) {
            recoveryUntil = Math.max(currentTick, recoveryUntil - windowData.recoveryCancelTicks);
        }
        damageResolvedTick = -1;
    }

    public static void beginImmediateRecoveryCancellation() {
        immediateRecoveryCancellationRequested = true;
    }

    public static void endImmediateRecoveryCancellation() {
        immediateRecoveryCancellationRequested = false;
    }

    public static boolean isImmediateRecoveryCancellationRequested() {
        return immediateRecoveryCancellationRequested;
    }

    private static FiercerBattlesCustomData.AttackData recoveryWindowData(
            FiercerBattlesCustomData.AttackData skillData
    ) {
        if (currentCustomData != null
                && (currentCustomData.hasRecoveryCancelFrames || currentCustomData.recoveryCancelTicks > 0)) {
            return currentCustomData;
        }
        FiercerBattlesCustomData.AttackData animationData =
                FiercerBattlesCustomData.getRecoveryWindowForAnimation(currentAnimation);
        if (animationData != null) {
            return animationData;
        }
        return skillData != null
                && (skillData.hasRecoveryCancelFrames || skillData.recoveryCancelTicks > 0)
                ? skillData
                : null;
    }

    /** Returns the inclusive number of PlayerAnimator frames left in the resource. */
    public static int getAnimationRemainingFrames(int currentTick) {
        Integer renderedFrames = getRenderedAnimationRemainingFrames();
        if (renderedFrames != null) {
            return renderedFrames;
        }
        int totalFrames = animationResourceStopFrame > 0
                ? animationResourceStopFrame
                : Math.max(1, Math.round(animationPlaybackLengthTicks));
        int elapsedTicks = Math.max(0, currentTick - animationStartTick);
        int currentFrame = Math.min(
                totalFrames,
                Math.max(0, Math.round(elapsedTicks * totalFrames / animationPlaybackLengthTicks))
        );
        return Math.max(0, totalFrames - currentFrame);
    }

    /**
     * Reads Better Combat's live PlayerAnimator player when available. This is
     * important because TransmissionSpeedModifier changes the playback rate
     * around upswing; a linear tick-to-frame estimate can otherwise miss a
     * configured range such as [15,30].
     */
    private static Integer getRenderedAnimationRemainingFrames() {
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) {
                return null;
            }
            Object attackAnimation = findFieldValue(player, "attackAnimation");
            Object base = attackAnimation != null ? findFieldValue(attackAnimation, "base") : null;
            Object animation = base != null ? invokeNoArg(base, "getAnimation") : null;
            if (animation == null) {
                return null;
            }
            for (int depth = 0; depth < 5 && animation != null; depth++) {
                Integer tick = invokeInt(animation, "getCurrentTick");
                if (tick == null) {
                    tick = invokeInt(animation, "getTick");
                }
                Integer stop = invokeInt(animation, "getStopTick");
                if (stop == null) {
                    Object data = invokeNoArg(animation, "getData");
                    stop = data != null ? readIntField(data, "stopTick") : null;
                }
                if (tick != null && stop != null && stop > 0) {
                    return Math.max(0, stop - tick);
                }
                animation = invokeNoArg(animation, "getAnim");
            }
        } catch (RuntimeException ignored) {
            // Fall back to the deterministic resource-length estimate below.
        }
        return null;
    }

    private static Object findFieldValue(Object target, String name) {
        Field field = findField(target.getClass(), name);
        if (field == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Integer invokeInt(Object target, String name) {
        Object value = invokeNoArg(target, name);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Integer readIntField(Object target, String name) {
        Object value = findFieldValue(target, name);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Object invokeNoArg(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static int animationStopFrame(String animation) {
        if (animation == null || animation.isBlank()) {
            return -1;
        }
        Integer cached = ANIMATION_STOP_FRAMES.get(animation);
        if (cached != null) {
            return cached == UNKNOWN_ANIMATION ? -1 : cached;
        }
        int stopFrame = -1;
        try {
            ResourceLocation animationId = ResourceLocation.parse(animation);
            ResourceLocation resourceId = ResourceLocation.fromNamespaceAndPath(
                    animationId.getNamespace(),
                    "player_animations/" + animationId.getPath() + ".json"
            );
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(resourceId);
            if (resource.isPresent()) {
                try (InputStream input = resource.get().open();
                     InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    JsonObject emote = root.has("emote") && root.get("emote").isJsonObject()
                            ? root.getAsJsonObject("emote")
                            : root;
                    JsonElement stop = emote.get("stopTick");
                    if (stop != null && stop.isJsonPrimitive()) {
                        stopFrame = Math.max(1, stop.getAsInt());
                    } else {
                        JsonElement end = emote.get("endTick");
                        if (end != null && end.isJsonPrimitive()) {
                            stopFrame = Math.max(1, end.getAsInt());
                        }
                    }
                }
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            stopFrame = -1;
        }
        ANIMATION_STOP_FRAMES.put(animation, stopFrame > 0 ? stopFrame : UNKNOWN_ANIMATION);
        return stopFrame;
    }

    public static void cancelMultipleHits() {
        currentMultipleHits = null;
        nextMultipleHitIndex = 0;
        executingMultipleHitIndex = -1;
    }
    public static boolean shouldSuppressDefaultAttack() {
        return currentMultipleHits != null && !isExecutingMultipleHit();
    }

    public static boolean isExecutingMultipleHit() {
        return executingMultipleHitIndex >= 0;
    }

    public static int getExecutingMultipleHitIndex() {
        return executingMultipleHitIndex;
    }

    public static boolean isExecutingFinalMultipleHit() {
        return currentMultipleHits != null && executingMultipleHitIndex == currentMultipleHits.frames.length - 1;
    }

    public static void tickMultipleHits(LocalPlayer player) {
        if (currentMultipleHits == null || nextMultipleHitIndex >= currentMultipleHits.frames.length) {
            return;
        }
        int elapsed = player.tickCount - multipleHitsStartTick;
        while (nextMultipleHitIndex < currentMultipleHits.frames.length
                && elapsed >= currentMultipleHits.frames[nextMultipleHitIndex]) {
            executingMultipleHitIndex = nextMultipleHitIndex;
            try {
                invokePerformAttack();
            } finally {
                executingMultipleHitIndex = -1;
            }
            nextMultipleHitIndex += 1;
        }
    }

    private static void invokePerformAttack() {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            for (Method method : minecraft.getClass().getDeclaredMethods()) {
                if (method.getName().equals("performAttack") && method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    method.invoke(minecraft);
                    return;
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }
    public static double getCurrentRangeBonus() {
        return currentRangeBonus;
    }

    public static boolean isInRecovery(int currentTick) {
        // The ordinary Better Combat cooldown already controls normal attacks.
        // This extra gate is kept only for explicitly configured after_cooldown.
        return currentAfterCooldown > 0 && recoveryUntil > 0 && currentTick < recoveryUntil;
    }

    public static String getCurrentAnimation() {
        return currentAnimation;
    }

    /**
     * True when the current animation resource explicitly requests that
     * Better Combat's attack-time pose adjustments and speed gears be bypassed.
     */
    public static boolean isForceOriginalPlayback() {
        return forceOriginalPlayback;
    }

    /** Returns the animation-resource override without changing current attack state. */
    public static boolean shouldForceOriginalPlayback(String animation) {
        return forceOriginalPlayback(animation);
    }

    public static float getCurrentAttackSpeedMultiplier() {
        return currentAttackSpeedMultiplier == 0 ? 1.0F : currentAttackSpeedMultiplier;
    }

    public static float getAnimationSpeed() {
        // Attack speed and animation playback are linked by BetterCombat itself:
        // changing getAttackCooldownTicksCapped already changes the animation speed.
        return 1.0F;
    }

    private static boolean forceOriginalPlayback(String animation) {
        if (animation == null || animation.isBlank()) {
            return false;
        }
        Boolean cached = FORCE_ORIGINAL_PLAYBACK.get(animation);
        if (cached != null) {
            return cached;
        }

        boolean enabled = false;
        try {
            ResourceLocation animationId = ResourceLocation.parse(animation);
            ResourceLocation resourceId = ResourceLocation.fromNamespaceAndPath(
                    animationId.getNamespace(),
                    "player_animations/" + animationId.getPath() + ".json"
            );
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(resourceId);
            if (resource.isPresent()) {
                try (InputStream input = resource.get().open();
                     InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    JsonObject emote = root.has("emote") && root.get("emote").isJsonObject()
                            ? root.getAsJsonObject("emote")
                            : root;
                    Boolean value = booleanField(emote, "forceOriginalPlayback", "force_original_playback");
                    if (value == null && emote != root) {
                        value = booleanField(root, "forceOriginalPlayback", "force_original_playback");
                    }
                    enabled = Boolean.TRUE.equals(value);
                }
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            enabled = false;
        }
        FORCE_ORIGINAL_PLAYBACK.put(animation, enabled);
        return enabled;
    }

    private static Boolean booleanField(JsonObject object, String primary, String alias) {
        JsonElement value = object.get(primary);
        if (value == null) {
            value = object.get(alias);
        }
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsBoolean();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void tryLoadCustomDataFromClient() {
        if (FiercerBattlesCustomData.isEmpty()) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.getSingleplayerServer() != null) {
                FiercerBattlesCustomData.load(minecraft.getSingleplayerServer().getResourceManager());
            }
        }
    }

    public static void tickDisplacement(LocalPlayer player) {
        if (!displacementActive || displacement == null) {
            return;
        }
        int elapsed = player.tickCount - displacementStartTick;
        if (elapsed < 0) {
            return;
        }
        int durationTicks = (int) Math.max(1, Math.ceil(displacement.durationTicks));
        if (elapsed >= durationTicks) {
            displacementActive = false;
            return;
        }
        double step = displacement.distance / displacement.durationTicks;
        Vec3 look = player.getLookAngle();
        player.move(MoverType.SELF, new Vec3(look.x * step, 0, look.z * step));
    }
}
