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
}
