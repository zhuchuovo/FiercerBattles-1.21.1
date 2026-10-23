package com.fiercerbattles.boss.client;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.boss.ZombieLord;
import com.fiercerbattles.boss.ZombieLordAction;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The phase transition as a cutscene for every player near the boss: the view leaves the player's eyes for a camera
 * that moves through a set of shots around the boss and comes back at the end. The camera is a client-only, invisible
 * armour stand made the camera entity (its eye height is close to a player's, so vanilla's eye-height easing barely
 * shows when switching), so the player's own movement input is ignored meanwhile; the HUD is hidden.
 */
@EventBusSubscriber(modid = FiercerBattles.MOD_ID, value = Dist.CLIENT)
public final class TransitionCamera {
    /** Ticks to fly from the player's eyes to the first shot, and back from the last one. */
    private static final double ENTER_TICKS = 25.0;
    private static final double LEAVE_FROM = 150.0;
    /**
     * Shots in the boss's frame at the start: {tick, right, up, forward, height looked at on the boss's axis}.
     * Between two shots the camera eases from one to the next.
     */
    private static final double[][] SHOTS = {
            {25, -3.5, 2.6, 6.5, 2.0},    // medium shot, front left: he plants the sword and kneels
            {55, -1.6, 2.2, 5.6, 1.9},    // slow push in while he convulses
            {62, 2.8, 1.3, 4.2, 1.8},     // whip round to a low angle front right
            {92, 1.6, 1.5, 3.4, 1.9},     // rising, clutching his chest
            {98, 0.7, 2.0, 3.0, 2.1},     // close on the chest as it bursts
            {104, -1.2, 2.3, 6.5, 2.4},   // pulled back by the blast
            {112, -2.2, 0.5, 5.6, 3.0},   // low hero shot for the roar
            {150, -3.2, 0.8, 7.5, 2.8},   // drifting back
    };
    private static final int NO_LORD = -1;

    @Nullable private static ArmorStand camera;
    private static int lordId = NO_LORD;
    private static Vec3 origin = Vec3.ZERO;
    private static float originYaw;
    private static Vec3 eyeStart = Vec3.ZERO;
    private static float yawStart, pitchStart;
    private static boolean hideGuiBefore;
    private static CameraType cameraTypeBefore = CameraType.FIRST_PERSON;

