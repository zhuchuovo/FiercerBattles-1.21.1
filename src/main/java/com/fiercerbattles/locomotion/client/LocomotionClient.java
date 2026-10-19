package com.fiercerbattles.locomotion.client;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.FiercerBattlesClient;
import com.fiercerbattles.FiercerBattlesExecutionClient;
import com.fiercerbattles.animation.core.AnimationClip;
import com.fiercerbattles.locomotion.LocomotionAnimations;
import com.fiercerbattles.locomotion.LocomotionNetwork;
import com.fiercerbattles.locomotion.LocomotionTimeline;
import com.fiercerbattles.mixin.LivingEntitySwimAccessor;
import com.fiercerbattles.parry.client.ParryClient;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.joml.Quaternionf;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Client owner of roll/prone playback, the sprint clip, and the local player's roll displacement. */
@EventBusSubscriber(modid = FiercerBattles.MOD_ID, value = Dist.CLIENT)
public final class LocomotionClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, Actor> PLAYING = new HashMap<>();
    private static final Map<UUID, Run> RUNNING = new HashMap<>();
    private static final Set<UUID> FORCED_POSE = new HashSet<>();
    /** Keeps the rolling camera this far below a low ceiling, clear of the near plane. */
    private static final double CEILING_MARGIN = 0.15;
    private static LocomotionAnimations.Clips animations;
    private static boolean pending;
    private static int pendingTick;
    private static Method playerAnimation;
    private static Method animationActive;
    private static boolean animatorUnavailable;

    private LocomotionClient() {}

    /** One resolved pose for one render of one entity: clip sampler, ordinary-pose weight, root yaw, head look. */
    public record Playback(Function<String, AnimationClip.Transform> sampler, float ordinaryWeight, float yaw, Quaternionf look) {
        public AnimationClip.Transform sample(String channel) {
            AnimationClip.Transform sampled = sampler.apply(channel);
            if (sampled == null || look == null || !"head".equals(channel)) return sampled;
            return new AnimationClip.Transform(sampled.x(), sampled.y(), sampled.z(),
                    new Quaternionf(sampled.rotation()).mul(look), sampled.scale());
        }
    }

    private static final class Run {
        float previous;
        float weight;
    }

    public static final class Actor {
        private final LocomotionNetwork.Start start;
        /** Roll tick at which the roll was cut short (released or stopped), or NaN while it runs its course. */
        private float releasedAt = Float.NaN;

        private Actor(LocomotionNetwork.Start start) { this.start = start; }

        public LocomotionNetwork.Kind kind() { return start.kind(); }
        public float elapsed(float partial) {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft.level == null ? 0 : minecraft.level.getGameTime() - start.startGameTime() + partial;
        }
        int duration() { return animations.clip(start.kind()).duration(); }
        /** Game ticks of the sped-up roll clip. */
        float rollTicks() { return LocomotionTimeline.rollTicks(duration()); }
        /** Prone after the drop-down clip has finished: crawling is allowed. */
        public boolean settled() { return start.kind() == LocomotionNetwork.Kind.PRONE && elapsed(0) >= duration(); }
        boolean released() { return !Float.isNaN(releasedAt); }
        void release() { if (!released()) releasedAt = Math.min(elapsed(0), rollTicks()); }
        /** Where the roll clip hands back to the ordinary pose: its end, or the moment it was cut short. */
        float rollEnd() { return released() ? releasedAt : rollTicks(); }
        /** A roll still tumbling: it holds the crawl-high hitbox until it ends or is cut short. */
        boolean rolling() { return start.kind() == LocomotionNetwork.Kind.ROLL && !released() && elapsed(0) < rollTicks(); }
        /** A sprinting dive still sliding along its heading during the drop-down clip. */
        boolean sliding() { return start.kind() == LocomotionNetwork.Kind.PRONE && start.slide() && elapsed(0) < duration(); }
        boolean finished() {
            return switch (start.kind()) {
                case ROLL -> elapsed(0) >= rollEnd() + LocomotionTimeline.ROLL_OUT_TICKS;
                case STAND -> elapsed(0) >= duration() + LocomotionTimeline.STAND_OUT_TICKS;
                case PRONE -> false;
            };
        }
    }

    public static void initialize() {
        LocomotionNetwork.setClientHandlers(LocomotionClient::sync, LocomotionClient::start,
                LocomotionClient::stop, LocomotionClient::reject);
    }

    public static void tryRoll(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        // Not blocksActions(): pressing roll again must not cut the current roll's recovery short.
        if (player == null || minecraft.level == null || minecraft.screen != null || pending || actor(player) != null
                || FiercerBattlesExecutionClient.blocksInput() || ParryClient.blocksActions()) return;
        if (!player.onGround()) {
            player.displayClientMessage(Component.translatable("message.fiercerbattles.roll_ground"), true);
            return;
        }
        pending = true;
        pendingTick = player.tickCount;
        LocomotionNetwork.requestRoll(inputHeading(player));
    }

    /** The held movement keys as a world yaw (relative to the camera), or straight ahead without input. */
    private static float inputHeading(LocalPlayer player) {
        float left = player.input.leftImpulse, forward = player.input.forwardImpulse;
        float yaw = player.getYRot();
        if (Math.abs(left) + Math.abs(forward) <= 1.0E-3F) return yaw;
        float sin = Mth.sin(yaw * Mth.DEG_TO_RAD), cos = Mth.cos(yaw * Mth.DEG_TO_RAD);
        double x = left * cos - forward * sin, z = forward * cos + left * sin;
        return (float) Math.toDegrees(Math.atan2(-x, z));
    }

    public static void toggleProne(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.screen != null || pending) return;
        Actor actor = actor(player);
        if (actor == null) {
            if (FiercerBattlesExecutionClient.blocksInput() || ParryClient.blocksActions()) return;
            if (!player.onGround()) {
                player.displayClientMessage(Component.translatable("message.fiercerbattles.prone_ground"), true);
                return;
            }
            pending = true;
            pendingTick = player.tickCount;
            // The server decides whether this is a sprinting dive; the heading only matters then.
            LocomotionNetwork.requestProne(true, inputHeading(player));
        } else if (actor.settled()) {
            pending = true;
            pendingTick = player.tickCount;
            LocomotionNetwork.requestProne(false, player.getYRot());
        }
    }

    private static void sync(LocomotionNetwork.Sync payload) {
        try {
            animations = LocomotionAnimations.decode(payload.data(), payload.hash());
        } catch (IOException exception) {
            LOGGER.error("Unable to load synchronized locomotion animations", exception);
        }
    }

    private static void start(LocomotionNetwork.Start payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().location().equals(payload.dimension())) return;
        boolean local = minecraft.player != null && minecraft.player.getUUID().equals(payload.actorUuid());
        if (local) pending = false;
        if (animations == null || !animations.hash().equals(payload.animationHash())) {
            LOGGER.error("Locomotion animation bundle {} is missing", payload.animationHash());
            return;
        }
        PLAYING.put(payload.actorUuid(), new Actor(payload));
        RUNNING.remove(payload.actorUuid());
        if (local && payload.kind() != LocomotionNetwork.Kind.STAND) FiercerBattlesClient.cancelAttackForExecution(minecraft);
    }

    private static void stop(LocomotionNetwork.Stop payload) {
        Actor actor = PLAYING.get(payload.actorUuid());
        if (actor == null || !actor.start.sessionId().equals(payload.sessionId())) return;
        // A roll cut short eases back to the ordinary pose, the same way its natural end does.
        if (actor.kind() == LocomotionNetwork.Kind.ROLL) actor.release();
        else PLAYING.remove(payload.actorUuid());
    }

    private static void reject(LocomotionNetwork.Reject payload) { pending = false; }

    public static Actor actor(LivingEntity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || animations == null) return null;
        Actor actor = PLAYING.get(entity.getUUID());
        if (actor == null || actor.start.actorId() != entity.getId()
                || !actor.start.dimension().equals(minecraft.level.dimension().location())
                || !animations.hash().equals(actor.start.animationHash()) || actor.finished()) return null;
        return actor;
    }

    /**
     * Weapon swings, combat skills, execution and parry wait until the local player is upright again.
     * Callers ask this as an action starts, so from the roll's recovery tick it ends the roll and lets the action through.
     */
    public static boolean blocksActions() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (pending || player == null) return pending;
        Actor actor = actor(player);
        return actor != null && !releaseRoll(actor);
    }

    /** End the local roll early once its recovery window is open; true when the roll no longer holds the player. */
    private static boolean releaseRoll(Actor actor) {
        if (actor.kind() != LocomotionNetwork.Kind.ROLL) return false;
        if (actor.released()) return true;
        if (!LocomotionTimeline.rollRecovering(actor.elapsed(0))) return false;
        actor.release();
        LocomotionNetwork.releaseRoll(actor.start.sessionId());
        return true;
    }

    /**
     * Camera eye height for a rolling camera entity, or NaN when it is not rolling: the standing eye height
     * eased down and back up, always kept under any low ceiling the roll passes beneath. Vanilla eases on
     * from this value once the roll lets go.
     */
    public static float rollEyeHeight(Entity entity) {
        if (!(entity instanceof Player player)) return Float.NaN;
        Actor actor = actor(player);
        if (actor == null || !actor.rolling()) return Float.NaN;
        // The camera eases toward this value over the coming tick.
        double eye = player.getEyeHeight(Pose.STANDING)
                - LocomotionTimeline.rollCameraDrop(actor.elapsed(0) + 1) * player.getScale();
        Vec3 feet = player.position();
        Vec3 next = feet.add(player.getDeltaMovement().multiply(1, 0, 1));
        double ceiling = Math.min(headroom(player, feet), headroom(player, next)) - CEILING_MARGIN;
        return (float) Math.max(player.getEyeHeight(Pose.SWIMMING), Math.min(eye, ceiling));
    }

    private static double headroom(Player player, Vec3 feet) {
        BlockHitResult hit = player.level().clip(new ClipContext(feet.add(0, 0.05, 0), feet.add(0, 2.0, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : hit.getLocation().y - feet.y;
    }

    /** Resolve this render's locomotion pose, or null when the entity is not rolling, prone or running. */
    public static Playback playback(LivingEntity entity, float partial) {
        if (animations == null) return null;
        Actor actor = actor(entity);
        if (actor != null) return switch (actor.kind()) {
            case ROLL -> roll(entity, actor, partial);
            case PRONE -> prone(entity, actor, partial);
            case STAND -> stand(entity, actor, partial);
        };
        Run run = RUNNING.get(entity.getUUID());
        if (run == null) return null;
        float weight = LocomotionTimeline.progress(Mth.lerp(partial, run.previous, run.weight), 1);
        if (weight <= 0) return null;
        AnimationClip clip = animations.run();
        float tick = LocomotionTimeline.loopTick(entity.walkAnimation.position(partial) * LocomotionTimeline.RUN_TICKS_PER_STRIDE,
                animations.runLoop().start(), animations.runLoop().end());
        return new Playback(channel -> clip.sample(channel, tick), 1 - weight, bodyYaw(entity, partial),
                look(entity, partial, 180, 1));
    }

    private static Playback roll(LivingEntity entity, Actor actor, float partial) {
        AnimationClip clip = animations.roll();
        float elapsed = actor.elapsed(partial);
        float tick = Math.max(0, Math.min(clip.duration(), LocomotionTimeline.rollFrame(elapsed)));
        float ordinary = elapsed > actor.rollEnd()
                ? LocomotionTimeline.progress(elapsed - actor.rollEnd(), LocomotionTimeline.ROLL_OUT_TICKS)
                : 1 - LocomotionTimeline.progress(elapsed, LocomotionTimeline.ROLL_IN_TICKS);
        float yaw = Mth.rotLerp(ordinary, actor.start.yaw(), bodyYaw(entity, partial));
        return new Playback(channel -> clip.sample(channel, tick), ordinary, yaw, null);
    }

    private static Playback prone(LivingEntity entity, Actor actor, float partial) {
        AnimationClip down = animations.proneDown();
        float elapsed = actor.elapsed(partial);
        if (elapsed < down.duration()) {
            float tick = Math.max(0, elapsed);
            return new Playback(channel -> down.sample(channel, tick),
                    1 - LocomotionTimeline.progress(elapsed, LocomotionTimeline.PRONE_IN_TICKS), bodyYaw(entity, partial), null);
        }
        float crossfade = LocomotionTimeline.progress(elapsed - down.duration(), LocomotionTimeline.CRAWL_CROSSFADE_TICKS);
        float crawlTick = crawlTick(entity, partial);
        return new Playback(channel -> blend(down.sample(channel, down.duration()),
                animations.proneCrawl().sample(channel, crawlTick), crossfade), 0, bodyYaw(entity, partial),
                look(entity, partial, LocomotionTimeline.CRAWL_HEAD_YAW_LIMIT, crossfade));
    }

    private static Playback stand(LivingEntity entity, Actor actor, float partial) {
        AnimationClip up = animations.proneUp();
        float elapsed = actor.elapsed(partial);
        float tick = Math.max(0, Math.min(up.duration(), elapsed));
        float crossfade = LocomotionTimeline.progress(elapsed, LocomotionTimeline.STAND_CROSSFADE_TICKS);
        float crawlTick = crawlTick(entity, partial);
        float ordinary = elapsed > up.duration()
                ? LocomotionTimeline.progress(elapsed - up.duration(), LocomotionTimeline.STAND_OUT_TICKS) : 0;
        return new Playback(channel -> blend(animations.proneCrawl().sample(channel, crawlTick),
                up.sample(channel, tick), crossfade), ordinary, bodyYaw(entity, partial),
                look(entity, partial, LocomotionTimeline.CRAWL_HEAD_YAW_LIMIT, 1 - crossfade));
    }

    private static float crawlTick(LivingEntity entity, float partial) {
        return LocomotionTimeline.loopTick(entity.walkAnimation.position(partial) * LocomotionTimeline.CRAWL_TICKS_PER_STRIDE,
                animations.crawlLoop().start(), animations.crawlLoop().end());
    }

    private static float bodyYaw(LivingEntity entity, float partial) {
        return Mth.rotLerp(partial, entity.yBodyRotO, entity.yBodyRot);
    }

    /** The vanilla head look (yaw relative to the body, pitch), applied on top of the clip's head pose. */
    private static Quaternionf look(LivingEntity entity, float partial, float yawLimit, float weight) {
        if (weight <= 0) return null;
        float yaw = Mth.clamp(Mth.wrapDegrees(Mth.rotLerp(partial, entity.yHeadRotO, entity.yHeadRot) - bodyYaw(entity, partial)),
                -yawLimit, yawLimit);
        float pitch = Mth.lerp(partial, entity.xRotO, entity.getXRot());
        Quaternionf look = new Quaternionf().rotationZYX(0, yaw * Mth.DEG_TO_RAD, pitch * Mth.DEG_TO_RAD);
        return weight >= 1 ? look : new Quaternionf().slerp(look, weight);
    }

    private static AnimationClip.Transform blend(AnimationClip.Transform from, AnimationClip.Transform to, float amount) {
        if (from == null || amount >= 1) return to;
        if (to == null || amount <= 0) return from;
        return new AnimationClip.Transform(Mth.lerp(amount, from.x(), to.x()), Mth.lerp(amount, from.y(), to.y()),
                Mth.lerp(amount, from.z(), to.z()), new Quaternionf(from.rotation()).slerp(to.rotation(), amount),
                Mth.lerp(amount, from.scale(), to.scale()));
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) { reset(); return; }
        if (pending && minecraft.player.tickCount - pendingTick > 40) pending = false;
        PLAYING.values().removeIf(actor -> !actor.start.dimension().equals(minecraft.level.dimension().location())
                || animations != null && actor.finished());
        // Mirror the server's crawling hitbox on every client copy so local prediction and remote
        // bounding boxes agree; the stand-up clip releases it as it starts, a roll as it ends.
        for (Player player : minecraft.level.players()) {
            Actor actor = actor(player);
            boolean crawling = actor != null && (actor.kind() == LocomotionNetwork.Kind.PRONE || actor.rolling());
            if (crawling) {
                if (player.getForcedPose() != Pose.SWIMMING) player.setForcedPose(Pose.SWIMMING);
                FORCED_POSE.add(player.getUUID());
            } else if (FORCED_POSE.remove(player.getUUID()) && player.getForcedPose() == Pose.SWIMMING) {
                player.setForcedPose(null);
            }
            if (actor != null && actor.rolling()) {
                // The swimming pose would build up vanilla's crawl tilt, which outlives the roll by ~11 ticks.
                LivingEntitySwimAccessor swim = (LivingEntitySwimAccessor) player;
                swim.fiercerbattles$setSwimAmount(0);
                swim.fiercerbattles$setSwimAmountO(0);
            }
            if (actor != null && (actor.kind() == LocomotionNetwork.Kind.ROLL && !actor.released() || actor.sliding())) {
                player.yBodyRot = player.yBodyRotO = actor.start.yaw();
            }
            updateRun(player, actor);
        }
        RUNNING.keySet().removeIf(uuid -> minecraft.level.getPlayerByUUID(uuid) == null);
        FORCED_POSE.removeIf(uuid -> minecraft.level.getPlayerByUUID(uuid) == null);
    }

    private static void updateRun(Player player, Actor actor) {
        boolean want = actor == null && player.isSprinting() && player.isAlive() && !player.isSpectator()
                && !player.isPassenger() && !player.isSwimming() && !player.isVisuallyCrawling() && !player.isFallFlying()
                && !player.getAbilities().flying && !player.isUsingItem() && !player.isSleeping()
                && !ParryClient.isBusy(player) && !FiercerBattlesExecutionClient.isExecuting(player);
        Run run = RUNNING.get(player.getUUID());
        if (run == null) {
            if (!want) return;
            run = new Run();
            RUNNING.put(player.getUUID(), run);
        }
        run.previous = run.weight;
        if (actor != null || playerAnimatorActive(player)) {
            // A Better Combat swing (or any PlayerAnimator layer) owns the body; cut the run at once
            // rather than fading through a suppressed attack pose.
            run.previous = run.weight = 0;
        } else {
            run.weight = Mth.clamp(run.weight + (want ? 1F : -1F) / LocomotionTimeline.RUN_FADE_TICKS, 0, 1);
        }
        if (run.previous <= 0 && run.weight <= 0 && !want) RUNNING.remove(player.getUUID());
    }

    private static boolean playerAnimatorActive(Player player) {
        if (!(player instanceof AbstractClientPlayer) || animatorUnavailable) return false;
        try {
            if (playerAnimation == null) playerAnimation = player.getClass().getMethod("playerAnimator_getAnimation");
            Object animation = playerAnimation.invoke(player);
            if (animation == null) return false;
            if (animationActive == null) animationActive = animation.getClass().getMethod("isActive");
            return Boolean.TRUE.equals(animationActive.invoke(animation));
        } catch (NoSuchMethodException exception) {
            animatorUnavailable = true;
            return false;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.debug("Unable to query PlayerAnimator state for {}", player.getId(), exception);
            return false;
        }
    }

    public static void reset() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && FORCED_POSE.contains(player.getUUID()) && player.getForcedPose() == Pose.SWIMMING) {
            player.setForcedPose(null);
        }
        PLAYING.clear(); RUNNING.clear(); FORCED_POSE.clear(); pending = false;
        LocomotionRenderContext.clear();
    }
    public static void clearAnimations() { animations = null; }

    /** The local player is a client-authoritative mover: apply the roll's or dive's displacement before it travels. */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getEntity() != minecraft.player || minecraft.level == null) return;
        Actor actor = actor(minecraft.player);
        if (actor == null) return;
        long elapsed = minecraft.level.getGameTime() - actor.start.startGameTime();
        double step;
        if (actor.kind() == LocomotionNetwork.Kind.ROLL && !actor.released()) {
            float duration = actor.rollTicks();
            if (elapsed < 0 || elapsed >= duration) return;
            step = LocomotionTimeline.rollTravel(elapsed + 1, duration) - LocomotionTimeline.rollTravel(elapsed, duration);
        } else if (actor.sliding()) {
            int duration = actor.duration();
            if (elapsed < 0) return;
            step = LocomotionTimeline.proneSlideTravel(elapsed + 1, duration) - LocomotionTimeline.proneSlideTravel(elapsed, duration);
        } else {
            return;
        }
        float yaw = actor.start.yaw() * Mth.DEG_TO_RAD;
        Vec3 motion = minecraft.player.getDeltaMovement();
        minecraft.player.setDeltaMovement(-Mth.sin(yaw) * step, motion.y, Mth.cos(yaw) * step);
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!(event.getEntity() instanceof LocalPlayer player)) return;
        Actor actor = actor(player);
        if (actor == null) return;
        var input = event.getInput();
        if (actor.kind() == LocomotionNetwork.Kind.ROLL) {
            // From the recovery tick, steering or jumping hands control straight back.
            boolean moving = Math.abs(input.forwardImpulse) + Math.abs(input.leftImpulse) > 1.0E-3F || input.jumping;
            if (actor.released() || moving && releaseRoll(actor)) return;
        }
        input.jumping = false;
        if (actor.settled()) return;
        input.forwardImpulse = input.leftImpulse = 0;
        input.up = input.down = input.left = input.right = false;
        input.shiftKeyDown = false;
    }

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !blocksActions()) return;
        Actor actor = actor(minecraft.player);
        // Settled prone players may still dig, use items and pick blocks; attacks on entities are refused.
        boolean allowed = actor != null && actor.settled() && (!event.isAttack()
                || minecraft.hitResult != null && minecraft.hitResult.getType() == HitResult.Type.BLOCK);
        if (allowed) return;
        event.setCanceled(true);
        event.setSwingHand(false);
    }
}
