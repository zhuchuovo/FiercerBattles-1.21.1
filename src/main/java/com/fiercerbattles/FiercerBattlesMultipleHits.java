package com.fiercerbattles;

public final class FiercerBattlesMultipleHits {
    private static final ThreadLocal<Double> SERVER_DAMAGE_MULTIPLIER = new ThreadLocal<>();

    private FiercerBattlesMultipleHits() {
    }

    public static void beginServerHit(double multiplier) {
        SERVER_DAMAGE_MULTIPLIER.set(multiplier);
    }

    public static double getServerDamageMultiplier(double fallback) {
        Double multiplier = SERVER_DAMAGE_MULTIPLIER.get();
        return multiplier != null ? multiplier : fallback;
    }

    public static void clearServerHit() {
        SERVER_DAMAGE_MULTIPLIER.remove();
    }
}
