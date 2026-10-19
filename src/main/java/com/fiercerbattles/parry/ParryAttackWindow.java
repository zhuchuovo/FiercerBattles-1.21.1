package com.fiercerbattles.parry;

/** An attack can be cancelled into a parry before its damage check or after its animation ends. */
public record ParryAttackWindow(long animationEnd, boolean damageChecked) {
    public static ParryAttackWindow start(long now, int animationTicks) {
        return new ParryAttackWindow(now + Math.max(1, animationTicks), false);
    }

    public ParryAttackWindow afterDamageCheck() {
        return new ParryAttackWindow(animationEnd, true);
    }

    public ParryAttackWindow afterAnimationEnd(long now) {
        return new ParryAttackWindow(Math.min(animationEnd, now), damageChecked);
    }

    public boolean allowsParry(long now) {
        return !damageChecked || now >= animationEnd;
    }
}
