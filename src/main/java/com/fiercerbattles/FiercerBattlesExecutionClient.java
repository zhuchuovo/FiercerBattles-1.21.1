package com.fiercerbattles;

import com.fiercerbattles.execution.ExecutionAnimation;
import com.fiercerbattles.execution.ExecutionNetwork;
import com.fiercerbattles.execution.ExecutionRenderContext;
import com.mojang.logging.LogUtils;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = FiercerBattles.MOD_ID, value = Dist.CLIENT)
public final class FiercerBattlesExecutionClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, Playing> PLAYING = new HashMap<>();
    private static ExecutionAnimation executioner;
    private static ExecutionAnimation victim;
    private static int pendingTarget = -1;
    private static int pendingTick;
    private static CameraType previousCamera;
    private static UUID cameraSession;
    public static final int RETURN_TICKS = 4;

    private static final class Playing {
        final ExecutionNetwork.Start start;
        boolean ended;
        long visualEnd;
        long returnStart;
        float returnYawDelta;

        Playing(ExecutionNetwork.Start start) { this.start = start; }
    }

    public record Actor(ExecutionAnimation animation, float yaw, double x, double y, double z,
                        long startTick, int duration, boolean ended, boolean returning,
                        long returnStart, float returnYawDelta) {
        public float tick(float partial) {
            Minecraft minecraft = Minecraft.getInstance();
            if (ended || minecraft.level == null) return duration;
            return Math.max(0, Math.min(duration, minecraft.level.getGameTime() - startTick + partial));
        }

        public float returnProgress(float partial) {
            Minecraft minecraft = Minecraft.getInstance();
            if (!returning || minecraft.level == null) return 0;
            return Mth.clamp((minecraft.level.getGameTime() - returnStart + partial) / RETURN_TICKS, 0, 1);
        }
    }

    private FiercerBattlesExecutionClient() {}

    public static void initialize() {
        ExecutionNetwork.setClientHandlers(FiercerBattlesExecutionClient::start,
                FiercerBattlesExecutionClient::end, FiercerBattlesExecutionClient::reject);
    }

    /** Let the server decide the allowlist; a rejected request resumes the ordinary skill. */
    public static boolean tryRequest(Minecraft minecraft) {
        if (blocksInput()) return true;
        if (minecraft.player == null || minecraft.level == null || minecraft.screen != null) return false;
        if (!(minecraft.hitResult instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof LivingEntity target) || !target.isAlive()) return false;
        if (!loadAnimations()) return false;
        pendingTarget = target.getId();
        pendingTick = minecraft.player.tickCount;
        ExecutionNetwork.request(pendingTarget);
        return true;
    }

    private static boolean loadAnimations() {
        if (executioner != null && victim != null) return true;
        try {
            executioner = ExecutionAnimation.load("executioner");
            victim = ExecutionAnimation.load("victim");
            return true;
        } catch (IOException exception) {
            LOGGER.error("Unable to load the paired execution animation", exception);
            executioner = null;
            victim = null;
            return false;
        }
    }

    private static void start(ExecutionNetwork.Start payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().location().equals(payload.dimension())) return;
        if (!loadAnimations() || payload.durationTicks() != executioner.duration()
                || payload.durationTicks() != victim.duration()) {
            LOGGER.error("Server execution duration does not match the paired client animation");
            pendingTarget = -1;
            return;
        }
        PLAYING.put(payload.sessionId(), new Playing(payload));
        if (minecraft.player != null && minecraft.player.getUUID().equals(payload.executorUuid())) {
            pendingTarget = -1;
            FiercerBattlesClient.cancelAttackForExecution(minecraft);
            if (minecraft.options.getCameraType() == CameraType.FIRST_PERSON) {
                previousCamera = CameraType.FIRST_PERSON;
                cameraSession = payload.sessionId();
                minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }
        }
        align(payload);
    }

    private static void reject(ExecutionNetwork.Reject payload) {
        if (pendingTarget != payload.targetEntityId()) return;
        pendingTarget = -1;
        Minecraft minecraft = Minecraft.getInstance();
        if (!blocksInput()) FiercerBattlesClient.resumeCombatSkill(minecraft);
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
        }
        restoreCamera(payload.sessionId());
    }

    public static Actor actor(LivingEntity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || executioner == null || victim == null) return null;
        for (Playing playing : PLAYING.values()) {
            ExecutionNetwork.Start start = playing.start;
            if (!start.dimension().equals(minecraft.level.dimension().location())) continue;
            if (entity.getId() == start.executorId() && entity.getUUID().equals(start.executorUuid())) {
                if (playing.ended) {
                    if (minecraft.level.getGameTime() >= playing.returnStart + RETURN_TICKS) continue;
                    return new Actor(executioner, entity.yBodyRot, entity.getX(), entity.getY(), entity.getZ(),
                            start.startGameTime(), start.durationTicks(), true, true,
                            playing.returnStart, playing.returnYawDelta);
                }
                return new Actor(executioner, start.executorYaw(), start.executorX(), start.executorY(), start.executorZ(),
                        start.startGameTime(), start.durationTicks(), false, false, 0, 0);
            }
            if (entity.getId() == start.victimId() && entity.getUUID().equals(start.victimUuid())) {
                return new Actor(victim, start.victimYaw(), start.victimX(), start.victimY(), start.victimZ(),
                        start.startGameTime(), start.durationTicks(), playing.ended, false, 0, 0);
            }
        }
        return null;
    }

    public static boolean isExecuting(Entity entity) {
        if (!(entity instanceof LivingEntity living)) return false;
        Actor actor = actor(living);
        return actor != null && !actor.ended();
    }

    public static boolean blocksInput() {
        LocalPlayer player = Minecraft.getInstance().player;
        return pendingTarget != -1 || (player != null && isExecuting(player));
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) { reset(); return; }
        if (pendingTarget != -1 && minecraft.player.tickCount - pendingTick > 40) pendingTarget = -1;
        Iterator<Map.Entry<UUID, Playing>> iterator = PLAYING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Playing> entry = iterator.next();
            Playing playing = entry.getValue();
            ExecutionNetwork.Start start = playing.start;
            boolean expired = playing.ended ? minecraft.level.getGameTime() >= playing.visualEnd
                    : minecraft.level.getGameTime() > start.startGameTime() + start.durationTicks() + 10;
            if (expired || !start.dimension().equals(minecraft.level.dimension().location())) {
                restoreCamera(entry.getKey());
                iterator.remove();
            } else if (!playing.ended) {
                align(start);
            }
        }
    }

    private static void align(ExecutionNetwork.Start start) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        align(minecraft.level.getEntity(start.executorId()), start.executorUuid(),
                start.executorX(), start.executorY(), start.executorZ(), start.executorYaw());
        align(minecraft.level.getEntity(start.victimId()), start.victimUuid(),
                start.victimX(), start.victimY(), start.victimZ(), start.victimYaw());
    }

    private static void align(Entity entity, UUID uuid, double x, double y, double z, float yaw) {
        if (!(entity instanceof LivingEntity living) || !entity.getUUID().equals(uuid)) return;
        living.setPos(x, y, z);
        living.xo = living.xOld = x;
        living.yo = living.yOld = y;
        living.zo = living.zOld = z;
        living.setDeltaMovement(Vec3.ZERO);
        living.setYRot(yaw);
        living.setXRot(0);
        living.yRotO = yaw;
        living.xRotO = 0;
        living.yBodyRot = living.yBodyRotO = yaw;
        living.yHeadRot = living.yHeadRotO = yaw;
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
    }

    public static void reset() {
        if (cameraSession != null) restoreCamera(cameraSession);
        PLAYING.clear();
        pendingTarget = -1;
        executioner = null;
        victim = null;
        ExecutionRenderContext.clear();
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
