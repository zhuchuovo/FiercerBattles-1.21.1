package com.fiercerbattles;

import com.fiercerbattles.config.FiercerBattlesConfig;
import com.mojang.logging.LogUtils;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

public final class FiercerBattlesRuntime {
    private FiercerBattlesRuntime() {}
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

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
    /**
     * The Better Combat swing object the armed multiple-hit frames belong to.
     * Frames are dispatched into {@code Minecraft.performAttack}, which
     * silently returns once {@code ongoingSwing} is gone, so the swing's
     * identity is what decides whether pending frames can still fire.
     */
    private static Object armedSwing = null;
    /**
     * Whether the armed multiple-hit frames belong to a combat-skill swing.
     * Recorded when the frames are armed (the skill/normal preparation has
     * already run by then), so it still identifies the OLD frames while a
     * newer swing is re-arming the state.
     */
    private static boolean multipleHitsSkill = false;
    private static boolean immediateRecoveryCancellationRequested;
    private static final Map<String, Integer> ANIMATION_STOP_FRAMES = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> FORCE_ORIGINAL_PLAYBACK = new ConcurrentHashMap<>();
    private static final Set<String> ITEM_CHANNEL_WARNINGS = ConcurrentHashMap.newKeySet();
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
        armedSwing = null;
        multipleHitsSkill = false;
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
        // Captured before the re-arm below overwrites it: true when a new
        // swing is replacing a skill swing whose delayed multiple-hit frames
        // have not all fired yet (their final packet - the chain-slot
        // consumer - can never be sent for the replaced swing).
        boolean replacedPendingSkillFrames = currentMultipleHits != null
                && multipleHitsSkill
                && nextMultipleHitIndex < currentMultipleHits.frames.length;
        currentMultipleHits = custom != null && custom.multipleHits != null
                ? custom.multipleHits
                : FiercerBattlesConfig.getMultipleHits(currentAnimation);
        multipleHitsStartTick = startTick;
        nextMultipleHitIndex = 0;
        // startUpswing assigns the swing object before it plays the attack
        // animation, so the swing these frames belong to is already live here.
        armedSwing = findOngoingSwing();
        multipleHitsSkill = currentCombatSkill;
        displacementStartTick = startTick;
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

        if (replacedPendingSkillFrames) {
            // The replaced skill swing's chain slot is normally consumed by
            // its final multiple-hit packet; that packet can never fire now.
            // Without this compensation every subsequent swing keeps being
            // redirected into the skill (until the chain window expires)
            // even though its damage was lost.
            FiercerBattlesCombatSkills.consumeReplacedSkillSwing();
        }
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
            if (windowData.recoveryCancelFramesPlayed) {
                // Played-frame semantics: the values are positions 0..stopTick
                // the animation has reached. A window like [50, 60] therefore
                // covers the late part of a long animation, right after its
                // damage frames, instead of being unreachable on short
                // animations (remaining frames can never exceed stopTick).
                int totalFrames = animationResourceStopFrame > 0
                        ? animationResourceStopFrame
                        : Math.max(1, Math.round(animationPlaybackLengthTicks));
                int playedFrames = Math.max(0, totalFrames - remainingFrames);
                return playedFrames >= windowData.recoveryCancelMinFrames
                        && playedFrames <= windowData.recoveryCancelMaxFrames;
            }
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
     * Whether the current attack animation has already PLAYED at least
     * {@code frame} resource frames (0..stopTick). Used by the
     * {@code interrupt_attack: N} option: the skill may only interrupt the
     * ongoing attack once the animation reached frame N.
     */
    public static boolean hasAttackPlayedAtLeast(int currentTick, int frame) {
        if (frame <= 0) {
            return true;
        }
        int remainingFrames = getAnimationRemainingFrames(currentTick);
        int totalFrames = animationResourceStopFrame > 0
                ? animationResourceStopFrame
                : Math.max(1, Math.round(animationPlaybackLengthTicks));
        int playedFrames = Math.max(0, totalFrames - remainingFrames);
        return playedFrames >= frame;
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
        armedSwing = null;
        multipleHitsSkill = false;
    }

