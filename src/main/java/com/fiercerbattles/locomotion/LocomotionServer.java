package com.fiercerbattles.locomotion;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.FiercerBattlesExecutionServer;
import com.fiercerbattles.parry.ParryServer;
import com.fiercerbattles.sound.ModSounds;
import com.mojang.logging.LogUtils;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server sessions for the dodge roll and the prone stance. The roll's displacement is applied by the
 * owning client (players are client-authoritative movers); the server owns timing, cooldown, the
 * roll's invulnerability, the crawling hitbox and mutual exclusion with execution and parry.
 */
@EventBusSubscriber(modid = FiercerBattles.MOD_ID)
public final class LocomotionServer {
    /** Damage types a dodge roll's invulnerability does not stop (data/fiercerbattles/tags/damage_type). */
    public static final TagKey<DamageType> IGNORES_DODGE_ROLL = TagKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(FiercerBattles.MOD_ID, "ignores_dodge_roll"));

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> ROLL_READY = new ConcurrentHashMap<>();
    private static final Map<UUID, String> SYNCED = new ConcurrentHashMap<>();

    private LocomotionServer() {}

    /** Holds off other actions; a roll stops holding them once its recovery window opens. */
    public static boolean isBusy(Entity entity) {
        State state = state(entity);
        return state != null && !state.recovering();
    }

    /** Any roll or prone session, including a roll's recovery window. */
    public static boolean hasSession(Entity entity) { return state(entity) != null; }

    private static State state(Entity entity) {
        if (entity == null || entity.level().isClientSide()) return null;
        State state = STATES.get(entity.getUUID());
        return state != null && state.player == entity ? state : null;
    }

    public static boolean tryRoll(ServerPlayer player, float yaw) {
        if (!Float.isFinite(yaw)) return false;
        if (!canAct(player) || hasSession(player)) return deny(player, "roll_busy");
        if (!player.onGround()) return deny(player, "roll_ground");
        if (player.serverLevel().getGameTime() < ROLL_READY.getOrDefault(player.getUUID(), Long.MIN_VALUE)) return false;
        LocomotionAnimations.Clips clips = LocomotionAnimations.clips();
        if (clips == null) return deny(player, "locomotion_animations");
        return begin(player, LocomotionNetwork.Kind.ROLL, clips, Mth.wrapDegrees(yaw), false);
    }

    /** {@code yaw} is the client's held movement heading; a sprinting player dives along it. */
    public static boolean tryProne(ServerPlayer player, boolean down, float yaw) {
        State state = STATES.get(player.getUUID());
        if (down) {
            if (!canAct(player) || state != null && state.player == player) return deny(player, "prone_busy");
            if (!player.onGround()) return deny(player, "prone_ground");
            LocomotionAnimations.Clips clips = LocomotionAnimations.clips();
            if (clips == null) return deny(player, "locomotion_animations");
            boolean slide = player.isSprinting() && Float.isFinite(yaw);
            return begin(player, LocomotionNetwork.Kind.PRONE, clips, slide ? Mth.wrapDegrees(yaw) : player.yBodyRot, slide);
        }
        // Standing up is only offered once the drop-down has finished; a repeat press mid-drop is ignored.
        if (state == null || state.player != player || state.kind != LocomotionNetwork.Kind.PRONE || !state.settled()) return false;
        if (!player.level().noCollision(player, player.getDimensions(Pose.STANDING)
                .makeBoundingBox(player.position()).deflate(1.0E-7))) return deny(player, "prone_room");
        return begin(player, LocomotionNetwork.Kind.STAND, state.clips, player.yBodyRot, false);
    }

    /** The roller moved or acted once the recovery window opened: end the roll now and start its cooldown. */
    public static void releaseRoll(ServerPlayer player, UUID sessionId) {
        State state = state(player);
        if (state == null || !state.id.equals(sessionId) || !state.recovering()) return;
        ROLL_READY.put(player.getUUID(), state.level.getGameTime() + LocomotionTimeline.ROLL_COOLDOWN_TICKS);
        stop(state, true);
    }

    private static boolean canAct(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.hasDisconnected() && !player.isSpectator()
                && !player.isSleeping() && !player.isPassenger() && !player.isFallFlying() && !player.getAbilities().flying
                && !player.isInWater() && !FiercerBattlesExecutionServer.isExecuting(player) && !ParryServer.isBusy(player);
    }

    private static boolean begin(ServerPlayer player, LocomotionNetwork.Kind kind, LocomotionAnimations.Clips clips,
                                 float yaw, boolean slide) {
        ServerLevel level = player.serverLevel();
        LocomotionNetwork.Sync sync;
        try { sync = new LocomotionNetwork.Sync(clips.hash(), LocomotionAnimations.encode(clips)); }
        catch (IOException exception) {
            LOGGER.error("Cannot synchronize locomotion animations", exception);
            return false;
        }
        State state = new State(player, level, kind, clips, yaw, slide);
        STATES.put(player.getUUID(), state);
        player.stopUsingItem();
        if (kind == LocomotionNetwork.Kind.PRONE) {
            // Neo's forced pose gives the vanilla crawling hitbox, eye height and crawl speed.
            player.setForcedPose(Pose.SWIMMING);
            player.setSprinting(false);
        } else if (kind == LocomotionNetwork.Kind.STAND) {
            // Release the hitbox as the stand-up clip starts (room was checked), so vanilla's crawl
            // tilt has faded out before the clip hands the model back.
            player.setForcedPose(null);
        } else {
            // A tumbling body is only crawl-high, so a roll fits under one-block gaps. If it ends under
            // one, releasing the pose lets vanilla keep the player crawling until there is room to stand.
            player.setForcedPose(Pose.SWIMMING);
            level.playSound(null, player.getX(), player.getY(), player.getZ(), ModSounds.ROLL.get(),
                    SoundSource.PLAYERS, 1.0F, 1.0F);
        }
        for (ServerPlayer viewer : level.players()) sync(viewer, sync);
        PacketDistributor.sendToPlayersInDimension(level, state.payload());
        LOGGER.debug("Locomotion started: kind={}, player={}, tick={}", kind, player.getUUID(), state.startGameTime);
        return true;
    }

    private static boolean deny(ServerPlayer player, String reason) {
        player.displayClientMessage(Component.translatable("message.fiercerbattles." + reason), true);
        return false;
    }

    private static void sync(ServerPlayer viewer, LocomotionNetwork.Sync sync) {
        if (sync.hash().equals(SYNCED.get(viewer.getUUID()))) return;
        PacketDistributor.sendToPlayer(viewer, sync);
        SYNCED.put(viewer.getUUID(), sync.hash());
    }

    /** Clients render the sprint clip on their own, so they need the bundle on login and after /reload. */
    @SubscribeEvent
    public static void datapackSync(OnDatapackSyncEvent event) {
        LocomotionAnimations.Clips clips = LocomotionAnimations.clips();
        if (clips == null) return;
        try {
            LocomotionNetwork.Sync sync = new LocomotionNetwork.Sync(clips.hash(), LocomotionAnimations.encode(clips));
            event.getRelevantPlayers().forEach(player -> sync(player, sync));
        } catch (IOException exception) { LOGGER.error("Cannot synchronize locomotion animations", exception); }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        for (State state : List.copyOf(STATES.values())) {
            ServerPlayer player = state.player;
            long now = state.level.getGameTime();
            if (!player.isAlive() || player.isRemoved() || player.hasDisconnected() || player.isSpectator()
                    || player.level() != state.level
                    || state.kind != LocomotionNetwork.Kind.ROLL && (player.isPassenger() || player.isFallFlying()
                    || player.getAbilities().flying || player.isSleeping() || player.isInWater())) {
                stop(state, true);
            } else if (state.kind == LocomotionNetwork.Kind.ROLL && now >= state.startGameTime + state.duration) {
                ROLL_READY.put(player.getUUID(), now + LocomotionTimeline.ROLL_COOLDOWN_TICKS);
                stop(state, false);
            } else if (state.kind == LocomotionNetwork.Kind.STAND && now >= state.startGameTime + state.duration) {
                stop(state, false);
            } else {
                if (state.kind != LocomotionNetwork.Kind.STAND && player.getForcedPose() != Pose.SWIMMING) {
                    player.setForcedPose(Pose.SWIMMING);
                }
                if (state.kind == LocomotionNetwork.Kind.PRONE) dust(state, now - state.startGameTime);
            }
        }
    }

    /** Ground dust: a trail while a sprinting dive slides, and a burst as the torso lands. */
    private static void dust(State state, long elapsed) {
        ServerPlayer player = state.player;
        if (state.slide && elapsed < state.duration && player.onGround()) {
            ground(player, player.getX(), player.getZ(), 3, 0.2, 0.05);
        }
        if (elapsed == LocomotionTimeline.PRONE_IMPACT_TICK) {
            // The head lies over the entity origin, so the torso lands about half a block behind it.
            float yaw = player.yBodyRot * Mth.DEG_TO_RAD;
            ground(player, player.getX() + Mth.sin(yaw) * 0.5, player.getZ() - Mth.cos(yaw) * 0.5, 24, 0.45, 0.12);
        }
    }

    private static void ground(ServerPlayer player, double x, double z, int count, double spread, double speed) {
        BlockState ground = player.level().getBlockState(player.getOnPos());
        if (ground.isAir() || ground.getRenderShape() == RenderShape.INVISIBLE) return;
        player.serverLevel().sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground),
                x, player.getY() + 0.1, z, count, spread, 0.05, spread, speed);
    }

    private static void stop(State state, boolean notify) {
        if (!STATES.remove(state.player.getUUID(), state)) return;
        if (state.kind != LocomotionNetwork.Kind.STAND && state.player.getForcedPose() == Pose.SWIMMING) {
            state.player.setForcedPose(null);
        }
        if (notify) {
            PacketDistributor.sendToPlayersInDimension(state.level,
                    new LocomotionNetwork.Stop(state.id, state.player.getId(), state.player.getUUID()));
        }
    }

    private static void abort(Entity entity) {
        State state = STATES.get(entity.getUUID());
        if (state != null && state.player == entity) stop(state, true);
    }

    /**
     * The roll and both prone transitions are committed actions; a settled prone player may still mine and
     * use items, and so may a roller once the recovery window opens.
     */
    private static void interaction(PlayerInteractEvent event) {
        State state = state(event.getEntity());
        if (state == null || state.settled() || state.recovering()
                || !(event instanceof ICancellableEvent cancellable)) return;
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

    /**
     * Dodge frames: the committed part of a roll ignores every hit except falls, invulnerability-bypassing damage and
     * the damage types in fiercerbattles:ignores_dodge_roll (attacks that cannot be rolled through).
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void incomingDamage(LivingIncomingDamageEvent event) {
        State state = state(event.getEntity());
        if (state == null || !state.invulnerable()) return;
        DamageSource source = event.getSource();
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || source.is(DamageTypeTags.IS_FALL)
                || source.is(IGNORES_DODGE_ROLL)) return;
        event.setCanceled(true);
    }

    @SubscribeEvent public static void death(LivingDeathEvent event) { abort(event.getEntity()); }
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) { abort(event.getEntity()); }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        SYNCED.remove(event.getEntity().getUUID());
        ROLL_READY.remove(event.getEntity().getUUID());
        abort(event.getEntity());
    }

    @SubscribeEvent
    public static void tracking(PlayerEvent.StartTracking event) {
        State state = STATES.get(event.getTarget().getUUID());
        if (state == null || state.player != event.getTarget() || !(event.getEntity() instanceof ServerPlayer viewer)) return;
        try {
            sync(viewer, new LocomotionNetwork.Sync(state.clips.hash(), LocomotionAnimations.encode(state.clips)));
            PacketDistributor.sendToPlayer(viewer, state.payload());
        } catch (IOException exception) { LOGGER.error("Cannot synchronize tracked locomotion", exception); }
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        STATES.clear(); ROLL_READY.clear(); SYNCED.clear();
    }

    private static final class State {
        final UUID id = UUID.randomUUID();
        final ServerPlayer player;
        final ServerLevel level;
        final LocomotionNetwork.Kind kind;
        final LocomotionAnimations.Clips clips;
        final long startGameTime;
        final int duration;
        final float yaw;
        final boolean slide;

        State(ServerPlayer player, ServerLevel level, LocomotionNetwork.Kind kind, LocomotionAnimations.Clips clips,
              float yaw, boolean slide) {
            this.player = player; this.level = level; this.kind = kind; this.clips = clips; this.yaw = yaw; this.slide = slide;
            this.startGameTime = level.getGameTime();
            int frames = clips.clip(kind).duration();
            this.duration = kind == LocomotionNetwork.Kind.ROLL ? Mth.ceil(LocomotionTimeline.rollTicks(frames)) : frames;
        }

        private float elapsed() { return level.getGameTime() - startGameTime; }

        /** Prone after the drop-down clip has finished. */
        boolean settled() {
            return kind == LocomotionNetwork.Kind.PRONE && level.getGameTime() >= startGameTime + duration;
        }

        /** A roll past its recovery frame: the roller may move and act again. */
        boolean recovering() {
            return kind == LocomotionNetwork.Kind.ROLL && LocomotionTimeline.rollRecovering(elapsed());
        }

        boolean invulnerable() {
            return kind == LocomotionNetwork.Kind.ROLL && LocomotionTimeline.rollInvulnerable(elapsed());
        }

        LocomotionNetwork.Start payload() {
            return new LocomotionNetwork.Start(id, player.getId(), player.getUUID(), level.dimension().location(), kind,
                    startGameTime, clips.hash(), yaw, slide);
        }
    }
}
