package com.fiercerbattles.boss.client;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.animation.core.AnimationClip;
import com.fiercerbattles.boss.ZombieLord;
import com.fiercerbattles.boss.ZombieLordAction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3f;

/**
 * The ghost blade's burning greatsword: flames along the blade, catching from the guard to the point over the ignition
 * and burning until the charged leap has landed. Points come from the greatsword channel of the ghost_blade clip,
 * so they follow the cuts.
 */
@EventBusSubscriber(modid = FiercerBattles.MOD_ID, value = Dist.CLIENT)
public final class GhostBladeFlames {
    /** The blade in the greatsword's own frame (model pixels): from just above the guard to the point. */
    private static final float BLADE_FROM = 6.0F;
    private static final float BLADE_TO = 29.0F;
    private static final int POINTS = 8;
    private static final double RANGE = 64.0;

    private GhostBladeFlames() {}

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null || minecraft.isPaused()) return;
        AnimationClip clip = ZombieLordAssets.clip(ZombieLordAction.GHOST_BLADE.clip);
        if (clip == null) return;
        for (ZombieLord lord : level.getEntitiesOfClass(ZombieLord.class, new AABB(minecraft.player.blockPosition()).inflate(RANGE))) {
            if (lord.isDeadOrDying() || lord.getAction() != ZombieLordAction.GHOST_BLADE) continue;
            double time = lord.actionTime(0.0F);
            if (time < ZombieLord.GB_IGNITE_FROM || time > ZombieLord.GB_FIRE_END) continue;
            float lit = (float) Mth.clamp((time - ZombieLord.GB_IGNITE_FROM) / (ZombieLord.GB_IGNITE_TO - ZombieLord.GB_IGNITE_FROM), 0.0, 1.0);
            burn(level, lord, clip.sample("ZL_greatsword", (float) time), lit);
        }
    }

    private static void burn(ClientLevel level, ZombieLord lord, AnimationClip.Transform sword, float lit) {
        RandomSource random = lord.getRandom();
        float yaw = lord.yBodyRot * Mth.DEG_TO_RAD;
        double forwardX = -Mth.sin(yaw), forwardZ = Mth.cos(yaw);
        double scale = ZombieLord.RENDER_SCALE / 16.0;
        float top = Mth.lerp(lit, BLADE_FROM, BLADE_TO);
        for (int i = 0; i < POINTS; i++) {
            float along = BLADE_FROM + (BLADE_TO - BLADE_FROM) * (i + random.nextFloat()) / POINTS;
            if (along > top) break;
            Vector3f point = sword.rotation().transform(new Vector3f(0.0F, 0.0F, along)).add(sword.x(), sword.y(), sword.z());
            // Model space (pixels, y down, feet at 24, -x right, -z forward) to the world.
            double right = -point.x() * scale, up = (24.0 - point.y()) * scale, forward = -point.z() * scale;
            double x = lord.getX() - forwardZ * right + forwardX * forward;
            double z = lord.getZ() + forwardX * right + forwardZ * forward;
            double y = lord.getY() + up;
            level.addParticle(i % 3 == 0 ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME, x, y, z,
                    (random.nextDouble() - 0.5) * 0.02, 0.02 + random.nextDouble() * 0.03, (random.nextDouble() - 0.5) * 0.02);
            if (random.nextInt(12) == 0) level.addParticle(ParticleTypes.LAVA, x, y, z, 0.0, 0.0, 0.0);
        }
    }
}
