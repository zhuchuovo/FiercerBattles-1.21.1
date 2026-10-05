package com.fiercerbattles;

import com.fiercerbattles.config.FiercerBattlesConfig;
import com.fiercerbattles.execution.ExecutionGeometry;
import com.fiercerbattles.execution.ExecutionNetwork;
import com.fiercerbattles.execution.ExecutionPlacement;
import com.fiercerbattles.execution.action.ExecutionActionRegistry;
import com.fiercerbattles.execution.action.PairedExecutionAction;
import com.fiercerbattles.mixin.ExecutionLivingEntityInvoker;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityInvulnerabilityCheckEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Owns both actors, their fixed world anchors and the single finishing damage on the server. */
@EventBusSubscriber(modid = FiercerBattles.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class FiercerBattlesExecutionServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int DURATION_TICKS = ExecutionGeometry.DURATION_TICKS;
    private static final String RECOVERY_TAG = "fiercerbattles:execution_restore";
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Session> ACTORS = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<ResourceLocation, String>> SYNCED_ACTIONS = new HashMap<>();
    private static final ThreadLocal<Boolean> EXECUTION_DAMAGE = ThreadLocal.withInitial(() -> false);

    private FiercerBattlesExecutionServer() {}

    public static boolean isExecuting(Entity entity) {
        if (entity == null || entity.level().isClientSide()) {
            return false;
        }
        Session session = ACTORS.get(entity.getUUID());
        // Check object identity as well: a respawn can reuse the player's UUID.
        return session != null && (session.executor == entity || session.victim == entity);
    }

    public static boolean isExecutionDamage() {
        return EXECUTION_DAMAGE.get();
    }

    public static boolean tryStart(ServerPlayer executor, int targetEntityId) {
        if (isExecuting(executor)) {
            return false;
        }
        if (!start(executor, targetEntityId)) {
            PacketDistributor.sendToPlayer(executor, new ExecutionNetwork.Reject(targetEntityId));
            return false;
        }
        return true;
    }

    private static boolean start(ServerPlayer executor, int targetEntityId) {
        FiercerBattlesConfig.Execution config = FiercerBattlesConfig.getExecution();
        ServerLevel level = executor.serverLevel();
        PairedExecutionAction action = ExecutionActionRegistry.get(config.action_id);
        if (action == null) {
            LOGGER.warn("Execution action {} is not registered in the loaded datapacks", config.action_id);
            return false;
        }
        Entity entity = level.getEntity(targetEntityId);
        if (!config.enabled || !executor.isAlive() || executor.isSpectator() || executor.hasDisconnected()
                || !(entity instanceof Mob victim) || !victim.isAlive()
                || victim.isInvulnerable() || isExecuting(victim) || !config.allows(victim)
                || !eligiblePose(executor) || !eligiblePose(victim) || victim.isBaby()) {
            LOGGER.debug("Execution rejected: ineligible target {}, player pose={}, grounded={}",
                    entity, executor.getPose(), executor.onGround());
            if (entity instanceof LivingEntity target) {
                LOGGER.debug("Target state: alive={}, allowed={}, pose={}, grounded={}, water={}, dimensions={}x{}, baby={}, invulnerable={}",
                        target.isAlive(), config.allows(target), target.getPose(), target.onGround(), target.isInWaterOrBubble(),
                        target.getBbWidth(), target.getBbHeight(), target.isBaby(), target.isInvulnerable());
            }
            return false;
        }
        float maxHealth = victim.getMaxHealth();
        if (!(maxHealth > 0) || !(victim.getHealth() < maxHealth * config.healthThreshold())
                || !ExecutionPlacement.canReach(executor, victim, config.range())) {
            LOGGER.debug("Execution rejected: health={}/{}, body distance={}, range={}",
                    victim.getHealth(), maxHealth, Math.sqrt(victim.getBoundingBox().distanceToSqr(executor.getEyePosition())),
                    config.range());
            return false;
        }
        ExecutionPlacement.Placement placement = ExecutionPlacement.find(level, executor, victim, action.geometry());
        if (placement == null) {
            LOGGER.debug("Execution rejected: no safe supported placement near target {}, foot height difference={}",
                    victim.position(), executor.getY() - victim.getY());
            return false;
        }
        Vec3 executorAnchor = placement.executorAnchor();
        Vec3 victimAnchor = placement.victimAnchor();
        float executorYaw = placement.executorYaw();
        float victimYaw = placement.victimYaw();

        ExecutionNetwork.ActionSync actionSync;
        try {
            actionSync = ExecutionNetwork.ActionSync.from(action);
        } catch (java.io.IOException exception) {
            LOGGER.error("Unable to synchronize execution action {} to clients", action.id(), exception);
            return false;
        }

        Session session = new Session(level, executor, victim, executorAnchor, victimAnchor, executorYaw, victimYaw,
                action, config);
        SESSIONS.put(session.id, session);
        ACTORS.put(executor.getUUID(), session);
        ACTORS.put(victim.getUUID(), session);
        saveRecovery(executor, session.executorNoGravity, false);
        saveRecovery(victim, session.victimNoGravity, session.victimNoAi);
        victim.getNavigation().stop();
        victim.setNoAi(true);
        executor.setNoGravity(true);
        victim.setNoGravity(true);
        executor.stopUsingItem();
        victim.stopUsingItem();
        lock(session, executor);
        lock(session, victim);
        // Vanilla teleport acknowledgement still runs; movement packets are ignored during the clip.
        executor.connection.teleport(executorAnchor.x, executorAnchor.y, executorAnchor.z, executorYaw, 0);
        for (ServerPlayer viewer : level.players()) syncAction(viewer, action, actionSync);
        PacketDistributor.sendToPlayersInDimension(level, session.payload());
        playFrameSounds(session);
        return true;
    }

    private static void syncAction(ServerPlayer player, PairedExecutionAction action, ExecutionNetwork.ActionSync packet) {
        Map<ResourceLocation, String> synced = SYNCED_ACTIONS.computeIfAbsent(player.getUUID(), ignored -> new HashMap<>());
        if (action.contentHash().equals(synced.get(action.id()))) return;
        PacketDistributor.sendToPlayer(player, packet);
        synced.put(action.id(), action.contentHash());
    }

    private static boolean eligiblePose(LivingEntity entity) {
        return entity.getPose() == Pose.STANDING && grounded(entity)
                && !entity.isPassenger() && !entity.isVehicle() && !entity.isSleeping()
                && !entity.isInWaterOrBubble() && !entity.isInLava()
                && !entity.isSwimming() && !entity.isFallFlying()
                && entity.getBbWidth() >= 0.4F && entity.getBbWidth() <= 1.0F
                && entity.getBbHeight() >= 1.4F && entity.getBbHeight() <= 2.6F;
    }

    private static boolean grounded(LivingEntity entity) {
        if (entity.onGround()) return true;
        if (!(entity.level() instanceof ServerLevel level) || entity.getDeltaMovement().y > 0.001) return false;
        AABB box = entity.getBoundingBox();
        // Vanilla can clear onGround on an AI movement tick even while the feet
        // still touch the floor. Use actual support, while rejecting jump takeoff.
        AABB feet = new AABB(box.minX + 0.05, box.minY - 0.01, box.minZ + 0.05,
                box.maxX - 0.05, box.minY + 1.0E-5, box.maxZ - 0.05);
        return !level.noBlockCollision(entity, feet);
    }

    private static void saveRecovery(LivingEntity entity, boolean noGravity, boolean noAi) {
        CompoundTag recovery = new CompoundTag();
        recovery.putBoolean("no_gravity", noGravity);
        recovery.putBoolean("no_ai", noAi);
        entity.getPersistentData().put(RECOVERY_TAG, recovery);
    }

    private static void lock(Session session, LivingEntity actor) {
        if (actor.level() != session.level || actor.isRemoved() || !actor.isAlive()) {
            return;
        }
        boolean executor = actor == session.executor;
        Vec3 anchor = executor ? session.executorAnchor : session.victimAnchor;
        float yaw = executor ? session.executorYaw : session.victimYaw;
        actor.setPos(anchor);
        actor.setYRot(yaw);
        actor.setXRot(0);
        actor.yRotO = yaw;
        actor.xRotO = 0;
        actor.yBodyRot = actor.yBodyRotO = yaw;
        actor.yHeadRot = actor.yHeadRotO = yaw;
        actor.setDeltaMovement(Vec3.ZERO);
        actor.xxa = actor.yya = actor.zza = 0;
        actor.setJumping(false);
        actor.setSprinting(false);
        actor.setShiftKeyDown(false);
        actor.setSwimming(false);
        actor.setPose(Pose.STANDING);
        actor.setOnGround(true);
        actor.resetFallDistance();
        if (actor instanceof ServerPlayer player) {
            player.connection.resetPosition();
        }
    }

    private static void lockActor(Entity entity) {
        if (isExecuting(entity) && entity instanceof LivingEntity actor) {
            lock(ACTORS.get(actor.getUUID()), actor);
        }
    }

    @SubscribeEvent public static void beforeEntityTick(EntityTickEvent.Pre event) { lockActor(event.getEntity()); }
    @SubscribeEvent public static void afterEntityTick(EntityTickEvent.Post event) { lockActor(event.getEntity()); }
    @SubscribeEvent public static void beforePlayerTick(PlayerTickEvent.Pre event) { lockActor(event.getEntity()); }
    @SubscribeEvent public static void afterPlayerTick(PlayerTickEvent.Post event) { lockActor(event.getEntity()); }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (Session session : List.copyOf(SESSIONS.values())) {
            if (!session.executor.isAlive() || session.executor.isRemoved() || session.executor.hasDisconnected()
                    || session.executor.isSpectator() || !session.victim.isAlive() || session.victim.isRemoved()
                    || session.executor.level() != session.level || session.victim.level() != session.level
                    || session.executor.isPassenger() || session.victim.isPassenger()
                    || !session.level.noBlockCollision(session.executor, session.executor.getBoundingBox().deflate(1.0E-4))
                    || !session.level.noBlockCollision(session.victim, session.victim.getBoundingBox().deflate(1.0E-4))
                    || !ExecutionPlacement.supported(session.level, session.executor, session.executor.getBoundingBox())
                    || !ExecutionPlacement.supported(session.level, session.victim, session.victim.getBoundingBox())
                    || !ExecutionPlacement.clearStage(session.level, session.executor, session.executor.getBoundingBox(), session.victim.getBoundingBox())) {
                end(session, false);
                continue;
            }
            lock(session, session.executor);
            lock(session, session.victim);
            playFrameSounds(session);
            if (session.level.getGameTime() >= session.startGameTime + session.action.durationTicks()) {
                finish(session);
            }
        }
    }

    private static void playFrameSounds(Session session) {
        // Source animation runs at 20 fps: one source frame is one server tick.
        int currentFrame = (int) Mth.clamp(session.level.getGameTime() - session.startGameTime, 0,
                session.action.durationTicks());
        for (int frame = session.lastSoundFrame + 1; frame <= currentFrame; frame++) {
            if (session.swingSoundFrames.contains(frame)) {
                session.executor.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 1.0F);
            }
            if (session.hurtSoundFrames.contains(frame)) {
                DamageSource source = session.level.damageSources().playerAttack(session.executor);
                session.victim.makeSound(((ExecutionLivingEntityInvoker) session.victim).fiercerbattles$getHurtSound(source));
            }
        }
        session.lastSoundFrame = currentFrame;
    }

    private static void finish(Session session) {
        if (!ExecutionPlacement.safeFinishPlacement(session.level, session.executor, session.victim,
                session.executor.getBoundingBox(), session.executorAnchor, session.executorYaw,
                session.action.geometry())) {
            end(session, false);
            return;
        }
        EXECUTION_DAMAGE.set(true);
        try {
            DamageSource source = new DamageSource(session.level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(DamageTypes.GENERIC_KILL), session.executor);
            session.victim.hurt(source, Float.MAX_VALUE);
        } finally {
            EXECUTION_DAMAGE.remove();
            end(session, !session.victim.isAlive());
        }
    }

    private static void end(Session session, boolean completed) {
        if (SESSIONS.remove(session.id) == null) {
            return;
        }
        ACTORS.remove(session.executor.getUUID(), session);
        ACTORS.remove(session.victim.getUUID(), session);
        session.executor.setNoGravity(session.executorNoGravity);
        session.victim.setNoGravity(session.victimNoGravity);
        session.victim.setNoAi(session.victimNoAi);
        session.executor.getPersistentData().remove(RECOVERY_TAG);
        session.victim.getPersistentData().remove(RECOVERY_TAG);
        session.executor.setDeltaMovement(Vec3.ZERO);
        session.victim.setDeltaMovement(Vec3.ZERO);
        if (completed && session.executor.isAlive() && !session.executor.hasDisconnected()
                && session.executor.level() == session.level) {
            Vec3 finishPosition = session.action.geometry().executorEndPosition(session.executorAnchor, session.executorYaw);
            float finishYaw = session.action.geometry().executorEndYaw(session.executorYaw);
            session.executor.connection.teleport(finishPosition.x, finishPosition.y, finishPosition.z, finishYaw, 0);
            session.executor.yBodyRot = session.executor.yBodyRotO = finishYaw;
            session.executor.yHeadRot = session.executor.yHeadRotO = finishYaw;
            session.executor.resetFallDistance();
        }
        PacketDistributor.sendToPlayersInDimension(session.level, new ExecutionNetwork.End(session.id, completed,
                session.executor.getX(), session.executor.getY(), session.executor.getZ(), session.executor.getYRot()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onInvulnerabilityCheck(EntityInvulnerabilityCheckEvent event) {
        // Session membership expires on completion or abort; no persistent player flag needs restoring.
        if (event.getEntity() instanceof ServerPlayer && isExecuting(event.getEntity())) {
            event.setInvulnerable(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        boolean executingPlayer = event.getEntity() instanceof ServerPlayer && isExecuting(event.getEntity());
        if (executingPlayer || (!isExecutionDamage()
                && (isExecuting(event.getEntity()) || isExecuting(event.getSource().getEntity())))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKnockback(LivingKnockBackEvent event) {
        if (isExecuting(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(AttackEntityEvent event) {
        if (isExecuting(event.getEntity()) || isExecuting(event.getTarget())) {
            event.setCanceled(true);
        }
    }

    private static void cancelInteraction(PlayerInteractEvent event) {
        if (isExecuting(event.getEntity()) && event instanceof ICancellableEvent cancellable) {
            cancellable.setCanceled(true);
            if (event instanceof PlayerInteractEvent.RightClickItem item) item.setCancellationResult(InteractionResult.FAIL);
            if (event instanceof PlayerInteractEvent.RightClickBlock block) block.setCancellationResult(InteractionResult.FAIL);
            if (event instanceof PlayerInteractEvent.EntityInteract entity) entity.setCancellationResult(InteractionResult.FAIL);
            if (event instanceof PlayerInteractEvent.EntityInteractSpecific entity) entity.setCancellationResult(InteractionResult.FAIL);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) { cancelInteraction(event); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) { cancelInteraction(event); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) { cancelInteraction(event); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) { cancelInteraction(event); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) { cancelInteraction(event); }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!isExecutionDamage()) {
            abort(event.getEntity());
        }
    }

    @SubscribeEvent public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SYNCED_ACTIONS.remove(event.getEntity().getUUID());
        abort(event.getEntity());
    }
    @SubscribeEvent public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) { abort(event.getEntity()); }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer player && isExecuting(event.getTarget())) {
            Session session = ACTORS.get(event.getTarget().getUUID());
            try {
                syncAction(player, session.action, ExecutionNetwork.ActionSync.from(session.action));
                PacketDistributor.sendToPlayer(player, session.payload());
            } catch (java.io.IOException exception) {
                LOGGER.error("Unable to synchronize tracked execution action {}", session.action.id(), exception);
            }
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof LivingEntity entity
                && entity.getPersistentData().contains(RECOVERY_TAG)) {
            CompoundTag recovery = entity.getPersistentData().getCompound(RECOVERY_TAG);
            entity.setNoGravity(recovery.getBoolean("no_gravity"));
            if (entity instanceof Mob mob) mob.setNoAi(recovery.getBoolean("no_ai"));
            entity.getPersistentData().remove(RECOVERY_TAG);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (Session session : List.copyOf(SESSIONS.values())) {
            end(session, false);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SYNCED_ACTIONS.clear();
        for (Session session : List.copyOf(SESSIONS.values())) {
            session.executor.setNoGravity(session.executorNoGravity);
            session.victim.setNoGravity(session.victimNoGravity);
            session.victim.setNoAi(session.victimNoAi);
            session.executor.getPersistentData().remove(RECOVERY_TAG);
            session.victim.getPersistentData().remove(RECOVERY_TAG);
        }
        SESSIONS.clear();
        ACTORS.clear();
    }

    private static void abort(Entity entity) {
        Session session = ACTORS.get(entity.getUUID());
        if (session != null) end(session, false);
    }

    private static final class Session {
        final UUID id = UUID.randomUUID();
        final ServerLevel level;
        final ServerPlayer executor;
        final Mob victim;
        final Vec3 executorAnchor;
        final Vec3 victimAnchor;
        final float executorYaw;
        final float victimYaw;
        final PairedExecutionAction action;
        final long startGameTime;
        final boolean executorNoGravity;
        final boolean victimNoGravity;
        final boolean victimNoAi;
        final List<Integer> hurtSoundFrames;
        final List<Integer> swingSoundFrames;
        int lastSoundFrame = -1;

        Session(ServerLevel level, ServerPlayer executor, Mob victim, Vec3 executorAnchor, Vec3 victimAnchor,
                float executorYaw, float victimYaw, PairedExecutionAction action, FiercerBattlesConfig.Execution config) {
            this.level = level;
            this.executor = executor;
            this.victim = victim;
            this.executorAnchor = executorAnchor;
            this.victimAnchor = victimAnchor;
            this.executorYaw = executorYaw;
            this.victimYaw = victimYaw;
            this.action = action;
            this.startGameTime = level.getGameTime();
            this.executorNoGravity = executor.isNoGravity();
            this.victimNoGravity = victim.isNoGravity();
            this.victimNoAi = victim.isNoAi();
            this.hurtSoundFrames = config.hurtSoundFrames(action.durationTicks());
            this.swingSoundFrames = config.swingSoundFrames(action.durationTicks());
        }

        ExecutionNetwork.Start payload() {
        return new ExecutionNetwork.Start(id, action.id(), action.contentHash(), executor.getId(), executor.getUUID(), victim.getId(), victim.getUUID(),
                    level.dimension().location(), startGameTime, action.durationTicks(),
                    executorAnchor.x, executorAnchor.y, executorAnchor.z, executorYaw,
                    victimAnchor.x, victimAnchor.y, victimAnchor.z, victimYaw);
        }
    }
}
