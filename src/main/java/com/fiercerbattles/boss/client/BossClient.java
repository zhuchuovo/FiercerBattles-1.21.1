package com.fiercerbattles.boss.client;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.boss.BossEntities;
import com.fiercerbattles.boss.ZombieLord;
import com.fiercerbattles.boss.ZombieLordAction;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/** Renderers, client resources and the quake's camera tremor. */
public final class BossClient {
    private static final double TREMOR_RANGE = 24.0;
    private BossClient() {}

    @EventBusSubscriber(modid = FiercerBattles.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        private Setup() {}

        @SubscribeEvent
        public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(BossEntities.ZOMBIE_LORD.get(), ZombieLordRenderer::new);
            event.registerEntityRenderer(BossEntities.LORD_SKULL.get(), LordSkullRenderer::new);
            event.registerEntityRenderer(BossEntities.LORD_SKELETON.get(), LordSkeletonRenderer::new);
        }

        @SubscribeEvent
        public static void reloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener(new ZombieLordAssets());
        }
    }

    @EventBusSubscriber(modid = FiercerBattles.MOD_ID, value = Dist.CLIENT)
    public static final class Tremor {
        private Tremor() {}

        /** The ground shakes for two seconds after the sword is planted; stronger the closer the camera is. */
        @SubscribeEvent
        public static void cameraAngles(ViewportEvent.ComputeCameraAngles event) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null || minecraft.isPaused()) return;
            Vec3 camera = event.getCamera().getPosition();
            float partialTick = (float) event.getPartialTick();
            float strength = 0.0F;
            for (ZombieLord lord : minecraft.level.getEntitiesOfClass(ZombieLord.class,
                    new net.minecraft.world.phys.AABB(camera, camera).inflate(TREMOR_RANGE))) {
                if (lord.getAction() != ZombieLordAction.QUAKE) continue;
                double since = lord.actionTime(partialTick) - ZombieLord.QUAKE_PLANT_FRAME;
                if (since < 0 || since >= ZombieLord.TREMOR_TICKS) continue;
                float fade = (float) (1.0 - since / ZombieLord.TREMOR_TICKS);
                float near = (float) (1.0 - lord.position().distanceTo(camera) / TREMOR_RANGE);
                strength = Math.max(strength, Mth.clamp(near, 0.0F, 1.0F) * (0.35F + 0.65F * fade));
            }
            if (strength <= 0.0F) return;
            float time = (minecraft.level.getGameTime() % 100000L) + partialTick;
            event.setPitch(event.getPitch() + strength * 1.2F * Mth.sin(time * 2.9F) * Mth.cos(time * 1.7F));
            event.setRoll(event.getRoll() + strength * 1.6F * Mth.sin(time * 2.3F + 1.1F));
            event.setYaw(event.getYaw() + strength * 0.6F * Mth.cos(time * 3.7F));
        }
    }
}
