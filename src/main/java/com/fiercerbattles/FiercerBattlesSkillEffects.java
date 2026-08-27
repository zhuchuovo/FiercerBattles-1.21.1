package com.fiercerbattles;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative combat-skill effects and cooldowns.
 */
@EventBusSubscriber(modid = FiercerBattles.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class FiercerBattlesSkillEffects {
    private static final Map<UUID, Map<String, Long>> SERVER_COOLDOWNS = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, ActiveSkill>> ACTIVE_SKILLS = new ConcurrentHashMap<>();
    private static final Map<String, Long> CLIENT_COOLDOWNS = new HashMap<>();
    /** Accepted skill requests waiting for Better Combat's server-thread task. */
    private static final Map<UUID, PendingSkill> PENDING_SERVER_SKILLS = new ConcurrentHashMap<>();
    /** The player whose skill attack is currently being resolved. */
    private static final ThreadLocal<ServerPlayer> SERVER_SKILL_REQUEST = new ThreadLocal<>();
    /** Custom data for that attack, used by server-side on-hit effects. */
    private static final ThreadLocal<FiercerBattlesCustomData.AttackData> SERVER_SKILL_ATTACK = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SERVER_REQUEST_THREAD = new ThreadLocal<>();

    private static Field attackStrengthTickerField;

    private FiercerBattlesSkillEffects() {
    }

    /**
     * Returns false when the skill-specific cooldown has not elapsed.
     * This method is called while Better Combat is resolving the server request.
     *
     * A combat skill directly interrupts whatever attack the player is
     * currently performing, so it is accepted at any point of the attack
     * recovery; the per-skill cooldown below is the only rate limiter.
     */
    public static boolean tryActivate(ServerPlayer player, FiercerBattlesCustomData.AttackData attackData) {
        String skillId = skillIdOf(attackData);
        long now = player.level().getGameTime();

        // Better Combat performs the actual attack from a server-thread task,
        // while the packet callback may have accepted the skill on a network
        // thread. A pending request may be observed by a second attack lookup,
        // but the server-thread task below is the authoritative consumer.
        PendingSkill pending = PENDING_SERVER_SKILLS.get(player.getUUID());
        if (pending != null) {
            if (pending.skillId.equals(skillId) && now <= pending.expiresAt) {
                activateOnServerThread(player, pending);
                return true;
            }
            if (now > pending.expiresAt) {
                PENDING_SERVER_SKILLS.remove(player.getUUID(), pending);
            }
        }

        // Better Combat resolves the current attack more than once while
        // handling one packet (once in ServerNetwork and again from
        // Player.attack).  The first lookup starts the skill cooldown; the
        // later lookups are still part of that same request and must keep
        // returning the skill attack instead of being rejected by its newly
        // started cooldown.
        if (SERVER_SKILL_REQUEST.get() == player) {
            return true;
        }
        FiercerBattlesCustomData.SkillEffectData effect = effectOf(attackData);
        if (effect != null) {
            Map<String, Long> cooldowns = SERVER_COOLDOWNS.computeIfAbsent(
                    player.getUUID(),
                    ignored -> new ConcurrentHashMap<>()
            );
            long readyAt = cooldowns.getOrDefault(skillId, 0L);
            if (now < readyAt) {
                return false;
            }

            long cooldownTicks = secondsToTicks(effect.cooldownSeconds);
            if (cooldownTicks > 0) {
                cooldowns.put(skillId, now + cooldownTicks);
            }
        }

        if (Boolean.TRUE.equals(SERVER_REQUEST_THREAD.get())) {
            PendingSkill accepted = new PendingSkill(skillId, attackData, effect, now + 20);
            if (PENDING_SERVER_SKILLS.putIfAbsent(player.getUUID(), accepted) == null) {
                // This is queued before Better Combat's own executeIfPossible
                // task, so the effect and full-strength ticker are installed
                // immediately before Player.attack() runs on the server.
                if (player.server.isSameThread()) {
                    activateOnServerThread(player, accepted);
                } else {
                    player.server.execute(() -> activateOnServerThread(player, accepted));
                }
            }
        } else {
            beginServerSkillAttack(player, attackData);
            if (effect != null) {
                applyEffect(player, attackData, effect, now);
            }
        }
        return true;
    }

    /**
     * Marks the start of the server-side handling of one attack request.
     */
    public static void beginServerAttackRequest() {
        SERVER_SKILL_REQUEST.remove();
        SERVER_SKILL_ATTACK.remove();
        SERVER_REQUEST_THREAD.set(Boolean.TRUE);
    }

    /**
     * Ends the server-side handling of one attack request. Mirrors the
     * client, which resets its attack-strength ticker after every
     * performed attack, hit or miss.
     */
    public static void finishServerAttackRequest() {
        // Do not reset the ticker here. Better Combat's attack runs from an
        // executeIfPossible server-thread task and resets it at the end of
        // Player.attack(). Resetting from this callback can happen first and
        // makes the skill hit use a partially charged attack.
        SERVER_REQUEST_THREAD.remove();
    }

    /**
     * Combat skills interrupt the ongoing attack, so their hit resolves at
     * full attack strength even while the vanilla attack-strength ticker is
     * still recovering from the interrupted swing.
     */
    private static void beginServerSkillAttack(
            ServerPlayer player,
            FiercerBattlesCustomData.AttackData attackData
    ) {
        SERVER_SKILL_REQUEST.set(player);
        SERVER_SKILL_ATTACK.set(attackData);
        setAttackStrengthTicker(player, Math.max(1, (int) Math.ceil(player.getCurrentItemAttackStrengthDelay())));
    }

    private static void activateOnServerThread(ServerPlayer player, PendingSkill pending) {
        if (!player.server.isSameThread()) {
            player.server.execute(() -> activateOnServerThread(player, pending));
            return;
        }
        if (!PENDING_SERVER_SKILLS.remove(player.getUUID(), pending)) {
            return;
        }
        long now = player.level().getGameTime();
        beginServerSkillAttack(player, pending.attackData);
        if (pending.effect != null) {
            applyEffect(player, pending.attackData, pending.effect, now);
        }
        // The Better Combat attack task was queued after this activation task.
        // Clear the request marker only after that attack has had a chance to
        // perform its duplicate attack lookup.
        player.server.execute(() -> player.server.execute(FiercerBattlesSkillEffects::clearServerSkillAttack));
    }

    private static void setAttackStrengthTicker(ServerPlayer player, int value) {
        try {
            if (attackStrengthTickerField == null) {
                attackStrengthTickerField = LivingEntity.class.getDeclaredField("attackStrengthTicker");
                attackStrengthTickerField.setAccessible(true);
            }
            attackStrengthTickerField.setInt(player, value);
        } catch (ReflectiveOperationException ignored) {
            // Keep the current ticker value; the skill still executes.
        }
    }

    public static boolean isClientReady(FiercerBattlesCustomData.AttackData attackData, int currentTick) {
        return getClientRemainingCooldownTicks(attackData, currentTick) == 0;
    }

    public static long getClientRemainingCooldownTicks(
            FiercerBattlesCustomData.AttackData attackData,
            int currentTick
    ) {
        if (effectOf(attackData) == null) {
            return 0;
        }
        long readyAt = CLIENT_COOLDOWNS.getOrDefault(skillIdOf(attackData), 0L);
        return Math.max(0, readyAt - currentTick);
    }

    public static void markClientCooldown(FiercerBattlesCustomData.AttackData attackData, int currentTick) {
        FiercerBattlesCustomData.SkillEffectData effect = effectOf(attackData);
        if (effect == null) {
            return;
        }
        long cooldownTicks = secondsToTicks(effect.cooldownSeconds);
        if (cooldownTicks > 0) {
            CLIENT_COOLDOWNS.put(skillIdOf(attackData), currentTick + cooldownTicks);
        }
    }

    /**
     * Client cooldowns are stored as absolute tickCount deadlines, and the
     * client player's tickCount resets to zero on every world rejoin. Without
     * this reset a previous session's deadline would block all skills until
     * the game itself is restarted.
     */
    public static void resetClientCooldowns() {
        CLIENT_COOLDOWNS.clear();
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            cleanupExpiredEffects(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UUID playerId = player.getUUID();
        SERVER_COOLDOWNS.remove(playerId);
        ACTIVE_SKILLS.remove(playerId);
        PENDING_SERVER_SKILLS.remove(playerId);
        FiercerBattlesCombatSkills.clearActiveSkillRange(player);
    }

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        DamageSource source = event.getSource();
        if (!(source.getEntity() instanceof ServerPlayer attacker)) {
            return;
        }
        applyHitTargetPotionEffect(attacker, event.getEntity());
        double multiplier = damageMultiplier(attacker);
        if (multiplier != 1.0) {
            event.setAmount((float) (event.getAmount() * multiplier));
        }
    }

    private static void applyEffect(
            ServerPlayer player,
            FiercerBattlesCustomData.AttackData attackData,
            FiercerBattlesCustomData.SkillEffectData effect,
            long now
    ) {
        long durationTicks = secondsToTicks(effect.durationSeconds);
        if (durationTicks <= 0 && (effect.damageBonus > 0 || effect.armorBonus > 0)) {
            durationTicks = 1;
        }

        if (effect.damageBonus > 0 || effect.armorBonus > 0) {
            String skillId = skillIdOf(attackData);
            Map<String, ActiveSkill> active = ACTIVE_SKILLS.computeIfAbsent(
                    player.getUUID(),
                    ignored -> new HashMap<>()
            );
            ActiveSkill state = active.computeIfAbsent(skillId, ignored -> new ActiveSkill());
            state.expiresAt = now + durationTicks;
            state.damageBonus = effect.damageBonus;

            if (effect.armorBonus > 0) {
                AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
                if (armor != null) {
                    ResourceLocation modifierId = modifierId(skillId);
                    state.armorModifierId = modifierId;
                    armor.addOrUpdateTransientModifier(new AttributeModifier(
                            modifierId,
                            effect.armorBonus,
                            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
                    ));
                }
            }
        }

        if (effect.potionEffect != null
                && effect.potionEffect.target() == FiercerBattlesCustomData.PotionEffectTarget.SELF) {
            applyPotionEffect(player, effect.potionEffect, durationTicks);
        }

        if (effect.healPercent > 0) {
            player.heal((float) (player.getMaxHealth() * effect.healPercent));
        }

        playSound(player, effect);
    }

    private static void applyHitTargetPotionEffect(ServerPlayer attacker, LivingEntity target) {
        if (SERVER_SKILL_REQUEST.get() != attacker) {
            return;
        }
        FiercerBattlesCustomData.AttackData attackData = SERVER_SKILL_ATTACK.get();
        FiercerBattlesCustomData.SkillEffectData effect = effectOf(attackData);
        if (effect == null || effect.potionEffect == null
                || effect.potionEffect.target() != FiercerBattlesCustomData.PotionEffectTarget.HIT_TARGET) {
            return;
        }
        applyPotionEffect(target, effect.potionEffect, secondsToTicks(effect.durationSeconds));
    }

    private static void applyPotionEffect(
            LivingEntity target,
            FiercerBattlesCustomData.PotionEffectData potionEffect,
            long fallbackDurationTicks
    ) {
        if (potionEffect.id().isBlank()) {
            return;
        }
        ResourceLocation effectId = ResourceLocation.tryParse(potionEffect.id());
        if (effectId == null) {
            return;
        }
        int durationTicks = potionEffect.durationTicks() > 0
                ? potionEffect.durationTicks()
                : (int) Math.max(1, fallbackDurationTicks);
        BuiltInRegistries.MOB_EFFECT.getHolder(effectId).ifPresent(holder ->
                target.addEffect(new MobEffectInstance(
                        holder,
                        durationTicks,
                        potionEffect.amplifier()
                ), SERVER_SKILL_REQUEST.get())
        );
    }

    private static void clearServerSkillAttack() {
        SERVER_SKILL_REQUEST.remove();
        SERVER_SKILL_ATTACK.remove();
    }

    private static void playSound(ServerPlayer player, FiercerBattlesCustomData.SkillEffectData effect) {
        if (effect.soundId == null || effect.soundId.isBlank()) {
            return;
        }
        ResourceLocation soundId = ResourceLocation.tryParse(effect.soundId);
        if (soundId == null) {
            return;
        }
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(soundId).orElse(null);
        if (sound != null) {
            player.level().playSound(
                    null,
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    sound,
                    SoundSource.PLAYERS,
                    effect.soundVolume,
                    effect.soundPitch
            );
        }
    }

    private static double damageMultiplier(ServerPlayer player) {
        cleanupExpiredEffects(player);
        Map<String, ActiveSkill> active = ACTIVE_SKILLS.get(player.getUUID());
        if (active == null) {
            return 1.0;
        }
        double multiplier = 1.0;
        for (ActiveSkill state : active.values()) {
            if (state.damageBonus > 0) {
                multiplier *= 1.0 + state.damageBonus;
            }
        }
        return multiplier;
    }

    private static void cleanupExpiredEffects(ServerPlayer player) {
        Map<String, ActiveSkill> active = ACTIVE_SKILLS.get(player.getUUID());
        if (active == null || active.isEmpty()) {
            return;
        }
        long now = player.level().getGameTime();
        Iterator<Map.Entry<String, ActiveSkill>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            ActiveSkill state = iterator.next().getValue();
            if (now < state.expiresAt) {
                continue;
            }
            if (state.armorModifierId != null) {
                AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
                if (armor != null) {
                    armor.removeModifier(state.armorModifierId);
                }
            }
            iterator.remove();
        }
        if (active.isEmpty()) {
            ACTIVE_SKILLS.remove(player.getUUID());
        }
    }

    private static FiercerBattlesCustomData.SkillEffectData effectOf(FiercerBattlesCustomData.AttackData attackData) {
        return attackData != null ? attackData.skillEffect : null;
    }

    private static String skillIdOf(FiercerBattlesCustomData.AttackData attackData) {
        return attackData != null && attackData.skillId != null && !attackData.skillId.isBlank()
                ? attackData.skillId
                : "unknown";
    }

    private static long secondsToTicks(double seconds) {
        return seconds > 0 ? Math.max(1, (long) Math.ceil(seconds * 20.0)) : 0;
    }

    private static ResourceLocation modifierId(String skillId) {
        return ResourceLocation.fromNamespaceAndPath(
                FiercerBattles.MOD_ID,
                "skill_effect/" + Integer.toUnsignedString(skillId.hashCode())
        );
    }

    private static final class ActiveSkill {
        private long expiresAt;
        private double damageBonus;
        private ResourceLocation armorModifierId;
    }

    private record PendingSkill(
            String skillId,
            FiercerBattlesCustomData.AttackData attackData,
            FiercerBattlesCustomData.SkillEffectData effect,
            long expiresAt
    ) {
    }
}