    private TransitionCamera() {}

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            stop(minecraft);
            return;
        }
        if (camera == null) {
            ZombieLord lord = findTransition(level, player);
            if (lord != null) start(minecraft, player, lord);
            return;
        }
        ZombieLord lord = level.getEntity(lordId) instanceof ZombieLord found ? found : null;
        if (lord == null || camera.level() != level || lord.getAction() != ZombieLordAction.TRANSITION
                || lord.actionTime(0.0F) >= ZombieLord.TRANSITION_LENGTH) {
            stop(minecraft);
            return;
        }
        place(player, lord.actionTime(0.0F));
    }

    @Nullable
    private static ZombieLord findTransition(ClientLevel level, LocalPlayer player) {
        for (ZombieLord lord : level.getEntitiesOfClass(ZombieLord.class,
                new AABB(player.blockPosition()).inflate(ZombieLord.TRANSITION_AUDIENCE_RANGE))) {
            // Not for the last second: there would be no time to fly out and back.
            if (lord.getAction() == ZombieLordAction.TRANSITION && lord.actionTime(0.0F) < ZombieLord.TRANSITION_LENGTH - 20) return lord;
        }
        return null;
    }

    private static void start(Minecraft minecraft, LocalPlayer player, ZombieLord lord) {
        ArmorStand marker = EntityType.ARMOR_STAND.create(player.level());
        if (marker == null) return;
        marker.setInvisible(true);
        marker.setNoGravity(true);
        camera = marker;
        lordId = lord.getId();
        origin = lord.position();
        originYaw = lord.getYRot();
        eyeStart = player.getEyePosition();
        yawStart = player.getYRot();
        pitchStart = player.getXRot();
        hideGuiBefore = minecraft.options.hideGui;
        cameraTypeBefore = minecraft.options.getCameraType();
        marker.moveTo(eyeStart.x, eyeStart.y - marker.getEyeHeight(), eyeStart.z, yawStart, pitchStart);
        marker.setOldPosAndRot();
        minecraft.options.hideGui = true;
        minecraft.options.setCameraType(CameraType.FIRST_PERSON);
        minecraft.setCameraEntity(marker);
        place(player, lord.actionTime(0.0F));
    }

    private static void stop(Minecraft minecraft) {
        if (camera == null) return;
        camera = null;
        lordId = NO_LORD;
        minecraft.options.hideGui = hideGuiBefore;
        minecraft.options.setCameraType(cameraTypeBefore);
        if (minecraft.player != null) minecraft.setCameraEntity(minecraft.player);
    }

    /** Move the camera to where it should be at {@code time}; the frames until the next tick interpolate to it. */
    private static void place(LocalPlayer player, double time) {
        ArmorStand marker = camera;
        if (marker == null) return;
        Vec3 position;
        float yaw, pitch;
        double[] shot = shotAt(time);
        Vec3 shotPosition = toWorld(shot[0], shot[1], shot[2]);
        Vec3 look = toWorld(0.0, shot[3], 0.0);
        float shotYaw = yawTo(shotPosition, look), shotPitch = pitchTo(shotPosition, look);
        if (time < ENTER_TICKS) {
            double s = smooth(time / ENTER_TICKS);
            position = eyeStart.lerp(shotPosition, s);
            yaw = Mth.rotLerp((float) s, yawStart, shotYaw);
            pitch = Mth.lerp((float) s, pitchStart, shotPitch);
        } else if (time > LEAVE_FROM) {
            double s = smooth((time - LEAVE_FROM) / (ZombieLord.TRANSITION_LENGTH - LEAVE_FROM));
            position = shotPosition.lerp(player.getEyePosition(), s);
            yaw = Mth.rotLerp((float) s, shotYaw, player.getYRot());
            pitch = Mth.lerp((float) s, shotPitch, player.getXRot());
        } else {
            position = shotPosition;
            yaw = shotYaw;
            pitch = shotPitch;
        }
        marker.setOldPosAndRot();
        marker.setPos(position.subtract(0.0, marker.getEyeHeight(), 0.0));
        // Unwrapped, so the frames between ticks never interpolate the long way round.
        marker.setYRot(marker.getYRot() + Mth.wrapDegrees(yaw - marker.getYRot()));
        marker.setXRot(pitch);
    }

    /** {right, up, forward, look height} at {@code time}, eased between the two shots around it. */
    private static double[] shotAt(double time) {
        if (time <= SHOTS[0][0]) return tail(SHOTS[0]);
        for (int i = 1; i < SHOTS.length; i++) {
            if (time <= SHOTS[i][0]) {
                double s = smooth((time - SHOTS[i - 1][0]) / (SHOTS[i][0] - SHOTS[i - 1][0]));
                double[] out = new double[4];
                for (int j = 0; j < 4; j++) out[j] = Mth.lerp(s, SHOTS[i - 1][j + 1], SHOTS[i][j + 1]);
                return out;
            }
        }
        return tail(SHOTS[SHOTS.length - 1]);
    }

    private static double[] tail(double[] shot) {
        return new double[]{shot[1], shot[2], shot[3], shot[4]};
    }

    private static Vec3 toWorld(double right, double up, double forward) {
        float yaw = originYaw * Mth.DEG_TO_RAD;
        double forwardX = -Mth.sin(yaw), forwardZ = Mth.cos(yaw);
        return origin.add(-forwardZ * right + forwardX * forward, up, forwardX * right + forwardZ * forward);
    }

    private static float yawTo(Vec3 from, Vec3 to) {
        return (float) (Mth.atan2(to.z - from.z, to.x - from.x) * Mth.RAD_TO_DEG) - 90.0F;
    }

    private static float pitchTo(Vec3 from, Vec3 to) {
        double horizontal = Math.sqrt(Mth.square(to.x - from.x) + Mth.square(to.z - from.z));
        return (float) (-Mth.atan2(to.y - from.y, horizontal) * Mth.RAD_TO_DEG);
    }

    private static double smooth(double s) {
        s = Mth.clamp(s, 0.0, 1.0);
        return s * s * (3.0 - 2.0 * s);
    }
}
