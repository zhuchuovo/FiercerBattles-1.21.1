package com.fiercerbattles.locomotion.client;

import com.fiercerbattles.animation.core.AnimationClip;
import com.fiercerbattles.parry.client.ParryRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayDeque;
import java.util.Deque;

/** Renderer scope for locomotion clips; execution and parry take precedence when either is active. */
public final class LocomotionRenderContext {
    private record Frame(LivingEntity entity, LocomotionClient.Playback playback) {}
    private static final ThreadLocal<Deque<Frame>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);

    private LocomotionRenderContext() {}

    public static void begin(LivingEntity entity, float partial, boolean otherActionActive) {
        var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        boolean humanoid = renderer instanceof LivingEntityRenderer<?, ?> living && living.getModel() instanceof HumanoidModel<?>;
        FRAMES.get().push(new Frame(entity, otherActionActive || !humanoid ? null : LocomotionClient.playback(entity, partial)));
    }

    public static void end() {
        Deque<Frame> frames = FRAMES.get();
        if (!frames.isEmpty()) frames.pop();
        if (frames.isEmpty()) FRAMES.remove();
    }

    public static boolean isActive() { Frame frame = FRAMES.get().peek(); return frame != null && frame.playback != null; }
    public static float yaw() { Frame frame = FRAMES.get().peek(); return frame == null || frame.playback == null ? 0 : frame.playback.yaw(); }
    public static float ordinaryProgress() {
        Frame frame = FRAMES.get().peek();
        return frame == null || frame.playback == null ? 0 : frame.playback.ordinaryWeight();
    }

    public static AnimationClip.Transform sample(String channel, AnimationClip.Transform ordinary) {
        Frame frame = FRAMES.get().peek();
        if (frame == null || frame.playback == null) return null;
        AnimationClip.Transform sampled = frame.playback.sample(channel);
        if (sampled == null) return null;
        // Unlike paired executions, locomotion plays for long stretches (sprinting), so it keeps the
        // player's ordinary model size instead of the clip's authored scale 1.
        return ParryRenderContext.blendOrdinary(frame.entity,
                ParryRenderContext.scaledPose(sampled, ParryRenderContext.ordinaryScale(frame.entity)),
                ordinary, frame.playback.ordinaryWeight());
    }

    public static void clear() { FRAMES.remove(); }
}
