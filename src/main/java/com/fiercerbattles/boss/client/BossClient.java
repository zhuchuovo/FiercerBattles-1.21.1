package com.fiercerbattles.boss.client;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.boss.BossEntities;
import com.fiercerbattles.boss.ZombieLord;
import com.fiercerbattles.boss.ZombieLordAction;
import com.fiercerbattles.boss.ZombieLordGrip;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Renderers, client resources, the quake's camera tremor and the grabbed victim's camera and body. */
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

    /**
     * A grabbed victim rides the boss (see ZombieLord#heldPosition). The local player's camera goes to third person
     * for the hold, facing the boss, and back to what it was (first person, normally) once it is thrown; and every
     * held body swings about the boss's fist (ZombieLordGrip#tilt), flat on its back at the slam.
     */
    @EventBusSubscriber(modid = FiercerBattles.MOD_ID, value = Dist.CLIENT)
    public static final class Held {
        @Nullable private static CameraType cameraBefore;
        private static final Set<LivingEntity> TILTED = Collections.newSetFromMap(new IdentityHashMap<>());

        private Held() {}

        @SubscribeEvent
        public static void clientTick(ClientTickEvent.Post event) {
            Minecraft minecraft = Minecraft.getInstance();
            LocalPlayer player = minecraft.player;
            ZombieLord lord = player != null && player.getVehicle() instanceof ZombieLord held ? held : null;
            if (lord != null && cameraBefore == null) {
                cameraBefore = minecraft.options.getCameraType();
                minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                // Not the vanilla "press shift to dismount": sneaking cannot free the victim.
                minecraft.gui.setOverlayMessage(Component.empty(), false);
                float yaw = (float) (Mth.atan2(lord.getZ() - player.getZ(), lord.getX() - player.getX()) * Mth.RAD_TO_DEG) - 90.0F;
                player.setYRot(yaw);
                player.yRotO = yaw;
                player.setYHeadRot(yaw);
                player.setXRot(-15.0F);
                player.xRotO = -15.0F;
            } else if (lord == null && cameraBefore != null) {
                minecraft.options.setCameraType(cameraBefore);
                cameraBefore = null;
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void tilt(RenderLivingEvent.Pre<?, ?> event) {
            LivingEntity entity = event.getEntity();
            if (!(entity.getVehicle() instanceof ZombieLord lord) || lord.getAction() != ZombieLordAction.THROW) return;
            float partialTick = event.getPartialTick();
            float degrees = ZombieLordGrip.tilt(lord.actionTime(partialTick));
            if (Math.abs(degrees) < 0.01F) return;
            float yaw = Mth.rotLerp(partialTick, lord.yRotO, lord.getYRot()) * Mth.DEG_TO_RAD;
            double forwardX = -Mth.sin(yaw), forwardZ = Mth.cos(yaw);
            double depth = ZombieLordGrip.CHEST_DEPTH, height = entity.getBbHeight() * ZombieLordGrip.GRIP_HEIGHT;
            PoseStack pose = event.getPoseStack();
            pose.pushPose();
            TILTED.add(entity);
            // About the fist, around the boss's right axis.
            pose.translate(-forwardX * depth, height, -forwardZ * depth);
            pose.mulPose(new Quaternionf().rotationAxis(degrees * Mth.DEG_TO_RAD, (float) -forwardZ, 0.0F, (float) forwardX));
            pose.translate(forwardX * depth, -height, forwardZ * depth);
        }

        @SubscribeEvent
        public static void untilt(RenderLivingEvent.Post<?, ?> event) {
            if (TILTED.remove(event.getEntity())) event.getPoseStack().popPose();
        }
    }
}
