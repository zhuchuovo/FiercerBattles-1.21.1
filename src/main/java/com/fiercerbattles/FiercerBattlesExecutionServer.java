package com.fiercerbattles;

import com.fiercerbattles.config.FiercerBattlesConfig;
import com.fiercerbattles.execution.ExecutionGeometry;
import com.fiercerbattles.execution.ExecutionNetwork;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
                || executor.distanceToSqr(victim) > config.range() * config.range()
                || !executor.hasLineOfSight(victim)
                || Math.abs(executor.getY() - victim.getY()) > 0.125) {
            LOGGER.debug("Execution rejected: health={}/{}, distance={}, foot height difference={}, sight={}",
                    victim.getHealth(), maxHealth, executor.distanceTo(victim), executor.getY() - victim.getY(),
                    executor.hasLineOfSight(victim));
            return false;
        }
        Vec3 eye = executor.getEyePosition();
        Vec3 lookEnd = eye.add(executor.getLookAngle().scale(config.range() + 0.5));
        if (victim.getBoundingBox().inflate(0.2).clip(eye, lookEnd).isEmpty()) {
            LOGGER.debug("Execution rejected: target is outside aim ray, yaw={}, pitch={}",
                    executor.getYRot(), executor.getXRot());
            return false;
        }

        Vec3 direction = new Vec3(victim.getX() - executor.getX(), 0, victim.getZ() - executor.getZ());
        if (direction.lengthSqr() < 0.04) {
            return false;
        }
        direction = direction.normalize();
        float towardVictimYaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        // The authored partner is slightly to the side. A generic "face each other" yaw
        // would erase that sideways offset and separate the hand from the victim's body.
        float executorYaw = Mth.wrapDegrees(towardVictimYaw
                - (float) Math.toDegrees(Math.atan2(-ExecutionGeometry.VICTIM_LOCAL_RIGHT,
                        ExecutionGeometry.VICTIM_LOCAL_FORWARD)));
        float victimYaw = Mth.wrapDegrees(executorYaw + 180.0F);
        Vec3 victimAnchor = victim.position();
        Vec3 executorAnchor = victimAnchor.subtract(direction.scale(ExecutionGeometry.SEPARATION));
        AABB executorBox = executor.getBoundingBox().move(executorAnchor.subtract(executor.position()));
        AABB victimBox = victim.getBoundingBox();
        AABB executorSweep = executor.getBoundingBox().expandTowards(executorAnchor.subtract(executor.position()));
        if (!safeBox(level, executor, victim, executorBox) || !safeBox(level, victim, executor, victimBox)
                || !safeBox(level, executor, victim, executorSweep)
                || !clearStage(level, executor, executorBox, victimBox)
                || !supported(level, executor, executorBox) || !supported(level, victim, victimBox)
                || !safeFinishPlacement(level, executor, victim, executorBox, executorAnchor, executorYaw)) {
            LOGGER.debug("Execution rejected: blocked placement or unsupported feet, executor={}, victim={}",
                    executorAnchor, victimAnchor);
            return false;
        }

        Session session = new Session(level, executor, victim, executorAnchor, victimAnchor, executorYaw, victimYaw);
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
        PacketDistributor.sendToPlayersInDimension(level, session.payload());
        return true;
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

    private static boolean safeBox(ServerLevel level, LivingEntity actor, Entity partner, AABB box) {
        if (!level.getWorldBorder().isWithinBounds(box) || !level.hasChunkAt(BlockPos.containing(box.getCenter()))
                || !level.noBlockCollision(actor, box.deflate(1.0E-4)) || level.containsAnyLiquid(box)) {
            return false;
        }
        return level.getEntities(actor, box.deflate(1.0E-4), entity -> entity != partner
                && entity.isAlive() && entity.isPushable()).isEmpty();
    }

    private static boolean supported(ServerLevel level, LivingEntity actor, AABB box) {
        AABB feet = new AABB(box.minX + 0.05, box.minY - 0.075, box.minZ + 0.05,
                box.maxX - 0.05, box.minY + 1.0E-5, box.maxZ - 0.05);
        return !level.noBlockCollision(actor, feet);
    }

    private static boolean clearStage(ServerLevel level, LivingEntity executor, AABB executorBox, AABB victimBox) {
        AABB stage = executorBox.minmax(victimBox);
        // Reserve the space between the actors for reaching arms and torso motion.
        stage = new AABB(stage.minX - 0.25, stage.minY + 0.02, stage.minZ - 0.25,
                stage.maxX + 0.25, stage.maxY + 0.3, stage.maxZ + 0.25);
        return level.getWorldBorder().isWithinBounds(stage) && level.noBlockCollision(executor, stage);
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
                    || !supported(session.level, session.executor, session.executor.getBoundingBox())
                    || !supported(session.level, session.victim, session.victim.getBoundingBox())
                    || !clearStage(session.level, session.executor, session.executor.getBoundingBox(), session.victim.getBoundingBox())) {
                end(session, false);
                continue;
            }
            lock(session, session.executor);
            lock(session, session.victim);
            if (session.level.getGameTime() >= session.startGameTime + DURATION_TICKS) {
                finish(session);
            }
        }
    }

    private static void finish(Session session) {
        if (!safeFinishPlacement(session.level, session.executor, session.victim,
                session.executor.getBoundingBox(), session.executorAnchor, session.executorYaw)) {
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

    private static boolean safeFinishPlacement(ServerLevel level, ServerPlayer executor, Mob victim,
                                               AABB startBox, Vec3 anchor, float yaw) {
        Vec3 displacement = ExecutionGeometry.executorEndPosition(anchor, yaw).subtract(anchor);
        AABB finalBox = startBox.move(displacement);
        AABB sweptBox = startBox.expandTowards(displacement);
        return safeBox(level, executor, victim, finalBox) && safeBox(level, executor, victim, sweptBox)
                && supported(level, executor, finalBox);
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
            Vec3 finishPosition = ExecutionGeometry.executorEndPosition(session.executorAnchor, session.executorYaw);
            float finishYaw = ExecutionGeometry.executorEndYaw(session.executorYaw);
            session.executor.connection.teleport(finishPosition.x, finishPosition.y, finishPosition.z, finishYaw, 0);
            session.executor.yBodyRot = session.executor.yBodyRotO = finishYaw;
            session.executor.yHeadRot = session.executor.yHeadRotO = finishYaw;
            session.executor.resetFallDistance();
        }
        PacketDistributor.sendToPlayersInDimension(session.level, new ExecutionNetwork.End(session.id, completed,
                session.executor.getX(), session.executor.getY(), session.executor.getZ(), session.executor.getYRot()));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!isExecutionDamage() && (isExecuting(event.getEntity()) || isExecuting(event.getSource().getEntity()))) {
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

    @SubscribeEvent public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) { abort(event.getEntity()); }
    @SubscribeEvent public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) { abort(event.getEntity()); }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer player && isExecuting(event.getTarget())) {
            PacketDistributor.sendToPlayer(player, ACTORS.get(event.getTarget().getUUID()).payload());
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
        final long startGameTime;
        final boolean executorNoGravity;
        final boolean victimNoGravity;
        final boolean victimNoAi;

        Session(ServerLevel level, ServerPlayer executor, Mob victim, Vec3 executorAnchor, Vec3 victimAnchor,
                float executorYaw, float victimYaw) {
            this.level = level;
            this.executor = executor;
            this.victim = victim;
            this.executorAnchor = executorAnchor;
            this.victimAnchor = victimAnchor;
            this.executorYaw = executorYaw;
            this.victimYaw = victimYaw;
            this.startGameTime = level.getGameTime();
            this.executorNoGravity = executor.isNoGravity();
            this.victimNoGravity = victim.isNoGravity();
            this.victimNoAi = victim.isNoAi();
        }

        ExecutionNetwork.Start payload() {
            return new ExecutionNetwork.Start(id, executor.getId(), executor.getUUID(), victim.getId(), victim.getUUID(),
                    level.dimension().location(), startGameTime, DURATION_TICKS,
                    executorAnchor.x, executorAnchor.y, executorAnchor.z, executorYaw,
                    victimAnchor.x, victimAnchor.y, victimAnchor.z, victimYaw);
        }
    }
}
