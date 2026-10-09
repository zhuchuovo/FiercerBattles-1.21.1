package com.fiercerbattles.execution.client;

import com.fiercerbattles.FiercerBattlesExecutionClient;
import com.fiercerbattles.animation.core.AnimationClip;
import com.fiercerbattles.animation.core.AnimationPoseContext;
import com.fiercerbattles.parry.client.ParryRenderContext;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
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
        AnimationPoseContext.begin(new AnimationPoseContext.PoseSource() {
            @Override
            public AnimationClip.Transform sample(String channel, AnimationClip.Transform rest) {
                return frame.actor == null ? ParryRenderContext.sample(channel, rest) : transform(frame, channel, rest);
            }

            @Override
            public float blendProgress() {
                return frame.actor == null ? ParryRenderContext.ordinaryProgress() : ordinaryProgress(frame);
            }
        });
    }

    public static void end() {
        Deque<Frame> frames = FRAMES.get();
        if (!frames.isEmpty()) frames.pop();
        if (frames.isEmpty()) FRAMES.remove();
        AnimationPoseContext.end();
    }

    public static boolean isActive() {
        Frame frame = FRAMES.get().peek();
        return frame != null && frame.actor != null || ParryRenderContext.isActive();
    }

    public static float yaw() {
        Frame frame = FRAMES.get().peek();
        if (frame == null || frame.actor == null) return ParryRenderContext.yaw();
        return frame.actor.returning()
                ? Mth.rotLerp(frame.partial, frame.entity.yBodyRotO, frame.entity.yBodyRot)
                : frame.actor.renderYaw(frame.partial);
    }

    public static float returnProgress() {
        Frame frame = FRAMES.get().peek();
        return frame == null || frame.actor == null ? ParryRenderContext.returnProgress() : frame.actor.returnProgress(frame.partial);
    }

    private static float ordinaryProgress(Frame frame) {
        if (frame.actor == null) return 0;
        return frame.actor.returning() ? frame.actor.returnProgress(frame.partial)
                : 1 - frame.actor.approachProgress(frame.partial);
    }

    private static AnimationClip.Transform transform(Frame frame, String name, AnimationClip.Transform ordinaryPose) {
        if (name == null) return null;
        AnimationClip.Transform sampled = frame.actor.animation().sample(name, frame.tick);
        float progress = ordinaryProgress(frame);
        if (sampled == null || progress <= 0 && !frame.actor.returning()) return sampled;

        Vector3f position = new Vector3f(sampled.x(), sampled.y(), sampled.z());
        Quaternionf rotation = new Quaternionf(sampled.rotation());
        if (frame.actor.returning()) {
            // Transfer the captured final pose into the new gameplay root before fading.
            var geometry = frame.actor.action().geometry();
            Quaternionf rebase = new Quaternionf().rotationY(-frame.actor.returnYawDelta() * Mth.DEG_TO_RAD);
            position = rebase.transform(new Vector3f(
                    sampled.x() - (float) (geometry.executorEndRight() * 16),
                    sampled.y(), sampled.z() + (float) (geometry.executorEndForward() * 16)));
            rotation = new Quaternionf(rebase).mul(sampled.rotation());
        }
        float ordinaryScale = (frame.entity instanceof Player ? 0.9375F : 1.0F) * frame.entity.getScale();
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
