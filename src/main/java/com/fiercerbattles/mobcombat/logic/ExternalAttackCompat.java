package com.fiercerbattles.mobcombat.logic;

import com.fiercerbattles.mobcombat.BetterMobCombatReimagined;
import com.fiercerbattles.mobcombat.api.MobCombatState;
import com.fiercerbattles.mobcombat.config.BMCConfig;
import com.fiercerbattles.mobcombat.network.BMCNetwork;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


public final class ExternalAttackCompat {
    private static final Map<UUID, Long> LAST_VISUAL_TICK = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> CUSTOM_MELEE_OVERRIDE_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> LOGGED_BRIDGES = ConcurrentHashMap.newKeySet();
    private static final int DEDUPE_TICKS = 10;

    private ExternalAttackCompat() {
    }

    public static boolean hasCustomMeleeOverride(Mob mob) {
        return CUSTOM_MELEE_OVERRIDE_CACHE.computeIfAbsent(mob.getClass(), type -> {
            // getMethod() only sees public methods and can miss modded attack hooks declared deeper
            // in an NPC hierarchy. Walk declarations explicitly so MCA/MineColonies-style mobs are
            // correctly recognized even when they bypass Mob#doHurtTarget.
            for (Class<?> cursor = type; cursor != null && Mob.class.isAssignableFrom(cursor); cursor = cursor.getSuperclass()) {
                try {
                    Method method = cursor.getDeclaredMethod("doHurtTarget", Entity.class);
                    Class<?> owner = method.getDeclaringClass();
                    return owner != Mob.class && !owner.getName().startsWith("net.minecraft.");
                } catch (NoSuchMethodException ignored) {
                    // Keep walking toward Mob.
                } catch (RuntimeException ignored) {
                    return false;
                }
            }
            return false;
        });
    }


    public static boolean needsVisualBridge(Mob mob) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        if (id != null) {
            String namespace = id.getNamespace().replace("_", "").replace("-", "")
                    .toLowerCase(Locale.ROOT);
            String path = id.getPath().replace("_", "").replace("-", "")
                    .toLowerCase(Locale.ROOT);
            if (namespace.contains("undeadunleashed") && path.contains("dreadknight")) {
                return true;
            }
        }

