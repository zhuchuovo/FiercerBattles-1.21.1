package com.fiercerbattles.parry;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.FiercerBattlesExecutionServer;
import com.fiercerbattles.config.FiercerBattlesConfig;
import com.fiercerbattles.execution.ExecutionWeapons;
import com.fiercerbattles.sound.ModSounds;
import com.fiercerbattles.locomotion.LocomotionServer;
import com.fiercerbattles.mobcombat.logic.MobCombatLogic;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Independent gestures share the animation core, never the paired execution anchors. */
@EventBusSubscriber(modid = FiercerBattles.MOD_ID)
public final class ParryServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RECOVERY_TAG = "fiercerbattles:parry_restore";
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> ATTACK_EPOCHS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> SYNCED = new ConcurrentHashMap<>();
    /** Client-reported guard of each player's current swing; absent or expired means not attacking. */
    private static final Map<UUID, Guard> GUARDS = new ConcurrentHashMap<>();
    private static final int MAX_GUARD_TICKS = 200;
    /** Fixed counter damage of a successful parry, defined in {@code data/fiercerbattles/damage_type}. */
    public static final ResourceKey<DamageType> PARRY_COUNTER = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(FiercerBattles.MOD_ID, "parry_counter"));
    private static final ThreadLocal<Boolean> COUNTER_DAMAGE = ThreadLocal.withInitial(() -> false);
    /** Recent flinches per victim (server thread only); weak keys let unloaded mobs go. */
    private static final Map<LivingEntity, Poise> POISE = new WeakHashMap<>();

    private ParryServer() {}

    public static boolean isBusy(Entity entity) {
        if (entity == null || entity.level().isClientSide()) return false;
        State state = STATES.get(entity.getUUID());
        return state != null && state.actor == entity;
    }

    public static boolean blocksActions(Entity entity) { return isBusy(entity); }

    public static boolean isMovementLocked(Entity entity) {
        if (!isBusy(entity)) return false;
        return STATES.get(entity.getUUID()).kind.locksMovement();
    }

    /** True only while a held pose suspends the actor; a parry always keeps falling. */
    public static boolean locksVertical(Entity entity) {
        if (!isBusy(entity)) return false;
        return STATES.get(entity.getUUID()).kind.locksVertical();
    }

    public static boolean isStaggered(Entity entity) {
        if (entity == null || entity.level().isClientSide()) return false;
        State state = STATES.get(entity.getUUID());
        return state != null && state.actor == entity && state.kind == ParryNetwork.Kind.STAGGER;
    }

    public static boolean isHitstunned(Entity entity) {
        if (entity == null || entity.level().isClientSide()) return false;
        State state = STATES.get(entity.getUUID());
        return state != null && state.actor == entity && state.kind == ParryNetwork.Kind.HITSTUN;
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
                || player.isSleeping() || player.isPassenger() || isBusy(player) || FiercerBattlesExecutionServer.isExecuting(player)
                || LocomotionServer.isBusy(player)) {
            return deny(player, "busy");
        }
        // A parry is a grounded gesture; the airborne fall is never interrupted by it.
        if (!player.onGround()) return false;
        Guard guard = GUARDS.get(player.getUUID());
        if (guard != null && !guard.attackWindow.allowsParry(player.serverLevel().getGameTime())) return false;
        if (!ExecutionWeapons.canExecute(player.getMainHandItem())) return deny(player, "weapon");
        ParryAnimations.Clips clips = ParryAnimations.clips();
        if (clips == null) return deny(player, "animations");
        if (!begin(player, ParryNetwork.Kind.PARRY, clips)) return false;
        // Exactly one wind-up cue per gesture, heard by everyone near the parrying player.
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), ModSounds.PARRY_USE.get(),
                SoundSource.PLAYERS, 1.0F, 1.0F);
        return true;
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
        GUARDS.remove(actor.getUUID());
        if (actor instanceof Mob mob) {
            // A flinch or stun takes the mob's wind-up away; that swing never lands afterwards.
            MobCombatLogic.interrupt(mob);
            mob.getNavigation().stop();
        }
        if (kind.locksMovement()) {
            // Only the vertical lock suspends the actor, so only it has gravity to give back.
            if (kind.locksVertical()) {
                CompoundTag recovery = new CompoundTag();
                recovery.putBoolean("no_gravity", state.noGravity);
                recovery.putBoolean("no_ai", state.noAi);
                actor.getPersistentData().put(RECOVERY_TAG, recovery);
                actor.setNoGravity(true);
            }
            if (kind == ParryNetwork.Kind.STAGGER && actor instanceof Mob mob) {
                mob.getNavigation().stop(); mob.setNoAi(true);
            }
            lock(state);
        }
        for (ServerPlayer viewer : level.players()) sync(viewer, sync);
        PacketDistributor.sendToPlayersInDimension(level, state.payload());
        if (kind == ParryNetwork.Kind.HITSTUN) {
            LOGGER.debug("Hitstun started: actor={}, tick={}", actor.getUUID(), state.startGameTime);
        } else {
            LOGGER.info("Parry gesture started: kind={}, actor={}, tick={}", kind, actor.getUUID(), state.startGameTime);
        }
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
        // The parrying player is still busy; its own counter must not be refused below.
        if (FiercerBattlesExecutionServer.isExecutionDamage() || COUNTER_DAMAGE.get()) return;
        LivingEntity defender = event.getEntity();
        DamageSource source = event.getSource();
        if (!(source.getEntity() instanceof LivingEntity attacker)
                || FiercerBattlesExecutionServer.isExecuting(defender)
                || FiercerBattlesExecutionServer.isExecuting(attacker)) return;
        // The current multi-target task may already be inside its damage loop.
        // Refuse its remaining hits while allowing that task to clean up attributes.
        // A flinching attacker only loses melee hits; projectiles already in flight still land.
        if (blocksActions(attacker) && (!isHitstunned(attacker) || isMelee(source, attacker))) {
            event.setCanceled(true);
            return;
        }
        if (attacker == defender || !canParry(defender) || !isMelee(source, attacker)) return;
        State gesture = STATES.get(defender.getUUID());
        event.setCanceled(true);
        // Stagger first: the counter then lands on a stunned attacker, which neither
        // flinches (hitstun) nor takes knockback.
        begin(attacker, ParryNetwork.Kind.STAGGER, gesture.clips);
        // A null player broadcasts to everyone nearby, including the parrying player.
        defender.level().playSound(null, defender.getX(), defender.getEyeY(), defender.getZ(), ModSounds.PARRY.get(),
                SoundSource.PLAYERS, 1.0F, 0.95F + defender.getRandom().nextFloat() * 0.1F);
        counter(defender, attacker);
    }

    private static void counter(LivingEntity defender, LivingEntity attacker) {
        float damage = FiercerBattlesConfig.getParry().counterDamage();
        if (damage <= 0 || !attacker.isAlive()) return;
        COUNTER_DAMAGE.set(true);
        try {
            attacker.hurt(defender.damageSources().source(PARRY_COUNTER, defender), damage);
        } finally {
            COUNTER_DAMAGE.remove();
        }
    }

    /** Ends a parry stun early, restoring AI and gravity, so a riposte execution can take over. */
    public static void releaseStagger(Entity actor) {
        if (isStaggered(actor)) abort(actor);
    }

    /** Records the swing the client just started: whether a hit may interrupt it, and for how long. */
    public static void guard(ServerPlayer player, byte mode, int ticks) {
        guard(player, mode, ticks, ticks);
    }

    public static void guard(ServerPlayer player, byte mode, int ticks, int animationTicks) {
        if (ticks <= 0 || mode < ParryNetwork.GUARD_UNSET || mode > ParryNetwork.GUARD_ARMORED) {
            GUARDS.remove(player.getUUID());
            return;
        }
        long now = player.serverLevel().getGameTime();
        GUARDS.put(player.getUUID(), new Guard(mode, now + Math.min(ticks, MAX_GUARD_TICKS),
                ParryAttackWindow.start(now, Math.min(Math.max(1, animationTicks), MAX_GUARD_TICKS))));
    }

    public static void damageChecked(ServerPlayer player) {
        GUARDS.computeIfPresent(player.getUUID(), (uuid, guard) ->
                new Guard(guard.mode, guard.until, guard.attackWindow.afterDamageCheck()));
    }

    /** The live client animation may finish before the nominal duration because of speed modifiers. */
    public static void animationFinished(ServerPlayer player) {
        GUARDS.computeIfPresent(player.getUUID(), (uuid, guard) ->
                new Guard(guard.mode, guard.until, guard.attackWindow.afterAnimationEnd(player.serverLevel().getGameTime())));
    }

    private static boolean interruptible(ServerPlayer player, FiercerBattlesConfig.Hitstun config) {
        Guard guard = GUARDS.get(player.getUUID());
        if (guard == null || player.serverLevel().getGameTime() >= guard.until) return true;
        if (guard.mode == ParryNetwork.GUARD_ARMORED) return false;
        return guard.mode == ParryNetwork.GUARD_INTERRUPTIBLE || config.default_hit_interruptible;
    }

    /**
     * A hit that actually wounds a player flinches them, cancelling an interruptible swing. Adult humanoid
     * mobs (the execution allowlist, whose models can play the clip) flinch too and lose their wind-up.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void damaged(LivingDamageEvent.Post event) {
        LivingEntity victim = event.getEntity();
        if (!(victim instanceof ServerPlayer) && !(victim instanceof Mob) || event.getNewDamage() <= 0) return;
        FiercerBattlesConfig.Hitstun config = FiercerBattlesConfig.getHitstun();
        if (config == null || !config.enabled || FiercerBattlesExecutionServer.isExecutionDamage()) return;
        Entity attacker = event.getSource().getEntity();
        if (attacker == null || attacker == victim || !victim.isAlive()
                || victim.isPassenger() || victim.isFallFlying() || victim.isAutoSpinAttack()
                || victim.getPose() != Pose.STANDING && victim.getPose() != Pose.CROUCHING
                || FiercerBattlesExecutionServer.isExecuting(victim) || isStaggered(victim)) return;
        if (victim instanceof ServerPlayer player) {
            if (player.isSpectator() || LocomotionServer.hasSession(player) || !interruptible(player, config)) return;
        } else {
            FiercerBattlesConfig.Execution humanoids = FiercerBattlesConfig.getExecution();
            if (!config.humanoid_mobs || victim.isBaby() || config.exempts(victim)
                    || humanoids == null || !humanoids.allows(victim)) return;
        }
        ParryAnimations.Clips clips = ParryAnimations.clips();
        if (clips == null || clips.hitstun() == null) return;
        long now = victim.level().getGameTime();
        Poise poise = POISE.computeIfAbsent(victim, ignored -> new Poise());
        if (now < poise.immuneUntil) return;
        if (begin(victim, ParryNetwork.Kind.HITSTUN, clips)) poise.flinched(now);
    }

    /** Counts recent flinches; the last allowed one starts a short window in which hits no longer stun. */
    private static final class Poise {
        final Deque<Long> flinches = new ArrayDeque<>();
        long immuneUntil = Long.MIN_VALUE;

        void flinched(long now) {
            while (!flinches.isEmpty() && now - flinches.peekFirst() >= ParryTimeline.HITSTUN_CHAIN_WINDOW_TICKS) {
                flinches.pollFirst();
            }
            flinches.addLast(now);
            if (flinches.size() >= ParryTimeline.HITSTUN_CHAIN_LIMIT) {
                immuneUntil = now + ParryTimeline.HITSTUN_IMMUNITY_TICKS;
                flinches.clear();
            }
        }
    }

    private static boolean isMelee(DamageSource source, LivingEntity attacker) {
        return source.getDirectEntity() == attacker && (source.is(DamageTypes.PLAYER_ATTACK)
                || source.is(DamageTypes.MOB_ATTACK) || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO)
                || source.is(DamageTypes.STING));
    }

    private static void lock(State state) {
        LivingEntity actor = state.actor;
        if (!state.kind.locksMovement() || !actor.isAlive() || actor.isRemoved()
                || actor.level() != state.level) return;
        boolean vertical = state.kind.locksVertical();
        actor.setPos(state.position.x, vertical ? state.position.y : actor.getY(), state.position.z);
        if (state.kind == ParryNetwork.Kind.STAGGER) {
            actor.setYRot(state.yaw);
            actor.setXRot(0);
            actor.yRotO = actor.yBodyRot = actor.yBodyRotO = actor.yHeadRot = actor.yHeadRotO = state.yaw;
        }
        Vec3 motion = actor.getDeltaMovement();
        actor.setDeltaMovement(vertical ? Vec3.ZERO : new Vec3(0.0, motion.y, 0.0));
        actor.xxa = actor.yya = actor.zza = 0;
        actor.setJumping(false);
        actor.setSprinting(false);
        actor.setShiftKeyDown(false);
        if (vertical) {
            actor.resetFallDistance();
            if (actor instanceof ServerPlayer player) player.connection.resetPosition();
        }
    }

    private static void lockActor(Entity actor) {
        if (isMovementLocked(actor)) lock(STATES.get(actor.getUUID()));
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
        if (!state.kind.locksMovement()) return;
        // Only the vertical lock suspends the actor, so only it has state to give back.
        if (state.kind.locksVertical()) {
            state.actor.setNoGravity(state.noGravity);
            state.actor.setDeltaMovement(Vec3.ZERO);
            state.actor.getPersistentData().remove(RECOVERY_TAG);
        }
        if (state.kind == ParryNetwork.Kind.STAGGER && state.actor instanceof Mob mob) mob.setNoAi(state.noAi);
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
        if (isMovementLocked(event.getEntity())) event.setCanceled(true);
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

    @SubscribeEvent public static void death(LivingDeathEvent event) {
        GUARDS.remove(event.getEntity().getUUID());
        abort(event.getEntity());
    }
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        GUARDS.remove(event.getEntity().getUUID());
        abort(event.getEntity());
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        SYNCED.remove(event.getEntity().getUUID());
        GUARDS.remove(event.getEntity().getUUID());
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
        STATES.clear(); ATTACK_EPOCHS.clear(); SYNCED.clear(); GUARDS.clear(); POISE.clear();
    }

    private record Guard(byte mode, long until, ParryAttackWindow attackWindow) {}

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
