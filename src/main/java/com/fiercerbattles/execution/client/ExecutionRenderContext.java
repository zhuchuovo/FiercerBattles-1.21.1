package com.fiercerbattles.execution.client;

import com.fiercerbattles.FiercerBattlesExecutionClient;
import com.fiercerbattles.animation.core.AnimationClip;
import com.fiercerbattles.animation.core.AnimationPoseContext;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Deque;

/** Render-thread scope prevents an executor's pose leaking into another entity. */
public final class ExecutionRenderContext {
    private record Frame(FiercerBattlesExecutionClient.Actor actor, LivingEntity entity, float tick, float partial) {}
    private static final ThreadLocal<Deque<Frame>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);

    private ExecutionRenderContext() {}

    public static void begin(LivingEntity entity, float partialTick) {
        FiercerBattlesExecutionClient.Actor actor = FiercerBattlesExecutionClient.actor(entity);
        Frame frame = new Frame(actor, entity, actor == null ? 0 : actor.tick(partialTick), partialTick);
        FRAMES.get().push(frame);
        AnimationPoseContext.begin((channel, rest) -> frame.actor == null ? null : transform(frame, channel, rest));
    }

    public static void end() {
        Deque<Frame> frames = FRAMES.get();
        if (!frames.isEmpty()) frames.pop();
        if (frames.isEmpty()) FRAMES.remove();
        AnimationPoseContext.end();
    }

    public static boolean isActive() {
        Frame frame = FRAMES.get().peek();
        return frame != null && frame.actor != null;
    }

    public static float yaw() {
        Frame frame = FRAMES.get().peek();
        if (frame == null || frame.actor == null) return 0;
        return frame.actor.returning()
                ? Mth.rotLerp(frame.partial, frame.entity.yBodyRotO, frame.entity.yBodyRot) : frame.actor.yaw();
    }

    public static float returnProgress() {
        Frame frame = FRAMES.get().peek();
        return frame == null || frame.actor == null ? 0 : frame.actor.returnProgress(frame.partial);
    }

    private static AnimationClip.Transform transform(Frame frame, String name, AnimationClip.Transform ordinaryPose) {
        if (name == null) return null;
        AnimationClip.Transform sampled = frame.actor.animation().sample(name, frame.tick);
        if (sampled == null || !frame.actor.returning()) return sampled;

        // Transfer the captured final pose into the new gameplay root before fading.
        var geometry = frame.actor.action().geometry();
        Quaternionf rebase = new Quaternionf().rotationY(-frame.actor.returnYawDelta() * Mth.DEG_TO_RAD);
        Vector3f position = rebase.transform(new Vector3f(
                sampled.x() - (float) (geometry.executorEndRight() * 16),
                sampled.y(),
                sampled.z() + (float) (geometry.executorEndForward() * 16)));
        Quaternionf rotation = new Quaternionf(rebase).mul(sampled.rotation());
        float progress = frame.actor.returnProgress(frame.partial);
        float ordinaryScale = 0.9375F * frame.entity.getScale();
        float targetX = ordinaryPose.x() * ordinaryScale;
        float targetY = ordinaryPose.y() * ordinaryScale
                + 16 * 1.501F * (1 - ordinaryScale);
        float targetZ = ordinaryPose.z() * ordinaryScale;
        Quaternionf targetRotation = new Quaternionf(ordinaryPose.rotation());
        return new AnimationClip.Transform(
                Mth.lerp(progress, position.x, targetX), Mth.lerp(progress, position.y, targetY),
                Mth.lerp(progress, position.z, targetZ), rotation.slerp(targetRotation, progress),
                Mth.lerp(progress, 1, ordinaryScale));
    }

    public static void clear() {
        FRAMES.remove();
    }
}
