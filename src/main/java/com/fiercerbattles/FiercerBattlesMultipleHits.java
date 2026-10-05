package com.fiercerbattles;

import com.fiercerbattles.config.FiercerBattlesConfig;

/**
 * Per-request server-side hit state. Better Combat resolves one attack
 * request on the server thread; the values recorded here (per-frame damage
 * multiplier, the attack segment's own multiplier, flat fixed damage and
 * extra knockback) are consumed while that request's attack is resolved and
 * cleared when the request finishes.
 */
public final class FiercerBattlesMultipleHits {
    private static final ThreadLocal<Double> SERVER_DAMAGE_MULTIPLIER = new ThreadLocal<>();
    private static final ThreadLocal<Double> SERVER_ATTACK_MULTIPLIER = new ThreadLocal<>();
    private static final ThreadLocal<Double> SERVER_FIXED_DAMAGE = new ThreadLocal<>();
    private static final ThreadLocal<Double> SERVER_EXTRA_KNOCKBACK = new ThreadLocal<>();

    private FiercerBattlesMultipleHits() {
    }

    public static void beginServerHit(double multiplier) {
        SERVER_DAMAGE_MULTIPLIER.set(multiplier);
    }

    /**
     * Records the attack segment the current request resolves: its own
     * Better Combat damage multiplier (so a skill hit always uses the
     * skill's multiplier, never the normal attack the decoded combo happens
     * to select), plus fixed damage and extra knockback. Values are taken
     * from the segment data first, then from the global per-animation config
     * as a fallback. Both are consumed by the damage/knockback events and
     * the damage-multiplier redirect that fire while the request is
     * processed.
     */
    public static void beginServerHitState(
            FiercerBattlesCustomData.AttackData attackData,
            String animation
    ) {
        double fixedDamage = attackData != null && attackData.fixedDamage > 0
                ? attackData.fixedDamage
                : FiercerBattlesConfig.getFixedDamage(animation);
        double knockback = attackData != null && attackData.knockback > 0
                ? attackData.knockback
                : FiercerBattlesConfig.getKnockback(animation);
        double attackMultiplier = attackData != null && attackData.damageMultiplier > 0
                ? attackData.damageMultiplier
                : 0;
        SERVER_FIXED_DAMAGE.set(fixedDamage > 0 ? fixedDamage : null);
        SERVER_EXTRA_KNOCKBACK.set(knockback > 0 ? knockback : null);
        SERVER_ATTACK_MULTIPLIER.set(attackMultiplier > 0 ? attackMultiplier : null);
    }

    public static double getServerDamageMultiplier(double fallback) {
        // Per-frame multiple-hit multiplier wins, then the segment's own
        // recorded multiplier (for combat skills), then the attack object's
        // own value.
        Double multiplier = SERVER_DAMAGE_MULTIPLIER.get();
        if (multiplier != null) {
            return multiplier;
        }
        Double attackMultiplier = SERVER_ATTACK_MULTIPLIER.get();
        return attackMultiplier != null ? attackMultiplier : fallback;
    }

    /** Flat damage of the current attack segment, or 0 when none is set. */
    public static double getServerFixedDamage() {
        Double value = SERVER_FIXED_DAMAGE.get();
        return value != null ? value : 0.0;
    }

    /** Extra knockback (in blocks) of the current attack segment, or 0. */
    public static double getServerExtraKnockback() {
        Double value = SERVER_EXTRA_KNOCKBACK.get();
        return value != null ? value : 0.0;
    }

    public static void clearServerHit() {
        SERVER_DAMAGE_MULTIPLIER.remove();
        SERVER_ATTACK_MULTIPLIER.remove();
        SERVER_FIXED_DAMAGE.remove();
        SERVER_EXTRA_KNOCKBACK.remove();
    }

    /**
     * Resolves the custom attack data one attack request resolves to: the
     * combat skill's own segment data for a skill request (falling back to
     * the weapon's normal per-segment data on a client/server datapack
     * mismatch), or the normal weapon's per-segment data directly.
     */
    public static FiercerBattlesCustomData.AttackData resolveRequestCustomData(
            Object player,
            Object hand,
            int encodedComboCount
    ) {
        if (FiercerBattlesCombatSkills.isCombatSkillCombo(encodedComboCount)) {
            FiercerBattlesCustomData.AttackData skillData = FiercerBattlesCombatSkills
                    .getCombatSkillDataForRequest(hand, encodedComboCount, player);
            if (skillData != null) {
                return skillData;
            }
            return FiercerBattlesCustomData.get(
                    FiercerBattlesCombatSkills.itemIdForRequest(hand, encodedComboCount, player),
                    FiercerBattlesCombatSkills.attackIndexForRequest(hand, encodedComboCount, player)
            );
        }
        return FiercerBattlesCustomData.get(
                FiercerBattlesCombatSkills.itemIdForHand(hand),
                FiercerBattlesCombatSkills.attackIndex(hand)
        );
    }

    /**
     * Installs the full per-request hit state for the server-thread attack
     * task of one Better Combat attack request. Better Combat resolves
     * {@code getCurrentAttack} (where this state is normally recorded) on the
     * thread that handles the packet, while the attack task that consumes the
     * state runs on the server thread; rebuilding the state from the request's
     * own packet and attack hand at the start of that task keeps the per-frame
     * multiplier, fixed damage and knockback correct regardless of which
     * thread the resolution ran on.
     */
    public static void beginServerAttackTask(Object player, Object hand, int encodedComboCount) {
        beginServerAttackTask(resolveRequestCustomData(player, hand, encodedComboCount), hand, encodedComboCount);
    }

    /**
     * Same as {@link #beginServerAttackTask(Object, Object, int)} with the
     * request's custom data already resolved (the {@code getCurrentAttack}
     * resolution path resolves it anyway for the activation logic).
     */
    public static void beginServerAttackTask(
            FiercerBattlesCustomData.AttackData customData,
            Object hand,
            int encodedComboCount
    ) {
        String animation = animationName(hand);
        if (FiercerBattlesCombatSkills.isMultipleHitCombo(encodedComboCount)) {
            FiercerBattlesCustomData.MultipleHits multipleHits = FiercerBattlesCustomData.getMultipleHits(
                    customData,
                    animation
            );
            int multipleHitIndex = FiercerBattlesCombatSkills.getMultipleHitIndex(encodedComboCount);
            if (multipleHits != null && multipleHits.isValidIndex(multipleHitIndex)) {
                // A valid zero is intentional (a hit may deal no damage), so
                // only install the override when the packet maps to a real
                // configured hit.  For an unknown/stale packet keep Better
                // Combat's attack multiplier instead of turning the damage
                // modifier into -100%.
                beginServerHit(multipleHits.damageMultipliers[multipleHitIndex]);
            } else {
                clearServerHit();
            }
        }
        // Record the segment's own multiplier, flat damage and extra knockback
        // for the damage/knockback events that fire while this request
        // resolves. Runs after the multiple-hit block so its invalid-index
        // clear cannot wipe the freshly recorded values.
        beginServerHitState(customData, animation);
    }

    /** Animation id of the attack the given request hand resolves to, or "". */
    public static String animationName(Object hand) {
        if (hand == null) {
            return "";
        }
        try {
            Object attack = hand.getClass().getMethod("attack").invoke(hand);
            Object animation = attack != null ? attack.getClass().getMethod("animation").invoke(attack) : null;
            return animation instanceof String value ? value : "";
        } catch (ReflectiveOperationException ignored) {
            return "";
        }
    }
}
