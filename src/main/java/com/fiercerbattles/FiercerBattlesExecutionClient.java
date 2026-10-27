package com.fiercerbattles;

import com.fiercerbattles.animation.core.AnimationClip;
import com.fiercerbattles.animation.core.AnimationPlayer;
import com.fiercerbattles.execution.ExecutionNetwork;
import com.fiercerbattles.execution.ExecutionWeapons;
import com.fiercerbattles.execution.ExecutionTimeline;
import com.fiercerbattles.locomotion.client.LocomotionClient;
import com.fiercerbattles.parry.client.ParryClient;
import com.fiercerbattles.execution.client.ExecutionRenderContext;
import com.fiercerbattles.execution.client.ExecutionHighlight;
import com.fiercerbattles.execution.action.PairedExecutionAction;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = FiercerBattles.MOD_ID, value = Dist.CLIENT)
public final class FiercerBattlesExecutionClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, Playing> PLAYING = new HashMap<>();
    private static final Map<net.minecraft.resources.ResourceLocation, PairedExecutionAction> ACTIONS = new ConcurrentHashMap<>();
    private static int pendingTarget = -1;
    private static int pendingTick;
    private static CameraType previousCamera;
    private static UUID cameraSession;
    private static long cameraStartNanos;
    private static boolean cameraPullback;
    private static float cameraYaw;
    private static float cameraPitch;
    private static final long CAMERA_TRANSITION_NANOS = 200_000_000L;
    public static final int RETURN_TICKS = ExecutionTimeline.RETURN_TICKS;
    private static final double MAX_TARGET_RANGE = 6.0;

    private static final class Playing {
        final ExecutionNetwork.Start start;
        final PairedExecutionAction action;
        boolean ended;
        long visualEnd;
        long returnStart;
        float returnYawDelta;
        final float executorFromPitch;
        final float executorFromHeadYaw;
        final float victimFromPitch;
        final float victimFromHeadYaw;
        /** Parry-stun pose the victim was released from (riposte), blended out during the approach. */
        final Map<String, AnimationClip.Transform> victimStartPose;

        Playing(ExecutionNetwork.Start start, PairedExecutionAction action) {
            this.start = start;
            this.action = action;
            Minecraft minecraft = Minecraft.getInstance();
            Entity executor = minecraft.level == null ? null : minecraft.level.getEntity(start.executorId());
            Entity victim = minecraft.level == null ? null : minecraft.level.getEntity(start.victimId());
            executorFromPitch = executor == null ? 0 : executor.getXRot();
            executorFromHeadYaw = executor instanceof LivingEntity living ? living.yHeadRot : start.executorFromYaw();
            victimFromPitch = victim == null ? 0 : victim.getXRot();
            victimFromHeadYaw = victim instanceof LivingEntity living ? living.yHeadRot : start.victimFromYaw();
            victimStartPose = ParryClient.takeReleasedPose(start.victimUuid());
        }
    }

    public record Actor(AnimationPlayer playback, PairedExecutionAction action, float yaw, double x, double y, double z,
                        int duration, boolean ended, boolean returning,
                        long returnStart, float returnYawDelta,
                        double fromX, double fromY, double fromZ, float fromYaw,
                        Map<String, AnimationClip.Transform> startPose) {
        public AnimationClip animation() { return playback.clip(); }

        /** Pose to approach from instead of the ordinary model pose, or null. */
        public AnimationClip.Transform startPose(String channel) {
            return startPose == null || returning ? null : startPose.get(channel);
        }

        public float tick(float partial) {
            Minecraft minecraft = Minecraft.getInstance();
            if (ended || minecraft.level == null) return duration;
            return playback.tick(minecraft.level.getGameTime(), partial);
        }

        public float returnProgress(float partial) {
            Minecraft minecraft = Minecraft.getInstance();
            if (!returning || minecraft.level == null) return 0;
            return ExecutionTimeline.progress(minecraft.level.getGameTime() - returnStart + partial, RETURN_TICKS);
        }

        public float approachProgress(float partial) {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft.level == null ? 1 : ExecutionTimeline.approachProgress(
                    playback.startGameTime(), minecraft.level.getGameTime(), partial);
        }

        public Vec3 renderPosition(float partial) {
            Vec3 anchor = new Vec3(x, y, z);
            return returning ? anchor : ExecutionTimeline.approachPosition(
                    new Vec3(fromX, fromY, fromZ), anchor, approachProgress(partial));
        }

        public float renderYaw(float partial) {
            return returning ? yaw : Mth.rotLerp(approachProgress(partial), fromYaw, yaw);
        }
    }

    private FiercerBattlesExecutionClient() {}

    public static void initialize() {
        ExecutionNetwork.setClientHandlers(FiercerBattlesExecutionClient::start,
                FiercerBattlesExecutionClient::end, FiercerBattlesExecutionClient::reject);
        ExecutionNetwork.setClientActionSyncHandler(FiercerBattlesExecutionClient::acceptActionPayload);
        ExecutionNetwork.setClientHighlightRulesHandler(ExecutionHighlight::acceptRules);
    }

    /** The dedicated execution key requests an eligible target; the server validates the action. */
    public static boolean tryRequest(Minecraft minecraft) {
        LOGGER.info("Execution key invoked");
        if (blocksInput() || ParryClient.blocksActions() || LocomotionClient.blocksActions()) {
            LOGGER.info("Execution request blocked: pending target={}, execution active={}", pendingTarget,
                    minecraft.player != null && isExecuting(minecraft.player));
            return true;
        }
        if (minecraft.player == null || minecraft.level == null || minecraft.screen != null) return false;
        if (!ExecutionWeapons.canExecute(minecraft.player.getMainHandItem())) {
            minecraft.player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.fiercerbattles.execution_weapon"), true);
            LOGGER.info("Execution request ignored: unsupported main-hand weapon");
            return false;
        }
        LivingEntity target = pickTarget(minecraft);
        if (target == null) {
            minecraft.player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.fiercerbattles.execution_no_target"), true);
            LOGGER.info("Execution request ignored: no living target under crosshair");
            return false;
        }
        LOGGER.info("Execution request selected target id={}", target.getId());
        pendingTarget = target.getId();
        pendingTick = minecraft.player.tickCount;
        ExecutionNetwork.request(pendingTarget);
        return true;
    }

    private static LivingEntity pickTarget(Minecraft minecraft) {
        // The displayed crosshair is picked with the render camera and interpolated view.
        // A second, non-interpolated tick ray can miss a mob that is visibly selected.
        if (minecraft.hitResult instanceof EntityHitResult renderedHit
                && renderedHit.getEntity() instanceof LivingEntity target
                && target.level() == minecraft.level && target.isAlive()
                && target.isPickable() && !target.isSpectator()) {
            return target;
        }
        // Extend vanilla's interaction reach only when the displayed crosshair has no entity.
        // The server still validates its configured range, visibility and target allowlist.
        Vec3 eye = minecraft.player.getEyePosition();
        Vec3 reach = minecraft.player.getLookAngle().scale(MAX_TARGET_RANGE);
        Vec3 end = eye.add(reach);
        HitResult block = minecraft.level.clip(new ClipContext(eye, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
        if (block.getType() != HitResult.Type.MISS) end = block.getLocation();
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(minecraft.player, eye, end,
                minecraft.player.getBoundingBox().expandTowards(reach).inflate(1),
                entity -> entity instanceof LivingEntity living && living.isAlive()
                        && entity.isPickable() && !entity.isSpectator(), eye.distanceToSqr(end));
        return hit != null && hit.getEntity() instanceof LivingEntity living ? living : null;
    }

    public static void acceptAction(net.minecraft.resources.ResourceLocation id, String hash, byte[] compressed)
            throws IOException {
        PairedExecutionAction old = ACTIONS.get(id);
        if (old != null && old.contentHash().equals(hash)) return;
        PairedExecutionAction action = PairedExecutionAction.decodeClient(id.toString(), compressed, hash);
        ACTIONS.put(id, action);
    }

    private static void acceptActionPayload(ExecutionNetwork.ActionSync payload) {
        try {
            acceptAction(payload.actionId(), payload.contentHash(), payload.compressedData());
        } catch (IOException exception) {
            LOGGER.error("Unable to load synchronized execution action {}", payload.actionId(), exception);
        }
    }

    private static void start(ExecutionNetwork.Start payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().location().equals(payload.dimension())) return;
        PairedExecutionAction action = ACTIONS.get(payload.actionId());
        if (action == null || !action.contentHash().equals(payload.actionHash())
                || payload.durationTicks() != action.durationTicks()) {
            LOGGER.error("Server execution action {} is missing or its duration differs", payload.actionId());
            pendingTarget = -1;
            return;
        }
        // Broadcast and tracking sync may both announce the same session.
        // Keep its captured approach pose, return state and original camera.
        if (PLAYING.containsKey(payload.sessionId())) return;
        Playing playing = new Playing(payload, action);
        PLAYING.put(payload.sessionId(), playing);
        if (minecraft.player != null && minecraft.player.getUUID().equals(payload.executorUuid())) {
            pendingTarget = -1;
            FiercerBattlesClient.cancelAttackForExecution(minecraft);
            previousCamera = minecraft.options.getCameraType();
            cameraSession = payload.sessionId();
            cameraPullback = previousCamera.isFirstPerson();
            cameraStartNanos = Util.getNanos();
            // Keep the current view independently of the paired actors' fixed yaw.
            cameraYaw = minecraft.player.getYRot() + (previousCamera == CameraType.THIRD_PERSON_FRONT ? 180 : 0);
            cameraPitch = minecraft.player.getXRot() * (previousCamera == CameraType.THIRD_PERSON_FRONT ? -1 : 1);
            minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
        align(playing);
    }

    private static void reject(ExecutionNetwork.Reject payload) {
        if (pendingTarget != payload.targetEntityId()) return;
        pendingTarget = -1;
    }

    private static void end(ExecutionNetwork.End payload) {
        Playing playing = PLAYING.get(payload.sessionId());
        Minecraft minecraft = Minecraft.getInstance();
        if (playing != null && payload.completed() && minecraft.level != null
                && playing.start.dimension().equals(minecraft.level.dimension().location())) {
            playing.ended = true;
            playing.visualEnd = minecraft.level.getGameTime() + 20;
            playing.returnStart = minecraft.level.getGameTime();
            playing.returnYawDelta = Mth.wrapDegrees(payload.executorYaw() - playing.start.executorYaw());
            // The clip moves the hips while entity anchors stay fixed. Transfer that
            // motion into the gameplay position before ordinary posing resumes.
            align(minecraft.level.getEntity(playing.start.executorId()), playing.start.executorUuid(),
                    payload.executorX(), payload.executorY(), payload.executorZ(), payload.executorYaw());
        } else {
            PLAYING.remove(payload.sessionId());
            restoreCamera(payload.sessionId());
        }
    }

    public static Actor actor(LivingEntity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return null;
        for (Playing playing : PLAYING.values()) {
            ExecutionNetwork.Start start = playing.start;
            if (!start.dimension().equals(minecraft.level.dimension().location())) continue;
            if (entity.getId() == start.executorId() && entity.getUUID().equals(start.executorUuid())) {
                if (playing.ended) {
                    if (minecraft.level.getGameTime() >= playing.returnStart + RETURN_TICKS) continue;
                    return new Actor(new AnimationPlayer(playing.action.executorClip(), start.startGameTime()),
                            playing.action, entity.yBodyRot, entity.getX(), entity.getY(), entity.getZ(),
                            start.durationTicks(), true, true,
                            playing.returnStart, playing.returnYawDelta,
                            start.executorFromX(), start.executorFromY(), start.executorFromZ(), start.executorFromYaw(),
                            null);
                }
                return new Actor(new AnimationPlayer(playing.action.executorClip(), start.startGameTime()),
                        playing.action, start.executorYaw(), start.executorX(), start.executorY(), start.executorZ(),
                        start.durationTicks(), false, false, 0, 0,
                        start.executorFromX(), start.executorFromY(), start.executorFromZ(), start.executorFromYaw(),
                        null);
            }
            // Without a victim clip the victim draws its own half (a boss, ExecutionTarget); it is still aligned.
            if (entity.getId() == start.victimId() && entity.getUUID().equals(start.victimUuid())
                    && playing.action.victimClip() != null) {
                return new Actor(new AnimationPlayer(playing.action.victimClip(), start.startGameTime()),
                        playing.action, start.victimYaw(), start.victimX(), start.victimY(), start.victimZ(),
                        start.durationTicks(), playing.ended, false, 0, 0,
                        start.victimFromX(), start.victimFromY(), start.victimFromZ(), start.victimFromYaw(),
                        playing.victimStartPose);
            }
        }
        return null;
    }

    public static boolean isExecuting(Entity entity) {
        if (!(entity instanceof LivingEntity living)) return false;
        Actor actor = actor(living);
        return actor != null && (!actor.ended() || actor.returning());
    }

    public static boolean blocksInput() {
        LocalPlayer player = Minecraft.getInstance().player;
        return pendingTarget != -1 || (player != null && isExecuting(player));
    }

    public static boolean controlsCamera(Entity entity) {
        return cameraSession != null && entity == Minecraft.getInstance().player && isExecuting(entity);
    }

    /** MouseHandler has already applied sensitivity, smoothing and inverted Y. */
    public static void turnCamera(double yaw, double pitch) {
        cameraYaw = Mth.wrapDegrees(cameraYaw + (float) (yaw * 0.15));
        cameraPitch = Mth.clamp(cameraPitch + (float) (pitch * 0.15), -90, 90);
    }

    public static float cameraDistance(float distance) {
        if (!cameraPullback) return distance;
        float progress = Mth.clamp((float) (Util.getNanos() - cameraStartNanos) / CAMERA_TRANSITION_NANOS, 0, 1);
        return distance * progress * progress * (3 - 2 * progress);
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!controlsCamera(event.getCamera().getEntity())) return;
        boolean front = Minecraft.getInstance().options.getCameraType() == CameraType.THIRD_PERSON_FRONT;
        event.setYaw(cameraYaw - (front ? 180 : 0));
        event.setPitch(front ? -cameraPitch : cameraPitch);
        event.setRoll(0);
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) { reset(); return; }
        if (pendingTarget != -1 && minecraft.player.tickCount - pendingTick > 40) pendingTarget = -1;
        if (controlsCamera(minecraft.player)) minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        Iterator<Map.Entry<UUID, Playing>> iterator = PLAYING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Playing> entry = iterator.next();
            Playing playing = entry.getValue();
            ExecutionNetwork.Start start = playing.start;
            if (playing.ended && minecraft.level.getGameTime() >= playing.returnStart + RETURN_TICKS) {
                restoreCamera(entry.getKey());
            }
            boolean expired = playing.ended ? minecraft.level.getGameTime() >= playing.visualEnd
                    : minecraft.level.getGameTime() > start.startGameTime() + start.durationTicks() + 10;
            if (expired || !start.dimension().equals(minecraft.level.dimension().location())) {
                restoreCamera(entry.getKey());
                iterator.remove();
            } else if (!playing.ended) {
                align(playing);
            }
        }
    }

    private static void align(Playing playing) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        ExecutionNetwork.Start start = playing.start;
        long gameTime = minecraft.level.getGameTime();
        float progress = ExecutionTimeline.approachProgress(start.startGameTime(), gameTime, 0);
        float previousProgress = ExecutionTimeline.approachProgress(start.startGameTime(), gameTime - 1, 0);
        Vec3 executorFrom = new Vec3(start.executorFromX(), start.executorFromY(), start.executorFromZ());
        Vec3 executorAnchor = new Vec3(start.executorX(), start.executorY(), start.executorZ());
        Vec3 victimFrom = new Vec3(start.victimFromX(), start.victimFromY(), start.victimFromZ());
        Vec3 victimAnchor = new Vec3(start.victimX(), start.victimY(), start.victimZ());
        align(minecraft.level.getEntity(start.executorId()), start.executorUuid(),
                ExecutionTimeline.approachPosition(executorFrom, executorAnchor, progress),
                ExecutionTimeline.approachPosition(executorFrom, executorAnchor, previousProgress),
                Mth.rotLerp(progress, start.executorFromYaw(), start.executorYaw()),
                Mth.rotLerp(previousProgress, start.executorFromYaw(), start.executorYaw()),
                Mth.lerp(progress, playing.executorFromPitch, 0),
                Mth.rotLerp(progress, playing.executorFromHeadYaw, start.executorYaw()));
        align(minecraft.level.getEntity(start.victimId()), start.victimUuid(),
                ExecutionTimeline.approachPosition(victimFrom, victimAnchor, progress),
                ExecutionTimeline.approachPosition(victimFrom, victimAnchor, previousProgress),
                Mth.rotLerp(progress, start.victimFromYaw(), start.victimYaw()),
                Mth.rotLerp(previousProgress, start.victimFromYaw(), start.victimYaw()),
                Mth.lerp(progress, playing.victimFromPitch, 0),
                Mth.rotLerp(progress, playing.victimFromHeadYaw, start.victimYaw()));
    }

    private static void align(Entity entity, UUID uuid, double x, double y, double z, float yaw) {
        Vec3 position = new Vec3(x, y, z);
        align(entity, uuid, position, position, yaw, yaw, 0, yaw);
    }

    private static void align(Entity entity, UUID uuid, Vec3 position, Vec3 previousPosition,
                              float yaw, float previousYaw, float pitch, float headYaw) {
        if (!(entity instanceof LivingEntity living) || !entity.getUUID().equals(uuid)) return;
        living.setPos(position);
        living.xo = living.xOld = previousPosition.x;
        living.yo = living.yOld = previousPosition.y;
        living.zo = living.zOld = previousPosition.z;
        living.setDeltaMovement(Vec3.ZERO);
        living.setYRot(yaw);
        living.setXRot(pitch);
        living.yRotO = previousYaw;
        living.xRotO = pitch;
        living.yBodyRot = yaw;
        living.yBodyRotO = previousYaw;
        living.yHeadRot = living.yHeadRotO = headYaw;
        living.setShiftKeyDown(false);
        living.setSprinting(false);
    }

    private static void restoreCamera(UUID session) {
        if (!session.equals(cameraSession)) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (previousCamera != null && minecraft.options.getCameraType() == CameraType.THIRD_PERSON_BACK) {
            minecraft.options.setCameraType(previousCamera);
        }
        previousCamera = null;
        cameraSession = null;
        cameraPullback = false;
    }

    public static void reset() {
        if (cameraSession != null) restoreCamera(cameraSession);
        PLAYING.clear();
        pendingTarget = -1;
        ExecutionRenderContext.clear();
    }

    public static void clearActionCache() {
        ACTIONS.clear();
        ExecutionHighlight.clearRules();
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!isExecuting(event.getEntity())) return;
        var input = event.getInput();
        input.forwardImpulse = input.leftImpulse = 0;
        input.up = input.down = input.left = input.right = false;
        input.jumping = input.shiftKeyDown = false;
    }

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (blocksInput()) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }
}