    /**
     * True while the live swing still has combat-skill multiple-hit frames
     * that have not fired yet. A combat skill's delayed damage must land
     * before the player may start the next swing: Better Combat's
     * {@code startUpswing} happily replaces a mid-flight swing once the
     * upswing is over (the suppressed natural hit never resets the
     * attack-strength ticker, so it reads as full), which would discard the
     * pending frames and - on fast weapons - loop every new swing back into
     * the skill with zero damage until the chain window expires.
     *
     * The hold is naturally bounded: {@link #tickMultipleHits} drops frames
     * beyond the swing window (plus a small grace) or whose owning swing has
     * ended, so this can never block attacks forever. Normal (non-skill)
     * multi-hit weapons are not held; replacing their swing simply restarts
     * the frame schedule, as before.
     */
    public static boolean shouldHoldNewSwing() {
        if (currentMultipleHits == null
                || !multipleHitsSkill
                || nextMultipleHitIndex >= currentMultipleHits.frames.length
                || isExecutingMultipleHit()) {
            return false;
        }
        // Only the swing that owns the frames may hold new swings. Once it
        // is gone the state is dropped on the next player tick anyway.
        return armedSwing != null && findOngoingSwing() == armedSwing;
    }

    /** True when the currently prepared attack belongs to a combat skill. */
    public static boolean isCurrentCombatSkill() {
        return currentCombatSkill;
    }

    /**
     * How long pending multiple-hit frames may outlive the swing window that
     * armed them before the whole state is dropped. Frames slightly past the
     * rounded window are legitimate (for example a 15-tick frame on a swing
     * stretched to ~15.2 ticks), but frames beyond the grace can never fire
     * and must not keep suppressing the default attack forever.
     */
    private static final int MULTIPLE_HIT_WINDOW_GRACE_TICKS = 4;

