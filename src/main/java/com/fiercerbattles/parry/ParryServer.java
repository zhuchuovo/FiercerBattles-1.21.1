package com.fiercerbattles.parry;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.FiercerBattlesExecutionServer;
import com.fiercerbattles.execution.ExecutionWeapons;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Independent gestures share the animation core, never the paired execution anchors. */
@EventBusSubscriber(modid = FiercerBattles.MOD_ID)
public final class ParryServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RECOVERY_TAG = "fiercerbattles:parry_restore";
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> ATTACK_EPOCHS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> SYNCED = new ConcurrentHashMap<>();

    private ParryServer() {}

    public static boolean isBusy(Entity entity) {
        if (entity == null || entity.level().isClientSide()) return false;
        State state = STATES.get(entity.getUUID());
        return state != null && state.actor == entity;
    }

    public static boolean blocksActions(Entity entity) { return isBusy(entity); }

    public static boolean isStaggered(Entity entity) {
        if (entity == null || entity.level().isClientSide()) return false;
        State state = STATES.get(entity.getUUID());
        return state != null && state.actor == entity && state.kind == ParryNetwork.Kind.STAGGER;
    }

    public static long attackEpoch(UUID actorUuid) { return ATTACK_EPOCHS.getOrDefault(actorUuid, 0L); }

    public static boolean canParry(Entity entity) {
        if (!isBusy(entity)) return false;
        State state = STATES.get(entity.getUUID());
        return state.kind == ParryNetwork.Kind.PARRY && entity instanceof ServerPlayer player
                && ExecutionWeapons.canExecute(player.getMainHandItem())
                && ParryTimeline.canParry(state.startGameTime, state.level.getGameTime());
    }

    public static boolean tryStart(ServerPlayer player) {
        if (!player.isAlive() || player.isRemoved() || player.hasDisconnected() || player.isSpectator()
                || player.isSleeping() || isBusy(player) || FiercerBattlesExecutionServer.isExecuting(player)) {
            return deny(player, "busy");
        }
        if (!ExecutionWeapons.canExecute(player.getMainHandItem())) return deny(player, "weapon");
        ParryAnimations.Clips clips = ParryAnimations.clips();
        if (clips == null) return deny(player, "animations");
        return begin(player, ParryNetwork.Kind.PARRY, clips);
    }

    private static boolean begin(LivingEntity actor, ParryNetwork.Kind kind, ParryAnimations.Clips clips) {
        if (!(actor.level() instanceof ServerLevel level) || !actor.isAlive() || actor.isRemoved()) return false;
        ParryNetwork.Sync sync;
        try { sync = new ParryNetwork.Sync(clips.hash(), ParryAnimations.encode(clips)); }
        catch (IOException exception) {
            LOGGER.error("Cannot synchronize parry animations", exception);
            return false;
        }
        State previous = STATES.get(actor.getUUID());
        if (previous != null) stop(previous);
        State state = new State(actor, level, kind, clips);
        STATES.put(actor.getUUID(), state);
        ATTACK_EPOCHS.merge(actor.getUUID(), 1L, Long::sum);
        actor.stopUsingItem();
        if (kind == ParryNetwork.Kind.STAGGER) {
            CompoundTag recovery = new CompoundTag();
            recovery.putBoolean("no_gravity", state.noGravity);
            recovery.putBoolean("no_ai", state.noAi);
            actor.getPersistentData().put(RECOVERY_TAG, recovery);
            actor.setNoGravity(true);
            if (actor instanceof Mob mob) { mob.getNavigation().stop(); mob.setNoAi(true); }
            lock(state);
        }
        for (ServerPlayer viewer : level.players()) sync(viewer, sync);
        PacketDistributor.sendToPlayersInDimension(level, state.payload());
        LOGGER.info("Parry gesture started: kind={}, actor={}, tick={}", kind, actor.getUUID(), state.startGameTime);
        return true;
    }

    private static boolean deny(ServerPlayer player, String reason) {
        player.displayClientMessage(Component.translatable("message.fiercerbattles.parry_" + reason), true);
        return false;
    }

    private static void sync(ServerPlayer viewer, ParryNetwork.Sync sync) {
        if (sync.hash().equals(SYNCED.get(viewer.getUUID()))) return;
        PacketDistributor.sendToPlayer(viewer, sync);
        SYNCED.put(viewer.getUUID(), sync.hash());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void incomingDamage(LivingIncomingDamageEvent event) {
        if (FiercerBattlesExecutionServer.isExecutionDamage()) return;
        LivingEntity defender = event.getEntity();
        DamageSource source = event.getSource();
        if (!(source.getEntity() instanceof LivingEntity attacker)
                || FiercerBattlesExecutionServer.isExecuting(defender)
                || FiercerBattlesExecutionServer.isExecuting(attacker)) return;
        // The current multi-target task may already be inside its damage loop.
        // Refuse its remaining hits while allowing that task to clean up attributes.
        if (blocksActions(attacker)) { event.setCanceled(true); return; }
        if (attacker == defender || !canParry(defender) || !isMelee(source, attacker)) return;
        State gesture = STATES.get(defender.getUUID());
        event.setCanceled(true);
        begin(attacker, ParryNetwork.Kind.STAGGER, gesture.clips);
        defender.playSound(SoundEvents.SHIELD_BLOCK, 1, 1);
        if (defender instanceof ServerPlayer player) {
            player.displayClientMessage(Component.translatable("message.fiercerbattles.parry_success"), true);
        }
    }

    private static boolean isMelee(DamageSource source, LivingEntity attacker) {
        return source.getDirectEntity() == attacker && (source.is(DamageTypes.PLAYER_ATTACK)
                || source.is(DamageTypes.MOB_ATTACK) || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO)
                || source.is(DamageTypes.STING));
    }

    private static void lock(State state) {
        LivingEntity actor = state.actor;
        if (state.kind != ParryNetwork.Kind.STAGGER || !actor.isAlive() || actor.isRemoved()
                || actor.level() != state.level) return;
        actor.setPos(state.position);
        actor.setYRot(state.yaw);
        actor.setXRot(0);
        actor.yRotO = actor.yBodyRot = actor.yBodyRotO = actor.yHeadRot = actor.yHeadRotO = state.yaw;
        actor.setDeltaMovement(Vec3.ZERO);
        actor.xxa = actor.yya = actor.zza = 0;
        actor.setJumping(false);
        actor.setSprinting(false);
        actor.resetFallDistance();
        if (actor instanceof ServerPlayer player) player.connection.resetPosition();
    }

    private static void lockActor(Entity actor) {
        if (isStaggered(actor)) lock(STATES.get(actor.getUUID()));
    }
    @SubscribeEvent public static void beforeEntity(EntityTickEvent.Pre event) { lockActor(event.getEntity()); }
    @SubscribeEvent public static void afterEntity(EntityTickEvent.Post event) { lockActor(event.getEntity()); }
    @SubscribeEvent public static void beforePlayer(PlayerTickEvent.Pre event) { lockActor(event.getEntity()); }
    @SubscribeEvent public static void afterPlayer(PlayerTickEvent.Post event) { lockActor(event.getEntity()); }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        for (State state : List.copyOf(STATES.values())) {
            LivingEntity actor = state.actor;
            if (!actor.isAlive() || actor.isRemoved() || actor.level() != state.level
                    || actor instanceof ServerPlayer serverPlayer && (serverPlayer.hasDisconnected() || serverPlayer.isSpectator())
                    || state.kind == ParryNetwork.Kind.PARRY && actor instanceof ServerPlayer weaponPlayer
                    && !ExecutionWeapons.canExecute(weaponPlayer.getMainHandItem())
                    || state.level.getGameTime() >= state.startGameTime + ParryTimeline.totalTicks(state.kind, state.duration)) {
                stop(state);
            } else { lock(state); }
        }
    }

    private static void restore(State state) {
        if (state.kind != ParryNetwork.Kind.STAGGER) return;
        state.actor.setNoGravity(state.noGravity);
        if (state.actor instanceof Mob mob) mob.setNoAi(state.noAi);
        state.actor.getPersistentData().remove(RECOVERY_TAG);
        state.actor.setDeltaMovement(Vec3.ZERO);
    }

    private static void stop(State state) {
        if (!STATES.remove(state.actor.getUUID(), state)) return;
        restore(state);
        PacketDistributor.sendToPlayersInDimension(state.level,
                new ParryNetwork.Stop(state.id, state.actor.getId(), state.actor.getUUID()));
    }

    private static void abort(Entity actor) {
        State state = STATES.get(actor.getUUID());
        if (state != null && state.actor == actor) stop(state);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void knockback(LivingKnockBackEvent event) {
        if (isStaggered(event.getEntity())) event.setCanceled(true);
    }

    private static void interaction(PlayerInteractEvent event) {
        if (!blocksActions(event.getEntity()) || !(event instanceof ICancellableEvent cancellable)) return;
        cancellable.setCanceled(true);
        if (event instanceof PlayerInteractEvent.RightClickItem item) item.setCancellationResult(InteractionResult.FAIL);
        if (event instanceof PlayerInteractEvent.RightClickBlock block) block.setCancellationResult(InteractionResult.FAIL);
        if (event instanceof PlayerInteractEvent.EntityInteract entity) entity.setCancellationResult(InteractionResult.FAIL);
        if (event instanceof PlayerInteractEvent.EntityInteractSpecific entity) entity.setCancellationResult(InteractionResult.FAIL);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void rightClickItem(PlayerInteractEvent.RightClickItem event) { interaction(event); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void rightClickBlock(PlayerInteractEvent.RightClickBlock event) { interaction(event); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void entityInteract(PlayerInteractEvent.EntityInteract event) { interaction(event); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void entityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) { interaction(event); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void leftClickBlock(PlayerInteractEvent.LeftClickBlock event) { interaction(event); }

    @SubscribeEvent public static void death(LivingDeathEvent event) { abort(event.getEntity()); }
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) { abort(event.getEntity()); }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        SYNCED.remove(event.getEntity().getUUID());
        // Invalidate tasks already queued before logout without reusing epoch zero.
        ATTACK_EPOCHS.merge(event.getEntity().getUUID(), 1L, Long::sum);
        abort(event.getEntity());
    }

    @SubscribeEvent
    public static void tracking(PlayerEvent.StartTracking event) {
        State state = STATES.get(event.getTarget().getUUID());
        if (state == null || state.actor != event.getTarget() || !(event.getEntity() instanceof ServerPlayer viewer)) return;
        try {
            sync(viewer, new ParryNetwork.Sync(state.clips.hash(), ParryAnimations.encode(state.clips)));
            PacketDistributor.sendToPlayer(viewer, state.payload());
        } catch (IOException exception) { LOGGER.error("Cannot synchronize tracked parry", exception); }
    }

    @SubscribeEvent
    public static void entityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof LivingEntity actor)
                || !actor.getPersistentData().contains(RECOVERY_TAG)) return;
        CompoundTag recovery = actor.getPersistentData().getCompound(RECOVERY_TAG);
        actor.setNoGravity(recovery.getBoolean("no_gravity"));
        if (actor instanceof Mob mob) mob.setNoAi(recovery.getBoolean("no_ai"));
        actor.getPersistentData().remove(RECOVERY_TAG);
    }

    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        for (State state : List.copyOf(STATES.values())) stop(state);
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        for (State state : STATES.values()) restore(state);
        STATES.clear(); ATTACK_EPOCHS.clear(); SYNCED.clear();
    }

    private static final class State {
        final UUID id = UUID.randomUUID();
        final LivingEntity actor;
        final ServerLevel level;
        final ParryNetwork.Kind kind;
        final ParryAnimations.Clips clips;
        final long startGameTime;
        final int duration;
        final Vec3 position;
        final float yaw;
        final boolean noGravity;
        final boolean noAi;

        State(LivingEntity actor, ServerLevel level, ParryNetwork.Kind kind, ParryAnimations.Clips clips) {
            this.actor = actor; this.level = level; this.kind = kind; this.clips = clips;
            this.startGameTime = level.getGameTime();
            this.duration = clips.clip(kind).duration();
            this.position = actor.position(); this.yaw = actor.yBodyRot;
            this.noGravity = actor.isNoGravity(); this.noAi = actor instanceof Mob mob && mob.isNoAi();
        }

        ParryNetwork.Start payload() {
            return new ParryNetwork.Start(id, actor.getId(), actor.getUUID(), level.dimension().location(), kind,
                    startGameTime, duration, clips.hash(), position.x, position.y, position.z, yaw);
        }
    }
}
