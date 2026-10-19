package com.fiercerbattles.execution.client;

import com.fiercerbattles.FiercerBattlesExecutionClient;
import com.fiercerbattles.execution.ExecutionNetwork;
import com.fiercerbattles.parry.client.ParryClient;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/** Health-line hint using server rules and the entity shader's existing white overlay. */
public final class ExecutionHighlight {
    public static final int OVERLAY_WIDTH = 256;
    public static final int WHITE_U_START = 16;
    public static final int PERIOD_TICKS = 40;
    public static final float MAX_OPACITY = 0.2F;
    private static ExecutionNetwork.HighlightRules rules;

    private ExecutionHighlight() {}

    public static void acceptRules(ExecutionNetwork.HighlightRules payload) { rules = payload; }
    public static void clearRules() { rules = null; }

    public static boolean belowHealthLine(LivingEntity entity) {
        ExecutionNetwork.HighlightRules current = rules;
        if (current == null || !current.enabled() || !(entity instanceof Mob)
                || !entity.isAlive() || entity.isBaby() || entity.isInvulnerable()
                || FiercerBattlesExecutionClient.isExecuting(entity)
                || !current.humanoidEntities().contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString())) {
            return false;
        }
        boolean staggered = ParryClient.isStaggered(entity);
        if (!staggered && ParryClient.isBusy(entity)) return false;
        double threshold = staggered ? Math.max(current.healthThreshold(), current.staggerHealthThreshold())
                : current.healthThreshold();
        return entity.getMaxHealth() > 0 && entity.getHealth() < entity.getMaxHealth() * threshold;
    }

    public static float opacity(float tick) {
        return MAX_OPACITY * 0.5F * (1.0F - (float) Math.cos(tick * (2.0 * Math.PI / PERIOD_TICKS)));
    }

    public static int overlay(LivingEntity entity, float partialTick, int original) {
        // Keep hurt/death red and any renderer-specific white flash at their original strength.
        if ((original & 0xFFFF) != 0 || (original >>> 16) != OverlayTexture.WHITE_OVERLAY_V
                || entity.isInvisible() || !belowHealthLine(entity)) return original;
        float tick = entity.level().getGameTime() % PERIOD_TICKS + partialTick;
        int whiteU = WHITE_U_START + Math.round(opacity(tick) * 255.0F);
        return OverlayTexture.pack(whiteU, OverlayTexture.WHITE_OVERLAY_V);
    }
}