    /**
     * The default upswing hit is replaced by the configured multiple hits.
     * Once every configured frame has been dispatched the suppression must
     * end, so a stale multiple-hit state can never keep eating later attacks.
     *
     * The suppression only applies while at least one configured frame is
     * still pending. After the last frame fired the state may linger for up
     * to the swing window before {@link #tickMultipleHits} cancels it, but
     * that lingering state must never swallow the natural hit of a following
     * swing: that would leave the player attacking with no damage and, for
     * skill chains whose chain-slot consumption happens on the final
     * multiple-hit packet, would keep later swings locked into the skill.
     *
     * tickMultipleHits is guaranteed to cancel the state once the swing
     * window (plus a grace) is over, even when configured frames can never
     * fire, so this suppression is always bounded and the player can never
     * get stuck in a no-damage swing loop.
     */
    public static boolean shouldSuppressDefaultAttack() {
        return currentMultipleHits != null
                && nextMultipleHitIndex < currentMultipleHits.frames.length
                && !isExecutingMultipleHit();
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
        if (currentMultipleHits == null) {
            return;
        }
        int elapsed = player.tickCount - multipleHitsStartTick;
        // The player entity was replaced (death/respawn resets tickCount) or
        // the timing is otherwise broken: a negative elapsed means the armed
        // multiple hits can never fire, so drop them instead of letting them
        // suppress the default attack forever.
        if (elapsed < 0) {
            cancelMultipleHits();
            FiercerBattlesCombatSkills.consumeBrokenSkillSwing();
            return;
        }
        int swingLengthTicks = Math.max(1, Math.round(animationPlaybackLengthTicks));
        // The multiple hits belong to one swing; once the swing's window is
        // over AND every configured frame has been dispatched the state is
        // stale and must not linger (it would keep suppressing the default
        // attack of later swings forever). Cancelling while frames are still
        // pending would eat frames configured at or just past the swing
        // window: for example a 15-tick frame on a dual-wield swing stretched
        // to ~15.2 ticks (rounds to a 15-tick window) cancels at
        // elapsed == 15 before that frame can fire. Pending frames always
        // come due eventually because frames are strictly ascending, and a
        // new swing re-arms the state before it can misapply.
        int windowEndTicks = swingLengthTicks + MULTIPLE_HIT_WINDOW_GRACE_TICKS;
        if (nextMultipleHitIndex >= currentMultipleHits.frames.length) {
            if (elapsed >= swingLengthTicks) {
                cancelMultipleHits();
            }
            return;
        }
        // Frames configured beyond the swing window (or beyond the
        // animation's playable frame range) can never fire. Without this
        // bound the state would stay armed forever and suppress every later
        // performAttack, soft-locking the player into a no-damage swing loop.
        if (elapsed >= windowEndTicks) {
            dropUnreachableMultipleHits(
                    "configured beyond the swing window, it can never fire"
            );
            return;
        }
        // The frames belong to one specific swing. performAttack dispatches
        // into Better Combat's ongoingSwing; once that swing is gone (it
        // expired naturally - which clears the field WITHOUT cancelWeaponSwing
        // - or was replaced by a newer swing) every remaining frame would
        // "fire" into nothing: no attack packet, no damage, no combo advance,
        // and a skill swing's chain slot would never be consumed. Drop the
        // remaining frames explicitly so the state (and any skill chain)
        // recovers immediately instead of firing blanks.
        if (armedSwing != null && findOngoingSwing() != armedSwing) {
            dropUnreachableMultipleHits(
                    "the swing that owns them has already ended"
            );
            return;
        }
        int currentFrame = currentMultipleHits.animationFrames
                ? currentAnimationFrame(player.tickCount)
                : elapsed;
        if (currentMultipleHits.animationFrames) {
            int totalFrames = animationResourceStopFrame > 0
                    ? animationResourceStopFrame
                    : swingLengthTicks;
            if (currentFrame >= totalFrames
                    && currentMultipleHits.frames[nextMultipleHitIndex] > totalFrames) {
                dropUnreachableMultipleHits(
                        "configured beyond the animation's playable frame range, it can never fire"
                );
                return;
            }
        }
        while (currentMultipleHits != null
                && nextMultipleHitIndex < currentMultipleHits.frames.length
                && currentFrame >= currentMultipleHits.frames[nextMultipleHitIndex]) {
            executingMultipleHitIndex = nextMultipleHitIndex;
            try {
                invokePerformAttack();
            } finally {
                executingMultipleHitIndex = -1;
            }
            // performAttack may cancel the swing (for example the feint key),
            // which clears the multiple-hit state; never touch it again then.
            if (currentMultipleHits == null) {
                return;
            }
            nextMultipleHitIndex += 1;
        }
    }

    /**
     * Drops the armed multiple-hit state because at least one configured
     * frame can never fire (beyond the swing window, beyond the animation's
     * playable range, or its owning swing already ended). Keeping the state
     * would suppress all later attacks. A skill swing whose frames can never
     * fire also consumes its chain slot so the chain cannot keep later swings
     * locked into the broken skill.
     */
    private static void dropUnreachableMultipleHits(String reason) {
        if (currentMultipleHits != null) {
            LOGGER.warn(
                    "Dropping pending multiple-hit frame {} (of {}) of animation '{}': {}",
                    nextMultipleHitIndex + 1,
                    currentMultipleHits.frames.length,
                    currentAnimation,
                    reason
            );
        }
        cancelMultipleHits();
        FiercerBattlesCombatSkills.consumeBrokenSkillSwing();
    }