        try {
            return hasCustomMeleeOverride(mob)
                    && (MobAttackSelector.hasCombatWeapon(mob)
                    || BMCConfig.ENABLE_FALLBACK_MELEE_ANIMATIONS.get());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static boolean handleSwing(Mob mob, InteractionHand requestedHand) {
        if (!needsVisualBridge(mob) || !play(mob, requestedHand, "swing")) {
            return false;
        }

        return true;
    }

    public static void handleSuccessfulMeleeDamage(Mob mob) {
        if (needsVisualBridge(mob) && !isRecent(mob)) {
            play(mob, InteractionHand.MAIN_HAND, "damage");
        }
    }

    /**
     * Last-resort compatibility bridge for NPC mods that neither call Mob#doHurtTarget nor emit a
     * vanilla swing at attack start. The damage event is necessarily late for windup timing, but it
     * guarantees a visible attack instead of a completely missing swing. Existing swing-triggered
     * animations are deduplicated.
     */
    public static void handleMeleeDamageAttempt(Mob mob) {
        MobCombatState state = (MobCombatState) mob;
        // Native BMC attacks reach the damage event while their pending attack is still active and
        // while invokeVanillaAttack has the re-entry guard set. Skip those so this bridge cannot
        // create a second animation. Anything that reaches here without those markers bypassed the
        // normal BMC attack path (common in NPC/citizen mods), so provide a visual fallback.
        if (state.bmc$getPendingAttack() != null || state.bmc$isCallingVanillaAttack() || isRecent(mob)) {
            return;
        }
        play(mob, InteractionHand.MAIN_HAND, "damage-event");
    }

    private static boolean play(Mob mob, InteractionHand requestedHand, String trigger) {
        if (!BMCConfig.ENABLED.get() || mob.level().isClientSide || !mob.isAlive()
                || BMCConfig.isBlacklisted(mob.getType())) {
            return false;
        }

        MobCombatState state = (MobCombatState) mob;
        if (state.bmc$getPendingAttack() != null || isRecent(mob)) {
            return false;
        }

        boolean selectedBetterCombatAnimation =
                playSelectedBetterCombatAnimation(mob, state.bmc$getComboCount(), requestedHand);
        if (!selectedBetterCombatAnimation) {
            ItemStack stack = requestedHand == InteractionHand.OFF_HAND
                    ? mob.getOffhandItem()
                    : mob.getMainHandItem();
            if (stack.getItem() instanceof ProjectileWeaponItem
                    || !BMCConfig.ENABLE_FALLBACK_MELEE_ANIMATIONS.get()) {
                return false;
            }
            BMCNetwork.sendAttackAnimation(
                    mob,
                    stack.isEmpty()
                            ? "bettercombat:one_handed_punch"
                            : "bettercombat:one_handed_slash_horizontal_right",
                    requestedHand == InteractionHand.OFF_HAND,
                    false,
                    12.0F,
                    0.5F,
                    0.08F
            );
        }

        LAST_VISUAL_TICK.put(mob.getUUID(), mob.level().getGameTime());
        String logKey = mob.getType() + "|" + mob.getClass().getName() + "|" + trigger
                + "|" + selectedBetterCombatAnimation;
        if (LOGGED_BRIDGES.add(logKey)) {
            BetterMobCombatReimagined.LOGGER.info(
                    "[BMC external-attack] mob={} class={} trigger={} selectedBetterCombatAnimation={}",
                    mob.getType(),
                    mob.getClass().getName(),
                    trigger,
                    selectedBetterCombatAnimation
            );
        }
        return true;
    }

    public static boolean isRecent(Mob mob) {
        Long tick = LAST_VISUAL_TICK.get(mob.getUUID());
        if (tick == null) {
            return false;
        }
        long age = mob.level().getGameTime() - tick;
        if (age >= DEDUPE_TICKS || age < 0L) {
            LAST_VISUAL_TICK.remove(mob.getUUID());
            return false;
        }
        return true;
    }

    private static boolean playSelectedBetterCombatAnimation(
            Mob mob,
            int comboCount,
            InteractionHand requestedHand
    ) {
        try {
            Method select = MobAttackSelector.class.getMethod("select", LivingEntity.class, int.class);
            Object hand = select.invoke(null, mob, comboCount);
            if (hand == null) {
                return false;
            }

            Method isOffHand = hand.getClass().getMethod("isOffHand");
            if (requestedHand == InteractionHand.OFF_HAND && !Boolean.TRUE.equals(isOffHand.invoke(hand))) {
                Object alternate = select.invoke(null, mob, comboCount + 1);
                if (alternate != null && Boolean.TRUE.equals(isOffHand.invoke(alternate))) {
                    hand = alternate;
                }
            }

            Object attack = hand.getClass().getMethod("attack").invoke(hand);
            Object attributes = hand.getClass().getMethod("attributes").invoke(hand);
            if (attack == null || attributes == null) {
                return false;
            }

            String animation = (String) attack.getClass().getMethod("animation").invoke(attack);
            if (animation == null || animation.isBlank()) {
                return false;
            }

            Class<?> attackHandClass = hand.getClass();
            float length = ((Number) MobCombatMath.class
                    .getMethod("attackDurationTicks", Mob.class, attackHandClass)
                    .invoke(null, mob, hand)).floatValue();
            float animationUpswing = ((Number) MobCombatMath.class
                    .getMethod("animationUpswing", attackHandClass)
                    .invoke(null, hand)).floatValue();
            float damageUpswing = ((Number) MobCombatMath.class
                    .getMethod("synchronizedDamageUpswing", Mob.class, attackHandClass)
                    .invoke(null, mob, hand)).floatValue();
            boolean offHand = Boolean.TRUE.equals(isOffHand.invoke(hand));
            boolean twoHanded = Boolean.TRUE.equals(attributes.getClass().getMethod("isTwoHanded").invoke(attributes));

            BMCNetwork.sendAttackAnimation(
                    mob,
                    animation,
                    offHand,
                    twoHanded,
                    length,
                    animationUpswing,
                    damageUpswing
            );
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }
}
