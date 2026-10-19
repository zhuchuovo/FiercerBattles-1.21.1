package com.fiercerbattles.parry.client;

import com.fiercerbattles.FiercerBattlesClient;
import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.FiercerBattlesExecutionClient;
import com.fiercerbattles.FiercerBattlesRuntime;
import com.fiercerbattles.animation.core.AnimationClip;
import com.fiercerbattles.animation.core.AnimationPlayer;
import com.fiercerbattles.animation.rig.HumanoidRig;
import com.fiercerbattles.execution.ExecutionWeapons;
import com.fiercerbattles.locomotion.client.LocomotionClient;
import com.fiercerbattles.mobcombat.api.MobAnimationAccess;
import com.fiercerbattles.parry.ParryAnimations;
import com.fiercerbattles.parry.ParryNetwork;
import com.fiercerbattles.parry.ParryTimeline;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client owner for parry gestures and real attack-pose rewind, without changing the core player. */
@EventBusSubscriber(modid = FiercerBattles.MOD_ID, value = Dist.CLIENT)
public final class ParryClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, Actor> PLAYING = new HashMap<>();
    private static ParryAnimations.Clips animations;
    private static boolean pending;
    private static int pendingTick;
    private static Vec3 pendingPosition;
    private static boolean guardReported;
    /** Last stun pose of recently released actors, so a riposte execution can blend out of it. */
    private static final Map<UUID, ReleasedPose> RELEASED = new HashMap<>();
    private static final int RELEASED_POSE_TICKS = 5;

    private record ReleasedPose(long gameTime, Map<String, AnimationClip.Transform> pose) {}

    private ParryClient() {}

    public static final class Actor {
        private final ParryNetwork.Start start;
        private final AnimationPlayer playback;
        private final ParryPoseHistory.Rewind rewind;
        private final Map<String, Matrix4f> fallback = new HashMap<>();

        private Actor(ParryNetwork.Start start, AnimationClip clip, ParryPoseHistory.Rewind rewind) {
            this.start = start;
            this.playback = new AnimationPlayer(clip, start.startGameTime() + ParryTimeline.reverseTicks(start.kind())
                    - ParryTimeline.clipStartOffsetTicks(start.kind()));
            this.rewind = rewind;
        }

        public ParryNetwork.Start start() { return start; }
        public AnimationClip animation() { return playback.clip(); }
        public boolean hasHistory() { return rewind.available(); }
        public ParryPoseHistory.Bend bend(String channel, float partial) {
            if (!staggered() || !hasHistory()) return ParryPoseHistory.Bend.ZERO;
            ParryPoseHistory.Bend bend = rewind.bend(channel, Math.min(ParryTimeline.REVERSE_TICKS, elapsed(partial)));
            return reversing(partial) ? bend : bend.fade(ParryTimeline.progress(transitionTick(partial), ParryTimeline.IN_TICKS));
        }
        public boolean staggered() { return start.kind() == ParryNetwork.Kind.STAGGER; }
        public boolean hitstunned() { return start.kind() == ParryNetwork.Kind.HITSTUN; }
        public float elapsed(float partial) {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft.level == null ? 0 : minecraft.level.getGameTime() - start.startGameTime() + partial;
        }
        public float tick(float partial) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) return 0;
            if (hitstunned()) {
                // The short flinch clip is stretched over the whole hitstun; its fades stretch with it.
                float rate = ParryTimeline.playbackRate(start.kind(), start.durationTicks());
                return Math.max(0, Math.min(start.durationTicks(), elapsed(partial) * rate));
            }
            return playback.tick(minecraft.level.getGameTime(), partial);
        }
        private float transitionTick(float partial) {
            return staggered() ? Math.max(0, elapsed(partial) - ParryTimeline.REVERSE_TICKS) : tick(partial);
        }
        public boolean reversing(float partial) {
            return staggered() && elapsed(partial) < ParryTimeline.REVERSE_TICKS;
        }
        public boolean matrixReplay(float partial) {
            return reversing(partial) || staggered() && hasHistory() && transitionTick(partial) < ParryTimeline.IN_TICKS;
        }
        public float returnProgress(float partial) {
            int playedTicks = start.durationTicks() - ParryTimeline.clipStartOffsetTicks(start.kind());
            return ParryTimeline.progress(transitionTick(partial) - playedTicks,
                    ParryTimeline.returnTicks(start.kind()));
        }
        public float ordinaryProgress(float partial) {
            float returning = returnProgress(partial);
            if (returning > 0) return returning;
            if (staggered() && hasHistory()) return 0;
            float tick = transitionTick(partial);
            int out = ParryTimeline.fadeOutTicks(start.kind());
            float fading = out > 0 ? ParryTimeline.progress(this.tick(partial) - (start.durationTicks() - out), out) : 0;
            return Math.max(fading, 1 - ParryTimeline.progress(tick, ParryTimeline.inTicks(start.kind())));
        }
        public float yaw(LivingEntity entity, float partial) {
            return staggered() ? start.yaw() : Mth.rotLerp(partial, entity.yBodyRotO, entity.yBodyRot);
        }
        public Vec3 position() { return new Vec3(start.x(), start.y(), start.z()); }

        public Matrix4f replay(String channel, Matrix4f ordinary, float partial) {
            return replay(channel, ordinary, partial, 1.0F);
        }

        public Matrix4f replay(String channel, Matrix4f ordinary, float partial, float modelScale) {
            Matrix4f historical = rewind.sample(channel, Math.min(ParryTimeline.REVERSE_TICKS, elapsed(partial)));
            if (historical == null) {
                historical = fallback.computeIfAbsent(channel,
                        ignored -> new Matrix4f(ordinary));
            }
            if (reversing(partial)) return new Matrix4f(historical);
            AnimationClip.Transform sampled = ParryRenderContext.scaledPose(
                    animation().sample(channel, tick(partial)), modelScale);
            return sampled == null ? null : ParryPoseHistory.interpolate(historical,
                    ParryRenderContext.matrix(sampled), ParryTimeline.progress(transitionTick(partial), ParryTimeline.IN_TICKS));
        }

        /** First-person hands use the same recorded arm timeline, expressed relative to neutral hands. */
        public Matrix4f firstPersonPose(String channel, float partial) {
            AnimationClip.Transform first = animation().sample(channel, 0);
            AnimationClip.Transform sampled = animation().sample(channel, tick(partial));
            if (first == null || sampled == null) return null;
            Matrix4f neutral = ParryRenderContext.matrix(first);
            if (reversing(partial)) {
                Matrix4f historical = rewind.sample(channel, elapsed(partial));
                return historical == null ? neutral : historical;
            }
            if (matrixReplay(partial)) return replay(channel, neutral, partial);
            return ParryPoseHistory.interpolate(ParryRenderContext.matrix(sampled), neutral, ordinaryProgress(partial));
        }
    }

    public static void initialize() {
        ParryNetwork.setClientHandlers(ParryClient::sync, ParryClient::start, ParryClient::stop, ParryClient::reject);
    }

    public static boolean tryRequest(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null || minecraft.screen != null) return false;
        if (blocksActions() || FiercerBattlesExecutionClient.blocksInput() || LocomotionClient.blocksActions()) return true;
        if (!FiercerBattlesRuntime.canParry(minecraft.player.tickCount)) return false;
        reportAnimationEnd(minecraft);
        if (!minecraft.player.onGround()) return false;
        if (!ExecutionWeapons.canExecute(minecraft.player.getMainHandItem())) {
            minecraft.player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.fiercerbattles.parry_weapon"), true);
            return false;
        }
        pending = true;
        pendingTick = minecraft.player.tickCount;
        pendingPosition = minecraft.player.position();
        lockMovement(minecraft.player);
        ParryNetwork.request();
        return true;
    }

    private static void sync(ParryNetwork.Sync payload) {
        try {
            animations = ParryAnimations.decode(payload.data(), payload.hash());
        } catch (IOException exception) {
            LOGGER.error("Unable to load synchronized parry animations", exception);
        }
    }

    private static void start(ParryNetwork.Start payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().location().equals(payload.dimension())) return;
        if (PLAYING.containsKey(payload.sessionId())) return;
        if (animations == null || !animations.hash().equals(payload.animationHash())) {
            LOGGER.error("Parry animation bundle {} is missing", payload.animationHash());
            pending = false;
            return;
        }
        AnimationClip clip = animations.clip(payload.kind());
        if (clip == null || clip.duration() != payload.durationTicks()) {
            LOGGER.error("Parry duration differs from synchronized clip: {}", payload.durationTicks());
            pending = false;
            return;
        }
        ParryPoseHistory.Rewind rewind = ParryPoseHistory.freeze(payload.actorUuid(), payload.startGameTime());
        PLAYING.values().removeIf(actor -> actor.start.actorUuid().equals(payload.actorUuid()));
        PLAYING.put(payload.sessionId(), new Actor(payload, clip, rewind));
        if (payload.kind() == ParryNetwork.Kind.STAGGER) {
            LOGGER.info("Stagger pose rewind for {}: {}", payload.actorId(), rewind.available() ? "6 captured ticks" : "ordinary fallback");
        }
        Entity entity = minecraft.level.getEntity(payload.actorId());
        if (entity != null && entity != minecraft.player && entity.getId() == payload.actorId()
                && entity.getUUID().equals(payload.actorUuid())) {
            stopAttackAnimation(entity);
        }
        if (minecraft.player != null && minecraft.player.getUUID().equals(payload.actorUuid())) {
            pending = false;
            FiercerBattlesClient.cancelAttackForExecution(minecraft);
        }
    }

    private static void stop(ParryNetwork.Stop payload) {
        Actor actor = PLAYING.get(payload.sessionId());
        if (actor != null && actor.start.actorId() == payload.actorId()
                && actor.start.actorUuid().equals(payload.actorUuid())) {
            PLAYING.remove(payload.sessionId());
            Minecraft minecraft = Minecraft.getInstance();
            if (actor.staggered() && minecraft.level != null) {
                Map<String, AnimationClip.Transform> pose = new HashMap<>();
                float tick = actor.tick(0);
                Entity entity = minecraft.level.getEntity(actor.start.actorId());
                float scale = entity instanceof LivingEntity living
                        && living.getUUID().equals(payload.actorUuid()) ? ParryRenderContext.ordinaryScale(living) : 1.0F;
                for (String channel : HumanoidRig.REQUIRED_CHANNELS) {
                    AnimationClip.Transform sampled = ParryRenderContext.scaledPose(
                            actor.animation().sample(channel, tick), scale);
                    if (sampled != null) pose.put(channel, sampled);
                }
                RELEASED.put(payload.actorUuid(), new ReleasedPose(minecraft.level.getGameTime(), pose));
            }
        }
    }

    /** The stun pose of an actor whose stun the server just ended (a riposte), or null. */
    public static Map<String, AnimationClip.Transform> takeReleasedPose(UUID actor) {
        ReleasedPose released = RELEASED.remove(actor);
        Minecraft minecraft = Minecraft.getInstance();
        if (released == null || minecraft.level == null
                || minecraft.level.getGameTime() - released.gameTime > RELEASED_POSE_TICKS) return null;
        return released.pose;
    }

    private static void reject(ParryNetwork.Reject payload) { pending = false; }

    private static void stopAttackAnimation(Entity entity) {
        if (entity instanceof MobAnimationAccess mob) {
            mob.bmc$stopAttackAnimation();
            return;
        }
        try {
            entity.getClass().getMethod("stopAttackAnimation", float.class).invoke(entity, 0.0F);
        } catch (NoSuchMethodException ignored) {
            // Ordinary mobs have no Better Combat player animation to stop.
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.debug("Unable to stop the remote attack animation for {}", entity.getId(), exception);
        }
    }

    public static Actor actor(LivingEntity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return null;
        for (Actor actor : PLAYING.values()) {
            if (actor.start.actorId() == entity.getId() && actor.start.actorUuid().equals(entity.getUUID())
                    && actor.start.dimension().equals(minecraft.level.dimension().location())
                    && actor.elapsed(0) < ParryTimeline.totalTicks(actor.start.kind(), actor.start.durationTicks())) return actor;
        }
        return null;
    }

    public static boolean isBusy(Entity entity) { return entity instanceof LivingEntity living && actor(living) != null; }
    public static boolean isMovementLocked(Entity entity) {
        if (entity == Minecraft.getInstance().player && pending) return true;
        Actor actor = entity instanceof LivingEntity living ? actor(living) : null;
        return actor != null && actor.start.kind().locksMovement();
    }
    /** True only while a held pose suspends the actor; a parry always keeps falling. */
    public static boolean isVerticallyLocked(Entity entity) {
        Actor actor = entity instanceof LivingEntity living ? actor(living) : null;
        return actor != null && actor.start.kind().locksVertical();
    }
    public static boolean isStaggered(Entity entity) {
        Actor actor = entity instanceof LivingEntity living ? actor(living) : null;
        return actor != null && actor.staggered();
    }
    public static boolean isHitstunned(Entity entity) {
        Actor actor = entity instanceof LivingEntity living ? actor(living) : null;
        return actor != null && actor.hitstunned();
    }
    public static boolean blocksActions() {
        return pending || Minecraft.getInstance().player != null && isBusy(Minecraft.getInstance().player);
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) { reset(); return; }
        reportAnimationEnd(minecraft);
        if (pending && minecraft.player.tickCount - pendingTick > 40) pending = false;
        PLAYING.values().removeIf(actor -> !actor.start.dimension().equals(minecraft.level.dimension().location())
                || actor.elapsed(0) >= ParryTimeline.totalTicks(actor.start.kind(), actor.start.durationTicks()));
        for (Actor actor : PLAYING.values()) {
            Entity entity = minecraft.level.getEntity(actor.start.actorId());
            if (!(entity instanceof LivingEntity living) || !entity.getUUID().equals(actor.start.actorUuid())) continue;
            lockMovement(living);
        }
        lockMovement(minecraft.player);
        ParryPoseHistory.prune(minecraft.level.getGameTime());
        long now = minecraft.level.getGameTime();
        RELEASED.values().removeIf(released -> now - released.gameTime > RELEASED_POSE_TICKS);
    }

    /** Holds the local player immediately, including while the server answers the request. */
    public static void lockMovement(Entity entity) {
        if (!(entity instanceof LivingEntity living) || !entity.level().isClientSide()) return;
        Minecraft minecraft = Minecraft.getInstance();
        Actor actor = actor(living);
        Vec3 position = actor != null && actor.start.kind().locksMovement() ? actor.position()
                : living == minecraft.player && pending ? pendingPosition : null;
        if (position == null) return;
        // A parry only pins the horizontal footprint, so the actor keeps falling under gravity.
        boolean vertical = actor != null && actor.start.kind().locksVertical();
        living.setPos(position.x, vertical ? position.y : living.getY(), position.z);
        living.xo = living.xOld = position.x;
        living.yo = living.yOld = living.getY();
        living.zo = living.zOld = position.z;
        Vec3 motion = living.getDeltaMovement();
        living.setDeltaMovement(vertical ? Vec3.ZERO : new Vec3(0.0, motion.y, 0.0));
        living.xxa = living.yya = living.zza = 0;
        living.setJumping(false);
        living.setSprinting(false);
        living.setShiftKeyDown(false);
        if (vertical) living.resetFallDistance();
        if (actor != null && actor.staggered()) {
            living.yBodyRot = living.yBodyRotO = actor.start.yaw();
            if (living != minecraft.player) {
                living.setYRot(actor.start.yaw()); living.yRotO = actor.start.yaw();
                living.yHeadRot = living.yHeadRotO = actor.start.yaw();
                living.setXRot(0); living.xRotO = 0;
            }
        }
    }

    /** Tells the server whether the swing that just started can be interrupted by a hit. */
    public static void reportSwing() {
        if (Minecraft.getInstance().getConnection() == null) return;
        Boolean interruptible = FiercerBattlesRuntime.currentHitInterruptible();
        byte mode = interruptible == null ? ParryNetwork.GUARD_UNSET
                : interruptible ? ParryNetwork.GUARD_INTERRUPTIBLE : ParryNetwork.GUARD_ARMORED;
        ParryNetwork.guard(mode, FiercerBattlesRuntime.currentSwingTicks(), FiercerBattlesRuntime.currentAnimationTicks());
        guardReported = true;
    }

    /** A damage check closes the cancellation window even when the attack hits nothing. */
    public static void damageChecked() {
        if (FiercerBattlesRuntime.markParryDamageChecked() && guardReported) ParryNetwork.damageChecked();
    }

    private static void reportAnimationEnd(Minecraft minecraft) {
        if (guardReported && FiercerBattlesRuntime.finishParryAttack(minecraft.player.tickCount)) {
            ParryNetwork.animationFinished();
        }
    }

    /** A cancelled swing no longer protects (or exposes) the player. */
    public static void clearSwing() {
        FiercerBattlesRuntime.clearParryAttack();
        if (!guardReported || Minecraft.getInstance().getConnection() == null) return;
        ParryNetwork.guard(ParryNetwork.GUARD_UNSET, 0);
        guardReported = false;
    }

    public static void reset() {
        PLAYING.clear(); RELEASED.clear(); pending = false; pendingPosition = null; guardReported = false;
        ParryPoseHistory.clear(); ParryRenderContext.clear();
    }
    public static void clearAnimations() { animations = null; }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        boolean locked = isMovementLocked(event.getEntity());
        if (!locked && !isHitstunned(event.getEntity())) return;
        // A flinch stops walking and jumping; the hit's knockback still moves the player.
        var input = event.getInput();
        input.forwardImpulse = input.leftImpulse = 0;
        input.up = input.down = input.left = input.right = false;
        input.jumping = false;
        if (locked) {
            input.shiftKeyDown = false;
            lockMovement(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!blocksActions()) return;
        event.setCanceled(true);
        event.setSwingHand(false);
    }
}