    /**
     * Better Combat's current {@code ongoingSwing} object (merged into
     * Minecraft by its client mixin), or null when absent or unreachable -
     * null from a missing field simply disables the swing-identity check and
     * keeps the previous timing-based behaviour.
     */
    private static Object findOngoingSwing() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null ? findFieldValue(minecraft, "ongoingSwing") : null;
    }

    /**
     * Maps the current swing position onto the animation resource's frame
     * number (0..stopTick). Prefers Better Combat's live PlayerAnimator
     * playback position so both the stretched playback and the upswing speed
     * ramp are reflected; falls back to a linear tick-to-frame estimate when
     * the live player is unavailable.
     */
    private static int currentAnimationFrame(int currentTick) {
        int totalFrames = animationResourceStopFrame > 0
                ? animationResourceStopFrame
                : Math.max(1, Math.round(animationPlaybackLengthTicks));
        Integer renderedRemaining = getRenderedAnimationRemainingFrames();
        if (renderedRemaining != null) {
            return Math.min(totalFrames, Math.max(0, totalFrames - renderedRemaining));
        }
        int elapsedTicks = Math.max(0, currentTick - multipleHitsStartTick);
        return Math.min(
                totalFrames,
                Math.max(0, Math.round(elapsedTicks * totalFrames / animationPlaybackLengthTicks))
        );
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
        } catch (ReflectiveOperationException exception) {
            // Never let a failed frame silently eat the hit: report it so the
            // missing damage is diagnosable instead of vanishing.
            LOGGER.warn("Failed to perform multiple-hit attack frame", exception);
        }
    }
    public static double getCurrentRangeBonus() {
        return currentRangeBonus;
    }

    public static boolean isInRecovery(int currentTick) {
        // The ordinary Better Combat cooldown already controls normal attacks.
        // This extra gate is kept only for explicitly configured after_cooldown.
        // A recorded recovery window whose start tick lies in the future
        // belongs to a previous player entity (death respawn resets tickCount)
        // and must never block the new one.
        return currentAfterCooldown > 0
                && recoveryUntil > 0
                && currentTick < recoveryUntil
                && animationStartTick <= currentTick;
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
                    if (enabled && !hasItemChannel(emote)) {
                        warnMissingItemChannel(animation);
                    }
                }
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            enabled = false;
        }
        FORCE_ORIGINAL_PLAYBACK.put(animation, enabled);
        return enabled;
    }

    /**
     * Better Combat only moves a held item independently when the animation
     * contains rightItem or leftItem keyframes. Keep this diagnostic next to
     * the playback flag so a missing export channel is visible immediately.
     */
    private static boolean hasItemChannel(JsonObject animation) {
        JsonElement moves = animation.get("moves");
        if (moves == null || !moves.isJsonArray()) {
            return false;
        }
        for (JsonElement move : moves.getAsJsonArray()) {
            if (!move.isJsonObject()) {
                continue;
            }
            JsonObject moveObject = move.getAsJsonObject();
            if (moveObject.has("rightItem") || moveObject.has("leftItem")) {
                return true;
            }
        }
        return false;
    }

    private static void warnMissingItemChannel(String animation) {
        if (ITEM_CHANNEL_WARNINGS.add(animation)) {
            LOGGER.warn(
                    "Animation {} enables forceOriginalPlayback but has no rightItem/leftItem channel; "
                            + "the held item cannot move independently until the animation is re-exported with an item channel",
                    animation
            );
        }
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
            } else if (minecraft.getResourceManager() != null) {
                // Multiplayer client: Better Combat only loads the weapon
                // registry on the server, so the client reads the same
                // weapon_attributes files from its own packs. Without this the
                // client would have no custom data at all and the skill key
                // could not select anything.
                FiercerBattlesCustomData.load(minecraft.getResourceManager());
            }
        }
    }

    public static void tickDisplacement(LocalPlayer player) {
        if (!displacementActive || displacement == null) {
            return;
        }
        int elapsed = player.tickCount - displacementStartTick;
        if (elapsed < 0) {
            // The player entity was replaced (death respawn resets tickCount);
            // the displacement belongs to the old entity and must not keep
            // pushing the new one forever.
            displacementActive = false;
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
