package com.fiercerbattles.execution;

import com.fiercerbattles.FiercerBattlesExecutionClient;
import net.minecraft.client.model.geom.ModelPart;
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
        FRAMES.get().push(new Frame(actor, entity, actor == null ? 0 : actor.tick(partialTick), partialTick));
    }

    public static void end() {
        Deque<Frame> frames = FRAMES.get();
        if (!frames.isEmpty()) frames.pop();
        if (frames.isEmpty()) FRAMES.remove();
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

    public static ExecutionAnimation.Transform transform(String name) {
        return transform(name, null);
    }

    public static float returnProgress() {
        Frame frame = FRAMES.get().peek();
        return frame == null || frame.actor == null ? 0 : frame.actor.returnProgress(frame.partial);
    }

    public static ExecutionAnimation.Transform transform(String name, ModelPart ordinaryPose) {
        Frame frame = FRAMES.get().peek();
        if (frame == null || frame.actor == null || name == null) return null;
        ExecutionAnimation.Transform sampled = frame.actor.animation().sample(name, frame.tick);
        if (sampled == null || !frame.actor.returning()) return sampled;

        // Transfer the captured final pose into the new gameplay root before fading.
        Quaternionf rebase = new Quaternionf().rotationY(-frame.actor.returnYawDelta() * Mth.DEG_TO_RAD);
        Vector3f position = rebase.transform(new Vector3f(
                sampled.x() - (float) (ExecutionGeometry.EXECUTOR_END_LOCAL_RIGHT * 16),
                sampled.y(),
                sampled.z() + (float) (ExecutionGeometry.EXECUTOR_END_LOCAL_FORWARD * 16)));
        Quaternionf rotation = new Quaternionf(rebase).mul(sampled.rotation());
        float progress = frame.actor.returnProgress(frame.partial);
        float ordinaryScale = 0.9375F * frame.entity.getScale();
        float targetX = ordinaryPose == null ? 0 : ordinaryPose.x * ordinaryScale;
        float targetY = (ordinaryPose == null ? 0 : ordinaryPose.y * ordinaryScale)
                + 16 * 1.501F * (1 - ordinaryScale);
        float targetZ = ordinaryPose == null ? 0 : ordinaryPose.z * ordinaryScale;
        Quaternionf targetRotation = ordinaryPose == null ? new Quaternionf()
                : new Quaternionf().rotationZYX(ordinaryPose.zRot, ordinaryPose.yRot, ordinaryPose.xRot);
        return new ExecutionAnimation.Transform(
                Mth.lerp(progress, position.x, targetX), Mth.lerp(progress, position.y, targetY),
                Mth.lerp(progress, position.z, targetZ), rotation.slerp(targetRotation, progress),
                Mth.lerp(progress, 1, ordinaryScale));
    }

    public static void clear() {
        FRAMES.remove();
    }
}
